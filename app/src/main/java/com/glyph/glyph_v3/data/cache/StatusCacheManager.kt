package com.glyph.glyph_v3.data.cache

import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.os.PowerManager
import android.content.Context
import android.util.Log
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.local.dao.StatusCacheDao
import com.glyph.glyph_v3.data.local.entity.CachedStatus
import com.glyph.glyph_v3.data.models.Status
import com.glyph.glyph_v3.data.models.StatusType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.net.URL

/**
 * Manages local disk caching of status media files.
 *
 * Architecture:
 * - Media files stored in `filesDir/status_cache/` (survives cache clears)
 * - Room DB tracks metadata (statusId → localPath, expiresAt)
 * - On view: check local → download if missing → return local path
 * - Cleanup: delete files + DB rows where expiresAt < now
 * - Max cache size: 200 MB (evict oldest when exceeded)
 */
object StatusCacheManager {
    private const val TAG = "StatusCacheManager"
    private const val CACHE_DIR = "status_cache"
    private const val MAX_CACHE_BYTES = 200L * 1024 * 1024 // 200 MB

    private lateinit var dao: StatusCacheDao
    private lateinit var cacheDir: File
    private lateinit var appContext: Context
    private val statusDownloadLocks = ConcurrentHashMap<String, Mutex>()

