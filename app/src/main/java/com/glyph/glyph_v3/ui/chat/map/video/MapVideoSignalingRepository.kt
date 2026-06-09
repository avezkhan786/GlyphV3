package com.glyph.glyph_v3.ui.chat.map.video

import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "MapVideoSignalRepo"
private const val ROOT_PATH = "mapVideoSessions"

data class MapVideoSdpMessage(
    val sessionId: String,
    val fromUserId: String,
    val targetUserId: String,
    val type: String,
    val sdp: String
)

data class MapVideoIceCandidateMessage(
    val key: String,
    val sessionId: String,
    val fromUserId: String,
    val targetUserId: String,
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val sdp: String
)

data class MapVideoParticipantState(
    val enabled: Boolean = false,
    val publishing: Boolean = false,
    val readyToReceive: Boolean = false
)

data class MapVideoCameraInviteCommand(
    val requestId: String = "",
    val senderName: String = "",
    val createdAt: Long = 0L
)

data class MapVideoCameraInviteResponseCommand(
    val requestId: String = "",
    val response: String = "",
    val responderUserId: String = "",
    val responderName: String = "",
    val createdAt: Long = 0L
)

/**
 * Sent by the initiator when live-location + camera share is started.
 * Signals the target to auto-open the video overlay without requiring acceptance.
 * Cleared when the share stops.
 */
data class MapVideoCameraBroadcastCommand(
    val createdAt: Long = 0L
)

object MapVideoSignalingRepository {

    private val rootRef = FirebaseDatabase.getInstance().getReference(ROOT_PATH)

    fun setParticipantState(chatId: String, userId: String, state: MapVideoParticipantState) {
        val ref = rootRef.child(chatId).child("participants").child(userId)
        if (state.enabled) {
            ref.setValue(
                mapOf(
                    "enabled" to true,
                    "publishing" to state.publishing,
                    "readyToReceive" to state.readyToReceive,
                    "updatedAt" to ServerValue.TIMESTAMP
                )
            ).addOnFailureListener { e ->
                Log.e(TAG, "Failed to set participant state for $chatId/$userId", e)
            }
        } else {
            ref.removeValue().addOnFailureListener { e ->
                Log.e(TAG, "Failed to clear participant state for $chatId/$userId", e)
            }
        }
    }

