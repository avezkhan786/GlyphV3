package com.glyph.glyph_v3.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats", indices = [Index(value = ["lastMessageTimestamp"])])
data class LocalChat(
    @PrimaryKey val id: String,              // chatId (e.g., "userId1_userId2" or "group_<uuid>")
    val otherUserId: String,                 // 1:1 only — empty for groups
    val otherUsername: String,               // 1:1 only — empty for groups
    val otherUserAvatar: String = "",        // 1:1 only — empty for groups
    val lastMessage: String = "",
    val lastMessageTimestamp: Long = 0,
    val lastMessageSenderId: String = "",
    val lastMessageStatus: String = "SENT",  // Message status: SENDING, SENT, DELIVERED, READ
    val unreadCount: Int = 0,
    val isArchived: Boolean = false,
    // ── Group chat metadata (defaults preserve 1:1 behavior) ──
    val isGroup: Boolean = false,
    val groupName: String = "",
    val groupIconUrl: String = "",
    val groupDescription: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L,
    // JSON-encoded List<String> of all participant uids (groups). Null/blank for 1:1.
    val participantsJson: String? = null,
    // JSON-encoded List<String> of admin uids (groups). Null/blank for 1:1.
    val adminsJson: String? = null
)
