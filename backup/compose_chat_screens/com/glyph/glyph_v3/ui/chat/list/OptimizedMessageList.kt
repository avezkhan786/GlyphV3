package com.glyph.glyph_v3.ui.chat.list

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.ui.chat.BubbleGroupPosition
import com.glyph.glyph_v3.ui.chat.ChatListItem
import com.glyph.glyph_v3.ui.chat.ChatListItemList
import com.glyph.glyph_v3.ui.chat.MediaProgressMap
import com.glyph.glyph_v3.ui.chat.SelectedMessagesSet
import com.glyph.glyph_v3.ui.chat.BubbleThemeSet
import com.glyph.glyph_v3.ui.chat.AudioPlaybackInfo
import com.glyph.glyph_v3.ui.chat.LocalAudioPlaybackState
import com.glyph.glyph_v3.ui.theme.glyphTheme
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.launch

/**
 * OptimizedMessageList - High-performance message list with list-level swipe handling
 * 
 * Architecture:
 * 1. Swipe-to-reply handled at LIST level, not per-item
 * 2. Message rows are pure/dumb - emit events only
 * 3. Selection state uses derivedStateOf per-item
 * 4. No gesture detection inside message rows
 * 
 * Performance Optimizations:
 * - Single swipe gesture detector at list level
 * - Message items skip gesture/animation code paths
 * - Proper contentType for efficient recycling
 * - derivedStateOf for selection to minimize recomposition
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OptimizedMessageList(
    messages: ChatListItemList,
    listState: LazyListState,
    isTyping: Boolean,
    mediaProgress: MediaProgressMap,
    selectedIds: SelectedMessagesSet,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    onToggleSelection: (String) -> Unit,
    onReply: (Message) -> Unit,
    currentUserPhone: String,
    otherUsername: String,
    isFastScrolling: Boolean,
    isWarmup: Boolean,
    textMeasurer: TextMeasurer,
    density: Density,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    
    // OPTIMIZATION 1: Pre-compute theme set ONCE at list level
    // Eliminates 4 theme lookups + 3 conditionals per message
    val theme = glyphTheme
    val bubbleTheme = remember(theme) {
        BubbleThemeSet(
            outgoingGradient = theme.gradientBubbleOutgoing,
            outgoingSolid = theme.bubbleOutgoingBackground,
            outgoingText = theme.bubbleOutgoingText,
            incomingGradient = theme.gradientBubbleIncoming,
            incomingSolid = theme.bubbleIncomingBackground,
            incomingText = theme.bubbleIncomingText,
            timestamp = theme.bubbleTimestamp,
            borderStroke = BorderStroke(1.dp, theme.bubbleBorder),
            cornerRadiusMedium = theme.cornerRadiusMedium.value
        )
    }
    
    // OPTIMIZATION 2: Centralize audio playback state at list level
    // Eliminates O(n) derivedStateOf + string formatting in message rows
    val audioPlaybackState = LocalAudioPlaybackState.current
    val currentAudioInfo by remember(audioPlaybackState) {
        derivedStateOf {
            if (audioPlaybackState != null && audioPlaybackState.isPlaying) {
                val posSec = ((audioPlaybackState.progress ?: 0f) * (audioPlaybackState.audioDuration ?: 0L) / 1000).toLong()
                AudioPlaybackInfo(
                    messageId = audioPlaybackState.playingMessageId ?: "",
                    isPlaying = true,
                    progress = audioPlaybackState.progress ?: 0f,
                    formattedPosition = String.format("%d:%02d", posSec / 60, posSec % 60),
                    audioDuration = audioPlaybackState.audioDuration ?: 0L
                )
            } else null
        }
    }
    
    // Pre-calculate widths
    val screenWidth = configuration.screenWidthDp.dp
    val maxTextWidth = remember(screenWidth) { screenWidth * 0.90f }
    val maxMediaWidth = remember(screenWidth) { screenWidth * 0.70f }
    
    // List-level swipe state - only one message can be swiped at a time
    val swipeState = remember { ListSwipeState() }
    
    // Reply highlight state
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }
    
    // Scroll to reply function
    val scrollToReply: (String) -> Unit = remember(messages.items) {
        { replyId: String ->
            val index = messages.items.indexOfFirst { 
                it is ChatListItem.MessageItem && it.message.id == replyId 
            }
            if (index != -1) {
                scope.launch {
                    val typingOffset = 1
                    val targetIndex = index + typingOffset
                    
                    val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                    if (!isVisible) {
                        listState.animateScrollToItem(targetIndex)
                    }
                    
                    highlightedMessageId = null
                    kotlinx.coroutines.delay(50)
                    highlightedMessageId = replyId
                }
            }
        }
    }

    // List-level swipe gesture detector enablement
    val swipeEnabled = !isFastScrolling && !isWarmup && selectedIds.ids.isEmpty()
    val replyThresholdPx = with(density) { 72.dp.toPx() }
    val maxSwipePx = with(density) { 120.dp.toPx() }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(swipeEnabled, messages, listState) {
                    if (!swipeEnabled) return@pointerInput

                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            hasTriggeredHaptic = false

                            // Find which list item is being touched based on viewport Y
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            val touchedItem = visibleItems.find { itemInfo ->
                                offset.y >= itemInfo.offset && offset.y < itemInfo.offset + itemInfo.size
                            }

                            if (touchedItem != null) {
                                // Account for the typing indicator item at index 0
                                val index = touchedItem.index - 1
                                val item = messages.items.getOrNull(index)
                                if (item is ChatListItem.MessageItem && !item.message.isDeletedForAll) {
                                    swipeState.startSwipe(item.message.id, !item.message.isIncoming)
                                }
                            }
                        },
                        onDragEnd = {
                            val messageId = swipeState.swipingMessageId.value
                            val currentOffset = swipeState.offsetX.value

                            if (messageId != null && kotlin.math.abs(currentOffset) >= replyThresholdPx) {
                                val message = messages.items
                                    .filterIsInstance<ChatListItem.MessageItem>()
                                    .find { it.message.id == messageId }
                                    ?.message

                                if (message != null) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onReply(message)
                                }
                            }

                            swipeState.endSwipe()
                        },
                        onDragCancel = {
                            swipeState.endSwipe()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            if (swipeState.swipingMessageId.value == null) {
                                return@detectHorizontalDragGestures
                            }

                            change.consume()

                            val isSelf = swipeState.swipingIsSelf.value
                            val currentOffset = swipeState.offsetX.value

                            // Direction validation
                            val isValidDirection = if (isSelf) {
                                dragAmount < 0 || currentOffset < 0
                            } else {
                                dragAmount > 0 || currentOffset > 0
                            }

                            if (isValidDirection) {
                                val absTarget = kotlin.math.abs(currentOffset + dragAmount)

                                // Elastic resistance
                                val resistance = if (absTarget > replyThresholdPx) {
                                    1f - ((absTarget - replyThresholdPx) / (maxSwipePx - replyThresholdPx))
                                        .coerceIn(0f, 1f) * 0.7f
                                } else 1f

                                val resistedDrag = dragAmount * resistance
                                val newOffset = currentOffset + resistedDrag

                                // Clamp based on direction
                                val clampedOffset = if (isSelf) {
                                    newOffset.coerceIn(-maxSwipePx, 0f)
                                } else {
                                    newOffset.coerceIn(0f, maxSwipePx)
                                }

                                swipeState.updateOffset(clampedOffset)

                                // Haptic feedback at threshold
                                val absClamped = kotlin.math.abs(clampedOffset)
                                if (!hasTriggeredHaptic && absClamped >= replyThresholdPx) {
                                    hasTriggeredHaptic = true
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                } else if (hasTriggeredHaptic && absClamped < replyThresholdPx * 0.85f) {
                                    hasTriggeredHaptic = false
                                }
                            }
                        }
                    )
                },
            contentPadding = PaddingValues(bottom = 4.dp, top = 4.dp),
            flingBehavior = ScrollableDefaults.flingBehavior()
        ) {
            // Typing indicator
            item(
                key = "typing_indicator",
                contentType = "typing_indicator"
            ) {
                AnimatedVisibility(
                    visible = isTyping,
                    enter = fadeIn(animationSpec = tween(120)) + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut(animationSpec = tween(160)) + shrinkVertically(shrinkTowards = Alignment.Top)
                ) {
                    TypingIndicatorRow(
                        textMeasurer = textMeasurer,
                        density = density
                    )
                }
            }
            
            items(
                items = messages.items,
                key = { it.id },
                contentType = { item ->
                    when (item) {
                        is ChatListItem.MessageItem -> {
                            val direction = if (item.message.isIncoming) "in" else "out"
                            when (item.message.type) {
                                MessageType.IMAGE -> "message_image_$direction"
                                MessageType.VIDEO -> "message_video_$direction"
                                MessageType.AUDIO -> "message_audio_$direction"
                                MessageType.MEDIA_GROUP -> "message_group_$direction"
                                else -> if (item.message.replyToMessageId != null) 
                                    "message_text_reply_$direction" 
                                else 
                                    "message_text_$direction"
                            }
                        }
                        is ChatListItem.DateHeader -> "date_header"
                    }
                }
            ) { item ->
                key(item.id) {
                    when (item) {
                        is ChatListItem.MessageItem -> {
                            // Use derivedStateOf to prevent recomposition when unrelated selection changes
                            val isSelected by remember(item.message.id) {
                                derivedStateOf { selectedIds.ids.contains(item.message.id) }
                            }
                            val isSelectionMode by remember {
                                derivedStateOf { selectedIds.ids.isNotEmpty() }
                            }
                            
                            val isSelf = !item.message.isIncoming
                            val msgMaxWidth = if (item.message.type == MessageType.IMAGE || 
                                item.message.type == MessageType.VIDEO) maxMediaWidth else maxTextWidth
                            
                            // Swipe offset for this message - only non-zero for the actively swiped message
                            val swipeOffset by remember(item.message.id) {
                                derivedStateOf {
                                    if (swipeState.swipingMessageId.value == item.message.id) {
                                        swipeState.offsetX.value
                                    } else 0f
                                }
                            }
                            
                            // Pure message row with swipe offset applied from list level
                            PureMessageRow(
                                message = item.message,
                                groupPosition = item.groupPosition,
                                isSelf = isSelf,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                swipeOffset = swipeOffset,
                                maxWidth = msgMaxWidth,
                                progress = mediaProgress.progress[item.message.id],
                                currentUserPhone = currentUserPhone,
                                otherUsername = otherUsername,
                                isHighlighted = highlightedMessageId == item.message.id,
                                context = context,
                                textMeasurer = textMeasurer,
                                density = density,
                                bubbleTheme = bubbleTheme,
                                currentAudioInfo = currentAudioInfo,
                                hapticFeedback = hapticFeedback,
                                view = view,
                                onToggleSelection = { onToggleSelection(item.message.id) },
                                onMediaClick = { index -> onMediaClick(item.message, index) },
                                onDownloadMedia = { onDownloadMedia(item.message) },
                                onReplyClick = scrollToReply,
                                onHighlightFinished = { highlightedMessageId = null }
                            )
                        }
                        is ChatListItem.DateHeader -> {
                            DateHeaderRow(
                                date = item.dateString,
                                textMeasurer = textMeasurer,
                                density = density
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ListSwipeState - Manages swipe state at the list level
 * Only one message can be swiped at a time
 */
