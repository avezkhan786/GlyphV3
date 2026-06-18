package com.glyph.glyph_v3.data.repo

import android.util.Log
import com.glyph.glyph_v3.data.models.WalkieTalkieFloor
import com.glyph.glyph_v3.data.models.WalkieTalkieIceCandidate
import com.glyph.glyph_v3.data.models.WalkieTalkiePttEffectEvent
import com.glyph.glyph_v3.data.models.WalkieTalkieSession
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Firebase repository for walkie-talkie signaling and floor control.
 *
 * Session signaling: RTDB  walkieTalkieSessions/{sessionId}
 * Floor control:     RTDB  walkieTalkieSessions/{sessionId}/floor
 * ICE candidates:    RTDB  walkieTalkieSessions/{sessionId}/candidates/{id}
 */
class WalkieTalkieRepository {

    companion object {
        private const val TAG = "WalkieTalkieRepo"
        private const val RTDB_SESSIONS = "walkieTalkieSessions"
        private const val RTDB_CANDIDATES = "candidates"
        private const val RTDB_FLOOR = "floor"
        private const val REALTIME_SIGNALING_WRITE_TIMEOUT_MS = 1_500L
        private const val REALTIME_ICE_WRITE_TIMEOUT_MS = 1_500L
    }

    private val rtdb = FirebaseDatabase.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val currentUserId: String
        get() = auth.currentUser?.uid ?: ""

    private fun requestDoc(sessionId: String) = firestore.collection("walkieTalkieRequests").document(sessionId)

    private fun candidateCollection(sessionId: String) = requestDoc(sessionId).collection(RTDB_CANDIDATES)

    private suspend fun mirrorSessionUpdates(sessionId: String, updates: Map<String, Any?>) {
        requestDoc(sessionId)
            .set(updates, SetOptions.merge())
            .await()
    }

    /**
     * Signaling must reach RTDB as quickly as possible (offer/answer/status/candidates).
     * Firestore is a durability mirror, but never the critical path.
     *
     * Returns true when at least one backend write succeeds.
     */
    private suspend fun writeSessionUpdatesWithFallback(
        sessionId: String,
        updates: Map<String, Any?>,
        opName: String
    ): Boolean {
        val realtimeResult = withTimeoutOrNull(REALTIME_SIGNALING_WRITE_TIMEOUT_MS) {
            runCatching {
                rtdb.getReference(RTDB_SESSIONS)
                    .child(sessionId)
                    .updateChildren(updates)
                    .await()
            }
        }

        if (realtimeResult?.isSuccess == true) {
            requestDoc(sessionId)
                .set(updates, SetOptions.merge())
                .addOnFailureListener { error ->
                    Log.w(TAG, "WT $opName: Firestore mirror failed for session=$sessionId", error)
                }
            return true
        }

        val realtimeError = realtimeResult?.exceptionOrNull()
            ?: RuntimeException("WT $opName RTDB write timed out after ${REALTIME_SIGNALING_WRITE_TIMEOUT_MS}ms")
        val firestoreError = runCatching {
            mirrorSessionUpdates(sessionId, updates)
        }.exceptionOrNull()

        if (firestoreError != null) {
            throw RuntimeException(
                "WT $opName failed on both Firestore and RTDB for session=$sessionId",
                realtimeError
            )
        }

        Log.w(TAG, "WT $opName: RTDB write failed/timed out, Firestore fallback succeeded for session=$sessionId", realtimeError)
        return true
    }

