package com.glyph.glyph_v3.ui.chat.map

import android.location.Geocoder
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.data.models.UserLocation
import com.glyph.glyph_v3.data.repo.LocationRepository
import com.glyph.glyph_v3.ui.chat.map.video.MapVideoConnectionState
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.Locale

// ── Banner states ──────────────────────────────────────────────────────────────

private sealed class MapBannerState {
    /** Initial load — briefly shows nothing meaningful yet. */
    object Loading : MapBannerState()

    /** Map is on but the other user has no location data at all. */
    object NoLocation : MapBannerState()

    /** Only the current user is sharing live — other hasn't started yet. */
    object MySharing : MapBannerState()

    /** Other user has a last-known location but is not currently live-sharing. */
    data class LastKnown(val address: String, val timestamp: Long) : MapBannerState()

    /** Only the other user is sharing live location. */
    data class OtherSharing(val name: String) : MapBannerState()

    /** Both users are actively sharing live location (mutual). */
    data class Mutual(val name: String) : MapBannerState()
}

// ── Public composable ──────────────────────────────────────────────────────────

/**
 * Smart, state-driven info banner displayed below the top app bar while the
 * map background is active.
 *
 * States (priority, highest first):
 *  1. **Mutual** — both users are live-sharing → green "Mutual" pill
 *  2. **OtherSharing** — only the other user is live → name + Lottie animation
 *  3. **LastKnown** — map on, no live share → last address + relative timestamp
 *  4. **NoLocation** — no data available at all
 *
 * @param isVisible         Show/hide the whole banner (maps to map-enabled state).
 * @param myIsLiveSharing   Whether the current user is live-sharing right now.
 * @param startedAtMs       Epoch-ms when the current user started sharing (for elapsed timer).
 * @param durationMs        Requested live-share duration in ms (0 = indefinite).
 * @param accuracyMeters    GPS accuracy of the current user, shown in live states.
 * @param otherUserId       Firebase UID of the other chat participant.
 * @param otherUserName     Display name of the other user.
 */
