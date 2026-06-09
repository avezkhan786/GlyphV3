package com.glyph.glyph_v3.ui.chat.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.UserLocation
import com.glyph.glyph_v3.data.repo.LocationRepository
import com.glyph.glyph_v3.ui.chat.map.video.MapVideoMarkerOverlay
import com.glyph.glyph_v3.ui.chat.map.video.MapVideoMode
import com.glyph.glyph_v3.ui.chat.map.video.MapVideoSessionManager
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "ChatMapBackground"

private fun UserLocation?.hasValidCoordinates(): Boolean =
    this != null && !(latitude == 0.0 && longitude == 0.0)

/**
 * Full-screen Google Map rendered as the chat background layer.
 *
 * MY location is read directly from the device GPS (no Firebase round-trip).
 * OTHER user's location is observed from Firebase RTDB.
 */
@SuppressLint("MissingPermission")
@OptIn(MapsComposeExperimentalApi::class)
@Composable
fun ChatMapBackground(
    myUserId: String,
    otherUserId: String,
    otherUserName: String,
    myAvatarUrl: String,
    otherAvatarUrl: String,
    videoSessionManager: MapVideoSessionManager? = null,
    autoReceiveVideoEnabled: Boolean = false,
    onAcceptIncomingCameraInvite: () -> Unit = {},
    onDismissIncomingCameraInvite: () -> Unit = {},
    isInteractive: Boolean,
    isVisible: Boolean,
    routePoints: List<LatLng> = emptyList(),
    isNavigating: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val theme = glyphTheme

    LaunchedEffect(videoSessionManager, autoReceiveVideoEnabled, isVisible) {
        val manager = videoSessionManager ?: return@LaunchedEffect
        manager.setRemoteShareExpected(autoReceiveVideoEnabled)
        when {
            autoReceiveVideoEnabled && isVisible -> {
                manager.startViewingRemoteCamera(automatic = true)
            }
            !autoReceiveVideoEnabled && (
                manager.currentMode == MapVideoMode.AUTO_RECEIVE_ONLY ||
                    manager.currentMode == MapVideoMode.AUTO_SEND_AND_AUTO_RECEIVE
                ) -> {
                manager.stopViewingRemoteCamera()
            }
        }
    }

    val hasFineLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarseLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasLocation = hasFineLocation || hasCoarseLocation

    // ── My location: seed from device + live updates via Firebase RTDB ─────
    var myLatLng by remember { mutableStateOf<LatLng?>(null) }
    var myUserLocation by remember { mutableStateOf<UserLocation?>(null) }

    // Quick local seed from lastLocation for initial camera placement.
    LaunchedEffect(myUserId, otherUserId, hasLocation) {
        if (!hasLocation || myUserId.isBlank() || otherUserId.isBlank()) return@LaunchedEffect
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val last = client.lastLocation.await()
            if (last != null && last.latitude != 0.0 && last.longitude != 0.0) {
                myLatLng = LatLng(last.latitude, last.longitude)
            } else {
                Log.w(TAG, "[MY-LOCAL] lastLocation null/zero -- waiting for a better local fix")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MY-LOCAL] Exception: ${e.message}")
        }
    }

    // Observe my own RTDB node for this chat. When live-sharing it animates; otherwise
    // it remains as a frozen last-shared marker.
    LaunchedEffect(myUserId, otherUserId) {
        if (myUserId.isBlank() || otherUserId.isBlank()) return@LaunchedEffect
        LocationRepository.observeMyLocationForTarget(otherUserId).collectLatest { loc ->
            if (loc.hasValidCoordinates()) {
                myUserLocation = loc
            } else {
                myUserLocation = null
            }

            if (loc?.isActivelyLiveSharing() == true && loc.hasValidCoordinates()) {
                myLatLng = LatLng(loc.latitude, loc.longitude)
            }
        }
    }

    // ── Other user's location: read from Firebase RTDB ─────
    var otherLatLng by remember { mutableStateOf<LatLng?>(null) }
    var otherUserLocation by remember { mutableStateOf<UserLocation?>(null) }
    var waitingForOther by remember { mutableStateOf(true) }

    LaunchedEffect(otherUserId) {
        if (otherUserId.isBlank()) {
            Log.e(TAG, "[RTDB] otherUserId is blank — cannot observe other user location")
            return@LaunchedEffect
        }
        LocationRepository.observeLocationSharedWithMe(otherUserId).collectLatest { loc ->
            waitingForOther = false
            when {
                loc == null ->
                    Log.w(TAG, "[RTDB] No data for $otherUserId — node doesn't exist yet (other user hasn't enabled map)")
                loc.latitude == 0.0 && loc.longitude == 0.0 ->
                    Log.w(TAG, "[RTDB] Got (0,0) for $otherUserId — IGNORING corrupt/default value")
                else -> {
                    otherLatLng = LatLng(loc.latitude, loc.longitude)
                    otherUserLocation = loc
                }
            }
        }
    }

    // ── Custom avatar markers ──────────────────────────────
    var myMarkerIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }
    var otherMarkerIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }

    LaunchedEffect(myAvatarUrl) {
        AvatarMarkerFactory.create(context, myAvatarUrl, borderColor = 0xFF4CAF50.toInt()) { desc ->
            myMarkerIcon = desc
        }
    }
    LaunchedEffect(otherAvatarUrl) {
        AvatarMarkerFactory.create(context, otherAvatarUrl, borderColor = 0xFF2196F3.toInt()) { desc ->
            otherMarkerIcon = desc
        }
    }

    val animatedMyLatLng = rememberAnimatedMarkerPosition(
        target = myLatLng,
        targetTimestamp = myUserLocation?.timestamp ?: 0L
    )
    val animatedOtherLatLng = rememberAnimatedMarkerPosition(
        target = otherLatLng,
        targetTimestamp = otherUserLocation?.timestamp ?: 0L
    )
    val myMarkerPosition = when {
        myUserLocation?.isActivelyLiveSharing() == true -> animatedMyLatLng
        myUserLocation.hasValidCoordinates() -> LatLng(myUserLocation!!.latitude, myUserLocation!!.longitude)
        else -> null
    }
    val otherMarkerPosition = when {
        otherUserLocation?.isActivelyLiveSharing() == true -> animatedOtherLatLng
        otherUserLocation.hasValidCoordinates() -> LatLng(otherUserLocation!!.latitude, otherUserLocation!!.longitude)
        else -> null
    }
    // ── Camera position ────────────────────────────────────
    val cameraPositionState = rememberCameraPositionState()
    val scope = rememberCoroutineScope()
    var cameraInitialized by remember { mutableStateOf(false) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }

    // Focus point is always the OTHER user.
    // Re-center if the other user's location changes OR if we leave interactive mode
    LaunchedEffect(otherLatLng, isInteractive) {
        val other = otherLatLng ?: return@LaunchedEffect
        
        val update = CameraUpdateFactory.newLatLngZoom(other, 16f)
        
        if (!cameraInitialized) {
            cameraPositionState.move(update)
            cameraInitialized = true
        } else {
            // Use faster animation if we're snapping back from interactive mode
            val duration = if (!isInteractive) 800 else 1500
            scope.launch { cameraPositionState.animate(update, durationMs = duration) }
        }
    }

    // Fallback: if other user location is null, show MY location as the center point until they connect
    LaunchedEffect(myLatLng) {
        if (otherLatLng != null || cameraInitialized) return@LaunchedEffect
        val me = myLatLng ?: return@LaunchedEffect
        cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(me, 16f))
        cameraInitialized = true
    }

    // ── When navigating, zoom to fit both markers + route ──
    LaunchedEffect(isNavigating, myLatLng, otherLatLng) {
        if (!isNavigating) return@LaunchedEffect
        val me = myLatLng ?: return@LaunchedEffect
        val other = otherLatLng ?: return@LaunchedEffect
        val bounds = LatLngBounds.builder()
            .include(me)
            .include(other)
            .build()
        val padding = 120 // px
        val update = CameraUpdateFactory.newLatLngBounds(bounds, padding)
        scope.launch { cameraPositionState.animate(update, durationMs = 1000) }
    }

    // ── Map style ──────────────────────────────────────────
    val mapStyleOptions = remember(context, theme.isDark) {
        if (theme.isDark) loadNightMapStyle(context) else null
    }
    val mapProperties = remember(isInteractive, mapStyleOptions) {
        MapProperties(
            isMyLocationEnabled = false,   // custom avatar pin used instead
            mapStyleOptions = mapStyleOptions,
            mapType = MapType.NORMAL
        )
    }
    val mapUiSettings = remember(isInteractive) {
        MapUiSettings(
            zoomControlsEnabled = false,
            zoomGesturesEnabled = isInteractive,
            scrollGesturesEnabled = isInteractive,
            tiltGesturesEnabled = isInteractive,
            rotationGesturesEnabled = isInteractive,
            compassEnabled = isInteractive,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false
        )
    }

    // ── Map loading state ──────────────────────────────────
    var mapLoaded by remember { mutableStateOf(false) }
    val mapAlpha by animateFloatAsState(
        targetValue = if (mapLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "mapRevealAlpha"
    )

    LaunchedEffect(isVisible, theme.isDark) {
        mapLoaded = false
    }

    // ── Render ─────────────────────────────────────────────
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(500)),
        exit = fadeOut(tween(500)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (theme.isDark) Color(0xFF212121) else Color.White)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(1f - mapAlpha)
                    .background(if (theme.isDark) Color(0xFF212121) else Color.White)
            )

            GoogleMap(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(mapAlpha),
                cameraPositionState = cameraPositionState,
                properties = mapProperties,
                uiSettings = mapUiSettings,
                onMapLoaded = {
                    mapLoaded = true
                }
            ) {
                MapEffect(theme.isDark) { map ->
                    googleMap = map
                    if (theme.isDark) {
                        map.setMapStyle(mapStyleOptions)
                    }
                }

                // My avatar pin marker
                myMarkerPosition?.let { pos ->
                    Marker(
                        state = MarkerState(position = pos),
                        icon = myMarkerIcon ?: BitmapDescriptorFactory.defaultMarker(
                            BitmapDescriptorFactory.HUE_GREEN
                        ),
                        title = "Me",
                        anchor = Offset(0.5f, 1.0f)
                    )
                }
                // Other user's avatar pin marker
                otherMarkerPosition?.let { pos ->
                    if (videoSessionManager?.isVideoModeEnabled == true) return@let
                    Marker(
                        state = MarkerState(position = pos),
                        icon = otherMarkerIcon ?: BitmapDescriptorFactory.defaultMarker(
                            BitmapDescriptorFactory.HUE_AZURE
                        ),
                        title = otherUserName.ifEmpty { "Other user" },
                        anchor = Offset(0.5f, 1.0f)
                    )
                }

                // ── Route polyline (rendered BELOW markers by draw order) ─────
                if (isNavigating && routePoints.size >= 2) {
                    // Shadow / outline polyline for contrast
                    Polyline(
                        points = routePoints,
                        color = Color(0x664285F4),
                        width = 18f,
                        geodesic = true
                    )
                    // Main route polyline
                    Polyline(
                        points = routePoints,
                        color = Color(0xFF4285F4),
                        width = 10f,
                        geodesic = true,
                        pattern = null  // solid line
                    )
                }
            }

            // Semi-transparent overlay so chat bubbles remain readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (theme.isDark) Color(0xFF04070A).copy(alpha = 0.10f)
                        else Color.White.copy(alpha = 0.20f)
                    )
            )

            videoSessionManager?.let { manager ->
                MapVideoMarkerOverlay(
                    googleMap = googleMap,
                    anchor = animatedOtherLatLng,
                    cameraMovementKey = cameraPositionState.position,
                    avatarUrl = otherAvatarUrl,
                    otherUserName = otherUserName,
                    manager = manager,
                    isVisible = isVisible,
                    onAcceptIncomingInvite = onAcceptIncomingCameraInvite,
                    onDismissIncomingInvite = onDismissIncomingCameraInvite
                )
            }
        }
    }
}

