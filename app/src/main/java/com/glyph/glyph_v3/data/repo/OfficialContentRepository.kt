package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.glyph.glyph_v3.data.models.OfficialMessage
import com.glyph.glyph_v3.data.models.OfficialMessageKind
import com.glyph.glyph_v3.data.models.OfficialStatus
import com.glyph.glyph_v3.data.models.OfficialStatusType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes portal-published official content (Phase 18 F4).
 *
 * The portal writes `official_messages` / `official_status` via the Admin SDK;
 * this repository reads them with the signed-in user's credentials. It is a
 * read-only mirror — per `firestore.rules` the client cannot write these
 * collections. Listeners are started once the user is authenticated (wired
 * from [com.glyph.glyph_v3.GlyphApplication]) and stopped on logout.
 *
 * Ordering matches the portal:
 *  - official_messages ordered by `publishedAt` desc (pinned first).
 *  - official_status ordered by `createdAt` desc, filtered to LIVE only
 *    (scheduled/expired are excluded client-side, mirroring statusLifecycle).
 *
 * Side channels exposed for the UI / notifications:
 *  - [newContentEvents] — emits once per *newly seen* official message/status so
 *    the app can post a local notification without re-notifying historical
 *    content on every launch (de-duped via a persisted `seen` id set).
 *  - [lastOpenedAtFlow] — timestamp of when the user last opened the official
 *    chat; used by the chat list to compute the unread badge.
 */
object OfficialContentRepository {
    private const val TAG = "OfficialContentRepo"
    private const val MESSAGES_COLLECTION = "official_messages"
    private const val STATUS_COLLECTION = "official_status"

    private const val PREFS_NAME = "official_content"
    private const val KEY_SEEN_IDS = "seen_ids"
    private const val KEY_LAST_OPENED = "last_opened"

    private const val MAX_SEEN_IDS = 500

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null

    private val seenIds = mutableSetOf<String>()
    /** True only when the persisted seen-set was empty at process start (brand-new install). */
    private var prefsWereEmpty = false
    private var firstMessagesEmission = false
    private var firstStatusesEmission = false

    private val _officialMessages = MutableStateFlow<List<OfficialMessage>>(emptyList())
    val officialMessages: StateFlow<List<OfficialMessage>> = _officialMessages.asStateFlow()

    private val _officialStatuses = MutableStateFlow<List<OfficialStatus>>(emptyList())
    val officialStatuses: StateFlow<List<OfficialStatus>> = _officialStatuses.asStateFlow()

    /** Emitted once per newly-seen official message/status (for local notifications). */
    private val _newContentEvents = MutableSharedFlow<OfficialContentEvent>(extraBufferCapacity = 64)
    val newContentEvents = _newContentEvents.asSharedFlow()

    private val _lastOpenedAt = MutableStateFlow(0L)
    val lastOpenedAtFlow: StateFlow<Long> = _lastOpenedAt.asStateFlow()

    private var messagesListener: ListenerRegistration? = null
    private var statusListener: ListenerRegistration? = null

    val currentUserId: String?
        get() = auth.currentUser?.uid

    fun startListening(context: Context? = null) {
        context?.let {
            appContext = it.applicationContext
            prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _lastOpenedAt.value = prefs?.getLong(KEY_LAST_OPENED, 0L) ?: 0L
            // Seed in-memory seen set from the persisted one.
            seenIds.clear()
            val persistedSeen = prefs?.getStringSet(KEY_SEEN_IDS, emptySet()) ?: emptySet()
            seenIds.addAll(persistedSeen)
            prefsWereEmpty = persistedSeen.isEmpty()
        }
        if (currentUserId == null) return
        firstMessagesEmission = false
        firstStatusesEmission = false
        startListeningMessages()
        startListeningStatuses()
    }

    fun stopListening() {
        messagesListener?.remove()
        messagesListener = null
        statusListener?.remove()
        statusListener = null
        _officialMessages.value = emptyList()
        _officialStatuses.value = emptyList()
    }

    /** Record that the user just viewed the official chat (clears the unread badge). */
    fun markOpened() {
        val now = System.currentTimeMillis()
        _lastOpenedAt.value = now
        prefs?.edit()?.putLong(KEY_LAST_OPENED, now)?.apply()
    }

    private fun persistSeenIds() {
        // Cap the set so it can't grow unbounded across many portal posts.
        if (seenIds.size > MAX_SEEN_IDS) {
            val keep = seenIds.take(MAX_SEEN_IDS / 2).toSet()
            seenIds.clear()
            seenIds.addAll(keep)
        }
        prefs?.edit()?.putStringSet(KEY_SEEN_IDS, seenIds.toSet())?.apply()
    }

