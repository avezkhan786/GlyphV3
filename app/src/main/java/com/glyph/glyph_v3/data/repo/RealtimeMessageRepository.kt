package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.glyph.glyph_v3.BuildConfig
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.local.dao.ChatDao
import com.glyph.glyph_v3.data.local.dao.DeletedMessageDao
import com.glyph.glyph_v3.data.local.dao.MessageDao
import com.glyph.glyph_v3.data.local.entity.LocalChat
import com.glyph.glyph_v3.data.local.entity.LocalDeletedMessage
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MediaType
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.models.SelectedMediaItem
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.glyph.glyph_v3.data.service.ActiveChatManager
import com.glyph.glyph_v3.data.media.MediaStorageManager
import com.glyph.glyph_v3.util.ChatOpenTrace
import com.glyph.glyph_v3.util.StartupTrace
import com.glyph.glyph_v3.util.MediaCompressor
import com.glyph.glyph_v3.util.MediaEstimationUtil
import com.glyph.glyph_v3.ui.share.LinkPreviewResolver
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optimized message repository using Firebase Realtime Database for instant message delivery.
 * 
 * Architecture:
 * - RTDB: Used for real-time message relay (pending_messages/{recipientId}/{messageId})
 * - Firestore: Used for persistent chat history and user data
 * - Room: Local cache for offline access and fast UI updates
 */
