package com.glyph.glyph_v3.ui.chat

import android.net.Uri
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import java.io.File

internal fun resolveChatBubbleMediaModel(
    message: Message,
    localFilePath: String? = null
): Any? {
    when (message.type) {
        MessageType.IMAGE,
        MessageType.VIDEO,
        MessageType.GIF,
        MessageType.MEME,
        MessageType.STICKER,
        MessageType.KLIPY_EMOJI -> Unit
        else -> return null
    }

    if (!localFilePath.isNullOrBlank()) return localFilePath

    val isSticker = message.type == MessageType.STICKER || message.type == MessageType.KLIPY_EMOJI
    val isAnimatedInline = isSticker || message.type == MessageType.GIF || message.type == MessageType.MEME

    if (!message.localUri.isNullOrBlank()) {
        val uri = Uri.parse(message.localUri)
        if (uri.scheme == "file" || message.localUri!!.startsWith("/")) {
            val path = uri.path ?: message.localUri!!
            if (File(path).exists()) {
                return message.localUri
            }
        } else {
            return message.localUri
        }
    }

    return when (message.type) {
        MessageType.IMAGE -> message.imageUrl ?: message.thumbnailUrl
        MessageType.VIDEO -> message.thumbnailUrl ?: message.videoUrl ?: message.imageUrl
        else -> if (isAnimatedInline) {
            message.imageUrl ?: message.thumbnailUrl
        } else {
            message.thumbnailUrl ?: message.imageUrl
        }
    }?.takeIf { it.isNotBlank() }
}