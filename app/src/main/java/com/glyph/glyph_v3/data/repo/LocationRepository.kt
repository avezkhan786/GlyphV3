package com.glyph.glyph_v3.data.repo

import android.util.Log
import com.glyph.glyph_v3.data.models.UserLocation
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Repository for **per-user isolated** location sharing via Firebase RTDB.
 *
 * **Structure (per sender–receiver pair):**
 * ```
 * locationSharing/
 *   {senderUserId}/
 *     {receiverUserId}/
 *       latitude, longitude, accuracy, timestamp,
 *       isLiveSharing, liveSharingStartedAt, liveSharingDurationMs,
 *       heading, speed, altitude
 * ```
 *
 * Privacy guarantees:
 * - Only the sender and receiver can read/write their shared node.
 * - Sharing with User B is completely isolated from sharing with User C.
 * - Stopping live sharing preserves the last location as "last seen" for that
 *   specific receiver only.
 */
object LocationRepository {

    private const val TAG = "LocationRepository"
    private const val SHARING_PATH = "locationSharing"

    private val database = FirebaseDatabase.getInstance()
    private val sharingRef = database.getReference(SHARING_PATH)

    // ── Write: per-target location updates ─────────────────

    /**
     * Push the current user's location for a **single** target to RTDB.
     * Writes to `locationSharing/{myUid}/{targetUserId}/`.
     */
    fun updateMyLocationForTarget(
        targetUserId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        heading: Float = -1f,
        speed: Float = -1f,
        altitude: Double = 0.0
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.w(TAG, "updateMyLocationForTarget: no authenticated user, skipping")
            return
        }
        if (latitude == 0.0 && longitude == 0.0) {
            Log.w(TAG, "updateMyLocationForTarget: REJECTING (0,0) for target=$targetUserId")
            return
        }
        val ref = sharingRef.child(uid).child(targetUserId)
        val updates = mapOf<String, Any?>(
            "latitude" to latitude,
            "longitude" to longitude,
            "accuracy" to accuracy,
            "timestamp" to ServerValue.TIMESTAMP,
            "heading" to heading,
            "speed" to speed,
            "altitude" to altitude
        )
        ref.updateChildren(updates)
            .addOnSuccessListener { }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to update location for $uid → $targetUserId", e) }
    }

    /**
     * Push the current user's location to **all** active sharing targets in a
     * single atomic multi-path update.
     *
     * Called from [LocationUpdateService] on every GPS fix.
     */
    fun updateMyLocationForAllTargets(
        targets: Set<String>,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        heading: Float = -1f,
        speed: Float = -1f,
        altitude: Double = 0.0
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.w(TAG, "updateMyLocationForAllTargets: no authenticated user, skipping")
            return
        }
        if (latitude == 0.0 && longitude == 0.0) {
            Log.w(TAG, "updateMyLocationForAllTargets: REJECTING (0,0)")
            return
        }
        if (targets.isEmpty()) {
            Log.w(TAG, "updateMyLocationForAllTargets: no targets, skipping")
            return
        }
        val updates = mutableMapOf<String, Any?>()
        for (target in targets) {
            val prefix = "$uid/$target"
            updates["$prefix/latitude"] = latitude
            updates["$prefix/longitude"] = longitude
            updates["$prefix/accuracy"] = accuracy
            updates["$prefix/timestamp"] = ServerValue.TIMESTAMP
            updates["$prefix/heading"] = heading
            updates["$prefix/speed"] = speed
            updates["$prefix/altitude"] = altitude
        }
        sharingRef.updateChildren(updates)
            .addOnSuccessListener { }
            .addOnFailureListener { e -> Log.e(TAG, "Batch location update failed", e) }
    }

    // ── Write: live sharing lifecycle (per-target) ─────────

    /**
     * Enable live sharing for a specific target user.
     * Writes to `locationSharing/{myUid}/{targetUserId}/`.
     */
    fun startLiveSharing(targetUserId: String, durationMs: Long) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = sharingRef.child(uid).child(targetUserId)
        val updates = mapOf<String, Any?>(
            "isLiveSharing" to true,
            "liveSharingStartedAt" to ServerValue.TIMESTAMP,
            "liveSharingDurationMs" to durationMs
        )
        ref.updateChildren(updates)
            .addOnFailureListener { e -> Log.e(TAG, "Failed to start live sharing for $targetUserId", e) }
    }

    /**
     * Marks whether the current user's live-location node is also broadcasting camera video
     * for the given target.
     */
    fun setCameraSharingEnabled(targetUserId: String, enabled: Boolean) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = sharingRef.child(uid).child(targetUserId).child("isCameraSharing")
        if (enabled) {
            ref.setValue(true)
                .addOnFailureListener { e -> Log.e(TAG, "Failed to enable camera sharing for $targetUserId", e) }
        } else {
            ref.removeValue()
                .addOnFailureListener { e -> Log.e(TAG, "Failed to clear camera sharing for $targetUserId", e) }
        }
    }

    /**
     * Stop live sharing for a specific target user.
     * The last location data is preserved as "last seen" for that receiver.
     */
    fun stopLiveSharing(targetUserId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val updates = mapOf<String, Any?>(
            "isLiveSharing" to false,
            "liveSharingStartedAt" to null,
            "liveSharingDurationMs" to null,
            "isCameraSharing" to null
        )
        sharingRef.child(uid).child(targetUserId).updateChildren(updates)
            .addOnFailureListener { e -> Log.e(TAG, "Failed to freeze locationSharing node for $targetUserId", e) }
    }

    /**
     * Stop live sharing for multiple targets at once (atomic multi-path update).
     * Used by the service's onDestroy for cleanup.
     */
    fun stopAllLiveSharing(targets: Set<String>) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (targets.isEmpty()) return
        val updates = mutableMapOf<String, Any?>()
        for (target in targets) {
            val prefix = "$uid/$target"
            updates["$prefix/isLiveSharing"] = false
            updates["$prefix/liveSharingStartedAt"] = null
            updates["$prefix/liveSharingDurationMs"] = null
            updates["$prefix/isCameraSharing"] = null
        }
        sharingRef.updateChildren(updates)
            .addOnFailureListener { e -> Log.e(TAG, "Failed to freeze all locationSharing nodes", e) }
    }

    // ── Read: per-target observation ───────────────────────

    /**
     * Observe the location that [senderUserId] is sharing **with the current user**.
     * Reads from `locationSharing/{senderUserId}/{myUid}/`.
     *
     * Use this when you want to see another user's location on your map.
     */
    fun observeLocationSharedWithMe(senderUserId: String): Flow<UserLocation?> = callbackFlow {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        if (myUid == null) {
            Log.w(TAG, "observeLocationSharedWithMe: no authenticated user")
            trySend(null)
            close()
            return@callbackFlow
        }
        val path = "$senderUserId/$myUid"
        val ref = sharingRef.child(senderUserId).child(myUid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?> ?: run {
                    Log.w(TAG, "observeLocationSharedWithMe: value is not a Map at $path: ${snapshot.value}")
                    trySend(null)
                    return
                }
                val loc = UserLocation.fromSnapshot(map)
                trySend(loc)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeLocationSharedWithMe cancelled for $path: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose {
            ref.removeEventListener(listener)
        }
    }

    /**
     * Observe the current user's own location **as shared with [targetUserId]**.
     * Reads from `locationSharing/{myUid}/{targetUserId}/`.
     *
     * Use this for displaying "my" marker on the map in a specific chat
     * and for monitoring accuracy / live-sharing state.
     */
    fun observeMyLocationForTarget(targetUserId: String): Flow<UserLocation?> = callbackFlow {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        if (myUid == null) {
            Log.w(TAG, "observeMyLocationForTarget: no authenticated user")
            trySend(null)
            close()
            return@callbackFlow
        }
        val path = "$myUid/$targetUserId"
        val ref = sharingRef.child(myUid).child(targetUserId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?> ?: run {
                    Log.w(TAG, "observeMyLocationForTarget: value is not a Map at $path: ${snapshot.value}")
                    trySend(null)
                    return
                }
                val loc = UserLocation.fromSnapshot(map)
                trySend(loc)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeMyLocationForTarget cancelled for $path: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose {
            ref.removeEventListener(listener)
        }
    }

    // ── Cleanup ────────────────────────────────────────────

    /**
     * Delete stale (0,0) location data for a specific target.
     * Called once when map background is enabled to clean up corrupt entries.
     */
    fun clearStaleLocationData(targetUserId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        sharingRef.child(uid).child(targetUserId).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) return@addOnSuccessListener
            @Suppress("UNCHECKED_CAST")
            val map = snapshot.value as? Map<String, Any?> ?: return@addOnSuccessListener
            val lat = (map["latitude"] as? Number)?.toDouble() ?: 0.0
            val lng = (map["longitude"] as? Number)?.toDouble() ?: 0.0
            if (lat == 0.0 && lng == 0.0) {
                Log.w(TAG, "clearStaleLocationData: Found (0,0) for $uid → $targetUserId — deleting")
                sharingRef.child(uid).child(targetUserId).removeValue()
            }
        }
    }

    /**
     * One-shot fetch of the location that [senderUserId] shared with the current user.
     */
    fun getLastKnownLocationSharedWithMe(senderUserId: String, callback: (UserLocation?) -> Unit) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            callback(null); return
        }
        sharingRef.child(senderUserId).child(myUid).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) { callback(null); return@addOnSuccessListener }
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?>
                callback(map?.let { UserLocation.fromSnapshot(it) })
            }
            .addOnFailureListener { callback(null) }
    }
}
