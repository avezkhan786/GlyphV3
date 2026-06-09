package com.glyph.glyph_v3.ui.chat.map.routing

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Colours ────────────────────────────────────────────────────────────────────

private val BannerBgDark = Brush.verticalGradient(
    listOf(Color(0xFF1A1A2E).copy(alpha = 0.96f), Color(0xFF16213E).copy(alpha = 0.94f))
)
private val BannerBgLight = Brush.verticalGradient(
    listOf(Color(0xFFF0F4FF).copy(alpha = 0.97f), Color(0xFFE8EDF8).copy(alpha = 0.96f))
)

private val AccentBlue   = Color(0xFF42A5F5)
private val AccentGreen  = Color(0xFF66BB6A)
private val AccentOrange = Color(0xFFFFB74D)
private val AccentCyan   = Color(0xFF26C6DA)
private val AccentPink   = Color(0xFFEC407A)

// ── Public composable ──────────────────────────────────────────────────────────

/**
 * Vertical info panel displayed below the `LiveLocationBanner` when navigation
 * is active. Shows distance, ETA, duration, travel mode, and a recalculating
 * indicator.
 *
 * @param state          Current routing UI state from RoutingManager.
 * @param otherUserName  Display name of the other user (for the header).
 * @param isDark         Whether dark theme is active.
 * @param onStop         Callback to stop navigation.
 * @param onToggleMode   Callback to switch driving/walking.
 * @param onRouteUpdated Optional callback invoked when route data changes (for notifications).
 */
@Composable
fun RoutingInfoBanner(
    state: RoutingUiState,
    otherUserName: String,
    isDark: Boolean,
    onStop: () -> Unit,
    onToggleMode: () -> Unit,
    onRouteUpdated: ((RoutingUiState) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Notify parent of route updates for notification display
    LaunchedEffect(state.distanceMeters, state.durationSeconds, state.travelMode) {
        if (state.isNavigating && onRouteUpdated != null) {
            onRouteUpdated(state)
        }
    }

    AnimatedVisibility(
        visible = state.isNavigating,
        enter = expandVertically(tween(350)) + fadeIn(tween(350)),
        exit  = shrinkVertically(tween(250)) + fadeOut(tween(250)),
        modifier = modifier
    ) {
        val bgBrush = if (isDark) BannerBgDark else BannerBgLight
        val primaryText = if (isDark) Color.White else Color(0xFF1A1A2E)
        val secondaryText = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF555577)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(bgBrush)
                .padding(horizontal = 12.dp, vertical = 3.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // ── Header row: "Navigating to [User]" + close button ───────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy((-2).dp)
                    ) {
                        Text(
                            text = "Navigating to",
                            color = secondaryText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = 0.3.sp,
                            lineHeight = 12.sp
                        )
                        Text(
                            text = otherUserName.ifBlank { "User" },
                            color = primaryText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Recalculating indicator
                    AnimatedVisibility(
                        visible = state.isRecalculating,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200))
                    ) {
                        RecalculatingBadge(isDark)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Close button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDark) Color.White.copy(alpha = 0.1f)
                                else Color.Black.copy(alpha = 0.08f)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onStop
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop navigation",
                            tint = if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF555577),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // ── Stats row ───────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RoutingStatCell(
                        icon = "\uD83D\uDCCD",   // 📍
                        label = "Distance",
                        value = state.formattedDistance,
                        valueColor = AccentBlue,
                        isDark = isDark
                    )
                    RoutingStatCell(
                        icon = "⏱",
                        label = "ETA",
                        value = state.formattedEta,
                        valueColor = AccentGreen,
                        isDark = isDark
                    )
                    RoutingStatCell(
                        icon = "\uD83D\uDE97",   // 🚗
                        label = "Duration",
                        value = state.formattedDuration,
                        valueColor = AccentOrange,
                        isDark = isDark
                    )
                }

                // ── Travel mode toggle ──────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 3.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TravelModeChip(
                        label = "Driving",
                        icon = "\uD83D\uDE97",
                        isSelected = state.travelMode == TravelMode.DRIVING,
                        isDark = isDark,
                        onClick = { if (state.travelMode != TravelMode.DRIVING) onToggleMode() }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TravelModeChip(
                        label = "Walking",
                        icon = "\uD83D\uDEB6",
                        isSelected = state.travelMode == TravelMode.WALKING,
                        isDark = isDark,
                        onClick = { if (state.travelMode != TravelMode.WALKING) onToggleMode() }
                    )
                }

                // ── Error row ───────────────────────────────────────────────
                state.error?.let { err ->
                    Text(
                        text = err,
                        color = AccentPink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun RoutingStatCell(
    icon: String,
    label: String,
    value: String,
    valueColor: Color,
    isDark: Boolean
) {
    val labelColor = if (isDark) Color.White.copy(alpha = 0.45f) else Color(0xFF888AAA)
    val iconColor = if (isDark) Color.White else Color(0xFFE65100)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((-7).dp)
    ) {
        Text(text = icon, fontSize = 16.sp, color = iconColor)
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(tween(200)) { -it } + fadeIn(tween(200)))
                    .togetherWith(slideOutVertically(tween(150)) { it } + fadeOut(tween(150)))
            },
            label = "statValue"
        ) { v ->
            Text(
                text = v,
                color = valueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun TravelModeChip(
    label: String,
    icon: String,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected && isDark  -> Color(0xFF1A73E8).copy(alpha = 0.35f)
            isSelected && !isDark -> Color(0xFF1A73E8).copy(alpha = 0.15f)
            isDark                -> Color.White.copy(alpha = 0.06f)
            else                  -> Color.Black.copy(alpha = 0.05f)
        },
        animationSpec = tween(250),
        label = "chipBg"
    )
    val textColor = when {
        isSelected && isDark  -> Color(0xFF90CAF9)
        isSelected && !isDark -> Color(0xFF1565C0)
        isDark                -> Color.White.copy(alpha = 0.5f)
        else                  -> Color(0xFF777799)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = icon, fontSize = 13.sp)
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun RecalculatingBadge(isDark: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "recalcPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recalcAlpha"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AccentOrange.copy(alpha = alpha * 0.3f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "\uD83D\uDD04", fontSize = 10.sp) // 🔄
            Text(
                text = "Updating",
                color = if (isDark) AccentOrange else Color(0xFFE65100),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