    private fun sessionFromMap(sessionId: String, data: Map<String, Any?>?): WalkieTalkieSession? {
        if (data == null) return null
        return WalkieTalkieSession(
            sessionId = data["sessionId"] as? String ?: sessionId,
            initiatorId = data["initiatorId"] as? String ?: "",
            initiatorName = data["initiatorName"] as? String ?: "",
            responderId = data["responderId"] as? String ?: "",
            status = data["status"] as? String ?: WalkieTalkieSession.STATUS_REQUESTING,
            statusUpdatedAt = (data["statusUpdatedAt"] as? Number)?.toLong() ?: 0L,
            endedBy = data["endedBy"] as? String,
            endReason = data["endReason"] as? String,
            offer = data["offer"] as? String,
            answer = data["answer"] as? String,
            offerRevision = (data["offerRevision"] as? Number)?.toInt() ?: 0,
            answerRevision = (data["answerRevision"] as? Number)?.toInt() ?: 0,
            answeredOfferRevision = (data["answeredOfferRevision"] as? Number)?.toInt() ?: 0,
            iceRestart = data["iceRestart"] as? Boolean ?: false,
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
            endedAt = (data["endedAt"] as? Number)?.toLong()
        )
    }

    fun primeTransport(reason: String, forceTokenRefresh: Boolean = false) {
        try {
            rtdb.goOnline()
            rtdb.getReference(RTDB_SESSIONS).keepSynced(true)

            val uid = currentUserId
            if (uid.isNotBlank()) {
                rtdb.getReference(RTDB_SESSIONS)
                    .orderByChild("responderId")
                    .equalTo(uid)
                    .keepSynced(true)
            }

            auth.currentUser?.getIdToken(forceTokenRefresh)
                ?.addOnFailureListener { error ->
                    Log.w(TAG, "WT auth prime failed for $reason", error)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prime WT transport for $reason", e)
        }
    }

    internal suspend fun awaitRealtimeConnection(timeoutMs: Long): Boolean {
        val connectedRef = rtdb.getReference(".info/connected")
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val connected = snapshot.getValue(Boolean::class.java) == true
                        if (connected && continuation.isActive) {
                            connectedRef.removeEventListener(this)
                            continuation.resume(true, null)
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        if (continuation.isActive) {
                            connectedRef.removeEventListener(this)
                            continuation.resume(false, null)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    connectedRef.removeEventListener(listener)
                }

                connectedRef.addValueEventListener(listener)
            }
        } ?: false
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun primeSession(sessionId: String) {
        // Note: do NOT call rtdb.goOnline() here — the app-level Firebase warmup in
        // GlyphApplication already manages the connection lifecycle. Calling goOnline()
        // inside a session-specific path risks triggering the DefaultRunLoop terminated
        // race condition (RejectedExecutionException on TubeSockReader) if another part
        // of the SDK is simultaneously cycling the connection state.
        val sessionRef = rtdb.getReference(RTDB_SESSIONS).child(sessionId)
        sessionRef.keepSynced(true)
        sessionRef.child(RTDB_CANDIDATES).keepSynced(true)
        sessionRef.child(RTDB_FLOOR).keepSynced(true)
    }

    // ── Session management ──────────────────────────────────────────

    /** Create a new walkie-talkie session. Returns session ID. */
    suspend fun createSession(
        responderId: String,
        initiatorName: String = "",
        sessionId: String = newSessionId(),
        initialOffer: String? = null,
        initialOfferRevision: Int = 0
    ): String {
        primeTransport("createSession", forceTokenRefresh = true)
        try {
            rtdb.goOnline()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to nudge RTDB online before walkie-talkie session create", e)
        }
        val now = System.currentTimeMillis()
        val normalizedOffer = initialOffer?.takeIf { it.isNotBlank() }
        val session = WalkieTalkieSession(
            sessionId = sessionId,
            initiatorId = currentUserId,
            initiatorName = initiatorName,
            responderId = responderId,
            status = WalkieTalkieSession.STATUS_REQUESTING,
            statusUpdatedAt = now,
            offer = normalizedOffer,
            offerRevision = if (normalizedOffer != null) initialOfferRevision.coerceAtLeast(1) else 0,
            createdAt = now
        )

        val realtimeResult = withTimeoutOrNull(REALTIME_SIGNALING_WRITE_TIMEOUT_MS) {
            runCatching {
                rtdb.getReference(RTDB_SESSIONS)
                    .child(sessionId)
                    .setValue(session)
                    .await()
            }
        }

        if (realtimeResult?.isSuccess == true) {
            requestDoc(sessionId)
                .set(session)
                .addOnFailureListener { error ->
                    Log.w(TAG, "WT createSession: Firestore mirror failed for session=$sessionId", error)
                }
            return sessionId
        }

        val realtimeError = realtimeResult?.exceptionOrNull()
            ?: RuntimeException("WT createSession RTDB write timed out after ${REALTIME_SIGNALING_WRITE_TIMEOUT_MS}ms")
        val firestoreError = runCatching {
            requestDoc(sessionId)
                .set(session)
                .await()
        }.exceptionOrNull()

        if (firestoreError != null) {
            throw RuntimeException(
                "WT createSession failed on both Firestore and RTDB for session=$sessionId",
                realtimeError
            )
        }

        Log.w(TAG, "WT createSession: RTDB write failed/timed out, Firestore fallback succeeded for session=$sessionId", realtimeError)
        return sessionId
    }

    /** Set the SDP offer (initiator → responder). */
    suspend fun setOffer(
        sessionId: String,
        sdp: String,
        offerRevision: Int,
        iceRestart: Boolean = false,
        status: String = WalkieTalkieSession.STATUS_ACTIVE
    ) {
        val updates = mapOf<String, Any?>(
            "offer" to sdp,
            "offerRevision" to offerRevision,
            "answer" to null,
            "answerRevision" to 0,
            "answeredOfferRevision" to 0,
            "iceRestart" to iceRestart,
            "status" to status,
            "statusUpdatedAt" to System.currentTimeMillis(),
            "endedBy" to null,
            "endReason" to null
        )
        writeSessionUpdatesWithFallback(sessionId, updates, opName = "setOffer")
    }

    /** Set the SDP answer (responder → initiator). */
    suspend fun setAnswer(
        sessionId: String,
        sdp: String,
        answerRevision: Int,
        answeredOfferRevision: Int,
        iceRestart: Boolean = false
    ) {
        val updates = mapOf<String, Any?>(
            "answer" to sdp,
            "answerRevision" to answerRevision,
            "answeredOfferRevision" to answeredOfferRevision,
            "iceRestart" to iceRestart,
            "status" to WalkieTalkieSession.STATUS_ACTIVE,
            "statusUpdatedAt" to System.currentTimeMillis(),
            "endedBy" to null,
            "endReason" to null
        )
        writeSessionUpdatesWithFallback(sessionId, updates, opName = "setAnswer")
    }

    suspend fun markSessionRinging(sessionId: String) {
        updateSessionStatus(
            sessionId = sessionId,
            status = WalkieTalkieSession.STATUS_RINGING,
            endedBy = null,
            endReason = null,
            clearAnswer = false,
            clearFloor = false
        )
    }

    /**
     * Fetch session from Firestore only.
     * Useful during RTDB cold reconnect windows after device/network wake-up.
     */
    suspend fun getSessionFromFirestore(sessionId: String): WalkieTalkieSession? {
        return runCatching {
            val doc = requestDoc(sessionId).get().await()
            sessionFromMap(sessionId, doc.data)
        }.getOrNull()
    }

    /** Get a session once. */
    suspend fun getSession(sessionId: String): WalkieTalkieSession? {
        val firestoreSession = getSessionFromFirestore(sessionId)

        if (firestoreSession != null) {
            return firestoreSession
        }

        return runCatching {
            val snap = rtdb.getReference(RTDB_SESSIONS).child(sessionId).get().await()
            snap.getValue(WalkieTalkieSession::class.java)
        }.getOrNull()
    }

    /**
     * Check whether a session is still joinable on the server.
     * Returns the session if it is still active/requesting/ringing, null otherwise.
     */
    suspend fun getSessionIfJoinable(sessionId: String): WalkieTalkieSession? {
        val session = getSession(sessionId) ?: return null
        return if (session.isJoinable()) session else null
    }

    suspend fun cancelSession(sessionId: String, endedBy: String = currentUserId) {
        updateSessionStatus(
            sessionId = sessionId,
            status = WalkieTalkieSession.STATUS_CANCELLED,
            endedBy = endedBy,
            endReason = WalkieTalkieSession.END_REASON_LOCAL_CANCEL,
            clearAnswer = false,
            clearFloor = true
        )
    }

    suspend fun rejectSession(sessionId: String, endedBy: String = currentUserId) {
        updateSessionStatus(
            sessionId = sessionId,
            status = WalkieTalkieSession.STATUS_REJECTED,
            endedBy = endedBy,
            endReason = WalkieTalkieSession.END_REASON_REJECTED,
            clearAnswer = false,
            clearFloor = true
        )
    }

    suspend fun timeoutSession(sessionId: String, endedBy: String = currentUserId) {
        updateSessionStatus(
            sessionId = sessionId,
            status = WalkieTalkieSession.STATUS_TIMEOUT,
            endedBy = endedBy,
            endReason = WalkieTalkieSession.END_REASON_TIMEOUT,
            clearAnswer = false,
            clearFloor = true
        )
    }

    /** End a session cleanly. */
    suspend fun endSession(
        sessionId: String,
        endedBy: String = currentUserId,
        endReason: String = WalkieTalkieSession.END_REASON_COMPLETED
    ) {
        updateSessionStatus(
            sessionId = sessionId,
            status = WalkieTalkieSession.STATUS_ENDED,
            endedBy = endedBy,
            endReason = endReason,
            clearAnswer = false,
            clearFloor = true
        )
    }

    private suspend fun updateSessionStatus(
        sessionId: String,
        status: String,
        endedBy: String?,
        endReason: String?,
        clearAnswer: Boolean,
        clearFloor: Boolean
    ) {
        val updates = mutableMapOf<String, Any?>(
            "status" to status,
            "statusUpdatedAt" to System.currentTimeMillis(),
            "endedBy" to endedBy,
            "endReason" to endReason,
            "endedAt" to if (
                status == WalkieTalkieSession.STATUS_ENDED ||
                status == WalkieTalkieSession.STATUS_CANCELLED ||
                status == WalkieTalkieSession.STATUS_REJECTED
            ) System.currentTimeMillis() else null
        )
        if (clearAnswer) {
            updates["answer"] = null
            updates["answerRevision"] = 0
            updates["answeredOfferRevision"] = 0
        }

        writeSessionUpdatesWithFallback(sessionId, updates, opName = "updateSessionStatus($status)")

        if (clearFloor) {
            rtdb.getReference(RTDB_SESSIONS).child(sessionId).child(RTDB_FLOOR)
                .removeValue()
                .addOnFailureListener { error ->
                    Log.w(TAG, "Direct WT RTDB floor clear failed for $sessionId", error)
                }
        }
    }

    /** Observe a session for changes (offer/answer/status). */
    fun observeSession(sessionId: String): Flow<WalkieTalkieSession?> = callbackFlow {
        val ref = rtdb.getReference(RTDB_SESSIONS).child(sessionId)
        var firestoreListener: ListenerRegistration? = null
        var realtimeSession: WalkieTalkieSession? = null
        var firestoreSession: WalkieTalkieSession? = null

        fun preferStatusSource(
            realtime: WalkieTalkieSession,
            firestore: WalkieTalkieSession
        ): WalkieTalkieSession =
            if (firestore.statusUpdatedAt >= realtime.statusUpdatedAt) firestore else realtime

        fun preferOfferSource(
            realtime: WalkieTalkieSession,
            firestore: WalkieTalkieSession
        ): WalkieTalkieSession = when {
            firestore.offerRevision > realtime.offerRevision -> firestore
            realtime.offerRevision > firestore.offerRevision -> realtime
            !firestore.offer.isNullOrBlank() && realtime.offer.isNullOrBlank() -> firestore
            else -> realtime
        }

        fun preferAnswerSource(
            realtime: WalkieTalkieSession,
            firestore: WalkieTalkieSession
        ): WalkieTalkieSession = when {
            firestore.answerRevision > realtime.answerRevision -> firestore
            realtime.answerRevision > firestore.answerRevision -> realtime
            !firestore.answer.isNullOrBlank() && realtime.answer.isNullOrBlank() -> firestore
            else -> realtime
        }

        fun mergeSessions(
            realtime: WalkieTalkieSession,
            firestore: WalkieTalkieSession
        ): WalkieTalkieSession {
            val statusSource = preferStatusSource(realtime, firestore)
            val offerSource = preferOfferSource(realtime, firestore)
            val answerSource = preferAnswerSource(realtime, firestore)
            return WalkieTalkieSession(
                sessionId = firestore.sessionId.ifBlank { realtime.sessionId },
                initiatorId = firestore.initiatorId.ifBlank { realtime.initiatorId },
                initiatorName = firestore.initiatorName.ifBlank { realtime.initiatorName },
                responderId = firestore.responderId.ifBlank { realtime.responderId },
                status = statusSource.status,
                statusUpdatedAt = maxOf(realtime.statusUpdatedAt, firestore.statusUpdatedAt),
                endedBy = statusSource.endedBy ?: answerSource.endedBy ?: offerSource.endedBy,
                endReason = statusSource.endReason ?: answerSource.endReason ?: offerSource.endReason,
                offer = offerSource.offer,
                answer = answerSource.answer,
                offerRevision = maxOf(realtime.offerRevision, firestore.offerRevision),
                answerRevision = maxOf(realtime.answerRevision, firestore.answerRevision),
                answeredOfferRevision = maxOf(realtime.answeredOfferRevision, firestore.answeredOfferRevision),
                iceRestart = answerSource.iceRestart || offerSource.iceRestart || statusSource.iceRestart,
                createdAt = listOf(realtime.createdAt, firestore.createdAt).filter { it > 0L }.minOrNull() ?: 0L,
                endedAt = maxOf(realtime.endedAt ?: 0L, firestore.endedAt ?: 0L).takeIf { it > 0L }
            )
        }

        fun emitMerged() {
            val merged = when {
                realtimeSession == null -> firestoreSession
                firestoreSession == null -> realtimeSession
                else -> mergeSessions(realtimeSession!!, firestoreSession!!)
            }
            trySend(merged)
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                realtimeSession = snapshot.getValue(WalkieTalkieSession::class.java)
                emitMerged()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Session observe cancelled: ${error.message}")
                realtimeSession = null
                emitMerged()
            }
        }

        firestoreListener = requestDoc(sessionId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Firestore WT session observe failed for $sessionId", error)
                return@addSnapshotListener
            }
            firestoreSession = sessionFromMap(sessionId, snapshot?.data)
            emitMerged()
        }