@Stable
class ListSwipeState {
    val swipingMessageId = mutableStateOf<String?>(null)
    val offsetX = mutableStateOf(0f)
    val swipingIsSelf = mutableStateOf(false)
    
    fun startSwipe(messageId: String, isSelf: Boolean) {
        swipingMessageId.value = messageId
        swipingIsSelf.value = isSelf
        offsetX.value = 0f
    }
    
    fun updateOffset(newOffset: Float) {
        offsetX.value = newOffset
    }
    
    fun endSwipe() {
        swipingMessageId.value = null
        offsetX.value = 0f
    }
}

/**
 * SwipeGestureLayer - Handles swipe-to-reply at list level
 * Detects which message is being swiped and manages the gesture
 */
@Composable
private fun SwipeGestureLayer(
    swipeState: ListSwipeState,
    listState: LazyListState,
    messages: ChatListItemList,
    density: Density,
    onReply: (Message) -> Unit,
    enabled: Boolean
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    
    val replyThreshold = with(density) { 72.dp.toPx() }
    val maxSwipe = with(density) { 120.dp.toPx() }
    
    var hasTriggeredHaptic by remember { mutableStateOf(false) }
    
    if (!enabled) return
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        hasTriggeredHaptic = false
                        
                        // Find which message is being touched based on position
                        val visibleItems = listState.layoutInfo.visibleItemsInfo
                        val touchedItem = visibleItems.find { itemInfo ->
                            offset.y >= itemInfo.offset && offset.y < itemInfo.offset + itemInfo.size
                        }
                        
                        if (touchedItem != null) {
                            val index = touchedItem.index - 1 // Account for typing indicator
                            if (index >= 0 && index < messages.items.size) {
                                val item = messages.items[index]
                                if (item is ChatListItem.MessageItem && !item.message.isDeletedForAll) {
                                    swipeState.startSwipe(item.message.id, !item.message.isIncoming)
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        val messageId = swipeState.swipingMessageId.value
                        val currentOffset = swipeState.offsetX.value
                        
                        if (messageId != null && kotlin.math.abs(currentOffset) >= replyThreshold) {
                            val message = messages.items
                                .filterIsInstance<ChatListItem.MessageItem>()
                                .find { it.message.id == messageId }
                                ?.message
                            
                            if (message != null) {
                                onReply(message)
                            }
                        }
                        
                        swipeState.endSwipe()
                    },
                    onDragCancel = {
                        swipeState.endSwipe()
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (swipeState.swipingMessageId.value == null) return@detectHorizontalDragGestures
                        
                        change.consume()
                        
                        val isSelf = swipeState.swipingIsSelf.value
                        val currentOffset = swipeState.offsetX.value
                        
                        // Direction validation
                        val isValidDirection = if (isSelf) {
                            dragAmount < 0 || currentOffset < 0
                        } else {
                            dragAmount > 0 || currentOffset > 0
                        }
                        
                        if (isValidDirection) {
                            val absTarget = kotlin.math.abs(currentOffset + dragAmount)
                            
                            // Elastic resistance
                            val resistance = if (absTarget > replyThreshold) {
                                1f - ((absTarget - replyThreshold) / (maxSwipe - replyThreshold)).coerceIn(0f, 1f) * 0.7f
                            } else 1f
                            
                            val resistedDrag = dragAmount * resistance
                            val newOffset = currentOffset + resistedDrag
                            
                            // Clamp based on direction
                            val clampedOffset = if (isSelf) {
                                newOffset.coerceIn(-maxSwipe, 0f)
                            } else {
                                newOffset.coerceIn(0f, maxSwipe)
                            }
                            
                            swipeState.updateOffset(clampedOffset)
                            
                            // Haptic feedback at threshold
                            val absClamped = kotlin.math.abs(clampedOffset)
                            if (!hasTriggeredHaptic && absClamped >= replyThreshold) {
                                hasTriggeredHaptic = true
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            } else if (hasTriggeredHaptic && absClamped < replyThreshold * 0.85f) {
                                hasTriggeredHaptic = false
                            }
                        }
                    }
                )
            }
    )
}

