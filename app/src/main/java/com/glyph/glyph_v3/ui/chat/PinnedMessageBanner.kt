package com.glyph.glyph_v3.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.MessageType

// ────────────────────────────────────────────────────────────────────────────
// Colour palette (WhatsApp dark, matches recent screenshot)
// ────────────────────────────────────────────────────────────────────────────
private val BannerBg        = Color(0xFF0B1014)   // Background as requested
private val BannerAccent    = Color(0xFF25D366)   // WhatsApp green
private val BannerText      = Color(0xFFE9EDEF)   // primary text
private val BannerSub       = Color(0xFF8696A0)   // secondary text
private val BannerIconColor = Color(0xFF8D9598)   // Keep icon tint as requested
private val BannerThumbBg   = Color(0xFF23282C)   // Icon background as requested
private val BannerDivider   = Color(0xFF2A3942)   // thin bottom divider

// ────────────────────────────────────────────────────────────────────────────
// Public API
// ────────────────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PinnedMessageBanner(
    pinnedMessages: List<LocalMessage>,
    currentIndex: Int = 0,
    onBannerTap: (LocalMessage) -> Unit = {},
    onUnpin: (LocalMessage) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val visible = pinnedMessages.isNotEmpty()
    val safeIndex = if (pinnedMessages.isEmpty()) 0 else currentIndex.coerceIn(0, pinnedMessages.lastIndex)
    val current = pinnedMessages.getOrNull(safeIndex)

    var showUnpinDialog by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(tween(200)),
        exit  = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(200)),
        modifier = modifier
    ) {
        if (current == null) return@AnimatedVisibility

        Column(modifier = Modifier.fillMaxWidth().background(BannerBg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onBannerTap(current) },
                        onLongClick = { showUnpinDialog = true }
                    )
            ) {
                // ── Dot indicator (WhatsApp-style left bars) ───────────────
                DotIndicatorColumn(
                    count  = pinnedMessages.size,
                    active = safeIndex,
                    modifier = Modifier
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                        .width(3.dp)
                        .height(36.dp)
                )

                Spacer(Modifier.width(10.dp))

                // ── Pin icon (Keep icon as in WhatsApp) ────────────────────
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 8.dp)
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BannerThumbBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_keep_v2),
                        contentDescription = null,
                        tint = BannerIconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // ── Text alignment ─────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp, bottom = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (current.type == MessageType.IMAGE || current.type == MessageType.MEDIA_GROUP) {
                            Icon(
                                painter = painterResource(
                                    if (current.type == MessageType.IMAGE) R.drawable.ic_photo 
                                    else R.drawable.ic_media
                                ),
                                contentDescription = null,
                                tint = BannerIconColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        
                        Text(
                            text = pinnedMessagePreview(current),
                            color = BannerText,
                            fontSize = if (current.type == MessageType.TEXT) 18.sp else 16.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // ── Thumbnail on the FAR RIGHT ─────────────────────────────
                val thumbUrl = when (current.type) {
                    MessageType.IMAGE -> current.localUri ?: current.imageUrl
                    MessageType.VIDEO -> current.thumbnailUrl ?: current.localUri ?: current.videoUrl
                    else -> null
                }
                
                if (thumbUrl != null) {
                    Box(
                        modifier = Modifier
                            .height(56.dp)
                            .width(56.dp)
                            .clip(RoundedCornerShape(0.dp))
                            .background(BannerThumbBg)
                    ) {
                        AsyncImage(
                            model = thumbUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    // Maintain horizontal end padding when no thumbnail is present
                    Spacer(Modifier.width(12.dp))
                }
            }

            // Thin bottom divider
            HorizontalDivider(thickness = 0.5.dp, color = BannerDivider)
        }
    }

    // Unpin confirmation dialog
    if (showUnpinDialog && current != null) {
        UnpinConfirmDialog(
            onDismiss = { showUnpinDialog = false },
            onConfirm = {
                showUnpinDialog = false
                onUnpin(current)
            }
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Dot indicator column  (WhatsApp-style narrow bars)
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun DotIndicatorColumn(count: Int, active: Int, modifier: Modifier = Modifier) {
    if (count <= 1) {
        // Single pin: one full-height white bar
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White)
        )
        return
    }
    // Multiple pins: stacked segments, active one is white, others dimmed
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        val segmentWeight = 1f / count
        repeat(count) { i ->
            val isActive = i == active
            Box(
                modifier = Modifier
                    .weight(segmentWeight)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) Color.White else BannerSub.copy(alpha = 0.4f))
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Unpin confirmation dialog  (minimal – matches WhatsApp style dialog)
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun UnpinConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13181C),
        title = {
            Text("Unpin message?", color = BannerText, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Text(
                "This message will no longer appear at the top of this chat.",
                color = BannerSub,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("UNPIN", color = BannerAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = BannerSub)
            }
        }
    )
}

// ────────────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────────────

/** Returns a one-line preview string for [msg] based on its type. */
private fun pinnedMessagePreview(msg: LocalMessage): String = when (msg.type) {
    MessageType.IMAGE       -> "Photo"
    MessageType.VIDEO       -> "Video"
    MessageType.AUDIO       -> "Audio"
    MessageType.DOCUMENT    -> msg.text.takeIf { it.isNotBlank() } ?: "Document"
    MessageType.GIF         -> "GIF"
    MessageType.STICKER     -> "Sticker"
    MessageType.MEME        -> "Meme"
    MessageType.MEDIA_GROUP -> "Media"
    MessageType.CONTACT     -> "Contact"
    MessageType.KLIPY_EMOJI -> "Emoji"
    MessageType.TEXT        -> msg.text.takeIf { it.isNotBlank() } ?: ""
    MessageType.STATUS_REPLY -> "Replied to status"
    MessageType.SYSTEM      -> msg.text.takeIf { it.isNotBlank() } ?: ""
}
