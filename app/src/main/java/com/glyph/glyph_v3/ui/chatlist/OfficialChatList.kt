package com.glyph.glyph_v3.ui.chatlist

import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.models.OFFICIAL_USER_ID
import com.glyph.glyph_v3.data.models.OfficialMessage

/**
 * Builds the synthetic "Glyph Official" chat row shown at the top of the chat list
 * when the portal has published official messages. The row is read-only: tapping it
 * opens [com.glyph.glyph_v3.ui.chat.OfficialChatActivity] instead of a normal chat.
 *
 * Shared by both the View ([ChatListFragment]) and Compose ([ChatListComposeFragment])
 * chat-list implementations so the row is built identically.
 */
fun buildOfficialChatRow(messages: List<OfficialMessage>, lastOpenedAt: Long): Chat? {
    if (messages.isEmpty()) return null
    val latest = messages.maxWithOrNull(
        compareByDescending<OfficialMessage> {
            if (it.publishedAt > 0) it.publishedAt else it.createdAt
        }
    ) ?: return null
    val ts = if (latest.publishedAt > 0) latest.publishedAt else latest.createdAt
    val lastMsg = latest.body.ifBlank { latest.title }.ifBlank { "Official message" }
    val unread = messages.count {
        (if (it.publishedAt > 0) it.publishedAt else it.createdAt) > lastOpenedAt
    }
    return Chat(
        id = OFFICIAL_USER_ID,
        participants = listOf(OFFICIAL_USER_ID),
        otherUsername = "Glyph Official",
        lastMessage = lastMsg,
        lastMessageTimestamp = if (ts > 0) java.util.Date(ts) else null,
        unreadCount = unread,
        isOfficial = true,
        otherUserAvatar = ""
    )
}

fun buildChatListWithOfficial(
    chats: List<Chat>,
    messages: List<OfficialMessage>,
    lastOpenedAt: Long
): List<Chat> {
    val official = buildOfficialChatRow(messages, lastOpenedAt) ?: return chats
    return listOf(official) + chats
}