        ref.addValueEventListener(listener)
        awaitClose {
            ref.removeEventListener(listener)
            firestoreListener?.remove()
        }
    }

    // ── Incoming request listener — persistent singleton ────────────────────────
    //
    // Using a single, non-recreatable Firebase listener backed by a MutableSharedFlow
    // avoids the "listen() called twice for same QuerySpec" RTDB crash that occurs
    // when a callbackFlow is torn down and immediately recreated on the same query path.
    // The Firebase RunLoop queues add/remove asynchronously; if add arrives before the
    // previous remove is processed, PersistentConnectionImpl.listen() hard-asserts.
    //
    // Fix: decouple the Firebase listener lifecycle from coroutine collection.
    //  - attachIncomingRequestListener() / detachIncomingRequestListener() manage the
    //    Firebase handle (idempotent — second attach is a no-op).
    //  - incomingRequestEvents() returns a shared flow that the manager collects.
    // ─────────────────────────────────────────────────────────────────────────────

    private val _incomingRequestEvents =
        MutableSharedFlow<WalkieTalkieSession>(extraBufferCapacity = 16)

    private var incomingReqListener: ChildEventListener? = null
    private var incomingReqRef: com.google.firebase.database.Query? = null
    private var incomingReqFirestoreListener: ListenerRegistration? = null
    private val emittedIncomingSessionIds = mutableSetOf<String>()

    private fun emitIncomingRequestIfNeeded(session: WalkieTalkieSession) {
        if (session.status != WalkieTalkieSession.STATUS_REQUESTING) return
        if (!session.isJoinable()) return
        val sessionId = session.sessionId
        if (sessionId.isBlank()) return
        synchronized(emittedIncomingSessionIds) {
            if (!emittedIncomingSessionIds.add(sessionId)) return
        }
        _incomingRequestEvents.tryEmit(session)
    }

    /**
     * Attach the incoming-request listener. Idempotent — safe to call multiple times;
     * the Firebase listener is only ever registered once until [detachIncomingRequestListener].
     */
    fun attachIncomingRequestListener() {
        if (incomingReqListener != null) return  // already attached
        val uid = currentUserId
        if (uid.isEmpty()) return

        primeTransport("attachIncomingRequestListener")

        val ref = rtdb.getReference(RTDB_SESSIONS)
            .orderByChild("responderId")
            .equalTo(uid)

        ref.keepSynced(true)

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val session = snapshot.getValue(WalkieTalkieSession::class.java) ?: return
                emitIncomingRequestIfNeeded(session)
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
        incomingReqFirestoreListener = firestore.collection("walkieTalkieRequests")
            .whereEqualTo("responderId", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Incoming Firestore WT request listener failed", error)
                    return@addSnapshotListener
                }

                snapshot?.documentChanges?.forEach { change ->
                    if (change.type.name != "ADDED" && change.type.name != "MODIFIED") return@forEach
                    val session = sessionFromMap(change.document.id, change.document.data) ?: return@forEach
                    emitIncomingRequestIfNeeded(session)
                }
            }
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
        incomingReqFirestoreListener?.remove()
        incomingReqFirestoreListener = null
        synchronized(emittedIncomingSessionIds) {
            emittedIncomingSessionIds.clear()
        }
    }

    /** Events emitted by the persistent incoming-request listener. */
    fun incomingRequestEvents(): Flow<WalkieTalkieSession> = _incomingRequestEvents

    // ── ICE candidates ──────────────────────────────────────────────

    /** Add an ICE candidate. */
    suspend fun addIceCandidate(sessionId: String, candidate: WalkieTalkieIceCandidate) {
        val realtimeResult = withTimeoutOrNull(REALTIME_ICE_WRITE_TIMEOUT_MS) {
            runCatching {
                rtdb.getReference(RTDB_SESSIONS).child(sessionId)
                    .child(RTDB_CANDIDATES)
                    .push()
                    .setValue(candidate)
                    .await()
            }
        }

        if (realtimeResult?.isSuccess == true) {
            candidateCollection(sessionId)
                .add(candidate)
                .addOnFailureListener { error ->
                    Log.w(TAG, "WT addIceCandidate: Firestore mirror failed for session=$sessionId", error)
                }
            return
        }

        val realtimeError = realtimeResult?.exceptionOrNull()
            ?: RuntimeException("WT addIceCandidate RTDB write timed out after ${REALTIME_ICE_WRITE_TIMEOUT_MS}ms")
        val firestoreError = runCatching {
            candidateCollection(sessionId)
                .add(candidate)
                .await()
        }.exceptionOrNull()

        if (firestoreError != null) {
            throw RuntimeException(
                "WT addIceCandidate failed on both Firestore and RTDB for session=$sessionId",
                realtimeError
            )
        }

        Log.w(TAG, "WT addIceCandidate: RTDB write failed/timed out, Firestore fallback succeeded for session=$sessionId", realtimeError)
    }

    /** Observe remote ICE candidates. */
    fun observeIceCandidates(sessionId: String): Flow<WalkieTalkieIceCandidate> = callbackFlow {
        val ref = rtdb.getReference(RTDB_SESSIONS).child(sessionId).child(RTDB_CANDIDATES)
        var firestoreListener: ListenerRegistration? = null
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidate = snapshot.getValue(WalkieTalkieIceCandidate::class.java) ?: return
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

        firestoreListener = candidateCollection(sessionId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Firestore WT ICE observe failed for $sessionId", error)
                return@addSnapshotListener
            }

            snapshot?.documentChanges?.forEach { change ->
                if (change.type.name != "ADDED") return@forEach
                val candidate = change.document.toObject(WalkieTalkieIceCandidate::class.java)
                if (candidate.fromUserId != currentUserId) {
                    trySend(candidate)
                }
            }
        }

        ref.addChildEventListener(listener)
        awaitClose {
            ref.removeEventListener(listener)
            firestoreListener?.remove()
        }
    }

    // ── Floor control ───────────────────────────────────────────────

    /**
     * Atomically claim the floor using a Firebase transaction.
     *
     * @return true if the floor was successfully claimed, false if someone else holds it.
     */
    suspend fun claimFloor(sessionId: String): Boolean {
        val ref = rtdb.getReference(RTDB_SESSIONS).child(sessionId).child(RTDB_FLOOR)
        val userId = currentUserId
        val now = System.currentTimeMillis()

        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                ref.runTransaction(object : Transaction.Handler {
                    override fun doTransaction(currentData: MutableData): Transaction.Result {
                        val existing = currentData.getValue(WalkieTalkieFloor::class.java)
                        if (existing != null && existing.isHeld() && existing.holderId != userId) {
                            // Floor is held by someone else — abort
                            return Transaction.abort()
                        }
                        // Floor is free or expired or already ours — claim it
                        currentData.value = mapOf(
                            "holderId" to userId,
                            "heldSince" to now,
                            "updatedAt" to now
                        )
                        return Transaction.success(currentData)
                    }

                    override fun onComplete(
                        error: DatabaseError?,
                        committed: Boolean,
                        snapshot: DataSnapshot?
                    ) {
                        if (cont.isActive) {
                            cont.resume(committed, null)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "claimFloor failed", e)
            false
        }
    }

    /** Release the floor (only if we hold it). */
    suspend fun releaseFloor(sessionId: String) {
        val ref = rtdb.getReference(RTDB_SESSIONS).child(sessionId).child(RTDB_FLOOR)
        val userId = currentUserId

        try {
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                ref.runTransaction(object : Transaction.Handler {
                    override fun doTransaction(currentData: MutableData): Transaction.Result {
                        val existing = currentData.getValue(WalkieTalkieFloor::class.java)
                        if (existing?.holderId == userId) {
                            currentData.value = mapOf(
                                "holderId" to null,
                                "heldSince" to 0L,
                                "updatedAt" to System.currentTimeMillis()
                            )
                        }
                        return Transaction.success(currentData)
                    }

                    override fun onComplete(
                        error: DatabaseError?,
                        committed: Boolean,
                        snapshot: DataSnapshot?
                    ) {
                        if (cont.isActive) {
                            cont.resume(Unit, null)
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "releaseFloor failed", e)
        }
    }

    /** Observe floor state in real-time. */
    fun observeFloor(sessionId: String): Flow<WalkieTalkieFloor> = callbackFlow {
        val ref = rtdb.getReference(RTDB_SESSIONS).child(sessionId).child(RTDB_FLOOR)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val floor = snapshot.getValue(WalkieTalkieFloor::class.java)
                    ?: WalkieTalkieFloor()
                trySend(floor)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Floor observe cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun emitPttEffect(sessionId: String, phase: String) {
        val now = System.currentTimeMillis()
        requestDoc(sessionId)
            .set(
                mapOf(
                    "pttEffectSenderId" to currentUserId,
                    "pttEffectPhase" to phase,
                    "pttEffectSequence" to now,
                    "pttEffectAt" to now
                ),
                SetOptions.merge()
            )
            .await()
    }

    fun observePttEffects(sessionId: String): Flow<WalkieTalkiePttEffectEvent> = callbackFlow {
        val listener = requestDoc(sessionId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "PTT effect observe cancelled for $sessionId", error)
                return@addSnapshotListener
            }

            val data = snapshot?.data ?: return@addSnapshotListener
            val senderId = data["pttEffectSenderId"] as? String ?: return@addSnapshotListener
            val phase = data["pttEffectPhase"] as? String ?: return@addSnapshotListener
            val sequence = (data["pttEffectSequence"] as? Number)?.toLong() ?: return@addSnapshotListener
            val emittedAt = (data["pttEffectAt"] as? Number)?.toLong() ?: sequence

            trySend(
                WalkieTalkiePttEffectEvent(
                    senderId = senderId,
                    phase = phase,
                    sequence = sequence,
                    emittedAt = emittedAt
                )
            )
        }

        awaitClose { listener.remove() }
    }

    /** Clean up stale sessions for current user. */
    suspend fun cleanupStaleSessions() {
        try {
            val initiatorSnap = rtdb.getReference(RTDB_SESSIONS)
                .orderByChild("initiatorId")
                .equalTo(currentUserId)
                .get().await()
            for (child in initiatorSnap.children) {
                val session = child.getValue(WalkieTalkieSession::class.java) ?: continue
                if (session.status !in WalkieTalkieSession.TERMINAL_STATUSES) {
                    endSession(session.sessionId)
                }
            }
            val responderSnap = rtdb.getReference(RTDB_SESSIONS)
                .orderByChild("responderId")
                .equalTo(currentUserId)
                .get().await()
            for (child in responderSnap.children) {
                val session = child.getValue(WalkieTalkieSession::class.java) ?: continue
                if (session.status !in WalkieTalkieSession.TERMINAL_STATUSES) {
                    endSession(session.sessionId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stale session cleanup failed", e)
        }
    }

    /**
     * Light-weight reconnect cleanup: find sessions where we are the responder that
     * are no longer joinable and return their initiator names so the caller can
     * show a "missed" indicator.
     */
    suspend fun cleanupStaleIncomingSessions(): List<Pair<String, String>> {
        val missed = mutableListOf<Pair<String, String>>() // sessionId → initiatorName
        val nowMs = System.currentTimeMillis()
        // Show missed notifications for sessions that were cancelled/ended while we were
        // offline, as long as they occurred within the last 10 minutes (avoids showing
        // missed notifications for very old calls that the user clearly knows about).
        val missedWindowMs = 10 * 60_000L
        try {
            val snap = rtdb.getReference(RTDB_SESSIONS)
                .orderByChild("responderId")
                .equalTo(currentUserId)
                .get().await()
            for (child in snap.children) {
                val session = child.getValue(WalkieTalkieSession::class.java) ?: continue
                when {
                    session.status == WalkieTalkieSession.STATUS_REJECTED -> {
                        // We rejected it (or it was rejected on our behalf) — nothing to show
                    }
                    session.status == WalkieTalkieSession.STATUS_CANCELLED ||
                    session.status == WalkieTalkieSession.STATUS_TIMEOUT -> {
                        // Caller cancelled or timed out before we answered — show missed.
                        val refTime = maxOf(
                            session.statusUpdatedAt.takeIf { it > 0L } ?: 0L,
                            session.createdAt.takeIf { it > 0L } ?: 0L
                        )
                        if (refTime > 0L && nowMs - refTime < missedWindowMs) {
                            missed += session.sessionId to session.initiatorName
                        }
                    }
                    session.status == WalkieTalkieSession.STATUS_ENDED -> {
                        // Call ended normally (both sides connected).  Do NOT show a missed
                        // notification — the user participated in the call.  Previously
                        // STATUS_ENDED was treated the same as CANCELLED/TIMEOUT, which caused
                        // a false "missed call" notification every time RTDB reconnected after
                        // a successfully completed call (RTDB drops its WebSocket after ~60 s
                        // of idle, so this triggered on virtually every subsequent call).
                    }
                    !session.isJoinable() -> {
                        // Expired but not yet marked terminal → end it and report missed,
                        // but only within the same 10-minute window used for CANCELLED/TIMEOUT
                        // to avoid surfacing stale sessions from previous testing sessions.
                        endSession(session.sessionId, endReason = WalkieTalkieSession.END_REASON_TIMEOUT)
                        val refTime = session.createdAt.takeIf { it > 0L } ?: 0L
                        if (refTime > 0L && nowMs - refTime < missedWindowMs) {
                            missed += session.sessionId to session.initiatorName
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stale incoming session cleanup failed", e)
        }
        return missed
    }
}
