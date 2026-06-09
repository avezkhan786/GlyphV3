package com.glyph.glyph_v3.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * QuotedReplyPreview - Renders quoted message preview inside message bubbles
 * WhatsApp-style compact quoted preview with accent line
 * 
 * This composable should be placed at the top of message bubbles when rendering
 * messages that are replies to other messages.
 * 
 * @param replyToText The text content of the original message being replied to
 * @param replyToType The type of the original message (TEXT, IMAGE, VIDEO, etc.)
 * @param replyToSenderId The sender ID of the original message
 * @param isSelf Whether the current message bubble is from the current user
 * @param currentUserPhone The phone number of the current user (for sender identification)
 * @param modifier Modifier for customization
 */
@Composable
fun QuotedReplyPreview(
    replyToText: String?,
    replyToType: MessageType?,
    replyToSenderId: String?,
    isSelf: Boolean,
    currentUserPhone: String,
    otherUsername: String,
    modifier: Modifier = Modifier,
    onQuotedMessageClick: (() -> Unit)? = null
) {
    val theme = glyphTheme
    val accentColor = if (theme.isDark) theme.actionPrimary else theme.textSecondary.copy(alpha = 0.9f)
    val isReplyToSelf = replyToSenderId == currentUserPhone
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelf) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.15f),
        onClick = onQuotedMessageClick ?: {}
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vertical accent line
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(38.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isReplyToSelf) "You" else otherUsername,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when(replyToType) {
                        MessageType.IMAGE -> "📷 Photo"
                        MessageType.VIDEO -> "🎥 Video"
                        MessageType.AUDIO -> "🎤 Audio"
                        MessageType.MEDIA_GROUP -> "📷 Album"
                        MessageType.CONTACT -> "👤 Contact"
                        else -> replyToText ?: "Message"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelf) {
                        theme.bubbleOutgoingText.copy(alpha = 0.8f)
                    } else {
                        theme.bubbleIncomingText.copy(alpha = 0.8f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Helper function to check if a message has reply metadata
 */
fun Message.hasReplyMetadata(): Boolean {
    return replyToMessageId != null
}

/**
 * Helper function to render quoted preview for a message if it has reply metadata
 */
@Composable
fun Message.RenderQuotedPreviewIfExists(
    isSelf: Boolean,
    currentUserPhone: String,
    otherUsername: String,
    modifier: Modifier = Modifier,
    onQuotedMessageClick: (() -> Unit)? = null
) {
    if (hasReplyMetadata()) {
        QuotedReplyPreview(
            replyToText = replyToText,
            replyToType = replyToType,
            replyToSenderId = replyToSenderId,
            isSelf = isSelf,
            currentUserPhone = currentUserPhone,
            otherUsername = otherUsername,
            modifier = modifier,
            onQuotedMessageClick = onQuotedMessageClick
        )
    }
}