@Composable
fun LiveLocationBanner(
    isVisible: Boolean,
    myIsLiveSharing: Boolean,
    startedAtMs: Long,
    durationMs: Long,
    accuracyMeters: Float = 0f,
    otherUserId: String,
    otherUserName: String,
    myIsCameraSharing: Boolean = false,
    videoConnectionState: MapVideoConnectionState? = null,
    myVideoSessionStartedAtMs: Long = 0L,
    isFrontCameraActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val theme = glyphTheme

    // ── Observe other user's location from Firebase ──────────────────────────
    var otherLocation by remember { mutableStateOf<UserLocation?>(null) }
    var otherLocationLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(otherUserId) {
        if (otherUserId.isBlank()) {
            otherLocationLoaded = true
            return@LaunchedEffect
        }
        LocationRepository.observeLocationSharedWithMe(otherUserId).collectLatest { loc ->
            otherLocation = loc
            otherLocationLoaded = true
        }
    }

    // ── Reverse-geocode the other user's last known position ─────────────────
    var geocodedAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(otherLocation?.latitude, otherLocation?.longitude) {
        val loc = otherLocation ?: return@LaunchedEffect
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return@LaunchedEffect
        geocodedAddress = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(context, Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                val addr = addresses?.firstOrNull()
                if (addr != null) {
                    val sub = addr.subLocality ?: addr.thoroughfare
                    val city = addr.locality ?: addr.adminArea
                    when {
                        sub != null && city != null && sub != city -> "$sub, $city"
                        sub != null -> sub
                        city != null -> city
                        else -> addr.adminArea ?: addr.countryName
                    }
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    // ── Derive live-sharing states ───────────────────────────────────────────
    val otherIsLive = otherLocation?.isActivelyLiveSharing() == true
    val hasLastKnownLocation = otherLocationLoaded &&
        otherLocation != null &&
        !(otherLocation!!.latitude == 0.0 && otherLocation!!.longitude == 0.0)

    val bannerState: MapBannerState = when {
        myIsLiveSharing && otherIsLive            -> MapBannerState.Mutual(otherUserName)
        otherIsLive                               -> MapBannerState.OtherSharing(otherUserName)
        myIsLiveSharing                           -> MapBannerState.MySharing
        hasLastKnownLocation                      -> MapBannerState.LastKnown(
            geocodedAddress ?: "Loading…",
            otherLocation!!.timestamp
        )
        otherLocationLoaded                       -> MapBannerState.NoLocation
        else                                      -> MapBannerState.Loading
    }

    // ── Container: expand/collapse with the map ──────────────────────────────
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(tween(350)) + fadeIn(tween(350)),
        exit  = shrinkVertically(tween(250)) + fadeOut(tween(250)),
        modifier = modifier
    ) {
        val isActiveState = bannerState is MapBannerState.OtherSharing ||
                bannerState is MapBannerState.Mutual ||
                bannerState is MapBannerState.MySharing

        val bannerBg = when {
            isActiveState -> if (theme.isDark) Color(0xFF1A3D1F).copy(alpha = 0.93f)
            else Color(0xFF2E7D32).copy(alpha = 0.88f)
            else          -> if (theme.isDark) Color(0xFF12121E).copy(alpha = 0.88f)
            else Color(0xFFF0F4FF).copy(alpha = 0.96f)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(tween(250))
                .background(bannerBg)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            AnimatedContent(
                targetState = bannerState,
                transitionSpec = {
                    (slideInVertically(tween(220)) { -it / 2 } + fadeIn(tween(220)))
                        .togetherWith(slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(180)))
                },
                label = "bannerContent"
            ) { state ->
                when (state) {
                    MapBannerState.Loading    -> LoadingBannerRow(theme.isDark)
                    MapBannerState.NoLocation -> NoLocationBannerRow(theme.isDark)
                    MapBannerState.MySharing  -> MySharingBannerRow(
                        isDark = theme.isDark,
                        startedAtMs = startedAtMs,
                        durationMs = durationMs,
                        isCameraSharing = myIsCameraSharing,
                        videoConnectionState = videoConnectionState,
                        videoSessionStartedAtMs = myVideoSessionStartedAtMs,
                        isFrontCameraActive = isFrontCameraActive
                    )
                    is MapBannerState.LastKnown -> LastKnownBannerRow(state, theme.isDark)
                    is MapBannerState.OtherSharing -> OtherSharingBannerRow(
                        state = state,
                        location = otherLocation,
                        isDark = theme.isDark,
                        isCameraSharing = otherLocation?.isCameraSharing == true,
                        videoConnectionState = videoConnectionState,
                        cameraSessionStartedAtMs = otherLocation?.liveSharingStartedAt ?: 0L
                    )
                    is MapBannerState.Mutual       -> MutualBannerRow(
                        state = state,
                        location = otherLocation,
                        isDark = theme.isDark,
                        myIsCameraSharing = myIsCameraSharing,
                        otherIsCameraSharing = otherLocation?.isCameraSharing == true,
                        videoConnectionState = videoConnectionState,
                        myVideoSessionStartedAtMs = myVideoSessionStartedAtMs,
                        otherCameraSessionStartedAtMs = otherLocation?.liveSharingStartedAt ?: 0L,
                        isFrontCameraActive = isFrontCameraActive
                    )
                }
            }
        }
    }
}

@Composable
private fun LastKnownBannerRow(state: MapBannerState.LastKnown, isDark: Boolean) {
    val iconTint = if (isDark) Color(0xFF90CAF9) else Color(0xFF1A73E8)
    val primaryText = if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF1A1A2E)
    val secondaryText = if (isDark) Color.White.copy(alpha = 0.50f) else Color(0xFF555577)

    var relativeTime by remember { mutableStateOf(formatRelativeTime(state.timestamp)) }
    LaunchedEffect(state.timestamp) {
        while (true) {
            relativeTime = formatRelativeTime(state.timestamp)
            delay(60_000L)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(15.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Last seen near ${state.address}",
                color = primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (state.timestamp > 0L) {
                Text(
                    text = "Updated $relativeTime",
                    color = secondaryText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.offset(y = (-5.5).dp)
                )
            }
        }
    }
}

// ── State-specific row composables ─────────────────────────────────────────────

@Composable
private fun LoadingBannerRow(isDark: Boolean) {
    val textColor = if (isDark) Color.White.copy(alpha = 0.35f) else Color(0xFF555577).copy(alpha = 0.6f)
    Text(
        text = "Loading location…",
        color = textColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal
    )
}

@Composable
private fun NoLocationBannerRow(isDark: Boolean) {
    val iconColor = if (isDark) Color.White.copy(alpha = 0.30f) else Color(0xFF888AAA).copy(alpha = 0.7f)
    val textColor = if (isDark) Color.White.copy(alpha = 0.38f) else Color(0xFF666688)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "Location not available",
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun MySharingBannerRow(
    isDark: Boolean,
    startedAtMs: Long,
    durationMs: Long,
    isCameraSharing: Boolean = false,
    videoConnectionState: MapVideoConnectionState? = null,
    videoSessionStartedAtMs: Long = 0L,
    isFrontCameraActive: Boolean = true,
) {
    val primaryText = Color.White
    val secondaryText = Color.White.copy(alpha = 0.75f)

    // Calculate remaining duration with countdown
    var remainingMs by remember { mutableStateOf(calculateRemainingMs(startedAtMs, durationMs)) }
    LaunchedEffect(startedAtMs, durationMs) {
        while (remainingMs > 0) {
            delay(1000L)
            remainingMs = calculateRemainingMs(startedAtMs, durationMs)
        }
    }

    val totalDurationStr = formatDuration(durationMs)
    val remainingStr = formatRemainingTime(remainingMs)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "You are sharing live location",
                color = primaryText,
                fontSize = 13.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$totalDurationStr • $remainingStr remaining",
                color = secondaryText,
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isCameraSharing) {
                CameraInlineSummary(
                    connectionState = videoConnectionState,
                    sessionStartedAtMs = videoSessionStartedAtMs,
                    isFrontCameraActive = isFrontCameraActive,
                    isSender = true,
                    modifier = Modifier.widthIn(min = 118.dp, max = 156.dp)
                )
            }
            LiveIndicatorBadge()
        }
    }
}