/**
 * PureMessageRow - Pure/dumb message row composable
 * 
 * Rules:
 * 1. NO gesture detection
 * 2. NO coroutines
 * 3. NO animations defined here (receives swipeOffset)
 * 4. NO AI/translation logic
 * 5. Only renders: text/media, timestamp, delivery status
 * 6. Only emits events via callbacks
 */
@Composable
fun PureMessageRow(
    message: Message,
    groupPosition: BubbleGroupPosition,
    isSelf: Boolean,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    swipeOffset: Float,
    maxWidth: Dp,
    progress: Float?,
    currentUserPhone: String,
    otherUsername: String,
    isHighlighted: Boolean,
    context: Context,
    textMeasurer: TextMeasurer,
    density: Density,
    bubbleTheme: BubbleThemeSet,
    currentAudioInfo: AudioPlaybackInfo?,
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    view: android.view.View,
    onToggleSelection: () -> Unit,
    onMediaClick: (Int) -> Unit,
    onDownloadMedia: () -> Unit,
    onReplyClick: (String) -> Unit,
    onHighlightFinished: () -> Unit
) {
    // Reply icon visibility based on swipe offset
    val replyThreshold = with(density) { 72.dp.toPx() }
    val absOffset = kotlin.math.abs(swipeOffset)
    val iconAlpha = (absOffset / replyThreshold).coerceIn(0f, 1f)
    val iconScale = if (absOffset > replyThreshold * 0.8f) 1.0f else 0.8f + (iconAlpha * 0.2f)
    
    Box(modifier = Modifier.fillMaxWidth()) {
        // Reply icon (behind bubble)
        if (iconAlpha > 0.01f && !isSelectionMode) {
            ReplyIconIndicator(
                isSelf = isSelf,
                iconAlpha = iconAlpha,
                iconScale = iconScale,
                groupPosition = groupPosition,
                density = density
            )
        }
        
        // Message bubble with swipe offset applied
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = swipeOffset }
        ) {
            // Delegate to existing MessageBubble for actual rendering
            // This keeps the pure row thin while reusing existing bubble logic
            com.glyph.glyph_v3.ui.chat.MessageBubble(
                message = message,
                groupPosition = groupPosition,
                isSelf = isSelf,
                isSelected = isSelected,
                isSelectionMode = isSelectionMode,
                onToggleSelection = onToggleSelection,
                onNormalClick = { 
                    if (message.type == MessageType.IMAGE || message.type == MessageType.VIDEO) {
                        val isPendingDownload = message.status == MessageStatus.PENDING_DOWNLOAD || 
                            message.status == MessageStatus.DOWNLOAD_FAILED
                        val isDownloading = message.status == MessageStatus.DOWNLOADING
                        val isUploading = message.status == MessageStatus.SENDING
                        
                        if (!isPendingDownload && !isDownloading && !isUploading) {
                            onMediaClick(0)
                        } else if (isPendingDownload) {
                            onDownloadMedia()
                        }
                    }
                },
                onReply = {}, // Handled at list level
                context = context,
                textMeasurer = textMeasurer,
                density = density,
                maxWidth = maxWidth,
                onMediaClick = { _, index -> onMediaClick(index) },
                onDownloadMedia = { _ -> onDownloadMedia() },
                progress = progress,
                currentUserPhone = currentUserPhone,
                otherUsername = otherUsername,
                onReplyClick = onReplyClick,
                onHighlightFinished = onHighlightFinished,
                bubbleTheme = bubbleTheme,
                hapticFeedback = hapticFeedback,
                view = view
            )
        }
    }
}

