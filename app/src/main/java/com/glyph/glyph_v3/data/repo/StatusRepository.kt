package com.glyph.glyph_v3.data.repo

import android.net.Uri
import android.util.Log
import com.glyph.glyph_v3.data.cache.StatusCacheManager
import com.glyph.glyph_v3.data.models.Status
import com.glyph.glyph_v3.data.models.StatusPrivacyMode
import com.glyph.glyph_v3.data.models.StatusPrivacySetting
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.models.UserStatusGroup
import com.glyph.glyph_v3.data.models.ViewerInfo
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.util.VideoThumbnailUtil
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Repository for Status feature.
 *
 * ## Firestore Schema
 *
 * ```
 * /statuses/{statusId}
 *     userId: String
 *     type: "TEXT" | "IMAGE" | "VIDEO"
 *     text: String
 *     backgroundColor: Int
 *     mediaUrl: String
 *     thumbnailUrl: String
 *     caption: String
 *     timestamp: Long
 *     expiresAt: Long
 *     viewerIds: List<String>
 *
 * /users/{userId}
 *     statusPrivacy: {
 *         mode: "MY_CONTACTS" | "MY_CONTACTS_EXCEPT" | "ONLY_SHARE_WITH"
 *         excludedContacts: List<String>
 *         includedContacts: List<String>
 *     }
 * ```
 *
 * ## Storage
 * ```
 * status_media/{userId}/{filename}
 * ```
 */
object StatusRepository {
    private const val TAG = "StatusRepository"
    private const val STATUSES_COLLECTION = "statuses"
    private const val USERS_COLLECTION = "users"
    private const val STATUS_MEDIA_PATH = "status_media"
    private const val STATUS_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    private val _myStatuses = MutableStateFlow<List<Status>>(emptyList())
    val myStatuses: StateFlow<List<Status>> = _myStatuses.asStateFlow()

    private val _contactStatuses = MutableStateFlow<List<UserStatusGroup>>(emptyList())
    val contactStatuses: StateFlow<List<UserStatusGroup>> = _contactStatuses.asStateFlow()

    private val _privacySetting = MutableStateFlow(StatusPrivacySetting())
    val privacySetting: StateFlow<StatusPrivacySetting> = _privacySetting.asStateFlow()

    private data class CachedStatusUserProfile(
        val id: String,
        val username: String,
        val profileImageUrl: String,
        val phoneNumber: String = "",
        val bio: String = "",
        val profileImageFullUrl: String = "",
        val isOnline: Boolean = false,
        val lastSeen: Long = 0L,
        val fcmToken: String = ""
    )

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var myStatusListener: ListenerRegistration? = null
    private var contactStatusListeners = mutableListOf<ListenerRegistration>()
    private val contactProfileCache = mutableMapOf<String, CachedStatusUserProfile>()
    private val contactProfileRequestsInFlight = mutableSetOf<String>()
    private var cacheSyncJob: Job? = null
    private var contactPrefetchJob: Job? = null
    private var contactStatusesGeneration: Long = 0L

    val currentUserId: String?
        get() = auth.currentUser?.uid

    // ──────────────────────────────────────────────
    // Resolve who can see a status (privacy + blocks)
    // ──────────────────────────────────────────────

    /**
     * Compute the list of user IDs who should be allowed to see this user's status,
     * based on the current privacy setting, contacts list, and block list.
     * Always includes the owner's own uid.
     */
    suspend fun resolveVisibleTo(): List<String> {
        val uid = currentUserId ?: return emptyList()

        // Load current privacy setting
        loadPrivacySetting()
        val privacy = _privacySetting.value

        // Get all contacts (users we share a chat with)
        val contacts = getAllContacts().map { it.id }

        // Get blocked user IDs (both directions)
        val blockedByMe = BlockRepository.myBlockedUsers.value
        val blockedMe = BlockRepository.blockedByUsers.value
        val allBlocked = blockedByMe + blockedMe

        // Compute allowed viewers based on privacy mode
        val allowed = when (privacy.mode) {
            StatusPrivacyMode.MY_CONTACTS -> {
                contacts
            }
            StatusPrivacyMode.MY_CONTACTS_EXCEPT -> {
                contacts.filter { it !in privacy.excludedContacts }
            }
            StatusPrivacyMode.ONLY_SHARE_WITH -> {
                privacy.includedContacts.filter { it in contacts }
            }
        }

        // Remove anyone in the block list (either direction) + always include self
        val result = (allowed.filter { it !in allBlocked } + uid).distinct()
        return result
    }

