package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.glyph.glyph_v3.util.StartupTrace
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * PresenceManager handles real-time online/offline status using Firebase Realtime Database.
 * 
 * Firebase Realtime Database is used instead of Firestore because it supports:
 * - onDisconnect() handlers that automatically update status when user goes offline
 * - Lower latency for presence updates
 * - Built-in connection state monitoring
 * 
 * CRITICAL DESIGN:
 * - Uses heartbeat mechanism to detect stale connections
 * - Lifecycle-aware: tracks app foreground/background state
 * - Per-chat presence: tracks when user is actively viewing specific chats
 * - Never infers presence from message delivery/read status
 */
object PresenceManager {

    private const val TAG = "PresenceManager"
    private const val PRESENCE_PATH = "presence"
    private const val HEARTBEAT_INTERVAL_MS = 30000L // 30 seconds
    private const val PRESENCE_TIMEOUT_MS = 60000L // 60 seconds - consider offline if no heartbeat
    private const val PRESENCE_REEVALUATION_INTERVAL_MS = 1000L
    private const val TYPING_FRESHNESS_MS = 5000L
    
    // COLD-START FIX: Eagerly initialize Firebase instances instead of `by lazy`.
    // Lazy init delays the first RTDB access, which delays WebSocket creation.
    // Eager init means the WebSocket handshake starts as soon as PresenceManager
    // is first touched (from Application.onCreate), shaving 2-5 s off cold start.
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val presenceRef = database.getReference(PRESENCE_PATH)
    private val chatsRef = database.getReference("chats")
    private val connectedRef = database.getReference(".info/connected")
    
    private var connectionListener: ValueEventListener? = null
    private var authStateListener: FirebaseAuth.AuthStateListener? = null
    private var isInitialized = false
    private var heartbeatJob: Job? = null
    private val presenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trustedLastSeenByUser = mutableMapOf<String, Long>()
    private val observedOnlineByUser = mutableMapOf<String, Boolean>()

    /**
     * Tracks which users have received at least one RTDB presence snapshot in this
     * process lifetime. On cold start this set is empty. The first snapshot from
     * Firebase after a cold start is authoritative — we must accept its lastSeen
     * because we missed any online→offline transitions while the process was dead.
     */
    private val receivedFirstSnapshotFor = mutableSetOf<String>()

    /**
     * COLD-START FIX: Signals when the Firebase RTDB connection is established.
     * Activities can use this to know when presence data is actually available.
     */
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // SharedPreferences for persisting trustedLastSeen across process restarts.
    // Without this, a process restart loses all sanitization state and the first
    // (possibly bumped) Firebase value is accepted as truth.
    private const val PREFS_NAME = "presence_trusted_last_seen"
    private var prefs: SharedPreferences? = null
    
    // Track if app is in foreground
    @Volatile
    private var isAppInForeground = false
    
    // Track currently active chat (if user is viewing a specific chat)
    @Volatile
    private var activeChatId: String? = null

    @Volatile
    private var pendingPresenceReplay = false

