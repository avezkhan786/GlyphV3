package com.glyph.glyph_v3.data.repo

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Repository for managing block/unblock operations.
 *
 * ## Firestore Schema
 *
 * ```
 * /users/{userId}/blockedUsers/{blockedUserId}   → { blockedAt: Timestamp }
 * /users/{userId}/blockedBy/{blockerUserId}       → { blockedAt: Timestamp }
 * ```
 *
 * Two sub-collections per user:
 * - **blockedUsers**: users I have blocked  (I am the blocker)
 * - **blockedBy**:    users who blocked me  (I am the target)
 *
 * Both are written atomically in a batched write so the relationship is always consistent.
 *
 * ## Security Model
 *
 * - Firebase Security Rules enforce that a blocked user cannot write to `pending_messages`
 *   or read presence of the blocker.
 * - The Cloud Function checks `blockedBy` before sending FCM.
 * - Client-side checks are convenience only; the backend is always authoritative.
 */
object BlockRepository {
    private const val TAG = "BlockRepository"
    private const val RTDB_BLOCKS_PATH = "blocks"
    private const val PREFS_NAME = "block_repo_cache"
    private const val KEY_BLOCKED_BY_ME = "blocked_by_me_ids"
    private const val KEY_BLOCKED_ME = "blocked_me_ids"

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val realtimeDatabase = FirebaseDatabase.getInstance()
    private val realtimeBlocksRef = realtimeDatabase.getReference(RTDB_BLOCKS_PATH)

    // In-memory cache of users I have blocked (for fast UI checks)
    private val _myBlockedUsers = MutableStateFlow<Set<String>>(emptySet())
    val myBlockedUsers: StateFlow<Set<String>> = _myBlockedUsers.asStateFlow()

    // In-memory cache of users I have blocked mapped to their blocked timestamp
    private val _myBlockedUsersMap = MutableStateFlow<Map<String, Long>>(emptyMap())

    // In-memory cache of users who have blocked me (for fast UI checks)
    private val _blockedByUsers = MutableStateFlow<Set<String>>(emptySet())
    val blockedByUsers: StateFlow<Set<String>> = _blockedByUsers.asStateFlow()

    private var blockedUsersListener: ListenerRegistration? = null
    private var blockedByListener: ListenerRegistration? = null

    // ── SharedPreferences disk cache for synchronous cold-start reads ─────
    private var prefs: android.content.SharedPreferences? = null

    fun initDiskCache(context: android.content.Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val blockedByMe = prefs!!.getStringSet(KEY_BLOCKED_BY_ME, emptySet()) ?: emptySet()
        val blockedMe = prefs!!.getStringSet(KEY_BLOCKED_ME, emptySet()) ?: emptySet()
        if (blockedByMe.isNotEmpty() || blockedMe.isNotEmpty()) {
            _myBlockedUsers.value = blockedByMe
            _blockedByUsers.value = blockedMe
        }
    }

    private fun persistBlockedByMe() {
        prefs?.edit()?.putStringSet(KEY_BLOCKED_BY_ME, _myBlockedUsers.value)?.apply()
    }

    private fun persistBlockedMe() {
        prefs?.edit()?.putStringSet(KEY_BLOCKED_ME, _blockedByUsers.value)?.apply()
    }

    private val currentUserId: String?
        get() = auth.currentUser?.uid

    // ──────────────────────────────────────────────
    // Initialization — call once after auth
    // ──────────────────────────────────────────────