    // ──────────────────────────────────────────────
    // Upload Status
    // ──────────────────────────────────────────────

    suspend fun uploadTextStatus(text: String, backgroundColor: Int, fontStyle: String = "default"): Result<Status> {
        val uid = currentUserId ?: return Result.failure(Exception("Not authenticated"))
        return try {
            val now = System.currentTimeMillis()
            val statusId = UUID.randomUUID().toString()
            val visibleTo = resolveVisibleTo()
            val status = Status(
                id = statusId,
                userId = uid,
                type = StatusType.TEXT,
                text = text,
                backgroundColor = backgroundColor,
                fontStyle = fontStyle,
                timestamp = now,
                expiresAt = now + STATUS_DURATION_MS,
                visibleTo = visibleTo
            )
            firestore.collection(STATUSES_COLLECTION)
                .document(statusId)
                .set(statusToMap(status))
                .await()
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload text status", e)
            Result.failure(e)
        }
    }

    suspend fun uploadMediaStatus(
        mediaUri: Uri,
        type: StatusType,
        caption: String = "",
        durationMs: Long = 0L,
        onProgress: (Float) -> Unit = {}
    ): Result<Status> {
        val uid = currentUserId ?: return Result.failure(Exception("Not authenticated"))
        require(type == StatusType.IMAGE || type == StatusType.VIDEO || type == StatusType.VOICE) { "Type must be IMAGE, VIDEO or VOICE" }

        return try {
            val now = System.currentTimeMillis()
            val statusId = UUID.randomUUID().toString()
            val visibleTo = resolveVisibleTo()
            val ext = when (type) {
                StatusType.IMAGE -> "jpg"
                StatusType.VOICE -> "m4a"
                else -> "mp4"
            }
            val fileName = "${statusId}.$ext"
            val storageRef = storage.reference.child("$STATUS_MEDIA_PATH/$uid/$fileName")
            val applicationContext = FirebaseApp.getInstance().applicationContext

            // Upload media. Generated layout statuses come through our FileProvider,
            // so stream those explicitly instead of relying on putFile URI handling.
            val metadata = StorageMetadata.Builder()
                .setContentType(
                    when (type) {
                        StatusType.IMAGE -> "image/jpeg"
                        StatusType.VOICE -> "audio/mp4"
                        else -> "video/mp4"
                    }
                )
                .build()

            val uploadTask = if (mediaUri.authority == "${applicationContext.packageName}.fileprovider" || mediaUri.scheme == "content") {
                val inputStream = applicationContext.contentResolver.openInputStream(mediaUri)
                    ?: return Result.failure(Exception("Unable to open media stream"))
                storageRef.putStream(inputStream, metadata)
            } else {
                storageRef.putFile(mediaUri, metadata)
            }
            uploadTask.addOnProgressListener { snapshot ->
                val raw = snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount
                // Reserve 0–80% for the main media upload; thumbnail gets 80–100%
                onProgress(raw * if (type == StatusType.VIDEO) 0.8f else 1.0f)
            }
            uploadTask.await()
            val mediaUrl = storageRef.downloadUrl.await().toString()

            // Generate and upload a real JPEG thumbnail for video statuses.
            // Previously thumbnailUrl was incorrectly set to mediaUrl (the video URL).
            val thumbnailUrl: String = if (type == StatusType.VIDEO) {
                val thumbPair = VideoThumbnailUtil.generateThumbnailBytes(applicationContext, mediaUri)
                if (thumbPair != null) {
                    val (thumbBytes, _) = thumbPair
                    val thumbRef = storage.reference.child("$STATUS_MEDIA_PATH/$uid/${statusId}_thumb.jpg")
                    val thumbMeta = StorageMetadata.Builder().setContentType("image/jpeg").build()
                    thumbRef.putBytes(thumbBytes, thumbMeta).await()
                    onProgress(1.0f)
                    thumbRef.downloadUrl.await().toString()
                } else {
                    Log.w(TAG, "Thumbnail generation failed; using empty thumbnailUrl")
                    ""
                }
            } else ""

            val status = Status(
                id = statusId,
                userId = uid,
                type = type,
                mediaUrl = mediaUrl,
                thumbnailUrl = thumbnailUrl,
                caption = caption,
                timestamp = now,
                expiresAt = now + STATUS_DURATION_MS,
                visibleTo = visibleTo,
                durationMs = durationMs
            )
            firestore.collection(STATUSES_COLLECTION)
                .document(statusId)
                .set(statusToMap(status))
                .await()
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload media status", e)
            Result.failure(e)
        }
    }

