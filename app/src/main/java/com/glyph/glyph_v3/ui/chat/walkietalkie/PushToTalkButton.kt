package com.glyph.glyph_v3.ui.chat.walkietalkie

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.service.WalkieTalkieManager

@Composable
fun PushToTalkButton(
    state: WalkieTalkieManager.State,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    isCompact: Boolean = false,
    isTight: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isTransmitting = state == WalkieTalkieManager.State.TRANSMITTING
    val isReceiving = state == WalkieTalkieManager.State.RECEIVING
    val isConnecting = state == WalkieTalkieManager.State.CONNECTING ||
        state == WalkieTalkieManager.State.REQUESTING
    val isConnected = state == WalkieTalkieManager.State.CONNECTED_IDLE ||
        isTransmitting || isReceiving
    val canPress = isConnected && !isReceiving
    var isTouchHolding by remember { mutableStateOf(false) }
    val outerSize = when {
        isTight -> 164.dp
        isCompact -> 182.dp
        else -> 196.dp
    }
    val middleSize = when {
        isTight -> 142.dp
        isCompact -> 156.dp
        else -> 168.dp
    }
    val coreSize = when {
        isTight -> 120.dp
        isCompact -> 132.dp
        else -> 144.dp
    }
    val iconSize = when {
        isTight -> 30.dp
        isCompact -> 34.dp
        else -> 36.dp
    }
    val headerSize = if (isCompact) 10.sp else 11.sp
    val mainLabelSize = if (isTight) 13.sp else 14.sp
    val supportingLabelSize = if (isCompact) 12.sp else 13.sp
    val outerSpacing = if (isCompact) 10.dp else 14.dp
    val innerSpacing = if (isCompact) 6.dp else 8.dp

    val infiniteTransition = rememberInfiniteTransition(label = "wt_ptt_pulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wt_ring_scale"
    )
    val innerRingScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wt_inner_ring_scale"
    )

    val haloColor by animateColorAsState(
        targetValue = when {
            isTransmitting -> Color(0x6656F0BE)
            isReceiving -> Color(0x667AB6FF)
            isConnecting -> Color(0x55FFC66D)
            else -> Color(0x2256F0BE)
        },
        animationSpec = tween(220),
        label = "wt_halo_color"
    )

    val rimColor by animateColorAsState(
        targetValue = when {
            isTransmitting -> Color(0xFF7AF7CF)
            isReceiving -> Color(0xFFA9D6FF)
            isConnecting -> Color(0xFFFFD78A)
            canPress -> Color(0xFF74D8D3)
            else -> Color(0xFF4D6874)
        },
        animationSpec = tween(180),
        label = "wt_rim_color"
    )

    val coreScale by animateFloatAsState(
        targetValue = when {
            isTouchHolding -> 0.93f
            isTransmitting -> 1.02f
            isReceiving -> 1.01f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "wt_core_scale"
    )

    val haloScale by animateFloatAsState(
        targetValue = when {
            isTouchHolding -> 1.03f
            isTransmitting || isReceiving || isConnecting -> ringScale
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "wt_halo_scale"
    )

    val middleScale by animateFloatAsState(
        targetValue = when {
            isTouchHolding -> 1.01f
            isTransmitting || isReceiving || isConnecting -> innerRingScale
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "wt_middle_scale"
    )

    val buttonBrush = when {
        isTransmitting -> Brush.radialGradient(
            colors = listOf(
                Color(0xFF3BD8A8),
                Color(0xFF0E7F72),
                Color(0xFF083B41)
            )
        )
        isReceiving -> Brush.radialGradient(
            colors = listOf(
                Color(0xFF84BFFF),
                Color(0xFF345CA7),
                Color(0xFF132B56)
            )
        )
        isConnecting -> Brush.radialGradient(
            colors = listOf(
                Color(0xFFFFD38A),
                Color(0xFF9A6935),
                Color(0xFF442B14)
            )
        )
        canPress -> Brush.radialGradient(
            colors = listOf(
                Color(0xFF5BDDD6),
                Color(0xFF1F6973),
                Color(0xFF0B2531)
            )
        )
        else -> Brush.radialGradient(
            colors = listOf(
                Color(0xFF627A88),
                Color(0xFF324754),
                Color(0xFF18232D)
            )
        )
    }

    val header = when {
        isTransmitting -> "LIVE"
        isReceiving -> "RX"
        isConnecting -> "SYNC"
        canPress -> "TALK"
        else -> "WAIT"
    }

    val mainLabel = when {
        isTransmitting -> "Release to stop"
        isReceiving -> "Listening..."
        isConnecting -> "Connecting..."
        canPress -> "Hold to speak"
        else -> "Stand by"
    }

    val supportingLabel = when {
        isTransmitting -> "Broadcasting on the active channel"
        isReceiving -> "Incoming voice currently has priority"
        isConnecting -> "Negotiating audio route and floor"
        canPress -> "Press and hold for instant transmission"
        else -> "Waiting for the session to become ready"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(outerSize)
        ) {
            Box(
                modifier = Modifier
                    .size(outerSize)
                    .scale(haloScale)
                    .clip(CircleShape)
                    .background(haloColor)
            )

            Box(
                modifier = Modifier
                    .size(middleSize)
                    .scale(middleScale)
                    .clip(CircleShape)
                    .border(1.dp, rimColor.copy(alpha = 0.32f), CircleShape)
                    .background(Color(0x12000000))
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(coreSize)
                    .scale(coreScale)
                    .clip(CircleShape)
                    .background(buttonBrush)
                    .border(1.5.dp, rimColor, CircleShape)
                    .then(
                        if (canPress) {
                            Modifier.pointerInput(canPress) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()

                                    var pressed = false
                                    try {
                                        pressed = true
                                        isTouchHolding = true
                                        onPttPress()
                                        waitForUpOrCancellation()
                                    } finally {
                                        isTouchHolding = false
                                        if (pressed) {
                                            onPttRelease()
                                        }
                                    }
                                }
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = header,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = headerSize,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = if (isCompact) 1.sp else 1.4.sp
                    )

                    Spacer(modifier = Modifier.height(innerSpacing))

                    Icon(
                        painter = painterResource(
                            id = when {
                                isReceiving -> R.drawable.ic_headset
                                else -> R.drawable.ic_mic
                            }
                        ),
                        contentDescription = when {
                            isTransmitting -> "Transmitting"
                            isReceiving -> "Receiving"
                            else -> "Push to Talk"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(iconSize)
                    )

                    Spacer(modifier = Modifier.height(innerSpacing))

                    Text(
                        text = mainLabel,
                        color = Color.White,
                        fontSize = mainLabelSize,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(outerSpacing))

        Text(
            text = supportingLabel,
            color = Color.White.copy(alpha = 0.78f),
            fontSize = supportingLabelSize,
            lineHeight = if (isCompact) 16.sp else 18.sp,
            textAlign = TextAlign.Center
        )
    }
}
