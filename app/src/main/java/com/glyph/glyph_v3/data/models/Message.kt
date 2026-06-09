package com.glyph.glyph_v3.data.models

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.runtime.Immutable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class Message(
    val id: String,
    val chatId: String,
    val text: String,
    val senderId: String,
    val timestamp: Long,
    val status: MessageStatus = MessageStatus.SENT,
    val isIncoming: Boolean,
    val type: MessageType = MessageType.TEXT,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDuration: Long = 0, // Duration in milliseconds
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
    val videoDuration: Long? = null, // Duration in milliseconds
    val fileSize: Long? = null, // File size in bytes
    val contactName: String? = null,
    val contactPhone: String? = null,
    val localUri: String? = null,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val mediaItems: String? = null,
    val deliveredTimestamp: Long? = null,
    val readTimestamp: Long? = null,
    // Reply metadata
    val replyToMessageId: String? = null,
    val replyToText: String? = null,
    val replyToSenderId: String? = null,
    val replyToType: MessageType? = null,
    val replyPreviewUrl: String? = null,
    // Edit metadata
    val isEdited: Boolean = false,
    val editedAt: Long? = null,
    // Delete-for-all metadata (WhatsApp-style)
    val isDeletedForAll: Boolean = false,
    val deletedAt: Long? = null,
    // Video note (circular video message)
    val isVideoNote: Boolean = false,
    // Document caption (optional, typed by sender before sending)
    val documentCaption: String? = null,
    // Optional webpage preview metadata for shared URLs
    val linkPreviewTitle: String? = null,
    val linkPreviewDomain: String? = null,
    val linkPreviewDescription: String? = null,
    val linkPreviewSiteName: String? = null,
    // Status reply metadata (for STATUS_REPLY type messages)
    val statusId: String? = null,
    val statusOwnerId: String? = null,
    val statusThumbnailUrl: String? = null,
    val statusType: String? = null,        // TEXT, IMAGE, VIDEO, VOICE
    val statusText: String? = null,        // For TEXT status: the status text
    val statusBgColor: Int? = null,        // For TEXT status: background color
    // Server-authoritative timestamp for deterministic cross-device ordering.
    // When non-null, takes priority over client-generated `timestamp` for sort order.
    val serverTimestamp: Long? = null,
    // WhatsApp-style forwarded marker. Forwarded messages keep the original content/media
    // but get a fresh sender, timestamp and delivery state.
    val isForwarded: Boolean = false,
    // WhatsApp-style emoji reactions: userId -> emoji.
    // Empty map = no reactions. Mirrors Firestore field `reactions` which is also a {uid: emoji} map.
    val reactions: Map<String, String> = emptyMap(),
    // ────────────────────────────────────────────────────────────
    // Group chat per-recipient receipts. Empty for 1:1 messages
    // (1:1 uses status / deliveredTimestamp / readTimestamp instead).
    // ────────────────────────────────────────────────────────────
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList()
) {
    /** Ordering timestamp: prefers server-authoritative time, falls back to client time. */
    val orderingTimestamp: Long get() = serverTimestamp ?: timestamp

    /**
     * PERFORMANCE: Pre-warm all lazily computed properties that are commonly touched during
     * first render / first scroll (JSON parsing, aspect ratio, time formatting).
     *
     * Call this from a ViewModel/background dispatcher *before* emitting messages to Compose
     * to avoid first-scroll jank on mid-range devices.
     */
    fun warmUpForUi() {
        // Always warm formattedTime (SimpleDateFormat + Date)
        runCatching { formattedTime }

        // Warm media-related lazies only when relevant
        when (type) {
            MessageType.IMAGE,
            MessageType.VIDEO,
            MessageType.GIF,
            MessageType.MEME,
            MessageType.STICKER -> {
                runCatching { aspectRatio }
            }

            MessageType.DOCUMENT -> Unit // No heavy pre-computation needed

            else -> Unit
        }
    }

    /**
     * Pre-calculated aspect ratio for media messages to prevent layout jumps and main-thread I/O
     */
    val aspectRatio: Float by lazy(LazyThreadSafetyMode.NONE) {
        if (mediaWidth > 0 && mediaHeight > 0) {
            return@lazy mediaWidth.toFloat() / mediaHeight.toFloat()
        }

        // Fallback: Try parsing from mediaItems (grouped media or single media with metadata)
        val item = mediaItemsList.firstOrNull()
        if (item != null && item.width > 0 && item.height > 0) {
            return@lazy item.width.toFloat() / item.height.toFloat()
        }
        
        // Final heuristic fallback for Klipy media which often lacks dimensions in older DB entries
        if (type == MessageType.GIF || type == MessageType.MEME) {
            return@lazy 1.333f // Default to 4:3 landscape which is safer than 1:1 for most memes
        }
        
        1.0f // Default square
    }

    /**
     * Returns the best available URL for display (local first, then remote)
     */
    val displayModel: String
        get() = localUri ?: imageUrl ?: ""

    /**
     * Pre-formatted timestamp to avoid expensive formatting during scroll
     */
    val formattedTime: String by lazy(LazyThreadSafetyMode.NONE) {
        // PERFORMANCE: java.time + cached formatter is significantly cheaper than allocating
        // a SimpleDateFormat per message, and is thread-safe.
        Instant.ofEpochMilli(timestamp).atZone(zoneId).format(timeFormatter)
    }

    /**
     * Lazy filtered list of media items to avoid JSON parsing on every access.
     */
    val mediaItemsList: List<MediaItem> by lazy(LazyThreadSafetyMode.NONE) {
        if (mediaItems.isNullOrEmpty()) {
            emptyList()
        } else {
            try {
                val type = object : TypeToken<List<MediaItem>>() {}.type
                (Gson().fromJson<List<MediaItem>>(mediaItems, type) ?: emptyList()).map { item ->
                    val safeLocalUri = item.localUri?.takeIf(::isUsableMediaLocalUri)
                    if (safeLocalUri == item.localUri) item else item.copy(localUri = safeLocalUri)
                }
            } catch (e: Exception) {
                // android.util.Log.e("Message", "Failed to parse mediaItems", e)
                emptyList()
            }
        }
    }

    companion object {
        private val zoneId: ZoneId = ZoneId.systemDefault()
        private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

        fun mediaItemsToJson(items: List<MediaItem>): String {
            return try {
                Gson().toJson(items)
            } catch (e: Exception) {
                "[]"
            }
        }
    }
}

