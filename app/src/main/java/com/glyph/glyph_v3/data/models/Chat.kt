package com.glyph.glyph_v3.data.models

import androidx.compose.runtime.Immutable
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

@Immutable
data class Chat(
    val id: String = "",
    val participants: List<String> = emptyList(),
    val lastMessage: String = "",
    @ServerTimestamp val lastMessageTimestamp: Date? = null,
    val lastMessageSenderId: String = "",
    val lastMessageStatus: String = "SENT", // Message status: SENDING, SENT, DELIVERED, READ
    val unreadCount: Int = 0,

    // These fields are populated locally after fetching the chat
    val otherUsername: String = "",
    val otherUserAvatar: String = "",

    // Presence fields (populated from PresenceManager)
    val isOtherUserOnline: Boolean = false,
    val isOtherUserInChat: Boolean = false,
    val otherUserLastSeen: Long = 0L,

    // Typing indicator
    val isOtherUserTyping: Boolean = false,
    val typingText: String = "",

    // Archive status
    val isArchived: Boolean = false,

    // Chat lock (local only, not synced — set from ChatSettingsDataStore)
    val isLocked: Boolean = false,

    // Draft text (local only, not synced)
    val draft: String = "",

    // ────────────────────────────────────────────────────────────
    // Group chat metadata (all empty/false for 1:1 — strictly additive)
    // ────────────────────────────────────────────────────────────
    val isGroup: Boolean = false,
    val groupName: String = "",
    val groupIconUrl: String = "",
    val groupDescription: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val admins: List<String> = emptyList(),

    // Group presence (populated dynamically, never persisted to Room)
    val groupOnlineCount: Int = 0,
    val groupOnlineUserNames: List<String> = emptyList()
)
