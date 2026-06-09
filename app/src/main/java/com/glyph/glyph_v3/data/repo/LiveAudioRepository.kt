package com.glyph.glyph_v3.data.repo

import android.util.Log
import com.glyph.glyph_v3.data.models.LiveAudioAudience
import com.glyph.glyph_v3.data.models.LiveAudioConfig
import com.glyph.glyph_v3.data.models.LiveAudioIceCandidate
import com.glyph.glyph_v3.data.models.LiveAudioSession
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Repository for live audio sharing signaling.
 *
 * Config storage: Firestore  users/{userId}/liveAudio/config
 * Session signaling: Realtime DB  liveAudioSessions/{sessionId}
 * ICE candidates: Realtime DB  liveAudioSessions/{sessionId}/candidates/{id}
 */
class LiveAudioRepository {

    companion object {
        private const val TAG = "LiveAudioRepo"
        private const val FIRESTORE_COLLECTION = "users"
        private const val CONFIG_SUBCOLLECTION = "liveAudio"
        private const val CONFIG_DOC = "config"
        private const val RTDB_SESSIONS = "liveAudioSessions"
        private const val RTDB_CANDIDATES = "candidates"
    }

    private val firestore = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val currentUserId: String
        get() = auth.currentUser?.uid ?: ""

    // ── Incoming request listener — persistent singleton ────────────────────────
    //
    // Same pattern as WalkieTalkieRepository: decouple Firebase listener lifecycle
    // from coroutine collection to avoid "listen() called twice" RTDB crash.
    // ─────────────────────────────────────────────────────────────────────────────

    private val _incomingRequestEvents =
        MutableSharedFlow<LiveAudioSession>(extraBufferCapacity = 16)

    private var incomingReqListener: ChildEventListener? = null
    private var incomingReqRef: com.google.firebase.database.Query? = null

    /**
     * Attach the incoming-request listener. Idempotent — safe to call multiple times;
     * the Firebase listener is only ever registered once until [detachIncomingRequestListener].
     */
    fun attachIncomingRequestListener() {
        if (incomingReqListener != null) return  // already attached
        val uid = currentUserId
        if (uid.isEmpty()) return

        val ref = rtdb.getReference(RTDB_SESSIONS)
            .orderByChild("broadcasterId")
            .equalTo(uid)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val session = snapshot.getValue(LiveAudioSession::class.java) ?: return
                if (session.status == LiveAudioSession.STATUS_REQUESTING) {
                    _incomingRequestEvents.tryEmit(session)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Incoming request listener cancelled: ${error.message}")
            }
        }

