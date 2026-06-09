package com.glyph.glyph_v3.data.cache

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists which ICE candidate type succeeded in recent walkie-talkie sessions
 * between specific user pairs so that future calls can skip wasted trial time.
 *
 * Why this matters:
 *   When both users are on different carrier networks, WebRTC's default ICE
 *   behaviour (IceTransportsType.ALL) spends 30-45 seconds trying host and
 *   srflx candidate pairs that will never succeed before falling back to TURN
 *   relay. By remembering that relay was required last time, the next call can
 *   start with IceTransportsType.RELAY immediately, cutting setup from ~60 s
 *   to typically 2-5 s.
 *
 * Storage format:
 *   SharedPreferences key: "ict_<sortedUid1>_<sortedUid2>"
 *   Value: comma-separated list of up to [HISTORY_SIZE] entries, newest first.
 *   Each entry is one of: "relay", "srflx", "host", "unknown"
 */
object IceCandidateHistoryCache {

    private const val TAG = "IceCandidateHistory"
    private const val PREFS_NAME = "glyph_ice_candidate_history"
    private const val KEY_PREFIX = "ict_"
    private const val HISTORY_SIZE = 4  // last N sessions kept per pair

    // If this fraction of recent sessions needed relay → use relay-first next time
    private const val RELAY_PREFERENCE_THRESHOLD = 0.5f

    enum class CandidateType(val value: String) {
        HOST("host"),
        SRFLX("srflx"),
        RELAY("relay"),
        UNKNOWN("unknown");

        companion object {
            fun from(value: String): CandidateType =
                entries.firstOrNull { it.value == value } ?: UNKNOWN
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun pairKey(uid1: String, uid2: String): String {
        // Sort so A→B and B→A share the same history bucket.
        val sorted = listOf(uid1, uid2).sorted()
        return KEY_PREFIX + sorted[0] + "_" + sorted[1]
    }

    /**
     * Record the outcome of a completed session.
     * Call this when IceConnectionState reaches CONNECTED/COMPLETED.
     *
     * @param usedRelay true if the winning candidate pair involved a relay candidate
     *                  on the local side (sawLocalRelayCandidate at connect time).
     */
    fun recordOutcome(
        context: Context,
        localUserId: String,
        remoteUserId: String,
        usedRelay: Boolean
    ) {
        if (localUserId.isBlank() || remoteUserId.isBlank()) return
        val type = if (usedRelay) CandidateType.RELAY else CandidateType.SRFLX
        recordType(context, localUserId, remoteUserId, type)
    }

    private fun recordType(
        context: Context,
        localUserId: String,
        remoteUserId: String,
        type: CandidateType
    ) {
        val key = pairKey(localUserId, remoteUserId)
        val prefs = prefs(context)
        val existing = prefs.getString(key, "")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val updated = (listOf(type.value) + existing).take(HISTORY_SIZE)
        prefs.edit().putString(key, updated.joinToString(",")).apply()
        Log.d(TAG, "Recorded ICE outcome=$type for pair key=${key.takeLast(12)} history=${updated.take(HISTORY_SIZE)}")
    }

    /**
     * Returns true when recent history strongly suggests relay will be needed,
     * meaning the next call should skip host/srflx and go straight to TURN.
     */
    fun shouldPreferRelay(
        context: Context,
        localUserId: String,
        remoteUserId: String
    ): Boolean {
        if (localUserId.isBlank() || remoteUserId.isBlank()) return false
        val key = pairKey(localUserId, remoteUserId)
        val raw = prefs(context).getString(key, "") ?: return false
        val entries = raw.split(",").filter { it.isNotBlank() }
        if (entries.isEmpty()) return false

        val relayCount = entries.count { CandidateType.from(it) == CandidateType.RELAY }
        val ratio = relayCount.toFloat() / entries.size.toFloat()
        val prefer = ratio >= RELAY_PREFERENCE_THRESHOLD
        Log.d(
            TAG,
            "shouldPreferRelay pair=${key.takeLast(12)}: relay=$relayCount/${entries.size} ratio=${"%.2f".format(ratio)} → $prefer"
        )
        return prefer
    }

    /** Clear history for a specific pair (e.g. if network changes are known). */
    fun clearHistory(context: Context, localUserId: String, remoteUserId: String) {
        val key = pairKey(localUserId, remoteUserId)
        prefs(context).edit().remove(key).apply()
    }
}
