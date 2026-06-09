package com.glyph.glyph_v3.data.service

import android.app.NotificationManager
import android.content.Context

/**
 * Singleton to track which chat is currently open.
 * Used to suppress notifications when the user is already viewing the chat.
 */
object ActiveChatManager {
    
    @Volatile
    var activeChatId: String? = null
        private set

    /** Set to the chatId when the local user has map video mode enabled; null otherwise. */
    @Volatile
    var mapVideoEnabledChatId: String? = null
        private set

    fun setMapVideoActive(chatId: String?) {
        mapVideoEnabledChatId = chatId
    }
    
    fun setActiveChat(chatId: String?, context: Context? = null) {
        activeChatId = chatId
        // Clear notification and unread messages for this chat when opened
        if (chatId != null && context != null) {
            // Cancel the notification using the same tag and ID as ChatNotificationHelper
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationTag = "chat_$chatId"
            
            // Cancel both the message notification and the summary notification
            notificationManager.cancel(notificationTag, 0) // CHAT_NOTIFICATION_ID
            notificationManager.cancel(notificationTag, 1) // CHAT_SUMMARY_NOTIFICATION_ID
            
            // Clear stored unread messages for this chat
            UnreadMessageStore.init(context)
            UnreadMessageStore.clearMessages(chatId)
        }
    }
    
    fun clearActiveChat() {
        activeChatId = null
    }
    
    fun isCurrentlyViewing(chatId: String?): Boolean {
        return chatId != null && chatId == activeChatId
    }
}
