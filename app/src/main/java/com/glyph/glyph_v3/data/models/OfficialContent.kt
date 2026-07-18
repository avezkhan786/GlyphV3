package com.glyph.glyph_v3.data.models

/**
 * Official content published by the Glyph Admin portal (Phase 18 F4).
 *
 * The Android app observes these collections READ-ONLY. `firestore.rules`
 * grants `allow read: if request.auth != null` for both `official_messages`
 * and `official_status`; writes are denied to clients because the portal
 * publishes via the Admin SDK (which bypasses rules).
 *
 * Schema mirrors the portal services:
 *  - `src/lib/services/official-messages.ts` (OfficialMessage)
 *  - `src/lib/services/official-status.ts` + `src/lib/status-lifecycle.ts`
 *    (OfficialStatus + live/scheduled/expired lifecycle).
 */

/** Stable id used to represent the company "Glyph Official" sender in the UI. */
const val OFFICIAL_USER_ID = "glyph_official"

enum class OfficialStatusType { TEXT, IMAGE, VIDEO }

enum class OfficialMessageKind {
    ANNOUNCEMENT, UPDATE, PROMOTION, ALERT, MAINTENANCE
}

enum class OfficialStatusLifecycle { LIVE, SCHEDULED, EXPIRED }

/**
 * A company announcement/message from the portal's Official Messages feature.
 * Firestore path: /official_messages/{id}
 */
data class OfficialMessage(
    val id: String = "",
    val kind: OfficialMessageKind = OfficialMessageKind.ANNOUNCEMENT,
    val title: String = "",
    val body: String = "",
    val imageUrl: String = "",
    val deepLink: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = 0L,
    val publishedAt: Long = 0L,
    val createdBy: String = "system"
)

/**
 * A company status (WhatsApp-style story) from the portal's Official Status
 * feature. Firestore path: /official_status/{id}
 */
data class OfficialStatus(
    val id: String = "",
    val type: OfficialStatusType = OfficialStatusType.TEXT,
    val text: String = "",
    val mediaUrl: String = "",
    val caption: String = "",
    /** Hex color string, e.g. "#1B5E20" (portal uses CSS hex). */
    val backgroundColor: String = "",
    val scheduledAt: Long? = null,
    val expiresAt: Long? = null,
    val publishedAt: Long? = null,
    val createdAt: Long? = null,
    val createdBy: String = "system",
    val views: Int = 0
) {
    /**
     * Live/scheduled/expired relative to [now], mirroring the portal's
     * `statusLifecycle()` helper so client + server agree on visibility.
     */
    fun lifecycle(now: Long = System.currentTimeMillis()): OfficialStatusLifecycle {
        return when {
            publishedAt != null && publishedAt <= now -> {
                if (expiresAt == null || expiresAt > now) {
                    OfficialStatusLifecycle.LIVE
                } else {
                    OfficialStatusLifecycle.EXPIRED
                }
            }
            else -> OfficialStatusLifecycle.SCHEDULED
        }
    }

    val isLive: Boolean get() = lifecycle() == OfficialStatusLifecycle.LIVE
}

/**
 * Map a live official status into the app's [Status] model so it renders in the
 * existing status viewer unchanged. The company "Glyph Official" sender is
 * represented by [OFFICIAL_USER_ID] and the statuses are marked viewed so they
 * render with a "viewed" ring (reply/like/delete write-back does not apply).
 */
fun OfficialStatus.toStatus(): Status {
    val defaultBg = 0xFF1B5E20.toInt()
    val bg = backgroundColor.takeIf { it.isNotBlank() }?.let { hex ->
        runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()
    } ?: defaultBg

    val displayText = when (type) {
        OfficialStatusType.TEXT -> text
        else -> caption.ifBlank { text }
    }

    val statusType = when (type) {
        OfficialStatusType.IMAGE -> StatusType.IMAGE
        OfficialStatusType.VIDEO -> StatusType.VIDEO
        else -> StatusType.TEXT
    }

    return Status(
        id = id,
        userId = OFFICIAL_USER_ID,
        type = statusType,
        text = displayText,
        backgroundColor = bg,
        mediaUrl = mediaUrl,
        caption = caption,
        timestamp = publishedAt ?: createdAt ?: 0L,
        expiresAt = expiresAt ?: 0L,
        viewerIds = listOf(OFFICIAL_USER_ID),
        visibleTo = emptyList(),
        durationMs = 0L
    )
}