    suspend fun publishForwardedStatus(
        type: StatusType,
        text: String = "",
        backgroundColor: Int = 0xFF1B5E20.toInt(),
        fontStyle: String = "default",
        mediaUrl: String = "",
        thumbnailUrl: String = "",
        caption: String = "",
        durationMs: Long = 0L
    ): Result<Status> {
        val uid = currentUserId ?: return Result.failure(Exception("Not authenticated"))
        require(type == StatusType.TEXT || type == StatusType.IMAGE || type == StatusType.VIDEO || type == StatusType.VOICE) {
            "Unsupported status type"
        }

        if (type == StatusType.TEXT && text.isBlank()) {
            return Result.failure(Exception("Text status cannot be empty"))
        }
        if (type != StatusType.TEXT && mediaUrl.isBlank()) {
            return Result.failure(Exception("Forwarded media status needs a remote media URL"))
        }

        return try {
            val now = System.currentTimeMillis()
            val statusId = UUID.randomUUID().toString()
            val visibleTo = resolveVisibleTo()
            val status = Status(
                id = statusId,
                userId = uid,
                type = type,
                text = text,
                backgroundColor = backgroundColor,
                fontStyle = fontStyle,
                mediaUrl = mediaUrl,
                thumbnailUrl = thumbnailUrl,
                caption = caption,
                timestamp = now,
                expiresAt = now + STATUS_DURATION_MS,
                visibleTo = visibleTo,
                durationMs = durationMs
            )
            firestore.collection(STATUSES_COLLECTION)
                .document(statusId)
                .set(statusToMap(status))
                .await()
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish forwarded status", e)
            Result.failure(e)
        }
    }

    // ──────────────────────────────────────────────
    // Local Cache Integration
    // ──────────────────────────────────────────────

    /**
     * Get local file path for a status, downloading if needed.
     * Returns null for text statuses or on failure.
     */
    suspend fun getLocalMediaPath(status: Status): String? {
        return StatusCacheManager.getLocalPath(status)
    }

    /** Pre-download media for a list of statuses (call when opening a group) */
    suspend fun prefetchStatuses(statuses: List<Status>) {
        StatusCacheManager.prefetch(statuses)
    }

    // ──────────────────────────────────────────────
    // Delete Status
    // ──────────────────────────────────────────────

    suspend fun deleteStatus(statusId: String): Result<Unit> {
        return try {
            val uid = currentUserId ?: return Result.failure(Exception("Not authenticated"))
            val doc = firestore.collection(STATUSES_COLLECTION).document(statusId).get().await()
            if (!doc.exists()) {
                StatusCacheManager.evict(statusId)
                return Result.success(Unit)
            }
            if (doc.getString("userId") != uid) {
                return Result.failure(Exception("Cannot delete another user's status"))
            }
            // Delete media from storage if present
            val mediaUrl = doc.getString("mediaUrl")
            if (!mediaUrl.isNullOrEmpty()) {
                try {
                    storage.getReferenceFromUrl(mediaUrl).delete().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete media from storage", e)
                }
            }
            val thumbnailUrl = doc.getString("thumbnailUrl")
            if (!thumbnailUrl.isNullOrEmpty()) {
                try {
                    storage.getReferenceFromUrl(thumbnailUrl).delete().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete thumbnail from storage", e)
                }
            }
            firestore.collection(STATUSES_COLLECTION).document(statusId).delete().await()
            // Evict from local cache
            StatusCacheManager.evict(statusId)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete status", e)
            Result.failure(e)
        }
    }

    // ──────────────────────────────────────────────
    // Mark Status Viewed
    // ──────────────────────────────────────────────

    suspend fun markStatusViewed(statusId: String) {
        val uid = currentUserId ?: return
        try {
            firestore.collection(STATUSES_COLLECTION)
                .document(statusId)
                .update(
                    mapOf(
                        "viewerIds" to FieldValue.arrayUnion(uid),
                        "viewerTimestamps.$uid" to System.currentTimeMillis()
                    )
                )
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark status viewed", e)
        }
    }

    suspend fun likeStatus(statusId: String) {
        val uid = currentUserId ?: return
        try {
            FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await()
            val result = FirebaseFunctions.getInstance()
                .getHttpsCallable("likeStatus")
                .call(mapOf("statusId" to statusId))
                .await()
            Log.d(TAG, "likeStatus callable result: ${result.data}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to like status", e)
            throw e
        }
    }