    /**
     * Must be called once from Application.onCreate() before any other method.
     * Provides the application context needed for SharedPreferences persistence.
     */
    fun initContext(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Restore trustedLastSeenByUser from disk
            prefs?.all?.forEach { (key, value) ->
                if (value is Long && value > 0L) {
                    trustedLastSeenByUser[key] = value
                }
            }
        }
        ensureAuthReplayListener()
    }

    private fun ensureAuthReplayListener() {
        if (authStateListener != null) return

        authStateListener = FirebaseAuth.AuthStateListener { authState ->
            val userId = authState.currentUser?.uid ?: return@AuthStateListener
            StartupTrace.logStage("presence_auth_ready", "uid=$userId")

            if (!isInitialized) {
                initialize()
            }

            if (pendingPresenceReplay && isAppInForeground) {
                pendingPresenceReplay = false
                val currentChatId = activeChatId
                if (currentChatId != null) {
                    setViewingChat(currentChatId)
                }
                goOnline()
            }
        }

        auth.addAuthStateListener(authStateListener!!)
    }

    private fun deferPresenceReplay(reason: String) {
        pendingPresenceReplay = true
        ensureAuthReplayListener()
        StartupTrace.logStage("presence_replay_deferred", reason)
    }

    fun isInForeground(): Boolean = isAppInForeground

    fun primeTransport(reason: String, forceTokenRefresh: Boolean = false) {
        try {
            database.goOnline()
            presenceRef.keepSynced(true)

            val user = auth.currentUser
            if (user != null) {
                presenceRef.child(user.uid).keepSynced(true)
                user.getIdToken(forceTokenRefresh)
                    .addOnSuccessListener {
                        StartupTrace.logStage(
                            "presence_transport_primed",
                            "reason=$reason uid=${user.uid} forceTokenRefresh=$forceTokenRefresh"
                        )
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Presence auth prime failed for $reason", error)
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prime presence transport for $reason", e)
        }
    }

    /**
     * Data class representing user presence status
     */
    data class PresenceStatus(
        val isOnline: Boolean = false,
        val lastSeen: Long = 0L,
        val viewingChatId: String? = null
    )

    private data class FirestoreTypingMirror(
        val isTyping: Boolean = false,
        val updatedAt: Long = 0L
    )

    private data class FirestorePresenceMirror(
        val isOnline: Boolean = false,
        val lastSeen: Long = 0L,
        val viewingChatId: String? = null,
        val updatedAt: Long = 0L
    )

    private fun syncFirestorePresenceMirror(
        userId: String,
        isOnline: Boolean,
        viewingChatId: String?,
        lastSeen: Long? = null
    ) {
        val payload = mutableMapOf<String, Any?>(
            "isOnline" to isOnline,
            "viewingChat" to viewingChatId,
            "presenceUpdatedAt" to System.currentTimeMillis()
        )
        if (lastSeen != null) {
            payload["lastSeen"] = lastSeen
        }
        firestore.collection("users").document(userId)
            .set(payload, com.google.firebase.firestore.SetOptions.merge())
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to sync Firestore presence mirror for $userId", error)
            }
    }

    private fun extractFirestoreTypingMirror(
        data: Map<String, Any?>?,
        senderId: String,
        receiverId: String
    ): FirestoreTypingMirror? {
        val typingStates = data?.get("typingStates") as? Map<*, *> ?: return null
        val senderStates = typingStates[senderId] as? Map<*, *> ?: return null
        val receiverState = senderStates[receiverId] as? Map<*, *> ?: return null
        return FirestoreTypingMirror(
            isTyping = receiverState["isTyping"] as? Boolean ?: false,
            updatedAt = (receiverState["updatedAt"] as? Number)?.toLong() ?: 0L
        )
    }

    private fun sanitizeLastSeenForDisplay(
        userId: String,
        rawLastSeen: Long,
        effectivelyOnline: Boolean,
        previousStatus: PresenceStatus?
    ): Long = synchronized(this) {
        val trusted = trustedLastSeenByUser[userId] ?: 0L
        val seenOnlineBefore = observedOnlineByUser[userId] == true
        val isFirstSnapshot = !receivedFirstSnapshotFor.contains(userId)

        // Mark that we've now received at least one snapshot for this user
        receivedFirstSnapshotFor.add(userId)

        if (effectivelyOnline) {
            observedOnlineByUser[userId] = true
            if (trusted == 0L && rawLastSeen > 0L) {
                trustedLastSeenByUser[userId] = rawLastSeen
                persistTrustedLastSeen(userId, rawLastSeen)
            }
            return@synchronized trustedLastSeenByUser[userId] ?: rawLastSeen
        }

        if (rawLastSeen <= 0L) {
            return@synchronized trusted
        }

        if (trusted == 0L) {
            trustedLastSeenByUser[userId] = rawLastSeen
            persistTrustedLastSeen(userId, rawLastSeen)
            return@synchronized rawLastSeen
        }

        // COLD-START FIX: On the first snapshot after a cold start, the persisted
        // trusted value may be days/weeks old because we missed all online→offline
        // transitions while the process was dead. The Firebase server value IS the
        // ground truth in this case — accept it unconditionally.
        //
        // Previously, this was rejected with "Ignoring offline lastSeen bump without
        // reliable online transition" because observedOnlineByUser is empty on cold
        // start. This caused users to see stale "last seen 3 days ago" when the real
        // value was "last seen 20 minutes ago".
        if (isFirstSnapshot && rawLastSeen > trusted) {
            trustedLastSeenByUser[userId] = rawLastSeen
            persistTrustedLastSeen(userId, rawLastSeen)
            return@synchronized rawLastSeen
        }

        val hasReliableOnlineTransition = seenOnlineBefore || previousStatus?.isOnline == true
        return@synchronized if (rawLastSeen > trusted && hasReliableOnlineTransition) {
            trustedLastSeenByUser[userId] = rawLastSeen
            persistTrustedLastSeen(userId, rawLastSeen)
            rawLastSeen
        } else if (rawLastSeen > trusted) {
            Log.w(
                TAG,
                "Ignoring offline lastSeen bump for $userId without reliable online transition: $trusted -> $rawLastSeen"
            )
            trusted
        } else {
            trusted
        }
    }

    /**
     * Persist a trusted lastSeen value to SharedPreferences.
     * Called inside synchronized(this), so keep it lightweight.
     */
    private fun persistTrustedLastSeen(userId: String, timestamp: Long) {
        prefs?.edit()?.putLong(userId, timestamp)?.apply()
    }

    /**
     * Initialize presence tracking for the current user.
     * This should be called when the user logs in or when the app starts.
     */
    fun initialize() {
        try {
            ensureAuthReplayListener()
            val userId = auth.currentUser?.uid ?: run {
                deferPresenceReplay("initialize_without_auth")
                return
            }
            
            if (isInitialized) {
                return
            }

            val userPresenceRef = presenceRef.child(userId)
            
            // Create presence data for when user is online.
            // IMPORTANT: Do not mutate lastSeen while online.
            val onlineData = mapOf(
                "isOnline" to true,
                "lastHeartbeat" to ServerValue.TIMESTAMP
            )
            
            // Create presence data for when user goes offline
            val offlineData = mapOf(
                "isOnline" to false,
                "lastSeen" to ServerValue.TIMESTAMP,
                "viewingChat" to null
            )

            // Listen for connection state changes
            connectionListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val connected = snapshot.getValue(Boolean::class.java) ?: false
                    StartupTrace.logStage("presence_connection_state", "connected=$connected")
                    _isConnected.value = connected
                    
                    if (connected) {
                        if (isAppInForeground) {
                            // COLD-START FIX: Write online data IN PARALLEL with onDisconnect()
                            // instead of chaining them serially. The old approach was:
                            //   onDisconnect() → success → updateChildren(online) → success → heartbeat
                            // which required 2 sequential server round-trips (1-4 seconds).
                            //
                            // New approach: fire both simultaneously. The onDisconnect handler
                            // is a server-side hook, so it's safe to write our online data
                            // at the same time — if the connection drops before onDisconnect
                            // completes, the worst case is we appear briefly online then
                            // immediately appear offline (which is correct behavior).
                            val reconnectActiveChatId: String? = activeChatId
                            val reconnectOnlineData: Map<String, Any?> = mapOf(
                                "isOnline" to true,
                                "lastHeartbeat" to ServerValue.TIMESTAMP,
                                "viewingChat" to reconnectActiveChatId
                            )
                            
                            // Fire onDisconnect handler setup (non-blocking)
                            userPresenceRef.onDisconnect().updateChildren(offlineData)
                                .addOnSuccessListener {
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Failed to set onDisconnect handler", e)
                                }
                            
                            // Write online data IMMEDIATELY (don't wait for onDisconnect)
                            userPresenceRef.updateChildren(reconnectOnlineData)
                                .addOnSuccessListener { 
                                    startHeartbeat()
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Failed to set online status on reconnect", e)
                                }
                        } else {
                            // Cancel any pending onDisconnect handler from the previous session
                            userPresenceRef.onDisconnect().cancel()
                            // CRITICAL FIX: If a previous session left us stuck as isOnline=true
                            // (e.g., goOffline() write failed before process death), we must
                            // correct it now. Do NOT update lastSeen — preserve the existing
                            // value so we don't create a phantom "last seen" bump.
                            val ensureOfflineData = mapOf<String, Any?>(
                                "isOnline" to false,
                                "viewingChat" to null
                            )
                            userPresenceRef.updateChildren(ensureOfflineData)
                                .addOnSuccessListener {
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Failed to ensure offline state in background", e)
                                }
                        }
                    } else {
                        // Connection lost, stop heartbeat
                        stopHeartbeat()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Connection listener cancelled", error.toException())
                }
            }

            connectedRef.addValueEventListener(connectionListener!!)
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize presence", e)
        }
    }
    
    /**
     * Start periodic heartbeat to keep presence status fresh.
     * This prevents stale "online" status when connection is lost.
     */
    private fun startHeartbeat() {
        stopHeartbeat() // Cancel any existing job
        
        val userId = auth.currentUser?.uid ?: return
        
        heartbeatJob = presenceScope.launch {
            while (isActive && isAppInForeground) {
                try {
                    presenceRef.child(userId).child("lastHeartbeat")
                        .setValue(ServerValue.TIMESTAMP)
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat failed", e)
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /**
     * Set the current user as online.
     * Called when the app comes to foreground.
     *
     * COLD-START FIX: Write online data IN PARALLEL with onDisconnect() setup.
     * Previously these were chained serially (onDisconnect success → write online),
     * costing 2 sequential server round-trips (1-4 seconds on cold start).
     * Now both are fired simultaneously, cutting the delay in half.
     */
    fun goOnline() {
        try {
            val userId = auth.currentUser?.uid ?: run {
                isAppInForeground = true
                deferPresenceReplay("goOnline_without_auth")
                return
            }
            isAppInForeground = true
            database.goOnline()
            StartupTrace.logStage("presence_go_online_requested", "uid=$userId")
            
            // Snapshot activeChatId synchronously so the atomic Firebase write below is
            // consistent. This eliminates the race condition where an async removeValue()
            // call on viewingChat fired AFTER setViewingChat() had already written the chat id.
            val currentActiveChatId: String? = activeChatId

            // NOTE: passing null for "viewingChat" in updateChildren() removes the field in
            // Firebase, which is the correct behaviour when the user is not in a chat.
            val onlineData: Map<String, Any?> = mapOf(
                "isOnline" to true,
                "lastHeartbeat" to ServerValue.TIMESTAMP,
                "viewingChat" to currentActiveChatId
            )
            
            val offlineData = mapOf(
                "isOnline" to false,
                "lastSeen" to ServerValue.TIMESTAMP,
                "viewingChat" to null
            )

            val userPresenceRef = presenceRef.child(userId)

            // COLD-START FIX: Fire onDisconnect and online write in PARALLEL.
            // onDisconnect is a server-side hook — safe to write online data simultaneously.
            // If onDisconnect hasn't finished when connection drops, the server-side
            // timeout will still eventually mark the user offline.
            userPresenceRef.onDisconnect().updateChildren(offlineData)
                .addOnSuccessListener {
                }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to set onDisconnect handler in goOnline", e) }
            
            // Write online data IMMEDIATELY (don't wait for onDisconnect round-trip)
            userPresenceRef.updateChildren(onlineData)
                .addOnSuccessListener { 
                    StartupTrace.logStage("presence_go_online_applied", "uid=$userId")
                    syncFirestorePresenceMirror(
                        userId = userId,
                        isOnline = true,
                        viewingChatId = currentActiveChatId,
                        lastSeen = trustedLastSeenByUser[userId]
                    )
                    startHeartbeat()
                }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to go online", e) }
        } catch (e: Exception) {
            Log.e(TAG, "Error in goOnline", e)
        }
    }

    /**
     * Set the current user as offline.
     * Called when the app goes to background or user logs out.
     */
    fun goOffline() {
        try {
            val userId = auth.currentUser?.uid ?: return
            
            // If we are already offline, don't update lastSeen again.
            // This prevents FCM background wakes from updating lastSeen when they finish.
            if (!isAppInForeground) {
                return
            }
            
            isAppInForeground = false
            activeChatId = null
            
            // Stop heartbeat
            stopHeartbeat()
            
            val offlineData = mapOf(
                "isOnline" to false,
                "lastSeen" to ServerValue.TIMESTAMP,
                "viewingChat" to null
            )
            val offlineLastSeen = System.currentTimeMillis()
            
            val userPresenceRef = presenceRef.child(userId)

            // Publish the offline mirror immediately so observers can flip to offline
            // without waiting for the RTDB round-trip or heartbeat timeout path.
            syncFirestorePresenceMirror(
                userId = userId,
                isOnline = false,
                viewingChatId = null,
                lastSeen = offlineLastSeen
            )
            
            // CRITICAL FIX: Write offline data FIRST, then cancel onDisconnect.
            // Previous implementation chained the offline write on cancel completion,
            // creating a race condition: if the process died between cancel completing
            // and the write starting, the user would be stuck as isOnline=true with
            // no onDisconnect safety net.
            //
            // Now: fire both simultaneously. Even if cancel completes but write fails
            // (process death), the connection will drop and — since we haven't yet
            // canceled onDisconnect — the handler will still fire as fallback.
            userPresenceRef.updateChildren(offlineData)
                .addOnSuccessListener {
                    // Only cancel onDisconnect AFTER the manual write succeeds.
                    // This ensures we always have a safety net until the write is confirmed.
                    userPresenceRef.onDisconnect().cancel()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to go offline — onDisconnect handler preserved as fallback", e)
                    // Intentionally do NOT cancel onDisconnect here.
                    // The handler will fire when the connection drops, providing a safety net.
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error in goOffline", e)
        }
    }

    /**
     * Mark user as viewing a specific chat.
     * This allows receivers to see accurate "online in this chat" status.
     * 
     * IMPORTANT: This method assumes the user is already online (called goOnline()).
     * If user is not online yet, this will implicitly set them online.
     */
    fun setViewingChat(chatId: String?) {
        activeChatId = chatId
        val userId = auth.currentUser?.uid ?: run {
            if (chatId != null || isAppInForeground) {
                deferPresenceReplay("setViewingChat_without_auth chatId=$chatId")
            }
            return
        }
        
        try {
            if (chatId != null) {
                // Ensure user is online and mark which chat they're viewing
                val chatPresenceData = mapOf(
                    "isOnline" to true,
                    "viewingChat" to chatId,
                    "lastHeartbeat" to ServerValue.TIMESTAMP
                )
                presenceRef.child(userId).updateChildren(chatPresenceData)
                syncFirestorePresenceMirror(
                    userId = userId,
                    isOnline = true,
                    viewingChatId = chatId,
                    lastSeen = trustedLastSeenByUser[userId]
                )
                
                // Start heartbeat if not already running
                startHeartbeat()
                StartupTrace.logStage("presence_viewing_chat", "uid=$userId chatId=$chatId")
                
            } else {
                // User left chat but app still in foreground
                presenceRef.child(userId).child("viewingChat").removeValue()
                syncFirestorePresenceMirror(
                    userId = userId,
                    isOnline = isAppInForeground,
                    viewingChatId = null,
                    lastSeen = trustedLastSeenByUser[userId]
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting viewing chat", e)
        }
    }

    /**
     * Clean up presence tracking when user logs out.
     * CRITICAL: This should ONLY be called on actual logout, NOT when leaving a chat.
     */
    fun cleanup() {
        try {
            stopHeartbeat()
            
            connectionListener?.let { listener ->
                connectedRef.removeEventListener(listener)
            }
            connectionListener = null
            isInitialized = false
            isAppInForeground = false
            activeChatId = null
            _isConnected.value = false
            receivedFirstSnapshotFor.clear()
            
            // Set user as offline
            auth.currentUser?.uid?.let { userId ->
                val offlineData = mapOf(
                    "isOnline" to false,
                    "lastSeen" to ServerValue.TIMESTAMP,
                    "viewingChat" to null
                )
                presenceRef.child(userId).updateChildren(offlineData)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in cleanup", e)
        }
    }

    /**
     * Observe the presence status of a specific user.
     * Returns a Flow that emits PresenceStatus updates.
     * 
     * Includes heartbeat-based timeout: if lastHeartbeat is older than PRESENCE_TIMEOUT_MS,
     * consider user offline even if isOnline=true (handles stale connections).
     */
    fun observeUserPresence(userId: String): Flow<PresenceStatus> = callbackFlow {
        val userPresenceRef = presenceRef.child(userId)
        var lastEmittedStatus: PresenceStatus? = null
        var firestoreListener: ListenerRegistration? = null
        data class RealtimePresenceSnapshot(
            val isOnlineFlag: Boolean = false,
            val lastSeen: Long = 0L,
            val lastHeartbeat: Long = 0L,
            val viewingChatId: String? = null
        )
        var rtdbStatus: RealtimePresenceSnapshot? = null
        var firestoreStatus: FirestorePresenceMirror? = null

        fun emitMergedStatus() {
            val now = System.currentTimeMillis()
            val mirror = firestoreStatus
            val mirrorFresh = mirror != null && (now - mirror.updatedAt) < PRESENCE_TIMEOUT_MS
            val rtdb = rtdbStatus
            val heartbeatAge = if ((rtdb?.lastHeartbeat ?: 0L) <= 0L) {
                Long.MAX_VALUE
            } else {
                (now - (rtdb?.lastHeartbeat ?: 0L)).coerceAtLeast(0L)
            }
            val rtdbEffectivelyOnline = rtdb?.isOnlineFlag == true && heartbeatAge < PRESENCE_TIMEOUT_MS
            val sanitizedLastSeen = sanitizeLastSeenForDisplay(
                userId = userId,
                rawLastSeen = rtdb?.lastSeen ?: mirror?.lastSeen ?: 0L,
                effectivelyOnline = rtdbEffectivelyOnline,
                previousStatus = lastEmittedStatus
            )
            val preferOfflineMirror = mirror != null && !mirror.isOnline && (
                mirrorFresh
                    || rtdb == null
                    || rtdbEffectivelyOnline
                    || mirror.lastSeen >= sanitizedLastSeen
            )
            val merged = when {
                preferOfflineMirror -> PresenceStatus(
                    isOnline = false,
                    lastSeen = mirror!!.lastSeen,
                    viewingChatId = null
                )
                rtdbEffectivelyOnline -> PresenceStatus(
                    isOnline = true,
                    lastSeen = sanitizedLastSeen,
                    viewingChatId = rtdb?.viewingChatId
                )
                mirror != null && mirror.isOnline && mirrorFresh -> PresenceStatus(
                    isOnline = true,
                    lastSeen = if (sanitizedLastSeen > 0L) sanitizedLastSeen else mirror.lastSeen,
                    viewingChatId = mirror.viewingChatId
                )
                rtdb != null -> PresenceStatus(
                    isOnline = false,
                    lastSeen = sanitizedLastSeen,
                    viewingChatId = null
                )
                mirror != null -> PresenceStatus(
                    isOnline = false,
                    lastSeen = mirror.lastSeen,
                    viewingChatId = mirror.viewingChatId
                )
                else -> PresenceStatus(false, 0L, null)
            }
            if (merged != lastEmittedStatus) {
                lastEmittedStatus = merged
                trySend(merged)
            } else {
            }
        }

        primeTransport("observeUserPresence:$userId")
        userPresenceRef.keepSynced(true)
        
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                val lastHeartbeat = snapshot.child("lastHeartbeat").getValue(Long::class.java) ?: 0L
                val viewingChatRaw = snapshot.child("viewingChat").getValue(String::class.java)
                val viewingChatId = viewingChatRaw?.takeIf { it.isNotBlank() }

                val now = System.currentTimeMillis()
                val rawHeartbeatAge = now - lastHeartbeat
                // Firebase ServerValue.TIMESTAMP is the *server* clock; System.currentTimeMillis()
                // is the *client* clock. If the server is even a few milliseconds ahead of the
                // client, rawHeartbeatAge is negative. The old check `in 0 until TIMEOUT` rejected
                // those negative values and incorrectly considered the user offline, producing
                // the false "last seen" bug. Clamping to 0 is safe: a negative age means the
                // heartbeat literally just arrived and is well within the timeout window.
                val heartbeatAge = if (rawHeartbeatAge < 0L) 0L else rawHeartbeatAge
                val effectivelyOnline = isOnline && lastHeartbeat > 0L && heartbeatAge < PRESENCE_TIMEOUT_MS

                rtdbStatus = RealtimePresenceSnapshot(
                    isOnlineFlag = isOnline,
                    lastSeen = lastSeen,
                    lastHeartbeat = lastHeartbeat,
                    viewingChatId = viewingChatId
                )
                emitMergedStatus()
            }

            override fun onCancelled(error: DatabaseError) {
                // Permission errors happen naturally when a user is blocked (RTDB
                // rules prevent the blocked user from reading presence).  Instead of
                // closing this flow (which would kill any combine/merge chain using
                // observeMultipleUsersPresence), emit a fallback and keep the flow
                // alive.  When the user is unblocked, the caller can restart the
                // listener and normal emissions will resume.
                rtdbStatus = null
                emitMergedStatus()
            }
        }

        firestoreListener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Failed to observe Firestore presence mirror for $userId", error)
                    return@addSnapshotListener
                }

                val data = snapshot?.data
                firestoreStatus = if (data != null) {
                    FirestorePresenceMirror(
                        isOnline = data["isOnline"] as? Boolean ?: false,
                        lastSeen = (data["lastSeen"] as? Number)?.toLong() ?: 0L,
                        viewingChatId = data["viewingChat"] as? String,
                        updatedAt = (data["presenceUpdatedAt"] as? Number)?.toLong() ?: 0L
                    )
                } else {
                    null
                }
                emitMergedStatus()
            }
        
        userPresenceRef.addValueEventListener(listener)

        val reevaluationJob = launch {
            while (isActive) {
                delay(PRESENCE_REEVALUATION_INTERVAL_MS)
                if (rtdbStatus?.isOnlineFlag == true || firestoreStatus != null) {
                    emitMergedStatus()
                }
            }
        }
        
        awaitClose {
            reevaluationJob.cancel()
            userPresenceRef.removeEventListener(listener)
            firestoreListener?.remove()
        }
    }

    /**
     * Get the current presence status of a user (one-time fetch).
     */
    fun getUserPresence(userId: String, callback: (PresenceStatus) -> Unit) {
        presenceRef.child(userId).get()
            .addOnSuccessListener { snapshot ->
                val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                callback(PresenceStatus(isOnline, lastSeen))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to get presence for $userId", e)
                callback(PresenceStatus(false, 0L))
            }
    }

    /**
     * Observe presence for multiple users at once.
     * Returns a Flow that emits a Map of userId to PresenceStatus.
     * 
     * Includes heartbeat-based timeout for accurate presence detection.
     */
    fun observeMultipleUsersPresence(userIds: List<String>): Flow<Map<String, PresenceStatus>> = callbackFlow {
        val distinctUserIds = userIds.distinct().filter { it.isNotBlank() }
        if (distinctUserIds.isEmpty()) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }

        primeTransport("observeMultipleUsersPresence:${distinctUserIds.size}")

        val combinedJob = launch {
            combine(distinctUserIds.map { userId -> observeUserPresence(userId) }) { statuses ->
                distinctUserIds.mapIndexed { index, userId ->
                    userId to statuses[index]
                }.toMap()
            }.collect { statusMap ->
                trySend(statusMap)
            }
        }

        awaitClose {
            combinedJob.cancel()
        }
    }

    /**
     * Set typing status for a specific chat.
     */
    fun setTypingStatus(chatId: String, otherUserId: String, isTyping: Boolean) {
        val userId = auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()

        primeTransport("setTypingStatus:$chatId")
        chatsRef.child(chatId).keepSynced(true)

        chatsRef.child(chatId).child("typing").child(userId).child(otherUserId).setValue(isTyping)
            .addOnFailureListener { e -> Log.e(TAG, "Failed to set typing status", e) }

        firestore.collection("chats").document(chatId)
            .set(
                mapOf(
                    "typingStates" to mapOf(
                        userId to mapOf(
                            otherUserId to mapOf(
                                "isTyping" to isTyping,
                                "updatedAt" to now
                            )
                        )
                    )
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .addOnFailureListener { e -> Log.e(TAG, "Failed to mirror typing status to Firestore", e) }

        if (isTyping) {
            chatsRef.child(chatId).child("typing").child(userId).child(otherUserId).onDisconnect().setValue(false)
        }
    }

    /**
     * Observe typing status of another user in a specific chat.
     */
    fun observeTypingStatus(chatId: String, otherUserId: String): Flow<Boolean> = callbackFlow {
        val myUserId = auth.currentUser?.uid
        if (myUserId == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        val typingRef = chatsRef.child(chatId).child("typing").child(otherUserId).child(myUserId)
        var firestoreListener: ListenerRegistration? = null
        var rtdbTyping = false
        var firestoreTyping: FirestoreTypingMirror? = null

        fun emitMergedTyping() {
            val now = System.currentTimeMillis()
            val firestoreValue = firestoreTyping
            val useFirestore = firestoreValue != null && (now - firestoreValue.updatedAt) < TYPING_FRESHNESS_MS
            trySend(if (useFirestore) firestoreValue!!.isTyping else rtdbTyping)
        }

        primeTransport("observeTypingStatus:$chatId")
        chatsRef.child(chatId).keepSynced(true)
        typingRef.keepSynced(true)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                rtdbTyping = snapshot.getValue(Boolean::class.java) ?: false
                emitMergedTyping()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to observe typing status", error.toException())
            }
        }

        firestoreListener = firestore.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Failed to observe Firestore typing status", error)
                    return@addSnapshotListener
                }

                firestoreTyping = extractFirestoreTypingMirror(snapshot?.data, otherUserId, myUserId)
                emitMergedTyping()
            }

        typingRef.addValueEventListener(listener)

        awaitClose {
            typingRef.removeEventListener(listener)
            firestoreListener?.remove()
        }
    }
}
