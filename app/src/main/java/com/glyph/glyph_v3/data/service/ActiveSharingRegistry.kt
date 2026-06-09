package com.glyph.glyph_v3.data.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Singleton registry that tracks which users we are currently sharing location with.
 *
 * Persisted via SharedPreferences so the foreground service can resume
 * sharing after process death.
 *
 * Two sharing modes per target:
 * - **PASSIVE**: Low-frequency updates (~30 s) — map background is enabled.
 * - **LIVE**: High-frequency updates (~5 s) — user started live location sharing.
 *
 * A target may be in both passive AND live simultaneously; the higher-frequency
 * mode wins for the service's update interval.
 *
 * Thread-safety: all reads/writes go through SharedPreferences which is
 * internally thread-safe for reads; writes use `apply()`.
 */
object ActiveSharingRegistry {

    private const val TAG = "ActiveSharingRegistry"
    private const val PREFS_NAME = "glyph_active_sharing_registry"
    private const val KEY_PASSIVE_TARGETS = "passive_targets"
    private const val KEY_LIVE_TARGETS = "live_targets"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Must be called before any other method (e.g., in Application/Service/Activity). */
    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException(
            "ActiveSharingRegistry.init(context) must be called before use"
        )

    // ── Passive targets ────────────────────────────────────

    /** Register [targetUserId] for passive (map-background) location sharing. */
    fun addPassiveTarget(targetUserId: String) {
        val p = requirePrefs()
        val targets = p.getStringSet(KEY_PASSIVE_TARGETS, emptySet())!!.toMutableSet()
        targets.add(targetUserId)
        p.edit().putStringSet(KEY_PASSIVE_TARGETS, targets).apply()
    }

    /** Un-register [targetUserId] from passive sharing. */
    fun removePassiveTarget(targetUserId: String) {
        val p = requirePrefs()
        val targets = p.getStringSet(KEY_PASSIVE_TARGETS, emptySet())!!.toMutableSet()
        targets.remove(targetUserId)
        p.edit().putStringSet(KEY_PASSIVE_TARGETS, targets).apply()
    }

    fun getPassiveTargets(): Set<String> =
        requirePrefs().getStringSet(KEY_PASSIVE_TARGETS, emptySet()) ?: emptySet()

    // ── Live targets ───────────────────────────────────────

    /** Register [targetUserId] for live (high-frequency) location sharing. */
    fun addLiveTarget(targetUserId: String, durationMs: Long, displayName: String = "") {
        val p = requirePrefs()
        val targets = p.getStringSet(KEY_LIVE_TARGETS, emptySet())!!.toMutableSet()
        targets.add(targetUserId)
        p.edit()
            .putStringSet(KEY_LIVE_TARGETS, targets)
            .putLong("live_duration_$targetUserId", durationMs)
            .putLong("live_start_$targetUserId", System.currentTimeMillis())
            .putString("live_display_name_$targetUserId", displayName)
            .apply()
    }

    /** Un-register [targetUserId] from live sharing. */
    fun removeLiveTarget(targetUserId: String) {
        val p = requirePrefs()
        val targets = p.getStringSet(KEY_LIVE_TARGETS, emptySet())!!.toMutableSet()
        targets.remove(targetUserId)
        p.edit()
            .putStringSet(KEY_LIVE_TARGETS, targets)
            .remove("live_duration_$targetUserId")
            .remove("live_start_$targetUserId")
            .remove("live_display_name_$targetUserId")
            .apply()
    }

    fun getLiveTargets(): Set<String> =
        requirePrefs().getStringSet(KEY_LIVE_TARGETS, emptySet()) ?: emptySet()

    fun getLiveDuration(targetUserId: String): Long =
        requirePrefs().getLong("live_duration_$targetUserId", 0L)

    fun getLiveStartTime(targetUserId: String): Long =
        requirePrefs().getLong("live_start_$targetUserId", 0L)

    fun getLiveDisplayName(targetUserId: String): String =
        requirePrefs().getString("live_display_name_$targetUserId", "") ?: ""

    // ── Combined queries ───────────────────────────────────

    /** All target user IDs receiving our location (passive ∪ live). */
    fun getAllTargets(): Set<String> = getPassiveTargets() + getLiveTargets()

    /** Whether any target is in live mode. */
    fun hasAnyLiveTargets(): Boolean = getLiveTargets().isNotEmpty()

    /** Whether we have any sharing targets at all. */
    fun hasAnyTargets(): Boolean = getAllTargets().isNotEmpty()

    /** Remove a target from both passive and live registries. */
    fun removeTarget(targetUserId: String) {
        removePassiveTarget(targetUserId)
        removeLiveTarget(targetUserId)
    }

    /**
     * Check all live targets for expiration and remove those that have exceeded
     * their requested duration.
     *
     * @return The set of target user IDs that were expired and removed.
     *         Callers should call [LocationRepository.stopLiveSharing] for each.
     */
    fun removeExpiredLiveTargets(): Set<String> {
        val now = System.currentTimeMillis()
        val expired = mutableSetOf<String>()
        for (target in getLiveTargets()) {
            val start = getLiveStartTime(target)
            val duration = getLiveDuration(target)
            if (duration > 0 && now - start > duration) {
                expired.add(target)
            }
        }
        for (target in expired) {
            removeLiveTarget(target)
        }
        return expired
    }

    /** Clear everything (e.g., on logout). */
    fun clearAll() {
        requirePrefs().edit().clear().apply()
    }
}