    /**
     * Start real-time listeners for both blockedUsers and blockedBy sub-collections.
     * Must be called after login / on app start.
     */
    fun startListening() {
        val uid = currentUserId ?: return

        // Listen to users I have blocked
        blockedUsersListener?.remove()
        blockedUsersListener = firestore.collection("users").document(uid)
            .collection("blockedUsers")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to blockedUsers", error)
                    return@addSnapshotListener
                }
                val documents = snapshot?.documents ?: emptyList()
                val ids = documents.map { it.id }.toSet()
                val map = documents.associate { 
                    it.id to (it.getTimestamp("blockedAt")?.toDate()?.time ?: 0L) 
                }
                _myBlockedUsersMap.value = map
                _myBlockedUsers.value = ids
                persistBlockedByMe()
            }

        // Listen to users who blocked me
        blockedByListener?.remove()
        blockedByListener = firestore.collection("users").document(uid)
            .collection("blockedBy")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to blockedBy", error)
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents?.map { it.id }?.toSet() ?: emptySet()
                _blockedByUsers.value = ids
                persistBlockedMe()
            }
    }

    fun stopListening() {
        blockedUsersListener?.remove()
        blockedByListener?.remove()
        blockedUsersListener = null
        blockedByListener = null
        _myBlockedUsers.value = emptySet()
        _blockedByUsers.value = emptySet()
    }

    // ──────────────────────────────────────────────
    // Block / Unblock operations
    // ──────────────────────────────────────────────

    /**
     * Block another user. Writes to both sub-collections atomically:
     * - /users/{myId}/blockedUsers/{otherUserId}
     * - /users/{otherUserId}/blockedBy/{myId}
     *
     * @throws Exception on network failure / rule denial.
     */
    suspend fun blockUser(otherUserId: String) {
        val myId = currentUserId ?: throw IllegalStateException("Not authenticated")
        if (myId == otherUserId) throw IllegalArgumentException("Cannot block yourself")

        val batch = firestore.batch()
        val timestamp = FieldValue.serverTimestamp()

        // My blockedUsers sub-collection
        val myBlockRef = firestore.collection("users").document(myId)
            .collection("blockedUsers").document(otherUserId)
        batch.set(myBlockRef, mapOf("blockedAt" to timestamp))

        // Their blockedBy sub-collection
        val theirBlockedByRef = firestore.collection("users").document(otherUserId)
            .collection("blockedBy").document(myId)
        batch.set(theirBlockedByRef, mapOf("blockedAt" to timestamp))

        batch.commit().await()
        mirrorRealtimeBlockState(myId = myId, otherUserId = otherUserId, blocked = true)

        // Optimistic local update (listener will confirm)
        _myBlockedUsers.value = _myBlockedUsers.value + otherUserId
        persistBlockedByMe()
    }

    /**
     * Unblock another user. Deletes from both sub-collections atomically.
     */
    suspend fun unblockUser(otherUserId: String) {
        val myId = currentUserId ?: throw IllegalStateException("Not authenticated")


        val batch = firestore.batch()

        val myBlockRef = firestore.collection("users").document(myId)
            .collection("blockedUsers").document(otherUserId)
        batch.delete(myBlockRef)

        val theirBlockedByRef = firestore.collection("users").document(otherUserId)
            .collection("blockedBy").document(myId)
        batch.delete(theirBlockedByRef)

        batch.commit().await()
        mirrorRealtimeBlockState(myId = myId, otherUserId = otherUserId, blocked = false)

        // Optimistic local update
        _myBlockedUsers.value = _myBlockedUsers.value - otherUserId
        persistBlockedByMe()
    }

    // ──────────────────────────────────────────────
    // Query helpers (use cached values for speed)
    // ──────────────────────────────────────────────

    /** Have I blocked this user? */
    fun isBlockedByMe(otherUserId: String): Boolean =
        _myBlockedUsers.value.contains(otherUserId)

    /** When did I block this user? */
    fun getBlockedAt(otherUserId: String): Long? =
        _myBlockedUsersMap.value[otherUserId]

    /** Has this user blocked me? */
    fun amIBlockedBy(otherUserId: String): Boolean =
        _blockedByUsers.value.contains(otherUserId)

    /**
     * Is messaging blocked in either direction?
     * Returns a [BlockStatus] describing the relationship.
     */
    fun getBlockStatus(otherUserId: String): BlockStatus {
        val iBlockedThem = isBlockedByMe(otherUserId)
        val theyBlockedMe = amIBlockedBy(otherUserId)
        return when {
            iBlockedThem && theyBlockedMe -> BlockStatus.MUTUAL_BLOCK
            iBlockedThem -> BlockStatus.I_BLOCKED_THEM
            theyBlockedMe -> BlockStatus.THEY_BLOCKED_ME
            else -> BlockStatus.NOT_BLOCKED
        }
    }

    /** True when any real-time interaction should be denied in either direction. */
    fun isInteractionBlocked(otherUserId: String): Boolean = getBlockStatus(otherUserId).isBlocked

    /**
     * Fetches the latest block status from Firestore and refreshes the local caches.
     * Use this at critical real-time entry points where stale local state is unacceptable.
     */
    suspend fun fetchBlockStatus(otherUserId: String, source: Source = Source.DEFAULT): BlockStatus {
        val myId = currentUserId ?: return getBlockStatus(otherUserId)

        return try {
            val blockedDoc = firestore.collection("users").document(myId)
                .collection("blockedUsers").document(otherUserId)
                .get(source)
                .await()
            val blockedByDoc = firestore.collection("users").document(myId)
                .collection("blockedBy").document(otherUserId)
                .get(source)
                .await()

            val updatedBlockedUsers = _myBlockedUsers.value.toMutableSet()
            val updatedBlockedUsersMap = _myBlockedUsersMap.value.toMutableMap()
            if (blockedDoc.exists()) {
                updatedBlockedUsers += otherUserId
                updatedBlockedUsersMap[otherUserId] = blockedDoc.getTimestamp("blockedAt")
                    ?.toDate()
                    ?.time
                    ?: updatedBlockedUsersMap[otherUserId]
                    ?: 0L
            } else {
                updatedBlockedUsers -= otherUserId
                updatedBlockedUsersMap -= otherUserId
            }

            val updatedBlockedByUsers = _blockedByUsers.value.toMutableSet()
            if (blockedByDoc.exists()) {
                updatedBlockedByUsers += otherUserId
            } else {
                updatedBlockedByUsers -= otherUserId
            }

            _myBlockedUsers.value = updatedBlockedUsers
            _myBlockedUsersMap.value = updatedBlockedUsersMap
            _blockedByUsers.value = updatedBlockedByUsers
            persistBlockedByMe()
            persistBlockedMe()
            getBlockStatus(otherUserId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch block status for $otherUserId", e)
            getBlockStatus(otherUserId)
        }
    }

    /**
     * Warms the in-memory block cache for a specific user from Firestore's local cache.
     *
     * This is intended for cold UI entry points where the global listeners may not have
     * delivered yet, but Firestore can still serve the last-known local snapshot instantly.
     */
    suspend fun warmBlockStatusFromLocalCache(otherUserId: String): BlockStatus {
        val myId = currentUserId ?: return getBlockStatus(otherUserId)

        return try {
            val blockedDoc = firestore.collection("users").document(myId)
                .collection("blockedUsers").document(otherUserId)
                .get(Source.CACHE)
                .await()
            val blockedByDoc = firestore.collection("users").document(myId)
                .collection("blockedBy").document(otherUserId)
                .get(Source.CACHE)
                .await()

            if (blockedDoc.exists()) {
                val blockedAt = blockedDoc.getTimestamp("blockedAt")?.toDate()?.time
                    ?: _myBlockedUsersMap.value[otherUserId]
                    ?: 0L
                _myBlockedUsers.value = _myBlockedUsers.value + otherUserId
                _myBlockedUsersMap.value = _myBlockedUsersMap.value + (otherUserId to blockedAt)
            }

            if (blockedByDoc.exists()) {
                _blockedByUsers.value = _blockedByUsers.value + otherUserId
            }

            persistBlockedByMe()
            persistBlockedMe()
            getBlockStatus(otherUserId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to warm block cache for $otherUserId", e)
            getBlockStatus(otherUserId)
        }
    }

    private suspend fun mirrorRealtimeBlockState(myId: String, otherUserId: String, blocked: Boolean) {
        val ref = realtimeBlocksRef.child(myId).child(otherUserId)
        if (blocked) {
            ref.setValue(mapOf("blockedAt" to com.google.firebase.database.ServerValue.TIMESTAMP)).await()
        } else {
            ref.removeValue().await()
        }
    }

    /**
     * Observe block status for a specific user as a Flow.
     * Emits immediately and on any change.
     */
    fun observeBlockStatus(otherUserId: String): Flow<BlockStatus> = callbackFlow {
        // Combine both state flows
        val job1 = launch {
            _myBlockedUsers.collect { trySend(getBlockStatus(otherUserId)) }
        }
        val job2 = launch {
            _blockedByUsers.collect { trySend(getBlockStatus(otherUserId)) }
        }
        awaitClose {
            job1.cancel()
            job2.cancel()
        }
    }

    /**
     * Observe the full set of users I have blocked (for Settings > Blocked Contacts screen).
     */
    fun observeMyBlockedUsers(): Flow<Set<String>> = callbackFlow {
        val job = launch {
            _myBlockedUsers.collect { trySend(it) }
        }
        awaitClose { job.cancel() }
    }

    /**
     * One-shot fetch of all users I have blocked, with their profile data.
     * Used to populate the "Blocked Contacts" settings screen.
     */
    suspend fun getBlockedUsersWithProfiles(): List<BlockedUserInfo> {
        val uid = currentUserId ?: return emptyList()
        val blockedDocs = firestore.collection("users").document(uid)
            .collection("blockedUsers").get().await()

        return blockedDocs.documents.mapNotNull { doc ->
            try {
                val userDoc = firestore.collection("users").document(doc.id).get().await()
                val data = userDoc.data ?: return@mapNotNull null
                BlockedUserInfo(
                    userId = doc.id,
                    username = data["username"] as? String ?: "",
                    profileImageUrl = data["profileImageUrl"] as? String ?: "",
                    phoneNumber = data["phoneNumber"] as? String ?: "",
                    blockedAt = doc.getTimestamp("blockedAt")?.toDate()?.time ?: 0L
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching blocked user profile: ${doc.id}", e)
                null
            }
        }
    }
}

/** Describes the block relationship between two users. */
enum class BlockStatus {
    /** No block in either direction. */
    NOT_BLOCKED,
    /** I blocked them. */
    I_BLOCKED_THEM,
    /** They blocked me. */
    THEY_BLOCKED_ME,
    /** Both users blocked each other. */
    MUTUAL_BLOCK;

    /** True if messaging is blocked in either direction. */
    val isBlocked: Boolean
        get() = this != NOT_BLOCKED

    /** True if I am the blocker (I_BLOCKED_THEM or MUTUAL_BLOCK). */
    val iBlockedThem: Boolean
        get() = this == I_BLOCKED_THEM || this == MUTUAL_BLOCK

    /** True if they blocked me (THEY_BLOCKED_ME or MUTUAL_BLOCK). */
    val theyBlockedMe: Boolean
        get() = this == THEY_BLOCKED_ME || this == MUTUAL_BLOCK
}

/** Profile info for a blocked user (used in Blocked Contacts list). */
data class BlockedUserInfo(
    val userId: String,
    val username: String,
    val profileImageUrl: String,
    val phoneNumber: String,
    val blockedAt: Long
)
