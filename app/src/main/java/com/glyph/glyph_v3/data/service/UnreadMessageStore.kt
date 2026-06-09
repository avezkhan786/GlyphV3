package com.glyph.glyph_v3.data.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores unread messages per chat for notification grouping.
 * Messages are cleared when the user opens the corresponding chat.
 */
object UnreadMessageStore {
    
    private const val PREFS_NAME = "unread_messages_prefs"
    private const val KEY_MESSAGES = "unread_messages"
    private const val KEY_CHAT_META = "chat_meta"
    
    private var prefs: SharedPreferences? = null
    
    // In-memory cache: chatId -> list of (message, timestamp)
    private val unreadMessages = mutableMapOf<String, MutableList<MessageData>>()

    // In-memory cache: chatId -> chat metadata used to rebuild notifications
    private val chatMeta = mutableMapOf<String, ChatMeta>()

    data class ChatMeta(
        val otherUserId: String,
        val otherUsername: String,
        val otherUserAvatarUrl: String? = null
    )
    
    data class MessageData(
        val messageId: String? = null,
        val messageType: String? = null,
        val text: String,
        val timestamp: Long,
        val senderName: String,
        val mediaUrl: String? = null,
        val mimeType: String? = null,
        // Phase 10: optional fields used to render per-sender avatars in group
        // notifications. 1:1 callers omit them and behavior is unchanged.
        val senderId: String? = null,
        val senderAvatarUrl: String? = null
    )
    
