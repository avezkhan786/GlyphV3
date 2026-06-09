package com.glyph.glyph_v3.data.repo

import android.util.Log
import com.glyph.glyph_v3.data.models.CallData
import com.glyph.glyph_v3.data.models.IceCandidateData
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for WebRTC call signaling via Firestore.
 *
 * Firestore structure:
 * ```
 * calls/{callId}
 *     callerId, receiverId, type, status, offer, answer, createdAt, ...
 *     candidates/{docId}
 *         sdpMid, sdpMLineIndex, candidate, fromUserId, timestamp
 * ```
 */
object CallSignalingRepository {

    private const val TAG = "CallSignaling"
    private const val TERMINAL_CALL_DELETE_DELAY_MS = 90_000L
    private val db get() = FirebaseFirestore.getInstance()
    private val currentUserId get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val terminalCallDeletionJobs = ConcurrentHashMap<String, Job>()

    fun primeTransport(reason: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(false)
            .addOnSuccessListener {
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Call signaling auth prime failed for $reason", error)
            }
    }

    private suspend fun ensureAuthReady(reason: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val result = withTimeoutOrNull(5_000L) {
            user.getIdToken(false).await()
        }
        if (result == null) {
            Log.w(TAG, "Timed out priming call signaling auth for $reason")
        }
    }

    // ── Create / Update ──────────────────────────────────────────────

    suspend fun createCall(callData: CallData) {
        ensureAuthReady("createCall")
        db.collection("calls").document(callData.callId)
            .set(callData)
            .await()
    }

    suspend fun updateCallStatus(callId: String, status: String) {
        ensureAuthReady("updateCallStatus")
        val updates = mutableMapOf<String, Any>("status" to status)
        if (status == "connected" || status == "accepted") {
            updates["answeredAt"] = System.currentTimeMillis()
        }
        if (status in listOf("ended", "declined", "missed", "no_answer", "busy")) {
            updates["endedAt"] = System.currentTimeMillis()
        }
        db.collection("calls").document(callId)
            .update(updates)
            .await()

        if (status in TERMINAL_STATUSES) {
            scheduleTerminalCallDeletion(callId)
        }
    }

    suspend fun updateCallFields(callId: String, updates: Map<String, Any>) {
        ensureAuthReady("updateCallFields")
        if (updates.isEmpty()) return
        db.collection("calls").document(callId)
            .update(updates)
            .await()
    }

    suspend fun setOffer(callId: String, sdp: String, offerRevision: Int, iceRestart: Boolean = false) {
        ensureAuthReady("setOffer")
        db.collection("calls").document(callId)
            .update(
                mapOf(
                    "offer" to sdp,
                    "offerRevision" to offerRevision,
                    "iceRestart" to iceRestart,
                    "negotiationUpdatedAt" to System.currentTimeMillis()
                )
            )
            .await()
    }

    /**
     * Creates the call document AND sets the offer SDP in a single Firestore write.
     * Saves ~300ms vs calling createCall() + setOffer() separately.
     */
    suspend fun createCallWithOffer(callData: CallData, offerSdp: String) {
        ensureAuthReady("createCallWithOffer")
        db.collection("calls").document(callData.callId)
            .set(
                callData.copy(
                    offer = offerSdp,
                    offerRevision = 1,
                    answerRevision = 0,
                    iceRestart = false,
                    negotiationUpdatedAt = System.currentTimeMillis()
                )
            )
            .await()
    }

    suspend fun setAnswer(
        callId: String,
        sdp: String,
        answerRevision: Int,
        markAccepted: Boolean,
        iceRestart: Boolean = false,
        additionalUpdates: Map<String, Any> = emptyMap()
    ) {
        ensureAuthReady("setAnswer")
        val updates = mutableMapOf<String, Any>(
            "answer" to sdp,
            "answerRevision" to answerRevision,
            "iceRestart" to iceRestart,
            "negotiationUpdatedAt" to System.currentTimeMillis()
        )
        if (markAccepted) {
            updates["status"] = "accepted"
            updates["answeredAt"] = System.currentTimeMillis()
        }
        updates.putAll(additionalUpdates)
        db.collection("calls").document(callId)
            .update(updates)
            .await()
    }

    suspend fun addIceCandidate(callId: String, candidateData: IceCandidateData) {
        ensureAuthReady("addIceCandidate")
        db.collection("calls").document(callId)
            .collection("candidates")
            .add(candidateData)
            .await()
    }

    // ── Observe ──────────────────────────────────────────────────────

