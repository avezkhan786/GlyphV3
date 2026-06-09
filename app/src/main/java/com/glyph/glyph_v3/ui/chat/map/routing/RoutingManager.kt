package com.glyph.glyph_v3.ui.chat.map.routing

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Callback interface for routing state changes.
 */
interface RoutingStateCallback {
    fun onNavigationStarted(mode: TravelMode)
    fun onNavigationStopped()
    fun onRoutingInfoUpdated(distance: String, eta: String)
}

/**
 * Manages routing state between two live locations with intelligent
 * throttling/debouncing to avoid excessive OSRM API calls.
 *
 * Compose reads [uiState] directly — it is a snapshot-state backed field.
 *
 * Lifecycle: create in [MapBackgroundManager], call [startNavigation] /
 * [stopNavigation], feed location updates via [onMyLocationChanged] /
 * [onOtherLocationChanged].
 */
@Stable
class RoutingManager(
    private val scope: CoroutineScope,
    private val chatId: String = "",
    private val callback: RoutingStateCallback? = null
) {
    private val TAG = "RoutingManager"

    // ── Observable state ───────────────────────────────────
    var uiState by mutableStateOf(RoutingUiState())
        private set

    // ── Internal tracking ──────────────────────────────────
    private var myLatLng: LatLng? = null
    private var otherLatLng: LatLng? = null
    private var fetchJob: Job? = null
    private var lastFetchTimeMs = 0L

    /** Minimum interval between route fetches (ms). */
    private val THROTTLE_INTERVAL_MS = 5_000L

    /** Debounce delay after a location change before fetching (ms). */
    private val DEBOUNCE_DELAY_MS = 2_000L

    /** Minimum distance change (m) to trigger a re-fetch. */
    private val MIN_DISTANCE_CHANGE_M = 20.0

    /** Last origin/destination used for the fetch — for distance-gating. */
    private var lastFetchOrigin: LatLng? = null
    private var lastFetchDestination: LatLng? = null

    // ── Public API ─────────────────────────────────────────

    fun startNavigation(travelMode: TravelMode = TravelMode.DRIVING) {
        uiState = RoutingUiState(
            isNavigating = true,
            travelMode = travelMode,
            isRecalculating = true,
            chatId = chatId
        )
        callback?.onNavigationStarted(travelMode)
        scheduleRouteFetch(immediate = true)
    }

    fun stopNavigation() {
        fetchJob?.cancel()
        fetchJob = null
        lastFetchOrigin = null
        lastFetchDestination = null
        uiState = RoutingUiState(isNavigating = false)
        callback?.onNavigationStopped()
    }

    fun setTravelMode(mode: TravelMode) {
        if (mode == uiState.travelMode) return
        uiState = uiState.copy(travelMode = mode, isRecalculating = true)
        lastFetchOrigin = null // force re-fetch
        scheduleRouteFetch(immediate = true)
    }

    /**
     * Call whenever my live location updates.
     */
    fun onMyLocationChanged(latLng: LatLng) {
        myLatLng = latLng
        if (uiState.isNavigating) {
            maybeScheduleRouteFetch()
        }
    }

    /**
     * Call whenever the other user's live location updates.
     */
    fun onOtherLocationChanged(latLng: LatLng) {
        otherLatLng = latLng
        if (uiState.isNavigating) {
            maybeScheduleRouteFetch()
        }
    }

    // ── Internal ───────────────────────────────────────────

    /**
     * Checks distance-based gating before scheduling a fetch.
     */
    private fun maybeScheduleRouteFetch() {
        val origin = myLatLng ?: return
        val dest = otherLatLng ?: return

        // Distance-gate: only re-fetch if either endpoint moved significantly
        val lastO = lastFetchOrigin
        val lastD = lastFetchDestination
        if (lastO != null && lastD != null) {
            val originMoved = haversineMeters(lastO, origin)
            val destMoved = haversineMeters(lastD, dest)
            if (originMoved < MIN_DISTANCE_CHANGE_M && destMoved < MIN_DISTANCE_CHANGE_M) {
                return // Not enough movement
            }
        }

        scheduleRouteFetch(immediate = false)
    }

    /**
     * Schedules a debounced route fetch. If [immediate], skips debounce
     * (but still respects throttle).
     */
    private fun scheduleRouteFetch(immediate: Boolean = false) {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            if (!immediate) {
                delay(DEBOUNCE_DELAY_MS)
            }

            // Throttle: wait until enough time has passed since last fetch
            val now = System.currentTimeMillis()
            val elapsed = now - lastFetchTimeMs
            if (elapsed < THROTTLE_INTERVAL_MS) {
                delay(THROTTLE_INTERVAL_MS - elapsed)
            }

            performRouteFetch()
        }
    }

    private suspend fun performRouteFetch() {
        val origin = myLatLng
        val dest = otherLatLng

        if (origin == null || dest == null) {
            Log.w(TAG, "Cannot fetch route: origin=$origin dest=$dest")
            uiState = uiState.copy(isRecalculating = false, error = "Waiting for locations…")
            return
        }

        uiState = uiState.copy(isRecalculating = true, error = null)

        val result = RouteService.fetchRoute(
            origin = origin,
            destination = dest,
            profile = uiState.travelMode.osrmProfile
        )

        lastFetchTimeMs = System.currentTimeMillis()
        lastFetchOrigin = origin
        lastFetchDestination = dest

        if (result != null) {
            uiState = uiState.copy(
                routePoints = result.points,
                distanceMeters = result.distanceMeters,
                durationSeconds = result.durationSeconds,
                isRecalculating = false,
                error = null
            )
            // Notify listener about updated routing info
            callback?.onRoutingInfoUpdated(uiState.formattedDistance, uiState.formattedEta)
        } else {
            uiState = uiState.copy(
                isRecalculating = false,
                error = "Could not fetch route"
            )
            Log.w(TAG, "Route fetch failed")
        }
    }

    // ── Haversine distance (metres) ────────────────────────

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val R = 6_371_000.0 // Earth radius in metres
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val sinLat = Math.sin(dLat / 2)
        val sinLng = Math.sin(dLng / 2)
        val h = sinLat * sinLat +
                Math.cos(Math.toRadians(a.latitude)) *
                Math.cos(Math.toRadians(b.latitude)) *
                sinLng * sinLng
        return 2 * R * Math.asin(Math.sqrt(h))
    }

    // ── Cleanup ────────────────────────────────────────────

    fun destroy() {
        fetchJob?.cancel()
        fetchJob = null
    }
}
