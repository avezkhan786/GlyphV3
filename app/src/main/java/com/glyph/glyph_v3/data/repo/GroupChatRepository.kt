package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.local.dao.ChatDao
import com.glyph.glyph_v3.data.local.dao.MessageDao
import com.glyph.glyph_v3.data.local.entity.LocalChat
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.ui.share.LinkPreviewResolver
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Group chat repository — strictly additive to the 1:1 stack.
 *
 * Reuses the same Firestore path `/chats/{chatId}/messages/{messageId}` and Room tables
 * as 1:1 chats. The only group-specific surface area is:
 *   * group lifecycle (create / add / remove / leave / promote / demote / update info)
 *   * fan-out RTDB write to `/pending_messages/{recipientUid}/{messageId}` for every
 *     non-sender participant (single multi-path `updateChildren()` round-trip)
 *   * per-recipient `deliveredTo` / `readBy` arrays on the message document
 *   * `/groupTyping/{chatId}/{uid}` for broadcast typing state
 *
 * 1:1 code paths in [RealtimeMessageRepository] are intentionally untouched.
 */
class GroupChatRepository(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    @Suppress("unused") private val context: Context
) {
    companion object {
        private const val TAG = "GroupChatRepo"
        private const val GROUP_ID_PREFIX = "group_"
        private const val GROUP_ICON_STORAGE_PATH = "group_icons"
        private const val GROUP_TYPING_FRESHNESS_MS = 5000L
        const val MAX_GROUP_MEMBERS = 256

        private val gson = Gson()
        private val stringListType = object : TypeToken<List<String>>() {}.type

        fun newGroupChatId(): String = GROUP_ID_PREFIX + UUID.randomUUID().toString()

        fun isGroupChatId(chatId: String): Boolean = chatId.startsWith(GROUP_ID_PREFIX)

        internal fun encodeStringList(values: List<String>): String = gson.toJson(values)

        internal fun decodeStringList(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching { gson.fromJson<List<String>>(json, stringListType) ?: emptyList() }
                .getOrDefault(emptyList())
        }
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val rtdb = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private val legacyGroupTypingWriteChats: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val legacyGroupTypingReadChats: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    // ────────────────────────────────────────────────────────────
    //  Lifecycle: create / update / membership / admin
    // ────────────────────────────────────────────────────────────

    /**
     * Create a new group chat. Returns the new chatId.
     *
     * @param name display name (required, trimmed)
     * @param iconUri optional local image to upload as the group icon
     * @param memberUids initial members (must NOT include the creator — added automatically)
     */
    suspend fun createGroup(name: String, iconUri: Uri?, memberUids: List<String>): String {
        val creatorUid = requireUid()
        val trimmedName = name.trim()
        require(trimmedName.isNotEmpty()) { "Group name must not be blank" }

        val participants = (listOf(creatorUid) + memberUids.filter { it != creatorUid })
            .distinct()
        require(participants.size in 2..MAX_GROUP_MEMBERS) {
            "Group must have between 2 and $MAX_GROUP_MEMBERS members (got ${participants.size})"
        }

        val chatId = newGroupChatId()
        val createdAt = System.currentTimeMillis()

        val iconUrl: String = if (iconUri != null) uploadGroupIcon(chatId, iconUri) else ""

        val chatData = hashMapOf<String, Any>(
            "id" to chatId,
            "isGroup" to true,
            "groupName" to trimmedName,
            "groupIconUrl" to iconUrl,
            "groupDescription" to "",
            "createdBy" to creatorUid,
            "createdAt" to createdAt,
            "participants" to participants,
            "admins" to listOf(creatorUid),
            "lastMessage" to "",
            "lastMessageTimestamp" to createdAt,
            "lastMessageSenderId" to creatorUid
        )
        firestore.collection("chats").document(chatId).set(chatData).await()

        // Local mirror so the chat list shows it instantly.
        chatDao.insertChat(
            LocalChat(
                id = chatId,
                otherUserId = "",
                otherUsername = "",
                otherUserAvatar = "",
                lastMessage = "",
                lastMessageTimestamp = createdAt,
                lastMessageSenderId = creatorUid,
                lastMessageStatus = MessageStatus.SENT.name,
                isGroup = true,
                groupName = trimmedName,
                groupIconUrl = iconUrl,
                createdBy = creatorUid,
                createdAt = createdAt,
                participantsJson = encodeStringList(participants),
                adminsJson = encodeStringList(listOf(creatorUid))
            )
        )

        // Initial system message + recipient fan-out so members see the group appear.
        val systemText = "Group \"$trimmedName\" was created"
        postSystemMessage(chatId, systemText, participants)
        return chatId
    }

    /** Add new members to an existing group. Caller must be an admin. */
    suspend fun addMembers(chatId: String, newMemberUids: List<String>) {
        val callerUid = requireUid()
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val currentParticipants = (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val currentAdmins = (snap.get("admins") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(callerUid in currentAdmins) { "Only admins can add members" }

        val toAdd = newMemberUids.filter { it !in currentParticipants }.distinct()
        if (toAdd.isEmpty()) return
        require(currentParticipants.size + toAdd.size <= MAX_GROUP_MEMBERS) {
            "Adding ${toAdd.size} members would exceed the $MAX_GROUP_MEMBERS limit"
        }

        firestore.collection("chats").document(chatId)
            .update("participants", FieldValue.arrayUnion(*toAdd.toTypedArray()))
            .await()

        val updatedParticipants = (currentParticipants + toAdd).distinct()
        chatDao.updateGroupMembership(
            chatId,
            encodeStringList(updatedParticipants),
            encodeStringList(currentAdmins)
        )

        val systemText = "${toAdd.size} member${if (toAdd.size == 1) "" else "s"} added"
        postSystemMessage(chatId, systemText, updatedParticipants)
    }

    /** Remove a member from the group. Caller must be an admin (or removing themselves). */
    suspend fun removeMember(chatId: String, memberUid: String) {
        val callerUid = requireUid()
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val currentParticipants = (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val currentAdmins = (snap.get("admins") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(memberUid in currentParticipants) { "User is not a member" }
        require(callerUid == memberUid || callerUid in currentAdmins) {
            "Only admins can remove other members"
        }

        val updates = mutableMapOf<String, Any>(
            "participants" to FieldValue.arrayRemove(memberUid)
        )
        if (memberUid in currentAdmins) updates["admins"] = FieldValue.arrayRemove(memberUid)
        firestore.collection("chats").document(chatId).update(updates).await()

        val updatedParticipants = currentParticipants.filterNot { it == memberUid }
        val updatedAdmins = currentAdmins.filterNot { it == memberUid }
        chatDao.updateGroupMembership(
            chatId,
            encodeStringList(updatedParticipants),
            encodeStringList(updatedAdmins)
        )

        val systemText = if (callerUid == memberUid) "A member left" else "A member was removed"
        postSystemMessage(chatId, systemText, updatedParticipants + memberUid)
    }

    /** Convenience: caller leaves the group. */
    suspend fun leaveGroup(chatId: String) {
        removeMember(chatId, requireUid())
    }

    /** Promote a member to admin. Caller must already be an admin. */
    suspend fun promoteAdmin(chatId: String, memberUid: String) {
        val callerUid = requireUid()
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val currentAdmins = (snap.get("admins") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val currentParticipants = (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(callerUid in currentAdmins) { "Only admins can promote" }
        require(memberUid in currentParticipants) { "User is not a member" }
        if (memberUid in currentAdmins) return

        firestore.collection("chats").document(chatId)
            .update("admins", FieldValue.arrayUnion(memberUid))
            .await()
        chatDao.updateGroupMembership(
            chatId,
            encodeStringList(currentParticipants),
            encodeStringList(currentAdmins + memberUid)
        )
    }

    /** Demote an admin back to a regular member. Caller must be an admin and not the only admin. */
    suspend fun demoteAdmin(chatId: String, adminUid: String) {
        val callerUid = requireUid()
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val currentAdmins = (snap.get("admins") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        val currentParticipants = (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(callerUid in currentAdmins) { "Only admins can demote" }
        require(adminUid in currentAdmins) { "User is not an admin" }
        require(currentAdmins.size > 1) { "Cannot demote the last admin" }

        firestore.collection("chats").document(chatId)
            .update("admins", FieldValue.arrayRemove(adminUid))
            .await()
        chatDao.updateGroupMembership(
            chatId,
            encodeStringList(currentParticipants),
            encodeStringList(currentAdmins.filterNot { it == adminUid })
        )
    }

    /** Update group display info. Caller must be an admin. */
    suspend fun updateGroupInfo(
        chatId: String,
        name: String? = null,
        description: String? = null,
        iconUri: Uri? = null,
        clearIcon: Boolean = false
    ) {
        val callerUid = requireUid()
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val currentAdmins = (snap.get("admins") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(callerUid in currentAdmins) { "Only admins can update group info" }

        val updates = mutableMapOf<String, Any>()
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { updates["groupName"] = it }
        description?.let { updates["groupDescription"] = it }
        if (iconUri != null) updates["groupIconUrl"] = uploadGroupIcon(chatId, iconUri)
        else if (clearIcon) updates["groupIconUrl"] = ""

        if (updates.isEmpty()) return
        firestore.collection("chats").document(chatId).update(updates).await()

        chatDao.updateGroupInfo(
            chatId,
            name = (updates["groupName"] as? String) ?: snap.getString("groupName") ?: "",
            iconUrl = (updates["groupIconUrl"] as? String) ?: snap.getString("groupIconUrl") ?: "",
            description = (updates["groupDescription"] as? String) ?: snap.getString("groupDescription") ?: ""
        )

        if (updates.containsKey("groupIconUrl")) {
            val newIconUrl = updates["groupIconUrl"] as? String
            if (newIconUrl.isNullOrBlank()) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.clearGroupAvatarCache(chatId)
            } else {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheGroupAvatar(chatId, newIconUrl, context)
            }
        }
    }

    /** Update only the group description. Any group member may call this. */
    suspend fun updateGroupDescription(chatId: String, description: String) {
        val callerUid = requireUid()
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val participants = (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(callerUid in participants) { "Only members can update the group description" }

        firestore.collection("chats").document(chatId)
            .update("groupDescription", description).await()

        chatDao.updateGroupInfo(
            chatId,
            name = snap.getString("groupName") ?: "",
            iconUrl = snap.getString("groupIconUrl") ?: "",
            description = description
        )
    }

    private suspend fun uploadGroupIcon(chatId: String, iconUri: Uri): String {
        val iconVersion = System.currentTimeMillis()
        val ref = storage.reference.child("$GROUP_ICON_STORAGE_PATH/$chatId/icon_$iconVersion.jpg")
        ref.putFile(iconUri).await()
        return ref.downloadUrl.await().toString()
    }

    // ────────────────────────────────────────────────────────────
    //  Send (text only for Phase 2 — media reuses media pipeline in later phase)
    // ────────────────────────────────────────────────────────────

    /**
     * Send a text message to a group. Reuses the same Firestore + RTDB shapes as 1:1
     * but writes to RTDB pending_messages for every other participant in a single
     * multi-path update.
     */
    suspend fun sendGroupTextMessage(
        chatId: String,
        text: String,
        previewThumbnailUrl: String? = null,
        previewTitle: String? = null,
        previewDomain: String? = null,
        previewDescription: String? = null,
        previewSiteName: String? = null,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null,
        clientTimestamp: Long? = null
    ) {
        val senderUid = requireUid()
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val participants = (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(senderUid in participants) { "Sender is not a member of this group" }

        val messageId = UUID.randomUUID().toString()
        val timestamp = clientTimestamp ?: System.currentTimeMillis()

        // 1. Optimistic local insert (status SENDING).
        val localMessage = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = text,
            senderId = senderUid,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.TEXT,
            thumbnailUrl = previewThumbnailUrl,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl,
            linkPreviewTitle = previewTitle,
            linkPreviewDomain = previewDomain,
            linkPreviewDescription = previewDescription,
            linkPreviewSiteName = previewSiteName
        )
        messageDao.insertMessage(localMessage)
        chatDao.updateLastMessage(chatId, text, timestamp, senderUid, MessageStatus.SENDING.name)
        LinkPreviewResolver.extractFirstUrl(text)?.let { previewUrl ->
            MessagePreviewCacheManager.warmLinkPreviewAsync(context.applicationContext, previewUrl, previewThumbnailUrl)
        }

        // 2. Firestore message + chat metadata batch.
        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to text,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "TEXT",
            "deliveredTo" to emptyList<String>(),
            "readBy" to emptyList<String>()
        )
        previewThumbnailUrl?.let { firestoreMessageData["thumbnailUrl"] = it }
        previewTitle?.let { firestoreMessageData["linkPreviewTitle"] = it }
        previewDomain?.let { firestoreMessageData["linkPreviewDomain"] = it }
        previewDescription?.let { firestoreMessageData["linkPreviewDescription"] = it }
        previewSiteName?.let { firestoreMessageData["linkPreviewSiteName"] = it }
        replyToMessageId?.let { firestoreMessageData["replyToMessageId"] = it }
        replyToText?.let { firestoreMessageData["replyToText"] = it }
        replyToSenderId?.let { firestoreMessageData["replyToSenderId"] = it }
        replyToType?.let { firestoreMessageData["replyToType"] = it.name }
        replyPreviewUrl?.let { firestoreMessageData["replyPreviewUrl"] = it }

        val firestoreChatData = hashMapOf<String, Any>(
            "lastMessage" to text,
            "lastMessageTimestamp" to timestamp,
            "lastMessageSenderId" to senderUid
        )

        val batch = firestore.batch()
        batch.set(firestore.collection("chats").document(chatId), firestoreChatData, SetOptions.merge())
        batch.set(
            firestore.collection("chats").document(chatId).collection("messages").document(messageId),
            firestoreMessageData,
            SetOptions.merge()
        )
        try {
            batch.commit().await()
            messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
            chatDao.updateLastMessage(chatId, text, timestamp, senderUid, MessageStatus.SENT.name)
        } catch (e: Exception) {
            Log.e(TAG, "Group send Firestore commit failed", e)
            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            chatDao.updateLastMessage(chatId, text, timestamp, senderUid, MessageStatus.FAILED.name)
            return
        }

        // 3. RTDB fan-out to every other participant in a single multi-path update.
        val rtdbPayload = hashMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to text,
            "senderId" to senderUid,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "TEXT"
        )
        previewThumbnailUrl?.let { rtdbPayload["thumbnailUrl"] = it }
        previewTitle?.let { rtdbPayload["linkPreviewTitle"] = it }
        previewDomain?.let { rtdbPayload["linkPreviewDomain"] = it }
        previewDescription?.let { rtdbPayload["linkPreviewDescription"] = it }
        previewSiteName?.let { rtdbPayload["linkPreviewSiteName"] = it }

        fanOutRtdb(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            recipients = participants.filter { it != senderUid },
            payload = rtdbPayload
        )
    }

    // ────────────────────────────────────────────────────────────
    //  Media sends (image / video / voice / document)
    //  Mirror the 1:1 pipelines in RealtimeMessageRepository but write Firestore
    //  directly (no 1:1 chat-doc fields) and fan out via [fanOutRtdb] instead of
    //  pushing to a single recipient. The 1:1 pipeline is intentionally untouched.
    // ────────────────────────────────────────────────────────────

    /**
     * Common tail for all group media sends: validate group + sender, commit the
     * Firestore message + chat metadata in one batch, then fan out the RTDB payload
     * to every other participant. On any Firestore failure the local placeholder is
     * marked [MessageStatus.FAILED]; on success it is marked [MessageStatus.SENT].
     *
     * @return `true` on success, `false` on Firestore failure (caller should not fan out).
     */
    private suspend fun finalizeGroupMediaSend(
        chatId: String,
        messageId: String,
        senderUid: String,
        timestamp: Long,
        lastMessagePreview: String,
        firestoreMessageData: Map<String, Any>,
        rtdbPayload: Map<String, Any>
    ): Boolean {
        val snap = firestore.collection("chats").document(chatId).get().await()
        require(snap.getBoolean("isGroup") == true) { "Not a group chat" }
        val participants = (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        require(senderUid in participants) { "Sender is not a member of this group" }

        val firestoreChatData = hashMapOf<String, Any>(
            "lastMessage" to lastMessagePreview,
            "lastMessageTimestamp" to timestamp,
            "lastMessageSenderId" to senderUid
        )
        val batch = firestore.batch()
        batch.set(firestore.collection("chats").document(chatId), firestoreChatData, SetOptions.merge())
        batch.set(
            firestore.collection("chats").document(chatId).collection("messages").document(messageId),
            firestoreMessageData,
            SetOptions.merge()
        )
        try {
            batch.commit().await()
            messageDao.updateMessageStatus(messageId, MessageStatus.SENT)
            chatDao.updateLastMessage(chatId, lastMessagePreview, timestamp, senderUid, MessageStatus.SENT.name)
        } catch (e: Exception) {
            Log.e(TAG, "Group media Firestore commit failed for $messageId", e)
            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            chatDao.updateLastMessage(chatId, lastMessagePreview, timestamp, senderUid, MessageStatus.FAILED.name)
            return false
        }

        fanOutRtdb(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            recipients = participants.filter { it != senderUid },
            payload = rtdbPayload
        )
        return true
    }

    /**
     * Apply [com.glyph.glyph_v3.data.repo.MediaProgressManager] updates while awaiting
     * a Firebase Storage [com.google.firebase.storage.UploadTask].
     */
    private suspend fun awaitStorageUploadWithProgress(
        task: com.google.firebase.storage.UploadTask,
        messageId: String
    ): Unit {
        task.addOnProgressListener { snapshot ->
            val progress = if (snapshot.totalByteCount > 0) {
                (100.0 * snapshot.bytesTransferred / snapshot.totalByteCount).toFloat()
            } else 0f
            com.glyph.glyph_v3.data.repo.MediaProgressManager.updateProgress(
                messageId,
                progress,
                isUploading = true,
                totalBytes = snapshot.totalByteCount,
                transferredBytes = snapshot.bytesTransferred
            )
        }
        task.await()
    }

    suspend fun sendGroupImageMessage(
        chatId: String,
        imageUri: Uri,
        caption: String = "",
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null
    ) {
        val senderUid = requireUid()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        var fileSize: Long = 0
        var width = 0
        var height = 0
        try {
            context.contentResolver.openFileDescriptor(imageUri, "r")?.use { descriptor ->
                fileSize = descriptor.statSize
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, opts)
                width = opts.outWidth
                height = opts.outHeight
            }
            context.contentResolver.openInputStream(imageUri)?.use { stream ->
                val exif = androidx.exifinterface.media.ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                )
                if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
                ) {
                    val tmp = width; width = height; height = tmp
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendGroupImageMessage: metadata probe failed", e)
        }

        val placeholderMediaItem = com.glyph.glyph_v3.data.models.MediaItem(
            url = imageUri.toString(),
            localUri = imageUri.toString(),
            type = com.glyph.glyph_v3.data.models.MediaType.IMAGE,
            fileSize = fileSize,
            width = width,
            height = height
        )
        val placeholderMediaJson = com.glyph.glyph_v3.data.models.Message.mediaItemsToJson(listOf(placeholderMediaItem))
        val previewText = caption.ifEmpty { "Photo" }

        val placeholder = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = previewText,
            senderId = senderUid,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.IMAGE,
            localUri = imageUri.toString(),
            fileSize = fileSize,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl,
            mediaItems = placeholderMediaJson
        )
        messageDao.insertMessage(placeholder)
        chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.SENDING.name)

        com.glyph.glyph_v3.data.repo.MediaProgressManager.updateProgress(
            messageId, 0f, isUploading = true, totalBytes = fileSize
        )

        val storageRef = storage.reference.child("chat_images/$chatId/$messageId.jpg")
        val downloadUrl: String = try {
            awaitStorageUploadWithProgress(storageRef.putFile(imageUri), messageId)
            storageRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Group image upload failed for $messageId", e)
            com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)
            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.FAILED.name)
            return
        }
        com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)

        var finalLocalPath = imageUri.toString()
        try {
            val saved = com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                context = context,
                chatId = chatId,
                messageId = messageId,
                mediaType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.IMAGE,
                sourceUri = imageUri
            )
            if (saved != null) finalLocalPath = saved.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Group image: persistent copy failed for $messageId", e)
        }

        val finalMediaItem = placeholderMediaItem.copy(url = downloadUrl, localUri = finalLocalPath)
        val finalMediaJson = com.glyph.glyph_v3.data.models.Message.mediaItemsToJson(listOf(finalMediaItem))
        messageDao.insertMessage(
            placeholder.copy(
                imageUrl = downloadUrl,
                localUri = finalLocalPath,
                mediaItems = finalMediaJson
            )
        )

        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to previewText,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "IMAGE",
            "imageUrl" to downloadUrl,
            "mediaItems" to finalMediaJson,
            "fileSize" to fileSize,
            "deliveredTo" to emptyList<String>(),
            "readBy" to emptyList<String>()
        )
        replyToMessageId?.let { firestoreMessageData["replyToMessageId"] = it }
        replyToText?.let { firestoreMessageData["replyToText"] = it }
        replyToSenderId?.let { firestoreMessageData["replyToSenderId"] = it }
        replyToType?.let { firestoreMessageData["replyToType"] = it.name }
        replyPreviewUrl?.let { firestoreMessageData["replyPreviewUrl"] = it }

        val rtdbPayload = hashMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to previewText,
            "senderId" to senderUid,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "IMAGE",
            "imageUrl" to downloadUrl,
            "mediaItems" to finalMediaJson,
            "fileSize" to fileSize
        )
        replyToMessageId?.let { rtdbPayload["replyToMessageId"] = it }
        replyToText?.let { rtdbPayload["replyToText"] = it }
        replyToSenderId?.let { rtdbPayload["replyToSenderId"] = it }
        replyToType?.let { rtdbPayload["replyToType"] = it.name }
        replyPreviewUrl?.let { rtdbPayload["replyPreviewUrl"] = it }

        finalizeGroupMediaSend(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            timestamp = timestamp,
            lastMessagePreview = previewText,
            firestoreMessageData = firestoreMessageData,
            rtdbPayload = rtdbPayload
        )
    }

    suspend fun sendGroupVideoMessage(
        chatId: String,
        videoUri: Uri,
        caption: String = "",
        isVideoNote: Boolean = false,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null
    ) {
        val senderUid = requireUid()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        var fileSize: Long = 0
        var videoDuration: Long = 0
        var videoWidth = 0
        var videoHeight = 0
        try {
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { fd ->
                fileSize = fd.statSize
            }
            val metadata = com.glyph.glyph_v3.util.VideoThumbnailUtil.getVideoMetadata(context, videoUri)
            if (metadata != null) {
                videoDuration = metadata.duration
                videoWidth = metadata.width
                videoHeight = metadata.height
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendGroupVideoMessage: metadata probe failed", e)
        }

        val previewText = caption.ifEmpty { if (isVideoNote) "Video note" else "Video" }
        val placeholder = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = previewText,
            senderId = senderUid,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.VIDEO,
            localUri = videoUri.toString(),
            fileSize = fileSize,
            videoDuration = videoDuration,
            mediaWidth = videoWidth,
            mediaHeight = videoHeight,
            isVideoNote = isVideoNote,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl
        )
        messageDao.insertMessage(placeholder)
        chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.SENDING.name)

        com.glyph.glyph_v3.data.repo.MediaProgressManager.updateProgress(
            messageId, 0f, isUploading = true, totalBytes = fileSize
        )

        // Generate + upload thumbnail (non-fatal).
        var thumbnailUrl: String? = null
        try {
            val thumbResult = com.glyph.glyph_v3.util.VideoThumbnailUtil.generateThumbnailBytes(context, videoUri)
            if (thumbResult != null) {
                val (thumbBytes, _) = thumbResult
                val thumbRef = storage.reference.child("chat_video_thumbnails/$chatId/$messageId.jpg")
                thumbRef.putBytes(thumbBytes).await()
                thumbnailUrl = thumbRef.downloadUrl.await().toString()
                messageDao.insertMessage(placeholder.copy(thumbnailUrl = thumbnailUrl))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Group video thumbnail upload failed for $messageId", e)
        }

        val storageRef = storage.reference.child("chat_videos/$chatId/$messageId.mp4")
        val downloadUrl: String = try {
            awaitStorageUploadWithProgress(storageRef.putFile(videoUri), messageId)
            storageRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Group video upload failed for $messageId", e)
            com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)
            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.FAILED.name)
            return
        }
        com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)

        var finalLocalPath = videoUri.toString()
        try {
            val saved = com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                context = context,
                chatId = chatId,
                messageId = messageId,
                mediaType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.VIDEO,
                sourceUri = videoUri
            )
            if (saved != null) finalLocalPath = saved.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Group video: persistent copy failed for $messageId", e)
        }

        messageDao.insertMessage(
            placeholder.copy(
                videoUrl = downloadUrl,
                thumbnailUrl = thumbnailUrl,
                localUri = finalLocalPath
            )
        )

        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to previewText,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "VIDEO",
            "videoUrl" to downloadUrl,
            "fileSize" to fileSize,
            "videoDuration" to videoDuration,
            "mediaWidth" to videoWidth,
            "mediaHeight" to videoHeight,
            "isVideoNote" to isVideoNote,
            "deliveredTo" to emptyList<String>(),
            "readBy" to emptyList<String>()
        )
        thumbnailUrl?.let { firestoreMessageData["thumbnailUrl"] = it }
        replyToMessageId?.let { firestoreMessageData["replyToMessageId"] = it }
        replyToText?.let { firestoreMessageData["replyToText"] = it }
        replyToSenderId?.let { firestoreMessageData["replyToSenderId"] = it }
        replyToType?.let { firestoreMessageData["replyToType"] = it.name }
        replyPreviewUrl?.let { firestoreMessageData["replyPreviewUrl"] = it }

        val rtdbPayload = hashMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to previewText,
            "senderId" to senderUid,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "VIDEO",
            "videoUrl" to downloadUrl,
            "fileSize" to fileSize,
            "videoDuration" to videoDuration,
            "mediaWidth" to videoWidth,
            "mediaHeight" to videoHeight,
            "isVideoNote" to isVideoNote
        )
        thumbnailUrl?.let { rtdbPayload["thumbnailUrl"] = it }
        replyToMessageId?.let { rtdbPayload["replyToMessageId"] = it }
        replyToText?.let { rtdbPayload["replyToText"] = it }
        replyToSenderId?.let { rtdbPayload["replyToSenderId"] = it }
        replyToType?.let { rtdbPayload["replyToType"] = it.name }
        replyPreviewUrl?.let { rtdbPayload["replyPreviewUrl"] = it }

        finalizeGroupMediaSend(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            timestamp = timestamp,
            lastMessagePreview = previewText,
            firestoreMessageData = firestoreMessageData,
            rtdbPayload = rtdbPayload
        )
    }

    suspend fun sendGroupVoiceMessage(
        chatId: String,
        voiceFile: java.io.File,
        duration: Long
    ) {
        val senderUid = requireUid()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Move to persistent storage immediately.
        val cacheUri = Uri.fromFile(voiceFile)
        var finalLocalPath = voiceFile.absolutePath
        var finalFile = voiceFile
        try {
            val persistent = com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                context = context,
                chatId = chatId,
                messageId = messageId,
                mediaType = com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.AUDIO,
                sourceUri = cacheUri
            )
            if (persistent != null) {
                finalLocalPath = persistent.absolutePath
                finalFile = persistent
            }
        } catch (e: Exception) {
            Log.w(TAG, "Group voice: persistent copy failed for $messageId", e)
        }
        val finalUri = Uri.fromFile(finalFile)
        val previewText = "Voice Message"

        val placeholder = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = previewText,
            senderId = senderUid,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.AUDIO,
            localUri = finalLocalPath,
            audioDuration = duration
        )
        messageDao.insertMessage(placeholder)
        chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.SENDING.name)

        com.glyph.glyph_v3.data.repo.MediaProgressManager.updateProgress(
            messageId, 0f, isUploading = true, totalBytes = finalFile.length()
        )

        val storageRef = storage.reference.child("chat_voice/$chatId/$messageId.m4a")
        val downloadUrl: String = try {
            awaitStorageUploadWithProgress(storageRef.putFile(finalUri), messageId)
            storageRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Group voice upload failed for $messageId", e)
            com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)
            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.FAILED.name)
            return
        }
        com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)

        messageDao.insertMessage(placeholder.copy(audioUrl = downloadUrl))

        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to previewText,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "AUDIO",
            "audioUrl" to downloadUrl,
            "audioDuration" to duration,
            "fileSize" to finalFile.length(),
            "deliveredTo" to emptyList<String>(),
            "readBy" to emptyList<String>()
        )
        val rtdbPayload = hashMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to previewText,
            "senderId" to senderUid,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "AUDIO",
            "audioUrl" to downloadUrl,
            "audioDuration" to duration,
            "fileSize" to finalFile.length()
        )

        finalizeGroupMediaSend(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            timestamp = timestamp,
            lastMessagePreview = previewText,
            firestoreMessageData = firestoreMessageData,
            rtdbPayload = rtdbPayload
        )
    }

    suspend fun sendGroupDocumentMessage(
        chatId: String,
        documentUri: Uri,
        caption: String = ""
    ) {
        val senderUid = requireUid()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Resolve filename + size.
        var fileName = "document"
        var fileSize: Long = 0L
        try {
            context.contentResolver.query(documentUri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Group document metadata probe failed", e)
        }
        if (fileSize == 0L) {
            try {
                context.contentResolver.openFileDescriptor(documentUri, "r")?.use { fd ->
                    fileSize = fd.statSize
                }
            } catch (e: Exception) {
                Log.w(TAG, "Group document file size probe failed", e)
            }
        }

        val ext = fileName.substringAfterLast('.', "")
        val storageName = if (ext.isNotEmpty()) "$messageId.$ext" else messageId
        val previewText = "📄 $fileName"

        val placeholder = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = fileName,
            senderId = senderUid,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.DOCUMENT,
            localUri = documentUri.toString(),
            fileSize = fileSize,
            documentCaption = caption.ifBlank { null }
        )
        messageDao.insertMessage(placeholder)
        chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.SENDING.name)

        // PDF thumbnail (non-fatal).
        var thumbnailUrl: String? = null
        try {
            val thumbBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.glyph.glyph_v3.util.DocumentThumbnailGenerator.generatePdfThumbnailBytes(
                    context, documentUri, fileName
                )
            }
            if (thumbBytes != null) {
                com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager.cacheDocumentThumbnailBytes(
                    context.applicationContext, messageId, thumbBytes
                )
                val thumbRef = storage.reference.child("chat_document_thumbnails/$chatId/$messageId.jpg")
                thumbRef.putBytes(thumbBytes).await()
                thumbnailUrl = thumbRef.downloadUrl.await().toString()
                messageDao.insertMessage(placeholder.copy(thumbnailUrl = thumbnailUrl))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Group document thumbnail failed for $messageId", e)
        }

        val storageRef = storage.reference.child("chat_documents/$chatId/$storageName")
        val downloadUrl: String = try {
            awaitStorageUploadWithProgress(storageRef.putFile(documentUri), messageId)
            storageRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            Log.e(TAG, "Group document upload failed for $messageId", e)
            com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)
            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.FAILED.name)
            return
        }
        com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)

        messageDao.insertMessage(
            placeholder.copy(
                imageUrl = downloadUrl,
                thumbnailUrl = thumbnailUrl
            )
        )

        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to fileName,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "DOCUMENT",
            "imageUrl" to downloadUrl,
            "fileSize" to fileSize,
            "deliveredTo" to emptyList<String>(),
            "readBy" to emptyList<String>()
        )
        thumbnailUrl?.let { firestoreMessageData["thumbnailUrl"] = it }
        if (caption.isNotBlank()) firestoreMessageData["documentCaption"] = caption

        val rtdbPayload = hashMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to fileName,
            "senderId" to senderUid,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "DOCUMENT",
            "imageUrl" to downloadUrl,
            "fileSize" to fileSize
        )
        thumbnailUrl?.let { rtdbPayload["thumbnailUrl"] = it }
        if (caption.isNotBlank()) rtdbPayload["documentCaption"] = caption

        finalizeGroupMediaSend(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            timestamp = timestamp,
            lastMessagePreview = previewText,
            firestoreMessageData = firestoreMessageData,
            rtdbPayload = rtdbPayload
        )
    }

    /**
     * Post a SYSTEM message (group lifecycle event). Visible to all current participants.
     * SYSTEM messages bypass typing indicators and reply chains — they exist purely as
     * an audit trail rendered as a centered grey pill.
     */
    private suspend fun postSystemMessage(
        chatId: String,
        text: String,
        recipients: List<String>
    ) {
        val senderUid = auth.currentUser?.uid ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        messageDao.insertMessage(
            LocalMessage(
                id = messageId,
                chatId = chatId,
                text = text,
                senderId = senderUid,
                timestamp = timestamp,
                status = MessageStatus.SENT,
                isIncoming = false,
                type = MessageType.SYSTEM
            )
        )
        chatDao.updateLastMessage(chatId, text, timestamp, senderUid, MessageStatus.SENT.name)

        val data = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to text,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "SYSTEM"
        )
        runCatching {
            firestore.collection("chats").document(chatId)
                .collection("messages").document(messageId)
                .set(data, SetOptions.merge())
                .await()
        }.onFailure { Log.w(TAG, "Failed to persist SYSTEM message $messageId", it) }

        fanOutRtdb(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            recipients = recipients.filter { it != senderUid }.distinct(),
            payload = mapOf(
                "id" to messageId,
                "chatId" to chatId,
                "text" to text,
                "senderId" to senderUid,
                "timestamp" to ServerValue.TIMESTAMP,
                "type" to "SYSTEM"
            )
        )
    }

    // ────────────────────────────────────────────────────────────
    //  Lightweight sends — Klipy (CDN URL, no upload) + Contact card
    // ────────────────────────────────────────────────────────────

    /**
     * Phase 8: send a Klipy GIF / sticker / meme / emoji to a group. Mirrors
     * [com.glyph.glyph_v3.data.repo.RealtimeMessageRepository.sendKlipyMediaMessage]
     * but writes Firestore + RTDB through the group [finalizeGroupMediaSend] pipeline.
     * No Storage upload — Klipy hosts the media on its own CDN ([imageUrl]).
     */
    suspend fun sendGroupKlipyMessage(
        chatId: String,
        type: MessageType,
        title: String,
        imageUrl: String,
        previewUrl: String?,
        mediaWidth: Int,
        mediaHeight: Int,
        replyToMessageId: String? = null,
        replyToText: String? = null,
        replyToSenderId: String? = null,
        replyToType: MessageType? = null,
        replyPreviewUrl: String? = null
    ): Boolean {
        val senderUid = requireUid()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val displayText = when (type) {
            MessageType.GIF -> "GIF"
            MessageType.STICKER -> "Sticker"
            MessageType.MEME -> "Meme"
            else -> title
        }

        val placeholder = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = displayText,
            senderId = senderUid,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = type,
            imageUrl = imageUrl,
            thumbnailUrl = previewUrl,
            mediaWidth = mediaWidth,
            mediaHeight = mediaHeight,
            replyToMessageId = replyToMessageId,
            replyToText = replyToText,
            replyToSenderId = replyToSenderId,
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl
        )
        messageDao.insertMessage(placeholder)
        chatDao.updateLastMessage(chatId, displayText, timestamp, senderUid, MessageStatus.SENDING.name)

        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to displayText,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to type.name,
            "imageUrl" to imageUrl,
            "deliveredTo" to emptyList<String>(),
            "readBy" to emptyList<String>()
        )
        previewUrl?.let { firestoreMessageData["thumbnailUrl"] = it }
        if (mediaWidth > 0) firestoreMessageData["mediaWidth"] = mediaWidth
        if (mediaHeight > 0) firestoreMessageData["mediaHeight"] = mediaHeight
        replyToMessageId?.let { firestoreMessageData["replyToMessageId"] = it }
        replyToText?.let { firestoreMessageData["replyToText"] = it }
        replyToSenderId?.let { firestoreMessageData["replyToSenderId"] = it }
        replyToType?.let { firestoreMessageData["replyToType"] = it.name }
        replyPreviewUrl?.let { firestoreMessageData["replyPreviewUrl"] = it }

        val rtdbPayload = hashMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to displayText,
            "senderId" to senderUid,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to type.name,
            "imageUrl" to imageUrl
        )
        previewUrl?.let { rtdbPayload["thumbnailUrl"] = it }
        if (mediaWidth > 0) rtdbPayload["mediaWidth"] = mediaWidth
        if (mediaHeight > 0) rtdbPayload["mediaHeight"] = mediaHeight

        return finalizeGroupMediaSend(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            timestamp = timestamp,
            lastMessagePreview = displayText,
            firestoreMessageData = firestoreMessageData,
            rtdbPayload = rtdbPayload
        )
    }

    /**
     * Phase 8: send a contact card to a group. Mirrors
     * [com.glyph.glyph_v3.data.repo.RealtimeMessageRepository.sendContactMessage].
     * No Storage upload — the contact info is inline on the message doc.
     */
    suspend fun sendGroupContactMessage(
        chatId: String,
        contactName: String,
        contactPhone: String
    ): Boolean {
        val senderUid = requireUid()
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val previewText = "Contact: $contactName"

        val placeholder = LocalMessage(
            id = messageId,
            chatId = chatId,
            text = contactName,
            senderId = senderUid,
            timestamp = timestamp,
            status = MessageStatus.SENDING,
            isIncoming = false,
            type = MessageType.CONTACT,
            contactName = contactName,
            contactPhone = contactPhone
        )
        messageDao.insertMessage(placeholder)
        chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.SENDING.name)

        val firestoreMessageData = hashMapOf<String, Any>(
            "id" to messageId,
            "text" to contactName,
            "senderId" to senderUid,
            "timestamp" to timestamp,
            "serverTimestamp" to FieldValue.serverTimestamp(),
            "status" to MessageStatus.SENT.name,
            "type" to "CONTACT",
            "contactName" to contactName,
            "contactPhone" to contactPhone,
            "deliveredTo" to emptyList<String>(),
            "readBy" to emptyList<String>()
        )

        val rtdbPayload = hashMapOf<String, Any>(
            "id" to messageId,
            "chatId" to chatId,
            "text" to contactName,
            "senderId" to senderUid,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "CONTACT",
            "contactName" to contactName,
            "contactPhone" to contactPhone
        )

        return finalizeGroupMediaSend(
            chatId = chatId,
            messageId = messageId,
            senderUid = senderUid,
            timestamp = timestamp,
            lastMessagePreview = previewText,
            firestoreMessageData = firestoreMessageData,
            rtdbPayload = rtdbPayload
        )
    }

    /**
     * Single multi-path RTDB update writing the same payload under
     * `/pending_messages/{recipientUid}/{messageId}` for every recipient. Per-recipient
     * write failures (e.g. block in either direction) are tolerated — the server still
     * delivers to the rest.
     */
    private fun fanOutRtdb(
        chatId: String,
        messageId: String,
        senderUid: String,
        recipients: List<String>,
        payload: Map<String, Any>
    ) {
        if (recipients.isEmpty()) return
        val updates = HashMap<String, Any>(recipients.size)
        recipients.forEach { recipientUid ->
            updates["pending_messages/$recipientUid/$messageId"] = payload
        }
        rtdb.reference.updateChildren(updates)
            .addOnFailureListener { e ->
                // A single rule rejection (e.g. one blocked recipient) fails the whole
                // multi-path write. Fall back to per-recipient writes so the rest get through.
                Log.w(TAG, "Group fan-out multi-path failed for $messageId; falling back per-recipient", e)
                recipients.forEach { recipientUid ->
                    rtdb.reference.child("pending_messages").child(recipientUid).child(messageId)
                        .setValue(payload)
                        .addOnFailureListener { perErr ->
                            Log.w(TAG, "Per-recipient send to $recipientUid failed (chat=$chatId msg=$messageId)", perErr)
                        }
                }
            }
        @Suppress("UNUSED_VARIABLE") val _unused = senderUid // reserved for sender-presence tagging
    }

    // ────────────────────────────────────────────────────────────
    //  Receipts (delivered / read)
    // ────────────────────────────────────────────────────────────

    /**
     * Mark a group message as delivered to the current device. Idempotent — uses
     * `arrayUnion` server-side and de-dupes locally.
     */
    suspend fun markGroupMessageDelivered(chatId: String, messageId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            firestore.collection("chats").document(chatId)
                .collection("messages").document(messageId)
                .update("deliveredTo", FieldValue.arrayUnion(uid))
                .await()
        }.onFailure { Log.w(TAG, "markGroupMessageDelivered failed: ${it.message}") }

        val existing = decodeStringList(messageDao.getMessageById(messageId)?.deliveredToJson)
        if (uid !in existing) {
            messageDao.updateDeliveredTo(messageId, encodeStringList(existing + uid))
        }
    }

    /** Mark a group message as read by the current user. */
    suspend fun markGroupMessageRead(chatId: String, messageId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            firestore.collection("chats").document(chatId)
                .collection("messages").document(messageId)
                .update(
                    mapOf(
                        "readBy" to FieldValue.arrayUnion(uid),
                        "deliveredTo" to FieldValue.arrayUnion(uid)
                    )
                )
                .await()
        }.onFailure { Log.w(TAG, "markGroupMessageRead failed: ${it.message}") }

        val msg = messageDao.getMessageById(messageId) ?: return
        val readers = decodeStringList(msg.readByJson)
        if (uid !in readers) messageDao.updateReadBy(messageId, encodeStringList(readers + uid))
        val delivered = decodeStringList(msg.deliveredToJson)
        if (uid !in delivered) messageDao.updateDeliveredTo(messageId, encodeStringList(delivered + uid))
    }

    // ────────────────────────────────────────────────────────────
    //  Typing
    // ────────────────────────────────────────────────────────────

    /**
     * Set the current user's typing flag for a group. Pass `false` (or simply omit
     * subsequent calls and let the 3-s client-side debounce expire) to clear it.
     */
    fun setGroupTyping(chatId: String, isTyping: Boolean, participantUids: List<String> = emptyList()) {
        val uid = auth.currentUser?.uid ?: return
        syncGroupTypingMirror(chatId, uid, isTyping)
        if (legacyGroupTypingWriteChats.contains(chatId)) {
            setGroupTypingLegacy(chatId, uid, isTyping, participantUids)
            return
        }

        val ref = rtdb.reference.child("groupTyping").child(chatId).child(uid)
        if (isTyping) {
            ref.setValue(true)
                .addOnSuccessListener {
                    // Auto-clear if the client crashes / loses connection.
                    ref.onDisconnect().removeValue()
                        .addOnFailureListener { error ->
                            Log.w(TAG, "groupTyping onDisconnect remove failed for $chatId", error)
                        }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "groupTyping write failed for $chatId; falling back", error)
                    if (isPermissionDenied(error)) {
                        legacyGroupTypingWriteChats.add(chatId)
                        setGroupTypingLegacy(chatId, uid, isTyping = true, participantUids = participantUids)
                    }
                }
        } else {
            ref.removeValue()
                .addOnFailureListener { error ->
                    Log.w(TAG, "groupTyping remove failed for $chatId; falling back", error)
                    if (isPermissionDenied(error)) {
                        legacyGroupTypingWriteChats.add(chatId)
                        setGroupTypingLegacy(chatId, uid, isTyping = false, participantUids = participantUids)
                    }
                }
        }
    }

    private fun setGroupTypingLegacy(
        chatId: String,
        uid: String,
        isTyping: Boolean,
        participantUids: List<String>
    ) {
        val recipients = participantUids
            .asSequence()
            .filter { it.isNotBlank() && it != uid }
            .distinct()
            .toList()
        if (recipients.isEmpty()) return

        val rootRef = rtdb.reference
        val updates = HashMap<String, Any?>()
        recipients.forEach { recipientUid ->
            updates["chats/$chatId/typing/$uid/$recipientUid"] = if (isTyping) true else null
        }

        rootRef.updateChildren(updates)
            .addOnFailureListener { error ->
                Log.w(TAG, "legacy group typing updateChildren failed for $chatId", error)
            }

        if (isTyping) {
            recipients.forEach { recipientUid ->
                rootRef
                    .child("chats")
                    .child(chatId)
                    .child("typing")
                    .child(uid)
                    .child(recipientUid)
                    .onDisconnect()
                    .removeValue()
                    .addOnFailureListener { error ->
                        Log.w(TAG, "legacy group typing onDisconnect remove failed for $chatId", error)
                    }
            }
        }
    }

    private fun isPermissionDenied(error: Throwable?): Boolean {
        val message = error?.message?.lowercase() ?: return false
        return "permission denied" in message || "permission_denied" in message
    }

    private data class FirestoreGroupTypingMirror(
        val isTyping: Boolean = false,
        val updatedAt: Long = 0L
    )

    private fun syncGroupTypingMirror(chatId: String, uid: String, isTyping: Boolean) {
        firestore.collection("chats").document(chatId)
            .set(
                mapOf(
                    "groupTypingStates" to mapOf(
                        uid to mapOf(
                            "isTyping" to isTyping,
                            "updatedAt" to System.currentTimeMillis()
                        )
                    )
                ),
                SetOptions.merge()
            )
            .addOnFailureListener { error ->
                Log.w(TAG, "Failed to sync Firestore group typing mirror for $chatId/$uid", error)
            }
    }

    private fun extractFirestoreGroupTypingMirror(
        data: Map<String, Any?>?,
        selfUid: String
    ): Map<String, FirestoreGroupTypingMirror> {
        val typingStates = data?.get("groupTypingStates") as? Map<*, *> ?: return emptyMap()
        return typingStates.entries.asSequence()
            .mapNotNull { (key, value) ->
                val uid = key as? String ?: return@mapNotNull null
                if (uid.isBlank() || uid == selfUid) return@mapNotNull null
                val state = value as? Map<*, *> ?: return@mapNotNull null
                uid to FirestoreGroupTypingMirror(
                    isTyping = state["isTyping"] as? Boolean ?: false,
                    updatedAt = (state["updatedAt"] as? Number)?.toLong() ?: 0L
                )
            }
            .toMap()
    }

    /**
     * Observe the set of uids currently typing in a group (excluding the current user).
     */
    fun observeGroupTyping(chatId: String): Flow<Set<String>> = callbackFlow {
        val selfUid = auth.currentUser?.uid
        if (selfUid.isNullOrBlank()) {
            trySend(emptySet())
            close()
            return@callbackFlow
        }

        val ref = rtdb.reference.child("groupTyping").child(chatId)
        val legacyListeners = mutableListOf<Pair<com.google.firebase.database.DatabaseReference, com.google.firebase.database.ValueEventListener>>()
        val legacyTypingByUser = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        var firestoreListener: ListenerRegistration? = null
        var legacyStarted = false
        var rtdbTyping = emptySet<String>()
        var firestoreTyping = emptyMap<String, FirestoreGroupTypingMirror>()

        fun emitMergedTyping() {
            val now = System.currentTimeMillis()
            val merged = rtdbTyping.toMutableSet()
            firestoreTyping.forEach { (uid, state) ->
                if ((now - state.updatedAt) < GROUP_TYPING_FRESHNESS_MS) {
                    if (state.isTyping) merged += uid else merged.remove(uid)
                }
            }
            trySend(merged)
        }

        fun emitLegacyTyping() {
            val typing = legacyTypingByUser.entries
                .asSequence()
                .filter { it.value }
                .map { it.key }
                .toSet()
            rtdbTyping = typing
            emitMergedTyping()
        }

        fun startLegacyObservers() {
            if (legacyStarted) return
            legacyStarted = true

            launch {
                val participants = runCatching {
                    val snap = firestore.collection("chats").document(chatId).get().await()
                    (snap.get("participants") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
                }.getOrDefault(emptyList())

                participants
                    .asSequence()
                    .filter { it.isNotBlank() && it != selfUid }
                    .forEach { senderUid ->
                        val legacyRef = rtdb.reference
                            .child("chats")
                            .child(chatId)
                            .child("typing")
                            .child(senderUid)
                            .child(selfUid)
                        legacyRef.keepSynced(true)

                        val legacyListener = object : com.google.firebase.database.ValueEventListener {
                            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                legacyTypingByUser[senderUid] = snapshot.getValue(Boolean::class.java) == true
                                emitLegacyTyping()
                            }

                            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                                Log.w(TAG, "legacy observeGroupTyping cancelled for $chatId/$senderUid: ${error.message}")
                            }
                        }

                        legacyListeners += legacyRef to legacyListener
                        legacyRef.addValueEventListener(legacyListener)
                    }
            }
        }

        val useLegacyOnly = legacyGroupTypingReadChats.contains(chatId)
        if (useLegacyOnly) {
            startLegacyObservers()
        }

        PresenceManager.primeTransport("observeGroupTyping:$chatId")
        rtdb.reference.child("groupTyping").child(chatId).keepSynced(true)

        firestoreListener = firestore.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "observeGroupTyping Firestore mirror failed for $chatId", error)
                    return@addSnapshotListener
                }

                firestoreTyping = extractFirestoreGroupTypingMirror(snapshot?.data, selfUid)
                emitMergedTyping()
            }

        val listener = if (useLegacyOnly) {
            null
        } else {
            object : com.google.firebase.database.ValueEventListener {
                override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                    val typing = HashSet<String>()
                    snapshot.children.forEach { child ->
                        val uid = child.key ?: return@forEach
                        if (uid == selfUid) return@forEach
                        if (child.getValue(Boolean::class.java) == true) typing += uid
                    }
                    rtdbTyping = typing
                    emitMergedTyping()
                }

                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                    Log.w(TAG, "observeGroupTyping cancelled: ${error.message}")
                    if (error.code == com.google.firebase.database.DatabaseError.PERMISSION_DENIED) {
                        legacyGroupTypingReadChats.add(chatId)
                        startLegacyObservers()
                    }
                }
            }.also { primaryListener ->
                ref.addValueEventListener(primaryListener)
            }
        }
        awaitClose {
            listener?.let { ref.removeEventListener(it) }
            legacyListeners.forEach { (legacyRef, legacyListener) ->
                legacyRef.removeEventListener(legacyListener)
            }
            firestoreListener?.remove()
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Grouped media (MEDIA_GROUP collage) — Phase 11
    //  Mirrors RealtimeMessageRepository.sendGroupedMediaMessage but writes through
    //  finalizeGroupMediaSend so the message is fanned out to every other group
    //  participant. The receiver renders the collage exactly the same way as 1:1
    //  because the Firestore document type is "MEDIA_GROUP" with the same
    //  mediaItems JSON shape.
    // ────────────────────────────────────────────────────────────

    private data class GroupedMediaPrep(
        val finalUri: Uri,
        val mimeType: String,
        val mediaType: com.glyph.glyph_v3.data.models.MediaType,
        val metadata: com.glyph.glyph_v3.data.models.SelectedMediaItem
    )

    suspend fun sendGroupedMediaMessage(
        chatId: String,
        uris: List<Uri>,
        quality: com.glyph.glyph_v3.data.models.CompressionQuality,
        overrides: Map<Uri, com.glyph.glyph_v3.data.models.CompressionQuality> = emptyMap(),
        caption: String = ""
    ) {
        if (uris.isEmpty()) return
        val senderUid = requireUid()
        val timestamp = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()

        val compressionDir = java.io.File(context.cacheDir, "media_group_compression").apply {
            if (!exists()) mkdirs()
        }

        val preparedItems = mutableListOf<GroupedMediaPrep>()

        try {
            for (uri in uris) {
                val mimeType = context.contentResolver.getType(uri) ?: "image/*"
                val itemQuality = overrides[uri] ?: quality

                val baseMetadata = com.glyph.glyph_v3.util.MediaEstimationUtil
                    .extractMediaMetadata(context, uri, mimeType)
                val compressedUri = if (itemQuality == com.glyph.glyph_v3.data.models.CompressionQuality.ORIGINAL) {
                    uri
                } else {
                    com.glyph.glyph_v3.util.MediaCompressor.compress(
                        context, baseMetadata, itemQuality, compressionDir
                    ) ?: uri
                }

                val finalMetadata = if (compressedUri == uri) {
                    baseMetadata
                } else {
                    com.glyph.glyph_v3.util.MediaEstimationUtil
                        .extractMediaMetadata(context, compressedUri, mimeType)
                }

                val mediaType = if (finalMetadata.isVideo) {
                    com.glyph.glyph_v3.data.models.MediaType.VIDEO
                } else {
                    com.glyph.glyph_v3.data.models.MediaType.IMAGE
                }
                preparedItems.add(GroupedMediaPrep(compressedUri, mimeType, mediaType, finalMetadata))
            }

            if (preparedItems.isEmpty()) return

            // Build placeholder items with base64 thumbnails for instant display.
            val placeholderItems = preparedItems.mapIndexed { index, prep ->
                val thumbnailBase64 = try {
                    com.glyph.glyph_v3.data.media.ThumbnailGenerator
                        .generateBase64Thumbnail(context, prep.finalUri)
                } catch (e: Exception) {
                    Log.w(TAG, "Group thumbnail generation failed for item $index", e)
                    null
                }
                com.glyph.glyph_v3.data.models.MediaItem(
                    url = "",
                    localUri = prep.finalUri.toString(),
                    type = prep.mediaType,
                    thumbnailUrl = null,
                    thumbnailBase64 = thumbnailBase64,
                    duration = prep.metadata.duration,
                    fileSize = prep.metadata.originalSize,
                    width = prep.metadata.width,
                    height = prep.metadata.height
                )
            }

            val previewText = caption.ifEmpty { "Media" }

            val placeholder = LocalMessage(
                id = messageId,
                chatId = chatId,
                text = previewText,
                senderId = senderUid,
                timestamp = timestamp,
                status = MessageStatus.SENDING,
                isIncoming = false,
                type = MessageType.MEDIA_GROUP,
                mediaItems = com.glyph.glyph_v3.data.models.Message.mediaItemsToJson(placeholderItems),
                fileSize = placeholderItems.sumOf { it.fileSize }
            )
            messageDao.insertMessage(placeholder)
            chatDao.updateLastMessage(chatId, previewText, timestamp, senderUid, MessageStatus.SENDING.name)

            val totalBytes = preparedItems.sumOf { it.metadata.originalSize }.coerceAtLeast(1L)
            com.glyph.glyph_v3.data.repo.MediaProgressManager.updateProgress(
                messageId, 0f, isUploading = true, totalBytes = totalBytes
            )

            val finalMediaItems = mutableListOf<com.glyph.glyph_v3.data.models.MediaItem>()
            var uploadedBytes = 0L

            for ((index, prepared) in preparedItems.withIndex()) {
                val extension = if (prepared.mediaType == com.glyph.glyph_v3.data.models.MediaType.VIDEO) "mp4" else "jpg"
                val storagePath = "chat_media/$chatId/$messageId/item_${index + 1}.$extension"
                val storageRef = storage.reference.child(storagePath)

                val uploadTask = storageRef.putFile(prepared.finalUri)
                uploadTask.addOnProgressListener { snapshot ->
                    val overallBytes = (uploadedBytes + snapshot.bytesTransferred).coerceAtMost(totalBytes)
                    val progress = if (totalBytes > 0) {
                        (overallBytes.toFloat() / totalBytes.toFloat() * 100f).coerceAtMost(100f)
                    } else 0f
                    com.glyph.glyph_v3.data.repo.MediaProgressManager.updateProgress(
                        messageId,
                        progress,
                        isUploading = true,
                        totalBytes = totalBytes,
                        transferredBytes = overallBytes
                    )
                }
                uploadTask.await()
                val downloadUrl = storageRef.downloadUrl.await().toString()

                uploadedBytes += prepared.metadata.originalSize

                val storageType = if (prepared.mediaType == com.glyph.glyph_v3.data.models.MediaType.VIDEO) {
                    com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.VIDEO
                } else {
                    com.glyph.glyph_v3.data.media.MediaStorageManager.MediaType.IMAGE
                }
                val persistedFile = com.glyph.glyph_v3.data.media.MediaStorageManager.saveMediaFromUri(
                    context = context,
                    chatId = chatId,
                    messageId = messageId,
                    mediaType = storageType,
                    sourceUri = prepared.finalUri,
                    itemIndex = index + 1
                )
                val persistedPath = persistedFile?.absolutePath ?: prepared.finalUri.toString()

                finalMediaItems.add(
                    com.glyph.glyph_v3.data.models.MediaItem(
                        url = downloadUrl,
                        localUri = persistedPath,
                        type = prepared.mediaType,
                        thumbnailUrl = null,
                        duration = prepared.metadata.duration,
                        fileSize = prepared.metadata.originalSize,
                        width = prepared.metadata.width,
                        height = prepared.metadata.height
                    )
                )
            }

            val finalJson = com.glyph.glyph_v3.data.models.Message.mediaItemsToJson(finalMediaItems)
            messageDao.updateMediaGroupMessage(messageId, finalJson, MessageStatus.SENT)
            com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)

            // Lean Firestore JSON — strip sender-local fields so the doc stays small
            // and the receiver can safely Gson-parse it.
            val finalJsonForFirestore = com.glyph.glyph_v3.data.models.Message.mediaItemsToJson(
                finalMediaItems.map { it.copy(localUri = null, thumbnailBase64 = null) }
            )

            val firestoreMessageData = hashMapOf<String, Any>(
                "id" to messageId,
                "text" to previewText,
                "senderId" to senderUid,
                "timestamp" to timestamp,
                "serverTimestamp" to FieldValue.serverTimestamp(),
                "status" to MessageStatus.SENT.name,
                "type" to "MEDIA_GROUP",
                "mediaItems" to finalJsonForFirestore,
                "mediaCount" to finalMediaItems.size,
                "fileSize" to totalBytes,
                "deliveredTo" to emptyList<String>(),
                "readBy" to emptyList<String>()
            )

            // RTDB payload mirrors firestore (the Cloud Function reads mediaItems
            // from here for FCM data; for groups the function fans out per-recipient
            // FCM via the same MEDIA_GROUP branch already deployed).
            val rtdbPayload = hashMapOf<String, Any>(
                "id" to messageId,
                "chatId" to chatId,
                "text" to previewText,
                "senderId" to senderUid,
                "timestamp" to ServerValue.TIMESTAMP,
                "type" to "MEDIA_GROUP",
                "mediaItems" to finalJsonForFirestore,
                "mediaCount" to finalMediaItems.size,
                "fileSize" to totalBytes
            )

            finalizeGroupMediaSend(
                chatId = chatId,
                messageId = messageId,
                senderUid = senderUid,
                timestamp = timestamp,
                lastMessagePreview = previewText,
                firestoreMessageData = firestoreMessageData,
                rtdbPayload = rtdbPayload
            )
        } catch (e: Exception) {
            Log.e(TAG, "Group MEDIA_GROUP send failed for $messageId", e)
            messageDao.updateMessageStatus(messageId, MessageStatus.FAILED)
            chatDao.updateLastMessage(chatId, "Media", timestamp, senderUid, MessageStatus.FAILED.name)
            com.glyph.glyph_v3.data.repo.MediaProgressManager.complete(messageId)
        }
    }

    // ────────────────────────────────────────────────────────────
    //  Internals
    // ────────────────────────────────────────────────────────────

    private fun requireUid(): String {
        return auth.currentUser?.uid
            ?: error("GroupChatRepository requires an authenticated user")
    }
}