class RealtimeMessageRepository(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val deletedMessageDao: DeletedMessageDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "RealtimeMessageRepo"
        private const val TRACE_TAG = "MessageTrace"
        private const val AUTH_PREFS_NAME = "auth_session_state"
        private const val KEY_LAST_AUTH_UID = "last_auth_uid"
        private const val PENDING_AUTH_SENDER_ID = "__pending_auth__"
        private const val DELETE_FOR_ALL_WINDOW_MS: Long = 48 * 60 * 60 * 1000 // 48 hours
        private const val EDIT_WINDOW_MS: Long = 60 * 60 * 1000 // 1 hour
        private const val INITIAL_CHAT_SYNC_LIMIT = 240L
        private const val CHAT_SYNC_OVERLAP_WINDOW_MS = 5 * 60 * 1000L
        private const val DELIVERY_RECONCILIATION_INTERVAL_MS = 20_000L
        private const val DELIVERY_RECONCILIATION_BATCH_SIZE = 25

        // Static tracking to prevent multiple listeners across repository instances
        private var globalIncomingMessageListener: ChildEventListener? = null
        private var globalIncomingMessageRef: DatabaseReference? = null
        
        private val globalDeliveryReceiptListeners = mutableMapOf<String, ChildEventListener>()
        private val globalDeliveryReceiptRefs = mutableMapOf<String, DatabaseReference>()

        private var globalAllDeliveryReceiptsListener: ChildEventListener? = null
        private var globalAllDeliveryReceiptsRef: DatabaseReference? = null
        private var globalGroupMetadataListener: com.google.firebase.firestore.ListenerRegistration? = null
        private var globalGroupMetadataListenerUid: String? = null
        private val startupPreviewRepairScheduled = AtomicBoolean(false)

        // Prefs key for soft-deleted group chats ("delete for me")
        private const val DELETED_GROUP_CHATS_PREFS = "glyph_deleted_group_chats"
        private const val KEY_DELETED_GROUP_IDS = "deleted_group_ids"
    }

    private val auth = FirebaseAuth.getInstance()
    private val rtdb = FirebaseDatabase.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
    private val storage = FirebaseStorage.getInstance()
    
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in repository scope", throwable)
    })

    private val authPrefs by lazy {
        context.applicationContext.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val chatSyncMutexes = ConcurrentHashMap<String, Mutex>()
    private val deliveryReconciliationMutex = Mutex()
    private var deliveryReceiptReconciliationJob: Job? = null

    /**
     * In-memory set of group chat IDs the current user has explicitly deleted ("delete for me").
     * Backed by SharedPreferences so it survives process restarts.
     * Prevents [syncGroupChatMetadata] and [fetchGroupAndCreateChat] from re-creating deleted rows.
     */
    private val deletedGroupChatIds: MutableSet<String> by lazy {
        val prefs = context.applicationContext
            .getSharedPreferences(DELETED_GROUP_CHATS_PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_DELETED_GROUP_IDS, emptySet()) ?: emptySet()
        java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        ).apply { addAll(saved) }
    }

    private fun persistDeletedGroupChatIds() {
        context.applicationContext
            .getSharedPreferences(DELETED_GROUP_CHATS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DELETED_GROUP_IDS, deletedGroupChatIds.toSet())
            .apply()
    }

    /**
     * Message IDs for which a reaction write is currently in-flight to Firestore.
     * Used to guard against a race condition where a stale Firestore snapshot (captured
     * before the local write was applied) bypasses the hasPendingWrites() filter and
     * overwrites the optimistic reaction removal with old Firestore data.
     */
    private val pendingReactionWrites = ConcurrentHashMap.newKeySet<String>()

    // Real-time status update events. Emitted after every successful outgoing-message
    // status write to Room so the UI can update status icons immediately — without
    // waiting for the Room flow → conflate → DiffUtil pipeline.
    // replay=64: buffers the last 64 events so a collector that starts late (e.g. on cold
    // start before repeatOnLifecycle(STARTED) fires) still receives recent status updates.
    private val _statusUpdateEvents = MutableSharedFlow<Pair<String, MessageStatus>>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val statusUpdateEvents: SharedFlow<Pair<String, MessageStatus>> = _statusUpdateEvents.asSharedFlow()

    val currentUserId get() = auth.currentUser?.uid

    // ==================== REACTIONS HELPERS ====================
    private val reactionsGson by lazy { Gson() }
    private val reactionsMapType =
        object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type

    /** Decode `{userId: emoji}` JSON to a Map. Returns empty on null/blank/parse failure. */
    private fun parseReactions(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            reactionsGson.fromJson<Map<String, String>>(json, reactionsMapType) ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    /** Encode reactions to JSON, or null when the map is empty. */
    private fun serializeReactions(map: Map<String, String>): String? {
        return if (map.isEmpty()) null else reactionsGson.toJson(map)
    }

    private fun normalizeChatPreviewText(text: String?): String {
        return text.orEmpty().trim().replace(Regex("\\s+"), " ")
    }

    private fun mergePreservingNonBlankText(incoming: String?, existing: String?): String {
        val incomingText = incoming?.trim().orEmpty()
        if (incomingText.isNotEmpty()) return incomingText
        val existingText = existing?.trim().orEmpty()
        if (existingText.isNotEmpty()) return existingText
        return ""
    }

    private fun buildBaseChatPreviewText(message: LocalMessage): String {
        if (message.isDeletedForAll) {
            return "🚫 This message was deleted"
        }

        val normalizedText = normalizeChatPreviewText(message.text)
        if (normalizedText.isNotEmpty()) {
            return normalizedText
        }

        return when {
            message.isVideoNote -> "Video note"
            message.type == MessageType.IMAGE -> "Photo"
            message.type == MessageType.VIDEO -> "Video"
            message.type == MessageType.AUDIO -> "Voice Message"
            message.type == MessageType.MEDIA_GROUP -> "Media"
            message.type == MessageType.GIF -> "GIF"
            message.type == MessageType.STICKER ||
                message.type == MessageType.KLIPY_EMOJI ||
                message.type == MessageType.MEME -> "Sticker"
            message.type == MessageType.DOCUMENT -> {
                normalizeChatPreviewText(message.documentCaption)
                    .takeIf { it.isNotEmpty() }
                    ?.let { "📄 $it" }
                    ?: "Document"
            }
            message.type == MessageType.CONTACT -> {
                normalizeChatPreviewText(message.contactName)
                    .takeIf { it.isNotEmpty() }
                    ?.let { "Contact: $it" }
                    ?: "Contact"
            }
            message.type == MessageType.STATUS_REPLY -> "Status"
            else -> "Message"
        }
    }

    private fun buildReactionTargetPreviewText(message: LocalMessage): String {
        val basePreview = buildBaseChatPreviewText(message)
        return if (normalizeChatPreviewText(message.text).isNotEmpty()) {
            "\"$basePreview\""
        } else {
            basePreview
        }
    }

    private fun buildReactionChatPreviewText(
        chat: LocalChat,
        message: LocalMessage,
        actorUserId: String,
        emoji: String
    ): String {
        val actorLabel = if (actorUserId == currentUserId) {
            "You"
        } else {
            ContactDisplayNameResolver.getDisplayName(
                otherUserId = actorUserId,
                remoteProfileName = chat.otherUsername,
                fallback = "Someone"
            )
        }
        return "$actorLabel reacted $emoji to ${buildReactionTargetPreviewText(message)}"
    }

    private fun findChangedReactionActorId(
        previous: Map<String, String>,
        next: Map<String, String>
    ): String? {
        val changedActors = (previous.keys + next.keys)
            .distinct()
            .filter { previous[it] != next[it] }
        if (changedActors.isEmpty()) return null
        return changedActors.firstOrNull { !next[it].isNullOrBlank() } ?: changedActors.first()
    }

    private fun isStoredReactionPreview(text: String, otherUsername: String): Boolean {
        if (text.startsWith("You reacted ")) return true
        val otherPrefix = otherUsername.ifBlank { "Someone" } + " reacted "
        return text.startsWith(otherPrefix)
    }

    private suspend fun syncChatPreviewForReactionChange(
        chatId: String,
        messageId: String,
        previous: Map<String, String>,
        next: Map<String, String>
    ) {
        val latest = messageDao.getLatestMessage(chatId) ?: return
        if (latest.id != messageId) return

        val chat = chatDao.getChatById(chatId) ?: return
        val changedActorId = findChangedReactionActorId(previous, next)
        val changedEmoji = changedActorId?.let(next::get)

        val previewText = if (!changedActorId.isNullOrBlank() && !changedEmoji.isNullOrBlank()) {
            buildReactionChatPreviewText(chat, latest, changedActorId, changedEmoji)
        } else {
            buildBaseChatPreviewText(latest)
        }

        chatDao.forceUpdateLastMessage(
            chatId,
            previewText,
            latest.timestamp,
            latest.senderId,
            latest.status.name
        )
    }

    /** Read the `reactions` map field from a Firestore document, sanitised to (String, String). */
    private fun extractReactionsFromDoc(
        doc: com.google.firebase.firestore.DocumentSnapshot
    ): Map<String, String> {
        val raw = doc.get("reactions") as? Map<*, *> ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        val result = HashMap<String, String>(raw.size)
        for ((k, v) in raw) {
            val uid = (k as? String)?.takeIf { it.isNotBlank() } ?: continue
            val emoji = (v as? String)?.takeIf { it.isNotBlank() } ?: continue
            result[uid] = emoji
        }
        return result
    }

    private fun localMessageToPreviewTarget(local: LocalMessage): Message {
        return Message(
            id = local.id,
            chatId = local.chatId,
            text = local.text,
            senderId = local.senderId,
            timestamp = local.timestamp,
            serverTimestamp = local.serverTimestamp,
            status = local.status,
            isIncoming = local.isIncoming,
            type = local.type,
            imageUrl = local.imageUrl,
            audioUrl = local.audioUrl,
            audioDuration = local.audioDuration,
            videoUrl = local.videoUrl,
            thumbnailUrl = local.thumbnailUrl,
            videoDuration = local.videoDuration,
            fileSize = local.fileSize,
            contactName = local.contactName,
            contactPhone = local.contactPhone,
            localUri = local.localUri,
            mediaWidth = local.mediaWidth,
            mediaHeight = local.mediaHeight,
            mediaItems = local.mediaItems,
            deliveredTimestamp = local.deliveredTimestamp,
            readTimestamp = local.readTimestamp,
            replyToMessageId = local.replyToMessageId,
            replyToText = local.replyToText,
            replyToSenderId = local.replyToSenderId,
            replyToType = local.replyToType?.let { MessageType.valueOf(it) },
            replyPreviewUrl = local.replyPreviewUrl,
            isEdited = local.isEdited,
            editedAt = local.editedAt,
            isDeletedForAll = local.isDeletedForAll,
            deletedAt = local.deletedAt,
            isVideoNote = local.isVideoNote,
            documentCaption = local.documentCaption,
            linkPreviewTitle = local.linkPreviewTitle,
            linkPreviewDomain = local.linkPreviewDomain,
            linkPreviewDescription = local.linkPreviewDescription,
            linkPreviewSiteName = local.linkPreviewSiteName,
            statusId = local.statusId,
            statusOwnerId = local.statusOwnerId,
            statusThumbnailUrl = local.statusThumbnailUrl,
            statusType = local.statusType,
            statusText = local.statusText,
            statusBgColor = local.statusBgColor,
            isForwarded = local.isForwarded,
            reactions = parseReactions(local.reactionsJson)
        )
    }

    private fun trace(stage: String, details: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TRACE_TAG, "$stage | $details")
        }
    }

    private fun summarizeUserId(userId: String?): String {
        if (userId.isNullOrBlank()) return "null"
        return userId.takeLast(6)
    }

    private fun traceKey(chatId: String, messageId: String): String {
        val chatSuffix = chatId.takeLast(6)
        val messagePrefix = messageId.take(8)
        return "$chatSuffix/$messagePrefix"
    }

    private fun summarizeLocalMessage(local: LocalMessage?): String {
        if (local == null) return "none"
        return "id=${local.id.take(8)} status=${local.status} incoming=${local.isIncoming} type=${local.type} ts=${local.timestamp}"
    }

    private fun schedulePendingSendDiagnostics(
        messageId: String,
        traceKey: String,
        startedAtElapsedMs: Long
    ) {
        if (!BuildConfig.DEBUG) return
        repositoryScope.launch {
            val checkpointsMs = listOf(1_000L, 5_000L, 15_000L)
            var previousDelayMs = 0L
            for (checkpointMs in checkpointsMs) {
                delay(checkpointMs - previousDelayMs)
                previousDelayMs = checkpointMs

                val local = messageDao.getMessageById(messageId) ?: run {
                    trace(
                        stage = "send_watchdog_missing",
                        details = "trace=$traceKey pendingFor=${SystemClock.elapsedRealtime() - startedAtElapsedMs}ms"
                    )
                    return@launch
                }

                if (local.status != MessageStatus.SENDING) {
                    trace(
                        stage = "send_watchdog_cleared",
                        details = "trace=$traceKey pendingFor=${SystemClock.elapsedRealtime() - startedAtElapsedMs}ms status=${local.status}"
                    )
                    return@launch
                }

                trace(
                    stage = "send_watchdog_pending",
                    details = "trace=$traceKey pendingFor=${SystemClock.elapsedRealtime() - startedAtElapsedMs}ms connected=${PresenceManager.isConnected.value} authCurrent=${summarizeUserId(currentUserId)} cachedAuth=${summarizeUserId(getCachedAuthUserId())} local=${summarizeLocalMessage(local)}"
                )
            }
        }
    }

    private fun getCachedAuthUserId(): String? = authPrefs.getString(KEY_LAST_AUTH_UID, null)

    private fun persistLastKnownAuthUserId(userId: String?) {
        authPrefs.edit().apply {
            if (userId.isNullOrBlank()) {
                remove(KEY_LAST_AUTH_UID)
            } else {
                putString(KEY_LAST_AUTH_UID, userId)
            }
        }.apply()
    }

    private suspend fun awaitAuthenticatedUserId(timeoutMs: Long = 5_000L): String? {
        // Fast path 1: Firebase Auth already has the user in memory (normal case).
        currentUserId?.let {
            persistLastKnownAuthUserId(it)
            return it
        }

        // Fast path 2: Use the SharedPreferences-cached UID from the previous session.
        //
        // Firebase Auth can take 1-3 s to surface its state to currentUser on cold start
        // (it must decrypt a stored token from the Android Keystore). During that window,
        // currentUserId is temporarily null even though the user IS authenticated.
        //
        // The cached UID lets the RTDB write proceed immediately.  The write itself is
        // still cryptographically authenticated by the Firebase SDK (it signs the WebSocket
        // handshake with the persisted session token), so this is NOT a security bypass —
        // it is identical to what the SDK would do internally if we called setValue()
        // before the AuthStateListener fires.
        getCachedAuthUserId()?.let { cached ->
            StartupTrace.logStage("repo_auth_cache_hit", "uid=$cached")
            return cached
        }

        // Slow path: wait for Auth to surface the user (handles post-logout re-login).
        StartupTrace.logStage("repo_auth_wait_start")
        val userId = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { continuation ->
                var authListener: FirebaseAuth.AuthStateListener? = null
                authListener = FirebaseAuth.AuthStateListener { authState ->
                    val readyUserId = authState.currentUser?.uid ?: return@AuthStateListener
                    authListener?.let { auth.removeAuthStateListener(it) }
                    if (continuation.isActive) {
                        continuation.resume(readyUserId)
                    }
                }

                auth.addAuthStateListener(authListener)
                continuation.invokeOnCancellation {
                    authListener?.let { auth.removeAuthStateListener(it) }
                }
            }
        }

        if (userId != null) {
            persistLastKnownAuthUserId(userId)
            StartupTrace.logStage("repo_auth_wait_end", "uid=$userId")
        } else {
            StartupTrace.logStage("repo_auth_wait_timeout")
        }
        return userId
    }

    private suspend fun awaitRealtimeDatabaseConnection(timeoutMs: Long): Boolean {
        val connectedRef = rtdb.reference.child(".info").child("connected")
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            val connected = snapshot.getValue(Boolean::class.java) == true
                            if (!connected) {
                                return
                            }

                            connectedRef.removeEventListener(this)
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onCancelled(error: DatabaseError) {
                            connectedRef.removeEventListener(this)
                            if (continuation.isActive) {
                                continuation.resumeWithException(error.toException())
                            }
                        }
                    }

                    connectedRef.addValueEventListener(listener)
                    continuation.invokeOnCancellation {
                        connectedRef.removeEventListener(listener)
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun warmRealtimeTransport(reason: String, waitForConnection: Boolean): Boolean {
        return runCatching {
            StartupTrace.logStage("repo_transport_warm_start", reason)
            rtdb.goOnline()
            // .info/connected is an internal Firebase path and does not support keepSynced().
            rtdb.reference.child("pending_messages").keepSynced(true)

            auth.currentUser?.let { user ->
                if (waitForConnection) {
                    withTimeoutOrNull(5_000L) {
                        user.getIdToken(false).await()
                    }
                } else {
                    user.getIdToken(false)
                        .addOnFailureListener { error ->
                            Log.w(TAG, "Best-effort RTDB auth warm-up failed for $reason", error)
                        }
                }
            }

            if (waitForConnection) {
                val connected = awaitRealtimeDatabaseConnection(timeoutMs = 4_000L)
                StartupTrace.logStage(
                    "repo_transport_warm_end",
                    "$reason connected=$connected"
                )
                connected
            } else {
                PresenceManager.isConnected.value
            }
        }.onFailure { error ->
            Log.w(TAG, "Best-effort RTDB/auth warm-up failed for $reason", error)
        }.getOrDefault(false)
    }

    private suspend fun aggressivelyReconnectRealtimeTransport(reason: String): Boolean {
        return runCatching {
            trace(stage = "transport_reconnect_start", details = "reason=$reason")
            // Use a cached token (no forced network refresh). getIdToken(false) returns
            // near-instantly when a valid token is in the Android Keystore cache.
            // getIdToken(true) was previously used here but it forces a network round-trip
            // (up to 5 s), which defeated the whole purpose of aggressive reconnection.
            auth.currentUser?.let { user ->
                withTimeoutOrNull(3_000L) {
                    user.getIdToken(false).await()
                }
            }

            // NOTE: goOffline/goOnline cycling removed — can cause "listen() called twice"
            // crash if removeEventListener round-trips are still in flight. goOnline() alone
            // is sufficient to force-reconnect a dormant WebSocket.
            rtdb.goOnline()
            rtdb.reference.child("pending_messages").keepSynced(true)

            val connected = awaitRealtimeDatabaseConnection(timeoutMs = 3_500L)
            trace(stage = "transport_reconnect_end", details = "reason=$reason connected=$connected")
            connected
        }.getOrElse { error ->
            Log.w(TAG, "Aggressive RTDB reconnect failed for $reason", error)
            trace(stage = "transport_reconnect_fail", details = "reason=$reason error=${error.message}")
            false
        }
    }

    private suspend fun ensureTransportReadyForSend(traceKey: String): Boolean {
        val connected = PresenceManager.isConnected.value || awaitRealtimeDatabaseConnection(timeoutMs = 1_500L)
        trace(stage = "send_transport_state", details = "trace=$traceKey connected=$connected")
        if (connected) {
            return true
        }

        trace(stage = "send_transport_reconnect_needed", details = "trace=$traceKey")
        return aggressivelyReconnectRealtimeTransport(reason = "send_$traceKey")
    }

    fun primeRealtimeTransportForForeground(reason: String) {
        repositoryScope.launch {
            val initialConnected = PresenceManager.isConnected.value
            trace(
                stage = "transport_prime_start",
                details = "reason=$reason connected=$initialConnected"
            )

            val connected = if (initialConnected) {
                warmRealtimeTransport(reason = "foreground_$reason", waitForConnection = false)
            } else {
                warmRealtimeTransport(reason = "foreground_$reason", waitForConnection = true)
            }

            val finalConnected = if (!connected && !PresenceManager.isConnected.value) {
                trace(
                    stage = "transport_prime_retry",
                    details = "reason=$reason"
                )
                aggressivelyReconnectRealtimeTransport(reason = "foreground_$reason")
            } else {
                connected || PresenceManager.isConnected.value
            }

            trace(
                stage = "transport_prime_end",
                details = "reason=$reason connected=$finalConnected"
            )
        }
    }
    
    private var incomingMessageListener: ChildEventListener?
        get() = globalIncomingMessageListener
        set(value) { globalIncomingMessageListener = value }

    private var incomingMessageRef: DatabaseReference?
        get() = globalIncomingMessageRef
        set(value) { globalIncomingMessageRef = value }

    private val deliveryReceiptListeners get() = globalDeliveryReceiptListeners
    private val deliveryReceiptRefs get() = globalDeliveryReceiptRefs

    private var allDeliveryReceiptsListener: ChildEventListener?
        get() = globalAllDeliveryReceiptsListener
        set(value) { globalAllDeliveryReceiptsListener = value }

    private var allDeliveryReceiptsRef: DatabaseReference?
        get() = globalAllDeliveryReceiptsRef
        set(value) { globalAllDeliveryReceiptsRef = value }

    // CRITICAL: Prevent redundant markChatAsRead calls with debouncing
    private val lastMarkAsReadTimestamps = mutableMapOf<String, Long>()
    private val MARK_AS_READ_DEBOUNCE_MS = 2000L // 2 seconds

    // Deterministic read-receipt buffering (never miss new messages during debounce)
    private val lastReadReceiptSent = mutableMapOf<String, Long>()
    private val pendingReadReceiptUpTo = mutableMapOf<String, Long>()
    private val pendingMarkReadJobs = mutableMapOf<String, Job>()
    private val markReadMutex = Mutex()

    // Cache latest read timestamp from the other user so late-arriving messages are upgraded instantly
    private val latestOtherReadTimestamp = mutableMapOf<String, Long>()
    private val readReceiptCacheMutex = Mutex()

    private fun outgoingStatusRank(status: MessageStatus): Int? {
        return when (status) {
            MessageStatus.FAILED -> -1
            MessageStatus.SENDING -> 0
            MessageStatus.SENT -> 1
            MessageStatus.DELIVERED -> 2
            MessageStatus.READ -> 3
            MessageStatus.PLAYED -> 4
            else -> null
        }
    }
    
    // CRITICAL: Check if status update should be allowed (monotonic - never downgrade)
    private fun shouldUpdateStatus(currentStatus: MessageStatus, newStatus: MessageStatus): Boolean {
        val currentRank = outgoingStatusRank(currentStatus) ?: return true
        val newRank = outgoingStatusRank(newStatus) ?: return true
        return newRank > currentRank // STRICT: Only allow upgrades
    }

    private fun parseOutgoingStatusRank(status: String): Int? {
        val parsed = runCatching { MessageStatus.valueOf(status) }.getOrNull() ?: return null
        return outgoingStatusRank(parsed)
    }

    private suspend fun updateOutgoingMessageStatusMonotonic(
        messageId: String,
        newStatus: MessageStatus,
        deliveredTimestamp: Long? = null,
        readTimestamp: Long? = null
    ) {
        val newRank = outgoingStatusRank(newStatus) ?: return
        val existing = messageDao.getMessageById(messageId) ?: return
        if (existing.isIncoming) return

        val currentRank = outgoingStatusRank(existing.status)
        // CRITICAL: Strict check - block ANY downgrade to prevent flickering
        if (currentRank != null && currentRank >= newRank) {
            trace(
                stage = "status_update_skipped",
                details = "messageId=${messageId.take(8)} chatId=${existing.chatId} current=${existing.status} requested=$newStatus"
            )
            return
        }

        trace(
            stage = "status_update_apply",
            details = "messageId=${messageId.take(8)} chatId=${existing.chatId} ${existing.status}->$newStatus age=${System.currentTimeMillis() - existing.timestamp}ms"
        )

        when (newStatus) {
            MessageStatus.DELIVERED -> {
                messageDao.updateMessageDelivered(
                    messageId,
                    newStatus,
                    deliveredTimestamp ?: System.currentTimeMillis()
                )
            }
            MessageStatus.READ,
            MessageStatus.PLAYED -> {
                messageDao.updateMessageReceiptState(
                    id = messageId,
                    status = newStatus,
                    deliveredTimestamp = deliveredTimestamp ?: existing.deliveredTimestamp,
                    readTimestamp = readTimestamp ?: System.currentTimeMillis()
                )
            }
            else -> {
                messageDao.updateMessageStatus(messageId, newStatus)
            }
        }

        // Notify the UI immediately so status icons update without waiting for the
        // Room flow → conflate → DiffUtil pipeline (which can delay or drop updates).
        _statusUpdateEvents.tryEmit(messageId to newStatus)

        if (newStatus == MessageStatus.SENT) {
            scheduleDeliveryReceiptReconciliation(reason = "message_sent", delayMs = 3_000L)
        }
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateMessageStatus(messageId, status)
    }

    private suspend fun updateChatLastMessageStatusMonotonic(
        chatId: String,
        senderId: String,
        timestamp: Long,
        messageText: String,
        newStatus: MessageStatus
    ) {
        val newRank = outgoingStatusRank(newStatus) ?: return
        val chat = chatDao.getChatById(chatId) ?: return

        // Only adjust status for the current chat's last message if it still matches this message.
        if (chat.lastMessageSenderId != senderId) return
        if (chat.lastMessageTimestamp != timestamp) return

        val currentRank = parseOutgoingStatusRank(chat.lastMessageStatus)
        if (currentRank != null && currentRank >= newRank) return

        chatDao.updateLastMessage(chatId, chat.lastMessage, chat.lastMessageTimestamp, chat.lastMessageSenderId, newStatus.name)
    }

    init {
        // Enable RTDB offline persistence for reliability
        try {
            rtdb.setPersistenceEnabled(true)
        } catch (e: Exception) {
            // Already enabled
        }
        
        // Repair chat previews once per process instead of once per repository instance.
        if (startupPreviewRepairScheduled.compareAndSet(false, true)) {
            repositoryScope.launch {
                try {
                    val chatIds = chatDao.getAllChatIds()
                    chatIds.forEach { chatId ->
                        refreshChatPreview(chatId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to repair chat previews", e)
                }
            }
        }
    }

    private suspend fun refreshChatPreview(chatId: String) {
        val latest = messageDao.getLatestMessage(chatId) ?: return // If no messages, do nothing (or clear?)
        
        val chat = chatDao.getChatById(chatId) ?: return
        
        // Safeguard: If the "latest" message in DB has 0 timestamp, assume valid fallback to fix UI 
        val safeTimestamp = if (latest.timestamp > 0) {
            latest.timestamp
        } else if (chat.lastMessageTimestamp > 0) {
            chat.lastMessageTimestamp
        } else {
            System.currentTimeMillis() 
        }

        // Only update if inconsistent or if we are repairing a 0-timestamp issue
        if (latest.timestamp != chat.lastMessageTimestamp || latest.senderId != chat.lastMessageSenderId || chat.lastMessageTimestamp == 0L) {
             val latestReactions = parseReactions(latest.reactionsJson)
             val previewText = when {
                 latestReactions.size == 1 -> {
                     val (actorUserId, emoji) = latestReactions.entries.first()
                     buildReactionChatPreviewText(chat, latest, actorUserId, emoji)
                 }
                 latestReactions.isNotEmpty() && isStoredReactionPreview(chat.lastMessage, chat.otherUsername) -> {
                     chat.lastMessage
                 }
                 else -> buildBaseChatPreviewText(latest)
             }
             
             chatDao.forceUpdateLastMessage(
                 chatId, 
                 previewText, 
                 safeTimestamp, 
                 latest.senderId, 
                 latest.status.name
             )
        }
    }


    // ==================== LOCAL CHATS ====================
    
    fun getLocalChats(): Flow<List<LocalChat>> {
        return chatDao.getAllChats()
    }

    suspend fun getLocalChatsOnce(): List<LocalChat> {
        return chatDao.getAllChatsOnce()
    }
    
    fun getArchivedChats(): Flow<List<LocalChat>> {
        return chatDao.getArchivedChats()
    }

    suspend fun getArchivedChatsOnce(): List<LocalChat> {
        return chatDao.getArchivedChatsOnce()
    }
    
    suspend fun archiveChats(chatIds: List<String>, isArchived: Boolean) {
        // Update local DB first
        chatIds.forEach { id ->
            chatDao.updateArchivedStatus(id, isArchived)
        }

        // IMMEDIATELY cancel any active notifications for these chats
        if (isArchived) {
            try {
                val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) 
                    as? android.app.NotificationManager
                
                chatIds.forEach { chatId ->
                    val notificationTag = "chat_$chatId"
                    val (msgId, summaryId) = com.glyph.glyph_v3.data.service.ChatNotificationHelper.notificationIdsForChat(chatId)
                    
                    notificationManager?.apply {
                        cancel(notificationTag, 0)
                        cancel(notificationTag, msgId)
                        cancel(notificationTag, summaryId)
                        cancel(msgId)
                        cancel(summaryId)
                    }
                    
                    // Clear from unread store
                    runCatching { 
                        com.glyph.glyph_v3.data.service.UnreadMessageStore.clearMessages(chatId)
                    }
                    
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling notifications during archive", e)
            }
        }

        // Sync to Firestore for server-side notification suppression
        // This is CRITICAL - if this fails, archived chats will still show notifications
        val uid = currentUserId
        if (uid.isNullOrBlank() || chatIds.isEmpty()) {
            Log.w(TAG, "Cannot sync archive status: uid=$uid, chatIds=${chatIds.size}")
            return
        }

        try {
            val batch = firestore.batch()
            val col = firestore.collection("users").document(uid).collection("archived_chats")

            chatIds.forEach { chatId ->
                val idsToSync = linkedSetOf(chatId)
                val local = runCatching { chatDao.getChatById(chatId) }.getOrNull()
                val parsedOtherUserId = deriveOtherUserIdFromChatId(chatId, uid)
                val otherUserId = local?.otherUserId?.takeIf { it.isNotBlank() } ?: parsedOtherUserId
                if (otherUserId.isNotBlank()) {
                    idsToSync.add("${uid}_${otherUserId}")
                    idsToSync.add("${otherUserId}_${uid}")
                    idsToSync.add(listOf(uid, otherUserId).sorted().joinToString("_"))
                }


                idsToSync.forEach { id ->
                    val ref = col.document(id)
                    if (isArchived) {
                        batch.set(
                            ref,
                            mapOf(
                                "archived" to true,
                                "chatId" to chatId,
                                "otherUserId" to otherUserId,
                                "updatedAt" to FieldValue.serverTimestamp()
                            ),
                            SetOptions.merge()
                        )
                    } else {
                        // Relay mode: delete the document entirely instead of setting archived=false.
                        // isChatArchivedForRecipient() treats a missing doc as "not archived" already.
                        batch.delete(ref)
                    }
                }

                if (!isArchived) {
                    val linkedDocs = runCatching {
                        col.whereEqualTo("chatId", chatId).get().await()
                    }.getOrNull()

                    linkedDocs?.documents
                        ?.map { it.reference }
                        ?.forEach { linkedRef ->
                            batch.delete(linkedRef)
                        }

                }
            }

            batch.commit().await()
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to sync archived_chats to Firestore - notifications may still show!", e)
            // Re-throw to let caller know sync failed
            throw e
        }
    }

    private fun deriveOtherUserIdFromChatId(chatId: String, currentUid: String): String {
        if (!chatId.contains("_")) return ""
        val parts = chatId.split("_")
        if (parts.size != 2) return ""
        return when {
            parts[0] == currentUid -> parts[1]
            parts[1] == currentUid -> parts[0]
            else -> ""
        }
    }

    suspend fun getOrCreateLocalChat(chatId: String, otherUserId: String, otherUsername: String, otherUserAvatar: String): LocalChat {
        val existing = chatDao.getChatById(chatId)
        if (existing != null) {
            if (existing.otherUsername != otherUsername || existing.otherUserAvatar != otherUserAvatar) {
                chatDao.updateUserInfo(chatId, otherUsername, otherUserAvatar)
            }
            return existing.copy(otherUsername = otherUsername, otherUserAvatar = otherUserAvatar)
        }
        
        val newChat = LocalChat(
            id = chatId,
            otherUserId = otherUserId,
            otherUsername = otherUsername,
            otherUserAvatar = otherUserAvatar
        )
        chatDao.insertChat(newChat)
        return newChat
    }

    suspend fun clearUnreadCount(chatId: String) {
        chatDao.clearUnreadCount(chatId)
    }
    
    /**
     * Delete entire chats ("Delete for me" — local only).
     * Removes all messages, the chat row, and cancels related notifications.
     * For group chats, records the deletion so new incoming messages cannot re-create the row.
     */
    suspend fun deleteChats(chatIds: List<String>) {
        if (chatIds.isEmpty()) return

        // 1. Remove all messages for each chat
        chatIds.forEach { chatId ->
            messageDao.deleteMessagesByChatId(chatId)
        }

        // 2. Remove the chat rows from local DB
        chatDao.deleteChats(chatIds)

        // 3. For group chats, remember the deletion so RTDB fan-out and metadata sync
        //    cannot re-create the row after it has been deleted.
        val groupIds = chatIds.filter {
            com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(it)
        }
        if (groupIds.isNotEmpty()) {
            deletedGroupChatIds.addAll(groupIds)
            persistDeletedGroupChatIds()
            // Write user-specific hidden flag to Firestore for cross-device persistence
            val uid = currentUserId
            if (uid != null) {
                repositoryScope.launch {
                    runCatching {
                        val batch = firestore.batch()
                        val hiddenCol = firestore
                            .collection("users").document(uid)
                            .collection("hidden_chats")
                        groupIds.forEach { chatId ->
                            batch.set(
                                hiddenCol.document(chatId),
                                mapOf(
                                    "chatId" to chatId,
                                    "hiddenAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                                ),
                                com.google.firebase.firestore.SetOptions.merge()
                            )
                        }
                        batch.commit().await()
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to persist deleted group chats to Firestore", e)
                    }
                }
            }
        }

        // 4. Cancel notifications for deleted chats
        try {
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager

            chatIds.forEach { chatId ->
                val notificationTag = "chat_$chatId"
                val (msgId, summaryId) = com.glyph.glyph_v3.data.service.ChatNotificationHelper.notificationIdsForChat(chatId)

                notificationManager?.apply {
                    cancel(notificationTag, 0)
                    cancel(notificationTag, msgId)
                    cancel(notificationTag, summaryId)
                    cancel(msgId)
                    cancel(summaryId)
                }

                runCatching {
                    com.glyph.glyph_v3.data.service.UnreadMessageStore.clearMessages(chatId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling notifications during chat deletion", e)
        }

        // 5. Remove draft text for deleted chats
        chatIds.forEach { chatId ->
            runCatching {
                com.glyph.glyph_v3.data.service.DraftMessageStore.clearDraft(chatId)
            }
        }

    }

    /**
     * Clear all messages in a chat ("Clear Chat" — WhatsApp-style).
     * 
     * This performs a comprehensive cleanup:
     * 1. Inserts tombstones for ALL messages so Firestore sync never re-inserts them
     * 2. Deletes all messages from local Room database
     * 3. Deletes all messages from Firestore (server-side cleanup)
     * 4. Deletes local media files (images, videos, voice notes)
     * 5. Clears in-memory caches (MessageCacheManager, Coil, etc.)
     * 6. Resets chat last-message in the chat list
     * 7. Clears unread count and notifications
     * 8. Deletes remote media objects from Firebase Storage (relay cleanup)
     * 9. Removes any leftover RTDB relay artifacts for this chat
     *
     * Runs entirely on [Dispatchers.IO] so the caller (Main-thread coroutine) never blocks.
     * Local steps run first and are durable; remote (Firestore/Storage/RTDB) cleanup is
     * best-effort so a network failure or interrupted deletion never leaves the chat
     * in a half-cleared state locally.
     */
    suspend fun clearChatMessages(chatId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 1. Get all message IDs before deletion (needed for tombstones)
        val allMessageIds = messageDao.getAllMessageIdsByChatId(chatId)

        // 2. Collect local media file paths for cleanup BEFORE deleting messages
        val localMediaPaths = mutableListOf<String>()
        for (id in allMessageIds) {
            val msg = messageDao.getMessageById(id)
            if (msg != null) {
                msg.localUri?.takeIf { it.isNotBlank() }?.let { localMediaPaths.add(it) }
                MessagePreviewCacheManager.evictMessagePreviews(localMessageToPreviewTarget(msg))
            }
        }

        // 3. Insert tombstones for ALL messages (bulk) — prevents Firestore sync from re-inserting
        if (allMessageIds.isNotEmpty()) {
            val tombstones = allMessageIds.map { id ->
                LocalDeletedMessage(id = id, chatId = chatId, deletedAt = now)
            }
            deletedMessageDao.insertDeletedMessages(tombstones)
        }

        // 4. Delete all messages from local Room DB
        messageDao.deleteMessagesByChatId(chatId)

        // 5. Delete messages from Firestore (server-side permanent removal)
        try {
            val messagesRef = firestore.collection("chats").document(chatId).collection("messages")
            val firestoreMessages = messagesRef.get().await()
            var batch = firestore.batch()
            var batchCount = 0
            for (doc in firestoreMessages.documents) {
                batch.delete(doc.reference)
                batchCount++
                // Firestore batch limit is 500; commit early and start a fresh batch
                if (batchCount >= 450) {
                    batch.commit().await()
                    batch = firestore.batch()
                    batchCount = 0
                }
            }
            if (batchCount > 0) {
                batch.commit().await()
            }
        } catch (e: Exception) {
            Log.e(TAG, "clearChatMessages: Firestore cleanup failed (messages are still cleared locally)", e)
        }

        // 6. Delete local media files
        for (path in localMediaPaths) {
            try {
                val cleanPath = if (path.startsWith("file://")) path.removePrefix("file://") else path
                val file = java.io.File(cleanPath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "clearChatMessages: failed to delete media file $path", e)
            }
        }

        // 6b. Delete the entire per-chat local media directory tree (downloaded incoming
        //     media, thumbnails, collage items, documents, voice notes). This catches files
        //     that were never tracked via localUri (e.g. received media downloaded on demand).
        try {
            MediaStorageManager.deleteAllMediaForChat(context, chatId)
        } catch (e: Exception) {
            Log.w(TAG, "clearChatMessages: local media directory cleanup failed", e)
        }

        // 7. Clear in-memory caches
        com.glyph.glyph_v3.utils.MessageCacheManager.clear(chatId)

        // 8. Reset chat last-message display in chat list
        try {
            chatDao.forceUpdateLastMessage(
                chatId = chatId,
                message = "",
                timestamp = now,
                senderId = "",
                status = "SENT"
            )
            chatDao.clearUnreadCount(chatId)
        } catch (e: Exception) {
            Log.w(TAG, "clearChatMessages: failed to update chat row", e)
        }

        // 9. Clear notifications and unread store
        try {
            com.glyph.glyph_v3.data.service.UnreadMessageStore.clearMessages(chatId)
            val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager
            val notificationTag = "chat_$chatId"
            val (msgId, summaryId) = com.glyph.glyph_v3.data.service.ChatNotificationHelper.notificationIdsForChat(chatId)
            notificationManager?.apply {
                cancel(notificationTag, 0)
                cancel(notificationTag, msgId)
                cancel(notificationTag, summaryId)
                cancel(msgId)
                cancel(summaryId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearChatMessages: notification cleanup failed", e)
        }

        // 10. Clear draft for this chat
        try {
            com.glyph.glyph_v3.data.service.DraftMessageStore.clearDraft(chatId)
        } catch (e: Exception) {
            Log.w(TAG, "clearChatMessages: draft cleanup failed", e)
        }

        // 11. Delete remote media from Firebase Storage (relay cleanup). All chat media is
        //     stored under chatId-scoped prefixes, so we recursively delete each prefix's
        //     {chatId} folder. Best-effort: a failure here never blocks the local clear.
        try {
            deleteChatStorageMedia(chatId)
        } catch (e: Exception) {
            Log.w(TAG, "clearChatMessages: Storage media cleanup failed", e)
        }

        // 12. Remove leftover RTDB relay artifacts for this chat. Delivery receipts destined
        //     for messages we sent in this chat live under delivery_receipts/{myUid}/{chatId}.
        //     These are transient and normally self-clean, but we purge them here so no
        //     conversation-related node lingers after a clear.
        try {
            currentUserId?.let { uid ->
                rtdb.reference
                    .child("delivery_receipts")
                    .child(uid)
                    .child(chatId)
                    .removeValue()
                    .addOnFailureListener { e ->
                        Log.w(TAG, "clearChatMessages: failed to remove RTDB delivery receipts for chat", e)
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearChatMessages: RTDB relay cleanup failed", e)
        }

    }

    /**
     * Recursively deletes every Firebase Storage object belonging to [chatId].
     *
     * All chat media is written under chatId-scoped prefixes:
     *   chat_media, chat_images, chat_videos, chat_video_thumbnails,
     *   chat_documents, chat_document_thumbnails, chat_voice  →  {prefix}/{chatId}/...
     *
     * Storage rules gate deletion on chat participation, so the current user can only
     * delete media for chats they belong to. Each prefix is walked via listAll() and every
     * item is deleted individually (Storage has no folder-delete primitive). Best-effort —
     * individual failures are logged and skipped.
     */
    private suspend fun deleteChatStorageMedia(chatId: String) {
        val prefixes = listOf(
            "chat_media",
            "chat_images",
            "chat_videos",
            "chat_video_thumbnails",
            "chat_documents",
            "chat_document_thumbnails",
            "chat_voice"
        )
        for (prefix in prefixes) {
            try {
                deleteStorageFolderRecursive(storage.reference.child("$prefix/$chatId"))
            } catch (e: Exception) {
                Log.w(TAG, "clearChatMessages: Storage cleanup failed for $prefix/$chatId", e)
            }
        }
    }

    /** Recursively lists and deletes all items (and sub-folders) under [folderRef]. */
    private suspend fun deleteStorageFolderRecursive(folderRef: StorageReference) {
        val listing = try {
            folderRef.listAll().await()
        } catch (e: Exception) {
            // Folder may not exist (no media of this type was ever sent) — nothing to do.
            return
        }
        for (item in listing.items) {
            try {
                item.delete().await()
            } catch (e: Exception) {
                Log.w(TAG, "clearChatMessages: failed to delete Storage object ${item.path}", e)
            }
        }
        for (subFolder in listing.prefixes) {
            deleteStorageFolderRecursive(subFolder)
        }
    }


    suspend fun deleteMessages(chatId: String, messageIds: List<String>) {
        // WhatsApp-style "Delete for me": remove locally only, never from Firestore.
        // Persist a local tombstone so Firestore sync does not re-insert after restart.
        val now = System.currentTimeMillis()
        messageDao.getMessagesByIds(messageIds)
            .forEach { local -> MessagePreviewCacheManager.evictMessagePreviews(localMessageToPreviewTarget(local)) }
        messageIds.forEach { id ->
            deletedMessageDao.insertDeletedMessage(LocalDeletedMessage(id = id, chatId = chatId, deletedAt = now))
            messageDao.deleteMessage(id)
        }
    }

    suspend fun deleteMediaGroupItemForMe(chatId: String, messageId: String, itemIndex: Int): Boolean {
        return deleteMediaGroupItemsForMe(chatId, messageId, listOf(itemIndex))
    }

    suspend fun deleteMediaGroupItemsForMe(chatId: String, messageId: String, itemIndices: List<Int>): Boolean {
        val local = messageDao.getMessageById(messageId) ?: return false
        if (local.chatId != chatId) return false

        if (local.type != MessageType.MEDIA_GROUP) {
            deleteMessages(chatId, listOf(messageId))
            return true
        }

        val items = parseMediaItemsOrEmpty(local.mediaItems).toMutableList()
        val indicesToRemove = itemIndices.distinct().filter { it in items.indices }.sortedDescending()
        if (indicesToRemove.isEmpty()) return false

        indicesToRemove.forEach { index -> items.removeAt(index) }
        MessagePreviewCacheManager.evictMessagePreviews(localMessageToPreviewTarget(local))

        if (items.isEmpty()) {
            deleteMessages(chatId, listOf(messageId))
        } else {
            messageDao.updateMessageMediaItems(messageId, Message.mediaItemsToJson(items))
        }
        return true
    }

    data class DeleteForAllResult(
        val deletedIds: List<String>,
        val rejected: Map<String, String>,
        val failureMessage: String? = null
    )

    suspend fun deleteMessagesForAll(chatId: String, messageIds: List<String>): DeleteForAllResult {
        val userId = currentUserId ?: return DeleteForAllResult(
            deletedIds = emptyList(),
            rejected = emptyMap(),
            failureMessage = "Not authenticated"
        )
        if (messageIds.isEmpty()) {
            return DeleteForAllResult(
                deletedIds = emptyList(),
                rejected = emptyMap(),
                failureMessage = "No messages selected"
            )
        }

        val now = System.currentTimeMillis()

        // Client-side eligibility check (must be re-validated server-side).
        // Batch load to avoid N individual Room queries.
        val localsById = messageDao.getMessagesByIds(messageIds).associateBy { it.id }
        val eligibleIds = messageIds.filter { id ->
            val local = localsById[id] ?: return@filter false
            !local.isIncoming &&
                !local.isDeletedForAll &&
                local.senderId == userId &&
                (now - local.timestamp) <= DELETE_FOR_ALL_WINDOW_MS
        }

        if (eligibleIds.isEmpty()) {
            return DeleteForAllResult(
                deletedIds = emptyList(),
                rejected = emptyMap(),
                failureMessage = "Not eligible for delete for all"
            )
        }

        // Optimistic local update (sender UI updates instantly).
        messageDao.markMessagesDeletedForAll(eligibleIds, now)
        localsById.values
            .filter { it.id in eligibleIds }
            .forEach { local -> MessagePreviewCacheManager.evictMessagePreviews(localMessageToPreviewTarget(local)) }

        // Server-side validation + propagation.
        return try {
            val payload = hashMapOf(
                "chatId" to chatId,
                "messageIds" to eligibleIds
            )
            val result = functions
                .getHttpsCallable("deleteMessageForAll")
                .call(payload)
                .await()

            val data = result.data as? Map<*, *>
            val results = data?.get("results") as? Map<*, *>

            val deletedIds = mutableListOf<String>()
            val rejected = linkedMapOf<String, String>()

            for (id in eligibleIds) {
                val r = results?.get(id) as? Map<*, *>
                val status = r?.get("status") as? String
                if (status == "deleted" || status == "already_deleted") {
                    deletedIds.add(id)
                } else {
                    rejected[id] = status ?: "unknown"
                }
            }

            if (rejected.isNotEmpty()) {
                for (id in rejected.keys) {
                    messageDao.updateDeletedForAllState(id, false, null)
                }
                Log.w(TAG, "deleteMessagesForAll partially rejected: $rejected")
            }

            DeleteForAllResult(
                deletedIds = deletedIds,
                rejected = rejected
            )
        } catch (e: Exception) {
            Log.e(TAG, "deleteMessagesForAll failed", e)
            // Revert optimistic state on hard failure.
            for (id in eligibleIds) {
                messageDao.updateDeletedForAllState(id, false, null)
            }

            val failureMessage = when (e) {
                is com.google.firebase.functions.FirebaseFunctionsException -> {
                    when (e.code) {
                        com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND ->
                            "Cloud Function deleteMessageForAll not found. Deploy functions to the 'glyphv3' project (or set the correct region)."
                        com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                            "Not authenticated. Please sign in again."
                        com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                            "Permission denied. Check Firebase rules / auth."
                        com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE ->
                            "Functions unavailable. Check network or emulator."
                        else -> "Delete for all failed (${e.code})."
                    }
                }
                else -> e.message ?: "Delete for all failed"
            }
            DeleteForAllResult(
                deletedIds = emptyList(),
                rejected = emptyMap(),
                failureMessage = failureMessage
            )
        }
    }

    // ==================== MESSAGES ====================
    
    // ==================== REACTIONS ====================

    /**
     * Toggle the current user's reaction on a message.
     *
     * - If the user already reacted with [emoji], the reaction is removed.
     * - If [emoji] is null, any existing reaction by the user is removed.
     * - Otherwise the user's reaction is set to [emoji] (replacing any prior emoji).
     *
     * The local Room row is updated optimistically (Firestore listener is the source of
     * truth and will reconcile any divergence). Firestore is updated using `FieldPath`
     * targeting `reactions.<uid>` so concurrent reactions from different users do not
     * collide.
     */
    suspend fun toggleReaction(chatId: String, messageId: String, emoji: String?) {
        val uid = currentUserId ?: awaitAuthenticatedUserId() ?: run {
            Log.w(TAG, "toggleReaction skipped — no authenticated user (chat=$chatId msg=$messageId)")
            return
        }

        val existing = messageDao.getMessageById(messageId) ?: run {
            Log.w(TAG, "toggleReaction skipped — message not found locally (id=$messageId)")
            return
        }
        val current = parseReactions(existing.reactionsJson).toMutableMap()
        val previous = current[uid]

        val removing = emoji == null || previous == emoji
        if (removing) {
            current.remove(uid)
        } else {
            current[uid] = emoji!!
        }
        val previousReactions = parseReactions(existing.reactionsJson)
        val nextReactions = current.toMap()
        val nextJson = serializeReactions(current)

        // Guard the Firestore listener against a race where a stale snapshot (captured
        // before this write reaches Firestore's local cache) bypasses hasPendingWrites()
        // and overwrites the optimistic removal with old data.
        pendingReactionWrites.add(messageId)
        messageDao.updateReactions(messageId, nextJson)
        syncChatPreviewForReactionChange(chatId, messageId, previousReactions, nextReactions)

        val docRef = firestore.collection("chats").document(chatId)
            .collection("messages").document(messageId)
        try {
            if (removing) {
                docRef.update("reactions.$uid", FieldValue.delete()).await()
            } else {
                docRef.set(
                    mapOf("reactions" to mapOf(uid to emoji)),
                    SetOptions.merge()
                ).await()
            }
        } catch (err: Exception) {
            Log.w(TAG, "toggleReaction Firestore update failed (chat=$chatId msg=$messageId)", err)
            // Roll back optimistic local change so the user sees the true state.
            messageDao.updateReactions(messageId, existing.reactionsJson)
            syncChatPreviewForReactionChange(chatId, messageId, nextReactions, previousReactions)
        } finally {
            pendingReactionWrites.remove(messageId)
        }
    }

    fun getMessages(chatId: String): Flow<List<Message>> {
        ChatOpenTrace.event(chatId, "room_flow_subscribe", "source=getMessages")
        return messageDao.getMessages(chatId).map { localList ->
            val latest = localList.lastOrNull()
            val latestAgeMs = latest?.let { System.currentTimeMillis() - it.timestamp } ?: -1L
            ChatOpenTrace.event(
                chatId,
                "room_flow_emit",
                "count=${localList.size} latest=${latest?.id?.take(8) ?: "none"} latestAge=${latestAgeMs}ms"
            )
            trace(
                stage = "room_emit",
                details = "chatId=$chatId count=${localList.size} latest=${summarizeLocalMessage(latest)} latestAge=${latestAgeMs}ms"
            )
            localList.map { local ->
                Message(
                    id = local.id,
                    chatId = local.chatId,
                    text = local.text,
                    senderId = local.senderId,
                    timestamp = local.timestamp,
                    serverTimestamp = local.serverTimestamp,
                    status = local.status,
                    isIncoming = local.isIncoming,
                    type = local.type,
                    imageUrl = local.imageUrl,
                    audioUrl = local.audioUrl,
                    audioDuration = local.audioDuration,
                    videoUrl = local.videoUrl,
                    thumbnailUrl = local.thumbnailUrl,
                    videoDuration = local.videoDuration,
                    fileSize = local.fileSize,
                    contactName = local.contactName,
                    contactPhone = local.contactPhone,
                    localUri = local.localUri,
                    mediaWidth = local.mediaWidth,
                    mediaHeight = local.mediaHeight,
                    mediaItems = local.mediaItems,
                    deliveredTimestamp = local.deliveredTimestamp,
                    readTimestamp = local.readTimestamp,
                    replyToMessageId = local.replyToMessageId,
                    replyToText = local.replyToText,
                    replyToSenderId = local.replyToSenderId,
                    replyToType = local.replyToType?.let { MessageType.valueOf(it) },
                    isEdited = local.isEdited,
                    editedAt = local.editedAt,
                    isDeletedForAll = local.isDeletedForAll,
                    deletedAt = local.deletedAt,
                    isVideoNote = local.isVideoNote,
                    documentCaption = local.documentCaption,
                    linkPreviewTitle = local.linkPreviewTitle,
                    linkPreviewDomain = local.linkPreviewDomain,
                    linkPreviewDescription = local.linkPreviewDescription,
                    linkPreviewSiteName = local.linkPreviewSiteName,
                    statusId = local.statusId,
                    statusOwnerId = local.statusOwnerId,
                    statusThumbnailUrl = local.statusThumbnailUrl,
                    statusType = local.statusType,
                    statusText = local.statusText,
                    statusBgColor = local.statusBgColor,
                    isForwarded = local.isForwarded,
                    reactions = parseReactions(local.reactionsJson)
                )
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Reactive windowed message stream for WhatsApp-style pagination.
     *
     * Emits the most recent [limit] messages (reversed to oldest -> newest) and keeps
     * emitting as the local DB changes. The ViewModel grows [limit] when the user
     * scrolls up so older history is paged in from Room without ever loading the
     * entire conversation into the adapter at once.
     */
    fun getRecentMessagesFlow(chatId: String, limit: Int): Flow<List<Message>> {
        return messageDao.getRecentMessagesFlow(chatId, limit).map { localList ->
            // DB returns newest-first; reverse for the UI (oldest -> newest).
            localList.asReversed().map { local -> local.toMessageModel() }
        }.flowOn(Dispatchers.Default)
    }

    /** Maps a Room [LocalMessage] row to the domain [Message] model. */
    private fun com.glyph.glyph_v3.data.local.entity.LocalMessage.toMessageModel(): Message {
        val local = this
        return Message(
            id = local.id,
            chatId = local.chatId,
            text = local.text,
            senderId = local.senderId,
            timestamp = local.timestamp,
            serverTimestamp = local.serverTimestamp,
            status = local.status,
            isIncoming = local.isIncoming,
            type = local.type,
            imageUrl = local.imageUrl,
            audioUrl = local.audioUrl,
            audioDuration = local.audioDuration,
            videoUrl = local.videoUrl,
            thumbnailUrl = local.thumbnailUrl,
            videoDuration = local.videoDuration,
            fileSize = local.fileSize,
            contactName = local.contactName,
            contactPhone = local.contactPhone,
            localUri = local.localUri,
            mediaWidth = local.mediaWidth,
            mediaHeight = local.mediaHeight,
            mediaItems = local.mediaItems,
            deliveredTimestamp = local.deliveredTimestamp,
            readTimestamp = local.readTimestamp,
            replyToMessageId = local.replyToMessageId,
            replyToText = local.replyToText,
            replyToSenderId = local.replyToSenderId,
            replyToType = local.replyToType?.let { MessageType.valueOf(it) },
            isEdited = local.isEdited,
            editedAt = local.editedAt,
            isDeletedForAll = local.isDeletedForAll,
            deletedAt = local.deletedAt,
            isVideoNote = local.isVideoNote,
            documentCaption = local.documentCaption,
            linkPreviewTitle = local.linkPreviewTitle,
            linkPreviewDomain = local.linkPreviewDomain,
            linkPreviewDescription = local.linkPreviewDescription,
            linkPreviewSiteName = local.linkPreviewSiteName,
            statusId = local.statusId,
            statusOwnerId = local.statusOwnerId,
            statusThumbnailUrl = local.statusThumbnailUrl,
            statusType = local.statusType,
            statusText = local.statusText,
            statusBgColor = local.statusBgColor,
            isForwarded = local.isForwarded,
            reactions = parseReactions(local.reactionsJson)
        )
    }

    suspend fun getRecentMessages(chatId: String, limit: Int): List<Message> {
        // Query newest-first for speed, then reverse for UI (oldest -> newest).
        val startedAt = SystemClock.elapsedRealtime()
        ChatOpenTrace.event(chatId, "room_recent_query_start", "limit=$limit")
        val recent = messageDao.getRecentMessages(chatId, limit)
        ChatOpenTrace.event(
            chatId,
            "room_recent_query_end",
            "limit=$limit count=${recent.size} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
        return recent.asReversed().map { local ->
            Message(
                id = local.id,
                chatId = local.chatId,
                text = local.text,
                senderId = local.senderId,
                timestamp = local.timestamp,
                serverTimestamp = local.serverTimestamp,
                status = local.status,
                isIncoming = local.isIncoming,
                type = local.type,
                imageUrl = local.imageUrl,
                audioUrl = local.audioUrl,
                audioDuration = local.audioDuration,
                videoUrl = local.videoUrl,
                thumbnailUrl = local.thumbnailUrl,
                videoDuration = local.videoDuration,
                fileSize = local.fileSize,
                contactName = local.contactName,
                contactPhone = local.contactPhone,
                localUri = local.localUri,
                mediaWidth = local.mediaWidth,
                mediaHeight = local.mediaHeight,
                mediaItems = local.mediaItems,
                deliveredTimestamp = local.deliveredTimestamp,
                readTimestamp = local.readTimestamp,
                replyToMessageId = local.replyToMessageId,
                replyToText = local.replyToText,
                replyToSenderId = local.replyToSenderId,
                replyToType = local.replyToType?.let { MessageType.valueOf(it) },
                isEdited = local.isEdited,
                editedAt = local.editedAt,
                isDeletedForAll = local.isDeletedForAll,
                deletedAt = local.deletedAt,
                isVideoNote = local.isVideoNote,
                documentCaption = local.documentCaption,
                linkPreviewTitle = local.linkPreviewTitle,
                linkPreviewDomain = local.linkPreviewDomain,
                linkPreviewDescription = local.linkPreviewDescription,
                linkPreviewSiteName = local.linkPreviewSiteName,
                statusId = local.statusId,
                statusOwnerId = local.statusOwnerId,
                statusThumbnailUrl = local.statusThumbnailUrl,
                statusType = local.statusType,
                statusText = local.statusText,
                statusBgColor = local.statusBgColor,
                isForwarded = local.isForwarded,
                reactions = parseReactions(local.reactionsJson)
            )
        }
    }

    suspend fun getLatestLocalMessageTimestamp(chatId: String): Long {
        return messageDao.getLatestMessageTimestamp(chatId) ?: 0L
    }

    fun persistMediaDimensions(messageId: String, mediaWidth: Int, mediaHeight: Int) {
        if (mediaWidth <= 0 || mediaHeight <= 0) return
        repositoryScope.launch {
            val existing = messageDao.getMessageById(messageId) ?: return@launch
            if (existing.mediaWidth == mediaWidth && existing.mediaHeight == mediaHeight) return@launch
            messageDao.updateMediaDimensions(messageId, mediaWidth, mediaHeight)
            trace(
                stage = "media_dimensions_persisted",
                details = "id=${messageId.take(8)} width=$mediaWidth height=$mediaHeight oldW=${existing.mediaWidth} oldH=${existing.mediaHeight}"
            )
        }
    }

    // ==================== SEND KLIPY MEDIA (GIF / STICKER) ====================

    /**
     * Send a Klipy media item (GIF, sticker, or AI emoji) as a message.
     * No upload needed — the media is a remote URL from Klipy's CDN.
     */
    suspend fun sendKlipyMediaMessage(
        chatId: String,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        type: MessageType,
        title: String,
        imageUrl: String,
        previewUrl: String?,
        mediaWidth: Int,
        mediaHeight: Int,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val displayText = when (type) {
            MessageType.GIF -> "GIF"
            MessageType.STICKER -> "Sticker"
            MessageType.MEME -> "Meme"
            else -> title
        }

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        // 1. Save to local DB immediately
        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = displayText,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = type,
            imageUrl = imageUrl,
            thumbnailUrl = previewUrl,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl
        )
        messageDao.insertMessage(localMessage)
        chatDao.updateLastMessage(chatId, displayText, timestamp, userId, MessageStatus.SENDING.name)

        // 2. Send via RTDB
        val messageData = mutableMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to displayText,
            "senderId" to userId,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to type.name,
            "imageUrl" to imageUrl
        )
        previewUrl?.let { messageData["thumbnailUrl"] = it }
        if (mediaWidth > 0) messageData["mediaWidth"] = mediaWidth
        if (mediaHeight > 0) messageData["mediaHeight"] = mediaHeight

        replyToMessageId?.let { messageData["replyToMessageId"] = it }
        replyToText?.let { messageData["replyToText"] = it }
        replyToSenderId?.let { messageData["replyToSenderId"] = it }
        replyToType?.let { messageData["replyToType"] = it.name }
        replyPreviewUrl?.let { messageData["replyPreviewUrl"] = it }

        try {
            persistToFirestoreAwait(
                chatId = chatId,
                messageId = messageId,
                text = displayText,
                senderId = userId,
                otherUserId = otherUserId,
                timestamp = timestamp,
                type = type.name,
                imageUrl = imageUrl,
                thumbnailUrl = previewUrl,
                mediaWidth = mediaWidth,
                mediaHeight = mediaHeight,
                replyToMessageId = replyToMessageId,
                replyToText = replyToText,
                replyToSenderId = replyToSenderId,
                replyToType = replyToType?.name,
                replyPreviewUrl = replyPreviewUrl
            )
            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, displayText, MessageStatus.SENT)

            rtdb.reference
                .child("pending_messages")
                .child(otherUserId)
                .child(messageId)
                .setValue(messageData)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Direct RTDB Klipy push failed after Firestore persist for $messageId", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending Klipy media", e)
            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
        }
    }

    // ==================== SEND MESSAGE (RTDB) ====================
    
    suspend fun sendMessage(
        chatId: String, 
        text: String, 
        otherUserId: String, 
        otherUsername: String, 
        otherUserAvatar: String,
        previewThumbnailUrl: String? = null,
        previewTitle: String? = null,
        previewDomain: String? = null,
        previewDescription: String? = null,
        previewSiteName: String? = null,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null,
        clientTimestamp: Long? = null
    ) {
        StartupTrace.logStage("send_message_requested", "chatId=$chatId")
        startDeliveryReceiptListener(chatId, forceRestart = true)
        val messageId = UUID.randomUUID().toString()
        val timestamp = clientTimestamp ?: System.currentTimeMillis()
        val traceKey = traceKey(chatId, messageId)
        val startElapsed = SystemClock.elapsedRealtime()
        val optimisticSenderId = currentUserId ?: getCachedAuthUserId() ?: PENDING_AUTH_SENDER_ID
        trace(
            stage = "send_begin",
            details = "trace=$traceKey chatId=$chatId otherUserId=$otherUserId textLen=${text.length} optimisticSender=${summarizeUserId(optimisticSenderId)} authCurrent=${summarizeUserId(currentUserId)} cachedAuth=${summarizeUserId(getCachedAuthUserId())} connected=${PresenceManager.isConnected.value}"
        )

        // Ensure local chat exists
        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        // 1. Save to Local DB immediately (SENDING status)
        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = text,
            senderId = optimisticSenderId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.TEXT,
            thumbnailUrl = previewThumbnailUrl,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl,
            linkPreviewTitle = previewTitle,
            linkPreviewDomain = previewDomain,
            linkPreviewDescription = previewDescription,
            linkPreviewSiteName = previewSiteName
        )
        messageDao.insertMessage(localMessage)
        chatDao.updateLastMessage(chatId, text, timestamp, localMessage.senderId, MessageStatus.SENDING.name)
        LinkPreviewResolver.extractFirstUrl(text)?.let { previewUrl ->
            MessagePreviewCacheManager.warmLinkPreviewAsync(context.applicationContext, previewUrl, previewThumbnailUrl)
        }
        trace(
            stage = "send_local_inserted",
            details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms status=${localMessage.status} messageId=${messageId.take(8)}"
        )
        StartupTrace.logStage("send_message_local_enqueued", "chatId=$chatId messageId=$messageId")
        schedulePendingSendDiagnostics(
            messageId = messageId,
            traceKey = traceKey,
            startedAtElapsedMs = startElapsed
        )

        warmRealtimeTransport(reason = "send_message", waitForConnection = false)

        val userId = awaitAuthenticatedUserId() ?: run {
            trace(
                stage = "send_auth_timeout",
                details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms"
            )
            Log.e(TAG, "Cannot send message because auth user is unavailable after timeout")
            repositoryScope.launch {
                updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
                updateChatLastMessageStatusMonotonic(chatId, localMessage.senderId, timestamp, text, MessageStatus.FAILED)
            }
            return
        }
        persistLastKnownAuthUserId(userId)
        trace(
            stage = "send_auth_ready",
            details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms userId=${summarizeUserId(userId)}"
        )

        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to text,
            "senderId" to userId,
            "timestamp" to timestamp,
            "serverTimestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "TEXT"
        )
        previewThumbnailUrl?.let { firestoreMessageData["thumbnailUrl"] = it }
        previewTitle?.let { firestoreMessageData["linkPreviewTitle"] = it }
        previewDomain?.let { firestoreMessageData["linkPreviewDomain"] = it }
        previewDescription?.let { firestoreMessageData["linkPreviewDescription"] = it }
        previewSiteName?.let { firestoreMessageData["linkPreviewSiteName"] = it }
        replyToMessageId?.let { firestoreMessageData["replyToMessageId"] = it }
        replyToText?.let { firestoreMessageData["replyToText"] = it }
        replyToSenderId?.let { firestoreMessageData["replyToSenderId"] = it }
        replyToType?.let { firestoreMessageData["replyToType"] = it.name }
        replyPreviewUrl?.let { firestoreMessageData["replyPreviewUrl"] = it }

        val firestoreChatData = hashMapOf<String, Any>(
            "participants" to listOf(userId, otherUserId),
            "lastMessage" to text,
            "lastMessageTimestamp" to timestamp,
            "lastMessageSenderId" to userId
        )

        trace(
            stage = "send_firestore_batch_start",
            details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms"
        )
        val sendBatch = firestore.batch()
        sendBatch.set(
            firestore.collection("chats").document(chatId),
            firestoreChatData,
            SetOptions.merge()
        )
        sendBatch.set(
            firestore.collection("chats").document(chatId).collection("messages").document(messageId),
            firestoreMessageData,
            SetOptions.merge()
        )
        sendBatch.commit()
            .addOnSuccessListener {
                trace(
                    stage = "send_firestore_batch_ack",
                    details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms"
                )
                // After the batch commit is ACK'd by Firestore, the SDK's local cache has
                // the server-resolved serverTimestamp for our message document. Reading from
                // Source.CACHE gives it instantly (no extra network round-trip) and lets us
                // update Room DB immediately. This collapses the sender's own serverTimestamp
                // gap from ~Firestore-sync-latency (300-800 ms) to ~0 ms, which is critical
                // for consistent ordering when two devices send messages simultaneously.
                firestore.collection("chats").document(chatId)
                    .collection("messages").document(messageId)
                    .get(com.google.firebase.firestore.Source.CACHE)
                    .addOnSuccessListener { cachedDoc ->
                        val resolvedTs: Long? = when (val sts = cachedDoc?.get("serverTimestamp")) {
                            is Number -> sts.toLong()
                            is com.google.firebase.Timestamp -> sts.toDate().time
                            else -> null
                        }
                        if (resolvedTs != null) {
                            android.util.Log.d("MsgOrder", "[SEND_TS] msgId=${messageId.takeLast(6)} fsTs=$resolvedTs clientTs=$timestamp diff=${resolvedTs - timestamp}ms")
                            repositoryScope.launch {
                                messageDao.updateServerTimestamp(messageId, resolvedTs)
                                trace(
                                    stage = "send_server_ts_cache_hit",
                                    details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms ts=$resolvedTs"
                                )
                            }
                        } else {
                            android.util.Log.w("MsgOrder", "[SEND_TS_MISS] msgId=${messageId.takeLast(6)} serverTimestamp not in cache yet — Firestore sync will backfill")
                        }
                    }
                repositoryScope.launch {
                    updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
                    updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, text, MessageStatus.SENT)
                }
            }
            .addOnFailureListener { error ->
                trace(
                    stage = "send_firestore_batch_fail",
                    details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms error=${error.message}"
                )
            }

        // Do NOT wait for the RTDB connection here. Firebase RTDB SDK queues writes
        // internally when offline and flushes them as soon as the WebSocket is ready.
        // The previous ensureTransportReadyForSend() call blocked for up to 10 seconds
        // (1.5 s RTDB wait + 5 s force-token-refresh + 3.5 s RTDB wait) on cold start.
        // Instead, nudge the connection in the background and let the SDK do its job.
        repositoryScope.launch {
            warmRealtimeTransport(reason = "send_nudge_$traceKey", waitForConnection = false)
        }
        trace(
            stage = "send_transport_nudged",
            details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms connected=${PresenceManager.isConnected.value}"
        )

        // 2. Send via RTDB for instant delivery to recipient
        // Only include optional reply fields when present to avoid RTDB rules rejecting nulls.
        val messageData = mutableMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to text,
            "senderId" to userId,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "TEXT"
        )

        previewThumbnailUrl?.let { messageData["thumbnailUrl"] = it }
        previewTitle?.let { messageData["linkPreviewTitle"] = it }
        previewDomain?.let { messageData["linkPreviewDomain"] = it }
        previewDescription?.let { messageData["linkPreviewDescription"] = it }
        previewSiteName?.let { messageData["linkPreviewSiteName"] = it }

        replyToMessageId?.let { messageData["replyToMessageId"] = it }
        replyToText?.let { messageData["replyToText"] = it }
        replyToSenderId?.let { messageData["replyToSenderId"] = it }
        replyToType?.let { messageData["replyToType"] = it.name }

        try {
            // Write to recipient's incoming messages in RTDB
            trace(
                stage = "send_rtdb_write_start",
                details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms recipient=${summarizeUserId(otherUserId)} connected=${PresenceManager.isConnected.value}"
            )
            rtdb.reference
                .child("pending_messages")
                .child(otherUserId)
                .child(messageId)
                .setValue(messageData)
                .addOnSuccessListener {
                    trace(
                        stage = "send_rtdb_write_ack",
                        details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms connected=${PresenceManager.isConnected.value}"
                    )
                    StartupTrace.logStage("send_message_rtdb_ack", "chatId=$chatId messageId=$messageId")
                    repositoryScope.launch {
                        updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
                        updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, text, MessageStatus.SENT)
                    }
                }
                .addOnFailureListener { e ->
                    trace(
                        stage = "send_rtdb_write_fail",
                        details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms connected=${PresenceManager.isConnected.value} error=${e.message}"
                    )
                    Log.e(TAG, "Failed to send message to RTDB", e)
                    repositoryScope.launch {
                        updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
                        updateChatLastMessageStatusMonotonic(chatId, localMessage.senderId, timestamp, text, MessageStatus.FAILED)
                    }
                }
        } catch (e: Exception) {
            trace(
                stage = "send_exception",
                details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - startElapsed}ms error=${e.message}"
            )
            Log.e(TAG, "Exception sending message", e)
            repositoryScope.launch {
                updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
                updateChatLastMessageStatusMonotonic(chatId, localMessage.senderId, timestamp, text, MessageStatus.FAILED)
            }
        }
    }

    // ==================== FORWARD MESSAGE ====================

    // ==================== SEND STATUS REPLY (RTDB) ====================
    
    suspend fun sendStatusReply(
        chatId: String,
        text: String,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        statusId: String,
        statusOwnerId: String,
        statusThumbnailUrl: String?,
        statusType: String,
        statusText: String?,
        statusBgColor: Int?
    ) {
        startDeliveryReceiptListener(chatId, forceRestart = true)
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val optimisticSenderId = currentUserId ?: getCachedAuthUserId() ?: PENDING_AUTH_SENDER_ID

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = text,
            senderId = optimisticSenderId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.STATUS_REPLY,
            statusId = statusId,
            statusOwnerId = statusOwnerId,
            statusThumbnailUrl = statusThumbnailUrl,
            statusType = statusType,
            statusText = statusText,
            statusBgColor = statusBgColor
        )
        messageDao.insertMessage(localMessage)
        chatDao.updateLastMessage(chatId, text, timestamp, localMessage.senderId, MessageStatus.SENDING.name)

        warmRealtimeTransport(reason = "send_status_reply", waitForConnection = false)

        val userId = awaitAuthenticatedUserId() ?: run {
            Log.e(TAG, "Cannot send status reply - auth unavailable")
            repositoryScope.launch {
                updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
            }
            return
        }
        persistLastKnownAuthUserId(userId)

        val messageData = mutableMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to text,
            "senderId" to userId,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "STATUS_REPLY",
            "statusId" to statusId,
            "statusOwnerId" to statusOwnerId,
            "statusType" to statusType
        )
        statusThumbnailUrl?.let { messageData["statusThumbnailUrl"] = it }
        statusText?.let { messageData["statusText"] = it }
        statusBgColor?.let { messageData["statusBgColor"] = it }

        try {
            persistToFirestoreAwait(
                chatId = chatId,
                messageId = messageId,
                text = text,
                senderId = userId,
                otherUserId = otherUserId,
                timestamp = timestamp,
                type = "STATUS_REPLY",
                statusId = statusId,
                statusOwnerId = statusOwnerId,
                statusThumbnailUrl = statusThumbnailUrl,
                statusType = statusType,
                statusText = statusText,
                statusBgColor = statusBgColor
            )
            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, text, MessageStatus.SENT)

            rtdb.reference
                .child("pending_messages")
                .child(otherUserId)
                .child(messageId)
                .setValue(messageData)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Direct RTDB status reply push failed after Firestore persist for $messageId", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending status reply", e)
            repositoryScope.launch {
                updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
                updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, text, MessageStatus.FAILED)
            }
        }
    }

    /**
     * Forwards an already-sent/received message (identified by [originalMessageId]) to a new chat.
     * Media is copied to a fresh forwarding object when a local/source file is available, so
     * receiver-side cleanup of the original Storage object cannot break forwarded downloads.
     * Clears reply chains and edit history.
     */
    suspend fun sendForwardedMessage(
        targetChatId: String,
        originalMessageId: String,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        clientTimestamp: Long? = null
    ): Boolean {
        val original = messageDao.getMessageById(originalMessageId) ?: return false
        return sendForwardedMessage(
            targetChatId = targetChatId,
            original = original,
            otherUserId = otherUserId,
            otherUsername = otherUsername,
            otherUserAvatar = otherUserAvatar,
            clientTimestamp = clientTimestamp
        )
    }

    suspend fun sendForwardedMessage(
        targetChatId: String,
        original: LocalMessage,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        clientTimestamp: Long? = null
    ): Boolean {
        val userId = currentUserId ?: return false
        if (original.isDeletedForAll) return false
        val originalMessageId = original.id
        val newMessageId = UUID.randomUUID().toString()
        val timestamp = clientTimestamp ?: System.currentTimeMillis()

        getOrCreateLocalChat(targetChatId, otherUserId, otherUsername, otherUserAvatar)

        // Clone the original message into the new chat, resetting delivery/personal metadata.
        val optimisticForwarded = original.copy(
            id = newMessageId,
            chatId = targetChatId,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            isEdited = false,
            editedAt = null,
            isDeletedForAll = false,
            deletedAt = null,
            deliveredTimestamp = null,
            readTimestamp = null,
            replyToMessageId = null,
            replyToText = null,
            replyToSenderId = null,
            replyToType = null,
            replyPreviewUrl = null,
            isStarred = false,
            isPinned = false,
            pinnedUntil = null,
            serverTimestamp = null,
            isForwarded = true,
            reactionsJson = null
        )
        messageDao.insertMessage(optimisticForwarded)

        val displayText = when (original.type) {
            MessageType.IMAGE -> original.text.ifEmpty { "Photo" }
            MessageType.VIDEO -> "Video"
            MessageType.AUDIO -> "Audio"
            MessageType.DOCUMENT -> original.text.ifEmpty { "Document" }
            MessageType.MEDIA_GROUP -> "Media"
            MessageType.GIF -> "GIF"
            MessageType.STICKER -> "Sticker"
            MessageType.KLIPY_EMOJI -> "Sticker"
            MessageType.MEME -> "Meme"
            MessageType.CONTACT -> "Contact: ${(original.contactName ?: original.text).ifBlank { "Contact" }}"
            else -> original.text
        }
        chatDao.updateLastMessage(targetChatId, displayText, timestamp, userId, MessageStatus.SENDING.name)

        try {
            val forwardedMedia = prepareForwardedMedia(original, targetChatId, newMessageId)
            if (!forwardedMedia.hasRequiredRemoteFor(original.type)) {
                Log.w(TAG, "Forward blocked for $originalMessageId (${original.type}): no durable remote media URL")
                updateOutgoingMessageStatusMonotonic(newMessageId, MessageStatus.FAILED)
                updateChatLastMessageStatusMonotonic(targetChatId, userId, timestamp, displayText, MessageStatus.FAILED)
                return false
            }

            val preparedForwarded = optimisticForwarded.copy(
                imageUrl = forwardedMedia.imageUrl,
                videoUrl = forwardedMedia.videoUrl,
                audioUrl = forwardedMedia.audioUrl,
                thumbnailUrl = forwardedMedia.thumbnailUrl,
                localUri = forwardedMedia.localUri,
                mediaItems = forwardedMedia.localMediaItemsJson
            )
            if (preparedForwarded != optimisticForwarded) {
                messageDao.insertMessage(preparedForwarded)
            }

            val previewTarget = if (preparedForwarded != optimisticForwarded) {
                preparedForwarded
            } else {
                optimisticForwarded
            }
            MessagePreviewCacheManager.warmMessagesAsync(
                context.applicationContext,
                listOf(localMessageToPreviewTarget(previewTarget))
            )

            // Send via RTDB for immediate push notification to recipient.
            val messageData = mutableMapOf<String, Any>(
                "id" to newMessageId,
                "chatId" to targetChatId,
                "text" to original.text,
                "senderId" to userId,
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "type" to original.type.name,
                "isForwarded" to true
            )
            forwardedMedia.imageUrl?.let { if (it.isNotEmpty()) messageData["imageUrl"] = it }
            forwardedMedia.videoUrl?.let { if (it.isNotEmpty()) messageData["videoUrl"] = it }
            forwardedMedia.audioUrl?.let { if (it.isNotEmpty()) messageData["audioUrl"] = it }
            forwardedMedia.thumbnailUrl?.let { if (it.isNotEmpty()) messageData["thumbnailUrl"] = it }
            original.linkPreviewTitle?.let { if (it.isNotEmpty()) messageData["linkPreviewTitle"] = it }
            original.linkPreviewDomain?.let { if (it.isNotEmpty()) messageData["linkPreviewDomain"] = it }
            original.linkPreviewDescription?.let { if (it.isNotEmpty()) messageData["linkPreviewDescription"] = it }
            original.linkPreviewSiteName?.let { if (it.isNotEmpty()) messageData["linkPreviewSiteName"] = it }
            original.fileSize?.let { messageData["fileSize"] = it }
            forwardedMedia.remoteMediaItemsJson?.let { json ->
                val payloadItems = mediaItemsRealtimePayload(json)
                if (payloadItems.isNotEmpty()) {
                    messageData["mediaItems"] = payloadItems
                }
            }
            original.contactName?.let { if (it.isNotEmpty()) messageData["contactName"] = it }
            original.contactPhone?.let { if (it.isNotEmpty()) messageData["contactPhone"] = it }
            original.documentCaption?.let { if (it.isNotEmpty()) messageData["documentCaption"] = it }
            if (original.audioDuration > 0) messageData["audioDuration"] = original.audioDuration
            original.videoDuration?.let { messageData["videoDuration"] = it }
            if (original.mediaWidth > 0) messageData["mediaWidth"] = original.mediaWidth
            if (original.mediaHeight > 0) messageData["mediaHeight"] = original.mediaHeight
            if (original.isVideoNote) messageData["isVideoNote"] = true

            persistToFirestoreAwait(
                chatId = targetChatId,
                messageId = newMessageId,
                text = original.text,
                senderId = userId,
                otherUserId = otherUserId,
                timestamp = timestamp,
                type = original.type.name,
                imageUrl = forwardedMedia.imageUrl,
                videoUrl = forwardedMedia.videoUrl,
                thumbnailUrl = forwardedMedia.thumbnailUrl,
                linkPreviewTitle = original.linkPreviewTitle,
                linkPreviewDomain = original.linkPreviewDomain,
                linkPreviewDescription = original.linkPreviewDescription,
                linkPreviewSiteName = original.linkPreviewSiteName,
                fileSize = original.fileSize,
                videoDuration = original.videoDuration,
                mediaWidth = original.mediaWidth,
                mediaHeight = original.mediaHeight,
                contactName = original.contactName,
                contactPhone = original.contactPhone,
                audioUrl = forwardedMedia.audioUrl,
                audioDuration = original.audioDuration,
                mediaItems = forwardedMedia.remoteMediaItemsJson,
                statusId = original.statusId,
                statusOwnerId = original.statusOwnerId,
                statusThumbnailUrl = original.statusThumbnailUrl,
                statusType = original.statusType,
                statusText = original.statusText,
                statusBgColor = original.statusBgColor,
                isVideoNote = original.isVideoNote,
                documentCaption = original.documentCaption,
                isForwarded = true
            )
            updateOutgoingMessageStatusMonotonic(newMessageId, MessageStatus.SENT)
            updateChatLastMessageStatusMonotonic(targetChatId, userId, timestamp, displayText, MessageStatus.SENT)

            rtdb.reference
                .child("pending_messages")
                .child(otherUserId)
                .child(newMessageId)
                .setValue(messageData)
                .addOnFailureListener { e ->
                    Log.w(TAG, "sendForwardedMessage RTDB failed after Firestore persist", e)
                }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "sendForwardedMessage exception", e)
            updateOutgoingMessageStatusMonotonic(newMessageId, MessageStatus.FAILED)
            updateChatLastMessageStatusMonotonic(targetChatId, userId, timestamp, displayText, MessageStatus.FAILED)
            return false
        }
    }

    private data class ForwardedMediaBundle(
        val imageUrl: String?,
        val videoUrl: String?,
        val audioUrl: String?,
        val thumbnailUrl: String?,
        val localUri: String?,
        val localMediaItemsJson: String?,
        val remoteMediaItemsJson: String?
    )

    private fun ForwardedMediaBundle.hasRequiredRemoteFor(type: MessageType): Boolean {
        return when (type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME,
            MessageType.DOCUMENT -> !imageUrl.isNullOrBlank()
            MessageType.VIDEO -> !videoUrl.isNullOrBlank()
            MessageType.AUDIO -> !audioUrl.isNullOrBlank()
            MessageType.MEDIA_GROUP -> {
                val remoteItems = parseMediaItemsFromJson(remoteMediaItemsJson)
                remoteItems.isNotEmpty() && remoteItems.all { it.url.isNotBlank() }
            }
            MessageType.TEXT,
            MessageType.CONTACT,
            MessageType.STATUS_REPLY,
            MessageType.SYSTEM -> true
        }
    }

    private suspend fun prepareForwardedMedia(
        original: LocalMessage,
        targetChatId: String,
        newMessageId: String
    ): ForwardedMediaBundle {
        var imageUrl = original.imageUrl
        var videoUrl = original.videoUrl
        var audioUrl = original.audioUrl
        var thumbnailUrl = original.thumbnailUrl
        var localUri = original.localUri
        var localMediaItemsJson = original.mediaItems
        var remoteMediaItemsJson = original.mediaItems?.let(::sanitizeMediaItemsForRemote)

        when (original.type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME -> {
                val prepared = prepareSingleForwardedAsset(
                    original = original,
                    targetChatId = targetChatId,
                    newMessageId = newMessageId,
                    remoteUrl = original.imageUrl,
                    storageType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.IMAGE,
                    label = "image",
                    fallbackExtension = when (original.type) {
                        MessageType.GIF -> "gif"
                        MessageType.STICKER, MessageType.KLIPY_EMOJI -> "webp"
                        else -> "jpg"
                    }
                )
                imageUrl = prepared.remoteUrl
                localUri = prepared.localUri

                val seedItem = parseMediaItemsOrEmpty(original.mediaItems).firstOrNull()
                val localItem = (seedItem ?: MediaItem(
                    url = imageUrl.orEmpty(),
                    type = MediaType.IMAGE,
                    fileSize = original.fileSize ?: 0L,
                    width = original.mediaWidth,
                    height = original.mediaHeight
                )).copy(
                    url = imageUrl.orEmpty(),
                    localUri = localUri,
                    type = MediaType.IMAGE,
                    fileSize = original.fileSize ?: seedItem?.fileSize ?: 0L,
                    width = original.mediaWidth.takeIf { it > 0 } ?: seedItem?.width ?: 0,
                    height = original.mediaHeight.takeIf { it > 0 } ?: seedItem?.height ?: 0
                )
                localMediaItemsJson = Message.mediaItemsToJson(listOf(localItem))
                remoteMediaItemsJson = Message.mediaItemsToJson(listOf(localItem.copy(localUri = null, thumbnailBase64 = null)))
            }

            MessageType.VIDEO -> {
                val prepared = prepareSingleForwardedAsset(
                    original = original,
                    targetChatId = targetChatId,
                    newMessageId = newMessageId,
                    remoteUrl = original.videoUrl,
                    storageType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.VIDEO,
                    label = "video",
                    fallbackExtension = "mp4"
                )
                videoUrl = prepared.remoteUrl
                localUri = prepared.localUri
                if (prepared.sourceUri != null) {
                    thumbnailUrl = uploadForwardedVideoThumbnail(
                        sourceUri = prepared.sourceUri,
                        targetChatId = targetChatId,
                        newMessageId = newMessageId
                    ) ?: thumbnailUrl
                }
            }

            MessageType.AUDIO -> {
                val prepared = prepareSingleForwardedAsset(
                    original = original,
                    targetChatId = targetChatId,
                    newMessageId = newMessageId,
                    remoteUrl = original.audioUrl,
                    storageType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.AUDIO,
                    label = "audio",
                    fallbackExtension = "m4a"
                )
                audioUrl = prepared.remoteUrl
                localUri = prepared.localUri
            }

            MessageType.DOCUMENT -> {
                val prepared = prepareSingleForwardedAsset(
                    original = original,
                    targetChatId = targetChatId,
                    newMessageId = newMessageId,
                    remoteUrl = original.imageUrl,
                    storageType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.DOCUMENT,
                    label = "document",
                    fallbackExtension = extensionFromCandidate(original.text, "bin")
                )
                imageUrl = prepared.remoteUrl
                localUri = prepared.localUri
            }

            MessageType.MEDIA_GROUP -> {
                val preparedItems = prepareForwardedMediaGroupItems(
                    original = original,
                    targetChatId = targetChatId,
                    newMessageId = newMessageId
                )
                if (preparedItems.isNotEmpty()) {
                    localMediaItemsJson = Message.mediaItemsToJson(preparedItems)
                    remoteMediaItemsJson = Message.mediaItemsToJson(
                        preparedItems.map { it.copy(localUri = null, thumbnailBase64 = null) }
                    )
                }
                localUri = null
            }

            MessageType.TEXT,
            MessageType.CONTACT,
            MessageType.STATUS_REPLY,
            MessageType.SYSTEM -> Unit
        }

        return ForwardedMediaBundle(
            imageUrl = imageUrl,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            thumbnailUrl = thumbnailUrl,
            localUri = localUri,
            localMediaItemsJson = localMediaItemsJson,
            remoteMediaItemsJson = remoteMediaItemsJson
        )
    }

    private data class PreparedForwardedAsset(
        val remoteUrl: String?,
        val localUri: String?,
        val sourceUri: Uri?
    )

    private suspend fun prepareSingleForwardedAsset(
        original: LocalMessage,
        targetChatId: String,
        newMessageId: String,
        remoteUrl: String?,
        storageType: com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType,
        label: String,
        fallbackExtension: String
    ): PreparedForwardedAsset {
        val extension = extensionFromCandidate(original.localUri ?: remoteUrl ?: original.text, fallbackExtension)
        val sourceUri = resolveForwardableLocalUri(original.localUri)
            ?: materializeRemoteForForward(remoteUrl, newMessageId, label, extension)

        if (sourceUri == null) {
            return PreparedForwardedAsset(
                remoteUrl = remoteUrl?.takeUnless { isFirebaseStorageUrl(it) },
                localUri = resolveForwardableLocalUri(original.localUri)?.toString(),
                sourceUri = null
            )
        }

        val localCopy = runCatching {
            com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                context = context,
                chatId = targetChatId,
                messageId = newMessageId,
                mediaType = storageType,
                sourceUri = sourceUri,
                extension = extension
            )
        }.getOrNull()

        val uploadedUrl = uploadForwardedUri(
            sourceUri = sourceUri,
            storagePath = "chat_media/$targetChatId/$newMessageId/$label.$extension"
        ) ?: remoteUrl?.takeUnless { isFirebaseStorageUrl(it) }

        return PreparedForwardedAsset(
            remoteUrl = uploadedUrl,
            localUri = localCopy?.absolutePath ?: sourceUri.toString(),
            sourceUri = sourceUri
        )
    }

    private suspend fun prepareForwardedMediaGroupItems(
        original: LocalMessage,
        targetChatId: String,
        newMessageId: String
    ): List<MediaItem> {
        val items = parseMediaItemsOrEmpty(original.mediaItems)
        if (items.isEmpty()) return emptyList()

        val preparedItems = ArrayList<MediaItem>(items.size)
        for ((index, item) in items.withIndex()) {
            val itemLabel = "item_${index + 1}"
            val storageType = if (item.type == MediaType.VIDEO) {
                com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.VIDEO
            } else {
                com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.IMAGE
            }
            val fallbackExtension = if (item.type == MediaType.VIDEO) "mp4" else "jpg"
            val extension = extensionFromCandidate(item.localUri ?: item.url, fallbackExtension)
            val sourceUri = resolveForwardableLocalUri(item.localUri)
                ?: materializeRemoteForForward(item.url, newMessageId, itemLabel, extension)

            if (sourceUri == null) {
                val durableUrl = item.url.takeUnless { isFirebaseStorageUrl(it) }
                if (durableUrl.isNullOrBlank()) return emptyList()
                preparedItems += item.copy(url = durableUrl, localUri = null, thumbnailBase64 = null)
            } else {
                val localCopy = runCatching {
                    com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                        context = context,
                        chatId = targetChatId,
                        messageId = newMessageId,
                        mediaType = storageType,
                        sourceUri = sourceUri,
                        extension = extension,
                        itemIndex = index + 1
                    )
                }.getOrNull()
                val uploadedUrl = uploadForwardedUri(
                    sourceUri = sourceUri,
                    storagePath = "chat_media/$targetChatId/$newMessageId/$itemLabel.$extension"
                ) ?: item.url.takeUnless { isFirebaseStorageUrl(it) }
                if (uploadedUrl.isNullOrBlank()) return emptyList()
                val uploadedThumb = if (item.type == MediaType.VIDEO) {
                    uploadForwardedVideoThumbnail(
                        sourceUri = sourceUri,
                        targetChatId = targetChatId,
                        newMessageId = "${newMessageId}_${itemLabel}"
                    )
                } else {
                    null
                }

                preparedItems += item.copy(
                    url = uploadedUrl,
                    localUri = localCopy?.absolutePath ?: sourceUri.toString(),
                    thumbnailUrl = uploadedThumb ?: item.thumbnailUrl
                )
            }
        }
        return preparedItems
    }

    private fun parseMediaItemsOrEmpty(json: String?): List<MediaItem> {
        return parseMediaItemsFromJson(json)
    }

    private fun parseMediaItemsFromJson(json: String?): List<MediaItem> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val type = object : com.google.gson.reflect.TypeToken<List<MediaItem>>() {}.type
            Gson().fromJson<List<MediaItem>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun sanitizeMediaItemsForRemote(json: String): String {
        val items = parseMediaItemsOrEmpty(json)
        if (items.isEmpty()) return json
        return Message.mediaItemsToJson(items.map { it.copy(localUri = null, thumbnailBase64 = null) })
    }

    private fun mediaItemsRealtimePayload(json: String?): List<Map<String, Any>> {
        return parseMediaItemsOrEmpty(json).mapNotNull { item ->
            if (item.url.isBlank()) return@mapNotNull null
            buildMap<String, Any> {
                put("url", item.url)
                put("type", item.type.name)
                item.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { put("thumbnailUrl", it) }
                if (item.duration > 0L) put("duration", item.duration)
                if (item.fileSize > 0L) put("fileSize", item.fileSize)
                if (item.width > 0) put("width", item.width)
                if (item.height > 0) put("height", item.height)
            }
        }
    }

    private fun mediaItemsJsonFromFirestoreDoc(doc: com.google.firebase.firestore.DocumentSnapshot): String? {
        val raw = doc.get("mediaItems") ?: return null
        return runCatching {
            when (raw) {
                is String -> raw.takeIf { it.isNotBlank() }
                is List<*> -> Gson().toJson(raw)
                is Map<*, *> -> Gson().toJson(raw.values.toList())
                else -> null
            }
        }.getOrNull()
    }

    private fun hasMediaGroupItems(json: String?): Boolean {
        return parseMediaItemsFromJson(json).isNotEmpty()
    }

    private fun resolveForwardableLocalUri(candidate: String?): Uri? {
        val value = candidate?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { Uri.parse(value) }.getOrNull()
        when (parsed?.scheme) {
            "content" -> return parsed
            "file" -> {
                val file = parsed.path?.let(::File)
                if (file != null && file.exists() && file.length() > 0L) return Uri.fromFile(file)
            }
            "http", "https", "gs" -> return null
        }

        val rawFile = File(value)
        return if (rawFile.exists() && rawFile.length() > 0L) Uri.fromFile(rawFile) else null
    }

    private suspend fun materializeRemoteForForward(
        remoteUrl: String?,
        newMessageId: String,
        label: String,
        extension: String
    ): Uri? {
        val url = remoteUrl?.takeIf { it.isNotBlank() } ?: return null
        if (!isFirebaseStorageUrl(url)) return null

        val tempDir = File(context.cacheDir, "forwarded_media").apply { mkdirs() }
        val tempFile = File(tempDir, "${newMessageId}_${label}_${System.currentTimeMillis()}.$extension")
        val downloaded = if (url.startsWith("https://", ignoreCase = true)) {
            downloadHttpsToFile(url, tempFile) || downloadStorageRefToFile(url, tempFile)
        } else {
            downloadStorageRefToFile(url, tempFile)
        }
        return if (downloaded && tempFile.exists() && tempFile.length() > 0L) Uri.fromFile(tempFile) else null
    }

    private suspend fun downloadHttpsToFile(url: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.connect()
            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) return@withContext false
            targetFile.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun downloadStorageRefToFile(url: String, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            targetFile.parentFile?.mkdirs()
            storage.getReferenceFromUrl(url).getFile(targetFile).await()
            targetFile.exists() && targetFile.length() > 0L
        }.getOrDefault(false)
    }

    private suspend fun uploadForwardedUri(sourceUri: Uri, storagePath: String): String? {
        return runCatching {
            val ref = storage.reference.child(storagePath)
            ref.putFile(sourceUri).await()
            ref.downloadUrl.await().toString()
        }.onFailure { error ->
            Log.w(TAG, "Forwarded media upload failed for $storagePath", error)
        }.getOrNull()
    }

    private suspend fun uploadForwardedVideoThumbnail(
        sourceUri: Uri,
        targetChatId: String,
        newMessageId: String
    ): String? {
        return runCatching {
            val thumbnail = com.glyph.glyph_v3.util.VideoThumbnailUtil.generateThumbnailBytes(context, sourceUri)
                ?: return null
            val (bytes, _) = thumbnail
            val ref = storage.reference.child("chat_media/$targetChatId/$newMessageId/thumbnail.jpg")
            ref.putBytes(bytes).await()
            ref.downloadUrl.await().toString()
        }.onFailure { error ->
            Log.w(TAG, "Forwarded video thumbnail upload failed for $newMessageId", error)
        }.getOrNull()
    }

    private fun isFirebaseStorageUrl(url: String): Boolean {
        return url.startsWith("gs://", ignoreCase = true) ||
            url.contains("firebasestorage.googleapis.com", ignoreCase = true)
    }

    private fun extensionFromCandidate(candidate: String?, fallback: String): String {
        val raw = candidate.orEmpty().substringBefore('?').substringBefore('#')
        val ext = raw.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
        return ext.takeIf { it.length in 2..5 } ?: fallback
    }

    // ==================== EDIT MESSAGE ====================
    
    suspend fun editMessage(
        messageId: String,
        newText: String,
        chatId: String,
        otherUserId: String
    ) {
        val userId = currentUserId ?: return
        val editedAt = System.currentTimeMillis()

        val existingMessage = messageDao.getMessageById(messageId) ?: return
        val isEligible = !existingMessage.isIncoming &&
            !existingMessage.isDeletedForAll &&
            existingMessage.senderId == userId &&
            existingMessage.type == MessageType.TEXT
        val isWithinWindow = (editedAt - existingMessage.timestamp) <= EDIT_WINDOW_MS
        if (!isEligible || !isWithinWindow) {
            Log.w(TAG, "Edit rejected for message $messageId (eligible=$isEligible, withinWindow=$isWithinWindow)")
            return
        }


        // 1. Update local database
        messageDao.updateMessageText(messageId, newText, editedAt)
        
        // 2. Update chat last message if this was the last message
        chatDao.updateLastMessage(chatId, newText, editedAt, userId, MessageStatus.SENT.name)

        // 3. Send edit notification via RTDB for real-time sync
        val editData = mapOf(
            "id" to messageId,
            "chatId" to chatId,
            "senderId" to userId,  // Required by RTDB validation rules
            "text" to newText,
            "timestamp" to editedAt,  // Required by RTDB validation rules
            "isEdited" to true,
            "editedAt" to editedAt,
            "type" to "EDIT"
        )

        try {
            // Notify recipient via RTDB
            val editRef = rtdb.reference
                .child("pending_messages")
                .child(otherUserId)
                .child("edit_$messageId")
            
            
            editRef.setValue(editData)
                .addOnSuccessListener {
                    // Update in Firestore for persistence
                    firestore.collection("chats")
                        .document(chatId)
                        .collection("messages")
                        .document(messageId)
                        .update(mapOf(
                            "text" to newText,
                            "isEdited" to true,
                            "editedAt" to editedAt
                        ))
                        .addOnSuccessListener {
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to update Firestore", e)
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send edit notification to RTDB", e)
                    Log.e(TAG, "RTDB Path: pending_messages/$otherUserId/edit_$messageId")
                    Log.e(TAG, "Current User: $userId, Target User: $otherUserId")
                    Log.e(TAG, "Make sure RTDB rules allow writes to pending_messages/{userId}/")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception editing message", e)
        }
    }

    // ==================== SEND IMAGE ====================
    
    suspend fun sendImageMessage(
        chatId: String,
        imageUri: Uri,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        caption: String = "",
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        var fileSize: Long = 0
        var width: Int = 0
        var height: Int = 0
        try {
            context.contentResolver.openFileDescriptor(imageUri, "r")?.use { descriptor ->
                fileSize = descriptor.statSize
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
                width = options.outWidth
                height = options.outHeight
            }
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                 val exif = androidx.exifinterface.media.ExifInterface(stream)
                 val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
                 if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 || 
                     orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) {
                     val temp = width
                     width = height
                     height = temp
                 }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file size/dimensions", e)
        }
        
        val mediaItemPlaceholder = MediaItem(
            url = imageUri.toString(),
            localUri = imageUri.toString(),
            type = MediaType.IMAGE,
            fileSize = fileSize,
            width = width,
            height = height
        )
        val mediaItemsJson = Message.mediaItemsToJson(listOf(mediaItemPlaceholder))

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val messageText = caption.ifEmpty { "Photo" }
        // 1. Show placeholder immediately
        val placeholderMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = messageText,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.IMAGE,
            localUri = imageUri.toString(),
            fileSize = fileSize,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl,
            mediaItems = mediaItemsJson
        )
        messageDao.insertMessage(placeholderMessage)
        chatDao.updateLastMessage(chatId, messageText, timestamp, userId, MessageStatus.SENDING.name)

        // Start progress tracking
        MediaProgressManager.updateProgress(messageId, 0f, isUploading = true, totalBytes = fileSize)

        // 2. Upload to Storage with progress tracking
        val storageRef = storage.reference.child("chat_images/$chatId/$messageId.jpg")
        
        repositoryScope.launch {
            storageRef.putFile(imageUri)
                .addOnProgressListener { taskSnapshot ->
                    val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toFloat()
                    MediaProgressManager.updateProgress(
                        messageId, 
                        progress, 
                        isUploading = true,
                        totalBytes = taskSnapshot.totalByteCount,
                        transferredBytes = taskSnapshot.bytesTransferred
                    )
                }
                .addOnSuccessListener {
                    storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                        val downloadUrl = downloadUri.toString()
                        
                        // Mark upload complete
                        MediaProgressManager.complete(messageId)
                        
                        // Save to persistent local storage AND update DB in one go
                        repositoryScope.launch {
                            var finalLocalPath = imageUri.toString()
                            try {
                                val localFile = com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                                    context = context,
                                    chatId = chatId,
                                    messageId = messageId,
                                    mediaType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.IMAGE,
                                    sourceUri = imageUri
                                )
                                
                                if (localFile != null) {
                                    finalLocalPath = localFile.absolutePath
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save image to persistent storage", e)
                            }
                            
                            // Update media items with remote URL
                            val sentMediaItem = mediaItemPlaceholder.copy(url = downloadUrl, localUri = finalLocalPath)
                            val sentMediaItemsJson = Message.mediaItemsToJson(listOf(sentMediaItem))

                            // Update placeholder message with NEW local path and SENT status
                            val sentMessage = placeholderMessage.copy(
                                imageUrl = downloadUrl,
                                localUri = finalLocalPath,
                                status = MessageStatus.SENDING,
                                mediaItems = sentMediaItemsJson
                            )
                            messageDao.insertMessage(sentMessage)

                            // 3. Send via RTDB
                            val messageData = mutableMapOf<String, Any?>(
                                "id" to messageId,
                                "chatId" to chatId,
                                "text" to messageText,
                                "senderId" to userId,
                                "timestamp" to ServerValue.TIMESTAMP,
                                "type" to "IMAGE",
                                "imageUrl" to downloadUrl,
                                "mediaItems" to sentMediaItemsJson,
                                "fileSize" to fileSize
                            )

                            if (replyToMessageId != null) {
                                messageData["replyToMessageId"] = replyToMessageId
                                messageData["replyToText"] = replyToText
                                messageData["replyToSenderId"] = replyToSenderId
                                messageData["replyToType"] = replyToType?.name
                                replyPreviewUrl?.let { messageData["replyPreviewUrl"] = it }
                            }

                            try {
                                persistToFirestoreAwait(
                                    chatId = chatId,
                                    messageId = messageId,
                                    text = messageText,
                                    senderId = userId,
                                    otherUserId = otherUserId,
                                    timestamp = timestamp,
                                    type = "IMAGE",
                                    imageUrl = downloadUrl,
                                    fileSize = fileSize,
                                    replyToMessageId = replyToMessageId,
                                    replyToText = replyToText,
                                    replyToSenderId = replyToSenderId,
                                    replyToType = replyToType?.name,
                                    replyPreviewUrl = replyPreviewUrl,
                                    mediaItems = sentMediaItemsJson
                                )
                                updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
                                updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, messageText, MessageStatus.SENT)

                                rtdb.reference
                                    .child("pending_messages")
                                    .child(otherUserId)
                                    .child(messageId)
                                    .setValue(messageData)
                                    .addOnFailureListener { e ->
                                        Log.w(TAG, "Direct RTDB image push failed after Firestore persist for $messageId", e)
                                    }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to persist image message before RTDB push", e)
                                messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                                chatDao.updateLastMessage(chatId, "Photo", timestamp, userId, MessageStatus.FAILED.name)
                            }
                        }
                    }.addOnFailureListener { e ->
                        Log.e(TAG, "Failed to get download URL", e)
                        MediaProgressManager.complete(messageId)
                        repositoryScope.launch {
                            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                            chatDao.updateLastMessage(chatId, "Photo", timestamp, userId, MessageStatus.FAILED.name)
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to upload image", e)
                    MediaProgressManager.complete(messageId)
                    repositoryScope.launch {
                        messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                        chatDao.updateLastMessage(chatId, "Photo", timestamp, userId, MessageStatus.FAILED.name)
                    }
                }
        }
    }

    // ==================== SEND VIDEO ====================
    
    suspend fun sendVideoMessage(
        chatId: String,
        videoUri: Uri,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        caption: String = "",
        isVideoNote: Boolean = false,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Get video metadata with proper rotation handling
        var fileSize: Long = 0
        var videoDuration: Long = 0
        var videoWidth = 0
        var videoHeight = 0
        var videoRotation = 0
        
        try {
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { descriptor ->
                fileSize = descriptor.statSize
            }
            
            // Use VideoThumbnailUtil for proper metadata extraction
            val metadata = com.glyph.glyph_v3.util.VideoThumbnailUtil.getVideoMetadata(context, videoUri)
            if (metadata != null) {
                videoDuration = metadata.duration
                videoWidth = metadata.width
                videoHeight = metadata.height
                videoRotation = metadata.rotation
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video metadata", e)
        }

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val messageText = caption.ifEmpty { if (isVideoNote) "Video note" else "Video" }
        val placeholderMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = messageText,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.VIDEO,
            localUri = videoUri.toString(),
            fileSize = fileSize,
            videoDuration = videoDuration,
            mediaWidth = videoWidth,
            mediaHeight = videoHeight,
            isVideoNote = isVideoNote,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl
        )
        messageDao.insertMessage(placeholderMessage)
        chatDao.updateLastMessage(chatId, messageText, timestamp, userId, MessageStatus.SENDING.name)

        // Start progress tracking
        MediaProgressManager.updateProgress(messageId, 0f, isUploading = true, totalBytes = fileSize)

        // Generate thumbnail with correct orientation
        var thumbnailUrl: String? = null
        try {
            val thumbnailResult = com.glyph.glyph_v3.util.VideoThumbnailUtil.generateThumbnailBytes(context, videoUri)
            if (thumbnailResult != null) {
                val (thumbnailBytes, rotation) = thumbnailResult
                
                // Upload thumbnail to Storage
                val thumbnailRef = storage.reference.child("chat_video_thumbnails/$chatId/$messageId.jpg")
                thumbnailRef.putBytes(thumbnailBytes).await()
                thumbnailUrl = thumbnailRef.downloadUrl.await().toString()
                
                // Update local message with thumbnail
                messageDao.insertMessage(
                    placeholderMessage.copy(thumbnailUrl = thumbnailUrl)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate/upload video thumbnail", e)
        }

        // Upload video
        val storageRef = storage.reference.child("chat_videos/$chatId/$messageId.mp4")
        
        val finalThumbnailUrl = thumbnailUrl
        storageRef.putFile(videoUri)
            .addOnProgressListener { taskSnapshot ->
                val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toFloat()
                MediaProgressManager.updateProgress(
                    messageId,
                    progress,
                    isUploading = true,
                    totalBytes = taskSnapshot.totalByteCount,
                    transferredBytes = taskSnapshot.bytesTransferred
                )
            }
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    val downloadUrl = downloadUri.toString()
                    
                    // Mark upload complete
                    MediaProgressManager.complete(messageId)
                    
                    repositoryScope.launch {
                        // 1. Save to persistent local storage
                        var finalLocalPath = videoUri.toString()
                        try {
                            val localFile = com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                                context = context,
                                chatId = chatId,
                                messageId = messageId,
                                mediaType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.VIDEO,
                                sourceUri = videoUri
                            )
                            
                            if (localFile != null) {
                                finalLocalPath = localFile.absolutePath
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save video to persistent storage", e)
                        }

                        // 2. Update local DB with SENT status and PERSISTENT path
                        val sentMessage = placeholderMessage.copy(
                            videoUrl = downloadUrl,
                            thumbnailUrl = finalThumbnailUrl,
                            localUri = finalLocalPath,
                            status = MessageStatus.SENDING
                        )
                        messageDao.insertMessage(sentMessage)

                        // 3. Send via RTDB
                        val messageData = mutableMapOf<String, Any?>(
                            "id" to messageId,
                            "chatId" to chatId,
                            "text" to messageText,
                            "senderId" to userId,
                            "timestamp" to ServerValue.TIMESTAMP,
                            "type" to "VIDEO",
                            "videoUrl" to downloadUrl,
                            "fileSize" to fileSize,
                            "videoDuration" to videoDuration,
                            "mediaWidth" to videoWidth,
                            "mediaHeight" to videoHeight,
                            "isVideoNote" to isVideoNote
                        )
                        
                        // Include thumbnail URL if generated
                        if (finalThumbnailUrl != null) {
                            messageData["thumbnailUrl"] = finalThumbnailUrl
                        }

                        if (replyToMessageId != null) {
                            messageData["replyToMessageId"] = replyToMessageId
                            messageData["replyToText"] = replyToText
                            messageData["replyToSenderId"] = replyToSenderId
                            messageData["replyToType"] = replyToType?.name
                            replyPreviewUrl?.let { messageData["replyPreviewUrl"] = it }
                        }

                        try {
                            persistToFirestoreAwait(
                                chatId = chatId,
                                messageId = messageId,
                                text = messageText,
                                senderId = userId,
                                otherUserId = otherUserId,
                                timestamp = timestamp,
                                type = "VIDEO",
                                videoUrl = downloadUrl,
                                thumbnailUrl = finalThumbnailUrl,
                                fileSize = fileSize,
                                videoDuration = videoDuration,
                                mediaWidth = videoWidth,
                                mediaHeight = videoHeight,
                                replyToMessageId = replyToMessageId,
                                replyToText = replyToText,
                                replyToSenderId = replyToSenderId,
                                replyToType = replyToType?.name,
                                replyPreviewUrl = replyPreviewUrl,
                                isVideoNote = isVideoNote
                            )
                            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
                            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, messageText, MessageStatus.SENT)

                            rtdb.reference
                                .child("pending_messages")
                                .child(otherUserId)
                                .child(messageId)
                                .setValue(messageData)
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "Direct RTDB video push failed after Firestore persist for $messageId", e)
                                }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist video message before RTDB push", e)
                            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                            chatDao.updateLastMessage(chatId, "Video", timestamp, userId, MessageStatus.FAILED.name)
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get video download URL", e)
                    MediaProgressManager.complete(messageId)
                    repositoryScope.launch {
                        messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                        chatDao.updateLastMessage(chatId, "Video", timestamp, userId, MessageStatus.FAILED.name)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to upload video", e)
                MediaProgressManager.complete(messageId)
                repositoryScope.launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    chatDao.updateLastMessage(chatId, "Video", timestamp, userId, MessageStatus.FAILED.name)
                }
            }
    }

    // ==================== SEND MULTI-MEDIA ====================
    
    /**
     * Sends multiple media items (images and videos) as individual messages.
     * @param mediaUris List of pairs where first is the URI and second is true if video
     */
    suspend fun sendMultiMediaMessage(
        chatId: String,
        mediaUris: List<Pair<Uri, Boolean>>,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String
    ) {
        // Ensure chat exists
        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        // Send each media item as a separate message
        mediaUris.forEach { (uri, isVideo) ->
            if (isVideo) {
                sendVideoMessage(chatId, uri, otherUserId, otherUsername, otherUserAvatar)
            } else {
                sendImageMessage(chatId, uri, otherUserId, otherUsername, otherUserAvatar)
            }
        }
    }

    private data class GroupedMediaPreparation(
        val finalUri: Uri,
        val mimeType: String,
        val mediaType: MediaType,
        val metadata: SelectedMediaItem
    )

    suspend fun sendGroupedMediaMessage(
        chatId: String,
        uris: List<Uri>,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        quality: CompressionQuality,
        overrides: Map<Uri, CompressionQuality> = emptyMap()
    ) {
        if (uris.isEmpty()) return
        val userId = currentUserId ?: return
        val timestamp = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val compressionDir = File(context.cacheDir, "media_group_compression").apply {
            if (!exists()) mkdirs()
        }

        val preparedItems = mutableListOf<GroupedMediaPreparation>()

        try {
            for (uri in uris) {
                val mimeType = context.contentResolver.getType(uri) ?: "image/*"
                val itemQuality = overrides[uri] ?: quality

                val baseMetadata = MediaEstimationUtil.extractMediaMetadata(context, uri, mimeType)
                val compressedUri = if (itemQuality == CompressionQuality.ORIGINAL) {
                    uri
                } else {
                    MediaCompressor.compress(context, baseMetadata, itemQuality, compressionDir) ?: uri
                }

                val finalMetadata = if (compressedUri == uri) {
                    baseMetadata
                } else {
                    MediaEstimationUtil.extractMediaMetadata(context, compressedUri, mimeType)
                }

                val mediaType = if (finalMetadata.isVideo) MediaType.VIDEO else MediaType.IMAGE
                preparedItems.add(GroupedMediaPreparation(compressedUri, mimeType, mediaType, finalMetadata))
            }

            if (preparedItems.isEmpty()) return


            // Generate thumbnails for instant display
            val placeholderItems = preparedItems.mapIndexed { index, prep ->
                val thumbnailBase64 = try {
                    val thumbnail = com.glyph.glyph_v3.data.media.ThumbnailGenerator.generateBase64Thumbnail(context, prep.finalUri)
                    thumbnail
                } catch (e: Exception) {
                    Log.e(TAG, "Thumbnail generation FAILED for item $index (${prep.finalUri})", e)
                    null
                }
                
                MediaItem(
                    url = "",
                    localUri = prep.finalUri.toString(),
                    type = prep.mediaType,
                    thumbnailUrl = null,
                    thumbnailBase64 = thumbnailBase64,
                    duration = prep.metadata.duration,
                    fileSize = prep.metadata.originalSize,
                    width = prep.metadata.width,
                    height = prep.metadata.height
                )
            }


            val placeholderMessage = LocalMessage(
                id = messageId,
                chatId = chatId,
                text = "Media",
                senderId = userId,
                timestamp = timestamp,
                status = MessageStatus.SENDING,
                isIncoming = false,
                type = MessageType.MEDIA_GROUP,
                mediaItems = Message.mediaItemsToJson(placeholderItems),
                fileSize = placeholderItems.sumOf { it.fileSize }
            )
            messageDao.insertMessage(placeholderMessage)
            chatDao.updateLastMessage(chatId, "Media", timestamp, userId, MessageStatus.SENDING.name)

            val totalBytes = preparedItems.sumOf { it.metadata.originalSize }.coerceAtLeast(1L)
            MediaProgressManager.updateProgress(messageId, 0f, isUploading = true, totalBytes = totalBytes)

            val finalMediaItems = mutableListOf<MediaItem>()
            var uploadedBytes = 0L

            for ((index, prepared) in preparedItems.withIndex()) {
                val extension = if (prepared.mediaType == MediaType.VIDEO) "mp4" else "jpg"
                val storagePath = "chat_media/$chatId/$messageId/item_${index + 1}.$extension"
                val storageRef = storage.reference.child(storagePath)

                val downloadUrl = uploadGroupedMediaItem(
                    storageRef = storageRef,
                    sourceUri = prepared.finalUri,
                    messageId = messageId,
                    bytesUploadedBefore = uploadedBytes,
                    totalBytes = totalBytes
                )

                uploadedBytes += prepared.metadata.originalSize
                MediaProgressManager.updateProgress(
                    messageId,
                    (uploadedBytes.toFloat() / totalBytes.toFloat() * 100f).coerceAtMost(100f),
                    isUploading = true,
                    totalBytes = totalBytes,
                    transferredBytes = uploadedBytes
                )

                val storageType = if (prepared.mediaType == MediaType.VIDEO) {
                    MediaStorageManager.MediaType.VIDEO
                } else {
                    MediaStorageManager.MediaType.IMAGE
                }

                val persistedFile = MediaStorageManager.saveMediaFromUri(
                    context = context,
                    chatId = chatId,
                    messageId = messageId,
                    mediaType = storageType,
                    sourceUri = prepared.finalUri,
                    itemIndex = index + 1
                )

                val persistedPath = persistedFile?.absolutePath ?: prepared.finalUri.toString()

                finalMediaItems.add(
                    MediaItem(
                        url = downloadUrl,
                        localUri = persistedPath,
                        type = prepared.mediaType,
                        thumbnailUrl = null,
                        duration = prepared.metadata.duration,
                        fileSize = prepared.metadata.originalSize,
                        width = prepared.metadata.width,
                        height = prepared.metadata.height
                    )
                )
            }

            val finalJson = Message.mediaItemsToJson(finalMediaItems)
            messageDao.updateMediaGroupMessage(messageId, finalJson, MessageStatus.SENT)
            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, "Media", MessageStatus.SENT)
            MediaProgressManager.complete(messageId)

            val mediaItemsPayload = finalMediaItems.map { item ->
                mapOf(
                    "url" to item.url,
                    "localUri" to item.localUri,
                    "type" to item.type.name,
                    "thumbnailUrl" to item.thumbnailUrl,
                    "thumbnailBase64" to item.thumbnailBase64,
                    "duration" to item.duration,
                    "fileSize" to item.fileSize,
                    "width" to item.width,
                    "height" to item.height
                )
            }

            val messageData = mutableMapOf<String, Any?>(
                "id" to messageId,
                "chatId" to chatId,
                "text" to "Media",
                "senderId" to userId,
                "timestamp" to ServerValue.TIMESTAMP,
                "type" to "MEDIA_GROUP",
                "mediaItems" to mediaItemsPayload,
                "mediaCount" to finalMediaItems.size,
                "fileSize" to totalBytes
            )

            // Write to Firestore BEFORE the RTDB write that triggers the Cloud Function / FCM.
            // The FCM payload hits the 4 KB limit with multiple image URLs, so the receiver
            // must fall back to fetching mediaItems from Firestore. If we wrote Firestore only
            // in addOnSuccessListener (fire-and-forget), the document wouldn't exist yet when
            // the receiver's FCM handler runs, causing "Firestore fetch returned 0 items".
            // Build a lean JSON for Firestore — strip sender-local fields (localUri, thumbnailBase64)
            // so the document stays small and the receiver can safely Gson-parse it.
            val finalJsonForFirestore = Message.mediaItemsToJson(
                finalMediaItems.map { it.copy(localUri = null, thumbnailBase64 = null) }
            )
            val firestoreMessageData = hashMapOf<String, Any?>(
                "id" to messageId,
                "text" to "Media",
                "senderId" to userId,
                "timestamp" to timestamp,
                "serverTimestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "type" to "MEDIA_GROUP",
                "mediaItems" to finalJsonForFirestore,
                "mediaCount" to finalMediaItems.size,
                "fileSize" to totalBytes,
                "status" to MessageStatus.SENT.name
            )
            val firestoreChatData = hashMapOf<String, Any?>(
                "participants" to listOf(userId, otherUserId),
                "lastMessage" to "Media",
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to userId
            )
            runCatching {
                firestore.collection("chats").document(chatId)
                    .set(firestoreChatData, SetOptions.merge()).await()
                firestore.collection("chats").document(chatId)
                    .collection("messages").document(messageId)
                    .set(firestoreMessageData, SetOptions.merge()).await()
            }.onFailure { e ->
                Log.w(TAG, "Pre-RTDB Firestore write failed for grouped media $messageId; receiver may fall back to retry", e)
            }

            rtdb.reference
                .child("pending_messages")
                .child(otherUserId)
                .child(messageId)
                .setValue(messageData)
                .addOnSuccessListener {
                    // Firestore already written above; nothing extra needed here.
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send media group to RTDB", e)
                    repositoryScope.launch {
                        messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                        chatDao.updateLastMessage(chatId, "Media", timestamp, userId, MessageStatus.FAILED.name)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send grouped media message", e)
            repositoryScope.launch {
                messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                chatDao.updateLastMessage(chatId, "Media", timestamp, userId, MessageStatus.FAILED.name)
            }
            MediaProgressManager.complete(messageId)
        }
    }

    private suspend fun uploadGroupedMediaItem(
        storageRef: StorageReference,
        sourceUri: Uri,
        messageId: String,
        bytesUploadedBefore: Long,
        totalBytes: Long
    ): String = suspendCancellableCoroutine { cont ->
        val task = storageRef.putFile(sourceUri)
            .addOnProgressListener { snapshot ->
                val overallBytes = (bytesUploadedBefore + snapshot.bytesTransferred).coerceAtMost(totalBytes)
                val progress = if (totalBytes > 0) {
                    (overallBytes.toFloat() / totalBytes.toFloat() * 100f).coerceAtMost(100f)
                } else {
                    0f
                }
                MediaProgressManager.updateProgress(
                    messageId,
                    progress,
                    isUploading = true,
                    totalBytes = totalBytes,
                    transferredBytes = overallBytes
                )
            }
            .addOnSuccessListener {
                storageRef.downloadUrl
                        .addOnSuccessListener { uri: Uri -> cont.resume(uri.toString()) }
                        .addOnFailureListener { exception: Exception -> cont.resumeWithException(exception) }
            }
                    .addOnFailureListener { exception: Exception -> cont.resumeWithException(exception) }

        cont.invokeOnCancellation { task.cancel() }
    }

    // uploadMultiMedia function removed - now sending individual messages

    // onAllMediaUploaded function removed - now sending individual messages

    // handleMultiMediaUploadFailure function removed - now sending individual messages

    // persistMultiMediaToFirestore function removed - now sending individual messages

    // ==================== SEND DOCUMENT ====================

    suspend fun sendDocumentMessage(
        chatId: String,
        documentUri: android.net.Uri,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        caption: String = ""
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Resolve file name and size from URI
        var fileName = "document"
        var fileSize: Long = 0L
        try {
            context.contentResolver.query(documentUri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve document metadata", e)
        }
        if (fileSize == 0L) {
            try {
                context.contentResolver.openFileDescriptor(documentUri, "r")?.use { fd ->
                    fileSize = fd.statSize
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not get document file size", e)
            }
        }

        val ext = fileName.substringAfterLast('.', "")
        val storageName = if (ext.isNotEmpty()) "$messageId.$ext" else messageId

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        // 1. Insert optimistic placeholder into local DB
        val placeholder = com.glyph.glyph_v3.data.local.entity.LocalMessage(
            id = messageId,
            chatId = chatId,
            text = fileName,                          // filename stored in text
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.DOCUMENT,
            localUri = documentUri.toString(),
            fileSize = fileSize,
            documentCaption = caption.ifBlank { null }
        )
        messageDao.insertMessage(placeholder)
        chatDao.updateLastMessage(chatId, "📄 $fileName", timestamp, userId, MessageStatus.SENDING.name)

        // 2. Generate PDF thumbnail (for PDFs only, synchronously via PdfRenderer)
        var thumbnailUrl: String? = null
        try {
            val thumbBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.glyph.glyph_v3.util.DocumentThumbnailGenerator.generatePdfThumbnailBytes(
                    context, documentUri, fileName
                )
            }
            if (thumbBytes != null) {
                MessagePreviewCacheManager.cacheDocumentThumbnailBytes(context.applicationContext, messageId, thumbBytes)
                val thumbRef = storage.reference.child("chat_document_thumbnails/$chatId/$messageId.jpg")
                thumbRef.putBytes(thumbBytes).await()
                thumbnailUrl = thumbRef.downloadUrl.await().toString()
                // Update placeholder immediately so the sender sees the preview
                messageDao.insertMessage(placeholder.copy(thumbnailUrl = thumbnailUrl))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate/upload document thumbnail for $messageId", e)
        }

        // 3. Upload document to Firebase Storage
        val storageRef = storage.reference.child("chat_documents/$chatId/$storageName")
        val finalThumbnailUrl = thumbnailUrl

        storageRef.putFile(documentUri)
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    val downloadUrl = downloadUri.toString()

                    repositoryScope.launch {
                        // 4. Update local record with download URL + SENT status
                        val sent = placeholder.copy(
                            imageUrl = downloadUrl,
                            thumbnailUrl = finalThumbnailUrl,
                            status = MessageStatus.SENDING
                        )
                        messageDao.insertMessage(sent)

                        // 5. Push to RTDB for instant delivery
                        val messageData = mutableMapOf<String, Any?>(
                            "id"       to messageId,
                            "chatId"   to chatId,
                            "text"     to fileName,
                            "senderId" to userId,
                            "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
                            "type"     to "DOCUMENT",
                            "imageUrl" to downloadUrl,   // reusing imageUrl as fileUrl
                            "fileSize" to fileSize
                        )
                        if (finalThumbnailUrl != null) {
                            messageData["thumbnailUrl"] = finalThumbnailUrl
                        }
                        try {
                            persistToFirestoreAwait(
                                chatId = chatId,
                                messageId = messageId,
                                text = fileName,
                                senderId = userId,
                                otherUserId = otherUserId,
                                timestamp = timestamp,
                                type = "DOCUMENT",
                                imageUrl = downloadUrl,
                                thumbnailUrl = finalThumbnailUrl,
                                fileSize = fileSize,
                                documentCaption = caption.ifBlank { null }
                            )
                            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
                            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, "📄 $fileName", MessageStatus.SENT)

                            rtdb.reference.child("pending_messages").child(otherUserId)
                                .child(messageId).setValue(messageData)
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "RTDB push failed for document $messageId after Firestore persist", e)
                                }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist document message before RTDB push", e)
                            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
                            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, "📄 $fileName", MessageStatus.FAILED)
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Download URL fetch failed for document $messageId", e)
                    repositoryScope.launch {
                        updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Storage upload failed for document $messageId", e)
                repositoryScope.launch {
                    updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.FAILED)
                }
            }
    }

    // ==================== SEND CONTACT ====================
    
    suspend fun sendContactMessage(
        chatId: String,
        contactName: String,
        contactPhone: String,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = contactName,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.CONTACT,
            contactName = contactName,
            contactPhone = contactPhone
        )
        messageDao.insertMessage(localMessage)
        chatDao.updateLastMessage(chatId, "Contact: $contactName", timestamp, userId, MessageStatus.SENDING.name)

        val messageData = mapOf(
            "id" to messageId,
            "chatId" to chatId,
            "text" to contactName,
            "senderId" to userId,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "CONTACT",
            "contactName" to contactName,
            "contactPhone" to contactPhone
        )

        try {
            persistToFirestoreAwait(
                chatId = chatId,
                messageId = messageId,
                text = contactName,
                senderId = userId,
                otherUserId = otherUserId,
                timestamp = timestamp,
                type = "CONTACT",
                contactName = contactName,
                contactPhone = contactPhone
            )
            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, "Contact: $contactName", MessageStatus.SENT)

            rtdb.reference
                .child("pending_messages")
                .child(otherUserId)
                .child(messageId)
                .setValue(messageData)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed direct RTDB contact push after Firestore persist for $messageId", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send contact message", e)
            repositoryScope.launch {
                messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                chatDao.updateLastMessage(chatId, "Contact: $contactName", timestamp, userId, MessageStatus.FAILED.name)
            }
        }
    }

    // ==================== RECEIVE MESSAGES (RTDB LISTENER) ====================
    
    fun startIncomingMessageSync(forceRestart: Boolean = false): DatabaseReference? {
        val userId = currentUserId ?: return null
        StartupTrace.logStage("incoming_sync_attach", "uid=$userId")
        repositoryScope.launch {
            warmRealtimeTransport(reason = "incoming_sync_attach", waitForConnection = false)
        }
        
        // If already syncing for this user and no forced reattach was requested, keep the listener.
        if (!forceRestart && incomingMessageRef != null && incomingMessageRef?.key == userId && incomingMessageListener != null) {
            incomingMessageRef?.keepSynced(true)
            trace(
                stage = "incoming_sync_reuse",
                details = "uid=$userId"
            )
            return incomingMessageRef
        }

        trace(
            stage = if (forceRestart) "incoming_sync_force_restart" else "incoming_sync_start",
            details = "uid=$userId hadRef=${incomingMessageRef != null} hadListener=${incomingMessageListener != null}"
        )
        
        // Remove existing listener if any (only if switching users or starting fresh)
        stopIncomingMessageSync()
        
        incomingMessageRef = rtdb.reference.child("pending_messages").child(userId)
        
        incomingMessageListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                processIncomingMessage(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                // Not expected, but handle gracefully
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                // Message was processed and removed
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Incoming message listener cancelled: ${error.message}")
                trace(
                    stage = "incoming_sync_cancelled",
                    details = "uid=$userId code=${error.code} message=${error.message}"
                )
                stopIncomingMessageSync()
            }
        }
        
        incomingMessageRef?.addChildEventListener(incomingMessageListener as ChildEventListener)
        
        // Force sync of existing children (in case onChildAdded doesn't fire for existing data on reconnect)
        incomingMessageRef?.keepSynced(true)
        
        return incomingMessageRef
    }

    fun restartIncomingMessageSync(): DatabaseReference? {
        return startIncomingMessageSync(forceRestart = true)
    }
    
    private fun processEditNotification(snapshot: DataSnapshot) {
        val messageId = snapshot.child("id").getValue(String::class.java) ?: return
        val chatId = snapshot.child("chatId").getValue(String::class.java) ?: return
        val newText = snapshot.child("text").getValue(String::class.java) ?: return
        val editedAt = snapshot.child("editedAt").getValue(Long::class.java) ?: System.currentTimeMillis()


        repositoryScope.launch {
            applyEditNotificationWithRetry(snapshot, messageId, chatId, newText, editedAt, attemptsLeft = 3)
        }
    }

    private suspend fun applyEditNotificationWithRetry(
        snapshot: DataSnapshot,
        messageId: String,
        chatId: String,
        newText: String,
        editedAt: Long,
        attemptsLeft: Int
    ) {
        try {
            val existingMessage = messageDao.getMessageById(messageId)
            if (existingMessage == null) {
                if (attemptsLeft > 0) {
                    Log.w(TAG, "Edit notification received before message persisted. Retrying... ($attemptsLeft)")
                    delay(350)
                    applyEditNotificationWithRetry(snapshot, messageId, chatId, newText, editedAt, attemptsLeft - 1)
                } else {
                    Log.e(TAG, "Cannot process edit - message $messageId not found after retries")
                    snapshot.ref.removeValue()
                }
                return
            }

            val senderId = snapshot.child("senderId").getValue(String::class.java)
            val senderMatches = senderId != null && senderId == existingMessage.senderId
            val isWithinWindow = (editedAt - existingMessage.timestamp) <= EDIT_WINDOW_MS
            val isEligibleType = existingMessage.type == MessageType.TEXT
            if (!senderMatches || !isWithinWindow || existingMessage.isDeletedForAll || !isEligibleType) {
                Log.w(TAG, "Edit notification rejected for message $messageId (senderMatches=$senderMatches, withinWindow=$isWithinWindow, deleted=${existingMessage.isDeletedForAll}, type=${existingMessage.type})")
                snapshot.ref.removeValue()
                return
            }


            // Update local database - This triggers the Flow in getMessages()
            messageDao.updateMessageText(messageId, newText, editedAt)

            // Update chat last message if this was the most recent message (preserve sender)
            val latestTimestamp = messageDao.getLatestIncomingMessageTimestamp(chatId) ?: 0L
            if (existingMessage.timestamp >= latestTimestamp) {
                // Only update if this is the latest message
                chatDao.updateLastMessage(chatId, newText, existingMessage.timestamp, existingMessage.senderId, existingMessage.status.name)
            }


            // Remove the edit notification from RTDB
            snapshot.ref.removeValue()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process edit notification", e)
        }
    }

    /**
     * Processes a DELETE_FOR_ALL notification written by the Cloud Function.
     * Updates the local Room DB so the receiver immediately sees "This message was deleted",
     * even if the Firestore MODIFIED event hasn't arrived yet (e.g. app backgrounded).
     */
    private fun processDeleteForAllNotification(snapshot: DataSnapshot) {
        val messageId = snapshot.child("id").getValue(String::class.java) ?: return
        val now = System.currentTimeMillis()
        repositoryScope.launch {
            try {
                val existing = messageDao.getMessageById(messageId)
                if (existing != null && !existing.isDeletedForAll) {
                    messageDao.updateDeletedForAllState(messageId, true, now)
                    MessagePreviewCacheManager.evictMessagePreviews(localMessageToPreviewTarget(existing))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process delete-for-all notification for $messageId", e)
            } finally {
                snapshot.ref.removeValue().addOnFailureListener { e ->
                    Log.w(TAG, "Could not remove delete-for-all RTDB node", e)
                }
            }
        }
    }
    
    private fun processIncomingMessage(snapshot: DataSnapshot) {
        val callbackElapsed = SystemClock.elapsedRealtime()
        
        val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key ?: return
        val chatId = snapshot.child("chatId").getValue(String::class.java) ?: return
        val typeStr = snapshot.child("type").getValue(String::class.java) ?: "TEXT"
        val traceKey = traceKey(chatId, id)
        trace(
            stage = "incoming_snapshot",
            details = "trace=$traceKey type=$typeStr key=${snapshot.key}"
        )
        
        
        // Check if this is an edit notification
        if (typeStr == "EDIT") {
            processEditNotification(snapshot)
            return
        }

        // Check if this is a delete-for-all notification from the Cloud Function
        if (typeStr == "DELETE_FOR_ALL") {
            processDeleteForAllNotification(snapshot)
            return
        }
        
        val text = snapshot.child("text").getValue(String::class.java) ?: ""
        val senderId = snapshot.child("senderId").getValue(String::class.java) ?: return
        // Hard block: if I blocked the sender, drop the payload and do not ack delivery.
        if (com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(senderId)) {
            // Remove the pending node to avoid repeated processing, but skip delivery receipts.
            snapshot.ref.removeValue()
            return
        }
        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
        val imageUrl = snapshot.child("imageUrl").getValue(String::class.java)
        val videoUrl = snapshot.child("videoUrl").getValue(String::class.java)
        val audioUrl = snapshot.child("audioUrl").getValue(String::class.java)
        val audioDuration = snapshot.child("audioDuration").getValue(Long::class.java) ?: 0L
        val thumbnailUrl = snapshot.child("thumbnailUrl").getValue(String::class.java)
        val linkPreviewTitle = snapshot.child("linkPreviewTitle").getValue(String::class.java)
        val linkPreviewDomain = snapshot.child("linkPreviewDomain").getValue(String::class.java)
        val linkPreviewDescription = snapshot.child("linkPreviewDescription").getValue(String::class.java)
        val linkPreviewSiteName = snapshot.child("linkPreviewSiteName").getValue(String::class.java)
        val fileSize = snapshot.child("fileSize").getValue(Long::class.java)
        val videoDuration = snapshot.child("videoDuration").getValue(Long::class.java)
        val contactName = snapshot.child("contactName").getValue(String::class.java)
        val contactPhone = snapshot.child("contactPhone").getValue(String::class.java)
        val mediaWidth = (snapshot.child("mediaWidth").value as? Number)?.toInt()
            ?: snapshot.child("mediaWidth").getValue(String::class.java)?.toIntOrNull()
            ?: 0
        val mediaHeight = (snapshot.child("mediaHeight").value as? Number)?.toInt()
            ?: snapshot.child("mediaHeight").getValue(String::class.java)?.toIntOrNull()
            ?: 0
        val isVideoNote = snapshot.child("isVideoNote").getValue(Boolean::class.java) ?: false
        val documentCaption = snapshot.child("documentCaption").getValue(String::class.java)
        val isForwarded = snapshot.child("isForwarded").getValue(Boolean::class.java) ?: false

        val replyToMessageId = snapshot.child("replyToMessageId").getValue(String::class.java)
        val replyToText = snapshot.child("replyToText").getValue(String::class.java)
        val replyToSenderId = snapshot.child("replyToSenderId").getValue(String::class.java)
        val replyToType = snapshot.child("replyToType").getValue(String::class.java)
        val replyPreviewUrl = snapshot.child("replyPreviewUrl").getValue(String::class.java)

        // Status reply fields
        val statusId = snapshot.child("statusId").getValue(String::class.java)
        val statusOwnerId = snapshot.child("statusOwnerId").getValue(String::class.java)
        val statusThumbnailUrl = snapshot.child("statusThumbnailUrl").getValue(String::class.java)
        val statusType = snapshot.child("statusType").getValue(String::class.java)
        val statusText = snapshot.child("statusText").getValue(String::class.java)
        val statusBgColor = (snapshot.child("statusBgColor").value as? Number)?.toInt()

        val type = try { 
            MessageType.valueOf(typeStr) 
        } catch (e: Exception) { 
            MessageType.TEXT 
        }

        // Parse mediaItems - handle both String (legacy) and ArrayList (multi-image collage)
        val mediaItemsJson: String? = try {
            val mediaItemsChild = snapshot.child("mediaItems")
            when {
                !mediaItemsChild.exists() -> {
                    null
                }
                mediaItemsChild.value is String -> {
                    mediaItemsChild.getValue(String::class.java)
                }
                mediaItemsChild.value is ArrayList<*> -> {
                    // Convert ArrayList to JSON string
                    val gson = com.google.gson.Gson()
                    val jsonString = gson.toJson(mediaItemsChild.value)
                    // Log thumbnailBase64 presence
                    val list = mediaItemsChild.value as ArrayList<*>
                    list.forEachIndexed { idx, item ->
                        if (item is Map<*, *>) {
                            val hasThumb = item["thumbnailBase64"] != null
                            val thumbSize = (item["thumbnailBase64"] as? String)?.length ?: 0
                        }
                    }
                    jsonString
                }
                mediaItemsChild.value is List<*> -> {
                    // Handle List as well
                    val gson = com.google.gson.Gson()
                    val jsonString = gson.toJson(mediaItemsChild.value)
                    jsonString
                }
                else -> {
                    android.util.Log.w("RealtimeMessageRepo", "mediaItems is unknown type: ${mediaItemsChild.value?.javaClass?.simpleName} for message $id")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RealtimeMessageRepo", "Error parsing mediaItems for message $id", e)
            null
        }

        android.util.Log.d("MsgOrder", "[RTDB_IN] msgId=${id.takeLast(6)} rtdbTs=$timestamp sender=${senderId.takeLast(6)} → serverTimestamp=null (Firestore ADDED will set authoritative ts)")
        val localMessage = LocalMessage(
            id = id,
            chatId = chatId,
            text = text,
            senderId = senderId,
            timestamp = timestamp,
            // ORDERING FIX: Do NOT use the RTDB server clock here.
            // RTDB and Firestore are separate servers with independent clocks that diverge
            // by ±5–50ms. Using RTDB ts here and Firestore ts on the sender side causes
            // both devices to see different timestamp values for the same message, producing
            // inconsistent ordering. Leave null so the Firestore snapshot listener
            // (syncMessages ADDED handler) fills in the single authoritative Firestore
            // serverTimestamp — guaranteed identical on every device.
            serverTimestamp = null,
            status = when (type) {
                MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.MEDIA_GROUP,
                MessageType.GIF, MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.MEME -> MessageStatus.PENDING_DOWNLOAD
                else -> MessageStatus.DELIVERED
            },
            isIncoming = true,
            type = type,
            imageUrl = imageUrl,
            audioUrl = audioUrl,
            audioDuration = audioDuration,
            videoUrl = videoUrl,
            thumbnailUrl = thumbnailUrl,
            fileSize = fileSize,
            videoDuration = videoDuration,
            contactName = contactName,
            contactPhone = contactPhone,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            mediaItems = mediaItemsJson,
            deliveredTimestamp = System.currentTimeMillis(),
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType,
            replyPreviewUrl = replyPreviewUrl,
            isVideoNote = isVideoNote,
            documentCaption = documentCaption,
            linkPreviewTitle = linkPreviewTitle,
            linkPreviewDomain = linkPreviewDomain,
            linkPreviewDescription = linkPreviewDescription,
            linkPreviewSiteName = linkPreviewSiteName,
            statusId = statusId,
            statusOwnerId = statusOwnerId,
            statusThumbnailUrl = statusThumbnailUrl,
            statusType = statusType,
            statusText = statusText,
            statusBgColor = statusBgColor,
            isForwarded = isForwarded
        )

        // 1. Insert to local DB IMMEDIATELY
        repositoryScope.launch {
            trace(
                stage = "incoming_repo_launch",
                details = "trace=$traceKey queued=${SystemClock.elapsedRealtime() - callbackElapsed}ms"
            )
            val existing = messageDao.getMessageById(id)
            val mergedStatus = if (existing != null && !shouldUpdateStatus(existing.status, localMessage.status)) {
                existing.status
            } else {
                localMessage.status
            }
            var updatedMessage = localMessage.copy(
                text = mergePreservingNonBlankText(localMessage.text, existing?.text),
                status = mergedStatus,
                imageUrl = localMessage.imageUrl ?: existing?.imageUrl,
                audioUrl = localMessage.audioUrl ?: existing?.audioUrl,
                audioDuration = localMessage.audioDuration.takeIf { it > 0 } ?: existing?.audioDuration ?: 0L,
                videoUrl = localMessage.videoUrl ?: existing?.videoUrl,
                thumbnailUrl = localMessage.thumbnailUrl ?: existing?.thumbnailUrl,
                videoDuration = localMessage.videoDuration ?: existing?.videoDuration,
                fileSize = localMessage.fileSize ?: existing?.fileSize,
                contactName = localMessage.contactName ?: existing?.contactName,
                contactPhone = localMessage.contactPhone ?: existing?.contactPhone,
                localUri = existing?.localUri ?: localMessage.localUri,
                mediaWidth = localMessage.mediaWidth.takeIf { it > 0 } ?: existing?.mediaWidth ?: 0,
                mediaHeight = localMessage.mediaHeight.takeIf { it > 0 } ?: existing?.mediaHeight ?: 0,
                mediaItems = localMessage.mediaItems ?: existing?.mediaItems,
                deliveredTimestamp = existing?.deliveredTimestamp ?: localMessage.deliveredTimestamp,
                readTimestamp = existing?.readTimestamp,
                replyToMessageId = localMessage.replyToMessageId ?: existing?.replyToMessageId,
                replyToText = localMessage.replyToText ?: existing?.replyToText,
                replyToSenderId = localMessage.replyToSenderId ?: existing?.replyToSenderId,
                replyToType = localMessage.replyToType ?: existing?.replyToType,
                isEdited = existing?.isEdited ?: localMessage.isEdited,
                editedAt = existing?.editedAt ?: localMessage.editedAt,
                isDeletedForAll = localMessage.isDeletedForAll || (existing?.isDeletedForAll == true),
                deletedAt = localMessage.deletedAt ?: existing?.deletedAt,
                isVideoNote = localMessage.isVideoNote || (existing?.isVideoNote == true),
                documentCaption = localMessage.documentCaption ?: existing?.documentCaption,
                linkPreviewTitle = localMessage.linkPreviewTitle ?: existing?.linkPreviewTitle,
                linkPreviewDomain = localMessage.linkPreviewDomain ?: existing?.linkPreviewDomain,
                linkPreviewDescription = localMessage.linkPreviewDescription ?: existing?.linkPreviewDescription,
                linkPreviewSiteName = localMessage.linkPreviewSiteName ?: existing?.linkPreviewSiteName,
                statusId = localMessage.statusId ?: existing?.statusId,
                statusOwnerId = localMessage.statusOwnerId ?: existing?.statusOwnerId,
                statusThumbnailUrl = localMessage.statusThumbnailUrl ?: existing?.statusThumbnailUrl,
                statusType = localMessage.statusType ?: existing?.statusType,
                statusText = localMessage.statusText ?: existing?.statusText,
                statusBgColor = localMessage.statusBgColor ?: existing?.statusBgColor,
                isForwarded = localMessage.isForwarded || (existing?.isForwarded == true),
                // ORDERING FIX: RTDB can deliver the same message twice with slightly different
                // timestamps (speculative vs confirmed server value). NEVER overwrite an already-
                // stored timestamp or serverTimestamp — the first write is authoritative.
                // Overwriting timestamp causes the message's COALESCE sort key to change,
                // producing a spurious re-sort and visible position change.
                timestamp = existing?.timestamp ?: localMessage.timestamp,
                serverTimestamp = existing?.serverTimestamp ?: localMessage.serverTimestamp
            )
            
            // MEDIA_GROUP merge logic removed - no longer supported
            
            messageDao.insertMessage(updatedMessage)
            trace(
                stage = "incoming_local_inserted",
                details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - callbackElapsed}ms status=${updatedMessage.status} existing=${existing != null}"
            )
            
            // Update or create local chat
            val existingChat = chatDao.getChatById(chatId)
            val isViewing = ActiveChatManager.isCurrentlyViewing(chatId)
            val isGroupChat = com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(chatId)
            // A user-deleted group chat must not be re-created by incoming messages
            val isDeletedGroup = isGroupChat && chatId in deletedGroupChatIds

            if (existingChat != null) {
                val messageStatus = when (type) {
                    MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.MEDIA_GROUP,
                    MessageType.GIF, MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.MEME -> MessageStatus.DELIVERED.name
                    else -> if (isViewing) MessageStatus.READ.name else MessageStatus.DELIVERED.name
                }
                if (!isDeletedGroup) {
                    if (isGroupChat && !existingChat.isGroup) {
                        val repairedUnread = if (isViewing) 0 else (existingChat.unreadCount + 1)
                        fetchGroupAndCreateChat(
                            chatId = chatId,
                            lastMessage = text,
                            timestamp = timestamp,
                            lastMessageSenderId = senderId,
                            messageStatus = messageStatus,
                            unreadCount = repairedUnread,
                            existingChat = existingChat
                        )
                    } else {
                        chatDao.updateLastMessage(chatId, text, timestamp, senderId, messageStatus)
                    }
                }
                
                if (isViewing) {
                    // Chat is open: Do NOT increment unread count. Ensure it's 0.
                    chatDao.clearUnreadCount(chatId)
                    
                    // Also update the just-inserted message status to READ if it's text
                    // (The message was inserted above with default status, likely DELIVERED)
                    if (!type.requiresMediaDownload()) {
                        messageDao.updateMessageStatus(updatedMessage.id, MessageStatus.READ)
                        // Trigger read receipt immediately
                        markChatAsRead(chatId)
                    }
                } else if (!isDeletedGroup) {
                    chatDao.incrementUnreadCount(chatId)
                }
            } else {
                val messageStatus = when (type) {
                    MessageType.IMAGE, MessageType.VIDEO, MessageType.AUDIO, MessageType.MEDIA_GROUP,
                    MessageType.GIF, MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.MEME -> MessageStatus.DELIVERED.name
                    else -> if (isViewing) MessageStatus.READ.name else MessageStatus.DELIVERED.name
                }
                if (!isDeletedGroup) {
                    if (isGroupChat) {
                        fetchGroupAndCreateChat(
                            chatId = chatId,
                            lastMessage = text,
                            timestamp = timestamp,
                            lastMessageSenderId = senderId,
                            messageStatus = messageStatus
                        )
                    } else {
                        fetchUserAndCreateChat(chatId, senderId, text, timestamp, messageStatus)
                    }
                }
                
                if (isViewing) {
                    if (!type.requiresMediaDownload()) {
                        messageDao.updateMessageStatus(updatedMessage.id, MessageStatus.READ)
                        markChatAsRead(chatId)
                    }
                }
            }

            trace(
                stage = "incoming_chat_updated",
                details = "trace=$traceKey elapsed=${SystemClock.elapsedRealtime() - callbackElapsed}ms viewing=${isViewing} existingChat=${existingChat != null}"
            )

            if (type.requiresMediaDownload()) {
                scheduleMediaDownload(updatedMessage)
            }

        }

        // 2. Send Delivery Receipt via RTDB (CRITICAL: Send BEFORE deleting message)
        // This ensures that if the app crashes or network fails, the message remains pending
        // and we will retry sending the receipt next time.
        val receiptRef = rtdb.reference
            .child("delivery_receipts")
            .child(senderId) // Send to sender
            .child(chatId)
            .child(id)
            
        receiptRef.setValue(System.currentTimeMillis())
            .addOnSuccessListener {
                // 3. Only delete from RTDB after receipt is successfully sent
                snapshot.ref.removeValue()
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to remove message $id from RTDB", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send delivery receipt via RTDB", e)
                // Do NOT delete the message. It will be retried on next sync.
            }
        
        // 4. Update Firestore status to DELIVERED (client-side fallback)
        // This is a redundant fallback in case the Cloud Function fails.
        val updateData = mapOf(
            "status" to "DELIVERED",
            "deliveredTimestamp" to System.currentTimeMillis()
        )
        firestore.collection("chats").document(chatId)
            .collection("messages").document(id)
            .update(updateData)
            .addOnFailureListener { e ->
                // This is expected if server already updated it
            }
    }
    
    fun stopIncomingMessageSync() {
        incomingMessageListener?.let { listener ->
            incomingMessageRef?.removeEventListener(listener)
        }
        incomingMessageListener = null
        incomingMessageRef = null
    }

    fun startDeliveryReceiptListener(chatId: String, forceRestart: Boolean = false) {
        val userId = currentUserId ?: return
        if (chatId.isBlank()) return
        if (!forceRestart && deliveryReceiptListeners.containsKey(chatId)) return
        if (forceRestart) {
            stopDeliveryReceiptListener(chatId)
        }
        trace(stage = "delivery_listener_start", details = "chatId=${chatId.takeLast(6)} user=${userId.takeLast(6)} force=$forceRestart")

        val ref = rtdb.reference
            .child("delivery_receipts")
            .child(userId)
            .child(chatId)
        ref.keepSynced(true)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleDeliveryReceipt(chatId, snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleDeliveryReceipt(chatId, snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Delivery receipt listener cancelled for $chatId: ${error.message}")
                trace(stage = "delivery_listener_cancelled", details = "chatId=${chatId.takeLast(6)} error=${error.message}")
                stopDeliveryReceiptListener(chatId)
            }
        }

        ref.addChildEventListener(listener)
        deliveryReceiptListeners[chatId] = listener
        deliveryReceiptRefs[chatId] = ref
    }

    fun stopDeliveryReceiptListener(chatId: String) {
        val listener = deliveryReceiptListeners.remove(chatId)
        val ref = deliveryReceiptRefs.remove(chatId)
        if (listener != null && ref != null) {
            ref.removeEventListener(listener)
        }
    }

    fun startGlobalDeliveryReceiptSync(forceRestart: Boolean = false) {
        val userId = currentUserId ?: return
        if (!forceRestart && allDeliveryReceiptsListener != null) return
        if (forceRestart) {
            stopGlobalDeliveryReceiptSync()
        }
        trace(stage = "delivery_global_listener_start", details = "user=${userId.takeLast(6)} force=$forceRestart")

        val ref = rtdb.reference
            .child("delivery_receipts")
            .child(userId)
        ref.keepSynced(true)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val chatId = snapshot.key ?: return
                // Attaching the per-chat listener will emit childAdded for existing message receipts.
                trace(stage = "delivery_global_chat_added", details = "chatId=${chatId.takeLast(6)}")
                startDeliveryReceiptListener(chatId, forceRestart = true)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val chatId = snapshot.key ?: return
                trace(stage = "delivery_global_chat_changed", details = "chatId=${chatId.takeLast(6)}")
                startDeliveryReceiptListener(chatId, forceRestart = true)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val chatId = snapshot.key ?: return
                trace(stage = "delivery_global_chat_removed", details = "chatId=${chatId.takeLast(6)}")
                stopDeliveryReceiptListener(chatId)
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Global delivery receipt listener cancelled: ${error.message}")
                trace(stage = "delivery_global_listener_cancelled", details = "error=${error.message}")
                stopGlobalDeliveryReceiptSync()
            }
        }

        ref.addChildEventListener(listener)
        allDeliveryReceiptsRef = ref
        allDeliveryReceiptsListener = listener
        ensureDeliveryReceiptReconciliationLoop()
        scheduleDeliveryReceiptReconciliation(reason = "delivery_sync_start", delayMs = 0L)
    }

    fun stopGlobalDeliveryReceiptSync() {
        val listener = allDeliveryReceiptsListener
        val ref = allDeliveryReceiptsRef
        if (listener != null && ref != null) {
            ref.removeEventListener(listener)
            ref.keepSynced(false)
        }
        allDeliveryReceiptsListener = null
        allDeliveryReceiptsRef = null

        // Also stop all per-chat listeners.
        val chatIds = deliveryReceiptListeners.keys.toList()
        chatIds.forEach { stopDeliveryReceiptListener(it) }

        deliveryReceiptReconciliationJob?.cancel()
        deliveryReceiptReconciliationJob = null
    }

    fun restartGlobalDeliveryReceiptSync() {
        startGlobalDeliveryReceiptSync(forceRestart = true)
    }

    private fun handleDeliveryReceipt(chatId: String, snapshot: DataSnapshot) {
        val messageId = snapshot.key ?: return
        val traceId = traceKey(chatId, messageId)
        trace(
            stage = "delivery_rtdb_event",
            details = "trace=$traceId hasChildren=${snapshot.hasChildren()} rawType=${snapshot.value?.javaClass?.simpleName ?: "null"}"
        )
        
        // Robust timestamp parsing to handle both Long (new) and Object (old/legacy) formats
        val timestamp = try {
            snapshot.getValue(Long::class.java)
        } catch (e: Exception) {
            // Try to parse as object with deliveredAt field
            snapshot.child("deliveredAt").getValue(Long::class.java)
        } ?: System.currentTimeMillis()

        repositoryScope.launch {
            try {
                val existing = messageDao.getMessageById(messageId)
                if (existing == null) {
                    trace(stage = "delivery_rtdb_orphan", details = "trace=$traceId")
                    return@launch
                }

                // Receipts are only meaningful for outgoing messages.
                if (existing.isIncoming) {
                    trace(stage = "delivery_rtdb_skip_incoming", details = "trace=$traceId local=${summarizeLocalMessage(existing)}")
                    return@launch
                }

                if (existing.status == MessageStatus.READ || existing.status == MessageStatus.PLAYED || existing.status == MessageStatus.DELIVERED) {
                    // Even if already delivered, update the timestamp if we have a more accurate one
                    if (existing.deliveredTimestamp == null || existing.deliveredTimestamp == 0L) {
                         messageDao.updateMessageDelivered(messageId, existing.status, timestamp)
                    }
                    trace(stage = "delivery_rtdb_already_applied", details = "trace=$traceId local=${summarizeLocalMessage(existing)} ts=$timestamp")
                    return@launch
                }

                // Update local DB with the timestamp from the receipt
                messageDao.updateMessageDelivered(messageId, MessageStatus.DELIVERED, timestamp)
                // Bypass Room→DiffUtil pipeline: notify the UI immediately.
                _statusUpdateEvents.tryEmit(messageId to MessageStatus.DELIVERED)
                trace(stage = "delivery_rtdb_applied", details = "trace=$traceId previous=${existing.status} ts=$timestamp")

                val userId = currentUserId ?: return@launch
                val chat = chatDao.getChatById(chatId)
                if (chat != null && chat.lastMessageSenderId == userId && chat.lastMessageTimestamp == existing.timestamp && chat.lastMessage == existing.text) {
                    updateChatLastMessageStatusMonotonic(chatId, userId, existing.timestamp, existing.text, MessageStatus.DELIVERED)
                }
            } finally {
                // Relay cleanup: receipt has been consumed, remove the RTDB node.
                snapshot.ref.removeValue()
                    .addOnSuccessListener {
                        trace(stage = "delivery_rtdb_cleanup", details = "trace=$traceId")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to remove delivery receipt $messageId from RTDB", e)
                        trace(stage = "delivery_rtdb_cleanup_failed", details = "trace=$traceId error=${e.message}")
                    }
            }
        }
    }

    private fun ensureDeliveryReceiptReconciliationLoop() {
        if (deliveryReceiptReconciliationJob?.isActive == true) {
            return
        }

        deliveryReceiptReconciliationJob = repositoryScope.launch {
            delay(5_000L)
            while (true) {
                reconcileOutstandingDeliveryReceipts(reason = "periodic")
                delay(DELIVERY_RECONCILIATION_INTERVAL_MS)
            }
        }
    }

    private fun scheduleDeliveryReceiptReconciliation(reason: String, delayMs: Long = 0L) {
        repositoryScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            reconcileOutstandingDeliveryReceipts(reason)
        }
    }

    private suspend fun reconcileOutstandingDeliveryReceipts(reason: String) {
        val userId = currentUserId ?: return

        deliveryReconciliationMutex.withLock {
            val pendingMessages = messageDao.getOutgoingMessagesByStatuses(
                statuses = listOf(MessageStatus.SENT),
                limit = DELIVERY_RECONCILIATION_BATCH_SIZE
            )
            if (pendingMessages.isEmpty()) {
                return
            }

            trace(
                stage = "delivery_reconcile_start",
                details = "reason=$reason pending=${pendingMessages.size}"
            )

            pendingMessages.forEach { localMessage ->
                runCatching {
                    val remoteDoc = firestore.collection("chats")
                        .document(localMessage.chatId)
                        .collection("messages")
                        .document(localMessage.id)
                        .get(Source.SERVER)
                        .await()

                    if (!remoteDoc.exists()) {
                        return@runCatching
                    }

                    val remoteStatus = runCatching {
                        MessageStatus.valueOf(remoteDoc.getString("status") ?: MessageStatus.SENT.name)
                    }.getOrDefault(MessageStatus.SENT)

                    if (outgoingStatusRank(remoteStatus) ?: 0 <= outgoingStatusRank(localMessage.status) ?: 0) {
                        return@runCatching
                    }

                    if (remoteStatus != MessageStatus.DELIVERED && remoteStatus != MessageStatus.READ && remoteStatus != MessageStatus.PLAYED) {
                        return@runCatching
                    }

                    val deliveredTimestamp = remoteDoc.getLong("deliveredTimestamp")
                        ?: localMessage.deliveredTimestamp
                        ?: System.currentTimeMillis()
                    val readTimestamp = if (remoteStatus == MessageStatus.READ || remoteStatus == MessageStatus.PLAYED) {
                        remoteDoc.getLong("readTimestamp")
                            ?: localMessage.readTimestamp
                            ?: deliveredTimestamp
                    } else {
                        null
                    }

                    messageDao.updateMessageReceiptState(
                        id = localMessage.id,
                        status = remoteStatus,
                        deliveredTimestamp = deliveredTimestamp,
                        readTimestamp = readTimestamp
                    )
                    // Bypass Room→DiffUtil pipeline: notify the UI immediately.
                    _statusUpdateEvents.tryEmit(localMessage.id to remoteStatus)

                    val chat = chatDao.getChatById(localMessage.chatId)
                    if (chat != null && chat.lastMessageSenderId == userId && chat.lastMessageTimestamp == localMessage.timestamp && chat.lastMessage == localMessage.text) {
                        updateChatLastMessageStatusMonotonic(
                            chatId = localMessage.chatId,
                            senderId = userId,
                            timestamp = localMessage.timestamp,
                            messageText = localMessage.text,
                            newStatus = remoteStatus
                        )
                    }

                    rtdb.reference
                        .child("delivery_receipts")
                        .child(userId)
                        .child(localMessage.chatId)
                        .child(localMessage.id)
                        .removeValue()
                        .addOnFailureListener { error ->
                            Log.w(TAG, "Failed to clear stale delivery receipt ${localMessage.id}", error)
                        }

                    trace(
                        stage = "delivery_reconcile_hit",
                        details = "reason=$reason trace=${traceKey(localMessage.chatId, localMessage.id)} status=$remoteStatus"
                    )
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Delivery receipt reconciliation failed for ${localMessage.id} (${localMessage.chatId})",
                        error
                    )
                }
            }
        }
    }

    // ==================== FIRESTORE PERSISTENCE (Background) ====================
    
    private fun persistToFirestore(
        chatId: String,
        messageId: String,
        text: String,
        senderId: String,
        otherUserId: String,
        timestamp: Long,
        type: String,
        imageUrl: String? = null,
        videoUrl: String? = null,
        thumbnailUrl: String? = null,
        linkPreviewTitle: String? = null,
        linkPreviewDomain: String? = null,
        linkPreviewDescription: String? = null,
        linkPreviewSiteName: String? = null,
        fileSize: Long? = null,
        videoDuration: Long? = null,
        mediaWidth: Int = 0,
        mediaHeight: Int = 0,
        contactName: String? = null,
        contactPhone: String? = null,
        deliveredTimestamp: Long? = null,
        readTimestamp: Long? = null,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: String? = null,
        replyPreviewUrl: String? = null,
        audioUrl: String? = null,
        audioDuration: Long? = null,
        mediaItems: String? = null,
        statusId: String? = null,
        statusOwnerId: String? = null,
        statusThumbnailUrl: String? = null,
        statusType: String? = null,
        statusText: String? = null,
        statusBgColor: Int? = null,
        status: String? = MessageStatus.SENT.name,
        isVideoNote: Boolean? = null,
        documentCaption: String? = null,
        mediaCount: Int? = null,
        isForwarded: Boolean = false,
        onMessagePersisted: (() -> Unit)? = null,
        onMessagePersistFailed: ((Exception) -> Unit)? = null
    ) {
        val messageData = hashMapOf(
            "id" to messageId,
            "text" to text,
            "senderId" to senderId,
            "timestamp" to timestamp,
            "serverTimestamp" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "type" to type
        )

        imageUrl?.let { messageData["imageUrl"] = it }
        videoUrl?.let { messageData["videoUrl"] = it }
        thumbnailUrl?.let { messageData["thumbnailUrl"] = it }
        linkPreviewTitle?.let { messageData["linkPreviewTitle"] = it }
        linkPreviewDomain?.let { messageData["linkPreviewDomain"] = it }
        linkPreviewDescription?.let { messageData["linkPreviewDescription"] = it }
        linkPreviewSiteName?.let { messageData["linkPreviewSiteName"] = it }
        fileSize?.let { messageData["fileSize"] = it }
        videoDuration?.let { messageData["videoDuration"] = it }
        if (mediaWidth > 0) messageData["mediaWidth"] = mediaWidth
        if (mediaHeight > 0) messageData["mediaHeight"] = mediaHeight
        contactName?.let { messageData["contactName"] = it }
        contactPhone?.let { messageData["contactPhone"] = it }
        deliveredTimestamp?.let { messageData["deliveredTimestamp"] = it }
        readTimestamp?.let { messageData["readTimestamp"] = it }
        audioUrl?.let { messageData["audioUrl"] = it }
        audioDuration?.let { messageData["audioDuration"] = it }
        mediaItems?.let { messageData["mediaItems"] = it }

        replyToMessageId?.let { messageData["replyToMessageId"] = it }
        replyToText?.let { messageData["replyToText"] = it }
        replyToSenderId?.let { messageData["replyToSenderId"] = it }
        replyToType?.let { messageData["replyToType"] = it }
        replyPreviewUrl?.let { messageData["replyPreviewUrl"] = it }
        statusId?.let { messageData["statusId"] = it }
        statusOwnerId?.let { messageData["statusOwnerId"] = it }
        statusThumbnailUrl?.let { messageData["statusThumbnailUrl"] = it }
        statusType?.let { messageData["statusType"] = it }
        statusText?.let { messageData["statusText"] = it }
        statusBgColor?.let { messageData["statusBgColor"] = it }
        status?.let { messageData["status"] = it }
        isVideoNote?.let { messageData["isVideoNote"] = it }
        documentCaption?.let { messageData["documentCaption"] = it }
        mediaCount?.let { messageData["mediaCount"] = it }
        if (isForwarded) messageData["isForwarded"] = true

        val chatData = hashMapOf(
            "participants" to listOf(senderId, otherUserId),
            "lastMessage" to text,
            "lastMessageTimestamp" to timestamp,
            "lastMessageSenderId" to senderId
        )

        // Non-blocking Firestore writes for history
        firestore.collection("chats").document(chatId)
            .set(chatData, com.google.firebase.firestore.SetOptions.merge())
        
        firestore.collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .set(messageData, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                onMessagePersisted?.invoke()
            }
            .addOnFailureListener { error ->
                onMessagePersistFailed?.invoke(error)
            }

    }

    private suspend fun persistToFirestoreAwait(
        chatId: String,
        messageId: String,
        text: String,
        senderId: String,
        otherUserId: String,
        timestamp: Long,
        type: String,
        imageUrl: String? = null,
        videoUrl: String? = null,
        thumbnailUrl: String? = null,
        linkPreviewTitle: String? = null,
        linkPreviewDomain: String? = null,
        linkPreviewDescription: String? = null,
        linkPreviewSiteName: String? = null,
        fileSize: Long? = null,
        videoDuration: Long? = null,
        mediaWidth: Int = 0,
        mediaHeight: Int = 0,
        contactName: String? = null,
        contactPhone: String? = null,
        deliveredTimestamp: Long? = null,
        readTimestamp: Long? = null,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: String? = null,
        replyPreviewUrl: String? = null,
        audioUrl: String? = null,
        audioDuration: Long? = null,
        mediaItems: String? = null,
        statusId: String? = null,
        statusOwnerId: String? = null,
        statusThumbnailUrl: String? = null,
        statusType: String? = null,
        statusText: String? = null,
        statusBgColor: Int? = null,
        status: String? = MessageStatus.SENT.name,
        isVideoNote: Boolean? = null,
        documentCaption: String? = null,
        mediaCount: Int? = null,
        isForwarded: Boolean = false
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        persistToFirestore(
            chatId = chatId,
            messageId = messageId,
            text = text,
            senderId = senderId,
            otherUserId = otherUserId,
            timestamp = timestamp,
            type = type,
            imageUrl = imageUrl,
            videoUrl = videoUrl,
            thumbnailUrl = thumbnailUrl,
            linkPreviewTitle = linkPreviewTitle,
            linkPreviewDomain = linkPreviewDomain,
            linkPreviewDescription = linkPreviewDescription,
            linkPreviewSiteName = linkPreviewSiteName,
            fileSize = fileSize,
            videoDuration = videoDuration,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            contactName = contactName,
            contactPhone = contactPhone,
            deliveredTimestamp = deliveredTimestamp,
            readTimestamp = readTimestamp,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType,
            replyPreviewUrl = replyPreviewUrl,
            audioUrl = audioUrl,
            audioDuration = audioDuration,
            mediaItems = mediaItems,
            statusId = statusId,
            statusOwnerId = statusOwnerId,
            statusThumbnailUrl = statusThumbnailUrl,
            statusType = statusType,
            statusText = statusText,
            statusBgColor = statusBgColor,
            status = status,
            isVideoNote = isVideoNote,
            documentCaption = documentCaption,
            mediaCount = mediaCount,
            isForwarded = isForwarded,
            onMessagePersisted = {
                if (continuation.isActive) continuation.resume(Unit)
            },
            onMessagePersistFailed = { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        )
    }

    private fun fetchUserAndCreateChat(chatId: String, otherUserId: String, lastMessage: String, timestamp: Long, messageStatus: String = MessageStatus.DELIVERED.name) {
        if (com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(chatId)) {
            fetchGroupAndCreateChat(
                chatId = chatId,
                lastMessage = lastMessage,
                timestamp = timestamp,
                lastMessageSenderId = otherUserId,
                messageStatus = messageStatus
            )
            return
        }
        firestore.collection("users").document(otherUserId).get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username") ?: "Unknown"
                val avatar = doc.getString("profileImageUrl") ?: ""
                
                repositoryScope.launch {
                    val isViewing = ActiveChatManager.isCurrentlyViewing(chatId)
                    val initialUnread = if (isViewing) 0 else 1

                    val newChat = LocalChat(
                        id = chatId,
                        otherUserId = otherUserId,
                        otherUsername = username,
                        otherUserAvatar = avatar,
                        lastMessage = lastMessage,
                        lastMessageTimestamp = timestamp,
                        lastMessageSenderId = otherUserId,
                        lastMessageStatus = messageStatus,
                        unreadCount = initialUnread
                    )
                    chatDao.insertChat(newChat)
                    
                    // Cache avatar for instant loading
                    if (avatar.isNotEmpty()) {
                        com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheAvatar(
                            otherUserId, 
                            avatar, 
                            context
                        )
                    }
                }
            }
            .addOnFailureListener {
                repositoryScope.launch {
                    val isViewing = ActiveChatManager.isCurrentlyViewing(chatId)
                    val initialUnread = if (isViewing) 0 else 1

                    val newChat = LocalChat(
                        id = chatId,
                        otherUserId = otherUserId,
                        otherUsername = "Unknown",
                        otherUserAvatar = "",
                        lastMessage = lastMessage,
                        lastMessageTimestamp = timestamp,
                        lastMessageSenderId = otherUserId,
                        lastMessageStatus = messageStatus,
                        unreadCount = initialUnread
                    )
                    chatDao.insertChat(newChat)
                }
            }
    }

    private fun fetchGroupAndCreateChat(
        chatId: String,
        lastMessage: String,
        timestamp: Long,
        lastMessageSenderId: String,
        messageStatus: String = MessageStatus.DELIVERED.name,
        existingChat: LocalChat? = null,
        unreadCount: Int? = null
    ) {
        firestore.collection("chats").document(chatId).get()
            .addOnSuccessListener { doc ->
                repositoryScope.launch {
                    val isViewing = ActiveChatManager.isCurrentlyViewing(chatId)
                    val participants = (doc.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                    val admins = (doc.get("admins") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                    val resolvedUnread = unreadCount ?: if (isViewing) 0 else 1
                    val groupName = doc.getString("groupName").orEmpty()
                        .ifBlank { existingChat?.groupName?.ifBlank { "Group" } ?: "Group" }
                    val groupIconUrl = doc.getString("groupIconUrl").orEmpty()
                        .ifBlank { existingChat?.groupIconUrl.orEmpty() }
                    val groupDescription = doc.getString("groupDescription").orEmpty()
                        .ifBlank { existingChat?.groupDescription.orEmpty() }
                    val createdBy = doc.getString("createdBy").orEmpty()
                        .ifBlank { existingChat?.createdBy.orEmpty() }
                    val createdAt = doc.getLong("createdAt") ?: existingChat?.createdAt ?: 0L

                    chatDao.insertChat(
                        LocalChat(
                            id = chatId,
                            otherUserId = "",
                            otherUsername = "",
                            otherUserAvatar = "",
                            lastMessage = lastMessage,
                            lastMessageTimestamp = timestamp,
                            lastMessageSenderId = lastMessageSenderId,
                            lastMessageStatus = messageStatus,
                            unreadCount = resolvedUnread,
                            isArchived = existingChat?.isArchived ?: false,
                            isGroup = true,
                            groupName = groupName,
                            groupIconUrl = groupIconUrl,
                            groupDescription = groupDescription,
                            createdBy = createdBy,
                            createdAt = createdAt,
                            participantsJson = participants.takeIf { it.isNotEmpty() }
                                ?.let { com.glyph.glyph_v3.data.repo.GroupChatRepository.encodeStringList(it) }
                                ?: existingChat?.participantsJson,
                            adminsJson = admins.takeIf { it.isNotEmpty() }
                                ?.let { com.glyph.glyph_v3.data.repo.GroupChatRepository.encodeStringList(it) }
                                ?: existingChat?.adminsJson
                        )
                    )
                }
            }
            .addOnFailureListener {
                repositoryScope.launch {
                    val isViewing = ActiveChatManager.isCurrentlyViewing(chatId)
                    val resolvedUnread = unreadCount ?: if (isViewing) 0 else 1
                    chatDao.insertChat(
                        LocalChat(
                            id = chatId,
                            otherUserId = "",
                            otherUsername = "",
                            otherUserAvatar = "",
                            lastMessage = lastMessage,
                            lastMessageTimestamp = timestamp,
                            lastMessageSenderId = lastMessageSenderId,
                            lastMessageStatus = messageStatus,
                            unreadCount = resolvedUnread,
                            isArchived = existingChat?.isArchived ?: false,
                            isGroup = true,
                            groupName = existingChat?.groupName?.ifBlank { "Group" } ?: "Group",
                            groupIconUrl = existingChat?.groupIconUrl.orEmpty(),
                            groupDescription = existingChat?.groupDescription.orEmpty(),
                            createdBy = existingChat?.createdBy.orEmpty(),
                            createdAt = existingChat?.createdAt ?: 0L,
                            participantsJson = existingChat?.participantsJson,
                            adminsJson = existingChat?.adminsJson
                        )
                    )
                }
            }
    }

    // ==================== SYNC FROM FIRESTORE (For chat history) ====================
    
    fun syncMessages(
        chatId: String,
        latestLocalTimestamp: Long = 0L
    ): com.google.firebase.firestore.ListenerRegistration {
        ChatOpenTrace.event(chatId, "firestore_sync_listener_init", "latestLocal=$latestLocalTimestamp")
        val syncStartTimestamp = if (latestLocalTimestamp > 0L) {
            (latestLocalTimestamp - CHAT_SYNC_OVERLAP_WINDOW_MS).coerceAtLeast(0L)
        } else {
            0L
        }

        val query = firestore.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
            .let { baseQuery ->
                if (syncStartTimestamp > 0L) {
                    baseQuery.whereGreaterThanOrEqualTo("timestamp", syncStartTimestamp)
                } else {
                    baseQuery.limitToLast(INITIAL_CHAT_SYNC_LIMIT)
                }
            }

        return query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                ChatOpenTrace.event(chatId, "firestore_sync_snapshot_error", "error=${error::class.java.simpleName}")
                Log.e(TAG, "syncMessages error", error)
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener

            val changes = snapshot.documentChanges.toList()
            if (changes.isEmpty()) return@addSnapshotListener
            ChatOpenTrace.event(
                chatId,
                "firestore_sync_snapshot",
                "changes=${changes.size} added=${changes.count { it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED }} modified=${changes.count { it.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED }} fromCache=${snapshot.metadata.isFromCache}"
            )
            trace(
                stage = "firestore_sync_snapshot",
                details = "chatId=$chatId changes=${changes.size} added=${changes.count { it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED }} modified=${changes.count { it.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED }} latestLocal=$latestLocalTimestamp"
            )

            repositoryScope.launch {
                chatSyncMutexes.getOrPut(chatId) { Mutex() }.withLock {
                    val committedChanges = changes.filterNot { change ->
                        val pendingWrites = change.document.metadata.hasPendingWrites()
                        if (pendingWrites) {
                            trace(
                                stage = "firestore_sync_skip_pending",
                                details = "chatId=$chatId doc=${change.document.id.take(8)} type=${change.type.name}"
                            )
                        }
                        pendingWrites
                    }

                    val relevantChanges = committedChanges.filter { change ->
                        val senderIdForBlock = change.document.getString("senderId") ?: ""
                        val isIncomingFromBlocked = senderIdForBlock.isNotEmpty() &&
                            senderIdForBlock != currentUserId &&
                            com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(senderIdForBlock)
                        !isIncomingFromBlocked
                    }

                    val modifiedDocs = relevantChanges
                        .filter { it.type == com.google.firebase.firestore.DocumentChange.Type.MODIFIED }
                        .map { it.document }

                    if (modifiedDocs.isNotEmpty()) {
                        val existingById = messageDao.getMessagesByIds(modifiedDocs.map { it.id }).associateBy { it.id }
                        for (doc in modifiedDocs) {
                            val id = doc.id
                            if (deletedMessageDao.isMessageDeletedForMe(id)) continue

                            val current = existingById[id] ?: continue
                            val statusStr = doc.getString("status") ?: MessageStatus.SENT.name
                            val status = try {
                                MessageStatus.valueOf(statusStr)
                            } catch (e: Exception) {
                                MessageStatus.SENT
                            }
                            val isDeletedForAll = doc.getBoolean("isDeletedForAll") == true
                            val deletedAtMs: Long? = when (val raw = doc.get("deletedAt")) {
                                is Number -> raw.toLong()
                                is com.google.firebase.Timestamp -> raw.toDate().time
                                else -> null
                            }

                            if (isDeletedForAll && (!current.isDeletedForAll || current.deletedAt != deletedAtMs)) {
                                messageDao.updateDeletedForAllState(id, true, deletedAtMs)
                            }

                            if (shouldUpdateStatus(current.status, status)) {
                                if (status == MessageStatus.DELIVERED) {
                                    val deliveredTimestamp = doc.getLong("deliveredTimestamp") ?: 0L
                                    if (deliveredTimestamp > 0L) {
                                        messageDao.updateMessageDelivered(id, status, deliveredTimestamp)
                                    } else {
                                        messageDao.updateMessageStatus(id, status)
                                    }
                                } else {
                                    messageDao.updateMessageStatus(id, status)
                                }
                                // Bypass Room→DiffUtil for outgoing messages: notify the UI immediately.
                                if (!current.isIncoming) {
                                    _statusUpdateEvents.tryEmit(id to status)
                                }
                            }

                            // Always use the Firestore serverTimestamp — it is the single
                            // authoritative clock. Overwrite any previously stored value
                            // (e.g. a stale RTDB ts) so every device converges to the same value.
                            val modDocServerTimestamp: Long? = when (val sts = doc.get("serverTimestamp")) {
                                is Number -> sts.toLong()
                                is com.google.firebase.Timestamp -> sts.toDate().time
                                else -> null
                            }
                            if (modDocServerTimestamp != null) {
                                android.util.Log.d("MsgOrder", "[FS_MOD] msgId=${id.takeLast(6)} fsTs=$modDocServerTimestamp prevTs=${current.serverTimestamp} ${if (current.serverTimestamp != modDocServerTimestamp) "→ UPDATED" else "→ no-op"}")
                                messageDao.updateServerTimestamp(id, modDocServerTimestamp)
                            }

                            val docStatusBgColor = (doc.get("statusBgColor") as? Number)?.toInt()
                            val docIsForwarded = doc.getBoolean("isForwarded") == true
                            val docTypeStr = doc.getString("type")
                            val docMediaItemsJson = mediaItemsJsonFromFirestoreDoc(doc)
                            val mergedText = mergePreservingNonBlankText(doc.getString("text"), current.text)
                            if (docIsForwarded && !current.isForwarded) {
                                messageDao.insertMessage(current.copy(isForwarded = true))
                            }
                            if (
                                (current.type == MessageType.MEDIA_GROUP || docTypeStr == MessageType.MEDIA_GROUP.name) &&
                                !hasMediaGroupItems(current.mediaItems) &&
                                hasMediaGroupItems(docMediaItemsJson)
                            ) {
                                messageDao.updateMessageMediaItems(id, docMediaItemsJson!!)
                                if (current.isIncoming) {
                                    scheduleMediaDownload(current.copy(mediaItems = docMediaItemsJson))
                                }
                            }
                            val needsStatusReplyMerge =
                                (current.type == MessageType.STATUS_REPLY || (docTypeStr == MessageType.STATUS_REPLY.name)) && (
                                    (current.statusId.isNullOrBlank() && !doc.getString("statusId").isNullOrBlank()) ||
                                    (current.statusOwnerId.isNullOrBlank() && !doc.getString("statusOwnerId").isNullOrBlank()) ||
                                    (current.statusThumbnailUrl.isNullOrBlank() && !doc.getString("statusThumbnailUrl").isNullOrBlank()) ||
                                    (current.statusType.isNullOrBlank() && !doc.getString("statusType").isNullOrBlank()) ||
                                    (current.statusText.isNullOrBlank() && !doc.getString("statusText").isNullOrBlank()) ||
                                    (current.statusBgColor == null && docStatusBgColor != null)
                                )
                            val needsTextBackfill = mergedText != current.text
                            if (needsStatusReplyMerge) {
                                messageDao.insertMessage(
                                    current.copy(
                                        text = mergedText,
                                        statusId = current.statusId ?: doc.getString("statusId"),
                                        statusOwnerId = current.statusOwnerId ?: doc.getString("statusOwnerId"),
                                        statusThumbnailUrl = current.statusThumbnailUrl ?: doc.getString("statusThumbnailUrl"),
                                        statusType = current.statusType ?: doc.getString("statusType"),
                                        statusText = current.statusText ?: doc.getString("statusText"),
                                        statusBgColor = current.statusBgColor ?: docStatusBgColor,
                                        isForwarded = current.isForwarded || docIsForwarded
                                    )
                                )
                            } else if (needsTextBackfill) {
                                messageDao.insertMessage(current.copy(text = mergedText))
                            }

                            // Reactions: Firestore is authoritative — overwrite local whenever
                            // the remote map differs (covers both add & remove cases).
                            // Skip if a local reaction write is still in-flight: a stale Firestore
                            // snapshot captured before our write was queued could otherwise undo
                            // the optimistic removal (race condition on the hasPendingWrites flag).
                            val remoteReactionsJson = serializeReactions(extractReactionsFromDoc(doc))
                            if (remoteReactionsJson != current.reactionsJson && !pendingReactionWrites.contains(id)) {
                                val previousReactions = parseReactions(current.reactionsJson)
                                val nextReactions = parseReactions(remoteReactionsJson)
                                messageDao.updateReactions(id, remoteReactionsJson)
                                syncChatPreviewForReactionChange(chatId, id, previousReactions, nextReactions)
                            }
                        }
                    }

                    val addedDocs = relevantChanges
                        .filter { it.type == com.google.firebase.firestore.DocumentChange.Type.ADDED }
                        .map { it.document }

                    if (addedDocs.isNotEmpty()) {
                        val addedIds = addedDocs.map { it.id }
                        val existingById = messageDao.getMessagesByIds(addedIds).associateBy { it.id }
                        val deletedByMeIds = addedIds.filter { deletedMessageDao.isMessageDeletedForMe(it) }.toSet()
                        val messagesToInsert = mutableListOf<LocalMessage>()
                        val incomingDocsToDelete = mutableListOf<com.google.firebase.firestore.DocumentSnapshot>()

                        for (doc in addedDocs) {
                            val id = doc.id
                            if (id in deletedByMeIds) continue

                            val senderId = doc.getString("senderId") ?: continue
                            val statusStr = doc.getString("status") ?: MessageStatus.SENT.name
                            val status = try {
                                MessageStatus.valueOf(statusStr)
                            } catch (e: Exception) {
                                MessageStatus.SENT
                            }
                            val isDeletedForAll = doc.getBoolean("isDeletedForAll") == true
                            val deletedAtMs: Long? = when (val raw = doc.get("deletedAt")) {
                                is Number -> raw.toLong()
                                is com.google.firebase.Timestamp -> raw.toDate().time
                                else -> null
                            }

                            val docMediaWidth = doc.getLong("mediaWidth")?.toInt() ?: 0
                            val docMediaHeight = doc.getLong("mediaHeight")?.toInt() ?: 0
                            val docStatusBgColor = (doc.get("statusBgColor") as? Number)?.toInt()
                            val docIsForwarded = doc.getBoolean("isForwarded") == true
                            val docMediaItemsJson = mediaItemsJsonFromFirestoreDoc(doc)
                            // Extract server timestamp from Firestore doc for backfill
                            val docServerTimestamp: Long? = when (val sts = doc.get("serverTimestamp")) {
                                is Number -> sts.toLong()
                                is com.google.firebase.Timestamp -> sts.toDate().time
                                else -> null
                            }
                            val existing = existingById[id]
                            val mergedText = mergePreservingNonBlankText(doc.getString("text"), existing?.text)
                            if (existing != null) {
                                if (isDeletedForAll && (!existing.isDeletedForAll || existing.deletedAt != deletedAtMs)) {
                                    messageDao.updateDeletedForAllState(id, true, deletedAtMs)
                                }
                                if (existing.status != status && shouldUpdateStatus(existing.status, status)) {
                                    messageDao.updateMessageStatus(id, status)
                                    if (!existing.isIncoming) {
                                        _statusUpdateEvents.tryEmit(id to status)
                                    }
                                }
                                // Always use the Firestore serverTimestamp — it is the single
                                // authoritative clock. Remove the == null guard so that any
                                // previously stored RTDB timestamp gets overwritten with the
                                // Firestore value, ensuring ALL devices use an identical ordering key.
                                if (docServerTimestamp != null) {
                                    android.util.Log.d("MsgOrder", "[FS_ADD_EX] msgId=${id.takeLast(6)} fsTs=$docServerTimestamp prevTs=${existing.serverTimestamp} ${if (existing.serverTimestamp != docServerTimestamp) "→ UPDATED" else "→ no-op"}")
                                    messageDao.updateServerTimestamp(id, docServerTimestamp)
                                }
                                // Also sync the sender's client timestamp so that while the message
                                // is still "pending" (no serverTimestamp on the receiver yet), both
                                // devices sort it by the SAME timestamp (sender's clock from Firestore
                                // rather than the RTDB server clock, which diverges per-device).
                                val docClientTimestamp: Long? = when (val ct = doc.get("timestamp")) {
                                    is Number -> ct.toLong()
                                    is com.google.firebase.Timestamp -> ct.toDate().time
                                    else -> null
                                }
                                if (docClientTimestamp != null && docClientTimestamp != existing.timestamp) {
                                    android.util.Log.d("MsgOrder", "[FS_ADD_EX_TS] msgId=${id.takeLast(6)} docTs=$docClientTimestamp storedTs=${existing.timestamp} diff=${docClientTimestamp - existing.timestamp}ms → sync")
                                    messageDao.updateClientTimestamp(id, docClientTimestamp)
                                }
                                val mergedMediaWidth = existing.mediaWidth.takeIf { it > 0 } ?: docMediaWidth
                                val mergedMediaHeight = existing.mediaHeight.takeIf { it > 0 } ?: docMediaHeight
                                if (
                                    mergedMediaWidth > 0 &&
                                    mergedMediaHeight > 0 &&
                                    (mergedMediaWidth != existing.mediaWidth || mergedMediaHeight != existing.mediaHeight)
                                ) {
                                    messageDao.insertMessage(
                                        existing.copy(
                                            mediaWidth = mergedMediaWidth,
                                            mediaHeight = mergedMediaHeight
                                        )
                                    )
                                }
                                if (docIsForwarded && !existing.isForwarded) {
                                    messageDao.insertMessage(existing.copy(isForwarded = true))
                                }
                                if (
                                    (existing.type == MessageType.MEDIA_GROUP || (doc.getString("type") == MessageType.MEDIA_GROUP.name)) &&
                                    !hasMediaGroupItems(existing.mediaItems) &&
                                    hasMediaGroupItems(docMediaItemsJson)
                                ) {
                                    messageDao.updateMessageMediaItems(id, docMediaItemsJson!!)
                                    if (existing.isIncoming) {
                                        scheduleMediaDownload(existing.copy(mediaItems = docMediaItemsJson))
                                    }
                                }
                                // Reactions sync for re-emitted ADDED docs (Firestore authoritative)
                                // Skip if a local reaction write is in-flight (same race guard as MODIFIED path).
                                val remoteAddReactionsJson = serializeReactions(extractReactionsFromDoc(doc))
                                if (remoteAddReactionsJson != existing.reactionsJson && !pendingReactionWrites.contains(id)) {
                                    val previousReactions = parseReactions(existing.reactionsJson)
                                    val nextReactions = parseReactions(remoteAddReactionsJson)
                                    messageDao.updateReactions(id, remoteAddReactionsJson)
                                    syncChatPreviewForReactionChange(chatId, id, previousReactions, nextReactions)
                                }
                                val needsStatusReplyMerge =
                                    (existing.type == MessageType.STATUS_REPLY || (doc.getString("type") == MessageType.STATUS_REPLY.name)) && (
                                        (existing.statusId.isNullOrBlank() && !doc.getString("statusId").isNullOrBlank()) ||
                                        (existing.statusOwnerId.isNullOrBlank() && !doc.getString("statusOwnerId").isNullOrBlank()) ||
                                        (existing.statusThumbnailUrl.isNullOrBlank() && !doc.getString("statusThumbnailUrl").isNullOrBlank()) ||
                                        (existing.statusType.isNullOrBlank() && !doc.getString("statusType").isNullOrBlank()) ||
                                        (existing.statusText.isNullOrBlank() && !doc.getString("statusText").isNullOrBlank()) ||
                                        (existing.statusBgColor == null && docStatusBgColor != null)
                                    )
                                val needsTextBackfill = mergedText != existing.text
                                if (needsStatusReplyMerge) {
                                    messageDao.insertMessage(
                                        existing.copy(
                                            text = mergedText,
                                            statusId = existing.statusId ?: doc.getString("statusId"),
                                            statusOwnerId = existing.statusOwnerId ?: doc.getString("statusOwnerId"),
                                            statusThumbnailUrl = existing.statusThumbnailUrl ?: doc.getString("statusThumbnailUrl"),
                                            statusType = existing.statusType ?: doc.getString("statusType"),
                                            statusText = existing.statusText ?: doc.getString("statusText"),
                                            statusBgColor = existing.statusBgColor ?: docStatusBgColor,
                                            isForwarded = existing.isForwarded || docIsForwarded
                                        )
                                    )
                                } else if (needsTextBackfill) {
                                    messageDao.insertMessage(existing.copy(text = mergedText))
                                }
                                if (senderId != currentUserId) {
                                    // Only clean up docs that are outside the delete-for-all window.
                                    // Keeping recent docs alive lets the Cloud Function find and
                                    // update them when the sender triggers delete-for-everyone.
                                    val docTs = when (val ts = doc.get("timestamp")) {
                                        is Number -> ts.toLong()
                                        is com.google.firebase.Timestamp -> ts.toDate().time
                                        else -> 0L
                                    }
                                    if (System.currentTimeMillis() - docTs > DELETE_FOR_ALL_WINDOW_MS) {
                                        incomingDocsToDelete += doc
                                    }
                                }
                                continue
                            }

                            val timestamp = when (val ts = doc.get("timestamp")) {
                                is Number -> ts.toLong()
                                is com.google.firebase.Timestamp -> ts.toDate().time
                                is Map<*, *> -> System.currentTimeMillis()
                                else -> System.currentTimeMillis()
                            }
                            // Extract server-authoritative timestamp for deterministic ordering
                            val serverTimestamp: Long? = when (val sts = doc.get("serverTimestamp")) {
                                is Number -> sts.toLong()
                                is com.google.firebase.Timestamp -> sts.toDate().time
                                else -> null // Not yet resolved or absent
                            }
                            android.util.Log.d("MsgOrder", "[FS_ADD_NEW] msgId=${id.takeLast(6)} fsTs=$serverTimestamp clientTs=$timestamp sender=${senderId.takeLast(6)}")
                            val typeStr = doc.getString("type") ?: MessageType.TEXT.name
                            val type = try {
                                MessageType.valueOf(typeStr)
                            } catch (e: Exception) {
                                MessageType.TEXT
                            }

                            messagesToInsert += LocalMessage(
                                id = id,
                                chatId = chatId,
                                text = doc.getString("text") ?: "",
                                senderId = senderId,
                                timestamp = timestamp,
                                serverTimestamp = serverTimestamp,
                                status = status,
                                isIncoming = senderId != currentUserId,
                                type = type,
                                imageUrl = doc.getString("imageUrl"),
                                videoUrl = doc.getString("videoUrl"),
                                thumbnailUrl = doc.getString("thumbnailUrl"),
                                linkPreviewTitle = doc.getString("linkPreviewTitle"),
                                linkPreviewDomain = doc.getString("linkPreviewDomain"),
                                linkPreviewDescription = doc.getString("linkPreviewDescription"),
                                linkPreviewSiteName = doc.getString("linkPreviewSiteName"),
                                fileSize = doc.getLong("fileSize"),
                                videoDuration = doc.getLong("videoDuration"),
                                contactName = doc.getString("contactName"),
                                contactPhone = doc.getString("contactPhone"),
                                deliveredTimestamp = doc.getLong("deliveredTimestamp"),
                                readTimestamp = doc.getLong("readTimestamp"),
                                isDeletedForAll = isDeletedForAll,
                                deletedAt = deletedAtMs,
                                isVideoNote = doc.getBoolean("isVideoNote") ?: false,
                                replyToMessageId = doc.getString("replyToMessageId"),
                                replyToText = doc.getString("replyToText"),
                                replyToSenderId = doc.getString("replyToSenderId"),
                                replyToType = doc.getString("replyToType"),
                                replyPreviewUrl = doc.getString("replyPreviewUrl"),
                                audioUrl = doc.getString("audioUrl"),
                                audioDuration = doc.getLong("audioDuration") ?: 0L,
                                mediaWidth = docMediaWidth,
                                mediaHeight = docMediaHeight,
                                mediaItems = docMediaItemsJson,
                                documentCaption = doc.getString("documentCaption"),
                                statusId = doc.getString("statusId"),
                                statusOwnerId = doc.getString("statusOwnerId"),
                                statusThumbnailUrl = doc.getString("statusThumbnailUrl"),
                                statusType = doc.getString("statusType"),
                                statusText = doc.getString("statusText"),
                                statusBgColor = doc.getLong("statusBgColor")?.toInt(),
                                isForwarded = doc.getBoolean("isForwarded") == true,
                                reactionsJson = serializeReactions(extractReactionsFromDoc(doc))
                            )

                            if (senderId != currentUserId) {
                                // Only clean up docs outside the delete-for-all window so the
                                // Cloud Function can still update them if the sender deletes.
                                if (System.currentTimeMillis() - timestamp > DELETE_FOR_ALL_WINDOW_MS) {
                                    incomingDocsToDelete += doc
                                }
                            }
                        }

                        if (messagesToInsert.isNotEmpty()) {
                            messageDao.insertMessages(messagesToInsert)
                            applyCachedOtherReadReceipt(chatId)
                            trace(
                                stage = "firestore_sync_inserted",
                                details = "chatId=$chatId inserted=${messagesToInsert.size} first=${messagesToInsert.first().id.take(8)} last=${messagesToInsert.last().id.take(8)}"
                            )
                        }

                        if (incomingDocsToDelete.isNotEmpty()) {
                            val batch = firestore.batch()
                            incomingDocsToDelete.forEach { doc -> batch.delete(doc.reference) }
                            runCatching { batch.commit().await() }
                                .onFailure { syncError ->
                                    Log.w(TAG, "Failed to cleanup incoming Firestore relay docs for $chatId", syncError)
                                }
                        }
                    }
                }
            }
        }
    }

    fun markChatAsRead(chatId: String) {
        val userId = currentUserId ?: return

        repositoryScope.launch {
            // Compute the target read timestamp outside the mutex to avoid holding the lock during I/O.
            val latestMsgTime = messageDao.getLatestIncomingMessageTimestamp(chatId) ?: 0L
            val desiredReadTime = maxOf(System.currentTimeMillis(), latestMsgTime)

            var shouldSend = false
            var targetReadTime = desiredReadTime

            markReadMutex.withLock {
                val lastSent = lastReadReceiptSent[chatId] ?: 0L
                if (desiredReadTime <= lastSent) {
                    // Already sent a newer/equal receipt; nothing to do.
                    return@withLock
                }

                val now = System.currentTimeMillis()
                val lastCall = lastMarkAsReadTimestamps[chatId] ?: 0L
                val elapsed = now - lastCall

                if (elapsed < MARK_AS_READ_DEBOUNCE_MS) {
                    // Buffer the highest timestamp seen during the debounce window and schedule a single flush.
                    val pending = maxOf(desiredReadTime, pendingReadReceiptUpTo[chatId] ?: 0L)
                    pendingReadReceiptUpTo[chatId] = pending

                    val delayFor = MARK_AS_READ_DEBOUNCE_MS - elapsed
                    pendingMarkReadJobs[chatId]?.cancel()
                    pendingMarkReadJobs[chatId] = repositoryScope.launch {
                        delay(delayFor)
                        flushPendingReadReceipt(chatId, userId)
                    }
                    return@withLock
                }

                lastMarkAsReadTimestamps[chatId] = now
                lastReadReceiptSent[chatId] = desiredReadTime
                shouldSend = true
                targetReadTime = desiredReadTime
            }

            if (shouldSend) {
                sendReadReceipt(chatId, userId, targetReadTime)
            }
        }
    }

    private suspend fun flushPendingReadReceipt(chatId: String, userId: String) {
        var target: Long? = null

        markReadMutex.withLock {
            val pending = pendingReadReceiptUpTo.remove(chatId) ?: return
            val lastSent = lastReadReceiptSent[chatId] ?: 0L
            if (pending <= lastSent) {
                pendingMarkReadJobs.remove(chatId)
                return
            }

            val now = System.currentTimeMillis()
            lastMarkAsReadTimestamps[chatId] = now
            lastReadReceiptSent[chatId] = pending
            pendingMarkReadJobs.remove(chatId)
            target = pending
        }

        target?.let { sendReadReceipt(chatId, userId, it) }
    }

    private suspend fun cacheOtherReadTimestamp(chatId: String, otherReadTime: Long) {
        readReceiptCacheMutex.withLock {
            val current = latestOtherReadTimestamp[chatId] ?: 0L
            if (otherReadTime > current) {
                latestOtherReadTimestamp[chatId] = otherReadTime
            }
        }
    }

    private suspend fun applyCachedOtherReadReceipt(chatId: String) {
        val target = readReceiptCacheMutex.withLock { latestOtherReadTimestamp[chatId] }
        if (target != null && target > 0) {
            markSentMessagesAsReadAndNotify(chatId, target)
            // Chat last message will be updated by the existing listener path when snapshot fires; avoid duplicate work here.
        }
    }

    private suspend fun markSentMessagesAsReadAndNotify(chatId: String, readTime: Long): Int {
        val messageIds = messageDao.getSentMessageIdsToMarkRead(chatId, readTime)
        val updatedCount = messageDao.markSentMessagesAsRead(chatId, readTime)
        if (updatedCount > 0) {
            messageIds.forEach { messageId ->
                _statusUpdateEvents.tryEmit(messageId to MessageStatus.READ)
            }
        }
        return updatedCount
    }

    private suspend fun sendReadReceipt(chatId: String, userId: String, readTime: Long) {
        chatDao.clearUnreadCount(chatId)
        val unreadIncomingMessageIds = messageDao.getUnreadIncomingMessageIdsUpTo(chatId, readTime)
        messageDao.markIncomingMessagesAsRead(chatId, readTime, MessageStatus.READ)

        // Respect read receipts privacy setting – only broadcast the read timestamp when enabled
        val privacySettings = try {
            PrivacySettingsRepository.getPrivacySettings()
        } catch (_: Exception) {
            PrivacySettingsRepository.PrivacySettings() // defaults (read receipts on)
        }
        if (!privacySettings.readReceipts) return

        val chatRef = firestore.collection("chats").document(chatId)
        runCatching {
            chatRef.update(mapOf("readTimestamps.$userId" to readTime)).await()
        }.recoverCatching {
            chatRef.set(
                mapOf("readTimestamps" to mapOf(userId to readTime)),
                com.google.firebase.firestore.SetOptions.merge()
            ).await()
        }.onFailure { error ->
            Log.w(TAG, "Failed to update chat read timestamp for $chatId", error)
        }

        if (unreadIncomingMessageIds.isNotEmpty()) {
            runCatching {
                val batch = firestore.batch()
                unreadIncomingMessageIds.forEach { messageId ->
                    batch.set(
                        chatRef.collection("messages").document(messageId),
                        mapOf(
                            "status" to MessageStatus.READ.name,
                            "readTimestamp" to readTime
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                }
                batch.commit().await()
            }.onFailure { error ->
                Log.w(TAG, "Failed to update message-level read receipts for $chatId", error)
            }
        }
    }

    /**
     * Marks a specific voice message as PLAYED.
     * This updates the status locally and in Firestore using the 'status' field.
     */
    fun markVoiceMessageAsPlayed(chatId: String, messageId: String) {
        repositoryScope.launch {
            // 1. Update local DB
            messageDao.updateMessageStatus(messageId, MessageStatus.PLAYED)
            
            // 2. Update Firestore document directly
            // Note: This relies on the message document existing in chats/{chatId}/messages/{messageId}
            firestore.collection("chats").document(chatId)
                .collection("messages").document(messageId)
                .update("status", MessageStatus.PLAYED.name)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to update voice message status to PLAYED for $messageId", e)
                }
        }
    }
    
    fun syncReadReceipts(chatId: String, otherUserId: String): com.google.firebase.firestore.ListenerRegistration {
        val userId = currentUserId
        return firestore.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                val timestampsRaw = snapshot.get("readTimestamps") as? Map<*, *> ?: return@addSnapshotListener
                val otherReadTime = (timestampsRaw[otherUserId] as? Number)?.toLong() ?: return@addSnapshotListener
                
                // Update all my sent messages that are older than otherReadTime to READ
                repositoryScope.launch {
                    cacheOtherReadTimestamp(chatId, otherReadTime)
                    markSentMessagesAsReadAndNotify(chatId, otherReadTime)
                    
                    // Also update chat's lastMessageStatus if the last message is from me and has been read
                    val chat = chatDao.getChatById(chatId)
                    if (chat != null && chat.lastMessageSenderId == userId && chat.lastMessageTimestamp <= otherReadTime) {
                        chatDao.updateLastMessage(chatId, chat.lastMessage, chat.lastMessageTimestamp, chat.lastMessageSenderId, MessageStatus.READ.name)
                    }
                }
            }
    }
    
    /**
     * Start syncing read receipts for all chats. This should be called from the chat list
     * to ensure read receipts are updated in real-time even when not in a specific chat.
     */
    fun syncAllReadReceipts(chatIds: List<String>) {
        val userId = currentUserId ?: return
        
        chatIds.forEach { chatId ->
            firestore.collection("chats").document(chatId)
                .addSnapshotListener { snapshot, e ->
                    if (e != null || snapshot == null) return@addSnapshotListener
                    
                    // Get the other user's read timestamp
                    val timestampsRaw = snapshot.get("readTimestamps") as? Map<*, *> ?: return@addSnapshotListener
                    
                    // Find the other user's timestamp (not mine)
                    val otherReadTime = timestampsRaw.entries
                        .firstOrNull { (key, _) -> key is String && key != userId }
                        ?.let { (_, value) -> (value as? Number)?.toLong() }
                        ?: return@addSnapshotListener
                    
                    // Update chat's lastMessageStatus if needed
                    repositoryScope.launch {
                        val chat = chatDao.getChatById(chatId)
                        if (chat != null && chat.lastMessageSenderId == userId && 
                            chat.lastMessageTimestamp <= otherReadTime &&
                            chat.lastMessageStatus != MessageStatus.READ.name) {
                            chatDao.updateLastMessage(
                                chatId, 
                                chat.lastMessage, 
                                chat.lastMessageTimestamp, 
                                chat.lastMessageSenderId, 
                                MessageStatus.READ.name
                            )
                        }
                    }
                }
        }
    }

    // Helper to sync user info (avatar/username) from Firestore to Local DB
    fun syncChatUserInfo(chatId: String, otherUserId: String) {
        if (otherUserId.isBlank() || com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(chatId)) {
            return
        }

        firestore.collection("users").document(otherUserId).get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username") ?: return@addOnSuccessListener
                val avatar = doc.getString("profileImageUrl") ?: ""
                
                repositoryScope.launch {
                    if (chatDao.getChatById(chatId)?.isGroup == true) return@launch
                    chatDao.updateUserInfo(chatId, username, avatar)
                    
                    // Cache avatar for instant loading
                    if (avatar.isNotEmpty()) {
                        com.glyph.glyph_v3.data.cache.AvatarCacheManager.updateAvatarIfNeeded(
                            otherUserId, 
                            avatar, 
                            context
                        )
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync user info", e)
            }
    }

    fun startGroupMetadataSync(forceRestart: Boolean = false) {
        val userId = currentUserId ?: return
        if (!forceRestart && globalGroupMetadataListener != null && globalGroupMetadataListenerUid == userId) {
            return
        }
        // Load user-deleted group chat IDs from Firestore so cross-device deletions are respected
        loadHiddenGroupChatsFromFirestore(userId)

        globalGroupMetadataListener?.remove()
        globalGroupMetadataListener = null
        globalGroupMetadataListenerUid = userId

        globalGroupMetadataListener = firestore.collection("chats")
            .whereArrayContains("participants", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Failed to observe group metadata", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                val changedDocuments = if (snapshot.documentChanges.isNotEmpty()) {
                    snapshot.documentChanges
                        .filter { it.type != com.google.firebase.firestore.DocumentChange.Type.REMOVED }
                        .map { it.document }
                } else {
                    snapshot.documents
                }

                if (changedDocuments.isEmpty()) return@addSnapshotListener

                repositoryScope.launch {
                    changedDocuments.forEach { doc ->
                        syncGroupChatMetadata(doc)
                    }
                }
            }
    }

    private suspend fun syncGroupChatMetadata(doc: com.google.firebase.firestore.DocumentSnapshot) {
        if (doc.getBoolean("isGroup") != true) return

        val chatId = doc.id
        if (!com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(chatId)) return

        // Don't re-create a group chat the current user has explicitly deleted
        if (chatId in deletedGroupChatIds) return

        val existingChat = chatDao.getChatById(chatId)
        val participants = (doc.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val admins = (doc.get("admins") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val groupName = doc.getString("groupName").orEmpty()
            .ifBlank { existingChat?.groupName?.ifBlank { "Group" } ?: "Group" }
        val groupIconUrl = doc.getString("groupIconUrl").orEmpty()
        when {
            groupIconUrl.isBlank() -> com.glyph.glyph_v3.data.cache.AvatarCacheManager.clearGroupAvatarCache(chatId)
            else -> com.glyph.glyph_v3.data.cache.AvatarCacheManager.updateGroupAvatarIfNeeded(
                chatId,
                groupIconUrl,
                context
            )
        }
        val groupDescription = doc.getString("groupDescription").orEmpty()
        val createdBy = doc.getString("createdBy").orEmpty()
            .ifBlank { existingChat?.createdBy.orEmpty() }
        val createdAt = doc.getLong("createdAt") ?: existingChat?.createdAt ?: 0L
        val lastMessage = existingChat?.lastMessage ?: doc.getString("lastMessage").orEmpty()
        val lastMessageTimestamp = existingChat?.lastMessageTimestamp?.takeIf { it > 0L }
            ?: doc.getLong("lastMessageTimestamp")
            ?: createdAt
        val lastMessageSenderId = existingChat?.lastMessageSenderId
            ?: doc.getString("lastMessageSenderId").orEmpty()
        val lastMessageStatus = existingChat?.lastMessageStatus ?: MessageStatus.SENT.name

        chatDao.insertChat(
            LocalChat(
                id = chatId,
                otherUserId = "",
                otherUsername = "",
                otherUserAvatar = "",
                lastMessage = lastMessage,
                lastMessageTimestamp = lastMessageTimestamp,
                lastMessageSenderId = lastMessageSenderId,
                lastMessageStatus = lastMessageStatus,
                unreadCount = existingChat?.unreadCount ?: 0,
                isArchived = existingChat?.isArchived ?: false,
                isGroup = true,
                groupName = groupName,
                groupIconUrl = groupIconUrl,
                groupDescription = groupDescription,
                createdBy = createdBy,
                createdAt = createdAt,
                participantsJson = participants.takeIf { it.isNotEmpty() }
                    ?.let { com.glyph.glyph_v3.data.repo.GroupChatRepository.encodeStringList(it) }
                    ?: existingChat?.participantsJson,
                adminsJson = admins.takeIf { it.isNotEmpty() }
                    ?.let { com.glyph.glyph_v3.data.repo.GroupChatRepository.encodeStringList(it) }
                    ?: existingChat?.adminsJson
            )
        )
    }
    
    /**
     * Schedule a background download for incoming media messages.
     * Uses WorkManager for reliable background execution.
     */
    private fun loadHiddenGroupChatsFromFirestore(uid: String) {
        repositoryScope.launch {
            runCatching {
                val docs = firestore
                    .collection("users").document(uid)
                    .collection("hidden_chats")
                    .get().await()
                val ids = docs.documents.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }
                if (ids.isNotEmpty()) {
                    deletedGroupChatIds.addAll(ids)
                    persistDeletedGroupChatIds()
                }
            }.onFailure { e ->
                Log.w(TAG, "Failed to load hidden group chats from Firestore", e)
            }
        }
    }

    /**
     * Schedule a background download for incoming media messages.
     * Uses WorkManager for reliable background execution.
     */
    private fun scheduleMediaDownload(message: LocalMessage) {
        if (message.type == MessageType.MEDIA_GROUP && !message.mediaItems.isNullOrBlank()) {
            val items = parseMediaItemsOrEmpty(message.mediaItems)
            items.forEachIndexed { index, item ->
                val remoteUrl = item.url.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                if (resolveForwardableLocalUri(item.localUri) != null) return@forEachIndexed

                val mediaType = if (item.type == MediaType.VIDEO) MessageType.VIDEO else MessageType.IMAGE
                com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
                    context = context,
                    messageId = "${message.id}_$index",
                    chatId = message.chatId,
                    mediaType = mediaType,
                    remoteUrl = remoteUrl,
                    fileSize = item.fileSize,
                    groupMessageId = message.id,
                    itemIndex = index
                )
            }
            return
        }

        val remoteUrl = when (message.type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME -> message.imageUrl
            MessageType.VIDEO -> message.videoUrl
            MessageType.AUDIO -> message.audioUrl
            else -> null
        }
        
        if (remoteUrl.isNullOrEmpty()) return
        
        val workerType = when (message.type) {
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME -> MessageType.IMAGE
            else -> message.type
        }

        com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
            context = context,
            messageId = message.id,
            chatId = message.chatId,
            mediaType = workerType,
            remoteUrl = remoteUrl,
            fileSize = message.fileSize
        )
    }

    private fun MessageType.requiresMediaDownload(): Boolean {
        return this == MessageType.IMAGE ||
            this == MessageType.VIDEO ||
            this == MessageType.AUDIO ||
            this == MessageType.MEDIA_GROUP ||
            this == MessageType.GIF ||
            this == MessageType.STICKER ||
            this == MessageType.KLIPY_EMOJI ||
            this == MessageType.MEME
    }
    
    /**
     * Observe status changes for outgoing messages in a chat.
     * This syncs DELIVERED and READ status from Firestore to local DB.
     */
    fun observeMessageStatusUpdates(chatId: String) {
        val userId = currentUserId ?: return
        
        firestore.collection("chats").document(chatId)
            .collection("messages")
            .whereEqualTo("senderId", userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing message status", error)
                    return@addSnapshotListener
                }
                
                snapshots?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.REMOVED) {
                        return@forEach
                    }

                    val messageId = change.document.id
                    val status = change.document.getString("status") ?: return@forEach
                    val text = change.document.getString("text") ?: ""
                    val timestamp = change.document.getLong("timestamp") ?: 0L
                    val deliveredTimestamp = change.document.getLong("deliveredTimestamp")
                        ?: change.document.getLong("deliveredAt")
                    val readTimestamp = change.document.getLong("readTimestamp")
                        ?: change.document.getLong("readAt")

                    repositoryScope.launch {
                        val messageStatus = when (status) {
                            "DELIVERED" -> MessageStatus.DELIVERED
                            "READ" -> MessageStatus.READ
                            "PLAYED" -> MessageStatus.PLAYED
                            else -> return@launch
                        }

                        updateOutgoingMessageStatusMonotonic(
                            messageId = messageId,
                            newStatus = messageStatus,
                            deliveredTimestamp = deliveredTimestamp,
                            readTimestamp = readTimestamp
                        )

                        updateChatLastMessageStatusMonotonic(
                            chatId = chatId,
                            senderId = userId,
                            timestamp = timestamp,
                            messageText = text,
                            newStatus = messageStatus
                        )
                    }
                }
            }
    }

    // ==================== SEND VOICE MESSAGE ====================

    suspend fun sendVoiceMessage(
        chatId: String,
        voiceFile: java.io.File,
        duration: Long,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        
        // 1. Move to persistent storage immediately (Local First)
        // This ensures the file is immune to cache clearing and available regardless of upload status.
        val cacheUri = Uri.fromFile(voiceFile)
        var finalLocalPath = voiceFile.absolutePath
        var finalFile = voiceFile
        
        try {
            val persistentFile = com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                context = context,
                chatId = chatId,
                messageId = messageId,
                mediaType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.AUDIO,
                sourceUri = cacheUri
            )
            if (persistentFile != null) {
                finalLocalPath = persistentFile.absolutePath
                finalFile = persistentFile
                // We can delete the cache file now, or let cache cleanup handle it.
                // voiceFile.delete() 
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save voice to persistent storage", e)
        }

        val finalUri = Uri.fromFile(finalFile)

        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        // 2. Use persistent path for local message
        val placeholderMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = "Voice Message",
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.AUDIO,
            localUri = finalLocalPath,
            audioDuration = duration
        )
        messageDao.insertMessage(placeholderMessage)
        chatDao.updateLastMessage(chatId, "Voice Message", timestamp, userId, MessageStatus.SENDING.name)

        val storageRef = storage.reference.child("chat_voice/$chatId/$messageId.m4a")
        
        MediaProgressManager.updateProgress(messageId, 0f, isUploading = true, totalBytes = finalFile.length())

        // 3. Upload the persistent file
        storageRef.putFile(finalUri)
            .addOnProgressListener { taskSnapshot ->
                val progress = (100.0 * taskSnapshot.bytesTransferred / taskSnapshot.totalByteCount).toFloat()
                MediaProgressManager.updateProgress(
                    messageId,
                    progress,
                    isUploading = true,
                    totalBytes = taskSnapshot.totalByteCount,
                    transferredBytes = taskSnapshot.bytesTransferred
                )
            }
            .addOnSuccessListener {
                storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    val downloadUrl = downloadUri.toString()
                    MediaProgressManager.complete(messageId)
                    
                    repositoryScope.launch {
                        // 4. Update with remote URL, but keep using our local file which is already persistent.
                        // No need to copy/move here anymore.
                        
                        // Update local with URL but wait for RTDB to mark as SENT
                        val sentMessage = placeholderMessage.copy(
                            audioUrl = downloadUrl,
                            status = MessageStatus.SENDING
                        )
                        messageDao.insertMessage(sentMessage)
                        
                        // updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
                        // updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, "Voice Message", MessageStatus.SENT)

                        val messageData = mapOf(
                            "id" to messageId,
                            "chatId" to chatId,
                            "text" to "Voice Message",
                            "senderId" to userId,
                            "timestamp" to ServerValue.TIMESTAMP,
                            "type" to "AUDIO",
                            "audioUrl" to downloadUrl,
                            "audioDuration" to duration,
                            "fileSize" to finalFile.length()
                        )

                        try {
                            persistToFirestoreAwait(
                                chatId = chatId,
                                messageId = messageId,
                                text = "Voice Message",
                                senderId = userId,
                                otherUserId = otherUserId,
                                timestamp = timestamp,
                                type = "AUDIO",
                                audioUrl = downloadUrl,
                                audioDuration = duration,
                                fileSize = finalFile.length()
                            )
                            updateOutgoingMessageStatusMonotonic(messageId, MessageStatus.SENT)
                            updateChatLastMessageStatusMonotonic(chatId, userId, timestamp, "Voice Message", MessageStatus.SENT)

                            rtdb.reference
                                .child("pending_messages")
                                .child(otherUserId)
                                .child(messageId)
                                .setValue(messageData)
                                .addOnFailureListener { e ->
                                    Log.w(TAG, "Failed direct RTDB voice push after Firestore persist for $messageId", e)
                                }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist voice message before RTDB push", e)
                            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                            chatDao.updateLastMessage(chatId, "Voice Message", timestamp, userId, MessageStatus.FAILED.name)
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get voice download URL", e)
                    MediaProgressManager.complete(messageId)
                    repositoryScope.launch {
                        messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                        chatDao.updateLastMessage(chatId, "Voice Message", timestamp, userId, MessageStatus.FAILED.name)
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to upload voice message", e)
                MediaProgressManager.complete(messageId)
                repositoryScope.launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    chatDao.updateLastMessage(chatId, "Voice Message", timestamp, userId, MessageStatus.FAILED.name)
                }
            }
    }

}


