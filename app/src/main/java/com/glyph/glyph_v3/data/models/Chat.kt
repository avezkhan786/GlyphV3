package com.glyph.glyph_v3.data.models

import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class Chat(
    val id: String = "",
    val participants: List<String> = emptyList(),
    val lastMessage: String = "",
    @ServerTimestamp val lastMessageTimestamp: Date? = null,
    val lastMessageSenderId: String = "",
    val lastMessageStatus: String = "SENT", // Message status: SENDING, SENT, DELIVERED, READ
    val unreadCount: Int = 0,

    // These fields are populated locally after fetching the chat
    var otherUsername: String = "",
    var otherUserAvatar: String = "",
    
    // Presence fields (populated from PresenceManager)
    var isOtherUserOnline: Boolean = false,
    var isOtherUserInChat: Boolean = false,
    var otherUserLastSeen: Long = 0L,
    
    // Typing indicator
    var isOtherUserTyping: Boolean = false,
    var typingText: String = "",
    
    // Archive status
    var isArchived: Boolean = false,

    // Chat lock (local only, not synced — set from ChatSettingsDataStore)
    var isLocked: Boolean = false,

    // Draft text (local only, not synced)
    var draft: String = "",

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
    var groupOnlineCount: Int = 0,
    var groupOnlineUserNames: List<String> = emptyList()
)
