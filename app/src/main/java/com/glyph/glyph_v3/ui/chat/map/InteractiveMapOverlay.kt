package com.glyph.glyph_v3.ui.chat.map

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.glyph.glyph_v3.BuildConfig
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon

private const val TOUCH_DEBUG_TAG = "MapTouchDebug"

@Composable
fun InteractiveMapButton(
    isInteractive: Boolean,
    isMapEnabled: Boolean,
    isVideoModeEnabled: Boolean,
    isAudioMuted: Boolean,
    isQuickReplyEnabled: Boolean,
    isQuickReplyOverlayActive: Boolean,
    myAvatarUrl: String?,
    videoManager: MapVideoSessionManager?,
    onToggle: () -> Unit,
    onQuickReply: () -> Unit,
    onToggleVideoMode: () -> Unit,
    onToggleAudioMute: () -> Unit,
    onDockBoundsChanged: (android.graphics.Rect?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isCollapsed by remember(isMapEnabled) { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }

    fun registerInteraction(expand: Boolean = false) {
        if (expand) {
            isCollapsed = false
        }
        interactionTick++
    }

    LaunchedEffect(isMapEnabled) {
        if (!isMapEnabled) {
            isCollapsed = false
        }
    }

    LaunchedEffect(isMapEnabled, isCollapsed, interactionTick) {
        if (!isMapEnabled || isCollapsed) return@LaunchedEffect
        delay(5_000)
        isCollapsed = true
    }

    AnimatedVisibility(
        visible = isMapEnabled,
        enter = fadeIn(tween(400)) + slideInVertically(
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
            initialOffsetY = { it }
        ),
        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { it },
        modifier = modifier
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val density = LocalDensity.current

        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.93f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
            label = "buttonScale"
        )
        val expandedProgress by animateFloatAsState(
            targetValue = if (isCollapsed) 0f else 1f,
            animationSpec = tween(
                durationMillis = 420,
                easing = CubicBezierEasing(0.2f, 0.88f, 0.22f, 1f)
            ),
            label = "expandedDockProgress"
        )
        val collapsedProgress by animateFloatAsState(
            targetValue = if (isCollapsed) 1f else 0f,
            animationSpec = tween(
                durationMillis = 420,
                easing = CubicBezierEasing(0.2f, 0.88f, 0.22f, 1f)
            ),
            label = "collapsedDockProgress"
        )
        val collapsedLiftTargetPx = with(density) {
            when {
                isInteractive && isQuickReplyOverlayActive -> 156.dp.toPx()
                isInteractive -> 0.dp.toPx()
                else -> 134.dp.toPx()
            }
        }
        val collapsedLiftPx by animateFloatAsState(
            targetValue = collapsedLiftTargetPx,
            animationSpec = spring(
                dampingRatio = 0.88f,
                stiffness = Spring.StiffnessLow
            ),
            label = "collapsedLiftPx"
        )
        val expandedSlideXPx = with(density) { 26.dp.toPx() }
        val expandedSlideYPx = with(density) { 8.dp.toPx() }
        val collapsedSlideXPx = with(density) { 18.dp.toPx() }
        val topHeadroomTarget = when {
            isInteractive && isQuickReplyOverlayActive -> 176.dp
            isInteractive -> 34.dp
            else -> 134.dp
        }
        val topHeadroom by animateDpAsState(
            targetValue = topHeadroomTarget,
            animationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessLow
            ),
            label = "topHeadroom"
        )

        val glowColor = if (isInteractive) Color(0xFF26C6DA) else Color(0xFF7C4DFF)

        Box(
            modifier = Modifier
                .wrapContentWidth()
                .padding(top = topHeadroom)
                .onGloballyPositioned { coordinates ->
                    if (BuildConfig.DEBUG) {
                        val bounds = coordinates.boundsInWindow()
                        Log.d(
                            TOUCH_DEBUG_TAG,
                            "compose_root bounds=[${bounds.left.toInt()},${bounds.top.toInt()},${bounds.right.toInt()},${bounds.bottom.toInt()}] " +
                                "interactive=$isInteractive collapsed=$isCollapsed quickReplyOverlay=$isQuickReplyOverlayActive"
                        )
                    }
                },
            contentAlignment = Alignment.BottomEnd
        ) {
            ExpandedMapDock(
                isInteractive = isInteractive,
                isVideoModeEnabled = isVideoModeEnabled,
                isAudioMuted = isAudioMuted,
                myAvatarUrl = myAvatarUrl,
                videoManager = videoManager,
                onDockBoundsChanged = onDockBoundsChanged,
                interactionSource = interactionSource,
                glowColor = glowColor,
                scale = scale,
                controlsEnabled = expandedProgress > 0.55f,
                modifier = Modifier.graphicsLayer {
                    alpha = expandedProgress
                    translationX = (1f - expandedProgress) * expandedSlideXPx
                    translationY = (1f - expandedProgress) * expandedSlideYPx
                },
                onToggle = {
                    registerInteraction()
                    onToggle()
                },
                onToggleVideoMode = {
                    registerInteraction()
                    onToggleVideoMode()
                },
                onToggleAudioMute = {
                    registerInteraction()
                    onToggleAudioMute()
                }
            )

            CollapsedMapRail(
                isInteractive = isInteractive,
                isVideoModeEnabled = isVideoModeEnabled,
                isAudioMuted = isAudioMuted,
                isQuickReplyEnabled = isQuickReplyEnabled,
                isEnabled = collapsedProgress > 0.55f,
                onExpand = { registerInteraction(expand = true) },
                onQuickReply = {
                    registerInteraction()
                    onQuickReply()
                },
                modifier = Modifier.graphicsLayer {
                    alpha = collapsedProgress
                    translationX = (1f - collapsedProgress) * collapsedSlideXPx
                    translationY = -collapsedLiftPx * collapsedProgress
                }
            )
        }
    }
}

