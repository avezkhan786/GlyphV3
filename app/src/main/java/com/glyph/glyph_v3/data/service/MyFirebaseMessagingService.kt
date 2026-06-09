package com.glyph.glyph_v3.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.text.TextPaint
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.StatusCacheManager
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.models.CallState
import com.glyph.glyph_v3.data.preferences.StatusNotificationPrefs
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.glyph.glyph_v3.data.repo.CallSignalingRepository
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptScope
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptSettingsRepository
import com.glyph.glyph_v3.data.repo.WalkieTalkieRepository
import com.glyph.glyph_v3.data.webrtc.CallManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.core.content.FileProvider
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.local.entity.LocalChat
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MediaType
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.models.Status
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.glyph.glyph_v3.ui.chat.ChatTranscriptSnapshotBuilder
import com.glyph.glyph_v3.utils.MessageCacheManager
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import android.graphics.Bitmap.CompressFormat
import android.telecom.DisconnectCause
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class DeliveryReceiptPayload(
        val chatId: String,
        val senderId: String,
        val messageId: String,
        val deliveredAt: Long
    )

    companion object {
        private const val TAG = "MyFirebaseMsgService"
        private const val TRACE_TAG = "MessageTrace"
        private const val CHANNEL_ID = "glyph_chat_channel"
        private const val CAMERA_INVITE_CHANNEL_ID = "camera_invite_channel"
        private const val CAMERA_INVITE_TTL_MS = 15_000L
        private const val GROUP_KEY = "com.glyph.glyph_v3.CHAT_MESSAGES"
    }
    
    override fun onCreate() {
        super.onCreate()
        UnreadMessageStore.init(applicationContext)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphV3:FCM")
        try {
            wakeLock?.acquire(10_000L)

        // Data-only FCM payload - we control all notification rendering
        // This ensures rich notifications (MessagingStyle, profile pics, actions) work in ALL states:
        // - App in foreground
        // - App in background  
        // - App killed
        // - Device in Doze mode
        
            val data = remoteMessage.data
        
            if (data.isNotEmpty()) {
                // ── CALL HANDLING ─────────────────────────────────────────
                val fcmType = data["type"] ?: ""
                if (fcmType == "INCOMING_CALL") {
                    handleIncomingCallNotification(data)
                    return
                }
                if (fcmType == "GROUP_CALL_INVITATION") {
                    handleGroupCallInvitation(data)
                    return
                }
                if (fcmType == "LIVE_AUDIO_REQUEST") {
                    handleLiveAudioRequestNotification(data)
                    return
                }
                if (fcmType == "WALKIE_TALKIE_REQUEST") {
                    handleWalkieTalkieRequestNotification(data)
                    return
                }
                if (fcmType == "CAMERA_INVITE") {
                    handleCameraInviteNotification(data)
                    return
                }
                if (fcmType == "CAMERA_INVITE_RESPONSE") {
                    handleCameraInviteResponseNotification(data)
                    return
                }
                if (fcmType == "MISSED_CAMERA_INVITE") {
                    handleMissedCameraInviteNotification(data)
                    return
                }
                if (fcmType == "STATUS_UPDATE") {
                    handleStatusUpdateNotification(data)
                    return
                }
                // ──────────────────────────────────────────────────────────

                // Extract chatId early for routing decisions.
                val rawChatId = data["chat_id"] ?: data["chatId"] ?: ""
                val chatId = rawChatId.trim()

                // BLOCK CHECK: Suppress notifications from blocked users (client-side defense-in-depth)
                // The Cloud Function also checks this, but this provides instant local filtering
                // for any race conditions or edge cases.
                val senderId = (data["other_user_id"] ?: data["senderId"] ?: data["sender_id"] ?: "").trim()
                if (senderId.isNotEmpty() && com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(senderId)) {
                    return
                }
                
                // BUZZ Handling
                if (data["type"] == "BUZZ") {
                    val senderName = data["senderName"] ?: "Someone"
                    val shouldShow = data["show_notification"] != "false"
                    
                    // If senderId was present and blocked, we'd have returned already. For safety, double-check.
                    if (senderId.isNotEmpty() && com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(senderId)) {
                        return
                    }
                    
                    // Pass to BuzzManager. If UI handles it (returns true), skip notification.
                    val handledByUi = com.glyph.glyph_v3.util.BuzzManager.onBuzzReceived(applicationContext, chatId, senderName)
                    
                    if (!handledByUi && shouldShow) {
                        sendNotification(senderName, "BUZZ!!! ⚡", data)
                    }
                    return
                }

                val mediaItemsLen = data["mediaItems"]?.length ?: 0
            
            // Check if we should show notification
            val serverShouldShow = data["show_notification"] != "false"
            val hasMessageIdentity = !(data["messageId"] ?: data["id"]).isNullOrBlank()
            val shouldForceShowForActiveChat = chatId.isNotEmpty() && hasMessageIdentity && data["show_notification"] == null
            val shouldShow = serverShouldShow || shouldForceShowForActiveChat
            val isViewingThisChat = chatId.isNotEmpty() && ActiveChatManager.isCurrentlyViewing(chatId)
            
            if (isViewingThisChat) {
                // User is viewing this exact chat - don't show notification
                // But still process the message data for media download
                scheduleMediaDownloadIfNeeded(data)
            } else if (shouldShow) {
                // Build and show rich notification
                if (!serverShouldShow && shouldForceShowForActiveChat) {
                }
                handleNow(data)
            } else {
                // Silent data update (e.g., typing indicator, presence)
            }

            sendDeliveryReceiptFromFcm(data)
            serviceScope.launch {
                persistIncomingMessageToLocalStore(data)
            }
            } else {
            // Fallback for notification-only payload (shouldn't happen with our setup)
            remoteMessage.notification?.let { notification ->
                // Extract chatId from notification data if available
                val notifData = remoteMessage.data
                val chatId = (notifData["chat_id"] ?: notifData["chatId"] ?: "").trim()
                
                // Check if archived before showing fallback notification
                if (chatId.isNotEmpty()) {
                    val isArchived = try {
                        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                            com.glyph.glyph_v3.data.local.AppDatabase.getDatabase(applicationContext)
                                .chatDao().getChatById(chatId)?.isArchived == true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error checking archive in fallback", e)
                        false
                    }
                    
                    if (isArchived) {
                        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        notificationManager?.cancel("chat_$chatId", 0)
                        return
                    }
                }
                
                sendNotification(notification.title, notification.body, notifData)
            }
            }
        } finally {
            runCatching { wakeLock?.release() }
        }
    }

    override fun onNewToken(token: String) {
        sendRegistrationToServer(token)
    }

    private fun handleNow(data: Map<String, String>) {
        val title = data["title"] ?: data["sender_name"] ?: data["senderName"] ?: "New Message"
        val body = data["body"] ?: data["text"] ?: "New message"
        sendNotification(title, body, data)
        
        // Schedule background media download if applicable
        scheduleMediaDownloadIfNeeded(data)
    }

    private fun persistIncomingMessageToLocalStore(data: Map<String, String>) {
        val messageId = (data["messageId"] ?: data["id"]).orEmpty().trim()
        val chatId = (data["chat_id"] ?: data["chatId"]).orEmpty().trim()
        val senderId = (data["other_user_id"] ?: data["senderId"] ?: data["sender_id"]).orEmpty().trim()
        if (messageId.isBlank() || chatId.isBlank() || senderId.isBlank()) {
            return
        }

        val type = runCatching { MessageType.valueOf((data["type"] ?: "TEXT").trim()) }
            .getOrDefault(MessageType.TEXT)
        val text = data["text"] ?: ""
        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()
        val otherUsername = data["title"] ?: data["sender_name"] ?: data["senderName"] ?: "Unknown"
        val otherUserAvatar = data["sender_avatar"]
            ?: data["senderAvatar"]
            ?: data["profileImageUrl"]
            ?: ""
        val replyToMessageId = data["replyToMessageId"] ?: data["reply_to_message_id"]
        val replyToText = data["replyToText"] ?: data["reply_to_text"]
        val replyToSenderId = data["replyToSenderId"] ?: data["reply_to_sender_id"]
        val replyToType = data["replyToType"] ?: data["reply_to_type"]
        val isViewing = ActiveChatManager.isCurrentlyViewing(chatId) && AppVisibilityTracker.isAppVisible
        val status = when (type) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME,
            MessageType.VIDEO,
            MessageType.AUDIO,
            MessageType.MEDIA_GROUP -> MessageStatus.DOWNLOADING
            else -> if (isViewing) MessageStatus.READ else MessageStatus.DELIVERED
        }

        runCatching {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(applicationContext)
                val messageDao = db.messageDao()
                val chatDao = db.chatDao()
                val existing = messageDao.getMessageById(messageId)
                val resolvedMediaItemsJson = if (type == MessageType.MEDIA_GROUP) {
                    val directJson = sanitizeIncomingMediaItemsJson(data["mediaItems"])
                    if (!directJson.isNullOrBlank()) {
                        directJson
                    } else {
                        val fetchedItems = fetchMediaGroupItemsFromFirestoreSync(chatId, messageId)
                        buildMediaGroupItemsJson(fetchedItems)
                    }
                } else {
                    data["mediaItems"]?.takeIf { it.isNotBlank() }
                }

                if (type == MessageType.MEDIA_GROUP && !resolvedMediaItemsJson.isNullOrBlank()) {
                    val previewWarmMessage = Message(
                        id = messageId,
                        chatId = chatId,
                        text = "Media",
                        senderId = senderId,
                        timestamp = timestamp,
                        status = status,
                        isIncoming = true,
                        type = MessageType.MEDIA_GROUP,
                        mediaItems = resolvedMediaItemsJson
                    )
                    withTimeoutOrNull(1_500L) {
                        MessagePreviewCacheManager.warmMessagesBlocking(
                            applicationContext,
                            listOf(previewWarmMessage)
                        )
                    } ?: MessagePreviewCacheManager.warmMessagesAsync(
                        applicationContext,
                        listOf(previewWarmMessage)
                    )
                }

                val localMessage = LocalMessage(
                    id = messageId,
                    chatId = chatId,
                    text = text,
                    senderId = senderId,
                    timestamp = timestamp,
                    status = status,
                    isIncoming = true,
                    type = type,
                    imageUrl = data["imageUrl"] ?: existing?.imageUrl,
                    audioUrl = data["audioUrl"] ?: existing?.audioUrl,
                    audioDuration = data["audioDuration"]?.toLongOrNull() ?: existing?.audioDuration ?: 0L,
                    videoUrl = data["videoUrl"] ?: existing?.videoUrl,
                    thumbnailUrl = data["thumbnailUrl"] ?: existing?.thumbnailUrl,
                    videoDuration = data["videoDuration"]?.toLongOrNull() ?: existing?.videoDuration,
                    fileSize = data["fileSize"]?.toLongOrNull() ?: existing?.fileSize,
                    contactName = data["contactName"] ?: existing?.contactName,
                    contactPhone = data["contactPhone"] ?: existing?.contactPhone,
                    localUri = existing?.localUri,
                    mediaWidth = existing?.mediaWidth ?: 0,
                    mediaHeight = existing?.mediaHeight ?: 0,
                    mediaItems = resolvedMediaItemsJson ?: existing?.mediaItems,
                    deliveredTimestamp = existing?.deliveredTimestamp ?: System.currentTimeMillis(),
                    readTimestamp = existing?.readTimestamp,
                    replyToMessageId = replyToMessageId ?: existing?.replyToMessageId,
                    replyToText = replyToText ?: existing?.replyToText,
                    replyToSenderId = replyToSenderId ?: existing?.replyToSenderId,
                    replyToType = replyToType ?: existing?.replyToType,
                    isEdited = existing?.isEdited ?: false,
                    editedAt = existing?.editedAt,
                    isDeletedForAll = existing?.isDeletedForAll ?: false,
                    deletedAt = existing?.deletedAt,
                    isVideoNote = data["isVideoNote"]?.toBooleanStrictOrNull() ?: (existing?.isVideoNote ?: false),
                    documentCaption = data["documentCaption"] ?: existing?.documentCaption,
                    linkPreviewTitle = data["linkPreviewTitle"] ?: existing?.linkPreviewTitle,
                    linkPreviewDomain = data["linkPreviewDomain"] ?: existing?.linkPreviewDomain,
                    linkPreviewDescription = data["linkPreviewDescription"] ?: existing?.linkPreviewDescription,
                    linkPreviewSiteName = data["linkPreviewSiteName"] ?: existing?.linkPreviewSiteName,
                    statusId = data["statusId"] ?: existing?.statusId,
                    statusOwnerId = data["statusOwnerId"] ?: existing?.statusOwnerId,
                    statusThumbnailUrl = data["statusThumbnailUrl"] ?: existing?.statusThumbnailUrl,
                    statusType = data["statusType"] ?: existing?.statusType,
                    statusText = data["statusText"] ?: existing?.statusText,
                    statusBgColor = data["statusBgColor"]?.toIntOrNull() ?: existing?.statusBgColor,
                    isForwarded = data["isForwarded"]?.toBooleanStrictOrNull() ?: (existing?.isForwarded ?: false)
                )
                messageDao.insertMessage(localMessage)
                val recentMessages = messageDao.getRecentMessages(chatId, 60)
                    .asReversed()
                    .map { it.toDomainMessage() }
                if (recentMessages.isNotEmpty()) {
                    recentMessages.forEach { it.warmUpForUi() }
                    MessageCacheManager.putSnapshot(
                        chatId = chatId,
                        recentMessages = recentMessages,
                        listItems = ChatTranscriptSnapshotBuilder.build(recentMessages),
                        source = "fcm_persist"
                    )
                }
                val previewWarmMessage = com.glyph.glyph_v3.data.models.Message(
                    id = localMessage.id,
                    chatId = localMessage.chatId,
                    text = localMessage.text,
                    senderId = localMessage.senderId,
                    timestamp = localMessage.timestamp,
                    status = localMessage.status,
                    isIncoming = localMessage.isIncoming,
                    type = localMessage.type,
                    imageUrl = localMessage.imageUrl,
                    audioUrl = localMessage.audioUrl,
                    audioDuration = localMessage.audioDuration,
                    videoUrl = localMessage.videoUrl,
                    thumbnailUrl = localMessage.thumbnailUrl,
                    videoDuration = localMessage.videoDuration,
                    fileSize = localMessage.fileSize,
                    contactName = localMessage.contactName,
                    contactPhone = localMessage.contactPhone,
                    localUri = localMessage.localUri,
                    mediaItems = localMessage.mediaItems,
                    deliveredTimestamp = localMessage.deliveredTimestamp,
                    isVideoNote = localMessage.isVideoNote,
                    documentCaption = localMessage.documentCaption,
                    linkPreviewTitle = localMessage.linkPreviewTitle,
                    linkPreviewDomain = localMessage.linkPreviewDomain,
                    linkPreviewDescription = localMessage.linkPreviewDescription,
                    linkPreviewSiteName = localMessage.linkPreviewSiteName,
                    statusId = localMessage.statusId,
                    statusOwnerId = localMessage.statusOwnerId,
                    statusThumbnailUrl = localMessage.statusThumbnailUrl,
                    statusType = localMessage.statusType,
                    statusText = localMessage.statusText,
                    statusBgColor = localMessage.statusBgColor,
                    isForwarded = localMessage.isForwarded
                )
                withTimeoutOrNull(1_500L) {
                    MessagePreviewCacheManager.warmMessagesBlocking(
                        applicationContext,
                        listOf(previewWarmMessage)
                    )
                } ?: MessagePreviewCacheManager.warmMessagesAsync(
                    applicationContext,
                    listOf(previewWarmMessage)
                )

                val existingChat = chatDao.getChatById(chatId)
                val isGroupChat = existingChat?.isGroup == true || com.glyph.glyph_v3.data.repo.GroupChatRepository.isGroupChatId(chatId)
                if (existingChat != null) {
                    if (!isGroupChat) {
                        chatDao.updateUserInfo(chatId, otherUsername, otherUserAvatar)
                    }
                    chatDao.updateLastMessage(chatId, text.ifBlank {
                        when (type) {
                            MessageType.IMAGE -> "Photo"
                            MessageType.VIDEO -> "Video"
                            MessageType.AUDIO -> "Audio"
                            MessageType.CONTACT -> "Contact"
                            MessageType.MEDIA_GROUP -> "Media"
                            else -> "Message"
                        }
                    }, timestamp, senderId, status.name)

                    if (existing == null) {
                        if (isViewing) {
                            chatDao.clearUnreadCount(chatId)
                        } else {
                            chatDao.incrementUnreadCount(chatId)
                        }
                    }
                } else {
                    chatDao.insertChat(
                        LocalChat(
                            id = chatId,
                            otherUserId = if (isGroupChat) "" else senderId,
                            otherUsername = if (isGroupChat) "" else otherUsername,
                            otherUserAvatar = if (isGroupChat) "" else otherUserAvatar,
                            lastMessage = text.ifBlank {
                                when (type) {
                                    MessageType.IMAGE -> "Photo"
                                    MessageType.VIDEO -> "Video"
                                    MessageType.AUDIO -> "Audio"
                                    MessageType.CONTACT -> "Contact"
                                    MessageType.MEDIA_GROUP -> "Media"
                                    else -> "Message"
                                }
                            },
                            lastMessageTimestamp = timestamp,
                            lastMessageSenderId = senderId,
                            lastMessageStatus = status.name,
                            unreadCount = if (isViewing) 0 else 1,
                            isGroup = isGroupChat,
                            groupName = if (isGroupChat) (data["group_name"] ?: otherUsername).orEmpty() else "",
                            groupIconUrl = if (isGroupChat) {
                                (data["group_icon_url"] ?: data["groupIconUrl"] ?: "").orEmpty()
                            } else {
                                ""
                            }
                        )
                    )
                }

            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist incoming FCM message locally", error)
        }
    }

    private fun LocalMessage.toDomainMessage(): Message {
        return Message(
            id = id,
            chatId = chatId,
            text = text,
            senderId = senderId,
            timestamp = timestamp,
            serverTimestamp = serverTimestamp,
            status = status,
            isIncoming = isIncoming,
            type = type,
            imageUrl = imageUrl,
            audioUrl = audioUrl,
            audioDuration = audioDuration,
            videoUrl = videoUrl,
            thumbnailUrl = thumbnailUrl,
            videoDuration = videoDuration,
            fileSize = fileSize,
            contactName = contactName,
            contactPhone = contactPhone,
            localUri = localUri,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            mediaItems = mediaItems,
            deliveredTimestamp = deliveredTimestamp,
            readTimestamp = readTimestamp,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.let { MessageType.valueOf(it) },
            isEdited = isEdited,
            editedAt = editedAt,
            isDeletedForAll = isDeletedForAll,
            deletedAt = deletedAt,
            isVideoNote = isVideoNote,
            documentCaption = documentCaption,
            linkPreviewTitle = linkPreviewTitle,
            linkPreviewDomain = linkPreviewDomain,
            linkPreviewDescription = linkPreviewDescription,
            linkPreviewSiteName = linkPreviewSiteName,
            statusId = statusId,
            statusOwnerId = statusOwnerId,
            statusThumbnailUrl = statusThumbnailUrl,
            statusType = statusType,
            statusText = statusText,
            statusBgColor = statusBgColor,
            isForwarded = isForwarded
        )
    }

    private fun sendDeliveryReceiptFromFcm(data: Map<String, String>) {
        val payload = parseDeliveryReceiptPayload(data) ?: run {
            return
        }

        DeliveryReceiptWorker.enqueue(
            context = applicationContext,
            chatId = payload.chatId,
            senderId = payload.senderId,
            messageId = payload.messageId,
            deliveredAt = payload.deliveredAt,
            trigger = "fcm_receive"
        )

        serviceScope.launch {
            val immediateSucceeded = runCatching {
                withTimeoutOrNull(2_500L) {
                    attemptDeliveryReceiptWrite(
                        payload = payload,
                        source = "fcm_inline",
                        authTimeoutMs = 1_500L
                    )
                } ?: false
            }.getOrElse { error ->
                Log.e(TAG, "Immediate FCM delivery receipt attempt failed", error)
                false
            }

            if (immediateSucceeded) {
                DeliveryReceiptWorker.cancel(applicationContext, payload.chatId, payload.messageId)
            }
        }
    }

    private fun parseDeliveryReceiptPayload(data: Map<String, String>): DeliveryReceiptPayload? {
        val messageId = (data["messageId"] ?: data["id"]).orEmpty().trim()
        val chatId = (data["chat_id"] ?: data["chatId"]).orEmpty().trim()
        val senderId = (data["other_user_id"] ?: data["senderId"] ?: data["sender_id"]).orEmpty().trim()
        if (messageId.isBlank() || chatId.isBlank() || senderId.isBlank()) {
            return null
        }

        return DeliveryReceiptPayload(
            chatId = chatId,
            senderId = senderId,
            messageId = messageId,
            deliveredAt = System.currentTimeMillis()
        )
    }

    private fun deliveryTraceKey(chatId: String, messageId: String): String {
        return "${chatId.takeLast(6)}/${messageId.take(8)}"
    }

    private suspend fun attemptDeliveryReceiptWrite(
        payload: DeliveryReceiptPayload,
        source: String,
        authTimeoutMs: Long
    ): Boolean {
        val trace = deliveryTraceKey(payload.chatId, payload.messageId)
        val recipientId = awaitAuthenticatedUserIdForFcm(timeoutMs = authTimeoutMs)
        if (recipientId.isNullOrBlank()) {
            return false
        }

        val startedAt = System.currentTimeMillis()
        FirebaseDatabase.getInstance()
            .reference
            .child("delivery_receipts")
            .child(payload.senderId)
            .child(payload.chatId)
            .child(payload.messageId)
            .setValue(
                mapOf(
                    "status" to "DELIVERED",
                    "recipientId" to recipientId,
                    "deliveredAt" to payload.deliveredAt
                )
            )
            .await()

        writeFirestoreDeliveryStateIfNotRead(payload)

        return true
    }

    private suspend fun writeFirestoreDeliveryStateIfNotRead(payload: DeliveryReceiptPayload) {
        val firestore = FirebaseFirestore.getInstance()
        val messageRef = firestore
            .collection("chats")
            .document(payload.chatId)
            .collection("messages")
            .document(payload.messageId)

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(messageRef)
            val currentStatus = snapshot.getString("status")
            if (currentStatus != "READ" && currentStatus != "PLAYED") {
                transaction.set(
                    messageRef,
                    mapOf(
                        "status" to "DELIVERED",
                        "deliveredTimestamp" to payload.deliveredAt,
                        "deliveredAt" to payload.deliveredAt
                    ),
                    SetOptions.merge()
                )
            }
            true
        }.await()
    }

    private suspend fun awaitAuthenticatedUserIdForFcm(timeoutMs: Long = 5_000L): String? {
        FirebaseAuth.getInstance().currentUser?.uid?.let { return it }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String?> { continuation ->
                val auth = FirebaseAuth.getInstance()
                var listener: FirebaseAuth.AuthStateListener? = null
                listener = FirebaseAuth.AuthStateListener { authState ->
                    val userId = authState.currentUser?.uid ?: return@AuthStateListener
                    listener?.let { auth.removeAuthStateListener(it) }
                    if (continuation.isActive) {
                        continuation.resume(userId)
                    }
                }

                auth.addAuthStateListener(listener)
                continuation.invokeOnCancellation {
                    listener?.let { auth.removeAuthStateListener(it) }
                }
            }
        }
    }
    
    /**
     * Schedule a background download for media messages.
     * This ensures media is downloaded even if the app is closed.
     */
    private fun scheduleMediaDownloadIfNeeded(data: Map<String, String>) {
        val messageId = data["messageId"] ?: data["id"] ?: return
        val chatId = data["chat_id"] ?: data["chatId"] ?: return
        val type = data["type"] ?: return
        
        val imageUrl = data["imageUrl"]
        val videoUrl = data["videoUrl"]
        val audioUrl = data["audioUrl"]
        val mediaItemsJson = data["mediaItems"]
        val fileSize = data["fileSize"]?.toLongOrNull() ?: 0L
        
        when (type) {
            "IMAGE", "GIF", "STICKER", "KLIPY_EMOJI", "MEME" -> {
                if (!imageUrl.isNullOrEmpty()) {
                    com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
                        context = applicationContext,
                        messageId = messageId,
                        chatId = chatId,
                        mediaType = com.glyph.glyph_v3.data.models.MessageType.IMAGE,
                        remoteUrl = imageUrl,
                        fileSize = fileSize
                    )
                }
            }
            "VIDEO" -> {
                if (!videoUrl.isNullOrEmpty()) {
                    com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
                        context = applicationContext,
                        messageId = messageId,
                        chatId = chatId,
                        mediaType = com.glyph.glyph_v3.data.models.MessageType.VIDEO,
                        remoteUrl = videoUrl,
                        fileSize = fileSize
                    )
                }
            }
            "AUDIO" -> {
                if (!audioUrl.isNullOrEmpty()) {
                    com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
                        context = applicationContext,
                        messageId = messageId,
                        chatId = chatId,
                        mediaType = com.glyph.glyph_v3.data.models.MessageType.AUDIO,
                        remoteUrl = audioUrl,
                        fileSize = fileSize
                    )
                }
            }
            "MEDIA_GROUP" -> {
                val items = parseMediaItems(messageId, mediaItemsJson)
                if (items.isNotEmpty()) {
                    // Persist items to the local DB so the adapter can render the collage
                    // immediately (before the per-item downloads complete).
                    saveMediaGroupItemsToDb(messageId, items)
                    scheduleMediaGroupDownloads(chatId = chatId, groupMessageId = messageId, items = items)
                    return
                }

                // Fallback: payload did not include mediaItems (size limits / older function / etc)
                Log.w(TAG, "MEDIA_GROUP missing mediaItems in FCM; fetching from Firestore: chatId=$chatId messageId=$messageId")
                fetchMediaGroupItemsFromFirestore(chatId = chatId, groupMessageId = messageId) { fetchedItems ->
                    if (fetchedItems.isEmpty()) {
                        Log.w(TAG, "MEDIA_GROUP Firestore fetch returned 0 items")
                        return@fetchMediaGroupItemsFromFirestore
                    }
                    // Persist items so the collage is visible right away
                    saveMediaGroupItemsToDb(messageId, fetchedItems)
                    scheduleMediaGroupDownloads(chatId = chatId, groupMessageId = messageId, items = fetchedItems)

                    // Also update the notification store so the user sees the collage once downloads complete
                    try {
                        UnreadMessageStore.removeMessagesByMessageId(chatId, messageId)
                    } catch (_: Exception) {
                        // ignore
                    }

                    addMediaGroupPreviewMessages(
                        chatId = chatId,
                        groupMessageId = messageId,
                        senderName = data["title"] ?: data["sender_name"] ?: data["senderName"] ?: "Unknown",
                        items = fetchedItems
                    )

                    try {
                        com.glyph.glyph_v3.data.service.ChatNotificationUpdater.refreshChatNotification(
                            context = applicationContext,
                            chatId = chatId
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "ChatNotificationUpdater refresh failed after MEDIA_GROUP fetch: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Persists the received MEDIA_GROUP items into the local DB message record so the adapter
     * can render the collage before (or instead of) the full downloads completing.
     * Only stores URL / type / fileSize — no sender-local paths or base64 blobs.
     */
    private fun saveMediaGroupItemsToDb(groupMessageId: String, items: List<FcmMediaItem>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = buildMediaGroupItemsJson(items) ?: return@launch
                val previewWarmMessage = Message(
                    id = groupMessageId,
                    chatId = "",
                    text = "Media",
                    senderId = "",
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.DOWNLOADING,
                    isIncoming = true,
                    type = MessageType.MEDIA_GROUP,
                    mediaItems = json
                )
                withTimeoutOrNull(1_500L) {
                    MessagePreviewCacheManager.warmMessagesBlocking(
                        applicationContext,
                        listOf(previewWarmMessage)
                    )
                } ?: MessagePreviewCacheManager.warmMessagesAsync(
                    applicationContext,
                    listOf(previewWarmMessage)
                )
                AppDatabase.getDatabase(applicationContext)
                    .messageDao()
                    .updateMessageMediaItems(groupMessageId, json)
            } catch (e: Exception) {
                Log.w(TAG, "saveMediaGroupItemsToDb failed for $groupMessageId: ${e.message}")
            }
        }
    }

    private fun buildMediaGroupItemsJson(items: List<FcmMediaItem>): String? {
        if (items.isEmpty()) return null
        val mediaItems = items.map { item ->
            MediaItem(
                url = item.url,
                localUri = null,
                type = if (item.type.equals("VIDEO", ignoreCase = true)) MediaType.VIDEO else MediaType.IMAGE,
                thumbnailUrl = item.thumbnailUrl,
                thumbnailBase64 = item.thumbnailBase64,
                fileSize = item.fileSize
            )
        }
        return Message.mediaItemsToJson(mediaItems)
    }

    private suspend fun fetchMediaGroupItemsFromFirestoreSync(
        chatId: String,
        groupMessageId: String
    ): List<FcmMediaItem> {
        return try {
            repeat(2) { attempt ->
                val snap = FirebaseFirestore.getInstance()
                    .collection("chats")
                    .document(chatId)
                    .collection("messages")
                    .document(groupMessageId)
                    .get()
                    .await()
                val items = parseMediaItemsFromFirestore(snap.get("mediaItems"), groupMessageId)
                if (items.isNotEmpty()) return items
                if (attempt == 0) delay(500L)
            }
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchMediaGroupItemsFromFirestoreSync failed for $groupMessageId: ${e.message}")
            emptyList()
        }
    }

    private fun sanitizeIncomingMediaItemsJson(mediaItemsJson: String?): String? {
        if (mediaItemsJson.isNullOrBlank()) return null
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<MediaItem>>() {}.type
            val sanitized = (com.google.gson.Gson().fromJson<List<MediaItem>>(mediaItemsJson, type) ?: emptyList()).map { item ->
                val safeLocalUri = item.localUri?.takeIf { candidate ->
                    candidate.isNotBlank() && runCatching {
                        val path = android.net.Uri.parse(candidate).path ?: candidate
                        java.io.File(path).exists()
                    }.getOrDefault(false)
                }
                item.copy(localUri = safeLocalUri)
            }
            if (sanitized.isEmpty()) null else Message.mediaItemsToJson(sanitized)
        } catch (_: Exception) {
            null
        }
    }

    private fun scheduleMediaGroupDownloads(chatId: String, groupMessageId: String, items: List<FcmMediaItem>) {
        if (items.isNotEmpty()) {
            MediaProgressManager.updateProgress(
                groupMessageId,
                0f,
                isUploading = false,
                totalBytes = items.size.toLong(),
                transferredBytes = 0L
            )
        }
        items.forEachIndexed { index, item ->
            when (item.type) {
                "IMAGE" -> {
                    if (item.url.isNotBlank()) {
                        com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
                            context = applicationContext,
                            messageId = item.id,
                            chatId = chatId,
                            mediaType = com.glyph.glyph_v3.data.models.MessageType.IMAGE,
                            remoteUrl = item.url,
                            fileSize = item.fileSize,
                            groupMessageId = groupMessageId,
                            itemIndex = index
                        )
                    }
                }
                "VIDEO" -> {
                    if (item.url.isNotBlank()) {
                        com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
                            context = applicationContext,
                            messageId = item.id,
                            chatId = chatId,
                            mediaType = com.glyph.glyph_v3.data.models.MessageType.VIDEO,
                            remoteUrl = item.url,
                            fileSize = item.fileSize,
                            groupMessageId = groupMessageId,
                            itemIndex = index
                        )
                    }

                    // Optional: if a remote thumbnail URL exists, download it as IMAGE for notification preview
                    val thumb = item.thumbnailUrl
                    if (!thumb.isNullOrBlank()) {
                        com.glyph.glyph_v3.data.media.MediaDownloadWorker.scheduleDownload(
                            context = applicationContext,
                            messageId = "${item.id}_thumb",
                            chatId = chatId,
                            mediaType = com.glyph.glyph_v3.data.models.MessageType.IMAGE,
                            remoteUrl = thumb,
                            fileSize = 0L
                            // No groupMessageId/itemIndex for thumbnail — it's only for notification
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    private fun addMediaGroupPreviewMessages(
        chatId: String,
        groupMessageId: String,
        senderName: String,
        items: List<FcmMediaItem>
    ) {
        val maxPreviewItems = 4
        val preview = items.take(maxPreviewItems)
        preview.forEach { item ->
            when (item.type) {
                "IMAGE" -> {
                    UnreadMessageStore.addMessage(
                        chatId = chatId,
                        messageId = item.id,
                        messageType = "IMAGE",
                        message = "📷 Photo",
                        senderName = senderName,
                        mediaUrl = item.url.takeIf { it.isNotBlank() },
                        mimeType = "image/*"
                    )
                }
                "VIDEO" -> {
                    val thumbId = "${item.id}_thumb"
                    UnreadMessageStore.addMessage(
                        chatId = chatId,
                        messageId = thumbId,
                        messageType = "IMAGE",
                        message = "🎥 Video",
                        senderName = senderName,
                        mediaUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() },
                        mimeType = "image/*"
                    )
                }
                else -> Unit
            }
        }
        val remaining = items.size - preview.size
        if (remaining > 0) {
            UnreadMessageStore.addMessage(
                chatId = chatId,
                messageId = groupMessageId,
                messageType = "TEXT",
                message = "+$remaining more",
                senderName = senderName,
                mediaUrl = null,
                mimeType = null
            )
        }
    }

    private fun fetchMediaGroupItemsFromFirestore(
        chatId: String,
        groupMessageId: String,
        onResult: (List<FcmMediaItem>) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            onResult(emptyList())
            return
        }

        FirebaseFirestore.getInstance()
            .collection("chats")
            .document(chatId)
            .collection("messages")
            .document(groupMessageId)
            .get()
            .addOnSuccessListener { snap ->
                val raw = snap.get("mediaItems")
                val items = parseMediaItemsFromFirestore(raw, groupMessageId)
                if (items.isNotEmpty()) {
                    onResult(items)
                } else {
                    // Race condition: FCM can arrive before the sender's Firestore write
                    // completes. Retry once after 2 s to handle this window.
                    Log.w(TAG, "MEDIA_GROUP Firestore first attempt empty, retrying in 2s: $groupMessageId")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        FirebaseFirestore.getInstance()
                            .collection("chats")
                            .document(chatId)
                            .collection("messages")
                            .document(groupMessageId)
                            .get()
                            .addOnSuccessListener { retrySnap ->
                                onResult(parseMediaItemsFromFirestore(retrySnap.get("mediaItems"), groupMessageId))
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "MEDIA_GROUP Firestore retry fetch failed", e)
                                onResult(emptyList())
                            }
                    }, 2_000L)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "MEDIA_GROUP Firestore fetch failed", e)
                onResult(emptyList())
            }
    }

    private fun parseMediaItemsFromFirestore(raw: Any?, groupMessageId: String): List<FcmMediaItem> {
        // Firestore stores MEDIA_GROUP mediaItems as JSON string in this project.
        // It may also be written as a list of maps in some versions.
        return try {
            val arr: JSONArray = when (raw) {
                is String -> JSONArray(raw)
                is List<*> -> JSONArray(raw)
                else -> return emptyList()
            }

            val out = ArrayList<FcmMediaItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
                val type = obj.optString("type").takeIf { it.isNotBlank() } ?: "IMAGE"
                out.add(
                    FcmMediaItem(
                        id = "${groupMessageId}_$i",
                        url = url,
                        type = type,
                        thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                        thumbnailBase64 = obj.optString("thumbnailBase64").takeIf { it.isNotBlank() },
                        fileSize = obj.optLong("fileSize", 0L)
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Firestore mediaItems", e)
            emptyList()
        }
    }

    private data class FcmMediaItem(
        val id: String,
        val url: String,
        val type: String,
        val thumbnailUrl: String? = null,
        val thumbnailBase64: String? = null,
        val fileSize: Long = 0L
    )

    private fun parseMediaItems(groupMessageId: String, mediaItemsJson: String?): List<FcmMediaItem> {
        if (mediaItemsJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(mediaItemsJson)
            val out = ArrayList<FcmMediaItem>(arr.length())
            for (i in 0 until arr.length()) {
                val obj: JSONObject = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: "${groupMessageId}_$i"
                val url = obj.optString("url")
                val type = obj.optString("type")
                if (url.isBlank() || type.isBlank()) continue
                out.add(
                    FcmMediaItem(
                        id = id,
                        url = url,
                        type = type,
                        thumbnailUrl = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                        thumbnailBase64 = obj.optString("thumbnailBase64").takeIf { it.isNotBlank() },
                        fileSize = obj.optLong("fileSize", 0L)
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse mediaItems JSON", e)
            emptyList()
        }
    }

    private fun sendRegistrationToServer(token: String) {
        // Update the user's FCM token in Firestore
        val repo = FirebaseRepository()
        repo.updateFcmToken(token)
    }

    /**
     * Handle a Live Audio listen request arriving via FCM when the app is closed/backgrounded.
     * Android 14 blocks microphone access for pure-background starts, so we surface a
     * full-screen incoming-live-audio activity and start sharing from that foreground UI.
     */
    private fun handleLiveAudioRequestNotification(data: Map<String, String>) {
        val sessionId = data["session_id"] ?: return
        val broadcasterId = data["broadcaster_id"] ?: return
        val listenerId = data["listener_id"] ?: return
        val listenerName = data["listener_name"] ?: "Someone"


        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (myUid != broadcasterId) {
            Log.w(TAG, "LIVE_AUDIO_REQUEST: not for me (myUid=$myUid, broadcasterId=$broadcasterId)")
            return
        }

        LiveAudioIncomingNotificationHelper.show(
            context = applicationContext,
            sessionId = sessionId,
            listenerId = listenerId,
            listenerName = listenerName
        )
    }

    /**
     * Handle walkie-talkie request arriving via FCM when the app is backgrounded/killed.
     * If the chat with the initiator is archived, silently suppress the request.
     * Otherwise starts the foreground service (for mic access on Android 14+),
     * then delegates to the WalkieTalkieManager which auto-accepts and connects.
     */
    private fun handleCameraInviteNotification(data: Map<String, String>) {
        val chatId = (data["chat_id"] ?: "").trim()
        val senderUserId = (data["sender_user_id"] ?: "").trim()
        val senderName = (data["sender_name"] ?: "Someone").ifBlank { "Someone" }
        val requestId = data["request_id"] ?: ""
        val createdAt = data["created_at"]?.toLongOrNull() ?: 0L
        val notifTag = "camera_invite_$chatId"
        val notifId = notifTag.hashCode()

        if (chatId.isEmpty()) return

        // Stale guard — invite already expired on the client side
        if (createdAt > 0L && System.currentTimeMillis() - createdAt > CAMERA_INVITE_TTL_MS) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifTag, notifId)
            return
        }

        // Block check
        if (senderUserId.isNotEmpty() &&
            com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(senderUserId)) return

        // Suppress only when the user is already in the same chat with video mode active —
        // in that case the in-app invite prompt is already visible on screen.
        val isInSameChatWithVideo = AppVisibilityTracker.isAppVisible &&
            ActiveChatManager.isCurrentlyViewing(chatId) &&
            ActiveChatManager.mapVideoEnabledChatId == chatId
        if (isInSameChatWithVideo) return

        // --- Build notification with Yes / No action buttons ---
        // Tap notification → open chat
        val openChatIntent = com.glyph.glyph_v3.ui.chat.ChatActivity.newIntent(
            context = applicationContext,
            chatId = chatId,
            otherUserId = senderUserId
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openChatPi = PendingIntent.getActivity(
            applicationContext, notifId,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Yes" button → open ChatActivity directly via PendingIntent.getActivity.
        // A direct activity PendingIntent works even when the app is fully killed;
        // PendingIntent.getBroadcast + startActivity() from onReceive is blocked by
        // Android 10+ background activity restrictions when the app is not running.
        val acceptIntent = com.glyph.glyph_v3.ui.chat.ChatActivity.newIntent(
            context = applicationContext,
            chatId = chatId,
            otherUserId = senderUserId,
            enableCamera = true,
            cameraRequestId = requestId
        ).apply {
            // SINGLE_TOP ensures the existing ChatActivity instance receives onNewIntent
            // instead of launching a duplicate when the same chat is already open.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val acceptPi = PendingIntent.getActivity(
            applicationContext, notifId + 1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "No" button → decline silently
        val declineIntent = Intent(applicationContext,
            com.glyph.glyph_v3.data.service.NotificationActionReceiver::class.java).apply {
            action = com.glyph.glyph_v3.data.service.NotificationActionReceiver.ACTION_CAMERA_INVITE_DECLINE
            putExtra(com.glyph.glyph_v3.data.service.NotificationActionReceiver.EXTRA_CHAT_ID, chatId)
            putExtra(com.glyph.glyph_v3.data.service.NotificationActionReceiver.EXTRA_OTHER_USER_ID, senderUserId)
            putExtra(com.glyph.glyph_v3.data.service.NotificationActionReceiver.EXTRA_REQUEST_ID, requestId)
            putExtra(com.glyph.glyph_v3.data.service.NotificationActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            putExtra(com.glyph.glyph_v3.data.service.NotificationActionReceiver.EXTRA_NOTIFICATION_TAG, notifTag)
        }
        val declinePi = PendingIntent.getBroadcast(
            applicationContext, notifId + 2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Ensure notification channel exists
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CAMERA_INVITE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CAMERA_INVITE_CHANNEL_ID,
                    "Camera Invites",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Camera sharing requests" }
            )
        }

        val notif = NotificationCompat.Builder(applicationContext, CAMERA_INVITE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_invite)
            .setLargeIcon(run {
                // ic_camera_invite uses white fill (correct for status-bar small icon which
                // Android auto-tints). For the large icon bitmap we must override the color to
                // something dark so it's visible against the notification's light background.
                val d = androidx.core.content.ContextCompat.getDrawable(
                    applicationContext, R.drawable.ic_camera_invite
                )?.mutate()
                if (d != null) {
                    d.colorFilter = android.graphics.PorterDuffColorFilter(
                        android.graphics.Color.WHITE,
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                    val density = applicationContext.resources.displayMetrics.density
                    val size = (48 * density).toInt().coerceAtLeast(48)
                    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    d.setBounds(0, 0, size, size)
                    d.draw(canvas)
                    bmp
                } else null
            })
            .setContentTitle("Camera Request")
            .setContentText("$senderName wants to share camera with you")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$senderName wants you to enable your camera"))
            .setContentIntent(openChatPi)
            .addAction(0, "✅ Yes", acceptPi)
            .addAction(0, "❌ No", declinePi)
            .setAutoCancel(true)
            .setTimeoutAfter(
                if (createdAt > 0L) {
                    (CAMERA_INVITE_TTL_MS - (System.currentTimeMillis() - createdAt)).coerceAtLeast(1L)
                } else {
                    CAMERA_INVITE_TTL_MS
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(notifTag, notifId, notif)
    }

    private fun handleMissedCameraInviteNotification(data: Map<String, String>) {
        val chatId = (data["chat_id"] ?: "").trim()
        val senderUserId = (data["sender_user_id"] ?: "").trim()
        val senderName = (data["sender_name"] ?: "Someone").ifBlank { "Someone" }
        if (chatId.isEmpty()) return

        // Block check
        if (senderUserId.isNotEmpty() &&
            com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(senderUserId)) return

        // Don't show if user is already in this chat
        if (AppVisibilityTracker.isAppVisible && ActiveChatManager.isCurrentlyViewing(chatId)) return

        val notifTag = "missed_camera_invite_$chatId"
        val notifId = notifTag.hashCode()

        val openChatIntent = com.glyph.glyph_v3.ui.chat.ChatActivity.newIntent(
            context = applicationContext,
            chatId = chatId,
            otherUserId = senderUserId
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openChatPi = PendingIntent.getActivity(
            applicationContext, notifId,
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CAMERA_INVITE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CAMERA_INVITE_CHANNEL_ID,
                    "Camera Invites",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Camera sharing requests" }
            )
        }

        val notif = NotificationCompat.Builder(applicationContext, CAMERA_INVITE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_invite)
            .setContentTitle("Missed camera request")
            .setContentText("$senderName tried to share their camera with you")
            .setContentIntent(openChatPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        nm.notify(notifTag, notifId, notif)
    }

    private fun handleCameraInviteResponseNotification(data: Map<String, String>) {
        // If the app is visible the in-app "Declined" / video overlay handles the feedback.
        if (AppVisibilityTracker.isAppVisible) return

        val responderName = (data["responder_name"] ?: "Someone").ifBlank { "Someone" }
        val response = data["response"] ?: ""
        val responderUserId = (data["responder_user_id"] ?: "").trim()

        if (response != "accepted" && response != "declined") return

        val body = if (response == "accepted") {
            "$responderName accepted your camera invite 📷"
        } else {
            "$responderName declined your camera invite"
        }

        // Block check
        if (responderUserId.isNotEmpty() &&
            com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(responderUserId)) return

        val notifData = data.toMutableMap()
        notifData["other_user_id"] = responderUserId
        sendNotification("📷 Camera", body, notifData)
    }

    private fun handleWalkieTalkieRequestNotification(data: Map<String, String>) {
        if (AppVisibilityTracker.isAppVisible) {
            WalkieTalkieManager.getInstance(applicationContext).startWatchingForRequests()
            return
        }

        // Kick RTDB online IMMEDIATELY — after 30+ min idle the WebSocket is gone.
        // This starts the TLS + auth reconnect in parallel with all the blocking checks
        // below, so by the time primeSession / setAnswer are called the connection is
        // significantly closer to being ready (saves 2–4 s on cold start timing).
        try { com.google.firebase.database.FirebaseDatabase.getInstance().goOnline() } catch (_: Exception) {}

        val sessionId = data["session_id"] ?: return
        val initiatorId = data["initiator_id"] ?: return
        val responderId = data["responder_id"] ?: return
        val initiatorName = data["initiator_name"] ?: "Someone"
        val createdAt = data["created_at"]?.toLongOrNull() ?: 0L
        val initialOfferBase64 = data["offer_b64"].orEmpty()
        val initialOfferRevision = data["offer_revision"]?.toIntOrNull() ?: 0
        val hasInlineOffer = initialOfferBase64.isNotBlank() && initialOfferRevision > 0


        // ── Stale session guard: check TTL before any further processing ──
        if (createdAt > 0L) {
            val ageMs = System.currentTimeMillis() - createdAt
            if (ageMs > com.glyph.glyph_v3.data.models.WalkieTalkieSession.SESSION_REQUEST_TTL_MS) {
                Log.w(TAG, "WALKIE_TALKIE_REQUEST: session $sessionId is stale (${ageMs}ms old) — showing missed notification")
                WalkieTalkieIncomingNotificationHelper.cancel(applicationContext)
                WalkieTalkieIncomingNotificationHelper.showMissed(applicationContext, initiatorName, sessionId)
                return
            }
        }

        // ── Validate session is still active on the server ──
        // Triple-state: true=confirmed joinable, false=confirmed not joinable, null=RTDB timed out.
        // We must distinguish timeout from "confirmed cancelled" because on reconnect RTDB can
        // take several seconds. We never want to auto-accept when the state is unknown.
        val serverJoinable: Boolean? = if (hasInlineOffer) {
            Log.d(TAG, "WALKIE_TALKIE_REQUEST: inline offer present for $sessionId — deferring DB validation to manager")
            null
        } else try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                // Keep this short: manager-level validation still runs later, and a long
                // pre-check here delays auto-accept/connect during cold RTDB reconnects.
                kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                    WalkieTalkieRepository().getSessionIfJoinable(sessionId) != null
                }
                // Returns null on timeout — caller handles as "unknown"
            }
        } catch (e: Exception) {
            Log.w(TAG, "WALKIE_TALKIE_REQUEST: session validation failed for $sessionId, proceeding cautiously", e)
            null // Unknown — treat same as timeout
        }

        if (serverJoinable == false) {
            // Server explicitly confirmed session is gone/cancelled
            Log.w(TAG, "WALKIE_TALKIE_REQUEST: session $sessionId is no longer active on server — showing missed notification")
            WalkieTalkieIncomingNotificationHelper.cancel(applicationContext)
            WalkieTalkieIncomingNotificationHelper.showMissed(applicationContext, initiatorName, sessionId)
            return
        }

        if (serverJoinable == null && !hasInlineOffer) {
            // RTDB may still be cold here. Use Firestore-only session status as a second
            // opinion. Give Firestore a longer window (3 s) since it reconnects independently.
            val fallbackStatus = try {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                        WalkieTalkieRepository().getSessionFromFirestore(sessionId)?.status
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "WALKIE_TALKIE_REQUEST: Firestore fallback validation failed for $sessionId", e)
                null
            }

            if (fallbackStatus in com.glyph.glyph_v3.data.models.WalkieTalkieSession.TERMINAL_STATUSES) {
                Log.w(TAG, "WALKIE_TALKIE_REQUEST: fallback status=$fallbackStatus for $sessionId — showing missed notification")
                WalkieTalkieIncomingNotificationHelper.cancel(applicationContext)
                WalkieTalkieIncomingNotificationHelper.showMissed(applicationContext, initiatorName, sessionId)
                return
            }

            // If Firestore also timed out (null), proceed while the FCM TTL is still valid.
            // Android Doze can delay high-priority data delivery and Firebase reconnects;
            // treating an under-TTL session as missed here makes walkie-talkie appear to
            // work only when the receiver screen is already awake. The manager/session
            // observers still terminate promptly if the session is later confirmed stale.
            if (fallbackStatus == null && createdAt > 0L) {
                val ageMs = System.currentTimeMillis() - createdAt
                Log.d(TAG, "WALKIE_TALKIE_REQUEST: session $sessionId age=${ageMs}ms, proceeding despite DB timeouts")
            }
        }
        // serverJoinable == true  → confirmed valid → proceed normally
        // serverJoinable == null  → RTDB timed out but Firestore confirmed or session is fresh

        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (myUid != responderId) {
            Log.w(TAG, "WALKIE_TALKIE_REQUEST: not for me (myUid=$myUid, responderId=$responderId)")
            return
        }

        CoroutineScope(Dispatchers.Default).launch {
            runCatching { com.glyph.glyph_v3.data.webrtc.WalkieTalkiePeerClient.prewarm(applicationContext) }
                .onFailure { error -> Log.w(TAG, "WALKIE_TALKIE_REQUEST: WebRTC prewarm failed", error) }
        }

        // Derive chatId and check if the chat is archived — suppress fully if so
        val chatId = if (initiatorId < responderId) "${initiatorId}_${responderId}"
                     else "${responderId}_${initiatorId}"
        val localChat = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                com.glyph.glyph_v3.data.local.AppDatabase.getDatabase(applicationContext)
                    .chatDao().getChatById(chatId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WALKIE_TALKIE_REQUEST: archive check failed for chat $chatId", e)
            null
        }
        val isArchived = localChat?.isArchived == true

        if (isArchived) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                WalkieTalkieRepository().markSessionRinging(sessionId)
            }.onFailure { error ->
                Log.w(TAG, "WALKIE_TALKIE_REQUEST: failed to mark session ringing", error)
            }
        }

        val shouldAutoAccept = try {
            runBlocking(Dispatchers.IO) {
                val autoAcceptRepository = WalkieTalkieAutoAcceptSettingsRepository()
                val settings = autoAcceptRepository.getSettings(applicationContext)
                if (!settings.enabled) {
                    false
                } else when (settings.scope) {
                    WalkieTalkieAutoAcceptScope.SELECTED_USERS -> initiatorId in settings.allowedUserIds
                    WalkieTalkieAutoAcceptScope.CONTACTS -> {
                        localChat != null || kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                            autoAcceptRepository.shouldAutoAcceptIncomingCall(applicationContext, initiatorId)
                        } == true
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "WALKIE_TALKIE_REQUEST: failed to evaluate auto-accept policy", error)
            false
        }

        if (shouldAutoAccept && serverJoinable != false) {
            // Auto-accept when the server either CONFIRMED the session is joinable (true)
            // or the RTDB check timed out (null — device just woke from sleep, reconnecting).
            // We must NOT show the incoming-call screen in auto-accept mode; the manager's
            // internal validation in handleIncomingRequest will catch a stale session and
            // show a missed notification if needed.
            // Only skip auto-accept when the server explicitly confirms the session is gone (false).
            val notificationText = if (serverJoinable == true) {
                "Receiving live audio from $initiatorName"
            } else {
                "Incoming walkie-talkie from $initiatorName"
            }
            LiveAudioForegroundService.start(
                context = applicationContext,
                mode = LiveAudioForegroundService.MODE_WALKIE_TALKIE,
                notificationTitle = "Walkie-Talkie Active",
                notificationText = notificationText,
                showDisableAutoAcceptAction = true,
                requiresMicrophoneAccess = false,
                wtSessionId = sessionId,
                wtPeerId = initiatorId,
                wtPeerName = initiatorName
            )
            WalkieTalkieManager.getInstance(applicationContext)
                .handleIncomingRequestFromFcm(
                    sessionId = sessionId,
                    initiatorId = initiatorId,
                    initiatorName = initiatorName,
                    createdAt = createdAt,
                    initialOfferBase64 = initialOfferBase64,
                    initialOfferRevision = initialOfferRevision,
                    backgroundReceiveOnly = true
                )
            return
        }

        val reportedToTelecom = GlyphTelecomManager.reportIncomingWalkieTalkie(
            context = applicationContext,
            sessionId = sessionId,
            initiatorName = initiatorName,
            initiatorId = initiatorId,
            offerBase64 = initialOfferBase64,
            offerRevision = initialOfferRevision,
            createdAt = createdAt
        )

        if (!reportedToTelecom) {
            WalkieTalkieIncomingNotificationHelper.show(
                context = applicationContext,
                sessionId = sessionId,
                initiatorId = initiatorId,
                initiatorName = initiatorName,
                createdAt = createdAt,
                offerBase64 = initialOfferBase64,
                offerRevision = initialOfferRevision
            )
        }
    }

    /**
     * Handle incoming group call invitation FCM push.
     * Shows an incoming call notification that launches GroupCallActivity when accepted.
     */
    private fun handleGroupCallInvitation(data: Map<String, String>) {
        val groupCallId = data["group_call_id"] ?: return
        val callerName = data["caller_name"] ?: "Unknown"
        val callerAvatar = data["caller_avatar"] ?: ""
        val callTypeStr = data["call_type"] ?: "VIDEO"
        val callType = if (callTypeStr == "VIDEO") {
            com.glyph.glyph_v3.data.models.CallType.VIDEO
        } else {
            com.glyph.glyph_v3.data.models.CallType.VOICE
        }
        val participantCount = data["participant_count"] ?: "2"


        CoroutineScope(Dispatchers.Main).launch {
            // Check if already in a call
            val activeCallState = CallManager.callState.value
            val isInGroupCall = com.glyph.glyph_v3.data.webrtc.GroupCallManager.isInGroupCall.value
            if (isInGroupCall) {
                return@launch
            }
            if (activeCallState in listOf(CallState.INITIATING, CallState.RINGING, CallState.ACCEPTED, CallState.CONNECTED)) {
                val myUid = FirebaseAuth.getInstance().currentUser?.uid
                if (myUid != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            com.glyph.glyph_v3.data.repo.GroupCallSignalingRepository
                                .updateParticipantStatus(groupCallId, myUid, "busy")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to mark busy for group call $groupCallId", e)
                        }
                    }
                }
                return@launch
            }

            // Check block status
            val callerUserId = data["caller_user_id"] ?: ""
            if (callerUserId.isNotBlank() && com.glyph.glyph_v3.data.repo.BlockRepository.fetchBlockStatus(callerUserId).isBlocked) {
                return@launch
            }

            // Show incoming call notification that launches GroupCallActivity on accept
            showGroupCallNotification(
                groupCallId = groupCallId,
                callerName = callerName,
                callerAvatar = callerAvatar,
                callType = callType,
                participantCount = participantCount
            )
        }
    }

    private suspend fun showGroupCallNotification(
        groupCallId: String,
        callerName: String,
        callerAvatar: String,
        callType: com.glyph.glyph_v3.data.models.CallType,
        participantCount: String
    ) {
        CallForegroundService.ensureNotificationChannels(applicationContext)
        val channelId = CallForegroundService.currentIncomingChannelId

        // Load avatar
        val rawBitmap: Bitmap? = if (callerAvatar.isNotEmpty()) {
            try {
                withContext(Dispatchers.IO) {
                    Glide.with(applicationContext)
                        .asBitmap()
                        .load(callerAvatar)
                        .override(256, 256)
                        .centerCrop()
                        .submit()
                        .get()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load group call caller avatar", e)
                null
            }
        } else null
        val avatarBitmap = rawBitmap?.let { com.glyph.glyph_v3.data.service.ChatNotificationHelper.circularize(it) }

        // Accept intent → launches GroupCallActivity
        val acceptIntent = Intent(applicationContext, com.glyph.glyph_v3.ui.calls.GroupCallActivity::class.java).apply {
            putExtra(com.glyph.glyph_v3.ui.calls.GroupCallActivity.EXTRA_GROUP_CALL_ID, groupCallId)
            putExtra(com.glyph.glyph_v3.ui.calls.GroupCallActivity.EXTRA_CALL_TYPE, callType.name)
            putExtra(com.glyph.glyph_v3.ui.calls.GroupCallActivity.EXTRA_IS_JOINING, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val acceptPending = PendingIntent.getActivity(
            applicationContext,
            110,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline intent → broadcast receiver
        val declineIntent = Intent(applicationContext, CallActionReceiver::class.java).apply {
            action = CallNotificationHelper.ACTION_DECLINE_GROUP_CALL
            putExtra("group_call_id", groupCallId)
        }
        val declinePending = PendingIntent.getBroadcast(
            applicationContext, 111, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Full-screen intent (same as accept — opens GroupCallActivity)
        val fullScreenPending = PendingIntent.getActivity(
            applicationContext,
            112,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callTypeLabel = if (callType == com.glyph.glyph_v3.data.models.CallType.VIDEO) {
            "Group video call • $participantCount participants"
        } else {
            "Group voice call • $participantCount participants"
        }

        val callerPerson = androidx.core.app.Person.Builder()
            .setName(callerName)
            .apply {
                if (avatarBitmap != null) setIcon(IconCompat.createWithBitmap(avatarBitmap))
            }
            .build()

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_call)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePending, acceptPending))
            .setContentText(callTypeLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(35_000L)
            .setContentIntent(fullScreenPending)
            .setFullScreenIntent(fullScreenPending, true)

        if (avatarBitmap != null) {
            builder.setLargeIcon(avatarBitmap)
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        com.glyph.glyph_v3.utils.CallLockScreenHelper.pulseScreenWake(applicationContext, "$TAG:GroupCallWake")
        manager.notify(CallNotificationHelper.GROUP_CALL_NOTIFICATION_ID, builder.build())
    }

    /**
     * Handle STATUS_UPDATE FCM push — delivered when a contact posts a new status
     * and the current user has opted in. Works whether the app is running or killed.
     *
     * Also marks the status ID as known in SharedPreferences so the foreground
     * Firestore listener (in StatusRepository) does not re-fire the same notification
     * when the user opens the app.
     */
    private fun handleStatusUpdateNotification(data: Map<String, String>) {
        val statusId = data["statusId"].orEmpty().trim()
        val publisherUserId = data["publisherUserId"].orEmpty().trim()
        val publisherName = data["publisherName"].orEmpty().ifEmpty { "A contact" }
        val statusTypeRaw = data["statusType"].orEmpty().uppercase()
        val mediaUrl = data["mediaUrl"].orEmpty()
        val thumbnailUrl = data["thumbnailUrl"].orEmpty()
        val timestamp = data["timestamp"]?.toLongOrNull() ?: System.currentTimeMillis()

        if (statusId.isEmpty() || publisherUserId.isEmpty()) {
            Log.w(TAG, "STATUS_UPDATE FCM missing required fields, skipping")
            return
        }

        // Mark the status as already-known so the foreground listener won't re-notify
        StatusNotificationPrefs.markStatusIdsKnownSync(
            applicationContext,
            publisherUserId,
            setOf(statusId)
        )

        val statusType = runCatching { StatusType.valueOf(statusTypeRaw) }.getOrDefault(StatusType.TEXT)
        val status = Status(
            id = statusId,
            userId = publisherUserId,
            type = statusType,
            mediaUrl = mediaUrl,
            thumbnailUrl = thumbnailUrl,
            timestamp = timestamp
        )

        // Block onMessageReceived until the status is ready for viewing and the
        // notification is posted, so tapping the notification opens a prepared status.
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(25_000L) {
                try {
                    val preparedStatus = prepareStatusForNotification(status)
                    try {
                        StatusRepository.primeIncomingContactStatus(preparedStatus, publisherName)
                    } catch (primeError: Exception) {
                        Log.w(TAG, "Failed to prime contact status before notification", primeError)
                    }
                    StatusUpdateNotificationHelper.post(applicationContext, publisherName, preparedStatus)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to post STATUS_UPDATE notification", e)
                }
            }
        }
    }

    private suspend fun prepareStatusForNotification(status: Status): Status {
        val snapshot = runCatching {
            FirebaseFirestore.getInstance()
                .collection("statuses")
                .document(status.id)
                .get()
                .await()
        }.getOrNull()

        val resolvedStatus = if (snapshot != null && snapshot.exists()) {
            val type = (snapshot.getString("type") ?: status.type.name)
                .let { raw -> runCatching { StatusType.valueOf(raw.uppercase()) }.getOrDefault(status.type) }
            status.copy(
                type = type,
                mediaUrl = snapshot.getString("mediaUrl").orEmpty().ifBlank { status.mediaUrl },
                thumbnailUrl = snapshot.getString("thumbnailUrl").orEmpty().ifBlank { status.thumbnailUrl },
                timestamp = snapshot.getLong("timestamp") ?: status.timestamp,
                expiresAt = snapshot.getLong("expiresAt") ?: status.expiresAt
            )
        } else {
            status
        }

        if (resolvedStatus.type != StatusType.TEXT && resolvedStatus.mediaUrl.isNotBlank()) {
            try {
                StatusCacheManager.getLocalPath(resolvedStatus)
                if (resolvedStatus.type == StatusType.VIDEO) {
                    StatusCacheManager.getLocalThumbnailPath(resolvedStatus)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Status prefetch failed before notification for ${resolvedStatus.id}", e)
            }
        }

        return resolvedStatus
    }

    /**
     * Handle incoming call FCM push.
     * Shows full-screen incoming call notification + launches IncomingCallActivity.
     */
    private fun handleIncomingCallNotification(data: Map<String, String>) {
        val callId = data["call_id"] ?: return
        val callerId = data["caller_id"].orEmpty()
        val callerName = data["caller_name"] ?: "Unknown"
        val callerPhone = data["caller_phone"]
            ?: data["phone_number"]
            ?: data["caller_number"]
            ?: ""
        val callerAvatar = data["caller_avatar"] ?: ""
        val callTypeStr = data["call_type"] ?: "VOICE"
        val createdAt = data["created_at"]?.toLongOrNull() ?: System.currentTimeMillis()
        val callType = if (callTypeStr == "VIDEO") {
            com.glyph.glyph_v3.data.models.CallType.VIDEO
        } else {
            com.glyph.glyph_v3.data.models.CallType.VOICE
        }


        if (callerId.isNotBlank() && com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(callerId)) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    CallSignalingRepository.updateCallStatus(callId, "ended")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to end blocked incoming call from cached state: $callId", error)
                }
            }
            return
        }

        if (isBusyForIncomingCall(callId)) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    CallSignalingRepository.updateCallStatus(callId, "busy")
                }.onFailure { error ->
                    Log.e(TAG, "Failed to mark incoming call busy: $callId", error)
                }
            }
            return
        }

        CallManager.cacheIncomingCallSignal(
            com.glyph.glyph_v3.data.models.CallData(
                callId = callId,
                callerId = callerId,
                callerName = callerName,
                callerPhone = callerPhone,
                callerAvatar = callerAvatar,
                type = callType.name,
                callMode = callType.name,
                status = "ringing",
                createdAt = createdAt
            )
        )
        CallSignalingRepository.primeTransport("incoming_call_fcm")

        val reportedToTelecom = GlyphTelecomManager.reportIncomingCall(
            context = applicationContext,
            callId = callId,
            callerName = callerName,
            callerPhone = callerPhone,
            callerAvatar = callerAvatar,
            callType = callType
        )

        if (!reportedToTelecom) {
            CoroutineScope(Dispatchers.Main).launch {
                CallNotificationHelper.showIncomingCallNotification(
                    context = applicationContext,
                    callId = callId,
                    callerName = callerName,
                    callerPhone = callerPhone,
                    callerAvatar = callerAvatar,
                    callType = callType
                )
            }
        }

        com.glyph.glyph_v3.data.webrtc.CallManager.startRingingObservation(
            callId = callId,
            callerName = callerName,
            callerAvatar = callerAvatar,
            callType = callType,
            context = applicationContext
        )

        CoroutineScope(Dispatchers.IO).launch {
            val callData = runCatching { CallSignalingRepository.getCallData(callId) }
                .onFailure { error -> Log.w(TAG, "Incoming call verification fetch failed: $callId", error) }
                .getOrNull()

            if (callData == null) {
                return@launch
            }

            CallManager.cacheIncomingCallSignal(callData)

            val resolvedCallerId = callData.callerId.ifBlank { callerId }
            if (resolvedCallerId.isNotBlank() && com.glyph.glyph_v3.data.repo.BlockRepository.fetchBlockStatus(resolvedCallerId).isBlocked) {
                runCatching { CallSignalingRepository.updateCallStatus(callId, "ended") }
                CallNotificationHelper.cancelIncomingNotification(applicationContext)
                GlyphTelecomManager.markCallDisconnected(callId, DisconnectCause.REJECTED)
                return@launch
            }

            if (callData.callState() != CallState.RINGING) {
                CallNotificationHelper.cancelIncomingNotification(applicationContext)
                GlyphTelecomManager.markCallDisconnected(callId, DisconnectCause.CANCELED)
            }
        }
    }

    private fun isBusyForIncomingCall(incomingCallId: String): Boolean {
        val activeState = CallManager.callState.value
        val activeCallId = CallManager.currentCallId

        if (activeCallId == incomingCallId) {
            return false
        }

        return when (activeState) {
            CallState.INITIATING,
            CallState.RINGING,
            CallState.ACCEPTED,
            CallState.CONNECTED -> activeCallId != null
            CallState.BUSY,
            CallState.NO_ANSWER,
            CallState.DECLINED,
            CallState.MISSED,
            CallState.ENDED,
            null -> false
        }
    }

    private fun sendNotification(title: String?, messageBody: String?, data: Map<String, String>) {
        val chatId = (data["chat_id"] ?: data["chatId"] ?: return).trim()

        // ── Check notification enable/disable setting ──
        val notificationsEnabled = runBlocking {
            com.glyph.glyph_v3.data.preferences.SettingsDataStore
                .notificationsEnabledFlow(applicationContext).first()
        }
        if (!notificationsEnabled) {
            return
        }

        val otherUserId = data["other_user_id"] ?: data["senderId"] ?: ""
        val otherUsername = title ?: data["sender_name"] ?: "Unknown"
        // Phase 7: in group chats the FCM payload sets title=groupName so otherUsername
        // above doubles as the conversation header. We still need the actual poster's
        // display name to attribute each MessagingStyle bubble correctly. For 1:1
        // chats both names collapse to the same value (sender == "the other person")
        // and behavior is unchanged.
        val isGroupNotification = (data["is_group"] ?: "").equals("true", ignoreCase = true)
        val groupName = data["group_name"]?.takeIf { it.isNotBlank() }
        val perMessageSenderName: String = if (isGroupNotification)
            (data["sender_name"]?.takeIf { it.isNotBlank() } ?: otherUsername)
        else
            otherUsername
        val senderAvatarUrl = data["sender_avatar"]
            ?: data["senderAvatar"]
            ?: data["profileImageUrl"]
            ?: ""
        val messageId = data["id"] ?: data["messageId"] ?: ""
        val type = data["type"] ?: "TEXT"
        val imageUrl = data["imageUrl"]?.takeIf { it.isNotEmpty() }
        val videoUrl = data["videoUrl"]?.takeIf { it.isNotEmpty() }
        val thumbnailUrl = data["thumbnailUrl"]?.takeIf { it.isNotEmpty() }
        val statusThumbnailUrl = data["statusThumbnailUrl"]?.takeIf { it.isNotEmpty() }
        val statusType = data["statusType"]?.takeIf { it.isNotEmpty() }
        val statusText = data["statusText"]?.takeIf { it.isNotEmpty() }
        val statusBgColor = data["statusBgColor"]?.toIntOrNull()
        val mediaItemsJson = data["mediaItems"]
        
        // For media notifications, determine the URL to display
        // - Images: use imageUrl directly
        // - Videos: prefer thumbnailUrl, fall back to videoUrl (we'll generate thumbnail)
        val mediaUrl: String? = when (type) {
            "IMAGE", "GIF", "STICKER", "KLIPY_EMOJI", "MEME" -> imageUrl
            "VIDEO" -> thumbnailUrl ?: videoUrl
            "MEDIA_GROUP" -> imageUrl ?: thumbnailUrl
            "STATUS_REPLY" -> statusThumbnailUrl
            else -> null
        }
        
        // Determine MIME type for media display
        val derivedMime: String? = when {
            type in setOf("IMAGE", "GIF", "STICKER", "KLIPY_EMOJI", "MEME") && imageUrl != null -> "image/*"
            type == "VIDEO" -> "image/jpeg" // We show thumbnail as image
            type == "MEDIA_GROUP" -> "image/*"
            type == "STATUS_REPLY" && statusThumbnailUrl != null -> "image/*"
            else -> null
        }
        
        
        val messageText = messageBody ?: when (type) {
            "IMAGE" -> "📷 Photo"
            "GIF" -> "GIF"
            "STICKER", "KLIPY_EMOJI" -> "Sticker"
            "MEME" -> "Meme"
            "VIDEO" -> "🎥 Video"
            "AUDIO" -> "🎵 Audio"
            "CONTACT" -> "👤 Contact"
            "MEDIA_GROUP" -> "📷 Media"
            "STATUS_REPLY" -> if (data["text"] == "❤️") "Liked your status" else "Replied to your status: ${data["text"].orEmpty()}"
            else -> "New message"
        }

        // Don't show notification if user is currently viewing this chat
        if (ActiveChatManager.isCurrentlyViewing(chatId)) {
            // If viewing, mark as read immediately (only if read receipts enabled)
            if (messageId.isNotEmpty()) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    val privacy = try {
                        com.glyph.glyph_v3.data.repo.PrivacySettingsRepository.getPrivacySettings()
                    } catch (_: Exception) {
                        com.glyph.glyph_v3.data.repo.PrivacySettingsRepository.PrivacySettings()
                    }
                    if (privacy.readReceipts) {
                        val repo = FirebaseRepository()
                        repo.markMessageAsRead(chatId, messageId)
                    }
                }
            }
            return
        }

        // Persist chat metadata so background workers can refresh notifications after media downloads
        if (otherUserId.isNotEmpty()) {
            UnreadMessageStore.setChatMeta(
                chatId = chatId,
                otherUserId = otherUserId,
                otherUsername = otherUsername,
                otherUserAvatarUrl = senderAvatarUrl.takeIf { it.isNotBlank() }
            )
        }

        if (type == "MEDIA_GROUP") {
            val items = parseMediaItems(messageId, mediaItemsJson)
            val maxPreviewItems = 4

            if (items.isNotEmpty()) {
                val preview = items.take(maxPreviewItems)
                preview.forEach { item ->
                    when (item.type) {
                        "IMAGE" -> {
                            UnreadMessageStore.addMessage(
                                chatId = chatId,
                                messageId = item.id,
                                messageType = "IMAGE",
                                message = "📷 Photo",
                                senderName = perMessageSenderName,
                                mediaUrl = item.url.takeIf { it.isNotBlank() },
                                mimeType = "image/*",
                                senderId = otherUserId.takeIf { it.isNotBlank() },
                                senderAvatarUrl = senderAvatarUrl.takeIf { it.isNotBlank() }
                            )
                        }
                        "VIDEO" -> {
                            val thumbId = "${item.id}_thumb"
                            UnreadMessageStore.addMessage(
                                chatId = chatId,
                                messageId = thumbId,
                                messageType = "IMAGE",
                                message = "🎥 Video",
                                senderName = perMessageSenderName,
                                mediaUrl = item.thumbnailUrl?.takeIf { it.isNotBlank() },
                                mimeType = "image/*",
                                senderId = otherUserId.takeIf { it.isNotBlank() },
                                senderAvatarUrl = senderAvatarUrl.takeIf { it.isNotBlank() }
                            )
                        }
                        else -> Unit
                    }
                }

                val remaining = items.size - preview.size
                if (remaining > 0) {
                    UnreadMessageStore.addMessage(
                        chatId = chatId,
                        messageId = messageId.takeIf { it.isNotBlank() },
                        messageType = "TEXT",
                        message = "+$remaining more",
                        senderName = perMessageSenderName,
                        mediaUrl = null,
                        mimeType = null,
                        senderId = otherUserId.takeIf { it.isNotBlank() },
                        senderAvatarUrl = senderAvatarUrl.takeIf { it.isNotBlank() }
                    )
                }
            } else {
                // Fallback
                UnreadMessageStore.addMessage(
                    chatId = chatId,
                    messageId = messageId.takeIf { it.isNotBlank() },
                    messageType = type,
                    message = messageText,
                    senderName = perMessageSenderName,
                    mediaUrl = mediaUrl,
                    mimeType = derivedMime,
                    senderId = otherUserId.takeIf { it.isNotBlank() },
                    senderAvatarUrl = senderAvatarUrl.takeIf { it.isNotBlank() }
                )
            }
        } else {
            // Add message to unread store (includes optional media metadata)
            UnreadMessageStore.addMessage(
                chatId = chatId,
                messageId = messageId.takeIf { it.isNotBlank() },
                messageType = type,
                message = messageText,
                senderName = perMessageSenderName,
                mediaUrl = mediaUrl,
                mimeType = derivedMime,
                senderId = otherUserId.takeIf { it.isNotBlank() },
                senderAvatarUrl = senderAvatarUrl.takeIf { it.isNotBlank() }
            )
        }

        val unreadMessages = UnreadMessageStore.getMessages(chatId)
        val quickStyleMessages = unreadMessages.map { msg ->
            val person = Person.Builder()
                .setName(msg.senderName)
                .build()
            NotificationCompat.MessagingStyle.Message(
                msg.text,
                msg.timestamp,
                person
            )
        }
        val notifPrefs = loadNotificationPrefs()

        ChatNotificationHelper.showChatNotification(
            context = this,
            chatId = chatId,
            otherUserId = otherUserId,
            senderName = otherUsername,
            messages = quickStyleMessages,
            avatarBitmap = null,
            statusPreviewBitmap = null,
            silent = false,
            prefs = notifPrefs,
            skipArchiveCheck = true,
            groupName = groupName
        )

        // Load avatar and media in background
        CoroutineScope(Dispatchers.IO).launch {
            val avatarBitmap = resolveAvatarBitmap(
                chatId = chatId,
                otherUserId = otherUserId,
                payloadAvatarUrl = senderAvatarUrl
            )
            val statusPreviewBitmap = if (type == "STATUS_REPLY") {
                resolveStatusPreviewBitmap(
                    statusThumbnailUrl = statusThumbnailUrl,
                    statusType = statusType,
                    statusText = statusText,
                    statusBgColor = statusBgColor
                )
            } else {
                null
            }
            val unreadMessages = UnreadMessageStore.getMessages(chatId)

            // Phase 10: in group chats every accumulated message can have a different
            // sender. Resolve a circular avatar bitmap for each unique senderId so
            // MessagingStyle bubbles render the correct face per line. 1:1 chats keep
            // using the single [avatarBitmap] above (perSenderAvatars stays empty).
            val perSenderAvatars: Map<String, Bitmap> = if (isGroupNotification) {
                val uniqueSenderIds = unreadMessages.mapNotNull { it.senderId?.takeIf { id -> id.isNotBlank() } }.toSet()
                val out = mutableMapOf<String, Bitmap>()
                for (uid in uniqueSenderIds) {
                    val payloadUrl = unreadMessages
                        .firstOrNull { it.senderId == uid && !it.senderAvatarUrl.isNullOrBlank() }
                        ?.senderAvatarUrl
                    val bmp = runCatching {
                        resolveAvatarBitmap(
                            chatId = chatId,
                            otherUserId = uid,
                            payloadAvatarUrl = payloadUrl
                        )
                    }.getOrNull()
                    if (bmp != null) out[uid] = ChatNotificationHelper.circularize(bmp)
                }
                out
            } else {
                emptyMap()
            }

            val styleMessages = unreadMessages.map { msg ->

                val perSenderIcon = if (isGroupNotification) {
                    msg.senderId?.let { perSenderAvatars[it] }?.let { IconCompat.createWithBitmap(it) }
                } else if (avatarBitmap != null && msg.senderName == otherUsername) {
                    IconCompat.createWithBitmap(avatarBitmap)
                } else {
                    null
                }

                val person = Person.Builder()
                    .setName(msg.senderName)
                    .apply {
                        if (msg.senderId?.isNotBlank() == true) setKey(msg.senderId)
                        if (perSenderIcon != null) setIcon(perSenderIcon)
                    }
                    .build()

                val styleMsg = NotificationCompat.MessagingStyle.Message(
                    msg.text,
                    msg.timestamp,
                    person
                )

                // Prefer local storage (downloaded by background worker) to avoid 404 after remote deletion
                val localMedia = resolveLocalNotificationMedia(chatId, msg)
                if (localMedia != null) {
                    val (uri, effectiveMime) = localMedia
                    styleMsg.setData(effectiveMime, uri)
                } else if (!msg.mediaUrl.isNullOrEmpty() && !msg.mimeType.isNullOrEmpty()) {
                    val media = downloadMedia(msg.mediaUrl, msg.mimeType)
                    if (media != null) {
                        val (uri, effectiveMime) = media
                        styleMsg.setData(effectiveMime, uri)
                    } else {
                        // Keep mediaUrl so a later notification rebuild can retry, or background download can refresh
                        Log.w(TAG, "Failed to download media for notification (will keep mediaUrl for later refresh)")
                    }
                }
                styleMsg
            }

            withContext(Dispatchers.Main) {
                ChatNotificationHelper.showChatNotification(
                    context = this@MyFirebaseMessagingService,
                    chatId = chatId,
                    otherUserId = otherUserId,
                    senderName = otherUsername,
                    messages = styleMessages,
                    avatarBitmap = avatarBitmap,
                    statusPreviewBitmap = statusPreviewBitmap,
                    silent = true,
                    prefs = notifPrefs,
                    skipArchiveCheck = true,
                    groupName = groupName
                )
            }
        }
    }

    private fun loadNotificationPrefs(): ChatNotificationPrefs {
        return ChatNotificationPrefs(
            soundUri = runBlocking {
                com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .notificationSoundFlow(this@MyFirebaseMessagingService).first()
            },
            vibrate = runBlocking {
                com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .notificationVibrateFlow(this@MyFirebaseMessagingService).first()
            },
            popup = runBlocking {
                com.glyph.glyph_v3.data.preferences.SettingsDataStore
                    .notificationPopupFlow(this@MyFirebaseMessagingService).first()
            }
        )
    }

    private fun resolveStatusPreviewBitmap(
        statusThumbnailUrl: String?,
        statusType: String?,
        statusText: String?,
        statusBgColor: Int?
    ): Bitmap? {
        statusThumbnailUrl?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                Glide.with(applicationContext)
                    .asBitmap()
                    .load(url)
                    .override(256, 256)
                    .centerCrop()
                    .submit()
                    .get()
            }.onSuccess { return it }
        }

        if (statusType == "TEXT" || statusBgColor != null || !statusText.isNullOrBlank()) {
            return buildTextStatusPreviewBitmap(
                text = statusText.orEmpty(),
                backgroundColor = statusBgColor ?: 0xFF1B5E20.toInt()
            )
        }

        return null
    }

    private fun buildTextStatusPreviewBitmap(text: String, backgroundColor: Int): Bitmap {
        val sizePx = 256
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)

        val previewText = text.trim().ifEmpty { "Status" }.take(18)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val x = sizePx / 2f
        val y = (sizePx / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(previewText, x, y, paint)
        return bitmap
    }

    private fun resolveLocalNotificationMedia(
        chatId: String,
        msg: UnreadMessageStore.MessageData
    ): Pair<android.net.Uri, String>? {
        val messageId = msg.messageId ?: return null
        val messageType = msg.messageType ?: return null

        val storageType = when (messageType) {
            "IMAGE" -> com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.IMAGE
            "VIDEO" -> com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.VIDEO
            else -> null
        } ?: return null

        val file = com.glyph.glyph_v3.data.media.MediaStorageManager.getMediaFile(
            applicationContext,
            chatId,
            messageId,
            storageType
        )

        if (!file.exists() || file.length() <= 0L) return null

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = when (messageType) {
            "IMAGE" -> "image/jpeg"
            "VIDEO" -> "video/mp4"
            else -> msg.mimeType ?: "application/octet-stream"
        }
        return Pair(uri, mime)
    }
    
    private fun downloadMedia(url: String, mimeType: String): Pair<android.net.Uri, String>? {
        // First check if we already have this media cached from a previous notification
        val imgCacheFile = File(cacheDir, "img_${url.hashCode()}.jpg")
        val thumbCacheFile = File(cacheDir, "thumb_${url.hashCode()}.jpg")
        val mediaCacheFile = File(cacheDir, "media_${url.hashCode()}.dat")
        
        // Return cached file if it exists and has content
        when {
            mimeType.startsWith("video") && thumbCacheFile.exists() && thumbCacheFile.length() > 0 -> {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", thumbCacheFile)
                return Pair(uri, "image/jpeg")
            }
            (mimeType.startsWith("image") || mimeType == "image/*") && imgCacheFile.exists() && imgCacheFile.length() > 0 -> {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imgCacheFile)
                return Pair(uri, "image/jpeg")
            }
            mediaCacheFile.exists() && mediaCacheFile.length() > 0 -> {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", mediaCacheFile)
                return Pair(uri, mimeType)
            }
        }
        
        // Not cached - download with retry logic for fresh uploads
        // Firebase Storage URLs may return 404 briefly after upload completes due to propagation delay
        var lastException: Exception? = null
        val maxRetries = 3
        val retryDelays = longArrayOf(1000L, 2000L, 3000L) // Escalating delays
        
        repeat(maxRetries) { attempt ->
            try {
                
                val file: File
                val effectiveMime: String
                
                // Always skip Glide's cache for notification downloads to avoid cached 404 responses
                // We save to our own cache file anyway
                
                when {
                    // For videos with video/* mime, generate thumbnail
                    mimeType.startsWith("video") -> {
                        val thumb = Glide.with(applicationContext)
                            .asBitmap()
                            .load(url)
                            .frame(0)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .skipMemoryCache(true)
                            .submit(512, 512) // Limit size for notification
                            .get()
                        file = thumbCacheFile
                        FileOutputStream(file).use { out ->
                            thumb.compress(CompressFormat.JPEG, 85, out)
                        }
                        effectiveMime = "image/jpeg"
                    }
                    // For images, use Glide for reliable download and caching
                    mimeType.startsWith("image") || mimeType == "image/*" -> {
                        val bitmap = Glide.with(applicationContext)
                            .asBitmap()
                            .load(url)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .skipMemoryCache(true)
                            .submit(1024, 1024) // Reasonable size for notification
                            .get()
                        file = imgCacheFile
                        FileOutputStream(file).use { out ->
                            bitmap.compress(CompressFormat.JPEG, 90, out)
                        }
                        effectiveMime = "image/jpeg"
                    }
                    else -> {
                        file = mediaCacheFile
                        if (!file.exists() || file.length() == 0L) {
                            val connection = URL(url).openConnection()
                            connection.connectTimeout = 10000
                            connection.readTimeout = 15000
                            connection.connect()
                            connection.getInputStream().use { input ->
                                FileOutputStream(file).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        effectiveMime = mimeType
                    }
                }
                
                if (file.exists() && file.length() > 0) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    return Pair(uri, effectiveMime)
                } else {
                    Log.e(TAG, "Media file is empty or doesn't exist")
                    return null
                }
            } catch (e: Exception) {
                lastException = e
                val is404 = e.message?.contains("404") == true || 
                            e.cause?.message?.contains("404") == true ||
                            e.toString().contains("404")
                
                if (is404 && attempt < maxRetries - 1) {
                    // 404 might mean file is still being uploaded - wait and retry with escalating delay
                    val delay = retryDelays.getOrElse(attempt) { 3000L }
                    Thread.sleep(delay)
                } else {
                    Log.e(TAG, "Error downloading media after ${attempt + 1} attempts: ${e.message}", e)
                    return null
                }
            }
        }
        
        return null
    }
    
    private fun loadBitmap(url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            // Download a plain square 256×256 bitmap.
            // createWithAdaptiveBitmap() in showChatNotification handles the circular clip.
            Glide.with(applicationContext)
                .asBitmap()
                .load(url)
                .override(256, 256)
                .centerCrop()
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .submit()
                .get()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar via Glide: $url", e)
            null
        }
    }

    private suspend fun resolveAvatarBitmap(
        chatId: String,
        otherUserId: String,
        payloadAvatarUrl: String?
    ): Bitmap? {
        if (otherUserId.isNotBlank()) {
            com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(otherUserId)
                ?.let { localPath ->
                    loadBitmap(localPath)?.let {
                        return it
                    }
                }
        }

        val candidateUrls = linkedSetOf<String>()
        fun addCandidate(url: String?) {
            val safeUrl = url?.trim().orEmpty()
            if (safeUrl.isNotEmpty()) candidateUrls.add(safeUrl)
        }

        addCandidate(payloadAvatarUrl)

        if (chatId.isNotBlank()) {
            try {
                val roomAvatarUrl = com.glyph.glyph_v3.data.local.AppDatabase
                    .getDatabase(applicationContext)
                    .chatDao()
                    .getChatById(chatId)
                    ?.otherUserAvatar
                addCandidate(roomAvatarUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read avatar fallback from Room for chat=$chatId", e)
            }
        }

        if (otherUserId.isNotBlank()) {
            try {
                val userDoc = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(otherUserId)
                    .get()
                    .await()
                addCandidate(userDoc.getString("profileImageUrl"))
                addCandidate(userDoc.getString("profileImageFullUrl"))
            } catch (e: Exception) {
                Log.w(TAG, "Could not read avatar fallback from Firestore for user=$otherUserId", e)
            }
        }

        for (candidateUrl in candidateUrls) {
            if (otherUserId.isNotBlank()) {
                runCatching {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheAvatar(
                        userId = otherUserId,
                        avatarUrl = candidateUrl,
                        context = applicationContext
                    )
                }.onFailure {
                    Log.w(TAG, "Avatar cache refresh failed for user=$otherUserId", it)
                }

                com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(otherUserId)
                    ?.let { localPath ->
                        loadBitmap(localPath)?.let {
                            return it
                        }
                    }
            }

            loadBitmap(candidateUrl)?.let {
                return it
            }
        }

        Log.w(TAG, "Notification avatar resolution failed for chat=$chatId user=$otherUserId")
        return null
    }
    
}
