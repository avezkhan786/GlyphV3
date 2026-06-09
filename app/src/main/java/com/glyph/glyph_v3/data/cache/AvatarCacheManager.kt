package com.glyph.glyph_v3.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import coil.imageLoader
import coil.request.ImageRequest
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance avatar caching system for instant profile picture loading.
 * 
 * Features:
 * - Persistent storage in app internal storage (not cache directory)
 * - Instant synchronous loads from disk
 * - Background updates with version/hash tracking
 * - Atomic file replacement to prevent corruption
 * - No network blocking on UI thread
 * 
 * Architecture:
 * 1. Always load from local storage first (synchronous, instant)
 * 2. Background check for updates (async, non-blocking)
 * 3. Atomic update if new version available
 */
object AvatarCacheManager {
    private const val TAG = "AvatarCacheManager"
    private const val AVATARS_DIR = "avatars"
    private const val METADATA_SUFFIX = ".meta"
    private const val GROUP_ICON_PREFIX = "group_icon_"
    
    private lateinit var avatarsDir: File
    private val localAvatarPathIndex = ConcurrentHashMap<String, String>()
    
    fun init(context: Context) {
        // Use internal storage (not cache) for permanent storage
        avatarsDir = File(context.filesDir, AVATARS_DIR)
        if (!avatarsDir.exists()) {
            val created = avatarsDir.mkdirs()
        } else {
        }
        rebuildLocalAvatarIndex()
    }
    
    /**
     * Get local file path for a user's avatar. Returns null if not cached.
     * This is SYNCHRONOUS and fast - suitable for UI thread.
     */
    fun getLocalAvatarPath(userId: String): String? {
        if (!::avatarsDir.isInitialized) {
            Log.w(TAG, "getLocalAvatarPath called before init() for user $userId")
            return null
        }

        localAvatarPathIndex[userId]?.let { cachedPath ->
            val cachedFile = File(cachedPath)
            if (cachedFile.exists() && cachedFile.length() > 0) {
                return cachedPath
            }
            localAvatarPathIndex.remove(userId)
        }
        
        val avatarFile = File(avatarsDir, "${userId}.jpg")
        val exists = avatarFile.exists() && avatarFile.length() > 0

        return if (exists) {
            localAvatarPathIndex[userId] = avatarFile.absolutePath
            avatarFile.absolutePath
        } else {
            null
        }
    }

