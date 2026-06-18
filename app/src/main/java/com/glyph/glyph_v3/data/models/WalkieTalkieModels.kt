package com.glyph.glyph_v3.data.models

import com.google.firebase.database.IgnoreExtraProperties

/**
 * Walkie-talkie session stored in Realtime DB.
 *
 * Path: walkieTalkieSessions/{sessionId}
 */
@IgnoreExtraProperties
data class WalkieTalkieSession(
    val sessionId: String = "",
    val initiatorId: String = "",
    val initiatorName: String = "",
    val responderId: String = "",
    val status: String = STATUS_REQUESTING,
    val statusUpdatedAt: Long = 0L,
    val endedBy: String? = null,
    val endReason: String? = null,
    val offer: String? = null,
    val answer: String? = null,
    val offerRevision: Int = 0,
    val answerRevision: Int = 0,
    val answeredOfferRevision: Int = 0,
    val iceRestart: Boolean = false,
    val createdAt: Long = 0L,
    val endedAt: Long? = null
) {
    companion object {
        const val STATUS_REQUESTING = "requesting"
        const val STATUS_RINGING = "ringing"
        const val STATUS_ACTIVE = "active"
        const val STATUS_ENDED = "ended"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_TIMEOUT = "timeout"

        const val END_REASON_LOCAL_CANCEL = "local_cancel"
        const val END_REASON_REMOTE_CANCEL = "remote_cancel"
        const val END_REASON_REJECTED = "rejected"
        const val END_REASON_COMPLETED = "completed"
        const val END_REASON_ERROR = "error"
        const val END_REASON_TIMEOUT = "timeout"

        /** Maximum age of a session request before it is considered expired.
         *  60 s gives room for FCM delivery delays (can be 5-35 s on Android Doze/cold-start)
         *  plus device clock skew (up to ~5 s between devices) plus RTDB reconnect time. */
        const val SESSION_REQUEST_TTL_MS = 45_000L

        /** Statuses that indicate the session is no longer joinable. */
        val TERMINAL_STATUSES = setOf(STATUS_ENDED, STATUS_CANCELLED, STATUS_REJECTED, STATUS_TIMEOUT)
    }

    /** Returns true if this session is still potentially joinable. */
    fun isJoinable(): Boolean {
        if (status in TERMINAL_STATUSES) return false
        // Active sessions stay valid regardless of age — TTL only applies to unanswered requests
        if (status == STATUS_ACTIVE) return true
        return createdAt <= 0L || System.currentTimeMillis() - createdAt < SESSION_REQUEST_TTL_MS
    }
}

/**
 * Floor control state — who currently holds the "talk" floor.
 *
 * Path: walkieTalkieSessions/{sessionId}/floor
 */
@IgnoreExtraProperties
data class WalkieTalkieFloor(
    val holderId: String? = null,
    val heldSince: Long = 0L,
    val updatedAt: Long = 0L
) {
    companion object {
        /** Auto-release floor after this duration to prevent stuck state. */
        const val MAX_HOLD_MS = 30_000L
    }

    fun isHeld(): Boolean =
        holderId != null && (System.currentTimeMillis() - heldSince) < MAX_HOLD_MS

    fun isHeldBy(userId: String): Boolean =
        holderId == userId && isHeld()
}

data class WalkieTalkiePttEffectEvent(
    val senderId: String = "",
    val phase: String = "",
    val sequence: Long = 0L,
    val emittedAt: Long = 0L
) {
    companion object {
        const val PHASE_PRESS = "press"
        const val PHASE_RELEASE = "release"
    }
}

/**
 * ICE candidate reused from the live audio system — same structure.
 */
@IgnoreExtraProperties
data class WalkieTalkieIceCandidate(
    val candidate: String = "",
    val sdpMLineIndex: Int = 0,
    val sdpMid: String = "",
    val fromUserId: String = "",
    val timestamp: Long = 0L
)