    fun init(context: Context) {
        if (prefs == null) {
            val baseContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(UserManager::class.java)
                if (um != null && !um.isUserUnlocked) {
                    val deviceContext = context.createDeviceProtectedStorageContext()
                    // Best-effort migration so existing prefs still load after unlock.
                    runCatching { deviceContext.moveSharedPreferencesFrom(context, PREFS_NAME) }
                    deviceContext
                } else {
                    context
                }
            } else {
                context
            }

            prefs = baseContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
            // Clean up old messages on init
            cleanupOldMessages()
        }
    }

    fun setChatMeta(chatId: String, otherUserId: String, otherUsername: String, otherUserAvatarUrl: String? = null) {
        chatMeta[chatId] = ChatMeta(otherUserId = otherUserId, otherUsername = otherUsername, otherUserAvatarUrl = otherUserAvatarUrl)
        saveChatMetaToPrefs()
    }

    fun getChatMeta(chatId: String): ChatMeta? {
        return chatMeta[chatId]
    }
    
    /**
     * Remove messages older than 24 hours to prevent stale data buildup
     */
    private fun cleanupOldMessages() {
        val cutoffTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // 24 hours ago
        var changed = false
        
        for ((chatId, messages) in unreadMessages.toMap()) {
            val filtered = messages.filter { it.timestamp > cutoffTime }.toMutableList()
            if (filtered.size != messages.size) {
                changed = true
                if (filtered.isEmpty()) {
                    unreadMessages.remove(chatId)
                } else {
                    unreadMessages[chatId] = filtered
                }
            }
        }
        
        if (changed) {
            saveToPrefs()
        }
    }
    
    fun addMessage(
        chatId: String,
        message: String,
        senderName: String,
        mediaUrl: String? = null,
        mimeType: String? = null,
        senderId: String? = null,
        senderAvatarUrl: String? = null
    ) {
        addMessage(
            chatId = chatId,
            messageId = null,
            messageType = null,
            message = message,
            senderName = senderName,
            mediaUrl = mediaUrl,
            mimeType = mimeType,
            senderId = senderId,
            senderAvatarUrl = senderAvatarUrl
        )
    }

    fun addMessage(
        chatId: String,
        messageId: String?,
        messageType: String?,
        message: String,
        senderName: String,
        mediaUrl: String? = null,
        mimeType: String? = null,
        senderId: String? = null,
        senderAvatarUrl: String? = null
    ) {
        val messages = unreadMessages.getOrPut(chatId) { mutableListOf() }
        
        // Deduplication: If messageId is provided, check if it already exists
        if (messageId != null && messages.any { it.messageId == messageId }) {
            return
        }
        
        // Limit to last 10 messages per chat to prevent excessive buildup
        if (messages.size >= 10) {
            messages.removeAt(0)
        }
        
        messages.add(
            MessageData(
                messageId = messageId,
                messageType = messageType,
                text = message,
                timestamp = System.currentTimeMillis(),
                senderName = senderName,
                mediaUrl = mediaUrl,
                mimeType = mimeType,
                senderId = senderId,
                senderAvatarUrl = senderAvatarUrl
            )
        )
        saveToPrefs()
    }
    
    fun getMessages(chatId: String): List<MessageData> {
        return unreadMessages[chatId]?.toList() ?: emptyList()
    }
    
    fun clearMessages(chatId: String) {
        unreadMessages.remove(chatId)
        chatMeta.remove(chatId)
        saveToPrefs()
        saveChatMetaToPrefs()
    }

    fun removeMessagesByMessageId(chatId: String, messageId: String) {
        val messages = unreadMessages[chatId] ?: return
        val updated = messages.filterNot { it.messageId == messageId }.toMutableList()
        if (updated.size == messages.size) return
        if (updated.isEmpty()) {
            unreadMessages.remove(chatId)
        } else {
            unreadMessages[chatId] = updated
        }
        saveToPrefs()
    }
    
    /**
     * Clear failed media URL for a specific message to prevent repeated download attempts.
     * Called when media download fails with 404.
     */
    fun clearMediaUrl(chatId: String, mediaUrl: String) {
        val messages = unreadMessages[chatId] ?: return
        val updated = messages.map { msg ->
            if (msg.mediaUrl == mediaUrl) {
                msg.copy(mediaUrl = null, mimeType = null)
            } else {
                msg
            }
        }.toMutableList()
        unreadMessages[chatId] = updated
        saveToPrefs()
    }
    
    fun getMessageCount(chatId: String): Int {
        return unreadMessages[chatId]?.size ?: 0
    }
    
    private fun saveToPrefs() {
        val jsonObject = JSONObject()
        for ((chatId, messages) in unreadMessages) {
            val jsonArray = JSONArray()
            for (msg in messages) {
                val msgJson = JSONObject().apply {
                    put("messageId", msg.messageId)
                    put("messageType", msg.messageType)
                    put("text", msg.text)
                    put("timestamp", msg.timestamp)
                    put("senderName", msg.senderName)
                    put("mediaUrl", msg.mediaUrl)
                    put("mimeType", msg.mimeType)
                    put("senderId", msg.senderId)
                    put("senderAvatarUrl", msg.senderAvatarUrl)
                }
                jsonArray.put(msgJson)
            }
            jsonObject.put(chatId, jsonArray)
        }
        prefs?.edit()?.putString(KEY_MESSAGES, jsonObject.toString())?.apply()
    }

    private fun saveChatMetaToPrefs() {
        val metaJson = JSONObject()
        for ((chatId, meta) in chatMeta) {
            val obj = JSONObject().apply {
                put("otherUserId", meta.otherUserId)
                put("otherUsername", meta.otherUsername)
                put("otherUserAvatarUrl", meta.otherUserAvatarUrl)
            }
            metaJson.put(chatId, obj)
        }
        prefs?.edit()?.putString(KEY_CHAT_META, metaJson.toString())?.apply()
    }
    
    private fun loadFromPrefs() {
        val jsonString = prefs?.getString(KEY_MESSAGES, null)
        val metaString = prefs?.getString(KEY_CHAT_META, null)
        try {
            if (!jsonString.isNullOrBlank()) {
                val jsonObject = JSONObject(jsonString)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val chatId = keys.next()
                    val jsonArray = jsonObject.getJSONArray(chatId)
                    val messages = mutableListOf<MessageData>()
                    for (i in 0 until jsonArray.length()) {
                        val msgJson = jsonArray.getJSONObject(i)
                        val mediaUrl = msgJson.optString("mediaUrl", "")?.takeIf { it.isNotEmpty() }
                        val mimeType = msgJson.optString("mimeType", "")?.takeIf { it.isNotEmpty() }
                        val messageId = msgJson.optString("messageId", "")?.takeIf { it.isNotEmpty() }
                        val messageType = msgJson.optString("messageType", "")?.takeIf { it.isNotEmpty() }
                        val senderId = msgJson.optString("senderId", "").takeIf { it.isNotEmpty() }
                        val senderAvatarUrl = msgJson.optString("senderAvatarUrl", "").takeIf { it.isNotEmpty() }
                        messages.add(
                            MessageData(
                                messageId = messageId,
                                messageType = messageType,
                                text = msgJson.getString("text"),
                                timestamp = msgJson.getLong("timestamp"),
                                senderName = msgJson.getString("senderName"),
                                mediaUrl = mediaUrl,
                                mimeType = mimeType,
                                senderId = senderId,
                                senderAvatarUrl = senderAvatarUrl
                            )
                        )
                    }
                    unreadMessages[chatId] = messages
                }
            }

            if (!metaString.isNullOrBlank()) {
                val metaObject = JSONObject(metaString)
                val metaKeys = metaObject.keys()
                while (metaKeys.hasNext()) {
                    val chatId = metaKeys.next()
                    val obj = metaObject.getJSONObject(chatId)
                    val otherUserId = obj.optString("otherUserId", "")
                    val otherUsername = obj.optString("otherUsername", "")
                    if (otherUserId.isNotEmpty() && otherUsername.isNotEmpty()) {
                        val avatar = obj.optString("otherUserAvatarUrl", "")?.takeIf { it.isNotEmpty() }
                        chatMeta[chatId] = ChatMeta(
                            otherUserId = otherUserId,
                            otherUsername = otherUsername,
                            otherUserAvatarUrl = avatar
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Clear corrupted data
            unreadMessages.clear()
            chatMeta.clear()
            prefs?.edit()?.remove(KEY_MESSAGES)?.apply()
            prefs?.edit()?.remove(KEY_CHAT_META)?.apply()
        }
    }
}
