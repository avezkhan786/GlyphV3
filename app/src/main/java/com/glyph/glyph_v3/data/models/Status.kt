package com.glyph.glyph_v3.data.models

/**
 * Represents a single status item (text, image, or video).
 *
 * Firestore path: /statuses/{statusId}
 */
data class Status(
    val id: String = "",
    val userId: String = "",
    val type: StatusType = StatusType.TEXT,
    val text: String = "",
    val backgroundColor: Int = 0xFF1B5E20.toInt(), // default dark green
    val fontStyle: String = "default",
    val mediaUrl: String = "",
    val thumbnailUrl: String = "",
    val caption: String = "",
    val timestamp: Long = 0L,
    val expiresAt: Long = 0L,
    val viewerIds: List<String> = emptyList(),
    val likedByIds: List<String> = emptyList(),
    /** User IDs allowed to see this status (computed from privacy settings at upload time).
     *  Always includes the owner. Enables Firestore whereArrayContains queries. */
    val visibleTo: List<String> = emptyList(),
    /** Duration in milliseconds for voice statuses. */
    val durationMs: Long = 0L,
    /** Per-viewer seen timestamps: userId → epoch ms. */
    val viewerTimestamps: Map<String, Long> = emptyMap()
)

/** Pairs a viewer's profile with the time they viewed the status. */
data class ViewerInfo(
    val user: User,
    /** Epoch ms when the status was viewed; 0 if not recorded. */
    val seenAt: Long
)

enum class StatusType {
    TEXT, IMAGE, VIDEO, VOICE
}

/**
 * Privacy setting for who can see a user's statuses.
 *
 * Firestore path: /users/{userId} → statusPrivacy field
 */
data class StatusPrivacySetting(
    val mode: StatusPrivacyMode = StatusPrivacyMode.MY_CONTACTS,
    val excludedContacts: List<String> = emptyList(),
    val includedContacts: List<String> = emptyList()
)

enum class StatusPrivacyMode {
    MY_CONTACTS,
    MY_CONTACTS_EXCEPT,
    ONLY_SHARE_WITH
}

/**
 * Groups all statuses from a single user for display in the status list.
 */
data class UserStatusGroup(
    val userId: String = "",
    val username: String = "",
    val profileImageUrl: String = "",
    val statuses: List<Status> = emptyList(),
    val lastStatusTimestamp: Long = 0L,
    val isMine: Boolean = false,
    val allViewed: Boolean = false
)