@Composable
private fun rememberAnimatedMarkerPosition(
    target: LatLng?,
    targetTimestamp: Long = 0L
): LatLng? {
    if (target == null) return null

    val latitude = remember { Animatable(target.latitude.toFloat()) }
    val longitude = remember { Animatable(target.longitude.toFloat()) }
    var lastTargetTimestamp by remember { mutableStateOf(0L) }
    var lastSampleRealtime by remember { mutableStateOf(0L) }

    LaunchedEffect(target.latitude, target.longitude, targetTimestamp) {
        val current = LatLng(latitude.value.toDouble(), longitude.value.toDouble())
        val distanceMeters = distanceBetweenMeters(current, target)
        val sampleRealtime = SystemClock.elapsedRealtime()
        val previousTargetTimestamp = lastTargetTimestamp
        val previousSampleRealtime = lastSampleRealtime
        val effectiveTimestamp = targetTimestamp.takeIf { it > 0L } ?: System.currentTimeMillis()

        lastTargetTimestamp = effectiveTimestamp
        lastSampleRealtime = sampleRealtime

        if (previousSampleRealtime == 0L || distanceMeters < 0.75f) {
            latitude.snapTo(target.latitude.toFloat())
            longitude.snapTo(target.longitude.toFloat())
            return@LaunchedEffect
        }

        val observedIntervalMs = when {
            previousTargetTimestamp > 0L && effectiveTimestamp > previousTargetTimestamp ->
                effectiveTimestamp - previousTargetTimestamp
            previousSampleRealtime > 0L ->
                sampleRealtime - previousSampleRealtime
            else -> 0L
        }

        val cadenceDurationMs = (observedIntervalMs - 120L)
            .coerceIn(1_000L, 5_200L)
            .toInt()
        val distanceDurationMs = markerAnimationDurationMillis(distanceMeters)
        val durationMs = maxOf(distanceDurationMs, cadenceDurationMs)

        listOf(
            launch {
                latitude.animateTo(
                    target.latitude.toFloat(),
                    animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
                )
            },
            launch {
                longitude.animateTo(
                    target.longitude.toFloat(),
                    animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
                )
            }
        ).joinAll()
    }

    return LatLng(latitude.value.toDouble(), longitude.value.toDouble())
}