@Composable
private fun OtherSharingBannerRow(
    state: MapBannerState.OtherSharing,
    location: UserLocation?,
    isDark: Boolean,
    isCameraSharing: Boolean = false,
    videoConnectionState: MapVideoConnectionState? = null,
    cameraSessionStartedAtMs: Long = 0L,
) {
    val primaryText = Color.White
    val secondaryText = Color.White.copy(alpha = 0.75f)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = state.name.ifBlank { "User" },
                    color = primaryText,
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "is sharing live location",
                    color = secondaryText,
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            if (isCameraSharing) {
                CameraInlineSummary(
                    connectionState = videoConnectionState,
                    sessionStartedAtMs = cameraSessionStartedAtMs,
                    isFrontCameraActive = false,
                    isSender = false,
                    modifier = Modifier.widthIn(min = 118.dp, max = 156.dp)
                )
            }
            LiveIndicatorBadge()
        }
        if (location != null) {
            LocationDetailStats(loc = location)
        }
    }
}

@Composable
private fun MutualBannerRow(
    state: MapBannerState.Mutual,
    location: UserLocation?,
    isDark: Boolean,
    myIsCameraSharing: Boolean = false,
    otherIsCameraSharing: Boolean = false,
    videoConnectionState: MapVideoConnectionState? = null,
    myVideoSessionStartedAtMs: Long = 0L,
    otherCameraSessionStartedAtMs: Long = 0L,
    isFrontCameraActive: Boolean = true,
) {
    val primaryText = Color.White
    val secondaryText = Color.White.copy(alpha = 0.75f)

    val showCameraSummary = otherIsCameraSharing || myIsCameraSharing
    val isSender = myIsCameraSharing && !otherIsCameraSharing
    val sessionMs = if (otherIsCameraSharing) otherCameraSessionStartedAtMs else myVideoSessionStartedAtMs

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = "Live location active",
                    color = primaryText,
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = "Sharing with ${state.name.ifBlank { "User" }}",
                    color = secondaryText,
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            if (showCameraSummary) {
                CameraInlineSummary(
                    connectionState = videoConnectionState,
                    sessionStartedAtMs = sessionMs,
                    isFrontCameraActive = isFrontCameraActive,
                    isSender = isSender,
                    modifier = Modifier.widthIn(min = 118.dp, max = 156.dp)
                )
            }
            MutualLiveIndicatorBadge()
        }
        if (location != null) {
            LocationDetailStats(loc = location)
        }
    }
}