    fun observeParticipantState(chatId: String, userId: String): Flow<MapVideoParticipantState> = callbackFlow {
        val ref = rootRef.child(chatId).child("participants").child(userId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(MapVideoParticipantState())
                    return
                }
                trySend(
                    MapVideoParticipantState(
                        enabled = snapshot.child("enabled").getValue(Boolean::class.java) == true,
                        publishing = snapshot.child("publishing").getValue(Boolean::class.java) == true,
                        readyToReceive = snapshot.child("readyToReceive").getValue(Boolean::class.java) == true
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeParticipantState cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun clearSession(chatId: String) {
        rootRef.child(chatId).child("signal").removeValue()
        rootRef.child(chatId).child("ice").removeValue()
    }

    fun clearIncomingCandidates(chatId: String, userId: String) {
        rootRef.child(chatId).child("ice").child(userId).removeValue()
    }

    fun writeOffer(chatId: String, message: MapVideoSdpMessage) {
        writeSdp(chatId, "offer", message)
    }

    fun writeAnswer(chatId: String, message: MapVideoSdpMessage) {
        writeSdp(chatId, "answer", message)
    }

    private fun writeSdp(chatId: String, key: String, message: MapVideoSdpMessage) {
        rootRef.child(chatId).child("signal").child(key)
            .setValue(
                mapOf(
                    "sessionId" to message.sessionId,
                    "fromUserId" to message.fromUserId,
                    "targetUserId" to message.targetUserId,
                    "type" to message.type,
                    "sdp" to message.sdp,
                    "updatedAt" to ServerValue.TIMESTAMP
                )
            )
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to write $key for chatId=$chatId", e)
            }
    }

    fun observeOffer(chatId: String): Flow<MapVideoSdpMessage?> = observeSdp(chatId, "offer")

    fun observeAnswer(chatId: String): Flow<MapVideoSdpMessage?> = observeSdp(chatId, "answer")

    private fun observeSdp(chatId: String, key: String): Flow<MapVideoSdpMessage?> = callbackFlow {
        val ref = rootRef.child(chatId).child("signal").child(key)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                trySend(
                    MapVideoSdpMessage(
                        sessionId = snapshot.child("sessionId").getValue(String::class.java).orEmpty(),
                        fromUserId = snapshot.child("fromUserId").getValue(String::class.java).orEmpty(),
                        targetUserId = snapshot.child("targetUserId").getValue(String::class.java).orEmpty(),
                        type = snapshot.child("type").getValue(String::class.java).orEmpty(),
                        sdp = snapshot.child("sdp").getValue(String::class.java).orEmpty()
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeSdp($key) cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun sendIceCandidate(chatId: String, targetUserId: String, message: MapVideoIceCandidateMessage) {
        rootRef.child(chatId).child("ice").child(targetUserId).push()
            .setValue(
                mapOf(
                    "sessionId" to message.sessionId,
                    "fromUserId" to message.fromUserId,
                    "targetUserId" to message.targetUserId,
                    "sdpMid" to message.sdpMid,
                    "sdpMLineIndex" to message.sdpMLineIndex,
                    "sdp" to message.sdp,
                    "updatedAt" to ServerValue.TIMESTAMP
                )
            )
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send ICE candidate for chatId=$chatId to $targetUserId", e)
            }
    }

    fun observeIceCandidates(chatId: String, userId: String): Flow<MapVideoIceCandidateMessage> = callbackFlow {
        val ref = rootRef.child(chatId).child("ice").child(userId)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                trySend(
                    MapVideoIceCandidateMessage(
                        key = snapshot.key.orEmpty(),
                        sessionId = snapshot.child("sessionId").getValue(String::class.java).orEmpty(),
                        fromUserId = snapshot.child("fromUserId").getValue(String::class.java).orEmpty(),
                        targetUserId = snapshot.child("targetUserId").getValue(String::class.java).orEmpty(),
                        sdpMid = snapshot.child("sdpMid").getValue(String::class.java).orEmpty(),
                        sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: 0,
                        sdp = snapshot.child("sdp").getValue(String::class.java).orEmpty()
                    )
                )
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeIceCandidates cancelled: ${error.message}")
            }
        }
        ref.addChildEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun sendCameraSwitchCommand(chatId: String, targetUserId: String) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("cameraSwitch")
            .setValue(ServerValue.TIMESTAMP)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send camera switch command to $targetUserId", e)
            }
    }

    fun observeCameraSwitchCommand(chatId: String, userId: String): Flow<Long> = callbackFlow {
        val ref = rootRef.child(chatId).child("commands").child(userId).child("cameraSwitch")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.getValue(Long::class.java)?.let { timestamp ->
                    trySend(timestamp)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeCameraSwitchCommand cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun sendCameraInviteCommand(
        chatId: String,
        targetUserId: String,
        requestId: String,
        senderUserId: String,
        senderName: String
    ) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("cameraInvite")
            .setValue(
                mapOf(
                    "requestId" to requestId,
                    "senderUserId" to senderUserId,
                    "senderName" to senderName,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send camera invite command to $targetUserId", e)
            }
    }

    fun observeCameraInviteCommand(chatId: String, userId: String): Flow<MapVideoCameraInviteCommand?> = callbackFlow {
        val ref = rootRef.child(chatId).child("commands").child(userId).child("cameraInvite")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                trySend(
                    MapVideoCameraInviteCommand(
                        requestId = snapshot.child("requestId").getValue(String::class.java).orEmpty(),
                        senderName = snapshot.child("senderName").getValue(String::class.java).orEmpty(),
                        createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeCameraInviteCommand cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun sendCameraInviteDeclinedCommand(
        chatId: String,
        targetUserId: String,
        requestId: String,
        responderUserId: String,
        responderName: String
    ) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("cameraInviteResponse")
            .setValue(
                mapOf(
                    "requestId" to requestId,
                    "response" to "declined",
                    "responderUserId" to responderUserId,
                    "responderName" to responderName,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send camera invite decline command to $targetUserId", e)
            }
    }

    fun sendCameraInviteAcceptedCommand(
        chatId: String,
        targetUserId: String,
        requestId: String,
        responderUserId: String,
        responderName: String
    ) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("cameraInviteResponse")
            .setValue(
                mapOf(
                    "requestId" to requestId,
                    "response" to "accepted",
                    "responderUserId" to responderUserId,
                    "responderName" to responderName,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send camera invite accepted command to $targetUserId", e)
            }
    }

    fun observeCameraInviteResponseCommand(chatId: String, userId: String): Flow<MapVideoCameraInviteResponseCommand?> = callbackFlow {
        val ref = rootRef.child(chatId).child("commands").child(userId).child("cameraInviteResponse")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                trySend(
                    MapVideoCameraInviteResponseCommand(
                        requestId = snapshot.child("requestId").getValue(String::class.java).orEmpty(),
                        response = snapshot.child("response").getValue(String::class.java).orEmpty(),
                        responderUserId = snapshot.child("responderUserId").getValue(String::class.java).orEmpty(),
                        responderName = snapshot.child("responderName").getValue(String::class.java).orEmpty(),
                        createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeCameraInviteResponseCommand cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /**
     * Writes a missedCameraInvite signal to RTDB. The Cloud Function
     * [notifyMissedCameraInvite] listens on this path and sends an FCM notification
     * to [targetUserId] with long default TTL, so it arrives even after a long offline period.
     */
    fun writeMissedCameraInvite(
        chatId: String,
        targetUserId: String,
        requestId: String,
        senderUserId: String,
        senderName: String
    ) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("missedCameraInvite")
            .setValue(
                mapOf(
                    "requestId" to requestId,
                    "senderUserId" to senderUserId,
                    "senderName" to senderName,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to write missedCameraInvite for $targetUserId", e)
            }
    }

    /** Removes the cameraInvite node from RTDB after it has been accepted, declined, or cancelled. */
    fun clearCameraInviteCommand(chatId: String, targetUserId: String) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("cameraInvite")
            .removeValue()
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to clear cameraInvite for $targetUserId", e)
            }
    }

    /** Removes the cameraInviteResponse node from RTDB after the inviter has read it. */
    fun clearCameraInviteResponseCommand(chatId: String, userId: String) {
        rootRef.child(chatId).child("commands").child(userId).child("cameraInviteResponse")
            .removeValue()
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to clear cameraInviteResponse for $userId", e)
            }
    }

    // ── Camera broadcast (live location + camera share, no acceptance required) ──────────────────

    /**
     * Written by the sender when they start live-location + camera share.
     * The target user's [MapVideoSessionManager] observes this node and automatically
     * enters [MapVideoMode.AUTO_RECEIVE_ONLY] to show the incoming feed without a prompt.
     */
    fun writeCameraBroadcastCommand(chatId: String, targetUserId: String) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("cameraBroadcast")
            .setValue(
                mapOf("createdAt" to ServerValue.TIMESTAMP)
            )
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to write cameraBroadcast for $targetUserId", e)
            }
    }

    /** Removed by the sender when live-location + camera share stops. */
    fun clearCameraBroadcastCommand(chatId: String, targetUserId: String) {
        rootRef.child(chatId).child("commands").child(targetUserId).child("cameraBroadcast")
            .removeValue()
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to clear cameraBroadcast for $targetUserId", e)
            }
    }

    /**
     * Observes the cameraBroadcast node for the given user.
     * Emits non-null when a live broadcast is active, null when cleared.
     */
    fun observeCameraBroadcastCommand(chatId: String, userId: String): Flow<MapVideoCameraBroadcastCommand?> = callbackFlow {
        val ref = rootRef.child(chatId).child("commands").child(userId).child("cameraBroadcast")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                trySend(
                    MapVideoCameraBroadcastCommand(
                        createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                    )
                )
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeCameraBroadcastCommand cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }
}
