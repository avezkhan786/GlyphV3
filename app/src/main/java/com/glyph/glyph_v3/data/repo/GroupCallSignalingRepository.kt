package com.glyph.glyph_v3.data.repo

import android.util.Log
import com.glyph.glyph_v3.data.models.GroupCallData
import com.glyph.glyph_v3.data.models.GroupCallIceCandidate
import com.glyph.glyph_v3.data.models.GroupCallParticipant
import com.glyph.glyph_v3.data.models.GroupCallSignaling
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Repository for group call signaling via Firestore.
 *
 * Firestore structure:
 * ```
 * groupCalls/{groupCallId}
 *     creatorId, type, status, createdAt, ...
 *     participants/{userId}
 *         userId, userName, userAvatar, status, videoEnabled, audioEnabled, ...
 *     signaling/{pairKey}
 *         offer, answer, offerFromUserId, offerRevision, answerRevision
 *         candidates/{docId}
 *             sdpMid, sdpMLineIndex, candidate, fromUserId, timestamp
 * ```
 */
object GroupCallSignalingRepository {

    private const val TAG = "GroupCallSignaling"
    private val db get() = FirebaseFirestore.getInstance()

    private suspend fun ensureAuthReady(reason: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        withTimeoutOrNull(5_000L) {
            user.getIdToken(false).await()
        }
    }

    /**
     * Deterministic pair key for two users: sorted UIDs joined by "_".
     * Ensures both sides use the same signaling document.
     */
    fun pairKey(uid1: String, uid2: String): String {
        return if (uid1 < uid2) "${uid1}_${uid2}" else "${uid2}_${uid1}"
    }

    /**
     * Determines which user is the offerer in a pair (the one with the smaller UID).
     */
    fun isOfferer(localUid: String, remoteUid: String): Boolean {
        return localUid < remoteUid
    }

    // ── Group Call CRUD ──────────────────────────────────────────────

    suspend fun createGroupCall(data: GroupCallData) {
        ensureAuthReady("createGroupCall")
        db.collection("groupCalls").document(data.groupCallId)
            .set(data)
            .await()
    }

    suspend fun endGroupCall(groupCallId: String) {
        ensureAuthReady("endGroupCall")
        db.collection("groupCalls").document(groupCallId)
            .update(
                mapOf(
                    "status" to "ended",
                    "endedAt" to System.currentTimeMillis()
                )
            )
            .await()
    }

    suspend fun getGroupCallData(groupCallId: String): GroupCallData? {
        return try {
            db.collection("groupCalls").document(groupCallId)
                .get().await()
                .toObject(GroupCallData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting group call $groupCallId", e)
            null
        }
    }

    // ── Participants ─────────────────────────────────────────────────

    suspend fun addParticipant(groupCallId: String, participant: GroupCallParticipant) {
        ensureAuthReady("addParticipant")
        db.collection("groupCalls").document(groupCallId)
            .collection("participants")
            .document(participant.userId)
            .set(participant)
            .await()
    }

    suspend fun updateParticipantStatus(groupCallId: String, userId: String, status: String) {
        ensureAuthReady("updateParticipantStatus")
        val updates = mutableMapOf<String, Any>("status" to status)
        if (status == "connected") {
            updates["joinedAt"] = System.currentTimeMillis()
        }
        if (status in listOf("left", "declined", "no_answer")) {
            updates["leftAt"] = System.currentTimeMillis()
        }
        db.collection("groupCalls").document(groupCallId)
            .collection("participants")
            .document(userId)
            .update(updates)
            .await()
    }

    suspend fun updateParticipantMedia(
        groupCallId: String,
        userId: String,
        videoEnabled: Boolean,
        audioEnabled: Boolean
    ) {
        ensureAuthReady("updateParticipantMedia")
        db.collection("groupCalls").document(groupCallId)
            .collection("participants")
            .document(userId)
            .update(
                mapOf(
                    "videoEnabled" to videoEnabled,
                    "audioEnabled" to audioEnabled
                )
            )
            .await()
    }

    suspend fun getParticipants(groupCallId: String): List<GroupCallParticipant> {
        return try {
            db.collection("groupCalls").document(groupCallId)
                .collection("participants")
                .get().await()
                .toObjects(GroupCallParticipant::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting participants for $groupCallId", e)
            emptyList()
        }
    }

    fun observeParticipants(groupCallId: String): Flow<List<GroupCallParticipant>> = callbackFlow {
        val listener = db.collection("groupCalls").document(groupCallId)
            .collection("participants")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing participants for $groupCallId", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val participants = snapshots?.toObjects(GroupCallParticipant::class.java) ?: emptyList()
                trySend(participants)
            }
        awaitClose { listener.remove() }
    }

    fun observeGroupCall(groupCallId: String): Flow<GroupCallData?> = callbackFlow {
        val listener = db.collection("groupCalls").document(groupCallId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing group call $groupCallId", error)
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(GroupCallData::class.java))
            }
        awaitClose { listener.remove() }
    }