// ── Shared sub-components ──────────────────────────────────────────────────────

/**
 * Compact heading / speed / altitude / accuracy row shown beneath live-sharing rows.
 */
@Composable
private fun LocationDetailStats(
    loc: UserLocation,
) {
    val headingStr = if (loc.heading >= 0) "${String.format("%.0f", loc.heading)}\u00b0" else "\u2013"
    val speedKmh   = if (loc.speed >= 0) loc.speed * 3.6f else -1f
    val speedStr   = if (speedKmh >= 0) "${String.format("%.1f", speedKmh)} km/h" else "\u2013"
    val altStr     = if (loc.altitude > 0) "${String.format("%.0f", loc.altitude)}m" else "\u2013"
    val accStr     = "${String.format("%.0f", loc.accuracy)}m"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatCell(
            label = "Accuracy",
            value = accStr,
            valueColor = Color.White,
            modifier = Modifier.weight(1f)
        )
        StatCell(
            label = "Heading",
            value = "\u2191 $headingStr",
            valueColor = Color(0xFFFFEB3B),
            modifier = Modifier.weight(1f)
        )
        StatCell(
            label = "Speed",
            value = speedStr,
            valueColor = Color(0xFF00E5FF),
            modifier = Modifier.weight(1f)
        )
        StatCell(
            label = "Altitude",
            value = altStr,
            valueColor = Color(0xFF69F0AE),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Modern LIVE badge with a pulsing white dot indicator.
 * Red pill background with "LIVE" text — similar to streaming app indicators.
 */
@Composable
private fun LiveIndicatorBadge() {
    val infiniteTransition = rememberInfiniteTransition(label = "liveBadge")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFE53935))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Pulsing white dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = dotAlpha))
            )
            // LIVE text
            Text(
                text = "LIVE",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.4.sp
            )
        }
    }
}

/**
 * LIVE indicator with MUTUAL label: shows "MUTUAL • [pulsing dot] • LIVE".
 * Red pill with both labels and pulsing dot in the center.
 */
@Composable
private fun MutualLiveIndicatorBadge() {
    val infiniteTransition = rememberInfiniteTransition(label = "mutualLiveBadge")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotPulse"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFE53935))
            .padding(horizontal = 7.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // MUTUAL text
            Text(
                text = "MUTUAL",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.4.sp
            )
            
            // Pulsing white dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = dotAlpha))
            )
            
            // LIVE text
            Text(
                text = "LIVE",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.4.sp
            )
        }
    }
}

@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "liveDotPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotScale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    Box(
        modifier = Modifier
            .size((8 * scale).dp)
            .clip(CircleShape)
            .background(Color(0xFF69F0AE).copy(alpha = alpha))
    )
}

// ── Camera stream status ──────────────────────────────────────────────────────

private data class CameraInlineUi(
    val title: String,
    val detail: String,
    val containerColor: Color,
    val dotColor: Color,
)