/**
 * ReplyIconIndicator - Shows reply icon during swipe
 */
@Composable
private fun ReplyIconIndicator(
    isSelf: Boolean,
    iconAlpha: Float,
    iconScale: Float,
    groupPosition: BubbleGroupPosition,
    density: Density
) {
    val topPadding = if (groupPosition == BubbleGroupPosition.TOP || 
        groupPosition == BubbleGroupPosition.SINGLE) 8.dp else 1.dp
    val verticalOffset = topPadding / 2f
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .offset(y = verticalOffset)
    ) {
        Box(
            modifier = Modifier
                .align(if (isSelf) Alignment.CenterEnd else Alignment.CenterStart)
                .graphicsLayer {
                    alpha = iconAlpha
                    scaleX = iconScale
                    scaleY = iconScale
                    translationY = if (iconAlpha > 0.01f) 0f else -2000f
                }
                .size(36.dp)
                .background(
                    color = com.glyph.glyph_v3.ui.theme.glyphTheme.textPrimary,
                    shape = androidx.compose.foundation.shape.CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(id = com.glyph.glyph_v3.R.drawable.ic_reply),
                contentDescription = "Reply",
                modifier = Modifier.size(20.dp),
                tint = com.glyph.glyph_v3.ui.theme.glyphTheme.backgroundPrimary
            )
        }
    }
}

/**
 * DateHeaderRow - Pure date header
 */
@Composable
fun DateHeaderRow(
    date: String,
    textMeasurer: TextMeasurer,
    density: Density
) {
    // Delegate to existing DateHeader
    com.glyph.glyph_v3.ui.chat.DateHeader(
        date = date,
        isStuck = false,
        textMeasurer = textMeasurer,
        density = density
    )
}

/**
 * TypingIndicatorRow - Pure typing indicator
 */
@Composable
fun TypingIndicatorRow(
    textMeasurer: TextMeasurer,
    density: Density
) {
    // Delegate to existing TypingIndicator
    com.glyph.glyph_v3.ui.chat.TypingIndicator(
        textMeasurer = textMeasurer,
        density = density
    )
}
