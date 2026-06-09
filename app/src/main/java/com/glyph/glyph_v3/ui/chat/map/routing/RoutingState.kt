package com.glyph.glyph_v3.ui.chat.map.routing

import androidx.compose.runtime.Immutable
import com.google.android.gms.maps.model.LatLng

/**
 * Observable routing state used by Compose UI.
 */
@Immutable
data class RoutingUiState(
    /** Whether the user has activated navigation. */
    val isNavigating: Boolean = false,
    /** Decoded polyline points for drawing on the map. */
    val routePoints: List<LatLng> = emptyList(),
    /** Total route distance in metres. */
    val distanceMeters: Double = 0.0,
    /** Estimated travel duration in seconds. */
    val durationSeconds: Double = 0.0,
    /** Current travel profile ("driving" or "walking"). */
    val travelMode: TravelMode = TravelMode.DRIVING,
    /** Whether a route recalculation is in progress. */
    val isRecalculating: Boolean = false,
    /** Human-readable error, if any. */
    val error: String? = null,
    /** Chat ID for notification context. */
    val chatId: String = ""
) {
    /** Distance formatted as "X.X km" or "X m". */
    val formattedDistance: String
        get() = when {
            distanceMeters >= 1000 -> String.format("%.1f km", distanceMeters / 1000)
            distanceMeters > 0    -> String.format("%.0f m", distanceMeters)
            else                  -> "—"
        }

    /** Duration formatted as "Xh Ym" or "X min". */
    val formattedDuration: String
        get() {
            if (durationSeconds <= 0) return "—"
            val totalMin = (durationSeconds / 60).toInt()
            val hours = totalMin / 60
            val mins = totalMin % 60
            return when {
                hours > 0 && mins > 0 -> "${hours}h ${mins}m"
                hours > 0             -> "${hours}h"
                mins > 0              -> "${mins} min"
                else                  -> "< 1 min"
            }
        }

    /** ETA formatted as clock time (e.g., "2:45 PM"). */
    val formattedEta: String
        get() {
            if (durationSeconds <= 0) return "—"
            val arrivalMs = System.currentTimeMillis() + (durationSeconds * 1000).toLong()
            val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(arrivalMs))
        }
}

/**
 * Supported travel modes.
 */
enum class TravelMode(val label: String, val osrmProfile: String) {
    DRIVING("Driving", "driving"),
    WALKING("Walking", "foot");

    companion object {
        fun fromProfile(profile: String): TravelMode =
            entries.find { it.osrmProfile == profile } ?: DRIVING
    }
}
