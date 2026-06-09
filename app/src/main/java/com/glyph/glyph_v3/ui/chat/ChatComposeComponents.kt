package com.glyph.glyph_v3.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.ui.theme.glyphTheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.delay

@Composable
fun ChatHeader(
    userName: String,
    lastSeen: String,
    profilePicture: @Composable () -> Unit = {},
    onBackClick: () -> Unit,
    onProfileClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onVoiceCallClick: () -> Unit,
    onGroupVoiceCallClick: () -> Unit = onVoiceCallClick,
    onGroupWalkieTalkieClick: () -> Unit = {},
    showVideoCall: Boolean = true,
    showVoiceCall: Boolean = true,
    showGroupDropdown: Boolean = false,
    // Walkie-Talkie: null = hidden, false = idle (start), true = active (stop)
    walkieTalkieButtonState: Boolean? = null,
    onWalkieTalkieClick: () -> Unit = {},
    onMenuClick: () -> Unit,
    showContent: Boolean = true,
    // true  → Pastel Sky: gradient extends behind the status bar, Compose adds its own padding
    // false → Dark/Light: AppBarLayout handles the status-bar inset normally, no padding here
    applyStatusBarPadding: Boolean = true,
    lastSeenMarqueeEnabled: Boolean = true,
    lastSeenMarqueeStartDelayMs: Int = 2000,
    lastSeenRevealEnabled: Boolean = true,
    // DropdownMenu integration
    showMenu: Boolean = false,
    onDismissMenu: () -> Unit = {},
    menuContent: @Composable ColumnScope.() -> Unit = {}
) {
    val theme = glyphTheme
    var showGroupCallMenu by remember { mutableStateOf(false) }
    var prefixWidth   by remember { mutableStateOf(0f) }
    var fullTextWidth by remember { mutableStateOf(0f) }
    var boxWidth      by remember { mutableStateOf(0f) }
    val scrollX = remember { Animatable(0f) }
    var hasAnimatedLastSeen by remember { mutableStateOf(false) }
    var animatedLastSeenText by remember { mutableStateOf<String?>(null) }
    val shouldScrollLastSeen = lastSeen.startsWith("last seen ")
    val scrollTarget by remember {
        derivedStateOf {
            maxOf(prefixWidth, fullTextWidth - boxWidth).coerceAtLeast(0f)
        }
    }

    LaunchedEffect(shouldScrollLastSeen, scrollTarget, lastSeen, lastSeenMarqueeEnabled, lastSeenMarqueeStartDelayMs) {
        if (!shouldScrollLastSeen || !lastSeenMarqueeEnabled) {
            scrollX.snapTo(0f)
            return@LaunchedEffect
        }

        if (animatedLastSeenText != lastSeen) {
            hasAnimatedLastSeen = false
            animatedLastSeenText = lastSeen
        }

        if (hasAnimatedLastSeen) {
            scrollX.snapTo(scrollTarget)
            return@LaunchedEffect
        }

        scrollX.snapTo(0f)
        if (scrollTarget > 0f) {
            if (lastSeenMarqueeStartDelayMs > 0) {
                delay(lastSeenMarqueeStartDelayMs.toLong())
            }
            scrollX.animateTo(
                targetValue = scrollTarget,
                animationSpec = tween(
                    durationMillis = 800,
                    easing = Easing { fraction ->
                        android.view.animation.OvershootInterpolator(2.0f).getInterpolation(fraction)
                    }
                )
            )
            hasAnimatedLastSeen = true
        }
    }

    val shouldShowStatusLine = lastSeenRevealEnabled && lastSeen.isNotEmpty()

    val userNameOffsetY by animateDpAsState(
        targetValue = if (shouldShowStatusLine) 0.dp else 6.dp,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "chatHeaderUserNameOffset"
    )
    val statusOffsetY by animateDpAsState(
        targetValue = if (shouldShowStatusLine) 0.dp else 6.dp,
        animationSpec = tween(
            durationMillis = 260,
            delayMillis = 80,
            easing = FastOutSlowInEasing
        ),
        label = "chatHeaderStatusOffset"
    )
    val statusAlpha by animateFloatAsState(
        targetValue = if (shouldShowStatusLine) 1f else 0f,
        animationSpec = tween(
            durationMillis = 220,
            delayMillis = 80,
            easing = FastOutSlowInEasing
        ),
        label = "chatHeaderStatusAlpha"
    )

    // Pastel Sky provides a full gradient; dark/light use a plain solid surface colour
    // (matching the original XML toolbar background) with no gradient.
    val headerBackground: Brush = theme.gradientHeader ?: SolidColor(theme.surfaceHeader)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shadowElevation = theme.elevationLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = headerBackground)
                .then(if (applyStatusBarPadding) Modifier.statusBarsPadding() else Modifier)
        ) {
            if (showContent) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(start = 12.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 28.dp, height = 48.dp)
                            .clickable { onBackClick() },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = theme.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .offset(x = (-4).dp, y = 2.dp)
                            .clickable { onProfileClick() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            profilePicture()
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        val isOnlineStatus = lastSeen == "Online" || lastSeen == "Online \u00b7 in chat" ||
                            lastSeen.endsWith(" online")
                        val onlineGreen = if (theme.gradientHeader != null) Color(0xFF057837) else Color(0xFF2ECC71)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = userName,
                                color = if (isOnlineStatus) onlineGreen else theme.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(lineHeight = 18.sp),
                                modifier = Modifier.offset(y = userNameOffsetY)
                            )
                            val statusText = lastSeen
                            Box(
                                modifier = Modifier
                                    .height(16.dp)
                                    .offset(y = statusOffsetY)
                                    .alpha(statusAlpha)
                                    .clipToBounds()
                                    .onSizeChanged { boxWidth = it.width.toFloat() }
                            ) {
                                if (statusText.isNotEmpty()) {
                                    val isScrolling = scrollX.value > 0f
                                    val statusColor = if (isOnlineStatus) onlineGreen else theme.textSecondary.copy(alpha = 0.7f)
                                    if (!isScrolling) {
                                        Text(
                                            text = statusText,
                                            color = statusColor,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Visible,
                                            style = TextStyle(lineHeight = 14.sp),
                                            modifier = Modifier.wrapContentWidth(unbounded = true, align = Alignment.Start)
                                        )
                                    }
                                    Text(
                                        text = statusText,
                                        color = statusColor,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Visible,
                                        style = TextStyle(lineHeight = 14.sp),
                                        onTextLayout = { layoutResult ->
                                            if (statusText.startsWith("last seen ")) {
                                                prefixWidth = layoutResult.getHorizontalPosition(10, true)
                                                fullTextWidth = layoutResult.size.width.toFloat()
                                            }
                                        },
                                        modifier = Modifier
                                            .wrapContentWidth(unbounded = true, align = Alignment.Start)
                                            .graphicsLayer {
                                                alpha = if (isScrolling) 1f else 0f
                                                translationX = -scrollX.value
                                            }
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier,
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy((-6).dp, Alignment.End)
                    ) {
                        if (showVideoCall) {
                            IconButton(
                                onClick = onVideoCallClick,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_videocam),
                                    contentDescription = "Video Call",
                                    tint = theme.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        if (showVoiceCall) {
                            IconButton(
                                onClick = onVoiceCallClick,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_call),
                                    contentDescription = "Voice Call",
                                    tint = theme.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        if (showGroupDropdown) {
                            Box(
                                modifier = Modifier.size(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = { showGroupCallMenu = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_expand_more),
                                        contentDescription = "Call options",
                                        tint = theme.textPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showGroupCallMenu,
                                    onDismissRequest = { showGroupCallMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Voice call", color = theme.textPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_call),
                                                contentDescription = null,
                                                tint = theme.textPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            showGroupCallMenu = false
                                            onGroupVoiceCallClick()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Walkie talkie", color = theme.textPrimary) },
                                        leadingIcon = {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_live_audio),
                                                contentDescription = null,
                                                tint = theme.textPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        },
                                        onClick = {
                                            showGroupCallMenu = false
                                            onGroupWalkieTalkieClick()
                                        }
                                    )
                                }
                            }
                        }

                        if (walkieTalkieButtonState != null) {
                            IconButton(
                                onClick = onWalkieTalkieClick,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    painter = painterResource(
                                        id = if (walkieTalkieButtonState) R.drawable.ic_close
                                             else R.drawable.ic_live_audio
                                    ),
                                    contentDescription = if (walkieTalkieButtonState) "End Walkie-Talkie" else "Start Walkie-Talkie",
                                    tint = if (walkieTalkieButtonState) androidx.compose.ui.graphics.Color(0xFFFFA726)
                                           else theme.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = onMenuClick,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_more_vert),
                                    contentDescription = "Menu",
                                    tint = theme.textPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = onDismissMenu
                            ) {
                                menuContent()
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(56.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onEmojiClick: () -> Unit,
    onCameraClick: () -> Unit,
    onSendClick: () -> Unit,
    onAiClick: () -> Unit = {},
    onBuzzClick: () -> Unit = {},
    onRecordingDown: () -> Unit = {},
    onRecordingDrag: (offsetX: Float, offsetY: Float) -> Unit = { _, _ -> },
    onRecordingUp: (offsetX: Float, offsetY: Float) -> Unit = { _, _ -> },
    replyToMessage: Message? = null,
    onCancelReply: () -> Unit = {},
    editingMessage: Message? = null,
    onCancelEdit: () -> Unit = {},
    otherUsername: String = "Unknown"
) {
    val theme = glyphTheme
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Input Pill
        Column(
            modifier = Modifier
                .weight(1f)
                .background(
                    color = theme.surfaceInput,
                    shape = RoundedCornerShape(24.dp)
                )
                .border(
                    width = 1.dp,
                    color = theme.borderInput,
                    shape = RoundedCornerShape(24.dp)
                )
        ) {
            // Remember the last non-null message for smooth exit animation
            var lastReplyMessage by remember { mutableStateOf(replyToMessage) }
            if (replyToMessage != null) {
                lastReplyMessage = replyToMessage
            }
            
            var lastEditMessage by remember { mutableStateOf(editingMessage) }
            if (editingMessage != null) {
                lastEditMessage = editingMessage
            }

            // Edit Preview (takes priority over Reply)
            AnimatedVisibility(
                visible = editingMessage != null,
                enter = expandVertically(
                    expandFrom = Alignment.Bottom,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(animationSpec = tween(300)) + slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(300, easing = LinearOutSlowInEasing)) + slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            ) {
                lastEditMessage?.let { msg ->
                    EditPreview(
                        message = msg,
                        onCancel = onCancelEdit,
                        modifier = Modifier
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            // Reply Preview
            AnimatedVisibility(
                visible = replyToMessage != null && editingMessage == null,
                enter = expandVertically(
                    expandFrom = Alignment.Bottom,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ) + fadeIn(animationSpec = tween(300)) + slideInVertically(
                    initialOffsetY = { it / 2 },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(300, easing = LinearOutSlowInEasing)) + slideOutVertically(
                    targetOffsetY = { it / 2 },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                )
            ) {
                lastReplyMessage?.let { msg ->
                    ReplyPreview(
                        message = msg,
                        username = otherUsername,
                        onCancel = onCancelReply,
                        modifier = Modifier
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            // Input Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAttachClick) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add_2),
                    contentDescription = "Attach",
                    tint = theme.attachmentIcon
                )
            }

            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 10.dp, end = 0.dp, bottom = 10.dp)
                    .heightIn(min = 24.dp, max = 120.dp),
                textStyle = TextStyle(
                    color = theme.textPrimary,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(theme.cursorColor),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                text = "Message",
                                color = theme.textPlaceholder,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Declare typing state early so animated values are available before use in child composables.
            val isTyping = text.isNotEmpty()
            // Right-side icons: [emoji] [buzz?] [camera/AI]
            // When typing we collapse the buzz slot so the text can extend up to the emoji area.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .wrapContentWidth()
                    .animateContentSize()
            ) {
                IconButton(
                    onClick = onEmojiClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_emoji),
                        contentDescription = "Emoji",
                        tint = theme.emojiIcon
                    )
                }

                if (!isTyping) {
                    IconButton(
                        onClick = onBuzzClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bolt),
                            contentDescription = "Buzz",
                            tint = theme.attachmentIcon
                        )
                    }
                }

                // Camera/AI swap — fixed 40dp slot, only content animated
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val transition = updateTransition(targetState = isTyping, label = "InputIcons")

                    val cameraScale by transition.animateFloat(
                        label = "CameraScale",
                        transitionSpec = {
                            if (targetState) {
                                tween(durationMillis = 120, easing = FastOutLinearInEasing)
                            } else {
                                tween(durationMillis = 200, delayMillis = 100, easing = FastOutSlowInEasing)
                            }
                        }
                    ) { typing -> if (typing) 0f else 1f }

                    val cameraAlpha by transition.animateFloat(
                        label = "CameraAlpha",
                        transitionSpec = {
                            if (targetState) {
                                tween(durationMillis = 100)
                            } else {
                                tween(durationMillis = 150, delayMillis = 100)
                            }
                        }
                    ) { typing -> if (typing) 0f else 1f }

                    val aiScale by transition.animateFloat(
                        label = "AiScale",
                        transitionSpec = {
                            if (targetState) {
                                tween(durationMillis = 220, delayMillis = 120, easing = FastOutSlowInEasing)
                            } else {
                                tween(durationMillis = 120, easing = FastOutLinearInEasing)
                            }
                        }
                    ) { typing -> if (typing) 1f else 0f }

                    val aiAlpha by transition.animateFloat(
                        label = "AiAlpha",
                        transitionSpec = {
                            if (targetState) {
                                tween(durationMillis = 150, delayMillis = 120)
                            } else {
                                tween(durationMillis = 100)
                            }
                        }
                    ) { typing -> if (typing) 1f else 0f }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                if (isTyping) onAiClick() else onCameraClick()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_camera),
                            contentDescription = "Camera",
                            tint = theme.attachmentIcon,
                            modifier = Modifier.graphicsLayer {
                                this.scaleX = cameraScale
                                this.scaleY = cameraScale
                                this.alpha = cameraAlpha
                            }
                        )

                        Icon(
                            painter = painterResource(id = R.drawable.ic_ai_composer),
                            contentDescription = "AI Composer",
                            tint = theme.aiIcon,
                            modifier = Modifier
                                .size(30.dp)
                                .graphicsLayer {
                                    this.scaleX = aiScale
                                    this.scaleY = aiScale
                                    this.alpha = aiAlpha
                                }
                        )
                    }
                }
            }
        } // end input Row
        } // end pill Column

        Spacer(modifier = Modifier.width(8.dp))

        // Send Button with Mic/Send transition
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(
                    brush = theme.gradientSendButton ?: SolidColor(theme.sendButtonBackground),
                    shape = CircleShape
                )
                .then(
                    if (text.isNotEmpty()) {
                        Modifier.clickable { onSendClick() }
                    } else {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    onRecordingDown()
                                    
                                    var lastPos = down.position
                                    drag(down.id) { change ->
                                        lastPos = change.position
                                        onRecordingDrag(
                                            lastPos.x - down.position.x,
                                            lastPos.y - down.position.y
                                        )
                                        change.consume()
                                    }
                                    
                                    onRecordingUp(
                                        lastPos.x - down.position.x,
                                        lastPos.y - down.position.y
                                    )
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = text.isEmpty(),
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f))
                        .togetherWith(fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.8f))
                },
                label = "SendMicTransition"
            ) { isTextEmpty ->
                Icon(
                    painter = painterResource(
                        id = if (isTextEmpty) R.drawable.ic_mic_custom else R.drawable.ic_send_custom
                    ),
                    contentDescription = if (isTextEmpty) "Record" else "Send",
                    tint = theme.sendButtonIcon,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
