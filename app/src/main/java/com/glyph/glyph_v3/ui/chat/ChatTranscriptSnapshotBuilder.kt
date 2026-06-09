package com.glyph.glyph_v3.ui.chat

import com.glyph.glyph_v3.data.models.Message
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ChatTranscriptSnapshotBuilder {
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.getDefault())

    fun build(messages: List<Message>): List<ChatListItem> {
        if (messages.isEmpty()) return emptyList()

        val result = ArrayList<ChatListItem>(messages.size + 12)
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        var lastDate: LocalDate? = null
        var currentHeaderText = ""

        for (message in messages) {
            val messageDate = Instant.ofEpochMilli(message.timestamp).atZone(zoneId).toLocalDate()
            if (messageDate != lastDate) {
                currentHeaderText = when (messageDate) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> dateFormatter.format(messageDate)
                }
                result.add(ChatListItem.DateHeader(currentHeaderText))
                lastDate = messageDate
            }

            result.add(
                ChatListItem.MessageItem(
                    message = message,
                    dateString = currentHeaderText,
                    isEmojiContent = EmojiUtils.isEmojiOnlyMessage(message.text)
                )
            )
        }

        for (index in result.indices) {
            val current = result[index] as? ChatListItem.MessageItem ?: continue
            val previous = result.getOrNull(index - 1) as? ChatListItem.MessageItem
            val next = result.getOrNull(index + 1) as? ChatListItem.MessageItem

            val hasPreviousSameSender = previous?.message?.senderId == current.message.senderId
            val hasNextSameSender = next?.message?.senderId == current.message.senderId
            val position = when {
                hasPreviousSameSender && hasNextSameSender -> BubbleGroupPosition.MIDDLE
                hasPreviousSameSender -> BubbleGroupPosition.BOTTOM
                hasNextSameSender -> BubbleGroupPosition.TOP
                else -> BubbleGroupPosition.SINGLE
            }

            if (position != BubbleGroupPosition.SINGLE) {
                result[index] = current.copy(groupPosition = position)
            }
        }

        return result
    }
}