    // ──────────────────────────────────────────────
    // Real-time Listeners
    // ──────────────────────────────────────────────

    fun startListeningMyStatuses() {
        val uid = currentUserId ?: return
        stopListeningMyStatuses()

        val now = System.currentTimeMillis()
        myStatusListener = firestore.collection(STATUSES_COLLECTION)
            .whereEqualTo("userId", uid)
            .whereGreaterThan("expiresAt", now)
            .orderBy("expiresAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to my statuses", error)
                    return@addSnapshotListener
                }
                val statuses = snapshot?.documents?.mapNotNull { doc ->
                    mapToStatus(doc.id, doc.data)
                }?.sortedBy { it.timestamp } ?: emptyList()
                if (_myStatuses.value != statuses) {
                    _myStatuses.value = statuses
                }
                scheduleCacheSync(statuses, _contactStatuses.value.flatMap { it.statuses })
            }
    }

    fun stopListeningMyStatuses() {
        myStatusListener?.remove()
        myStatusListener = null
    }

    /**
     * Listen to statuses the current user is allowed to see.
     * Uses `whereArrayContains("visibleTo", uid)` so only statuses
     * that include the current user in their visibility list are returned.
     * Expired statuses are filtered client-side.
     */
    fun startListeningContactStatuses() {
        val uid = currentUserId ?: return
        stopListeningContactStatuses()

        val listener = firestore.collection(STATUSES_COLLECTION)
            .whereArrayContains("visibleTo", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to contact statuses", error)
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val allStatuses = snapshot?.documents?.mapNotNull { doc ->
                    mapToStatus(doc.id, doc.data)
                }?.filter { it.expiresAt > now } ?: emptyList()

                // Filter out my own statuses — those go to _myStatuses
                val otherStatuses = allStatuses.filter { it.userId != uid }

                // Also filter out statuses from blocked users (defensive)
                val blockedByMe = BlockRepository.myBlockedUsers.value
                val blockedMe = BlockRepository.blockedByUsers.value
                val allBlocked = blockedByMe + blockedMe
                val safeStatuses = otherStatuses.filter { it.userId !in allBlocked }
                val generation = ++contactStatusesGeneration
                publishReadyContactStatuses(safeStatuses, uid, generation)
                scheduleCacheSync(_myStatuses.value, safeStatuses)
                scheduleContactStatusPrefetch(safeStatuses, uid, generation)
            }
        contactStatusListeners.add(listener)
    }

    private fun scheduleContactStatusPrefetch(statuses: List<Status>, uid: String, generation: Long) {
        contactPrefetchJob?.cancel()
        contactPrefetchJob = repositoryScope.launch {
            val downloadedAny = StatusCacheManager.prefetchForDelivery(statuses)
            if (downloadedAny || generation == contactStatusesGeneration) {
                publishReadyContactStatuses(statuses, uid, generation)
            }
        }
    }

    private fun publishReadyContactStatuses(statuses: List<Status>, uid: String, generation: Long) {
        repositoryScope.launch {
            if (generation != contactStatusesGeneration) return@launch

            val grouped = statuses.groupBy { it.userId }
                .map { (userId, userStatuses) ->
                    val sortedStatuses = userStatuses.sortedBy { it.timestamp }
                    UserStatusGroup(
                        userId = userId,
                        statuses = sortedStatuses,
                        lastStatusTimestamp = sortedStatuses.maxOf { it.timestamp },
                        allViewed = sortedStatuses.all { it.viewerIds.contains(uid) }
                    )
                }
                .sortedByDescending { it.lastStatusTimestamp }

            val cachedGroups = applyCachedProfiles(grouped)
            if (generation != contactStatusesGeneration) return@launch
            if (_contactStatuses.value != cachedGroups) {
                _contactStatuses.value = cachedGroups
            }
            enrichStatusGroups(cachedGroups)
        }
    }

    private fun enrichStatusGroups(groups: List<UserStatusGroup>) {
        val userIds = groups.map { it.userId }
            .distinct()
            .filterNot { userId ->
                contactProfileCache.containsKey(userId) || contactProfileRequestsInFlight.contains(userId)
            }
        if (userIds.isEmpty()) return

        for (userId in userIds) {
            contactProfileRequestsInFlight += userId
            firestore.collection(USERS_COLLECTION).document(userId).get()
                .addOnSuccessListener { doc ->
                    val user = doc.toObject(User::class.java)
                    val remoteUsername = user?.username ?: doc.getString("username") ?: ""
                    val phoneNumber = user?.phoneNumber ?: doc.getString("phoneNumber") ?: ""
                    val profileImageUrl = user?.profileImageUrl ?: doc.getString("profileImageUrl") ?: ""
                    contactProfileRequestsInFlight.remove(userId)
                    cacheUser(userId, user, remoteUsername, profileImageUrl)
                    // Resolve display name with device contact priority
                    if (phoneNumber.isNotBlank()) {
                        ContactDisplayNameResolver.cacheUserPhone(userId, phoneNumber)
                    }
                    val resolvedUsername = ContactDisplayNameResolver.getDisplayName(
                        otherUserId = userId,
                        remoteProfileName = remoteUsername,
                        remotePhoneNumber = phoneNumber
                    )
                    val current = _contactStatuses.value.toMutableList()
                    val idx = current.indexOfFirst { it.userId == userId }
                    if (idx >= 0) {
                        val updatedGroup = current[idx].copy(
                            username = resolvedUsername,
                            profileImageUrl = profileImageUrl
                        )
                        if (updatedGroup != current[idx]) {
                            current[idx] = updatedGroup
                            _contactStatuses.value = current
                        }
                    }
                }
                .addOnFailureListener {
                    contactProfileRequestsInFlight.remove(userId)
                    Log.w(TAG, "Failed to fetch profile for status user=$userId", it)
                }
        }
    }

    private fun applyCachedProfiles(groups: List<UserStatusGroup>): List<UserStatusGroup> {
        var changed = false
        val updated = groups.map { group ->
            val cached = contactProfileCache[group.userId] ?: return@map group
            if (group.username == cached.username && group.profileImageUrl == cached.profileImageUrl) {
                group
            } else {
                changed = true
                group.copy(
                    username = cached.username,
                    profileImageUrl = cached.profileImageUrl
                )
            }
        }
        return if (changed) updated else groups
    }

    private fun scheduleCacheSync(myStatuses: List<Status>, contactStatuses: List<Status>) {
        val validIds = buildSet {
            myStatuses.forEach { add(it.id) }
            contactStatuses.forEach { add(it.id) }
        }
        cacheSyncJob?.cancel()
        cacheSyncJob = repositoryScope.launch {
            StatusCacheManager.syncWithRemote(validIds)
        }
    }

    private suspend fun getUsersByIds(userIds: List<String>): List<User> {
        if (userIds.isEmpty()) return emptyList()

        val distinctIds = userIds.distinct()
        val usersById = linkedMapOf<String, User>()
        val missingIds = mutableListOf<String>()

        distinctIds.forEach { userId ->
            val cached = contactProfileCache[userId]
            if (cached != null) {
                usersById[userId] = cached.toUser()
            } else {
                missingIds += userId
            }
        }

        if (missingIds.isNotEmpty()) {
            missingIds.chunked(30).forEach { chunk ->
                val result = firestore.collection(USERS_COLLECTION)
                    .whereIn("id", chunk)
                    .get()
                    .await()
                result.documents.forEach { doc ->
                    val user = doc.toObject(User::class.java)
                    val resolvedId = user?.id?.takeIf { it.isNotBlank() } ?: doc.id
                    val resolvedUser = (user ?: User(id = resolvedId)).copy(
                        id = resolvedId,
                        username = user?.username ?: doc.getString("username").orEmpty(),
                        profileImageUrl = user?.profileImageUrl ?: doc.getString("profileImageUrl").orEmpty()
                    )
                    cacheUser(resolvedId, resolvedUser, resolvedUser.username, resolvedUser.profileImageUrl)
                    usersById[resolvedId] = resolvedUser
                }
            }
        }

        return distinctIds.mapNotNull { usersById[it] ?: contactProfileCache[it]?.toUser() }
    }

    private fun cacheUser(
        fallbackId: String,
        user: User?,
        fallbackUsername: String,
        fallbackProfileImageUrl: String
    ) {
        val resolvedId = user?.id?.takeIf { it.isNotBlank() } ?: fallbackId
        contactProfileCache[resolvedId] = CachedStatusUserProfile(
            id = resolvedId,
            username = user?.username ?: fallbackUsername,
            profileImageUrl = user?.profileImageUrl ?: fallbackProfileImageUrl,
            phoneNumber = user?.phoneNumber.orEmpty(),
            bio = user?.bio.orEmpty(),
            profileImageFullUrl = user?.profileImageFullUrl.orEmpty(),
            isOnline = user?.isOnline ?: false,
            lastSeen = user?.lastSeen ?: 0L,
            fcmToken = user?.fcmToken.orEmpty()
        )
    }

    private fun CachedStatusUserProfile.toUser(): User {
        return User(
            id = id,
            phoneNumber = phoneNumber,
            username = username,
            bio = bio,
            profileImageUrl = profileImageUrl,
            profileImageFullUrl = profileImageFullUrl,
            isOnline = isOnline,
            lastSeen = lastSeen,
            fcmToken = fcmToken
        )
    }

    suspend fun primeIncomingContactStatus(status: Status, ownerName: String = ""): Boolean {
        val uid = currentUserId ?: return false
        if (status.userId.isBlank() || status.userId == uid) return false
        if (status.expiresAt > 0L && status.expiresAt <= System.currentTimeMillis()) return false
        if (status.visibleTo.isNotEmpty() && uid !in status.visibleTo) return false

        val ready = if (status.type == StatusType.TEXT) {
            true
        } else {
            !StatusCacheManager.getLocalPath(status).isNullOrBlank()
        }
        if (!ready) return false

        val cachedProfile = contactProfileCache[status.userId]
        // Cache phone for contact lookup and resolve display name with device contact priority
        cachedProfile?.phoneNumber?.takeIf { it.isNotBlank() }?.let {
            ContactDisplayNameResolver.cacheUserPhone(status.userId, it)
        }
        val resolvedName = ContactDisplayNameResolver.getDisplayName(
            otherUserId = status.userId,
            remoteProfileName = ownerName.ifBlank { cachedProfile?.username },
            remotePhoneNumber = cachedProfile?.phoneNumber
        )
        val fallbackName = resolvedName.ifBlank { status.userId }
        val fallbackAvatar = cachedProfile?.profileImageUrl.orEmpty()

        val currentGroups = _contactStatuses.value.toMutableList()
        val existingIndex = currentGroups.indexOfFirst { it.userId == status.userId }
        val updatedGroup = if (existingIndex >= 0) {
            val existingGroup = currentGroups[existingIndex]
            val mergedStatuses = (existingGroup.statuses + status)
                .distinctBy { it.id }
                .sortedBy { it.timestamp }
            existingGroup.copy(
                username = existingGroup.username.ifBlank { fallbackName },
                profileImageUrl = existingGroup.profileImageUrl.ifBlank { fallbackAvatar },
                statuses = mergedStatuses,
                lastStatusTimestamp = mergedStatuses.maxOf { it.timestamp },
                allViewed = mergedStatuses.all { uid in it.viewerIds }
            )
        } else {
            UserStatusGroup(
                userId = status.userId,
                username = fallbackName,
                profileImageUrl = fallbackAvatar,
                statuses = listOf(status),
                lastStatusTimestamp = status.timestamp,
                allViewed = uid in status.viewerIds
            )
        }

        if (existingIndex >= 0) {
            currentGroups[existingIndex] = updatedGroup
        } else {
            currentGroups += updatedGroup
        }

        val sortedGroups = applyCachedProfiles(
            currentGroups.sortedByDescending { it.lastStatusTimestamp }
        )
        if (_contactStatuses.value != sortedGroups) {
            _contactStatuses.value = sortedGroups
        }
        enrichStatusGroups(sortedGroups)
        return true
    }

    suspend fun primeIncomingContactStatus(
        statusId: String,
        ownerUserId: String,
        ownerName: String = ""
    ): Boolean {
        if (statusId.isBlank()) return false
        val snapshot = firestore.collection(STATUSES_COLLECTION).document(statusId).get().await()
        val status = mapToStatus(snapshot.id, snapshot.data) ?: return false
        if (ownerUserId.isNotBlank() && status.userId != ownerUserId) return false
        return primeIncomingContactStatus(status, ownerName)
    }

    fun stopListeningContactStatuses() {
        contactStatusListeners.forEach { it.remove() }
        contactStatusListeners.clear()
        contactPrefetchJob?.cancel()
    }

    // ──────────────────────────────────────────────
    // Privacy Settings
    // ──────────────────────────────────────────────

    suspend fun loadPrivacySetting() {
        val uid = currentUserId ?: return
        try {
            val doc = firestore.collection(USERS_COLLECTION).document(uid).get().await()
            @Suppress("UNCHECKED_CAST")
            val privacyMap = doc.get("statusPrivacy") as? Map<String, Any>
            if (privacyMap != null) {
                _privacySetting.value = StatusPrivacySetting(
                    mode = try {
                        StatusPrivacyMode.valueOf(privacyMap["mode"] as? String ?: "MY_CONTACTS")
                    } catch (_: Exception) {
                        StatusPrivacyMode.MY_CONTACTS
                    },
                    excludedContacts = (privacyMap["excludedContacts"] as? List<*>)
                        ?.filterIsInstance<String>() ?: emptyList(),
                    includedContacts = (privacyMap["includedContacts"] as? List<*>)
                        ?.filterIsInstance<String>() ?: emptyList()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load privacy setting", e)
        }
    }

    suspend fun savePrivacySetting(setting: StatusPrivacySetting): Result<Unit> {
        val uid = currentUserId ?: return Result.failure(Exception("Not authenticated"))
        return try {
            val data = mapOf(
                "statusPrivacy" to mapOf(
                    "mode" to setting.mode.name,
                    "excludedContacts" to setting.excludedContacts,
                    "includedContacts" to setting.includedContacts
                )
            )
            firestore.collection(USERS_COLLECTION).document(uid)
                .update(data)
                .await()
            _privacySetting.value = setting

            // Propagate new visibleTo to all active statuses
            propagateVisibleToActiveStatuses()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save privacy setting", e)
            Result.failure(e)
        }
    }

    /**
     * Update visibleTo on all active (non-expired) statuses for the current user.
     * Called after a privacy setting change so existing statuses reflect the new rules.
     */
    private suspend fun propagateVisibleToActiveStatuses() {
        val uid = currentUserId ?: return
        try {
            val newVisibleTo = resolveVisibleTo()
            val now = System.currentTimeMillis()
            val activeStatuses = firestore.collection(STATUSES_COLLECTION)
                .whereEqualTo("userId", uid)
                .whereGreaterThan("expiresAt", now)
                .get()
                .await()

            if (activeStatuses.isEmpty) return

            val batch = firestore.batch()
            for (doc in activeStatuses.documents) {
                batch.update(doc.reference, "visibleTo", newVisibleTo)
            }
            batch.commit().await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to propagate visibleTo to active statuses", e)
        }
    }

    /**
     * Check if a given viewer can see statuses from the status owner,
     * based on the owner's privacy settings.
     */
    suspend fun canViewStatus(ownerId: String, viewerId: String): Boolean {
        try {
            val doc = firestore.collection(USERS_COLLECTION).document(ownerId).get().await()
            @Suppress("UNCHECKED_CAST")
            val privacyMap = doc.get("statusPrivacy") as? Map<String, Any>
            if (privacyMap == null) return true // default: visible to all contacts

            val mode = try {
                StatusPrivacyMode.valueOf(privacyMap["mode"] as? String ?: "MY_CONTACTS")
            } catch (_: Exception) {
                StatusPrivacyMode.MY_CONTACTS
            }

            return when (mode) {
                StatusPrivacyMode.MY_CONTACTS -> true
                StatusPrivacyMode.MY_CONTACTS_EXCEPT -> {
                    val excluded = (privacyMap["excludedContacts"] as? List<*>)
                        ?.filterIsInstance<String>() ?: emptyList()
                    viewerId !in excluded
                }
                StatusPrivacyMode.ONLY_SHARE_WITH -> {
                    val included = (privacyMap["includedContacts"] as? List<*>)
                        ?.filterIsInstance<String>() ?: emptyList()
                    viewerId in included
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check status visibility", e)
            return true
        }
    }

    // ──────────────────────────────────────────────
    // View status viewers
    // ──────────────────────────────────────────────

    fun getStatusViewers(statusId: String): Flow<List<ViewerInfo>> = callbackFlow {
        val listener = firestore.collection(STATUSES_COLLECTION).document(statusId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to status viewers", error)
                    return@addSnapshotListener
                }
                @Suppress("UNCHECKED_CAST")
                val viewerIds = (snapshot?.get("viewerIds") as? List<String>) ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val timestamps = (snapshot?.get("viewerTimestamps") as? Map<String, *>)
                    ?.mapValues { (_, v) -> (v as? Number)?.toLong() ?: 0L }
                    ?: emptyMap()

                if (viewerIds.isEmpty()) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                // Pre-emit from cache so the sheet renders instantly for known users
                val cached = viewerIds.mapNotNull { uid ->
                    contactProfileCache[uid]?.toUser()?.let { user ->
                        ViewerInfo(user = user, seenAt = timestamps[uid] ?: 0L)
                    }
                }
                if (cached.isNotEmpty()) trySend(cached)

                repositoryScope.launch {
                    try {
                        val users = getUsersByIds(viewerIds.take(30))
                        trySend(users.map { user ->
                            ViewerInfo(user = user, seenAt = timestamps[user.id] ?: 0L)
                        })
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to fetch viewer profiles", t)
                        trySend(emptyList())
                    }
                }
            }
        awaitClose { listener.remove() }
    }

    // ──────────────────────────────────────────────
    // Fetch all contacts for privacy picker
    // ──────────────────────────────────────────────

    suspend fun getAllContacts(): List<User> {
        return try {
            val uid = currentUserId ?: return emptyList()
            // Get all users from chats the current user participates in
            val chatSnapshots = firestore.collection("chats")
                .whereArrayContains("participants", uid)
                .get()
                .await()

            val contactIds = mutableSetOf<String>()
            for (doc in chatSnapshots.documents) {
                @Suppress("UNCHECKED_CAST")
                val participants = doc.get("participants") as? List<String> ?: continue
                participants.filter { it != uid }.forEach { contactIds.add(it) }
            }

            if (contactIds.isEmpty()) return emptyList()

            val users = mutableListOf<User>()
            // Firestore 'in' supports up to 30 items per query
            contactIds.chunked(30).forEach { chunk ->
                val result = firestore.collection(USERS_COLLECTION)
                    .whereIn("id", chunk)
                    .get()
                    .await()
                result.documents.forEach { doc ->
                    doc.toObject(User::class.java)?.let { users.add(it) }
                }
            }
            users
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch contacts", e)
            emptyList()
        }
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private fun statusToMap(status: Status): Map<String, Any> {
        return mapOf(
            "userId" to status.userId,
            "type" to status.type.name,
            "text" to status.text,
            "backgroundColor" to status.backgroundColor,
            "fontStyle" to status.fontStyle,
            "mediaUrl" to status.mediaUrl,
            "thumbnailUrl" to status.thumbnailUrl,
            "caption" to status.caption,
            "timestamp" to status.timestamp,
            "expiresAt" to status.expiresAt,
            "viewerIds" to status.viewerIds,
            "likedByIds" to status.likedByIds,
            "visibleTo" to status.visibleTo,
            "durationMs" to status.durationMs
        )
    }

    private fun mapToStatus(id: String, data: Map<String, Any>?): Status? {
        if (data == null) return null
        return try {
            Status(
                id = id,
                userId = data["userId"] as? String ?: "",
                type = try {
                    StatusType.valueOf(data["type"] as? String ?: "TEXT")
                } catch (_: Exception) {
                    StatusType.TEXT
                },
                text = data["text"] as? String ?: "",
                backgroundColor = (data["backgroundColor"] as? Number)?.toInt()
                    ?: 0xFF1B5E20.toInt(),
                fontStyle = data["fontStyle"] as? String ?: "default",
                mediaUrl = data["mediaUrl"] as? String ?: "",
                thumbnailUrl = data["thumbnailUrl"] as? String ?: "",
                caption = data["caption"] as? String ?: "",
                timestamp = (data["timestamp"] as? Number)?.toLong() ?: 0L,
                expiresAt = (data["expiresAt"] as? Number)?.toLong() ?: 0L,
                viewerIds = (data["viewerIds"] as? List<*>)
                    ?.filterIsInstance<String>() ?: emptyList(),
                likedByIds = (data["likedByIds"] as? List<*>)
                    ?.filterIsInstance<String>() ?: emptyList(),
                visibleTo = (data["visibleTo"] as? List<*>)
                    ?.filterIsInstance<String>() ?: emptyList(),
                durationMs = (data["durationMs"] as? Number)?.toLong() ?: 0L,
                viewerTimestamps = (data["viewerTimestamps"] as? Map<*, *>)
                    ?.entries
                    ?.associate { (k, v) -> k.toString() to ((v as? Number)?.toLong() ?: 0L) }
                    ?: emptyMap()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse status: $id", e)
            null
        }
    }

    fun cleanup() {
        stopListeningMyStatuses()
        stopListeningContactStatuses()
        cacheSyncJob?.cancel()
        contactProfileRequestsInFlight.clear()
    }
}
