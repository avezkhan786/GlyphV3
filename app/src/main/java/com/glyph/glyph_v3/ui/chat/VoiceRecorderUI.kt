package com.glyph.glyph_v3.ui.chat

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
// import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.glyph.glyph_v3.data.models.MessageStatus // Added Import
import kotlin.math.sin

@Composable
fun VoiceRecorderUI(
    isRecording: Boolean,
    isLocked: Boolean,
    duration: String,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onLock: () -> Unit,
    amplitude: Int
) {
    val view = LocalView.current
    
    // Slide in animation
    AnimatedVisibility(
        visible = isRecording,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp) // Match input height
                .background(glyphTheme.backgroundElevated)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isLocked) {
                // Locked Mode UI
                IconButton(onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    onCancel()
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = "Delete",
                        tint = Color.Red
                    )
                }
            } else {
                // Hold Mode UI - "Slide to cancel" hint could go here, but for now just simple
                Spacer(modifier = Modifier.width(16.dp))
                // Blinking red dot
                val infiniteTransition = rememberInfiniteTransition(label = "RecDot")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "RecDotAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Red.copy(alpha = alpha))
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Timer
            Text(
                text = duration,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum"
                ),
                color = glyphTheme.textPrimary
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Waveform Animation
            Box(modifier = Modifier.weight(1f)) {
                RecordingWaveform(amplitude = amplitude)
            }

            if (isLocked) {
                // Send Button for Locked Mode
                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSend()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(glyphTheme.sendButtonBackground, CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send_custom),
                        contentDescription = "Send",
                        tint = glyphTheme.sendButtonIcon
                    )
                }
            } else {
                // Hint text or empty space
                Text(
                    text = "Slide left to cancel",
                    style = MaterialTheme.typography.bodySmall,
                    color = glyphTheme.textSecondary,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }
    }
}

@Composable
fun RecordingWaveform(amplitude: Int) {
    // Simple simulated waveform
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "Waveform")
        repeat(20) { index ->
            val heightScale by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 300 + (index * 50),
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 100)
                ),
                label = "Bar$index"
            )
            
            // Use amplitude to modulate height if available, otherwise just animation
            val effectiveHeight = if (amplitude > 0) {
                // Normalize amplitude (0-32767)
                val normAmp = (amplitude / 32767f).coerceIn(0.1f, 1f)
                heightScale * normAmp
            } else {
                heightScale * 0.5f
            }

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp * effectiveHeight)
                    .background(glyphTheme.textSecondary, CircleShape)
            )
        }
    }
}


@Composable
fun VoiceMessageBubble(
    isSelf: Boolean,
    isPlaying: Boolean,
    progress: Float,
    currentPosition: String,
    totalDuration: String,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    // Style parameters
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = Color.Gray,
    gradient: androidx.compose.ui.graphics.Brush? = null,
    contentColor: Color = Color.White,
    borderStroke: androidx.compose.foundation.BorderStroke? = null,
    durationMs: Long = 0L,
    timestamp: String = "", 
    status: MessageStatus? = null,
    avatarUrl: String? = null
) {
    // Determine background modifier
    val backgroundModifier = if (gradient != null) {
        Modifier.background(gradient, shape)
    } else {
        Modifier.background(backgroundColor, shape)
    }

    val borderModifier = if (borderStroke != null) {
        Modifier.border(borderStroke, shape)
    } else Modifier

    // Generate deterministic waveform based on message duration - gives unique "fingerprint"
    val waveformPoints = remember(durationMs) {
        val random = java.util.Random(durationMs)
        List(40) { (0.2f + (random.nextFloat() * 0.8f)) }
    }

    Box(
        modifier = modifier
            .width(240.dp) // Re-widened slightly to accommodate larger content
            .wrapContentHeight()
            .then(backgroundModifier)
            .then(borderModifier)
    ) {
        // Main content Row
        Row(
            modifier = Modifier
                .padding(0.dp)
                .fillMaxWidth()
                .wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Interactive Play/Pause Disc
            Box(
                modifier = Modifier
                    .size(44.dp) 
                    .clip(CircleShape)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                val hasAvatar = !avatarUrl.isNullOrBlank()
                if (hasAvatar) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.18f))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(contentColor.copy(alpha = 0.12f))
                    )
                }

                // Background progress ring (Subtle)
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF4CAF50), // Standard Material Green
                    strokeWidth = 2.5.dp, 
                    trackColor = Color.Transparent
                )

                Icon(
                    painter = painterResource(
                        id = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(26.dp) // Large, centered play icon
                )
            }

            Spacer(modifier = Modifier.width(13.dp)) // Increased spacing for better separation

            // 2. Waveform & Meta Column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .wrapContentHeight()
            ) {
                // Waveform Display (Deterministic)
                Box(
                    modifier = Modifier
                        .height(36.dp) // Increased height for visual impact
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                onSeek((offset.x / size.width).coerceIn(0f, 1f))
                            }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val barWidth = 2.5.dp.toPx()
                        val gap = 1.5.dp.toPx()
                        val totalBarWidth = barWidth + gap
                        val maxBars = (size.width / totalBarWidth).toInt()
                        
                        val centerY = size.height / 2
                        
                        for (i in 0 until maxBars.coerceAtMost(waveformPoints.size)) {
                            val x = i * totalBarWidth
                            // Use the pre-calculated points
                            val pointValue = waveformPoints.getOrElse(i) { 0.5f }
                            val barHeight = pointValue * size.height * 0.75f
                            
                            val isPlayed = (x / size.width) <= progress
                            
                            drawRoundRect(
                                color = if (isPlayed) contentColor else contentColor.copy(alpha = 0.25f),
                                topLeft = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2),
                                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Metadata Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isPlaying) currentPosition else totalDuration,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum"
                        ),
                        color = contentColor.copy(alpha = 0.9f)
                    )

                    // Unified Timestamp & Status
                    val metaColor = contentColor.copy(alpha = 0.6f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (timestamp.isNotEmpty()) {
                            Text(
                                text = timestamp,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                color = metaColor
                            )
                        }
                        
                        if (isSelf && status != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            
                            // 1. Played Indicator (Mic)
                            // "Muted color" (Gray) -> Green when played
                            val isPlayed = status == MessageStatus.PLAYED
                            val micColor = if (isPlayed) Color(0xFF4CAF50) else metaColor
                            
                            Icon(
                                painter = painterResource(id = R.drawable.ic_voice_played),
                                contentDescription = "Voice Status",
                                modifier = Modifier.size(18.dp),
                                tint = micColor
                            )
                            
                            Spacer(modifier = Modifier.width(2.dp))

                            // 2. Delivery Status (Ticks)
                            val iconRes = when (status) {
                                MessageStatus.PLAYED, MessageStatus.READ -> R.drawable.ic_double_check
                                MessageStatus.DELIVERED -> R.drawable.ic_double_check
                                MessageStatus.SENT -> R.drawable.ic_check
                                else -> R.drawable.ic_clock
                            }
                            
                            val tickColor = if (status == MessageStatus.READ || status == MessageStatus.PLAYED) {
                                Color(0xFF4FC3F7) 
                            } else {
                                metaColor
                            }

                            Icon(
                                painter = painterResource(id = iconRes),
                                contentDescription = "Delivery Status",
                                modifier = Modifier.size(22.dp), 
                                tint = tickColor
                            )
                        }
                    }
                }
            }
        }
    }
}