    fun buildAvatarCacheKey(userId: String, localAvatarPath: String?, avatarUrl: String?): String? {
        val localKey = localAvatarPath?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                "avatar:$userId:${file.lastModified()}"
            } else {
                null
            }
        }
        if (localKey != null) return localKey
        return avatarUrl?.takeIf { it.isNotBlank() }?.let { "avatar:$userId:$it" }
    }

    fun getLocalGroupAvatarPath(chatId: String): String? {
        return getLocalAvatarPath(groupIconCacheId(chatId))
    }

    fun buildGroupAvatarCacheKey(chatId: String, localAvatarPath: String?, avatarUrl: String?): String? {
        return buildAvatarCacheKey(
            userId = groupIconCacheId(chatId),
            localAvatarPath = localAvatarPath,
            avatarUrl = avatarUrl
        )
    }
    
    /**
     * Check if avatar exists in local storage.
     * Fast synchronous check suitable for UI thread.
     */
    fun hasLocalAvatar(userId: String): Boolean {
        return getLocalAvatarPath(userId) != null
    }
    
    /**
     * Download and cache an avatar from URL.
     * This is ASYNC and should be called from a background thread.
     * 
     * @param userId User ID for filename
     * @param avatarUrl Remote URL of the avatar
     * @param context Application context for Glide
     * @return True if successfully cached, false otherwise
     */
    suspend fun cacheAvatar(userId: String, avatarUrl: String?, context: Context): Boolean {
        if (avatarUrl.isNullOrEmpty()) {
            Log.w(TAG, "Cannot cache avatar: URL is null or empty for user $userId")
            return false
        }
        
        if (!::avatarsDir.isInitialized) {
            Log.e(TAG, "Cannot cache avatar: AvatarCacheManager not initialized! Call init() in Application.onCreate()")
            return false
        }
        
        
        return withContext(Dispatchers.IO) {
            try {
                // Save to internal storage atomically
                val tempFile = File(avatarsDir, "${userId}.tmp")
                val finalFile = File(avatarsDir, "${userId}.jpg")

                // Always fetch fresh bytes when the Storage URL stays stable.
                val wroteBytes = downloadAvatarToFileFresh(
                    avatarUrl = avatarUrl,
                    tempFile = tempFile,
                    context = context
                )

                if (!wroteBytes || !tempFile.exists() || tempFile.length() <= 0) {
                    tempFile.delete()
                    Log.w(TAG, "Failed to download avatar bytes for user $userId (empty temp file)")
                    return@withContext false
                }


                // Atomic rename (prevents corruption if app crashes during write)
                if (tempFile.renameTo(finalFile)) {
                    localAvatarPathIndex[userId] = finalFile.absolutePath
                    // Persist urlHash + remote signature (md5/updatedTime) so other clients can detect overwrites.
                    val metaFile = File(avatarsDir, "${userId}${METADATA_SUFFIX}")
                    val remote = fetchRemoteSignature(avatarUrl)
                    writeMetadata(
                        metaFile = metaFile,
                        urlHash = hashUrl(avatarUrl),
                        remoteMd5 = remote?.md5,
                        remoteUpdatedTimeMs = remote?.updatedTimeMs
                    )

                    return@withContext true
                } else {
                    tempFile.delete()
                    Log.e(TAG, "✗ Failed to rename temp file for user $userId from ${tempFile.absolutePath} to ${finalFile.absolutePath}")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "✗ Error caching avatar for user $userId from URL $avatarUrl", e)
                return@withContext false
            }
        }
    }

    suspend fun cacheGroupAvatar(chatId: String, avatarUrl: String?, context: Context): Boolean {
        return cacheAvatar(groupIconCacheId(chatId), avatarUrl, context)
    }
    
    /**
     * Check if avatar needs update by comparing URL hash.
     * This is ASYNC and lightweight - only checks metadata.
     * 
     * @return True if avatar should be re-downloaded
     */
    suspend fun needsUpdate(userId: String, currentUrl: String?): Boolean {
        if (currentUrl.isNullOrEmpty()) return false

        return withContext(Dispatchers.IO) {
            try {
                // If we don't have a cached file, we definitely need to download.
                val avatarFile = File(avatarsDir, "${userId}.jpg")
                if (!avatarFile.exists() || avatarFile.length() <= 0) {
                    return@withContext true
                }

                val metaFile = File(avatarsDir, "${userId}${METADATA_SUFFIX}")
                val currentUrlHash = hashUrl(currentUrl)
                val saved = readMetadata(metaFile)

                // Back-compat: if metadata missing, we need at least one download OR metadata refresh.
                if (saved == null) {
                    return@withContext true
                }

                // URL change always means update.
                if (saved.urlHash != null && saved.urlHash != currentUrlHash) {
                    return@withContext true
                }

                // Critical fix: URL can stay the same when users overwrite the same Storage path.
                // Compare remote Storage metadata (md5Hash/updatedTime) to detect content changes.
                val remote = fetchRemoteSignature(currentUrl)

                if (remote != null) {
                    // If we previously only stored urlHash (older meta), upgrade metadata without redownloading.
                    if (saved.remoteMd5 == null && saved.remoteUpdatedTimeMs == null) {
                        writeMetadata(
                            metaFile = metaFile,
                            urlHash = currentUrlHash,
                            remoteMd5 = remote.md5,
                            remoteUpdatedTimeMs = remote.updatedTimeMs
                        )
                        // Force one refresh: we cannot safely know whether the local file matches remote
                        // when coming from legacy metadata (urlHash-only) and URLs may remain stable.
                        return@withContext true
                    }

                    val md5Changed =
                        !remote.md5.isNullOrEmpty() && !saved.remoteMd5.isNullOrEmpty() && remote.md5 != saved.remoteMd5
                    val updatedTimeChanged =
                        remote.updatedTimeMs != null && saved.remoteUpdatedTimeMs != null && remote.updatedTimeMs > saved.remoteUpdatedTimeMs

                    return@withContext md5Changed || updatedTimeChanged
                }

                // No remote metadata available (offline / error). Don't force downloads.
                return@withContext false
            } catch (e: Exception) {
                Log.e(TAG, "Error checking avatar metadata for user $userId", e)
                // If we can't check, do not force updates (avoids loops when offline)
                return@withContext false
            }
        }
    }
    
    /**
     * Update avatar if needed. Checks metadata first to avoid unnecessary downloads.
     * This is ASYNC and non-blocking.
     * 
     * @return True if avatar was updated, false if no update needed or failed
     */
    suspend fun updateAvatarIfNeeded(userId: String, avatarUrl: String?, context: Context): Boolean {
        if (avatarUrl.isNullOrEmpty()) {
            return false
        }
        
        // Quick metadata check first
        val needsUpdate = needsUpdate(userId, avatarUrl)
        
        if (!needsUpdate) {
            return false
        }
        
        // Download and cache new version
        val cached = cacheAvatar(userId, avatarUrl, context)
        if (cached) {
            // Best-effort: update metadata with remote signature (so future checks work even if URL stays same)
            try {
                val remote = fetchRemoteSignature(avatarUrl)
                val metaFile = File(avatarsDir, "${userId}${METADATA_SUFFIX}")
                writeMetadata(
                    metaFile = metaFile,
                    urlHash = hashUrl(avatarUrl),
                    remoteMd5 = remote?.md5,
                    remoteUpdatedTimeMs = remote?.updatedTimeMs
                )
            } catch (_: Exception) {
                // ignore
            }
        }
        return cached
    }

    suspend fun updateGroupAvatarIfNeeded(chatId: String, avatarUrl: String?, context: Context): Boolean {
        if (avatarUrl.isNullOrBlank()) return false
        return updateAvatarIfNeeded(groupIconCacheId(chatId), avatarUrl, context)
    }
    
    /**
     * Preload avatars for a list of users in the background.
     * Non-blocking, best-effort operation.
     */
    suspend fun preloadAvatars(users: List<Pair<String, String>>, context: Context) {
        withContext(Dispatchers.IO) {
            users.forEach { (userId, avatarUrl) ->
                try {
                    if (avatarUrl.isNotEmpty()) {
                        enqueueAvatarIntoCoilMemory(
                            context = context,
                            data = avatarUrl,
                            cacheKey = buildAvatarCacheKey(userId, null, avatarUrl) ?: avatarUrl
                        )
                    }

                    if (!hasLocalAvatar(userId) && avatarUrl.isNotEmpty()) {
                        cacheAvatar(userId, avatarUrl, context)
                    }

                    val localAvatarPath = getLocalAvatarPath(userId)
                    val localCacheKey = buildAvatarCacheKey(userId, localAvatarPath, avatarUrl)
                    if (localAvatarPath != null && localCacheKey != null) {
                        enqueueAvatarIntoCoilMemory(
                            context = context,
                            data = File(localAvatarPath),
                            cacheKey = localCacheKey
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error preloading avatar for user $userId", e)
                    // Continue with next avatar
                }
            }
        }
    }

    suspend fun preloadGroupAvatars(groups: List<Pair<String, String>>, context: Context) {
        withContext(Dispatchers.IO) {
            groups.forEach { (chatId, avatarUrl) ->
                try {
                    if (avatarUrl.isBlank()) return@forEach

                    val cacheId = groupIconCacheId(chatId)
                    val localAvatarPath = getLocalAvatarPath(cacheId)
                    if (localAvatarPath == null) {
                        cacheAvatar(cacheId, avatarUrl, context)
                    } else {
                        updateAvatarIfNeeded(cacheId, avatarUrl, context)
                    }

                    val refreshedLocalPath = getLocalAvatarPath(cacheId)
                    val localCacheKey = buildAvatarCacheKey(cacheId, refreshedLocalPath, avatarUrl)
                    if (refreshedLocalPath != null && localCacheKey != null) {
                        enqueueAvatarIntoCoilMemory(
                            context = context,
                            data = File(refreshedLocalPath),
                            cacheKey = localCacheKey
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error preloading group avatar for chat $chatId", e)
                }
            }
        }
    }
    
    /**
     * Clear avatar cache for a specific user.
     */
    fun clearAvatarCache(userId: String) {
        if (!::avatarsDir.isInitialized) return
        
        val avatarFile = File(avatarsDir, "${userId}.jpg")
        val metaFile = File(avatarsDir, "${userId}${METADATA_SUFFIX}")
        
        avatarFile.delete()
        metaFile.delete()
        localAvatarPathIndex.remove(userId)
        
    }

    fun clearGroupAvatarCache(chatId: String) {
        clearAvatarCache(groupIconCacheId(chatId))
    }
    
    /**
     * Clear all cached avatars. Use sparingly.
     */
    fun clearAllAvatars() {
        if (!::avatarsDir.isInitialized) return
        
        avatarsDir.listFiles()?.forEach { it.delete() }
        localAvatarPathIndex.clear()
    }
    
    /**
     * Get cache statistics for debugging.
     */
    fun getCacheStats(): Map<String, Any> {
        if (!::avatarsDir.isInitialized) {
            return mapOf("initialized" to false)
        }
        
        val files = avatarsDir.listFiles()?.filter { it.extension == "jpg" } ?: emptyList()
        val totalSize = files.sumOf { it.length() }
        
        return mapOf(
            "initialized" to true,
            "avatar_count" to files.size,
            "total_size_bytes" to totalSize,
            "total_size_mb" to (totalSize / 1024.0 / 1024.0),
            "directory" to avatarsDir.absolutePath
        )
    }
    
    // ==================== PRIVATE HELPERS ====================
    
    private data class AvatarMetadata(
        val urlHash: String?,
        val remoteMd5: String?,
        val remoteUpdatedTimeMs: Long?
    )

    private data class RemoteSignature(
        val md5: String?,
        val updatedTimeMs: Long?
    )

    private fun rebuildLocalAvatarIndex() {
        localAvatarPathIndex.clear()
        avatarsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) && it.length() > 0 }
            ?.forEach { file ->
                localAvatarPathIndex[file.nameWithoutExtension] = file.absolutePath
            }
    }

    private fun groupIconCacheId(chatId: String): String = "$GROUP_ICON_PREFIX$chatId"

    private fun enqueueAvatarIntoCoilMemory(context: Context, data: Any, cacheKey: String) {
        runCatching {
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .crossfade(false)
                    .build()
            )
        }.onFailure { e ->
        }
    }

    private fun readMetadata(metaFile: File): AvatarMetadata? {
        return try {
            if (!metaFile.exists()) return null
            val raw = metaFile.readText().trim()

            // Back-compat: old format stored just urlHash.
            if (!raw.contains("=")) {
                return AvatarMetadata(urlHash = raw.ifEmpty { null }, remoteMd5 = null, remoteUpdatedTimeMs = null)
            }

            val map = raw
                .lineSequence()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@mapNotNull null
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    key to value
                }
                .toMap()

            AvatarMetadata(
                urlHash = map["urlHash"].takeUnless { it.isNullOrEmpty() },
                remoteMd5 = map["remoteMd5"].takeUnless { it.isNullOrEmpty() },
                remoteUpdatedTimeMs = map["remoteUpdatedTimeMs"]?.toLongOrNull()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading avatar metadata from ${metaFile.absolutePath}", e)
            null
        }
    }

    private fun writeMetadata(
        metaFile: File,
        urlHash: String,
        remoteMd5: String?,
        remoteUpdatedTimeMs: Long?
    ) {
        try {
            val content = buildString {
                append("urlHash=").append(urlHash).append('\n')
                append("remoteMd5=").append(remoteMd5.orEmpty()).append('\n')
                append("remoteUpdatedTimeMs=").append(remoteUpdatedTimeMs?.toString().orEmpty()).append('\n')
            }
            metaFile.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving avatar metadata to ${metaFile.absolutePath}", e)
        }
    }

    private suspend fun fetchRemoteSignature(avatarUrl: String): RemoteSignature? {
        return try {
            val ref = FirebaseStorage.getInstance().getReferenceFromUrl(avatarUrl)
            val metadata = ref.metadata.await()
            RemoteSignature(
                md5 = metadata.md5Hash,
                updatedTimeMs = metadata.updatedTimeMillis
            )
        } catch (e: StorageException) {
            if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) {
                null
            } else {
                Log.w(TAG, "Remote metadata fetch failed (non-fatal)", e)
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote metadata fetch failed (non-fatal)", e)
            null
        }
    }

    private suspend fun downloadAvatarToFileFresh(
        avatarUrl: String,
        tempFile: File,
        context: Context
    ): Boolean {
        // 1) Prefer FirebaseStorage download to bypass Glide's HTTP/disk cache when the URL is stable.
        try {
            val ref = FirebaseStorage.getInstance().getReferenceFromUrl(avatarUrl)
            val maxBytes = 15L * 1024L * 1024L // 15MB safety cap
            val bytes = ref.getBytes(maxBytes).await()
            FileOutputStream(tempFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            return true
        } catch (e: Exception) {
            // Not a Firebase Storage URL or metadata/permission issue; fallback to Glide.
            Log.w(TAG, "FirebaseStorage download failed; falling back to Glide", e)
        }

        // 2) Fallback: Glide download with caching disabled.
        return try {
            val bitmap = Glide.with(context.applicationContext)
                .asBitmap()
                .load(avatarUrl)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .submit()
                .get()

            if (bitmap == null) {
                Log.w(TAG, "Glide returned null bitmap")
                return false
            }

            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Glide download failed", e)
            false
        }
    }
    
    private fun hashUrl(url: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val hash = digest.digest(url.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Fallback to simple hashCode if MD5 fails
            url.hashCode().toString()
        }
    }
}
