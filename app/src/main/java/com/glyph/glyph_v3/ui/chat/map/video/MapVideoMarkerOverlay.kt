package com.glyph.glyph_v3.ui.chat.map.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import androidx.compose.ui.viewinterop.AndroidView
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import org.webrtc.RendererCommon

@Composable
fun MapVideoMarkerOverlay(
    googleMap: GoogleMap?,
    anchor: LatLng?,
    cameraMovementKey: Any,
    avatarUrl: String,
    otherUserName: String,
    manager: MapVideoSessionManager,
    isVisible: Boolean,
    onAcceptIncomingInvite: () -> Unit,
    onDismissIncomingInvite: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return
    val map = googleMap ?: return
    val position = anchor ?: return
    val showVideoBubble = manager.shouldShowMarkerVideoBubble || manager.isInviteDeclinedNoticeVisible
    val incomingInvite = manager.incomingCameraInvite
    val showInvitePrompt = incomingInvite != null

    if (!showVideoBubble && !showInvitePrompt) return

    val screenPoint = remember(map, position, cameraMovementKey) {
        map.projection?.toScreenLocation(position)
    } ?: return

    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = screenPoint.x - (overlaySize.width / 2),
                        y = screenPoint.y - overlaySize.height
                    )
                }
                .onSizeChanged { overlaySize = it },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (showVideoBubble) {
                MapVideoBubble(
                    avatarUrl = avatarUrl,
                    otherUserName = otherUserName,
                    manager = manager
                )
            }

            AnimatedVisibility(
                visible = showInvitePrompt,
                enter = fadeIn() + slideInHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 260,
                        easing = FastOutSlowInEasing
                    ),
                    initialOffsetX = { fullWidth -> fullWidth / 3 }
                ),
                exit = fadeOut() + slideOutHorizontally(
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing
                    ),
                    targetOffsetX = { fullWidth -> fullWidth / 4 }
                )
            ) {
                incomingInvite?.let { invite ->
                    CameraInvitePromptBubble(
                        senderName = invite.senderName,
                        onAccept = onAcceptIncomingInvite,
                        onDismiss = onDismissIncomingInvite
                    )
                }
            }

            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(width = 14.dp, height = 14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0F172A))
                    .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            )
        }
    }
}

@Composable
private fun CameraInvitePromptBubble(
    senderName: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE60F172A))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "$senderName has started camera sharing.",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
            Text(
                text = "Do you want to share your camera as well?",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 14.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                ) {
                    Text(
                        text = "No",
                        color = Color.White.copy(alpha = 0.88f),
                        fontWeight = FontWeight.Medium
                    )
                }
                TextButton(
                    onClick = onAccept,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF2563EB))
                ) {
                    Text(
                        text = "Yes",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun MapVideoBubble(
    avatarUrl: String,
    otherUserName: String,
    manager: MapVideoSessionManager
) {
    var renderer by remember { mutableStateOf<VideoTextureViewRenderer?>(null) }

    DisposableEffect(manager, renderer) {
        renderer?.let(manager::attachRemoteRenderer)
        onDispose {
            renderer?.let { rendererView: VideoTextureViewRenderer ->
                manager.detachRemoteRenderer(rendererView)
            }
        }
    }

    Box(
        modifier = Modifier
            .width(108.dp)
            .height(132.dp)
            .clickable(enabled = manager.canSwitchCamera) {
                manager.switchCamera()
            }
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xCC0F172A))
            .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(26.dp))
            .padding(6.dp)
    ) {
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
                    setMirror(false)
                    renderer = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(22.dp)),
            update = { view ->
                view.setMirror(false)
                renderer = view
            }
        )

        if (!manager.hasRemoteVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF111827)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = otherUserName,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                )
            }
        }

        if (manager.canSwitchCamera) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-6).dp)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.42f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cameraswitch,
                    contentDescription = if (manager.isFrontCameraActive) {
                        "Switch to back camera"
                    } else {
                        "Switch to front camera"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 2.dp, y = 2.dp)
                .padding(top = 0.dp, start = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.42f))
                .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (manager.isInviteDeclinedNoticeVisible) {
                            Color(0xFFEF4444)
                        } else {
                            when (manager.connectionState) {
                                MapVideoConnectionState.LIVE -> Color(0xFF22C55E)
                                MapVideoConnectionState.CONNECTING -> Color(0xFFF59E0B)
                                MapVideoConnectionState.ERROR -> Color(0xFFEF4444)
                                else -> Color(0xFF94A3B8)
                            }
                        }
                    )
            )
            Text(
                text = if (manager.isInviteDeclinedNoticeVisible) {
                    "Declined"
                } else {
                    when (manager.connectionState) {
                        MapVideoConnectionState.LIVE -> "Live"
                        MapVideoConnectionState.CONNECTING -> "Connecting"
                        MapVideoConnectionState.ERROR -> "Retry"
                        MapVideoConnectionState.WAITING -> "Waiting"
                        MapVideoConnectionState.AVATAR -> "Avatar"
                    }
                },
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 10.sp
            )
        }

        Text(
            text = otherUserName.ifBlank { "User" },
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            lineHeight = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-1).dp)
                .padding(start = 6.dp, end = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.42f))
                .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}
