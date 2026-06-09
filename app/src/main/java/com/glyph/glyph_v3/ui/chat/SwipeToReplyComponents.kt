package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.launch

/**
 * SwipeableMessageBubbleWrapper - Wraps any message bubble with swipe-to-reply gesture
 * Implements WhatsApp-style horizontal swipe with reply icon animation and haptic feedback
 * 
 * Features:
 * - Right swipe gesture detection
 * - Elastic resistance beyond threshold
 * - Animated reply icon (fade + scale)
 * - Haptic feedback at reply threshold
 * - Smooth snap-back animation
 * - No conflict with vertical LazyColumn scrolling
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableMessageBubbleWrapper(
    message: Message,
    groupPosition: BubbleGroupPosition,
    isSelf: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onNormalClick: () -> Unit = {},
    onReply: () -> Unit = {},
    context: Context,
    textMeasurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    progress: MediaProgressManager.MediaProgress? = null,
    isWarmingUp: Boolean = false,
    imagesEnabled: Boolean = true,
    currentUserPhone: String = "",
    bubbleContent: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    
    // Swipe state
    var offsetX by remember { mutableStateOf(0f) }
    val offsetXAnim = remember { Animatable(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val replyThreshold = with(density) { 80.dp.toPx() }
    val maxSwipe = with(density) { 120.dp.toPx() }
    
    // Reply icon animation
    val iconAlpha by animateFloatAsState(
        targetValue = if (offsetX > 0) (offsetX / replyThreshold).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 100),
        label = "ReplyIconAlpha"
    )
    
    val iconScale by animateFloatAsState(
        targetValue = if (offsetX > replyThreshold * 0.8f) 1.1f else 0.7f + (iconAlpha * 0.3f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ReplyIconScale"
    )
    
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Reply icon (behind the bubble)
        if (iconAlpha > 0.01f && !isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(if (isSelf) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        alpha = iconAlpha
                        scaleX = iconScale
                        scaleY = iconScale
                    }
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_reply),
                    contentDescription = "Reply",
                    tint = glyphTheme.actionPrimary.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // Message bubble with swipe gesture
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX
                }
                .pointerInput(isSelectionMode) {
                    if (!isSelectionMode) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                hasTriggeredHaptic = false
                            },
                            onDragEnd = {
                                scope.launch {
                                    // Trigger reply if threshold crossed
                                    if (offsetX >= replyThreshold) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        onReply()
                                    }
                                    
                                    // Snap back animation
                                    offsetXAnim.snapTo(offsetX)
                                    offsetXAnim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                    offsetX = 0f
                                }
                            },
                            onDragCancel = {
                                scope.launch {
                                    offsetXAnim.snapTo(offsetX)
                                    offsetXAnim.animateTo(0f)
                                    offsetX = 0f
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                
                                // Only allow right swipe
                                if (dragAmount > 0 || offsetX > 0) {
                                    // Apply elastic resistance
                                    val newOffset = (offsetX + dragAmount).coerceIn(0f, maxSwipe)
                                    val resistance = if (newOffset > replyThreshold) {
                                        1f - ((newOffset - replyThreshold) / (maxSwipe - replyThreshold)) * 0.7f
                                    } else 1f
                                    
                                    offsetX = (offsetX + dragAmount * resistance).coerceIn(0f, maxSwipe)
                                    
                                    // Haptic feedback at threshold
                                    if (offsetX >= replyThreshold && !hasTriggeredHaptic) {
                                        hasTriggeredHaptic = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    } else if (offsetX < replyThreshold && hasTriggeredHaptic) {
                                        hasTriggeredHaptic = false
                                    }
                                }
                            }
                        )
                    }
                }
        ) {
            bubbleContent()
        }
    }
    
    // Observe animated value changes
    LaunchedEffect(offsetXAnim.value) {
        if (offsetXAnim.isRunning) {
            offsetX = offsetXAnim.value
        }
    }
}
