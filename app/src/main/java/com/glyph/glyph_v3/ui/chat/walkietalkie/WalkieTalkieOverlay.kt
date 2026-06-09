package com.glyph.glyph_v3.ui.chat.walkietalkie

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.service.WalkieTalkieManager

@Composable
fun WalkieTalkieOverlay(
    state: WalkieTalkieManager.State,
    peerName: String,
    topSafeAreaInsetPx: Int,
    bottomSafeAreaInsetPx: Int,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActive = state != WalkieTalkieManager.State.IDLE
    val isTransmitting = state == WalkieTalkieManager.State.TRANSMITTING
    val isReceiving = state == WalkieTalkieManager.State.RECEIVING
    val isConnecting = state == WalkieTalkieManager.State.CONNECTING ||
        state == WalkieTalkieManager.State.REQUESTING
    val titleFont = remember { FontFamily(Font(R.font.bbh_bartle_regular)) }
    val infoRowInteractionSource = remember { MutableInteractionSource() }
    val overlayInteractionSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val topSafeAreaInset = with(density) { topSafeAreaInsetPx.toDp() }
    val bottomSafeAreaInset = with(density) { bottomSafeAreaInsetPx.toDp() }

    val accentColor = when {
        isTransmitting -> Color(0xFF56F0BE)
        isReceiving -> Color(0xFF7AB6FF)
        isConnecting -> Color(0xFFFFC66D)
        state == WalkieTalkieManager.State.DISCONNECTING -> Color(0xFFFF8A80)
        else -> Color(0xFFA8F0E8)
    }

    val statusLabel = when {
        isTransmitting -> "TRANSMITTING"
        isReceiving -> "RECEIVING"
        isConnecting -> "LINKING CHANNEL"
        state == WalkieTalkieManager.State.DISCONNECTING -> "SIGNING OFF"
        else -> "CHANNEL OPEN"
    }

    val headline = when {
        isTransmitting -> "Your voice is live"
        isReceiving -> "$peerName has the floor"
        isConnecting -> "Establishing the line"
        state == WalkieTalkieManager.State.DISCONNECTING -> "Closing the session"
        else -> "Ready for instant voice"
    }

    val supportingText = when {
        isTransmitting -> "Keep holding the talk key until you finish the message."
        isReceiving -> "Audio is incoming now. Wait for the channel to clear before replying."
        isConnecting -> "Negotiating audio and floor control for this session."
        state == WalkieTalkieManager.State.DISCONNECTING -> "The walkie-talkie link is shutting down."
        else -> "Hold the main key to speak. Release any time to hand the floor back."
    }

    val modeChip = when {
        isTransmitting -> "YOUR TURN"
        isReceiving -> "REMOTE TURN"
        isConnecting -> "SYNCING"
        else -> "PUSH TO TALK"
    }

    val lineChip = when {
        isTransmitting -> "Mic open"
        isReceiving -> "Speaker active"
        isConnecting -> "Securing audio"
        state == WalkieTalkieManager.State.DISCONNECTING -> "Ending link"
        else -> "Private channel"
    }

    AnimatedVisibility(
        visible = isActive,
        enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 5 },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 5 },
        modifier = modifier
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF031018))
                .clickable(
                    indication = null,
                    interactionSource = overlayInteractionSource,
                    onClick = { }
                )
        ) {
            val compactHeight = maxHeight < 760.dp
            val tightHeight = maxHeight < 700.dp
            val compactWidth = maxWidth < 360.dp
            val horizontalPadding = when {
                tightHeight || compactWidth -> 18.dp
                compactHeight -> 20.dp
                else -> 24.dp
            }
            val verticalPadding = when {
                tightHeight -> 12.dp
                compactHeight -> 14.dp
                else -> 18.dp
            }
            val headerWeight = if (tightHeight) 0.30f else if (compactHeight) 0.34f else 0.38f
            val cardWeight = if (tightHeight) 0.70f else if (compactHeight) 0.66f else 0.62f
            val headerGap = if (tightHeight) 6.dp else if (compactHeight) 8.dp else 10.dp
            val footerGap = if (tightHeight) 10.dp else if (compactHeight) 12.dp else 16.dp
            val titleSize = when {
                tightHeight -> 28.sp
                compactHeight -> 30.sp
                else -> 34.sp
            }
            val peerNameSize = when {
                tightHeight -> 24.sp
                compactHeight -> 26.sp
                else -> 30.sp
            }
            val headlineSize = if (tightHeight) 15.sp else 16.sp
            val supportingSize = if (compactHeight) 12.sp else 13.sp
            val cardCorner = if (tightHeight) 24.dp else if (compactHeight) 28.dp else 32.dp
            val cardPaddingHorizontal = if (tightHeight) 16.dp else if (compactHeight) 18.dp else 22.dp
            val cardPaddingVertical = if (tightHeight) 14.dp else if (compactHeight) 18.dp else 22.dp
            val showHeaderSupportingText = !tightHeight

            BackgroundOrb(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(if (tightHeight) 240.dp else if (compactHeight) 280.dp else 320.dp),
                colors = listOf(
                    accentColor.copy(alpha = 0.42f),
                    accentColor.copy(alpha = 0.16f),
                    Color.Transparent
                )
            )
            BackgroundOrb(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(if (tightHeight) 280.dp else if (compactHeight) 320.dp else 360.dp),
                colors = listOf(
                    Color(0x664B74FF),
                    Color(0x224B74FF),
                    Color.Transparent
                )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0x88071822),
                                Color(0xEE081C25)
                            )
                        )
                    )
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = horizontalPadding,
                        top = topSafeAreaInset + verticalPadding,
                        end = horizontalPadding,
                        bottom = bottomSafeAreaInset + verticalPadding
                    )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(headerWeight, fill = true)
                ) {
                    StatusBadge(
                        text = statusLabel,
                        color = accentColor,
                        compact = compactHeight
                    )

                    Spacer(modifier = Modifier.height(if (tightHeight) 10.dp else 12.dp))

                    Text(
                        text = peerName,
                        color = Color.White,
                        fontSize = peerNameSize,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(headerGap))

                    Text(
                        text = "Walkie-Talkie",
                        color = Color(0xFFE7FBFF),
                        fontFamily = titleFont,
                        fontSize = titleSize,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = if (tightHeight) 0.3.sp else 0.5.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )

                    Spacer(modifier = Modifier.height(headerGap))

                    Text(
                        text = headline,
                        color = Color(0xFFD7F8FF),
                        fontSize = headlineSize,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.92f)
                    )

                    if (showHeaderSupportingText) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = supportingText,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = supportingSize,
                            lineHeight = if (compactHeight) 16.sp else 18.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.92f)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(cardWeight, fill = true)
                        .clip(RoundedCornerShape(cardCorner))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xCC1D3144),
                                    Color(0xB1132433)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = Color(0x3AE4F2FF),
                            shape = RoundedCornerShape(cardCorner)
                        )
                        .padding(horizontal = cardPaddingHorizontal, vertical = cardPaddingVertical)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (tightHeight) 8.dp else 10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            InfoChip(
                                iconRes = R.drawable.ic_walkie_talkie,
                                text = modeChip,
                                tint = accentColor,
                                compact = compactHeight,
                                modifier = Modifier.weight(1f)
                            )
                            InfoChip(
                                iconRes = if (isReceiving) R.drawable.ic_volume_up else R.drawable.ic_mic,
                                text = lineChip,
                                tint = Color(0xFFA9D6FF),
                                compact = compactHeight,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        PushToTalkButton(
                            state = state,
                            onPttPress = onPttPress,
                            onPttRelease = onPttRelease,
                            isCompact = compactHeight,
                            isTight = tightHeight
                        )

                        Text(
                            text = if (isReceiving) {
                                "Incoming audio has priority. Talk becomes available when the floor clears."
                            } else {
                                "Press and hold to talk. Release to hand the floor back."
                            },
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = if (compactHeight) 12.sp else 13.sp,
                            lineHeight = if (compactHeight) 16.sp else 18.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.94f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(footerGap))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (tightHeight) 8.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                indication = null,
                                interactionSource = infoRowInteractionSource,
                                onClick = { }
                            )
                            .clip(RoundedCornerShape(if (compactHeight) 18.dp else 20.dp))
                            .background(Color(0x24364B60))
                            .border(1.dp, Color(0x409FC7D4), RoundedCornerShape(if (compactHeight) 18.dp else 20.dp))
                            .padding(
                                horizontal = if (tightHeight) 10.dp else 14.dp,
                                vertical = if (tightHeight) 10.dp else 12.dp
                            )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_info),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(if (tightHeight) 16.dp else 18.dp)
                        )
                        Spacer(modifier = Modifier.width(if (tightHeight) 8.dp else 10.dp))
                        Text(
                            text = if (isConnecting) "Channel handshake in progress" else "Direct encrypted voice session",
                            color = Color.White.copy(alpha = 0.84f),
                            fontSize = if (compactHeight) 12.sp else 13.sp,
                            lineHeight = if (compactHeight) 16.sp else 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(if (compactHeight) 20.dp else 24.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xFFFF6A6A),
                                        Color(0xFFD73A49)
                                    )
                                )
                            )
                            .clickable(onClick = onDisconnect)
                            .padding(
                                horizontal = if (tightHeight) 16.dp else if (compactHeight) 18.dp else 22.dp,
                                vertical = if (tightHeight) 12.dp else 14.dp
                            )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(if (tightHeight) 16.dp else 18.dp)
                        )
                        Spacer(modifier = Modifier.width(if (tightHeight) 6.dp else 8.dp))
                        Text(
                            text = if (tightHeight) "End" else "End Session",
                            color = Color.White,
                            fontSize = if (compactHeight) 13.sp else 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundOrb(
    modifier: Modifier,
    colors: List<Color>
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.radialGradient(colors = colors))
    )
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    compact: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wt_status_pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wt_status_dot_alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .background(Color(0x24364B60))
            .border(1.dp, Color(0x409FC7D4), RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 7.dp else 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 7.dp else 8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = dotAlpha))
        )
        Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
        Text(
            text = text,
            color = color,
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (compact) 0.8.sp else 1.2.sp
        )
    }
}

@Composable
private fun InfoChip(
    iconRes: Int,
    text: String,
    tint: Color,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .background(Color(0x24364B60))
            .border(1.dp, Color(0x409FC7D4), RoundedCornerShape(if (compact) 16.dp else 18.dp))
            .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 8.dp else 10.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(if (compact) 14.dp else 16.dp)
        )
        Spacer(modifier = Modifier.width(if (compact) 6.dp else 8.dp))
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = if (compact) 11.sp else 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
