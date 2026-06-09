package com.glyph.glyph_v3.data.cache

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that cleans up expired status cache files.
 *
 * Runs every 6 hours to:
 * 1. Delete cached media for statuses past their 24-hour expiry
 * 2. Remove orphan files not tracked in the database
 * 3. Enforce max cache size (200 MB)
 */
class StatusCacheCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {

            // Initialize if needed (handles app restart edge case)
            StatusCacheManager.init(applicationContext)

            // 1. Remove expired entries + files
            StatusCacheManager.cleanupExpired()

            // 2. Enforce size limit
            StatusCacheManager.enforceSizeLimit()

            // 3. Clean orphan files in cache directory
            cleanOrphanFiles()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Status cache cleanup failed", e)
            Result.retry()
        }
    }

    private suspend fun cleanOrphanFiles() {
        try {
            val cacheDir = java.io.File(applicationContext.filesDir, "status_cache")
            if (!cacheDir.exists()) return

            val files = cacheDir.listFiles() ?: return
            for (file in files) {
                // Extract statusId from filename (e.g., "abc123.jpg" → "abc123")
                val statusId = file.nameWithoutExtension.removeSuffix("_thumb")
                if (!StatusCacheManager.isCached(statusId)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Orphan file cleanup failed", e)
        }
    }

    companion object {
        private const val TAG = "StatusCacheCleanup"
        private const val WORK_NAME = "status_cache_cleanup"

        /**
         * Schedule periodic cleanup. Safe to call multiple times —
         * KEEP policy ensures only one instance runs.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StatusCacheCleanupWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES // flex interval
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