@Composable
private fun ExpandedMapDock(
    isInteractive: Boolean,
    isVideoModeEnabled: Boolean,
    isAudioMuted: Boolean,
    myAvatarUrl: String?,
    videoManager: MapVideoSessionManager?,
    onDockBoundsChanged: (android.graphics.Rect?) -> Unit,
    interactionSource: MutableInteractionSource,
    glowColor: Color,
    scale: Float,
    controlsEnabled: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onToggleVideoMode: () -> Unit,
    onToggleAudioMute: () -> Unit
) {
    LaunchedEffect(controlsEnabled) {
        if (!controlsEnabled) {
            onDockBoundsChanged(null)
        }
    }

    Row(
        modifier = modifier
            .scale(scale)
            .widthIn(max = 300.dp)
            .onGloballyPositioned { coordinates ->
                if (controlsEnabled) {
                    val bounds = coordinates.boundsInWindow()
                    onDockBoundsChanged(
                        android.graphics.Rect(
                            bounds.left.toInt(),
                            bounds.top.toInt(),
                            bounds.right.toInt(),
                            bounds.bottom.toInt()
                        )
                    )
                }
            }
            .shadow(
                elevation = if (isInteractive) 14.dp else 10.dp,
                shape = RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp),
                ambientColor = glowColor.copy(alpha = 0.36f),
                spotColor = glowColor.copy(alpha = 0.48f)
            )
            .clip(RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp))
            .background(
                brush = if (isInteractive) {
                    Brush.linearGradient(listOf(Color(0xFF062C30), Color(0xFF0F766E), Color(0xFF22D3EE)))
                } else {
                    Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF4C1D95)))
                }
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                shape = RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp)
            )
            .padding(start = 10.dp, top = 6.dp, end = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(22.dp))
                .clickable(
                    enabled = controlsEnabled,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle
                )
                .background(Color.Black.copy(alpha = 0.18f))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                painter = painterResource(if (isInteractive) R.drawable.ic_arrow_back else R.drawable.ic_explore),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )

            Text(
                text = if (isInteractive) "Back to Chat" else "Map",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                maxLines = 1
            )
        }

        Box(
            modifier = Modifier
                .size(52.dp)
                .clickable(
                    enabled = controlsEnabled && videoManager?.canSwitchCamera == true,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true)
                ) {
                    videoManager?.switchLocalCamera()
                }
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.18f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.24f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            LocalVideoPreview(
                manager = videoManager,
                avatarUrl = myAvatarUrl,
                isVideoModeEnabled = isVideoModeEnabled,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        }

        DockActionButton(
            enabled = controlsEnabled && isVideoModeEnabled,
            isActive = isVideoModeEnabled,
            isAlert = isAudioMuted && isVideoModeEnabled,
            interactionSource = remember { MutableInteractionSource() },
            debugName = "audioToggle",
            onClick = onToggleAudioMute
        ) {
            Icon(
                imageVector = if (isAudioMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = if (isAudioMuted) "Unmute mic and audio" else "Mute mic and audio",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        DockActionButton(
            enabled = controlsEnabled,
            isActive = isVideoModeEnabled,
            interactionSource = remember { MutableInteractionSource() },
            debugName = "cameraToggle",
            onClick = onToggleVideoMode
        ) {
            Icon(
                painter = painterResource(if (isVideoModeEnabled) R.drawable.ic_videocam else R.drawable.ic_avatar),
                contentDescription = if (isVideoModeEnabled) "Live video mode" else "Avatar mode",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CollapsedMapRail(
    isInteractive: Boolean,
    isVideoModeEnabled: Boolean,
    isAudioMuted: Boolean,
    isQuickReplyEnabled: Boolean,
    isEnabled: Boolean,
    onExpand: () -> Unit,
    onQuickReply: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(42.dp)
            .shadow(
                elevation = if (isInteractive) 12.dp else 9.dp,
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                ambientColor = if (isInteractive) Color(0xFF26C6DA).copy(alpha = 0.28f) else Color(0xFF7C4DFF).copy(alpha = 0.28f),
                spotColor = if (isInteractive) Color(0xFF26C6DA).copy(alpha = 0.40f) else Color(0xFF7C4DFF).copy(alpha = 0.40f)
            )
            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .clickable(enabled = isEnabled, onClick = onExpand)
            .background(
                brush = if (isInteractive) {
                    Brush.verticalGradient(listOf(Color(0xFF083344), Color(0xFF0F766E), Color(0xFF164E63)))
                } else {
                    Brush.verticalGradient(listOf(Color(0xFF2E1065), Color(0xFF4C1D95), Color(0xFF312E81)))
                }
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            )
            .padding(vertical = 16.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = "Expand map controls",
            tint = Color.White,
            modifier = Modifier.size(25.dp)
        )

        if (isInteractive && isQuickReplyEnabled) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clickable(
                        enabled = isEnabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onQuickReply
                    )
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.24f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.16f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = "Reply with keyboard",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isInteractive) Color(0xFF67E8F9) else Color(0xFFD8B4FE))
        )

        Icon(
            painter = painterResource(if (isVideoModeEnabled) R.drawable.ic_videocam else R.drawable.ic_avatar),
            contentDescription = if (isVideoModeEnabled) "Live video mode enabled" else "Avatar mode enabled",
            tint = Color.White.copy(alpha = if (isVideoModeEnabled) 1f else 0.7f),
            modifier = Modifier.size(25.dp)
        )

        Icon(
            imageVector = if (isAudioMuted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = if (isAudioMuted) "Audio muted" else "Audio available",
            tint = Color.White.copy(alpha = if (isVideoModeEnabled) 1f else 0.72f),
            modifier = Modifier.size(25.dp)
        )
    }
}

@Composable
private fun DockActionButton(
    enabled: Boolean,
    isActive: Boolean,
    interactionSource: MutableInteractionSource,
    debugName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isAlert: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .onGloballyPositioned { coordinates ->
                if (BuildConfig.DEBUG) {
                    val bounds = coordinates.boundsInWindow()
                    Log.d(
                        TOUCH_DEBUG_TAG,
                        "button_bounds name=$debugName enabled=$enabled active=$isActive rect=[${bounds.left.toInt()},${bounds.top.toInt()},${bounds.right.toInt()},${bounds.bottom.toInt()}]"
                    )
                }
            }
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.68f)
            .background(
                when {
                    isAlert -> Color(0xFFB91C1C).copy(alpha = 0.55f)
                    isActive -> Color.White.copy(alpha = 0.22f)
                    else -> Color.Black.copy(alpha = 0.18f)
                }
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = when {
                    isAlert -> 0.85f
                    isActive -> 0.72f
                    else -> 0.28f
                }),
                shape = CircleShape
            )
            .indication(
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TOUCH_DEBUG_TAG,
                            "button_click name=$debugName enabled=$enabled active=$isActive"
                        )
                    }
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun LocalVideoPreview(
    manager: MapVideoSessionManager?,
    avatarUrl: String?,
    isVideoModeEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    var renderer by remember { mutableStateOf<VideoTextureViewRenderer?>(null) }

    DisposableEffect(manager, renderer, isVideoModeEnabled) {
        if (isVideoModeEnabled) {
            renderer?.let { sink -> manager?.attachLocalRenderer(sink) }
        }
        onDispose {
            renderer?.let { rendererView: VideoTextureViewRenderer ->
                manager?.detachLocalRenderer(rendererView)
            }
        }
    }

    Box(
        modifier = modifier.background(Color(0xFF111827)),
        contentAlignment = Alignment.Center
    ) {
        if (isVideoModeEnabled && manager != null) {
            AndroidView(
                factory = { context ->
                    VideoTextureViewRenderer(context).apply {
                        init(
                            manager.eglBaseContext,
                            object : RendererCommon.RendererEvents {
                                override fun onFirstFrameRendered() = Unit

                                override fun onFrameResolutionChanged(
                                    videoWidth: Int,
                                    videoHeight: Int,
                                    rotation: Int
                                ) = Unit
                            }
                        )
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(manager.isFrontCameraActive)
                        renderer = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { clip = true },
                update = { view ->
                    view.setMirror(manager.isFrontCameraActive)
                    renderer = view
                }
            )
        } else {
            AsyncImage(
                model = avatarUrl,
                contentDescription = "My avatar preview",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun InteractiveSlideContent(
    isInteractive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val offsetX by animateFloatAsState(
        targetValue = if (isInteractive) 1f else 0f,
        animationSpec = tween(
            durationMillis = 450,
            easing = FastOutSlowInEasing
        ),
        label = "chatSlideOffset"
    )

    val alpha by animateFloatAsState(
        targetValue = if (isInteractive) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (isInteractive) 300 else 400,
            easing = FastOutSlowInEasing
        ),
        label = "chatSlideAlpha"
    )

    val scale = 1f - (offsetX * 0.05f)

    Box(
        modifier = modifier
            .alpha(alpha)
            .scale(scale)
    ) {
        content()
    }
}