    /**
     * Diffs [items] against [seenIds] and emits a [OfficialContentEvent] for each
     * genuinely new id (recorded as seen + persisted). De-dup rules:
     *  - On the first emission of a flow, if this is a brand-new install
     *    ([prefsWereEmpty]) seed [seenIds] with everything currently present so
     *    historical content does NOT spam notifications on first launch.
     *  - On the first emission of a flow where prefs already had data (returning
     *    user), behave normally — any id not already seen (e.g. content published
     *    while the app was dead) is notified.
     *  - Subsequent emissions notify only new ids.
     */
    private fun <T> processNewItems(
        items: List<T>,
        getId: (T) -> String,
        toEvent: (T) -> OfficialContentEvent,
        isFirstEmission: Boolean
    ) {
        if (items.isEmpty()) return
        if (isFirstEmission && prefsWereEmpty) {
            items.forEach { seenIds.add(getId(it)) }
            persistSeenIds()
            return
        }
        val newOnes = items.filter { getId(it) !in seenIds }
        if (newOnes.isEmpty()) return
        newOnes.forEach { item ->
            seenIds.add(getId(item))
            _newContentEvents.tryEmit(toEvent(item))
        }
        persistSeenIds()
    }

    private fun startListeningMessages() {
        messagesListener?.remove()
        // Single-field descending order is covered by the automatic index.
        messagesListener = firestore.collection(MESSAGES_COLLECTION)
            .orderBy("publishedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to official messages", error)
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val messages = snapshot?.documents
                    ?.mapNotNull { doc -> mapToOfficialMessage(doc.id, doc.data) }
                    ?.filter { it.publishedAt <= now || it.publishedAt == 0L }
                    ?.sortedWith(
                        compareByDescending<OfficialMessage> { it.pinned }
                            .thenByDescending { if (it.publishedAt > 0) it.publishedAt else it.createdAt }
                    )
                    ?: emptyList()
                processNewItems(
                    items = messages,
                    getId = { it.id },
                    toEvent = { OfficialContentEvent.NewMessage(it) },
                    isFirstEmission = !firstMessagesEmission
                )
                firstMessagesEmission = true
                if (_officialMessages.value != messages) {
                    _officialMessages.value = messages
                }
            }
    }

    private fun startListeningStatuses() {
        statusListener?.remove()
        statusListener = firestore.collection(STATUS_COLLECTION)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to official status", error)
                    return@addSnapshotListener
                }
                val statuses = snapshot?.documents
                    ?.mapNotNull { doc -> mapToOfficialStatus(doc.id, doc.data) }
                    ?.filter { it.isLive }
                    ?: emptyList()
                processNewItems(
                    items = statuses,
                    getId = { it.id },
                    toEvent = { OfficialContentEvent.NewStatus(it) },
                    isFirstEmission = !firstStatusesEmission
                )
                firstStatusesEmission = true
                if (_officialStatuses.value != statuses) {
                    _officialStatuses.value = statuses
                }
            }
    }

    private fun mapToOfficialMessage(id: String, data: Map<String, Any>?): OfficialMessage? {
        if (data == null) return null
        return try {
            OfficialMessage(
                id = id,
                kind = runCatching {
                    OfficialMessageKind.valueOf((data["kind"] as? String) ?: "ANNOUNCEMENT")
                }.getOrDefault(OfficialMessageKind.ANNOUNCEMENT),
                title = (data["title"] as? String) ?: "",
                body = (data["body"] as? String) ?: "",
                imageUrl = (data["imageUrl"] as? String) ?: "",
                deepLink = (data["deepLink"] as? String) ?: "",
                pinned = data["pinned"] == true,
                createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
                publishedAt = (data["publishedAt"] as? Number)?.toLong() ?: 0L,
                createdBy = (data["createdBy"] as? String) ?: "system"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse official message $id", e)
            null
        }
    }

    private fun mapToOfficialStatus(id: String, data: Map<String, Any>?): OfficialStatus? {
        if (data == null) return null
        val longOrNull = { v: Any? -> (v as? Number)?.toLong()?.takeIf { it > 0L } }
        return try {
            OfficialStatus(
                id = id,
                type = runCatching {
                    OfficialStatusType.valueOf((data["type"] as? String) ?: "TEXT")
                }.getOrDefault(OfficialStatusType.TEXT),
                text = (data["text"] as? String) ?: "",
                mediaUrl = (data["mediaUrl"] as? String) ?: "",
                caption = (data["caption"] as? String) ?: "",
                backgroundColor = (data["backgroundColor"] as? String) ?: "",
                scheduledAt = longOrNull(data["scheduledAt"]),
                expiresAt = longOrNull(data["expiresAt"]),
                publishedAt = longOrNull(data["publishedAt"]),
                createdAt = longOrNull(data["createdAt"]),
                createdBy = (data["createdBy"] as? String) ?: "system",
                views = (data["views"] as? Number)?.toInt() ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse official status $id", e)
            null
        }
    }

    fun cleanup() {
        stopListening()
    }
}

/** One-shot event for a newly-published official item (drives local notifications). */
sealed interface OfficialContentEvent {
    val id: String
    data class NewMessage(val message: OfficialMessage) : OfficialContentEvent {
        override val id get() = message.id
    }
    data class NewStatus(val status: OfficialStatus) : OfficialContentEvent {
        override val id get() = status.id
    }
}
