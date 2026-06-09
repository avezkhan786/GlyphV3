package com.glyph.glyph_v3.ui.chat.map.routing

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches driving/walking routes from the OSRM public API (free, no API key).
 *
 * Returns decoded polyline points along with distance and duration.
 */
object RouteService {

    private const val TAG = "RouteService"
    private const val OSRM_BASE = "https://router.project-osrm.org/route/v1"
    private const val CONNECTION_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000

    /**
     * Fetch a route between two points.
     *
     * @param origin   Starting point (my location)
     * @param destination End point (other user's location)
     * @param profile  "driving" or "walking"
     * @return [RouteResult] with polyline points, distance, and duration, or null on failure.
     */
    suspend fun fetchRoute(
        origin: LatLng,
        destination: LatLng,
        profile: String = "driving"
    ): RouteResult? = withContext(Dispatchers.IO) {
        try {
            // OSRM uses lon,lat format (not lat,lon)
            val url = "$OSRM_BASE/$profile/" +
                    "${origin.longitude},${origin.latitude};" +
                    "${destination.longitude},${destination.latitude}" +
                    "?overview=full&geometries=polyline&steps=false"


            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECTION_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "OSRM returned HTTP $responseCode")
                connection.disconnect()
                return@withContext null
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }
            connection.disconnect()

            val json = JSONObject(response)
            val code = json.optString("code", "")
            if (code != "Ok") {
                Log.e(TAG, "OSRM error code: $code")
                return@withContext null
            }

            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                Log.w(TAG, "No routes returned")
                return@withContext null
            }

            val route = routes.getJSONObject(0)
            val geometry = route.optString("geometry", "")
            val distanceMeters = route.optDouble("distance", 0.0)
            val durationSeconds = route.optDouble("duration", 0.0)

            val points = decodePolyline(geometry)
            if (points.isEmpty()) {
                Log.w(TAG, "Decoded polyline is empty")
                return@withContext null
            }


            RouteResult(
                points = points,
                distanceMeters = distanceMeters,
                durationSeconds = durationSeconds,
                profile = profile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch route: ${e.message}", e)
            null
        }
    }

    /**
     * Decode a Google-encoded polyline string into a list of LatLng points.
     * See: https://developers.google.com/maps/documentation/utilities/polylinealgorithm
     */
    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = mutableListOf<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            // Decode latitude
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            // Decode longitude
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            poly.add(LatLng(lat / 1E5, lng / 1E5))
        }

        return poly
    }
}

/**
 * Represents a fetched route result.
 */
data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val profile: String   // "driving" or "walking"
)
