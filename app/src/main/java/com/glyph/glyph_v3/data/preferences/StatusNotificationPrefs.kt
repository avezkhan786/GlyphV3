package com.glyph.glyph_v3.data.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Stores per-user opt-in preference for status update notifications.
 * When enabled for a target user, the current user receives a notification
 * whenever that contact posts a new status.
 *
 * Local copy: SharedPreferences (fast sync reads from foreground listener).
 * Server copy: Firestore at /statusNotifSubscriptions/{publisherUserId}/{subscriberUserId}
 *   — read by the `onStatusCreated` Cloud Function to deliver FCM when the app is killed.
 */
object StatusNotificationPrefs {

    private const val TAG = "StatusNotifPrefs"
    private const val PREFS_NAME = "status_notification_prefs"
    private const val KEY_NOTIF_ENABLED_PREFIX = "notif_enabled_"
    private const val KEY_KNOWN_IDS_PREFIX = "known_ids_"

    // Firestore path: /statusNotifSubscriptions/{publisherUserId}/{subscriberUserId}
    private const val FS_COLLECTION = "statusNotifSubscriptions"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Notification opt-in ────────────────────────────────────────

    /**
     * Returns true if the current user has opted in to status notifications
     * for [targetUserId]. Default is false (no notifications).
     */
    fun isEnabled(context: Context, targetUserId: String): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_ENABLED_PREFIX + targetUserId, false)

    /**
     * Sets the notification opt-in preference for [targetUserId] and mirrors
     * the change to Firestore so the Cloud Function can deliver FCM when the app is killed.
     */
    suspend fun setEnabled(context: Context, targetUserId: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            prefs(context).edit()
                .putBoolean(KEY_NOTIF_ENABLED_PREFIX + targetUserId, enabled)
                .apply()
        }
        try {
            syncRemoteSubscription(targetUserId, enabled)
        } catch (e: Exception) {
            Log.w(TAG, "Firestore sync failed for $targetUserId enabled=$enabled", e)
        }
    }

    /**
     * Repairs any locally enabled subscriptions that may have failed to sync remotely.
     * This keeps background/killed-app notifications working after transient write failures.
     */
    suspend fun syncEnabledSubscriptions(context: Context) {
        val enabledTargetUserIds = withContext(Dispatchers.IO) {
            prefs(context).all.entries
                .asSequence()
                .filter { (key, value) ->
                    key.startsWith(KEY_NOTIF_ENABLED_PREFIX) && value == true
                }
                .map { (key, _) -> key.removePrefix(KEY_NOTIF_ENABLED_PREFIX) }
                .filter { it.isNotBlank() }
                .toList()
        }

        enabledTargetUserIds.forEach { targetUserId ->
            try {
                syncRemoteSubscription(targetUserId, enabled = true)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to repair remote subscription for $targetUserId", e)
            }
        }
    }

    private suspend fun syncRemoteSubscription(targetUserId: String, enabled: Boolean) {
        val subscriberId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val subDocRef = FirebaseFirestore.getInstance()
            .collection(FS_COLLECTION)
            .document(targetUserId)
            .collection("subscribers")
            .document(subscriberId)

        withContext(Dispatchers.IO) {
            if (enabled) {
                subDocRef.set(mapOf("enabled" to true), SetOptions.merge()).await()
            } else {
                subDocRef.delete().await()
            }
        }
    }

    // ── Known status IDs (dedup guard) ─────────────────────────────

    /**
     * Returns the set of status IDs that have already been delivered as
     * notifications for [targetUserId], so we don't re-notify on restart.
     */
    fun getKnownStatusIds(context: Context, targetUserId: String): Set<String> =
        prefs(context).getStringSet(KEY_KNOWN_IDS_PREFIX + targetUserId, emptySet())
            ?.toSet() ?: emptySet()

    /**
     * Replaces the known-ID set for [targetUserId] with [ids]. Call after
     * triggering notifications to prevent duplicates.
     *
     * We store at most 200 IDs per user to avoid unbounded growth; the oldest
     * ones are automatically dropped when the set exceeds that limit.
     */
    suspend fun markStatusIdsKnown(context: Context, targetUserId: String, ids: Set<String>) {
        withContext(Dispatchers.IO) {
            val trimmed = if (ids.size > 200) ids.toList().takeLast(200).toSet() else ids
            prefs(context).edit()
                .putStringSet(KEY_KNOWN_IDS_PREFIX + targetUserId, trimmed)
                .apply()
        }
    }

    /** Synchronous variant of [markStatusIdsKnown] — safe to call from a background thread. */
    fun markStatusIdsKnownSync(context: Context, targetUserId: String, ids: Set<String>) {
        val existing = getKnownStatusIds(context, targetUserId)
        val merged = (existing + ids).let { combined ->
            if (combined.size > 200) combined.toList().takeLast(200).toSet() else combined
        }
        prefs(context).edit()
            .putStringSet(KEY_KNOWN_IDS_PREFIX + targetUserId, merged)
            .apply()
    }
}