    fun observeCall(callId: String): Flow<CallData?> = callbackFlow {
        primeTransport("observeCall")
        val listener = db.collection("calls").document(callId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                        // Transient offline error during initial listener attach.
                        // Firestore will automatically re-deliver the snapshot once the
                        // connection is restored — keeping the flow open is correct here.
                        Log.w(TAG, "observeCall: offline while attaching listener for $callId — will retry on reconnect")
                        trySend(null)
                        return@addSnapshotListener
                    }
                    // For non-recoverable errors (PERMISSION_DENIED, etc.) close the flow so
                    // collectors are properly notified and can terminate the call.
                    Log.e(TAG, "Error observing call $callId", error)
                    close(error)
                    return@addSnapshotListener
                }
                val data = snapshot?.toObject(CallData::class.java)
                trySend(data)
            }
        awaitClose { listener.remove() }
    }

    /**
     * Observe incoming calls for the current user that are in "ringing" state.
     */
    fun observeIncomingCalls(): Flow<CallData?> = callbackFlow {
        primeTransport("observeIncomingCalls")
        val uid = currentUserId
        if (uid.isEmpty()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val listener = db.collection("calls")
            .whereEqualTo("receiverId", uid)
            .whereEqualTo("status", "ringing")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing incoming calls", error)
                    trySend(null)
                    return@addSnapshotListener
                }
                val call = snapshots?.documents
                    ?.mapNotNull { it.toObject(CallData::class.java) }
                    ?.maxByOrNull { it.createdAt }
                trySend(call)
            }
        awaitClose { listener.remove() }
    }

    fun observeIceCandidates(callId: String, forUserId: String): Flow<List<IceCandidateData>> = callbackFlow {
        primeTransport("observeIceCandidates")
        val listener = db.collection("calls").document(callId)
            .collection("candidates")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing ICE candidates", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val candidates = snapshots?.documents
                    ?.mapNotNull { it.toObject(IceCandidateData::class.java) }
                    ?.filter { it.fromUserId != forUserId }  // only remote candidates
                    ?: emptyList()
                trySend(candidates)
            }
        awaitClose { listener.remove() }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    suspend fun deleteCallCandidates(callId: String) {
        try {
            val docs = db.collection("calls").document(callId)
                .collection("candidates")
                .get().await()
            for (doc in docs) {
                doc.reference.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning candidates for $callId", e)
        }
    }

    suspend fun getCallData(callId: String): CallData? {
        return try {
            db.collection("calls").document(callId)
                .get().await()
                .toObject(CallData::class.java)
        } catch (e: Exception) {
            val firestoreError = e as? FirebaseFirestoreException
            if (firestoreError?.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                // Device is offline — retry from local cache.
                Log.w(TAG, "getCallData: offline, falling back to cache for $callId")
                try {
                    db.collection("calls").document(callId)
                        .get(Source.CACHE).await()
                        .toObject(CallData::class.java)
                } catch (cacheEx: Exception) {
                    Log.w(TAG, "getCallData: cache miss for $callId", cacheEx)
                    null
                }
            } else {
                Log.e(TAG, "Error getting call $callId", e)
                null
            }
        }
    }

    private fun scheduleTerminalCallDeletion(callId: String) {
        terminalCallDeletionJobs.remove(callId)?.cancel()
        terminalCallDeletionJobs[callId] = cleanupScope.launch {
            try {
                delay(TERMINAL_CALL_DELETE_DELAY_MS)
                deleteCallCandidates(callId)
                db.collection("calls").document(callId).delete().await()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to delete terminal call doc $callId", error)
            } finally {
                terminalCallDeletionJobs.remove(callId)
            }
        }
    }

    suspend fun isCallStillRinging(callId: String): Boolean {
        return try {
            db.collection("calls").document(callId)
                .get().await()
                .toObject(CallData::class.java)
                ?.callState() == com.glyph.glyph_v3.data.models.CallState.RINGING
        } catch (e: Exception) {
            val firestoreError = e as? FirebaseFirestoreException
            if (firestoreError?.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                Log.w(TAG, "isCallStillRinging: offline, falling back to cache for $callId")
                try {
                    db.collection("calls").document(callId)
                        .get(Source.CACHE).await()
                        .toObject(CallData::class.java)
                        ?.callState() == com.glyph.glyph_v3.data.models.CallState.RINGING
                } catch (cacheEx: Exception) {
                    // No cached state — assume still ringing so the incoming UI is shown
                    // rather than silently dropped. The observer will correct it shortly.
                    Log.w(TAG, "isCallStillRinging: cache miss for $callId, assuming ringing", cacheEx)
                    true
                }
            } else {
                Log.e(TAG, "Error checking ringing status for $callId", e)
                false
            }
        }
    }

    private val TERMINAL_STATUSES = setOf("ended", "declined", "missed", "no_answer", "busy")
}