@Composable
private fun CameraInlineSummary(
    connectionState: MapVideoConnectionState?,
    sessionStartedAtMs: Long,
    isFrontCameraActive: Boolean,
    isSender: Boolean,
    modifier: Modifier = Modifier,
) {
    val elapsedMs = rememberCameraElapsedMs(sessionStartedAtMs)
    val timeLabel = if (sessionStartedAtMs > 0L) formatElapsedDuration(elapsedMs) else "--:--"
    val ui = cameraInlineUi(
        connectionState = connectionState,
        isSender = isSender,
        isFrontCameraActive = isFrontCameraActive,
        elapsedLabel = timeLabel
    )
    val infiniteTransition = rememberInfiniteTransition(label = "cameraInlinePulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cameraInlineDotAlpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ui.containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(ui.dotColor.copy(alpha = dotAlpha))
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = ui.title,
                    color = Color.White,
                    fontSize = 10.5.sp,
                    lineHeight = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = ui.detail,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun cameraInlineUi(
    connectionState: MapVideoConnectionState?,
    isSender: Boolean,
    isFrontCameraActive: Boolean,
    elapsedLabel: String,
): CameraInlineUi {
    return when (connectionState) {
        MapVideoConnectionState.ERROR -> CameraInlineUi(
            title = if (isSender) "Camera Active" else "Live Feed Enabled",
            detail = if (isSender) "Interrupted • Reconnecting" else "Feed interrupted • Reconnecting",
            containerColor = Color(0x66B91C1C),
            dotColor = Color(0xFFFDA4AF)
        )
        MapVideoConnectionState.CONNECTING -> CameraInlineUi(
            title = if (isSender) "Streaming" else "Live Feed Enabled",
            detail = "$elapsedLabel • Adaptive link",
            containerColor = Color(0x668A5A00),
            dotColor = Color(0xFFFCD34D)
        )
        MapVideoConnectionState.WAITING -> CameraInlineUi(
            title = if (isSender) "Camera Active" else "Live Feed Enabled",
            detail = if (isSender) {
                "$elapsedLabel • Standby • ${if (isFrontCameraActive) "Front" else "Rear"}"
            } else {
                "$elapsedLabel • Waiting for stream"
            },
            containerColor = Color(0x663B5B7A),
            dotColor = Color(0xFF93C5FD)
        )
        MapVideoConnectionState.LIVE -> CameraInlineUi(
            title = if (isSender) "Streaming" else "Live Feed Enabled",
            detail = if (isSender) {
                "$elapsedLabel • Strong signal • ${if (isFrontCameraActive) "Front" else "Rear"}"
            } else {
                "$elapsedLabel • Strong signal"
            },
            containerColor = Color(0x66246A50),
            dotColor = Color(0xFF6EE7B7)
        )
        else -> CameraInlineUi(
            title = if (isSender) "Camera Active" else "Live Feed Enabled",
            detail = if (isSender) {
                "$elapsedLabel • ${if (isFrontCameraActive) "Front" else "Rear"} camera"
            } else {
                "$elapsedLabel • Feed ready"
            },
            containerColor = if (isFrontCameraActive) Color(0x66306A8A) else Color(0x66475569),
            dotColor = if (isFrontCameraActive) Color(0xFF67E8F9) else Color(0xFFCBD5E1)
        )
    }
}

@Composable
private fun rememberCameraElapsedMs(sessionStartedAtMs: Long): Long {
    var elapsedMs by remember(sessionStartedAtMs) {
        mutableStateOf(
            if (sessionStartedAtMs > 0L) maxOf(0L, System.currentTimeMillis() - sessionStartedAtMs) else 0L
        )
    }
    LaunchedEffect(sessionStartedAtMs) {
        if (sessionStartedAtMs <= 0L) return@LaunchedEffect
        while (true) {
            delay(1000L)
            elapsedMs = maxOf(0L, System.currentTimeMillis() - sessionStartedAtMs)
        }
    }
    return elapsedMs
}


// ── Helpers ────────────────────────────────────────────────────────────────────

private fun calculateRemainingMs(startedAtMs: Long, durationMs: Long): Long {
    if (startedAtMs <= 0 || durationMs <= 0) return 0
    val elapsedMs = System.currentTimeMillis() - startedAtMs
    val remaining = durationMs - elapsedMs
    return if (remaining > 0) remaining else 0
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0m"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0                 -> if (hours == 1L) "1 hour" else "$hours hours"
        minutes > 0               -> if (minutes == 1L) "1 minute" else "$minutes minutes"
        else                      -> "< 1 minute"
    }
}

private fun formatRemainingTime(remainingMs: Long): String {
    if (remainingMs <= 0) return "0:00"
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return when {
        hours > 0   -> String.format("%d:%02d:%02d", hours, minutes, seconds)
        minutes > 0 -> String.format("%d:%02d", minutes, seconds)
        else        -> "0:${String.format("%02d", seconds)}"
    }
}

private fun formatElapsedDuration(elapsedMs: Long): String {
    if (elapsedMs <= 0L) return "0:00"
    val totalSeconds = elapsedMs / 1000
    val hours   = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val diff = System.currentTimeMillis() - timestampMs
    return when {
        diff <   60_000L       -> "just now"
        diff < 3_600_000L      -> "${diff / 60_000L} min${if (diff / 60_000L != 1L) "s" else ""} ago"
        diff < 86_400_000L     -> "${diff / 3_600_000L} hr${if (diff / 3_600_000L != 1L) "s" else ""} ago"
        else                   -> "${diff / 86_400_000L} day${if (diff / 86_400_000L != 1L) "s" else ""} ago"
    }
}
