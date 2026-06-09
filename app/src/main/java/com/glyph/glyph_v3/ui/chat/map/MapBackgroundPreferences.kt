package com.glyph.glyph_v3.ui.chat.map

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight SharedPreferences wrapper for per-chat map background settings.
 *
 * Stores:
 * - Whether the map background is enabled (per chat).
 * - Whether live location sharing is active.
 * - The chosen live-sharing duration.
 */
class MapBackgroundPreferences private constructor(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "glyph_map_bg_prefs"

        @Volatile
        private var instance: MapBackgroundPreferences? = null

        fun getInstance(context: Context): MapBackgroundPreferences {
            return instance ?: synchronized(this) {
                instance ?: MapBackgroundPreferences(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
        }
    }

    // ── Map background toggle ──────────────────────────────

    fun isMapBackgroundEnabled(chatId: String): Boolean =
        prefs.getBoolean("map_bg_enabled_$chatId", false)

    fun setMapBackgroundEnabled(chatId: String, enabled: Boolean) {
        prefs.edit().putBoolean("map_bg_enabled_$chatId", enabled).apply()
    }

    // ── Live sharing ───────────────────────────────────────

    fun isLiveSharingEnabled(chatId: String): Boolean =
        prefs.getBoolean("live_sharing_$chatId", false)

    fun setLiveSharingEnabled(chatId: String, enabled: Boolean) {
        prefs.edit().putBoolean("live_sharing_$chatId", enabled).apply()
    }

    /** Duration in ms. Default 1 hour. */
    fun getLiveSharingDuration(chatId: String): Long =
        prefs.getLong("live_sharing_duration_$chatId", 3_600_000L)

    fun setLiveSharingDuration(chatId: String, durationMs: Long) {
        prefs.edit().putLong("live_sharing_duration_$chatId", durationMs).apply()
    }

    /** Timestamp when live sharing started (ms since epoch). Defaults to 0L when not set. */
    fun getLiveSharingStartedAt(chatId: String): Long =
        prefs.getLong("live_sharing_started_at_$chatId", 0L)

    fun setLiveSharingStartedAt(chatId: String, startedAtMs: Long) {
        prefs.edit().putLong("live_sharing_started_at_$chatId", startedAtMs).apply()
    }

    // ── Interactive mode ───────────────────────────────────

    fun isInteractiveMode(chatId: String): Boolean =
        prefs.getBoolean("interactive_mode_$chatId", false)

    fun setInteractiveMode(chatId: String, enabled: Boolean) {
        prefs.edit().putBoolean("interactive_mode_$chatId", enabled).apply()
    }

    // ── Navigation persistence ─────────────────────────────

    fun isNavigationEnabled(chatId: String): Boolean =
        prefs.getBoolean("nav_enabled_$chatId", false)

    fun setNavigationEnabled(chatId: String, enabled: Boolean) {
        prefs.edit().putBoolean("nav_enabled_$chatId", enabled).apply()
    }

    fun getNavigationTravelMode(chatId: String): String =
        prefs.getString("nav_travel_mode_$chatId", "driving") ?: "driving"

    fun setNavigationTravelMode(chatId: String, mode: String) {
        prefs.edit().putString("nav_travel_mode_$chatId", mode).apply()
    }

    fun clearNavigationState(chatId: String) {
        prefs.edit()
            .remove("nav_enabled_$chatId")
            .remove("nav_travel_mode_$chatId")
            .apply()
    }
}