    // ── Signaling ────────────────────────────────────────────────────

    suspend fun setOffer(
        groupCallId: String,
        pairKey: String,
        offerSdp: String,
        fromUserId: String,
        offerRevision: Int
    ) {
        ensureAuthReady("setOffer")
        db.collection("groupCalls").document(groupCallId)
            .collection("signaling")
            .document(pairKey)
            .set(
                GroupCallSignaling(
                    offer = offerSdp,
                    offerFromUserId = fromUserId,
                    offerRevision = offerRevision,
                    answerRevision = 0,
                    updatedAt = System.currentTimeMillis()
                )
            )
            .await()
    }

    suspend fun setAnswer(
        groupCallId: String,
        pairKey: String,
        answerSdp: String,
        answerRevision: Int
    ) {
        ensureAuthReady("setAnswer")
        db.collection("groupCalls").document(groupCallId)
            .collection("signaling")
            .document(pairKey)
            .update(
                mapOf(
                    "answer" to answerSdp,
                    "answerRevision" to answerRevision,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
            .await()
    }

    fun observeSignaling(groupCallId: String, pairKey: String): Flow<GroupCallSignaling?> = callbackFlow {
        val listener = db.collection("groupCalls").document(groupCallId)
            .collection("signaling")
            .document(pairKey)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing signaling $pairKey in $groupCallId", error)
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(GroupCallSignaling::class.java))
            }
        awaitClose { listener.remove() }
    }

    suspend fun addIceCandidate(
        groupCallId: String,
        pairKey: String,
        candidateData: GroupCallIceCandidate
    ) {
        ensureAuthReady("addIceCandidate")
        db.collection("groupCalls").document(groupCallId)
            .collection("signaling")
            .document(pairKey)
            .collection("candidates")
            .add(candidateData)
            .await()
    }

    fun observeIceCandidates(
        groupCallId: String,
        pairKey: String,
        forUserId: String
    ): Flow<List<GroupCallIceCandidate>> = callbackFlow {
        val listener = db.collection("groupCalls").document(groupCallId)
            .collection("signaling")
            .document(pairKey)
            .collection("candidates")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error observing ICE candidates $pairKey in $groupCallId", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val candidates = snapshots?.toObjects(GroupCallIceCandidate::class.java)
                    ?.filter { it.fromUserId != forUserId }
                    ?: emptyList()
                trySend(candidates)
            }
        awaitClose { listener.remove() }
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    suspend fun cleanupSignaling(groupCallId: String, pairKey: String) {
        try {
            val docs = db.collection("groupCalls").document(groupCallId)
                .collection("signaling")
                .document(pairKey)
                .collection("candidates")
                .get().await()
            for (doc in docs) {
                doc.reference.delete()
            }
            db.collection("groupCalls").document(groupCallId)
                .collection("signaling")
                .document(pairKey)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning signaling for $pairKey in $groupCallId", e)
        }
    }
}
