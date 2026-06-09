package com.glyph.glyph_v3.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatId", "timestamp"]),
        Index(value = ["chatId", "isIncoming", "timestamp"]),
        Index(value = ["chatId", "status"])
    ]
)
data class LocalMessage(
    @PrimaryKey val id: String,
    val chatId: String, // Foreign key linking to a chat/user
    val text: String,
    val senderId: String,
    val timestamp: Long,
    val status: MessageStatus,
    val isIncoming: Boolean,
    val type: MessageType,
    val imageUrl: String? = null,
    val audioUrl: String? = null,
    val audioDuration: Long = 0,
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
    val videoDuration: Long? = null,
    val fileSize: Long? = null,
    val contactName: String? = null,
    val contactPhone: String? = null,
    val localUri: String? = null,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    // Multi-media support: JSON-encoded list of MediaItem objects
    val mediaItems: String? = null,
    val deliveredTimestamp: Long? = null,
    val readTimestamp: Long? = null,
    // Reply metadata
    val replyToMessageId: String? = null,
    val replyToText: String? = null,
    val replyToSenderId: String? = null,
    val replyToType: String? = null,
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
    // Starred / favourite flag (local only, never synced to server)
    val isStarred: Boolean = false,
    // Pinned-to-chat flag (local only)
    val isPinned: Boolean = false,
    // Unix-ms timestamp after which the pin expires (null = no expiry)
    val pinnedUntil: Long? = null,
    // Status reply metadata (for STATUS_REPLY type messages)
    val statusId: String? = null,
    val statusOwnerId: String? = null,
    val statusThumbnailUrl: String? = null,
    val statusType: String? = null,
    val statusText: String? = null,
    val statusBgColor: Int? = null,
    // Server-authoritative timestamp for deterministic cross-device ordering
    val serverTimestamp: Long? = null,
    // Persisted forwarded marker for WhatsApp-style labels and cross-device sync.
    val isForwarded: Boolean = false,
    // WhatsApp-style emoji reactions, JSON-encoded {userId: emoji}. Null/blank = no reactions.
    val reactionsJson: String? = null,
    // Group-only per-recipient receipts. JSON-encoded List<String> of uids.
    // Null/blank for 1:1 messages (1:1 uses status + deliveredTimestamp/readTimestamp).
    val deliveredToJson: String? = null,
    val readByJson: String? = null
)
