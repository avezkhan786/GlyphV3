package com.glyph.glyph_v3.data.models

import com.google.firebase.firestore.PropertyName

/**
 * Audience rule for who can listen to a user's live audio.
 */
enum class LiveAudioAudience {
    EVERYONE,
    CONTACTS,
    SELECTED_USERS
}

/**
 * Persisted per-user live audio sharing preferences stored in Firestore.
 *
 * Firestore path: users/{userId}/liveAudio/config
 */
data class LiveAudioConfig(
    val shareMic: Boolean = false,
    val audience: String = LiveAudioAudience.EVERYONE.name,
    val allowedUsers: List<String> = emptyList(),
    @get:PropertyName("isStreaming") @set:PropertyName("isStreaming") var isStreaming: Boolean = false,
    val currentListeners: List<String> = emptyList()
) {
    fun audienceEnum(): LiveAudioAudience = try {
        LiveAudioAudience.valueOf(audience)
    } catch (_: Exception) {
        LiveAudioAudience.SELECTED_USERS
    }
}

/**
 * Signaling session for an active live audio stream.
 *
 * Realtime DB path: liveAudioSessions/{sessionId}
 */
@com.google.firebase.database.IgnoreExtraProperties
data class LiveAudioSession(
    val sessionId: String = "",
    val broadcasterId: String = "",
    val listenerId: String = "",
    val status: String = STATUS_REQUESTING,
    val offer: String? = null,
    val answer: String? = null,
    val createdAt: Long = 0L,
    val endedAt: Long? = null
) {
    companion object {
        const val STATUS_REQUESTING = "requesting"
        const val STATUS_ACTIVE = "active"
        const val STATUS_ENDED = "ended"
        const val STATUS_REJECTED = "rejected"
    }
}

/**
 * ICE candidate for live audio signaling.
 * Stored in Realtime DB: liveAudioSessions/{sessionId}/candidates/{candidateId}
 */
data class LiveAudioIceCandidate(
    val candidate: String = "",
    val sdpMLineIndex: Int = 0,
    val sdpMid: String = "",
    val fromUserId: String = "",
    val timestamp: Long = 0L
)
