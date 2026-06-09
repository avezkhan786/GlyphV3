package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import com.glyph.glyph_v3.data.local.dao.ChatDao
import com.glyph.glyph_v3.data.local.dao.MessageDao
import com.glyph.glyph_v3.data.local.entity.LocalChat
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class UnifiedChatRepository(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val context: Context
) {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()
    val currentUserId get() = auth.currentUser?.uid

    // ==================== LOCAL CHATS ====================
    
    fun getLocalChats(): Flow<List<LocalChat>> {
        return chatDao.getAllChats()
    }

    suspend fun getOrCreateLocalChat(chatId: String, otherUserId: String, otherUsername: String, otherUserAvatar: String): LocalChat {
        val existing = chatDao.getChatById(chatId)
        if (existing != null) {
            // Update user info if changed
            if (existing.otherUsername != otherUsername || existing.otherUserAvatar != otherUserAvatar) {
                chatDao.updateUserInfo(chatId, otherUsername, otherUserAvatar)
            }
            return existing.copy(otherUsername = otherUsername, otherUserAvatar = otherUserAvatar)
        }
        
        val newChat = LocalChat(
            id = chatId,
            otherUserId = otherUserId,
            otherUsername = otherUsername,
            otherUserAvatar = otherUserAvatar
        )
        chatDao.insertChat(newChat)
        return newChat
    }

    suspend fun clearUnreadCount(chatId: String) {
        chatDao.clearUnreadCount(chatId)
    }

    // ==================== MESSAGES ====================
    fun getMessages(chatId: String): Flow<List<Message>> {
        return messageDao.getMessages(chatId).map { localList ->
            localList.map { local ->
                Message(
                    id = local.id,
                    chatId = local.chatId,
                    text = local.text,
                    senderId = local.senderId,
                    timestamp = local.timestamp,
                    serverTimestamp = local.serverTimestamp,
                    status = local.status,
                    isIncoming = local.isIncoming,
                    type = local.type,
                    imageUrl = local.imageUrl,
                    audioUrl = local.audioUrl,
                    audioDuration = local.audioDuration,
                    videoUrl = local.videoUrl,
                    thumbnailUrl = local.thumbnailUrl,
                    videoDuration = local.videoDuration,
                    fileSize = local.fileSize,
                    contactName = local.contactName,
                    contactPhone = local.contactPhone,
                    localUri = local.localUri,
                    mediaItems = local.mediaItems,
                    deliveredTimestamp = local.deliveredTimestamp,
                    readTimestamp = local.readTimestamp,
                    linkPreviewTitle = local.linkPreviewTitle,
                    linkPreviewDomain = local.linkPreviewDomain,
                    statusId = local.statusId,
                    statusOwnerId = local.statusOwnerId,
                    statusThumbnailUrl = local.statusThumbnailUrl,
                    statusType = local.statusType,
                    statusText = local.statusText,
                    statusBgColor = local.statusBgColor,
                    isForwarded = local.isForwarded
                )
            }
        }
    }

    // 2. Send Message (Local -> Remote -> Update Local)
    suspend fun sendMessage(chatId: String, text: String) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // A. Save to Local DB (SENDING)
        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = text,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.TEXT
        )
        messageDao.insertMessage(localMessage)

        // B. Upload to Firebase
        val messageData = hashMapOf(
            "id" to messageId,
            "text" to text,
            "senderId" to userId,
            "timestamp" to timestamp,
            "status" to "SENT",
            "type" to "TEXT"
        )

        firestore.collection("chats").document(chatId)
            .collection("messages")
            .document(messageId) // Use same ID
            .set(messageData)
            .addOnSuccessListener {
                // C. Update Local DB (SENT)
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
                    // Update local chat's last message
                    chatDao.updateLastMessage(chatId, text, timestamp, userId, MessageStatus.SENT.name)
                }
            }
            .addOnFailureListener {
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                }
            }
    }

    // Send message with other user ID (creates chat if needed)
    suspend fun sendMessage(chatId: String, text: String, otherUserId: String, otherUsername: String, otherUserAvatar: String) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Ensure local chat exists
        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        // Save to Local DB (SENDING)
        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = text,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.TEXT
        )
        messageDao.insertMessage(localMessage)
        
        // Update local chat immediately
        chatDao.updateLastMessage(chatId, text, timestamp, userId, MessageStatus.SENDING.name)

        // Upload to Firebase (Standard Collection)
        val messageData = hashMapOf(
            "id" to messageId,
            "text" to text,
            "senderId" to userId,
            "timestamp" to timestamp,
            "status" to "SENT",
            "type" to "TEXT"
        )
        
        // Ensure chat document exists
        val chatData = hashMapOf(
            "participants" to listOf(userId, otherUserId),
            "lastMessage" to text,
            "lastMessageTimestamp" to timestamp,
            "lastMessageSenderId" to userId
        )
        firestore.collection("chats").document(chatId).set(chatData, com.google.firebase.firestore.SetOptions.merge())

        // Write to pending_messages FIRST for fastest delivery to receiver
        val pendingData = hashMapOf(
            "id" to messageId,
            "chatId" to chatId,
            "text" to text,
            "senderId" to userId,
            "receiverId" to otherUserId,
            "timestamp" to timestamp,
            "status" to "SENT",
            "type" to "TEXT"
        )
        
        // Use batch write for atomicity and speed
        val batch = firestore.batch()
        batch.set(firestore.collection("pending_messages").document(messageId), pendingData)
        batch.set(firestore.collection("chats").document(chatId).collection("messages").document(messageId), messageData)
        
        batch.commit()
            .addOnSuccessListener {
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
                    chatDao.updateLastMessage(chatId, text, timestamp, userId, MessageStatus.SENT.name)
                }
            }
            .addOnFailureListener {
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    chatDao.updateLastMessage(chatId, text, timestamp, userId, MessageStatus.FAILED.name)
                }
            }
    }

    // Send image attachment
    suspend fun sendImageMessage(
        chatId: String,
        imageUri: Uri,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        caption: String = ""
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Get file size
        var fileSize: Long = 0
        try {
            context.contentResolver.openFileDescriptor(imageUri, "r")?.use { descriptor ->
                fileSize = descriptor.statSize
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Ensure local chat exists for UI responsiveness
        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val messageText = caption.ifEmpty { "Photo" }
        val placeholderMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = messageText,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.IMAGE,
            imageUrl = null,
            localUri = imageUri.toString(),
            fileSize = fileSize
        )

        // Show immediately in local DB
        messageDao.insertMessage(placeholderMessage)
        chatDao.updateLastMessage(chatId, messageText, timestamp, userId, MessageStatus.SENDING.name)

        val storageRef = storage.reference.child("chat_images/$chatId/$messageId.jpg")

        storageRef.putFile(imageUri)
            .addOnSuccessListener {
                storageRef.downloadUrl
                    .addOnSuccessListener { downloadUri ->
                        val downloadUrl = downloadUri.toString()

                        val messageData = hashMapOf(
                            "id" to messageId,
                            "text" to messageText,
                            "senderId" to userId,
                            "timestamp" to timestamp,
                            "status" to "SENT",
                            "type" to "IMAGE",
                            "imageUrl" to downloadUrl,
                            "fileSize" to fileSize
                        )

                        val chatData = hashMapOf(
                            "participants" to listOf(userId, otherUserId),
                            "lastMessage" to "Photo",
                            "lastMessageTimestamp" to timestamp,
                            "lastMessageSenderId" to userId
                        )

                        firestore.collection("chats").document(chatId)
                            .set(chatData, com.google.firebase.firestore.SetOptions.merge())

                        // Use batch write for faster delivery
                        val pendingData = hashMapOf(
                            "id" to messageId,
                            "chatId" to chatId,
                            "text" to "Photo",
                            "senderId" to userId,
                            "receiverId" to otherUserId,
                            "timestamp" to timestamp,
                            "status" to "SENT",
                            "type" to "IMAGE",
                            "imageUrl" to downloadUrl,
                            "fileSize" to fileSize
                        )
                        
                        val batch = firestore.batch()
                        batch.set(firestore.collection("pending_messages").document(messageId), pendingData)
                        batch.set(firestore.collection("chats").document(chatId).collection("messages").document(messageId), messageData)
                        
                        batch.commit()
                            .addOnSuccessListener {
                                CoroutineScope(Dispatchers.IO).launch {
                                    messageDao.insertMessage(
                                        placeholderMessage.copy(
                                            imageUrl = downloadUrl,
                                            status = MessageStatus.SENT,
                                            fileSize = fileSize
                                        )
                                    )
                                    chatDao.updateLastMessage(chatId, "Photo", timestamp, userId, MessageStatus.SENT.name)
                                }
                            }
                            .addOnFailureListener {
                                CoroutineScope(Dispatchers.IO).launch {
                                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                                    chatDao.updateLastMessage(chatId, "Photo", timestamp, userId, MessageStatus.FAILED.name)
                                }
                            }
                    }
                    .addOnFailureListener {
                        CoroutineScope(Dispatchers.IO).launch {
                            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                            chatDao.updateLastMessage(chatId, "Photo", timestamp, userId, MessageStatus.FAILED.name)
                        }
                    }
            }
            .addOnFailureListener {
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    chatDao.updateLastMessage(chatId, "Photo", timestamp, userId, MessageStatus.FAILED.name)
                }
            }
    }

    // Send video attachment
    suspend fun sendVideoMessage(
        chatId: String,
        videoUri: Uri,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String,
        caption: String = ""
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Get file size and duration
        var fileSize: Long = 0
        var videoDuration: Long = 0
        try {
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { descriptor ->
                fileSize = descriptor.statSize
            }
            
            // Get video duration
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            videoDuration = durationStr?.toLongOrNull() ?: 0L
            retriever.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Ensure local chat exists for UI responsiveness
        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val messageText = caption.ifEmpty { "Video" }
        val placeholderMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = messageText,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.VIDEO,
            videoUrl = null,
            localUri = videoUri.toString(),
            fileSize = fileSize,
            videoDuration = videoDuration
        )

        // Show immediately in local DB
        messageDao.insertMessage(placeholderMessage)
        chatDao.updateLastMessage(chatId, messageText, timestamp, userId, MessageStatus.SENDING.name)

        val storageRef = storage.reference.child("chat_videos/$chatId/$messageId.mp4")

        storageRef.putFile(videoUri)
            .addOnSuccessListener {
                storageRef.downloadUrl
                    .addOnSuccessListener { downloadUri ->
                        val downloadUrl = downloadUri.toString()

                        val messageData = hashMapOf(
                            "id" to messageId,
                            "text" to messageText,
                            "senderId" to userId,
                            "timestamp" to timestamp,
                            "status" to "SENT",
                            "type" to "VIDEO",
                            "videoUrl" to downloadUrl,
                            "fileSize" to fileSize,
                            "videoDuration" to videoDuration
                        )

                        val chatData = hashMapOf(
                            "participants" to listOf(userId, otherUserId),
                            "lastMessage" to "Video",
                            "lastMessageTimestamp" to timestamp,
                            "lastMessageSenderId" to userId
                        )

                        firestore.collection("chats").document(chatId)
                            .set(chatData, com.google.firebase.firestore.SetOptions.merge())

                        // Use batch write for faster delivery
                        val pendingData = hashMapOf(
                            "id" to messageId,
                            "chatId" to chatId,
                            "text" to "Video",
                            "senderId" to userId,
                            "receiverId" to otherUserId,
                            "timestamp" to timestamp,
                            "status" to "SENT",
                            "type" to "VIDEO",
                            "videoUrl" to downloadUrl,
                            "fileSize" to fileSize,
                            "videoDuration" to videoDuration
                        )
                        
                        val batch = firestore.batch()
                        batch.set(firestore.collection("pending_messages").document(messageId), pendingData)
                        batch.set(firestore.collection("chats").document(chatId).collection("messages").document(messageId), messageData)
                        
                        batch.commit()
                            .addOnSuccessListener {
                                CoroutineScope(Dispatchers.IO).launch {
                                    messageDao.insertMessage(
                                        placeholderMessage.copy(
                                            videoUrl = downloadUrl,
                                            status = MessageStatus.SENT,
                                            fileSize = fileSize,
                                            videoDuration = videoDuration
                                        )
                                    )
                                    chatDao.updateLastMessage(chatId, "Video", timestamp, userId, MessageStatus.SENT.name)
                                }
                            }
                            .addOnFailureListener {
                                CoroutineScope(Dispatchers.IO).launch {
                                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                                    chatDao.updateLastMessage(chatId, "Video", timestamp, userId, MessageStatus.FAILED.name)
                                }
                            }
                    }
                    .addOnFailureListener {
                        CoroutineScope(Dispatchers.IO).launch {
                            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                            chatDao.updateLastMessage(chatId, "Video", timestamp, userId, MessageStatus.FAILED.name)
                        }
                    }
            }
            .addOnFailureListener {
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    chatDao.updateLastMessage(chatId, "Video", timestamp, userId, MessageStatus.FAILED.name)
                }
            }
    }

    // Send contact card
    suspend fun sendContactMessage(
        chatId: String,
        contactName: String,
        contactPhone: String,
        otherUserId: String,
        otherUsername: String,
        otherUserAvatar: String
    ) {
        val userId = currentUserId ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Ensure local chat exists
        getOrCreateLocalChat(chatId, otherUserId, otherUsername, otherUserAvatar)

        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = contactName,
            senderId = userId,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.CONTACT,
            contactName = contactName,
            contactPhone = contactPhone
        )

        messageDao.insertMessage(localMessage)
        chatDao.updateLastMessage(chatId, "Contact: $contactName", timestamp, userId, MessageStatus.SENDING.name)

        val messageData = hashMapOf(
            "id" to messageId,
            "text" to contactName,
            "senderId" to userId,
            "timestamp" to timestamp,
            "status" to "SENT",
            "type" to "CONTACT",
            "contactName" to contactName,
            "contactPhone" to contactPhone
        )

        val chatData = hashMapOf(
            "participants" to listOf(userId, otherUserId),
            "lastMessage" to "Contact: $contactName",
            "lastMessageTimestamp" to timestamp,
            "lastMessageSenderId" to userId
        )

        firestore.collection("chats").document(chatId)
            .set(chatData, com.google.firebase.firestore.SetOptions.merge())

        // Use batch write for faster delivery
        val pendingData = hashMapOf(
            "id" to messageId,
            "chatId" to chatId,
            "text" to contactName,
            "senderId" to userId,
            "receiverId" to otherUserId,
            "timestamp" to timestamp,
            "status" to "SENT",
            "type" to "CONTACT",
            "contactName" to contactName,
            "contactPhone" to contactPhone
        )
        
        val batch = firestore.batch()
        batch.set(firestore.collection("pending_messages").document(messageId), pendingData)
        batch.set(firestore.collection("chats").document(chatId).collection("messages").document(messageId), messageData)
        
        batch.commit()
            .addOnSuccessListener {
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
                    chatDao.updateLastMessage(chatId, "Contact: $contactName", timestamp, userId, MessageStatus.SENT.name)
                }
            }
            .addOnFailureListener {
                CoroutineScope(Dispatchers.IO).launch {
                    messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
                    chatDao.updateLastMessage(chatId, "Contact: $contactName", timestamp, userId, MessageStatus.FAILED.name)
                }
            }
    }

    // Sync messages for a specific chat (Real-time updates including status)
    fun syncMessages(chatId: String): com.google.firebase.firestore.ListenerRegistration {
        return firestore.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener
                
                for (doc in snapshot.documents) {
                    val senderId = doc.getString("senderId") ?: ""
                    val isIncomingFromBlocked = senderId.isNotEmpty() && senderId != currentUserId &&
                        com.glyph.glyph_v3.data.repo.BlockRepository.isBlockedByMe(senderId)
                    if (isIncomingFromBlocked) {
                        // Skip persisting blocked user's messages; do not touch status/receipts.
                        continue
                    }
                    val id = doc.id
                    val text = doc.getString("text") ?: ""
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    val statusStr = doc.getString("status") ?: "SENT"
                    val status = try { MessageStatus.valueOf(statusStr) } catch(e: Exception) { MessageStatus.SENT }
                    val typeStr = doc.getString("type") ?: "TEXT"
                    val type = try { MessageType.valueOf(typeStr) } catch(e: Exception) { MessageType.TEXT }
                    val imageUrl = doc.getString("imageUrl")
                    val audioUrl = doc.getString("audioUrl")
                    val audioDuration = doc.getLong("audioDuration") ?: 0L
                    val videoUrl = doc.getString("videoUrl")
                    val thumbnailUrl = doc.getString("thumbnailUrl")
                    val linkPreviewTitle = doc.getString("linkPreviewTitle")
                    val linkPreviewDomain = doc.getString("linkPreviewDomain")
                    val videoDuration = doc.getLong("videoDuration")
                    val fileSize = doc.getLong("fileSize")
                    val contactName = doc.getString("contactName")
                    val contactPhone = doc.getString("contactPhone")
                    
                    val isIncoming = senderId != currentUserId

                    val localMessage = LocalMessage(
                        id = id,
                        chatId = chatId,
                        text = text,
                        senderId = senderId,
                        timestamp = timestamp,
                        status = status,
                        isIncoming = isIncoming,
                        type = type,
                        imageUrl = imageUrl,
                        audioUrl = audioUrl,
                        audioDuration = audioDuration,
                        videoUrl = videoUrl,
                        thumbnailUrl = thumbnailUrl,
                        linkPreviewTitle = linkPreviewTitle,
                        linkPreviewDomain = linkPreviewDomain,
                        videoDuration = videoDuration,
                        fileSize = fileSize,
                        contactName = contactName,
                        contactPhone = contactPhone
                    )
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        // Preserve localUri if exists
                        val existing = messageDao.getMessageById(id)
                        val updatedMessage = localMessage.copy(
                            localUri = existing?.localUri
                        )
                        messageDao.insertMessage(updatedMessage)
                        
                        if (isIncoming) {
                             // Update chat last message if this is newer - incoming messages should show their actual status
                             chatDao.updateLastMessage(chatId, text, timestamp, senderId, status.name)
                        }
                    }
                }
            }
    }

    // ==================== SYNC (Firebase Relay) ====================

    // Helper to sync user info (avatar/username) from Firestore to Local DB
    fun syncChatUserInfo(chatId: String, otherUserId: String) {
        firestore.collection("users").document(otherUserId).get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username") ?: return@addOnSuccessListener
                val avatar = doc.getString("profileImageUrl") ?: ""
                
                CoroutineScope(Dispatchers.IO).launch {
                    chatDao.updateUserInfo(chatId, username, avatar)
                }
            }
            .addOnFailureListener { e ->
                Log.e("UnifiedChatRepo", "Failed to sync user info", e)
            }
    }

    // Start listening for incoming messages from Firebase relay
    fun startIncomingMessageSync(): com.google.firebase.firestore.ListenerRegistration? {
        val userId = currentUserId ?: return null

        // Listen for messages where current user is the receiver
        return firestore.collection("pending_messages")
            .whereEqualTo("receiverId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    Log.e("UnifiedChatRepo", "Error listening for incoming messages", error)
                    return@addSnapshotListener
                }

                // Process only newly added documents for speed
                for (change in snapshot.documentChanges) {
                    if (change.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) continue
                    
                    val doc = change.document
                    val id = doc.getString("id") ?: doc.id
                    val chatId = doc.getString("chatId") ?: continue
                    val text = doc.getString("text") ?: ""
                    val senderId = doc.getString("senderId") ?: ""
                    val timestamp = doc.getLong("timestamp") ?: 0L
                    val typeStr = doc.getString("type") ?: "TEXT"
                    val imageUrl = doc.getString("imageUrl")
                    val audioUrl = doc.getString("audioUrl")
                    val audioDuration = doc.getLong("audioDuration") ?: 0L
                    val videoUrl = doc.getString("videoUrl")
                    val thumbnailUrl = doc.getString("thumbnailUrl")
                    val linkPreviewTitle = doc.getString("linkPreviewTitle")
                    val linkPreviewDomain = doc.getString("linkPreviewDomain")
                    val videoDuration = doc.getLong("videoDuration")
                    val fileSize = doc.getLong("fileSize")
                    val contactName = doc.getString("contactName")
                    val contactPhone = doc.getString("contactPhone")
                    
                    // Safe parse message type
                    val type = try { MessageType.valueOf(typeStr) } catch(e: Exception) { 
                        Log.w("UnifiedChatRepo", "Unknown message type '$typeStr', defaulting to TEXT")
                        MessageType.TEXT 
                    }

                    // ===== MEDIA_GROUP SUPPORT: Parse mediaItems from Firestore =====
                    var mediaItemsJson: String? = null
                    if (type == MessageType.MEDIA_GROUP) {
                        @Suppress("UNCHECKED_CAST")
                        val mediaItemsList = doc.get("mediaItems") as? List<Map<String, Any?>>
                        if (mediaItemsList != null) {
                            mediaItemsJson = com.google.gson.Gson().toJson(mediaItemsList)
                            
                            // Log thumbnail data for debugging
                            mediaItemsList.forEachIndexed { index, item ->
                                val thumbData = item["thumbnailBase64"] as? String
                            }
                        } else {
                            Log.w("UnifiedChatRepo", "MEDIA_GROUP message $id has no mediaItems!")
                        }
                    }

                    val localMessage = LocalMessage(
                        id = id,
                        chatId = chatId,
                        text = text,
                        senderId = senderId,
                        timestamp = timestamp,
                        status = MessageStatus.DELIVERED,
                        isIncoming = true,
                        type = type,
                        imageUrl = imageUrl,
                        audioUrl = audioUrl,
                        audioDuration = audioDuration,
                        videoUrl = videoUrl,
                        thumbnailUrl = thumbnailUrl,
                        linkPreviewTitle = linkPreviewTitle,
                        linkPreviewDomain = linkPreviewDomain,
                        videoDuration = videoDuration,
                        fileSize = fileSize,
                        contactName = contactName,
                        contactPhone = contactPhone,
                        mediaItems = mediaItemsJson
                    )

                    // PRIORITY 1: Insert message to local DB IMMEDIATELY for fast UI update
                    CoroutineScope(Dispatchers.IO).launch {
                        val existing = messageDao.getMessageById(id)
                        val updatedMessage = localMessage.copy(localUri = existing?.localUri)
                        messageDao.insertMessage(updatedMessage)
                        
                        // ===== AUTO-DOWNLOAD FOR MEDIA_GROUP =====
                        if (type == MessageType.MEDIA_GROUP && mediaItemsJson != null) {
                            try {
                                val mediaItems = com.google.gson.Gson().fromJson(
                                    mediaItemsJson,
                                    object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
                                ) as? List<Map<String, Any?>>
                                
                                if (mediaItems != null && mediaItems.isNotEmpty()) {
                                    val downloadManager = com.glyph.glyph_v3.data.media.MediaDownloadManager.getInstance(context)
                                    
                                    mediaItems.forEachIndexed { index, item ->
                                        val url = item["url"] as? String
                                        val thumbBase64 = item["thumbnailBase64"] as? String
                                        val typeStr = item["type"] as? String
                                        val fileSize = (item["fileSize"] as? Number)?.toLong()
                                        
                                        if (!url.isNullOrEmpty()) {
                                            val itemIndex = index + 1
                                            
                                            val mediaType = if (typeStr == "VIDEO") 
                                                com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.VIDEO 
                                            else 
                                                com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.IMAGE
                                            
                                            downloadManager.queueDownload(
                                                messageId = "${id}_item_$itemIndex",
                                                chatId = chatId,
                                                mediaType = mediaType,
                                                remoteUrl = url,
                                                expectedSize = fileSize?.takeIf { it > 0 },
                                                itemIndex = itemIndex,
                                                onComplete = { localFile: File? ->
                                                    val success = localFile != null
                                                    val localPath = localFile?.absolutePath
                                                    if (success && localPath != null) {
                                                        // Update message with downloaded file path
                                                        CoroutineScope(Dispatchers.IO).launch {
                                                            val msg = messageDao.getMessageById(id)
                                                            if (msg != null && msg.mediaItems != null) {
                                                                val items = com.google.gson.Gson().fromJson(
                                                                    msg.mediaItems,
                                                                    object : com.google.gson.reflect.TypeToken<List<MutableMap<String, Any?>>>() {}.type
                                                                ) as? MutableList<MutableMap<String, Any?>>
                                                                
                                                                if (items != null && index < items.size) {
                                                                    items[index]["localPath"] = localPath
                                                                    val updatedJson = com.google.gson.Gson().toJson(items)
                                                                    messageDao.updateMessageMediaItems(id, updatedJson)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("UnifiedChatRepo", "Failed to trigger auto-download for $id", e)
                            }
                        }
                        
                        // Update or create local chat
                        val existingChat = chatDao.getChatById(chatId)
                        if (existingChat != null) {
                            // Incoming messages should show DELIVERED status
                            chatDao.updateLastMessage(chatId, text, timestamp, senderId, MessageStatus.DELIVERED.name)
                            chatDao.incrementUnreadCount(chatId)
                        } else {
                            fetchUserAndCreateChat(chatId, senderId, text, timestamp, MessageStatus.DELIVERED.name)
                        }
                    }
                    
                    // PRIORITY 2: Background cleanup - delete from relay and update status
                    doc.reference.delete()
                        .addOnSuccessListener { 
                            // Update status to DELIVERED in main chat collection
                            firestore.collection("chats").document(chatId)
                                .collection("messages").document(id)
                                .update("status", "DELIVERED")
                        }
                        .addOnFailureListener { Log.e("Sync", "Failed to delete message $id from relay") }
                }
            }
    }

    private fun fetchUserAndCreateChat(chatId: String, otherUserId: String, lastMessage: String, timestamp: Long, messageStatus: String = MessageStatus.DELIVERED.name) {
        firestore.collection("users").document(otherUserId).get()
            .addOnSuccessListener { doc ->
                val username = doc.getString("username") ?: "Unknown"
                val avatar = doc.getString("profileImageUrl") ?: ""
                
                CoroutineScope(Dispatchers.IO).launch {
                    val newChat = LocalChat(
                        id = chatId,
                        otherUserId = otherUserId,
                        otherUsername = username,
                        otherUserAvatar = avatar,
                        lastMessage = lastMessage,
                        lastMessageTimestamp = timestamp,
                        lastMessageSenderId = otherUserId,
                        lastMessageStatus = messageStatus,
                        unreadCount = 1
                    )
                    chatDao.insertChat(newChat)
                }
            }
            .addOnFailureListener { e ->
                Log.e("UnifiedChatRepo", "Failed to fetch user info for chat", e)
                // Create chat with unknown user
                CoroutineScope(Dispatchers.IO).launch {
                    val newChat = LocalChat(
                        id = chatId,
                        otherUserId = otherUserId,
                        otherUsername = "Unknown",
                        otherUserAvatar = "",
                        lastMessage = lastMessage,
                        lastMessageTimestamp = timestamp,
                        lastMessageSenderId = otherUserId,
                        lastMessageStatus = messageStatus,
                        unreadCount = 1
                    )
                    chatDao.insertChat(newChat)
                }
            }
    }

    // Mark all unread messages in a chat as READ
    fun markChatAsRead(chatId: String) {
        val userId = currentUserId ?: return

        // 1. Clear local unread count
        CoroutineScope(Dispatchers.IO).launch {
            chatDao.clearUnreadCount(chatId)
        }

        // 2. Update status on Firestore for all incoming messages that are not yet READ
        //    AND update readTimestamps — but ONLY if read receipts are enabled.
        CoroutineScope(Dispatchers.IO).launch {
            val privacySettings = try {
                PrivacySettingsRepository.getPrivacySettings()
            } catch (_: Exception) {
                PrivacySettingsRepository.PrivacySettings() // defaults (read receipts on)
            }
            val readReceiptsEnabled = privacySettings.readReceipts

            if (readReceiptsEnabled) {
                firestore.collection("chats").document(chatId)
                    .collection("messages")
                    .whereNotEqualTo("senderId", userId)
                    .get()
                    .addOnSuccessListener { snapshot ->
                        val batch = firestore.batch()
                        var updateCount = 0
                        for (doc in snapshot.documents) {
                            val status = doc.getString("status")
                            if (status != "READ") {
                                batch.update(doc.reference, "status", "READ")
                                updateCount++
                            }
                        }
                        if (updateCount > 0) {
                            batch.commit()
                                .addOnSuccessListener { }
                                .addOnFailureListener { e -> Log.e("UnifiedChatRepo", "Failed to mark chat as read", e) }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("UnifiedChatRepo", "Failed to query unread messages", e)
                    }

                // 3. Update readTimestamps field in chat document for read receipts
                val update = mapOf("readTimestamps.$userId" to System.currentTimeMillis())
                firestore.collection("chats").document(chatId)
                    .update(update)
                    .addOnFailureListener {
                        val data = mapOf("readTimestamps" to mapOf(userId to System.currentTimeMillis()))
                        firestore.collection("chats").document(chatId)
                            .set(data, com.google.firebase.firestore.SetOptions.merge())
                    }
            }
        }
    }

    // Legacy sync method for backward compatibility
    fun startSync(chatId: String) {
        // This is now handled by startIncomingMessageSync()
        // Keep for backward compatibility but redirect to new method
        startIncomingMessageSync()
    }
}
