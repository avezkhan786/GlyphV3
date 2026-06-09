package com.glyph.glyph_v3.data.models

import androidx.compose.runtime.Immutable

/**
 * Represents a user's location data as stored in Firebase RTDB.
 *
 * Firebase RTDB structure:
 * ```
 * locations/
 *   {userId}/
 *     latitude: Double
 *     longitude: Double
 *     accuracy: Float
 *     timestamp: Long (server time)
 *     isLiveSharing: Boolean
 *     liveSharingStartedAt: Long
 *     liveSharingDurationMs: Long (requested duration, e.g. 15min / 1hr / 8hr)
 *     heading: Float (bearing in degrees 0-360, or -1 if unavailable)
 *     speed: Float (speed in m/s, or -1 if unavailable)
 *     altitude: Double (altitude in meters, or 0 if unavailable)
 * ```
 */
@Immutable
data class UserLocation(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = 0L,
    val isLiveSharing: Boolean = false,
    val isCameraSharing: Boolean = false,
    val liveSharingStartedAt: Long = 0L,
    val liveSharingDurationMs: Long = 0L,
    val heading: Float = -1f,
    val speed: Float = -1f,
    val altitude: Double = 0.0
) {
    /** True only while the sender explicitly has live sharing enabled and unexpired. */
    fun isActivelyLiveSharing(): Boolean = isLiveSharing && !isLiveSharingExpired()

    /** Check if live sharing has expired based on the requested duration. */
    fun isLiveSharingExpired(): Boolean {
        if (!isLiveSharing || liveSharingDurationMs <= 0L) return !isLiveSharing
        return System.currentTimeMillis() - liveSharingStartedAt > liveSharingDurationMs
    }

    /** Returns how long the live sharing has been active, in milliseconds. */
    fun liveSharingElapsedMs(): Long {
        if (!isLiveSharing) return 0L
        return System.currentTimeMillis() - liveSharingStartedAt
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "latitude" to latitude,
        "longitude" to longitude,
        "accuracy" to accuracy,
        "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP,
        "isLiveSharing" to isLiveSharing,
        "isCameraSharing" to isCameraSharing,
        "liveSharingStartedAt" to liveSharingStartedAt,
        "liveSharingDurationMs" to liveSharingDurationMs,
        "heading" to if (heading >= 0) heading else null,
        "speed" to if (speed >= 0) speed else null,
        "altitude" to if (altitude > 0) altitude else null
    )

    companion object {
        /** Minimum distance (meters) for marker to be worth animating. */
        const val MIN_MOVE_DISTANCE_M = 2f

        fun fromSnapshot(snapshot: Map<String, Any?>): UserLocation {
            return UserLocation(
                latitude = (snapshot["latitude"] as? Number)?.toDouble() ?: 0.0,
                longitude = (snapshot["longitude"] as? Number)?.toDouble() ?: 0.0,
                accuracy = (snapshot["accuracy"] as? Number)?.toFloat() ?: 0f,
                timestamp = (snapshot["timestamp"] as? Number)?.toLong() ?: 0L,
                isLiveSharing = (snapshot["isLiveSharing"] as? Boolean) ?: false,
                isCameraSharing = (snapshot["isCameraSharing"] as? Boolean) ?: false,
                liveSharingStartedAt = (snapshot["liveSharingStartedAt"] as? Number)?.toLong() ?: 0L,
                liveSharingDurationMs = (snapshot["liveSharingDurationMs"] as? Number)?.toLong() ?: 0L,
                heading = (snapshot["heading"] as? Number)?.toFloat() ?: -1f,
                speed = (snapshot["speed"] as? Number)?.toFloat() ?: -1f,
                altitude = (snapshot["altitude"] as? Number)?.toDouble() ?: 0.0
            )
        }
    }
}
