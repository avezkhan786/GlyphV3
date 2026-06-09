package com.glyph.glyph_v3.ui.chat.forward

import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.Message
import com.google.gson.Gson
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ForwardMessageCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private val payloads = ConcurrentHashMap<String, Payload>()
    private val gson = Gson()

    fun put(sourceChatId: String, messages: List<Message>): String {
        pruneExpired()
        val token = UUID.randomUUID().toString()
        payloads[token] = Payload(
            sourceChatId = sourceChatId,
            messages = messages.map { it.toLocalMessageSnapshot() },
            createdAtMs = System.currentTimeMillis()
        )
        return token
    }

    fun get(token: String?, messageIds: List<String>, sourceChatId: String): List<LocalMessage> {
        pruneExpired()
        if (token.isNullOrBlank()) return emptyList()
        val payload = payloads[token] ?: return emptyList()
        if (payload.sourceChatId != sourceChatId || payload.isExpired()) {
            payloads.remove(token)
            return emptyList()
        }
        val byId = payload.messages.associateBy { it.id }
        return messageIds.mapNotNull(byId::get)
    }

    fun remove(token: String?) {
        if (!token.isNullOrBlank()) payloads.remove(token)
    }

    private fun pruneExpired() {
        payloads.entries.removeIf { it.value.isExpired() }
    }

    private fun Payload.isExpired(): Boolean {
        return System.currentTimeMillis() - createdAtMs > TTL_MS
    }

    private fun Message.toLocalMessageSnapshot(): LocalMessage {
        return LocalMessage(
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
            replyToType = replyToType?.name,
            replyPreviewUrl = replyPreviewUrl,
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
            serverTimestamp = serverTimestamp,
            isForwarded = isForwarded,
            reactionsJson = reactions.takeIf { it.isNotEmpty() }?.let(gson::toJson)
        )
    }

    private data class Payload(
        val sourceChatId: String,
        val messages: List<LocalMessage>,
        val createdAtMs: Long
    )
}