    fun init(context: Context) {
        appContext = context.applicationContext
        dao = AppDatabase.getDatabase(context).statusCacheDao()
        cacheDir = File(appContext.filesDir, CACHE_DIR)
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    /**
     * Get the local file path for a status's media.
     * Downloads the file if not already cached.
     *
     * @return absolute path to the local file, or null if download failed
     */
    suspend fun getLocalPath(status: Status): String? {
        if (status.type == StatusType.TEXT) return null
        val url = status.mediaUrl
        if (url.isBlank()) return null

        return withStatusDownloadLock(status.id) {
            // Check DB first
            val cached = dao.getByStatusId(status.id)
            if (cached != null && cached.remoteUrl == status.mediaUrl && cached.expiresAt == status.expiresAt) {
                val file = File(cached.localPath)
                if (file.exists()) {
                    return@withStatusDownloadLock cached.localPath
                }
                // File missing on disk — re-download
                dao.deleteByStatusId(status.id)
            } else if (cached != null) {
                deleteEntryFiles(cached)
                dao.deleteByStatusId(status.id)
            }

            // Download
            downloadAndCache(status)
        }
    }

    /**
     * Get local thumbnail path for video statuses.
     * Falls back to the main local path if no separate thumbnail.
     */
    suspend fun getLocalThumbnailPath(status: Status): String? {
        if (status.type != StatusType.VIDEO) return null
        val cached = dao.getByStatusId(status.id) ?: return null
        val thumbFile = File(cached.localThumbnailPath)
        if (thumbFile.exists()) return cached.localThumbnailPath
        // Fallback to main file path (thumbnailUrl == mediaUrl currently)
        val mainFile = File(cached.localPath)
        return if (mainFile.exists()) cached.localPath else null
    }

    /**
     * Check if a status is already cached locally without downloading.
     */
    suspend fun isCached(statusId: String): Boolean {
        val cached = dao.getByStatusId(statusId) ?: return false
        return File(cached.localPath).exists()
    }

    suspend fun isReadyForPlayback(status: Status): Boolean {
        if (status.type == StatusType.TEXT) return true
        val cached = dao.getByStatusId(status.id) ?: return false
        return cached.remoteUrl == status.mediaUrl &&
            cached.expiresAt == status.expiresAt &&
            File(cached.localPath).exists()
    }

    suspend fun filterReadyStatuses(statuses: List<Status>): List<Status> {
        if (statuses.isEmpty()) return emptyList()
        val mediaStatuses = statuses.filter { it.type != StatusType.TEXT }
        val cacheEntriesById = dao.getByStatusIds(mediaStatuses.map { it.id })
            .associateBy { it.statusId }

        return statuses.filter { status ->
            if (status.type == StatusType.TEXT) {
                true
            } else {
                val cached = cacheEntriesById[status.id]
                cached != null &&
                    cached.remoteUrl == status.mediaUrl &&
                    cached.expiresAt == status.expiresAt &&
                    File(cached.localPath).exists()
            }
        }
    }

    suspend fun prefetchForDelivery(statuses: List<Status>): Boolean {
        if (statuses.isEmpty()) return false
        if (!canRunBackgroundPrefetch()) return false

        var downloadedAny = false
        withContext(Dispatchers.IO) {
            for (status in statuses) {
                if (!shouldPrefetch(status)) continue
                try {
                    val existing = isReadyForPlayback(status)
                    if (existing) continue
                    val localPath = getLocalPath(status)
                    if (!localPath.isNullOrEmpty()) {
                        downloadedAny = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Delivery prefetch failed for ${status.id}", e)
                }
            }
        }
        return downloadedAny
    }

    /**
     * Pre-cache the next N statuses in a list for smooth viewing.
     * Called when a user opens a status group.
     */
    suspend fun prefetch(statuses: List<Status>) {
        withContext(Dispatchers.IO) {
            for (s in statuses) {
                if (s.type == StatusType.TEXT) continue
                try {
                    getLocalPath(s)
                } catch (e: Exception) {
                    Log.w(TAG, "Prefetch failed for ${s.id}", e)
                }
            }
        }
    }

    /**
     * Delete all expired status cache entries and their files.
     */
    suspend fun cleanupExpired() {
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val expired = dao.getExpired(now)
                var deletedCount = 0
                for (entry in expired) {
                    deleteEntryFiles(entry)
                    deletedCount++
                }
                dao.deleteExpired(now)
                if (deletedCount > 0) {
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup failed", e)
            }
        }
    }

    /**
     * Seed the cache with a file that was just uploaded by the current user.
     * Called immediately after a successful upload so the uploader can play
     * their own status instantly from local storage without re-downloading.
     *
     * @param localFile  The compressed/source file that was uploaded (will be copied).
     * @param thumbBytes JPEG thumbnail bytes for video statuses (may be null).
     */
    suspend fun seedFromUpload(
        statusId: String,
        userId: String,
        type: StatusType,
        mediaUrl: String,
        localFile: File,
        thumbBytes: ByteArray?,
        expiresAt: Long
    ) {
        if (type == StatusType.TEXT) return
        withContext(Dispatchers.IO) {
            try {
                val ext = when (type) {
                    StatusType.IMAGE -> "jpg"
                    StatusType.VIDEO -> "mp4"
                    StatusType.VOICE -> "m4a"
                    else -> return@withContext
                }
                val destFile = File(cacheDir, "$statusId.$ext")
                // Copy — worker still needs to delete its own temp file
                localFile.copyTo(destFile, overwrite = true)

                var thumbPath = ""
                if (type == StatusType.VIDEO && thumbBytes != null && thumbBytes.isNotEmpty()) {
                    val thumbFile = File(cacheDir, "${statusId}_thumb.jpg")
                    thumbFile.writeBytes(thumbBytes)
                    thumbPath = thumbFile.absolutePath
                }

                val entry = CachedStatus(
                    statusId = statusId,
                    userId = userId,
                    type = type.name,
                    remoteUrl = mediaUrl,
                    localPath = destFile.absolutePath,
                    localThumbnailPath = thumbPath,
                    expiresAt = expiresAt,
                    fileSize = destFile.length()
                )
                dao.upsert(entry)
                Log.d(TAG, "Seeded own-upload cache for $statusId (${destFile.length()} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "seedFromUpload failed for $statusId", e)
            }
        }
    }

    /**
     * Remove a specific status from cache (e.g., when deleted remotely).
     */
    suspend fun evict(statusId: String) {
        try {
            val cached = dao.getByStatusId(statusId) ?: return
            deleteEntryFiles(cached)
            dao.deleteByStatusId(statusId)
        } catch (e: Exception) {
            Log.w(TAG, "Evict failed for $statusId", e)
        }
    }

    /**
     * Enforce max cache size. Evicts oldest entries first.
     */
    suspend fun enforceSizeLimit() {
        withContext(Dispatchers.IO) {
            try {
                var totalSize = dao.totalCacheSize()
                if (totalSize <= MAX_CACHE_BYTES) return@withContext

                val count = dao.count()
                // Evict in batches of 10% of total entries
                val batchSize = (count * 0.1f).toInt().coerceAtLeast(1)
                while (totalSize > MAX_CACHE_BYTES) {
                    val oldest = dao.getOldest(batchSize)
                    dao.deleteOldest(batchSize)
                    // Delete associated files
                    for (entry in oldest) {
                        deleteEntryFiles(entry)
                    }
                    totalSize = dao.totalCacheSize()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Size limit enforcement failed", e)
            }
        }
    }

    /**
     * Sync local cache with a list of currently-valid status IDs.
     * Removes any cached entries not present in the valid set.
     */
    suspend fun syncWithRemote(validStatusIds: Set<String>) {
        withContext(Dispatchers.IO) {
            try {
                val allEntries = dao.getAll()
                val now = System.currentTimeMillis()
                val staleEntries = allEntries.filter { entry ->
                    entry.expiresAt <= now || entry.statusId !in validStatusIds
                }
                if (staleEntries.isEmpty()) return@withContext

                for (entry in staleEntries) {
                    deleteEntryFiles(entry)
                }
                dao.deleteByStatusIds(staleEntries.map { it.statusId })
            } catch (e: Exception) {
                Log.e(TAG, "Sync cleanup failed", e)
            }
        }
    }

    // ── Internal ──

    private suspend fun downloadAndCache(status: Status): String? {
        return withContext(Dispatchers.IO) {
            try {
                val ext = when (status.type) {
                    StatusType.IMAGE -> inferImageExtension(status.mediaUrl)
                    StatusType.VIDEO -> "mp4"
                    StatusType.VOICE -> "m4a"
                    else -> return@withContext null
                }
                val fileName = "${status.id}.$ext"
                val destFile = File(cacheDir, fileName)

                // Download
                downloadFile(status.mediaUrl, destFile)

                if (!destFile.exists() || destFile.length() == 0L) {
                    Log.w(TAG, "Download produced empty file for ${status.id}")
                    return@withContext null
                }

                // Also cache thumbnail if different from main URL
                var thumbPath = ""
                if (status.type == StatusType.VIDEO &&
                    status.thumbnailUrl.isNotEmpty() &&
                    status.thumbnailUrl != status.mediaUrl
                ) {
                    val thumbFile = File(cacheDir, "${status.id}_thumb.jpg")
                    try {
                        downloadFile(status.thumbnailUrl, thumbFile)
                        if (thumbFile.exists() && thumbFile.length() > 0) {
                            thumbPath = thumbFile.absolutePath
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Thumbnail download failed for ${status.id}", e)
                    }
                }

                // Persist to Room
                val entry = CachedStatus(
                    statusId = status.id,
                    userId = status.userId,
                    type = status.type.name,
                    remoteUrl = status.mediaUrl,
                    localPath = destFile.absolutePath,
                    localThumbnailPath = thumbPath,
                    expiresAt = status.expiresAt,
                    fileSize = destFile.length()
                )
                dao.upsert(entry)

                // Enforce size limit in background
                enforceSizeLimit()

                destFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download status ${status.id}", e)
                null
            }
        }
    }

    private fun inferImageExtension(url: String): String {
        val urlExt = url.substringBefore('?').substringAfterLast('.').lowercase()
        return when (urlExt) {
            "gif" -> "gif"
            "webp" -> "webp"
            "png" -> "png"
            else -> "jpg"
        }
    }

    private fun downloadFile(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmpFile = File(dest.parent, "${dest.name}.tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP ${connection.responseCode}")
            }

            connection.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            // Atomic rename
            if (!tmpFile.renameTo(dest)) {
                tmpFile.copyTo(dest, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    private suspend fun <T> withStatusDownloadLock(statusId: String, block: suspend () -> T): T {
        val mutex = statusDownloadLocks.getOrPut(statusId) { Mutex() }
        return try {
            mutex.withLock { block() }
        } finally {
            if (!mutex.isLocked) {
                statusDownloadLocks.remove(statusId, mutex)
            }
        }
    }

    private fun shouldPrefetch(status: Status): Boolean {
        if (status.type == StatusType.TEXT) return false
        if (status.expiresAt <= System.currentTimeMillis()) return false

        val connectivityManager = appContext.getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isMetered = connectivityManager?.isActiveNetworkMetered ?: true
        return !(isMetered && status.type == StatusType.VIDEO)
    }

    private fun canRunBackgroundPrefetch(): Boolean {
        val connectivityManager = appContext.getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (!capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isPowerSaveMode != true
    }

    private fun deleteEntryFiles(entry: CachedStatus) {
        File(entry.localPath).delete()
        if (entry.localThumbnailPath.isNotEmpty()) {
            File(entry.localThumbnailPath).delete()
        }
    }
}
