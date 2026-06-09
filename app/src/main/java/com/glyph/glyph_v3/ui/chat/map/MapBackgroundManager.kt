package com.glyph.glyph_v3.ui.chat.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.data.models.UserLocation
import com.glyph.glyph_v3.data.repo.LocationRepository
import com.glyph.glyph_v3.data.service.ActiveSharingRegistry
import com.glyph.glyph_v3.data.service.LocationUpdateService
import com.glyph.glyph_v3.ui.chat.map.routing.RoutingManager
import com.glyph.glyph_v3.ui.chat.map.routing.RoutingNotificationManager
import com.glyph.glyph_v3.ui.chat.map.routing.RoutingStateCallback
import com.glyph.glyph_v3.ui.chat.map.routing.RoutingUiState
import com.glyph.glyph_v3.ui.chat.map.routing.TravelMode
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Central state holder for the Google Maps chat background feature.
 *
 * Manages:
 * - Map visibility toggle
 * - Interactive mode toggle
 * - **Per-user** live location sharing lifecycle
 * - Location permission status
 * - Observable state for Compose UI
 *
 * All location sharing is scoped to the (myUserId, otherUserId) pair.
 * Sharing with User B is completely isolated from sharing with User C.
 *
 * Should be created in [ChatActivity.onCreate] and bound to its lifecycle.
 */
