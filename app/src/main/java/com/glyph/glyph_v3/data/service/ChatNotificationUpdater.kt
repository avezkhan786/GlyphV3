package com.glyph.glyph_v3.data.service

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.FileProvider
import com.glyph.glyph_v3.data.media.MediaStorageManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import java.io.File

object ChatNotificationUpdater {
    private const val TAG = "ChatNotifUpdater"

    /**
     * Rebuilds and re-posts the chat notification for [chatId], attaching media from local storage
     * if it has been downloaded in the background.
     */
    fun refreshChatNotification(context: Context, chatId: String) {
        try {
            UnreadMessageStore.init(context.applicationContext)
            val meta = UnreadMessageStore.getChatMeta(chatId)
            if (meta == null) {
                return
            }

            val cachedAvatarPath = meta.otherUserId
                .takeIf { it.isNotBlank() }
                ?.let { com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(it) }

            // Resolve sender display name with device contact priority
            val resolvedSenderName = ContactDisplayNameResolver.getDisplayName(
                otherUserId = meta.otherUserId,
                remoteProfileName = meta.otherUsername
            )

            // Load avatar with fallback (blocking Glide call, already on background thread from worker)
            val avatarBitmap = ChatNotificationHelper.loadAvatarWithFallback(
                context = context.applicationContext,
                url = cachedAvatarPath ?: meta.otherUserAvatarUrl,
                name = resolvedSenderName
            )

            val unreadMessages = UnreadMessageStore.getMessages(chatId)
            if (unreadMessages.isEmpty()) return

            val styleMessages = unreadMessages.map { msg ->
                val person = Person.Builder()
                    .setName(resolvedSenderName)
                    .build()

                val styleMsg = NotificationCompat.MessagingStyle.Message(
                    msg.text,
                    msg.timestamp,
                    person
                )

                val localMedia = resolveLocalMedia(context, chatId, msg)
                if (localMedia != null) {
                    val (uri, mime) = localMedia
                    styleMsg.setData(mime, uri)
                }

                styleMsg
            }

            ChatNotificationHelper.showChatNotification(
                context = context,
                chatId = chatId,
                otherUserId = meta.otherUserId,
                senderName = resolvedSenderName,
                avatarBitmap = avatarBitmap,
                messages = styleMessages,
                silent = true  // Refreshes are always silent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh chat notification", e)
        }
    }

    private fun resolveLocalMedia(
        context: Context,
        chatId: String,
        msg: UnreadMessageStore.MessageData
    ): Pair<android.net.Uri, String>? {
        val messageId = msg.messageId ?: return null
        val messageType = msg.messageType ?: return null

        val storageType = when (messageType) {
            "IMAGE" -> MediaStorageManager.MediaType.IMAGE
            "VIDEO" -> MediaStorageManager.MediaType.VIDEO
            else -> null
        } ?: return null

        val file: File = MediaStorageManager.getMediaFile(context.applicationContext, chatId, messageId, storageType)
        if (!file.exists() || file.length() <= 0L) return null

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = when (messageType) {
            "IMAGE" -> "image/jpeg"
            "VIDEO" -> "video/mp4"
            else -> msg.mimeType ?: "application/octet-stream"
        }
        return Pair(uri, mime)
    }
}
