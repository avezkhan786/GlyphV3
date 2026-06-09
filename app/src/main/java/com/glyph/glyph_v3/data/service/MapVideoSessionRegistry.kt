package com.glyph.glyph_v3.data.service

import com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager

/**
 * Keeps the single foreground-service-owned map video manager reachable from the UI so
 * auto camera sharing and in-chat remote viewing use the same WebRTC session.
 */
object MapVideoSessionRegistry {

    private data class Entry(
        val chatId: String,
        val myUserId: String,
        val otherUserId: String,
        val manager: MapVideoSessionManager
    ) {
        fun matches(chatId: String, myUserId: String, otherUserId: String): Boolean {
            return this.chatId == chatId && this.myUserId == myUserId && this.otherUserId == otherUserId
        }
    }

    @Volatile
    private var entry: Entry? = null

    fun register(
        chatId: String,
        myUserId: String,
        otherUserId: String,
        manager: MapVideoSessionManager
    ) {
        entry = Entry(chatId, myUserId, otherUserId, manager)
    }

    fun get(chatId: String, myUserId: String, otherUserId: String): MapVideoSessionManager? {
        val current = entry ?: return null
        return current.manager.takeIf { current.matches(chatId, myUserId, otherUserId) }
    }

    /** Returns the active session for [chatId] regardless of which user-id pair created it. */
    fun getByChatId(chatId: String): MapVideoSessionManager? {
        return entry?.takeIf { it.chatId == chatId }?.manager
    }

    fun clear(manager: MapVideoSessionManager? = null) {
        val current = entry ?: return
        if (manager == null || current.manager === manager) {
            entry = null
        }
    }
}