private fun String?.takeIfNotBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun isUsableMediaLocalUri(candidate: String): Boolean {
    if (candidate.isBlank()) return false
    return runCatching {
        val parsed = android.net.Uri.parse(candidate)
        when {
            parsed.scheme == "content" -> true
            parsed.scheme == "file" || candidate.startsWith("/") -> {
                val path = parsed.path ?: candidate
                File(path).exists()
            }
            parsed.scheme != null -> true
            else -> File(candidate).exists()
        }
    }.getOrDefault(false)
}

fun Message.safeLocalPreviewUri(): String? {
    val candidate = localUri.takeIfNotBlank() ?: return null
    return runCatching {
        val parsed = android.net.Uri.parse(candidate)
        if (parsed.scheme == "file" || candidate.startsWith("/")) {
            val path = parsed.path ?: candidate
            if (File(path).exists()) candidate else null
        } else {
            candidate
        }
    }.getOrNull()
}

fun Message.bestReplyPreviewModel(): String? {
    val safeLocal = safeLocalPreviewUri()
    return when (type) {
        MessageType.IMAGE -> safeLocal ?: imageUrl.takeIfNotBlank() ?: thumbnailUrl.takeIfNotBlank()
        MessageType.VIDEO -> safeLocal ?: thumbnailUrl.takeIfNotBlank() ?: videoUrl.takeIfNotBlank()
        MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.GIF, MessageType.MEME ->
            safeLocal ?: imageUrl.takeIfNotBlank() ?: thumbnailUrl.takeIfNotBlank()
        MessageType.MEDIA_GROUP -> mediaItemsList.firstOrNull()?.let { item ->
            item.localUri?.takeIf { local ->
                local.isNotBlank() && runCatching {
                    val parsed = android.net.Uri.parse(local)
                    if (parsed.scheme == "file" || local.startsWith("/")) {
                        val path = parsed.path ?: local
                        File(path).exists()
                    } else {
                        true
                    }
                }.getOrDefault(false)
            } ?: item.displayUrl.takeIfNotBlank()
        }
        MessageType.DOCUMENT -> thumbnailUrl.takeIfNotBlank() ?: imageUrl.takeIfNotBlank()
        else -> null
    }
}

fun Message.bestReplyPreviewRemoteUrl(): String? {
    return when (type) {
        MessageType.IMAGE -> imageUrl.takeIfNotBlank() ?: thumbnailUrl.takeIfNotBlank()
        MessageType.VIDEO -> thumbnailUrl.takeIfNotBlank() ?: videoUrl.takeIfNotBlank()
        MessageType.STICKER, MessageType.KLIPY_EMOJI, MessageType.GIF, MessageType.MEME ->
            imageUrl.takeIfNotBlank() ?: thumbnailUrl.takeIfNotBlank()
        MessageType.MEDIA_GROUP -> mediaItemsList.firstOrNull()?.displayUrl.takeIfNotBlank()
        MessageType.DOCUMENT -> thumbnailUrl.takeIfNotBlank() ?: imageUrl.takeIfNotBlank()
        else -> null
    }
}

enum class MessageStatus {
    SENDING,           // Message is being sent (uploading media)
    SENT,              // Message sent to server
    DELIVERED,         // Message delivered to recipient device
    READ,              // Message read by recipient
    PLAYED,            // Voice message played by recipient
    FAILED,            // Message failed to send
    
    // Media-specific states
    PENDING_DOWNLOAD,  // Media available for download
    DOWNLOADING,       // Media is being downloaded
    DOWNLOAD_FAILED,   // Media download failed
    DOWNLOADED         // Media downloaded and saved locally
}

enum class MessageType {
    TEXT, IMAGE, AUDIO, VIDEO, CONTACT, MEDIA_GROUP, GIF, STICKER, KLIPY_EMOJI, MEME, DOCUMENT, STATUS_REPLY,
    // System / informational message rendered as a centered grey pill (no bubble).
    // Used for group lifecycle events (created, member added/removed, name/icon changed, etc.).
    SYSTEM
}
