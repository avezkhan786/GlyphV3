package com.glyph.glyph_v3.data.models

import com.google.firebase.Timestamp

/**
 * Call type: voice or video.
 */
enum class CallType {
    VOICE, VIDEO
}

enum class CallMode {
    VOICE, VIDEO
}

enum class VideoUpgradeRequestState {
    NONE,
    PENDING,
    ACCEPTED,
    REJECTED
}

/**
 * Call lifecycle states.
 */
enum class CallState {
    INITIATING,
    RINGING,
    ACCEPTED,
    CONNECTED,
    ENDED,
    DECLINED,
    MISSED,
    BUSY,
    NO_ANSWER
}

enum class OutgoingCallUiStatus {
    CONNECTING,
    CALLING,
    RINGING,
    CONNECTED
}

/**
 * Root Firestore document stored at `calls/{callId}`.
 */
data class CallData(
    val callId: String = "",
    val callerId: String = "",
    val receiverId: String = "",
    val callerName: String = "",
    val callerPhone: String = "",
    val callerAvatar: String = "",
    val receiverName: String = "",
    val receiverAvatar: String = "",
    val type: String = "VOICE",              // "VOICE" | "VIDEO"
    val callMode: String = "VOICE",          // "VOICE" | "VIDEO"
    val status: String = "initiating",       // maps to CallState
    val callerVideoEnabled: Boolean = false,
    val receiverVideoEnabled: Boolean = false,
    val mediaStateRevision: Int = 0,
    val videoUpgradeRequestState: String = "NONE",
    val videoUpgradeRequesterId: String = "",
    val signalingEvent: String = "",
    val offer: String = "",                  // SDP offer JSON
    val answer: String = "",                 // SDP answer JSON
    val offerRevision: Int = 0,
    val answerRevision: Int = 0,
    val iceRestart: Boolean = false,
    val negotiationUpdatedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val answeredAt: Long = 0L,
    val endedAt: Long = 0L,
    /** When non-empty, the 1:1 call was upgraded to a group call with this ID. */
    val upgradedToGroupCallId: String = ""
) {
    fun callType(): CallType = if (type == "VIDEO") CallType.VIDEO else CallType.VOICE

    fun resolvedCallMode(): CallMode = if (callMode == "VIDEO") CallMode.VIDEO else CallMode.VOICE

    fun resolvedVideoUpgradeRequestState(): VideoUpgradeRequestState = when (videoUpgradeRequestState) {
        "PENDING" -> VideoUpgradeRequestState.PENDING
        "ACCEPTED" -> VideoUpgradeRequestState.ACCEPTED
        "REJECTED" -> VideoUpgradeRequestState.REJECTED
        else -> VideoUpgradeRequestState.NONE
    }

    fun callState(): CallState = when (status) {
        "initiating"  -> CallState.INITIATING
        "ringing"     -> CallState.RINGING
        "accepted"    -> CallState.ACCEPTED
        "connected"   -> CallState.CONNECTED
        "ended"       -> CallState.ENDED
        "declined"    -> CallState.DECLINED
        "missed"      -> CallState.MISSED
        "busy"        -> CallState.BUSY
        "no_answer"   -> CallState.NO_ANSWER
        else          -> CallState.ENDED
    }
}

/**
 * ICE candidate stored at `calls/{callId}/candidates/{docId}`.
 */
data class IceCandidateData(
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = "",
    val fromUserId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
