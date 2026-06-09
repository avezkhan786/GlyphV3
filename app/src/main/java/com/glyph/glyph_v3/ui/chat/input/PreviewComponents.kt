package com.glyph.glyph_v3.ui.chat.input

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * EditPreviewContent - Displays edit mode preview
 * Pure composable - no internal state
 */
@Composable
fun EditPreviewContent(
    message: Message,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    val accentColor = theme.actionPrimary
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.surfaceInput.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Edit icon
            Icon(
                painter = painterResource(id = R.drawable.ic_edit),
                contentDescription = "Edit",
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "✏️ Editing message",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textPrimary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Cancel Edit",
                    tint = theme.textPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * ReplyPreviewContent - Displays reply mode preview
 * Pure composable - no internal state
 */
@Composable
fun ReplyPreviewContent(
    message: Message,
    username: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    val accentColor = if (theme.isDark) theme.actionPrimary else theme.textLink
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.surfaceInput.copy(alpha = if (theme.isDark) 0.60f else 0.78f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(accentColor, RoundedCornerShape(2.dp))
            )
            
            Spacer(modifier = Modifier.width(10.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (message.isIncoming) "Replying to $username" else "Replying to yourself",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (message.type) {
                        MessageType.IMAGE -> "📷 Photo"
                        MessageType.VIDEO -> "🎥 Video"
                        MessageType.AUDIO -> "🎤 Audio"
                        MessageType.MEDIA_GROUP -> "📷 Album"
                        MessageType.STICKER -> "Sticker"
                        MessageType.KLIPY_EMOJI -> "Emoji"
                        MessageType.GIF -> "GIF"
                        else -> message.text ?: ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
            }
            
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_close),
                    contentDescription = "Cancel Reply",
                    tint = theme.textPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        // Separator line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(theme.borderInput.copy(alpha = 0.3f))
        )
    }
}