@Stable
class MapBackgroundManager(
    private val context: Context,
    private val scope: CoroutineScope,
    val chatId: String,
    val myUserId: String,
    val otherUserId: String,
    initialMyAvatarUrl: String = "",
    initialOtherAvatarUrl: String = "",
    var otherUserName: String = ""
) {
    private val TAG = "MapBackgroundManager"
    private val prefs = MapBackgroundPreferences.getInstance(context)
    private val notificationManager = RoutingNotificationManager(context)

    init {
        // Ensure the sharing registry is initialized before any operations
        ActiveSharingRegistry.init(context)
    }

    // ── Observable state (read by Compose) ─────────────────

    /** Whether the map background is currently enabled. */
    var isMapEnabled by mutableStateOf(prefs.isMapBackgroundEnabled(chatId))
        private set

    /** Whether the map is in interactive (full gesture) mode. */
    var isInteractiveMode by mutableStateOf(false)
        private set

    /** Whether live location sharing is active for this specific chat. */
    var isLiveSharing by mutableStateOf(prefs.isLiveSharingEnabled(chatId))
        private set

    /** Timestamp when live sharing started (ms since epoch). */
    var liveSharingStartedAt by mutableStateOf(prefs.getLiveSharingStartedAt(chatId))
        private set

    /** Requested live sharing duration in ms. */
    var liveSharingDurationMs by mutableStateOf(prefs.getLiveSharingDuration(chatId))
        private set

    /** Current accuracy of my location (meters), for the banner display. */
    var myAccuracyMeters by mutableStateOf(0f)
        private set

    /** Whether location permission is granted. */
    var hasLocationPermission by mutableStateOf(checkLocationPermission())
        private set

    /** Other user's live sharing state (from Firebase, scoped to this pair). */
    var otherUserLiveSharing by mutableStateOf(false)
        private set

    /** Whether the other user is also sharing their camera alongside live location. */
    var otherUserCameraSharing by mutableStateOf(false)
        private set

    /** My avatar source used for the live-location marker. */
    var myAvatarUrl by mutableStateOf(initialMyAvatarUrl)
        private set

    /** Other user's avatar source used for the live-location marker. */
    var otherAvatarUrl by mutableStateOf(initialOtherAvatarUrl)
        private set

    // ── Routing ────────────────────────────────────────────

    // Callback for routing state changes — triggered when navigation starts/stops/updates
    private val routingCallback = object : RoutingStateCallback {
        override fun onNavigationStarted(mode: TravelMode) {
            // Show notification when navigation starts
            showNavigationNotification()
        }

        override fun onNavigationStopped() {
            // Cancel notification when navigation stops
            notificationManager.cancelNavigationNotification()
        }

        override fun onRoutingInfoUpdated(distance: String, eta: String) {
            // Update notification with new info
            showNavigationNotification()
        }
    }

    /** Routing manager — fully modular, does not touch live-location logic. */
    val routingManager = RoutingManager(scope, chatId, routingCallback)

    /** Convenience: current routing UI state (read by Compose). */
    val routingState: RoutingUiState get() = routingManager.uiState

    private var myLocationJob: Job? = null
    private var otherLocationJob: Job? = null

    private fun showNavigationNotification() {
        if (!routingState.isNavigating) {
            notificationManager.cancelNavigationNotification()
            return
        }

        val mainActivityIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            android.content.Intent(context, com.glyph.glyph_v3.ui.chat.ChatActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        notificationManager.showNavigationNotification(
            otherUserName = otherUserName.ifBlank { "User" },
            distance = routingState.formattedDistance,
            eta = routingState.formattedEta,
            chatId = chatId,
            mainActivityIntent = mainActivityIntent
        )
    }

    // ── Actions ────────────────────────────────────────────

    /**
     * Toggle map background on/off.
     * When enabling, registers [otherUserId] as a passive sharing target
     * and starts location updates. When disabling, removes the target.
     */
    fun toggleMapBackground() {
        val newEnabled = !isMapEnabled
        isMapEnabled = newEnabled
        prefs.setMapBackgroundEnabled(chatId, newEnabled)

        if (newEnabled) {
            refreshPermissionStatus()
            if (hasLocationPermission) {
                ActiveSharingRegistry.addPassiveTarget(otherUserId)
                LocationUpdateService.startPassive(context)
                startObservingLocations()
            }
        } else {
            isInteractiveMode = false
            stopLiveSharing()
            ActiveSharingRegistry.removeTarget(otherUserId)
            if (!ActiveSharingRegistry.hasAnyTargets()) {
                LocationUpdateService.stop(context)
            }
            myLocationJob?.cancel()
            otherLocationJob?.cancel()
        }
    }

    /** Enable map background (called after permission is granted). */
    fun enableMapBackground() {
        isMapEnabled = true
        prefs.setMapBackgroundEnabled(chatId, true)
        hasLocationPermission = true
        // Remove any stale (0,0) data from previous broken sessions
        LocationRepository.clearStaleLocationData(otherUserId)
        // Register the other user as a passive sharing target
        ActiveSharingRegistry.addPassiveTarget(otherUserId)
        LocationUpdateService.startPassive(context)
        startObservingLocations()
    }

    /** Disable map background. */
    fun disableMapBackground() {
        isMapEnabled = false
        isInteractiveMode = false
        prefs.setMapBackgroundEnabled(chatId, false)
        stopLiveSharing()
        ActiveSharingRegistry.removeTarget(otherUserId)
        if (!ActiveSharingRegistry.hasAnyTargets()) {
            LocationUpdateService.stop(context)
        }
        myLocationJob?.cancel()
        otherLocationJob?.cancel()
    }

    fun toggleInteractiveMode() {
        isInteractiveMode = !isInteractiveMode
        prefs.setInteractiveMode(chatId, isInteractiveMode)
    }

    fun exitInteractiveMode() {
        isInteractiveMode = false
        prefs.setInteractiveMode(chatId, false)
    }

    /** Refresh marker avatar sources after async profile/cache updates complete. */
    fun updateAvatarUrls(
        myAvatarUrl: String? = null,
        otherAvatarUrl: String? = null
    ) {
        myAvatarUrl?.let { this.myAvatarUrl = it }
        otherAvatarUrl?.let { this.otherAvatarUrl = it }
    }

    // ── Routing actions ────────────────────────────────────

    /** Start navigation to the other user. */
    fun startNavigation(mode: TravelMode = TravelMode.DRIVING) {
        routingManager.startNavigation(mode)
        // Persist navigation state
        prefs.setNavigationEnabled(chatId, true)
        prefs.setNavigationTravelMode(chatId, mode.osrmProfile)
    }

    /** Stop navigation and remove the route. */
    fun stopNavigation() {
        routingManager.stopNavigation()
        // Clear persisted navigation state
        prefs.clearNavigationState(chatId)
    }

    /** Toggle between driving and walking. */
    fun toggleTravelMode() {
        val next = if (routingState.travelMode == TravelMode.DRIVING)
            TravelMode.WALKING else TravelMode.DRIVING
        routingManager.setTravelMode(next)
        // Update persisted travel mode
        prefs.setNavigationTravelMode(chatId, next.osrmProfile)
    }

    /**
     * Start live location sharing **with [otherUserId] only** for the specified duration.
     * This is completely isolated from any other chat.
     *
     * @param durationMs Duration in ms. Common values:
     *   - 15 min = 900_000
     *   - 1 hr   = 3_600_000
     *   - 8 hr   = 28_800_000
     */
    fun startLiveSharing(durationMs: Long = 3_600_000L, displayName: String = "") {
        isLiveSharing = true
        liveSharingDurationMs = durationMs
        liveSharingStartedAt = System.currentTimeMillis()
        prefs.setLiveSharingEnabled(chatId, true)
        prefs.setLiveSharingDuration(chatId, durationMs)
        prefs.setLiveSharingStartedAt(chatId, liveSharingStartedAt)
        // Register in the sharing registry and update Firebase (per-target)
        ActiveSharingRegistry.addLiveTarget(otherUserId, durationMs, displayName)
        LocationRepository.startLiveSharing(otherUserId, durationMs)
        LocationUpdateService.startLive(context, durationMs)
    }

    /**
     * Stop live sharing with [otherUserId].
     * The last location at the moment of stopping is preserved as "last seen"
     * visible only to [otherUserId].
     */
    fun stopLiveSharing() {
        if (!isLiveSharing) return
        isLiveSharing = false
        liveSharingStartedAt = 0L
        prefs.setLiveSharingEnabled(chatId, false)
        prefs.setLiveSharingStartedAt(chatId, 0L)
        com.glyph.glyph_v3.data.service.MapCameraShareForegroundService.stop(context, otherUserId)
        // Remove live target and update Firebase (per-target)
        ActiveSharingRegistry.removeLiveTarget(otherUserId)
        LocationRepository.stopLiveSharing(otherUserId)
        // Adjust service interval: if other chats still have live targets, keep live;
        // otherwise drop to passive (if map is still enabled).
        if (isMapEnabled && hasLocationPermission) {
            if (ActiveSharingRegistry.hasAnyLiveTargets()) {
                // Another chat has live targets — keep live interval
            } else if (ActiveSharingRegistry.hasAnyTargets()) {
                LocationUpdateService.startPassive(context)
            }
        }
    }

    fun refreshPermissionStatus() {
        hasLocationPermission = checkLocationPermission()
    }

    // ── Internal ───────────────────────────────────────────

    private fun startObservingLocations() {
        // Observe my own location (as written for this chat's target) for accuracy display
        myLocationJob?.cancel()
        myLocationJob = scope.launch {
            LocationRepository.observeMyLocationForTarget(otherUserId).collectLatest { loc ->
                if (loc != null) {
                    myAccuracyMeters = loc.accuracy
                    // Feed routing manager (non-intrusive — no-op if not navigating)
                    if (loc.isActivelyLiveSharing() && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                        routingManager.onMyLocationChanged(LatLng(loc.latitude, loc.longitude))
                    }
                    if (loc.isLiveSharing && !isLiveSharing) {
                        // Restore state if app was killed while sharing
                        isLiveSharing = true
                        liveSharingStartedAt = loc.liveSharingStartedAt
                        liveSharingDurationMs = loc.liveSharingDurationMs
                    }
                    if (loc.isLiveSharingExpired() && isLiveSharing) {
                        stopLiveSharing()
                    }
                }
            }
        }

        // Observe other user's location (what they share with me) for their live sharing state
        otherLocationJob?.cancel()
        otherLocationJob = scope.launch {
            LocationRepository.observeLocationSharedWithMe(otherUserId).collectLatest { loc ->
                otherUserLiveSharing = loc?.isActivelyLiveSharing() == true
                otherUserCameraSharing = otherUserLiveSharing && loc?.isCameraSharing == true
                // Feed routing manager (non-intrusive)
                if (loc?.isActivelyLiveSharing() == true && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                    routingManager.onOtherLocationChanged(LatLng(loc.latitude, loc.longitude))
                }
            }
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Call from Activity.onResume to restore live state if service is still running. */
    fun onResume() {
        refreshPermissionStatus()
        if (isMapEnabled && hasLocationPermission) {
            startObservingLocations()
        }
        // Restore navigation state if it was persisted
        if (prefs.isNavigationEnabled(chatId) && !routingState.isNavigating && hasLocationPermission) {
            val modeStr = prefs.getNavigationTravelMode(chatId)
            val mode = TravelMode.fromProfile(modeStr)
            routingManager.startNavigation(mode)
        }
    }

    /** Call from Activity.onPause to clean up observers. */
    fun onPause() {
        myLocationJob?.cancel()
        otherLocationJob?.cancel()
    }

    /** Call from Activity.onDestroy for final cleanup. */
    fun onDestroy() {
        myLocationJob?.cancel()
        otherLocationJob?.cancel()
        routingManager.destroy()
        // Don't stop the service if live sharing is active — it should keep running.
        // Don't stop the service if other chats still have targets.
        if (!isLiveSharing && isMapEnabled && !ActiveSharingRegistry.hasAnyTargets()) {
            LocationUpdateService.stop(context)
        }
    }
}