private fun markerAnimationDurationMillis(distanceMeters: Float): Int = when {
    distanceMeters < 5f -> 650
    distanceMeters < 20f -> 900
    distanceMeters < 75f -> 1200
    distanceMeters < 200f -> 1500
    else -> 1800
}

private fun distanceBetweenMeters(start: LatLng, end: LatLng): Float {
    val result = FloatArray(1)
    Location.distanceBetween(
        start.latitude,
        start.longitude,
        end.latitude,
        end.longitude,
        result
    )
    return result[0]
}

/**
 * Observe device GPS location directly using FusedLocationProviderClient.
 * Emits LatLng every ~5 seconds. No Firebase dependency.
 */
@SuppressLint("MissingPermission")
private fun observeDeviceLocation(context: android.content.Context): Flow<LatLng> = callbackFlow {
    val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    if (!hasPerm) {
        Log.e(TAG, "[GPS-FLOW] No location permission — closing flow immediately")
        close()
        return@callbackFlow
    }

    val client = LocationServices.getFusedLocationProviderClient(context)

    // Emit last known location immediately while waiting for fresh fix
    // Only use it if accuracy <= 200m (reject stale/zero GPS fixes)
    client.lastLocation
        .addOnSuccessListener { loc ->
            if (loc == null) {
                Log.w(TAG, "[GPS-FLOW] lastLocation returned null — no cached fix")
            } else if (loc.latitude == 0.0 && loc.longitude == 0.0) {
                Log.w(TAG, "[GPS-FLOW] lastLocation is (0,0) — IGNORING invalid fix")
            } else if (loc.accuracy > 500f) {
                Log.w(TAG, "[GPS-FLOW] lastLocation accuracy too low: ${loc.accuracy}m — skipping, waiting for better fix")
            } else {
                val age = System.currentTimeMillis() - loc.time
                trySend(LatLng(loc.latitude, loc.longitude))
            }
        }
        .addOnFailureListener { e ->
            Log.e(TAG, "[GPS-FLOW] lastLocation failed: ${e.message}")
        }

    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
        .setMinUpdateIntervalMillis(3_000L)
        .build()

    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: run {
                Log.w(TAG, "[GPS-FLOW] onLocationResult fired but lastLocation is null")
                return
            }
            if (loc.latitude == 0.0 && loc.longitude == 0.0) {
                Log.w(TAG, "[GPS-FLOW] Callback returned (0,0) — IGNORING")
                return
            }
            trySend(LatLng(loc.latitude, loc.longitude))
        }
    }

    client.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
        .addOnSuccessListener { }
        .addOnFailureListener { e -> Log.e(TAG, "[GPS-FLOW] requestLocationUpdates FAILED: ${e.message}") }

    awaitClose {
        client.removeLocationUpdates(callback)
    }
}

private fun loadNightMapStyle(context: Context): MapStyleOptions? {
    return runCatching {
        MapStyleOptions.loadRawResourceStyle(context, R.raw.chat_map_dark_night_style)
    }.onFailure { error ->
        Log.e(TAG, "[MAP] Failed to load dark map style", error)
    }.getOrNull()
}