        incomingReqRef = ref
        incomingReqListener = listener
        ref.addChildEventListener(listener)
    }

    /**
     * Detach the incoming-request listener. Idempotent — safe to call when not attached.
     */
    fun detachIncomingRequestListener() {
        val listener = incomingReqListener ?: return
        incomingReqRef?.removeEventListener(listener)
        incomingReqRef = null
        incomingReqListener = null
    }

    /** Events emitted by the persistent incoming-request listener. */
    fun incomingRequestEvents(): Flow<LiveAudioSession> = _incomingRequestEvents

    // ── Config (Firestore) ──────────────────────────────────────────

    /** Save or update the user's live audio config. */
    suspend fun saveConfig(config: LiveAudioConfig) {
        firestore.collection(FIRESTORE_COLLECTION)
            .document(currentUserId)
            .collection(CONFIG_SUBCOLLECTION)
            .document(CONFIG_DOC)
            .set(config)
            .await()
    }

    /** Update individual fields of the config (atomic). Creates the document if missing. */
    suspend fun updateConfigFields(fields: Map<String, Any>) {
        firestore.collection(FIRESTORE_COLLECTION)
            .document(currentUserId)
            .collection(CONFIG_SUBCOLLECTION)
            .document(CONFIG_DOC)
            .set(fields, com.google.firebase.firestore.SetOptions.merge())
            .await()
    }

    /** Get the current user's config once. */
    suspend fun getMyConfig(): LiveAudioConfig? {
        val snap = firestore.collection(FIRESTORE_COLLECTION)
            .document(currentUserId)
            .collection(CONFIG_SUBCOLLECTION)
            .document(CONFIG_DOC)
            .get()
            .await()
        return snap.toObject(LiveAudioConfig::class.java)
    }

    /** Get another user's config once. */
    suspend fun getUserConfig(userId: String): LiveAudioConfig? {
        val snap = firestore.collection(FIRESTORE_COLLECTION)
            .document(userId)
            .collection(CONFIG_SUBCOLLECTION)
            .document(CONFIG_DOC)
            .get()
            .await()
        return snap.toObject(LiveAudioConfig::class.java)
    }

    /** Observe another user's config in real-time. */
    fun observeUserConfig(userId: String): Flow<LiveAudioConfig?> = callbackFlow {
        val reg: ListenerRegistration = firestore.collection(FIRESTORE_COLLECTION)
            .document(userId)
            .collection(CONFIG_SUBCOLLECTION)
            .document(CONFIG_DOC)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Config observe error for $userId", error)
                    trySend(null)
                    return@addSnapshotListener
                }
                val config = snapshot?.toObject(LiveAudioConfig::class.java)
                trySend(config)
            }
        awaitClose { reg.remove() }
    }

    /** Check if current user is allowed to listen to another user. */
    suspend fun canListenTo(broadcasterId: String): Boolean {
        val config = getUserConfig(broadcasterId) ?: return false
        if (!config.shareMic) return false
        return when (config.audienceEnum()) {
            LiveAudioAudience.EVERYONE -> true
            LiveAudioAudience.CONTACTS -> true // Simplified: server-side validation recommended
            LiveAudioAudience.SELECTED_USERS -> currentUserId in config.allowedUsers
        }
    }

    // ── Session Signaling (Realtime DB) ─────────────────────────────

    /** Create a new listening request session. Returns session ID. */
    suspend fun createSession(broadcasterId: String): String {
        try {
            rtdb.goOnline()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to nudge RTDB online before live-audio session create", e)
        }
        val sessionId = UUID.randomUUID().toString()
        val session = LiveAudioSession(
            sessionId = sessionId,
            broadcasterId = broadcasterId,
            listenerId = currentUserId,
            status = LiveAudioSession.STATUS_REQUESTING,
            createdAt = System.currentTimeMillis()
        )
        rtdb.getReference(RTDB_SESSIONS)
            .child(sessionId)
            .setValue(session)
            .await()
        return sessionId
    }

    /** Set the SDP offer (broadcaster → listener). */
    suspend fun setOffer(sessionId: String, sdp: String) {
        rtdb.getReference(RTDB_SESSIONS).child(sessionId)
            .updateChildren(
                mapOf(
                    "offer" to sdp,
                    "status" to LiveAudioSession.STATUS_ACTIVE
                )
            ).await()
    }

    /** Set the SDP answer (listener → broadcaster). */
    suspend fun setAnswer(sessionId: String, sdp: String) {
        rtdb.getReference(RTDB_SESSIONS).child(sessionId)
            .updateChildren(mapOf("answer" to sdp))
            .await()
    }

    /** Add an ICE candidate. */
    suspend fun addIceCandidate(sessionId: String, candidate: LiveAudioIceCandidate) {
        rtdb.getReference(RTDB_SESSIONS).child(sessionId)
            .child(RTDB_CANDIDATES)
            .push()
            .setValue(candidate)
            .await()
    }

    /** Get a session once (used for FCM wake-up path). */
    suspend fun getSession(sessionId: String): LiveAudioSession? {
        val snap = rtdb.getReference(RTDB_SESSIONS).child(sessionId).get().await()
        return snap.getValue(LiveAudioSession::class.java)
    }

    /** End a session cleanly. */
    suspend fun endSession(sessionId: String) {
        rtdb.getReference(RTDB_SESSIONS).child(sessionId)
            .updateChildren(
                mapOf(
                    "status" to LiveAudioSession.STATUS_ENDED,
                    "endedAt" to System.currentTimeMillis()
                )
            ).await()
    }

    /** Observe a session for changes (offer/answer/status). */
    fun observeSession(sessionId: String): Flow<LiveAudioSession?> = callbackFlow {
        val ref = rtdb.getReference(RTDB_SESSIONS).child(sessionId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val session = snapshot.getValue(LiveAudioSession::class.java)
                trySend(session)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Session observe cancelled: ${error.message}")
                trySend(null)
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    // observeIncomingRequests() replaced by attachIncomingRequestListener() / incomingRequestEvents()

    /** Observe ICE candidates for a session from the remote peer. */
    fun observeIceCandidates(sessionId: String): Flow<LiveAudioIceCandidate> = callbackFlow {
        val ref = rtdb.getReference(RTDB_SESSIONS).child(sessionId).child(RTDB_CANDIDATES)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidate = snapshot.getValue(LiveAudioIceCandidate::class.java) ?: return
                // Only emit remote candidates
                if (candidate.fromUserId != currentUserId) {
                    trySend(candidate)
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "ICE candidates observe cancelled: ${error.message}")
            }
        }
        ref.addChildEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /** Clean up stale sessions for current user (broadcaster or listener). */
    suspend fun cleanupStaleSessions() {
        try {
            // End any active sessions where we're the broadcaster
            val broadcasterSnap = rtdb.getReference(RTDB_SESSIONS)
                .orderByChild("broadcasterId")
                .equalTo(currentUserId)
                .get().await()
            for (child in broadcasterSnap.children) {
                val session = child.getValue(LiveAudioSession::class.java) ?: continue
                if (session.status != LiveAudioSession.STATUS_ENDED) {
                    endSession(session.sessionId)
                }
            }
            // End any active sessions where we're the listener
            val listenerSnap = rtdb.getReference(RTDB_SESSIONS)
                .orderByChild("listenerId")
                .equalTo(currentUserId)
                .get().await()
            for (child in listenerSnap.children) {
                val session = child.getValue(LiveAudioSession::class.java) ?: continue
                if (session.status != LiveAudioSession.STATUS_ENDED) {
                    endSession(session.sessionId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stale session cleanup failed", e)
        }
    }

    /** Update the streaming state + listener list atomically. */
    suspend fun updateStreamingState(isStreaming: Boolean, listenerIds: List<String>) {
        updateConfigFields(
            mapOf(
                "isStreaming" to isStreaming,
                "currentListeners" to listenerIds
            )
        )
    }
}
