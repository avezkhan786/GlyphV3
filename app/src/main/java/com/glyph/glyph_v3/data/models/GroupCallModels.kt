package com.glyph.glyph_v3.data.models

/**
 * Group call lifecycle states.
 */
enum class GroupCallState {
    ACTIVE,
    ENDED
}

/**
 * Participant status within a group call.
 */
enum class GroupCallParticipantStatus {
    RINGING,
    CONNECTED,
    LEFT,
    DECLINED,
    NO_ANSWER
}

/**
 * Root Firestore document stored at `groupCalls/{groupCallId}`.
 */
data class GroupCallData(
    val groupCallId: String = "",
    val creatorId: String = "",
    val creatorName: String = "",
    val creatorAvatar: String = "",
    val type: String = "VIDEO",          // "VOICE" | "VIDEO"
    val status: String = "active",       // "active" | "ended"
    val createdAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0L,
    /** Set when this group call was upgraded from a 1:1 call. */
    val upgradedFromCallId: String = ""
) {
    fun callType(): CallType = if (type == "VIDEO") CallType.VIDEO else CallType.VOICE

    fun groupCallState(): GroupCallState = when (status) {
        "active" -> GroupCallState.ACTIVE
        "ended" -> GroupCallState.ENDED
        else -> GroupCallState.ENDED
    }
}

/**
 * Participant document stored at `groupCalls/{groupCallId}/participants/{userId}`.
 */
data class GroupCallParticipant(
    val userId: String = "",
    val userName: String = "",
    val userAvatar: String = "",
    val status: String = "ringing",      // "ringing" | "connected" | "left" | "declined" | "no_answer"
    val videoEnabled: Boolean = false,
    val audioEnabled: Boolean = true,
    val joinedAt: Long = 0L,
    val leftAt: Long = 0L
) {
    fun participantStatus(): GroupCallParticipantStatus = when (status) {
        "ringing" -> GroupCallParticipantStatus.RINGING
        "connected" -> GroupCallParticipantStatus.CONNECTED
        "left" -> GroupCallParticipantStatus.LEFT
        "declined" -> GroupCallParticipantStatus.DECLINED
        "no_answer" -> GroupCallParticipantStatus.NO_ANSWER
        else -> GroupCallParticipantStatus.LEFT
    }
}

/**
 * Pairwise signaling document stored at `groupCalls/{groupCallId}/signaling/{pairKey}`.
 */
data class GroupCallSignaling(
    val offer: String = "",
    val offerFromUserId: String = "",
    val offerRevision: Int = 0,
    val answer: String = "",
    val answerRevision: Int = 0,
    val updatedAt: Long = 0L
)

/**
 * ICE candidate stored at `groupCalls/{groupCallId}/signaling/{pairKey}/candidates/{docId}`.
 */
data class GroupCallIceCandidate(
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val candidate: String = "",
    val fromUserId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Derived UI state for rendering a participant tile in the group call grid.
 */
data class GroupCallParticipantUiState(
    val userId: String = "",
    val userName: String = "",
    val userAvatar: String = "",
    val videoEnabled: Boolean = false,
    val audioEnabled: Boolean = true,
    val isConnected: Boolean = false,
    val isSelf: Boolean = false
)
