package com.glyph.glyph_v3.data.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.glyph.glyph_v3.BuildConfig
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.auth.GoogleSignInRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager CoroutineWorker that performs a backup and uploads it to
 * Google Drive AppDataFolder. Runs fully in the background and survives
 * app closure via WorkManager's built-in guarantee.
 *
 * Progress is written to [BackupProgressTracker] so the UI can observe
 * real-time state even after the app is backgrounded or killed.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "glyph_backup_periodic"
        private const val TAG = "BackupWorker"
        private const val NOTIFICATION_CHANNEL_ID = "glyph_backup_channel"
        private const val NOTIFICATION_ID = 1001

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            // IMPORTANCE_DEFAULT is required on Android 14+ for foreground service notifications
            if (existing != null && existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) return
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Backup",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows backup progress"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }

        fun schedule(
            context: Context,
            frequency: BackupPreferences.BackupFrequency,
            networkPolicy: BackupPreferences.NetworkPolicy
        ) {
            ensureChannel(context)
            if (frequency == BackupPreferences.BackupFrequency.MANUAL) {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    when (networkPolicy) {
                        BackupPreferences.NetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
                        BackupPreferences.NetworkPolicy.WIFI_MOBILE -> NetworkType.CONNECTED
                    }
                )
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                frequency.durationMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("glyph_backup")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Scheduled periodic: ${frequency.displayName} / ${networkPolicy.displayName}")
        }

        fun enqueueManualBackup(context: Context) {
            ensureChannel(context)
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("glyph_backup_manual")
                .build()
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Enqueued manual backup ${request.id}")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            CoroutineScope(Dispatchers.IO).launch {
                BackupProgressTracker.markCancelled(context)
            }
            Log.d(TAG, "Cancelled periodic backup")
        }

        /** Cancel all running backup jobs and clear progress state. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag("glyph_backup_manual")
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            CoroutineScope(Dispatchers.IO).launch {
                BackupProgressTracker.clear(context)
            }
            Log.d(TAG, "Cancelled all backup jobs + cleared stale state")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val appContext = applicationContext
        val tracker = BackupProgressTracker
        val startTime = System.currentTimeMillis()
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        Log.d(TAG, "╔══════════════════════════════════════════╗")
        Log.d(TAG, "║  BackupWorker.doWork() STARTED          ║")
        Log.d(TAG, "╚══════════════════════════════════════════╝")

        // Guarantee the notification channel exists before posting foreground notification
        ensureChannel(appContext)
        postProgressNotification(nm, 0, "Preparing backup…")
        setForeground(createForegroundInfo("Preparing backup…"))

        try {
            // ═══ 1. Pre-flight ═══════════════════════════════════
            Log.d(TAG, "[1/8] Pre-flight: checking Google sign-in…")
            val googleRepo = GoogleSignInRepository.getInstance(appContext)
            val account = googleRepo.silentSignIn()
            if (account == null) {
                Log.e(TAG, "Pre-flight FAILED: no Google account signed in")
                tracker.fail(appContext, "No Google account signed in")
                return@withContext Result.failure(Data.Builder().putString("error", "no_google_account").build())
            }
            Log.d(TAG, "Pre-flight OK: ${account.email}")

            val credential = googleRepo.getDriveCredential(account)
            val driveRepo = DriveRepository.getInstance(appContext)
            driveRepo.init(account, credential)
            val includeVideos = BackupPreferences.includeVideosFlow(appContext).first()
            val accountEmail = account.email ?: ""
            Log.d(TAG, "includeVideos=$includeVideos")

            // ═══ 2. Prepare ═══════════════════════════════════════
            Log.d(TAG, "[2/8] Preparing backup temp dirs…")
            val backupId = UUID.randomUUID().toString()
            val tempDir = File(appContext.cacheDir, "backup_temp_$backupId")
            val exportDir = File(tempDir, "export")
            tempDir.mkdirs(); exportDir.mkdirs()
            Log.d(TAG, "Temp dir: ${tempDir.absolutePath}")

            val db = (appContext as GlyphApplication).getOrCreateAppDatabase()
            val exporter = BackupExporter.getInstance(appContext)
            val metadataManager = BackupMetadataManager.getInstance(appContext)
            val keyManager = BackupKeyManager.getInstance(appContext)

            // Count totals
            val totalMessageCount = countTotalMessages(db)
            val mediaDir = File(appContext.filesDir, "media")
            val totalMediaFiles = if (mediaDir.exists()) mediaDir.walkTopDown().count { it.isFile } else 0
            Log.d(TAG, "Totals: $totalMessageCount messages, $totalMediaFiles media files")

            tracker.startBackup(appContext, totalMessageCount, totalMediaFiles, accountEmail)
            Log.d(TAG, "BackupProgressTracker.startBackup() called")

            // ═══ 3. Export database ════════════════════════════════
            Log.d(TAG, "[3/8] Exporting database…")
            postProgressNotification(nm, 5, "Collecting messages…")
            var exportedMessageCount = 0L
            withPeriodicProgress(0.05f, 0.20f, "collecting_messages") {
                val messageFiles = exporter.exportMessages(db, File(exportDir, "messages"))
                Log.d(TAG, "Messages exported: ${messageFiles.size} chunk files")
                exportedMessageCount = messageFiles.sumOf { countMessagesInFile(it) }
                Log.d(TAG, "Total exported messages counted: $exportedMessageCount")
                tracker.updateProgress(appContext, 0.20f, "collecting_messages",
                    messagesProcessed = exportedMessageCount)

                exporter.exportChats(db, exportDir)
                exporter.exportCallLogs(db, exportDir)
                exporter.exportAiMessages(db, exportDir)
                exporter.exportDeletedMessages(db, exportDir)
            }

            // ═══ 4. Export settings ════════════════════════════════
            Log.d(TAG, "[4/8] Exporting settings…")
            postProgressNotification(nm, 25, "Exporting settings…")
            val settingsDir = File(exportDir, "settings"); settingsDir.mkdirs()
            exporter.exportChatSettings(appContext, settingsDir)
            exporter.exportWallpapers(appContext, settingsDir)

            // ═══ 5. Export media ═══════════════════════════════════
            Log.d(TAG, "[5/8] Exporting media (includeVideos=$includeVideos)…")
            postProgressNotification(nm, 28, "Exporting media…")
            var totalMediaExported = 0
            var exportedSize = 0L
            withPeriodicProgress(0.25f, 0.45f, "exporting_media") {
                val mediaFiles = exporter.exportMedia(mediaDir, exportDir, includeVideos)
                totalMediaExported = mediaFiles.size
                exportedSize = exportDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                Log.d(TAG, "Media exported: $totalMediaExported files, ${BackupProgressTracker.BackupProgressState.formatBytes(exportedSize)}")
            }
            tracker.updateProgress(appContext, 0.45f, "exporting_media",
                mediaFilesProcessed = totalMediaExported, backupSizeBytes = exportedSize)

            // ═══ 6. Compress & encrypt ════════════════════════════
            Log.d(TAG, "[6/8] Compressing & encrypting backup package…")
            postProgressNotification(nm, 48, "Compressing backup…")

            val previousHashes = metadataManager.loadPreviousHashes()
            val allExportedFiles = exportDir.walkTopDown()
                .filter { it.isFile && it.name != "backup_manifest.json" }.toList()
            val delta = metadataManager.detectChanges(allExportedFiles, previousHashes, exportedMessageCount, totalMediaExported)
            Log.d(TAG, "Delta: isFull=${delta.isFull}, changed=${delta.changedFiles.size} files, ${BackupProgressTracker.BackupProgressState.formatBytes(delta.totalSizeBytes)}")

            val manifest = metadataManager.generateManifest(
                backupId = backupId, timestamp = startTime, delta = delta,
                googleAccountEmail = accountEmail, appVersion = BuildConfig.VERSION_NAME,
                chatCount = db.chatDao().getAllChatsOnce().size
            )

            postProgressNotification(nm, 55, "Encrypting backup…")
            val encryptionKey = keyManager.getOrCreateEncryptionKey()
            val backupFile = File(tempDir, "glyph_backup_$backupId.bin")

            var finalBackupFile: File? = null
            var finalSize = 0L
            withPeriodicProgress(0.50f, 0.70f, "compressing") {
                finalBackupFile = exporter.createBackupPackage(
                    exportedDir = exportDir, manifest = manifest,
                    encryptionKey = encryptionKey, outputFile = backupFile
                )
                finalSize = finalBackupFile.length()
                Log.d(TAG, "Package created: ${BackupProgressTracker.BackupProgressState.formatBytes(finalSize)}")
            }
            tracker.updateProgress(appContext, 0.70f, "encrypting", backupSizeBytes = finalSize)

            // ═══ 7. Upload to Drive ═══════════════════════════════
            Log.d(TAG, "[7/8] Uploading to Google Drive (${BackupProgressTracker.BackupProgressState.formatBytes(finalSize)})…")
            postProgressNotification(nm, 72, "Uploading to Google Drive…")
            val uploadStartMs = System.currentTimeMillis()
            var fileId = ""
            withPeriodicProgress(0.72f, 0.92f, "uploading") {
                fileId = driveRepo.uploadBackup(finalBackupFile!!, manifest)
                val uploadElapsed = System.currentTimeMillis() - uploadStartMs
                val uploadSpeedBps = if (uploadElapsed > 0) (finalSize * 1000L) / uploadElapsed else 0L
                Log.d(TAG, "Upload complete: fileId=$fileId, elapsed=${uploadElapsed}ms, speed=${BackupProgressTracker.BackupProgressState.formatBytesPerSecond(uploadSpeedBps)}")
                tracker.updateProgress(appContext, 0.92f, "uploading",
                    uploadedBytes = finalSize, uploadSpeedBps = uploadSpeedBps)
            }

            // ═══ 8. Verify & finalize ═════════════════════════════
            Log.d(TAG, "[8/8] Verifying & finalizing…")
            postProgressNotification(nm, 95, "Verifying upload…")
            tracker.updateProgress(appContext, 0.95f, "verifying")
            val manifestJson = metadataManager.manifestToJson(manifest)
            driveRepo.uploadManifest(manifestJson, backupId)
            Log.d(TAG, "Manifest uploaded to Drive")

            val fileHashes = allExportedFiles.map { file ->
                BackupMetadataManager.FileHashEntry(
                    path = file.absolutePath, sha256 = metadataManager.computeFileHash(file),
                    lastModified = file.lastModified(), size = file.length()
                )
            }
            metadataManager.saveBackupState(fileHashes)
            Log.d(TAG, "Saved ${fileHashes.size} file hashes for next incremental backup")

            BackupPreferences.setLastBackupTime(appContext, System.currentTimeMillis())
            BackupPreferences.setLastBackupSize(appContext, finalSize)
            driveRepo.cleanupOldBackups()
            tempDir.deleteRecursively()
            Log.d(TAG, "Temp dir cleaned up, preferences updated")

            tracker.completeBackup(appContext, finalSize)
            Log.d(TAG, "╔══════════════════════════════════════════╗")
            Log.d(TAG, "║  BACKUP COMPLETE                        ║")
            Log.d(TAG, "║  Size:  ${BackupProgressTracker.BackupProgressState.formatBytes(finalSize)}")
            Log.d(TAG, "║  Msgs:  $exportedMessageCount")
            Log.d(TAG, "║  Media: $totalMediaExported files")
            Log.d(TAG, "║  Time:  ${(System.currentTimeMillis() - startTime) / 1000}s")
            Log.d(TAG, "╚══════════════════════════════════════════╝")

            showCompletionNotification(appContext, finalSize)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "╔══════════════════════════════════════════╗")
            Log.e(TAG, "║  BACKUP FAILED                          ║", e)
            Log.e(TAG, "╚══════════════════════════════════════════╝")
            tracker.fail(appContext, e.message ?: "Unknown error")
            showFailureNotification(appContext, e.message ?: "Unknown error")
            Result.failure(Data.Builder().putString("error", e.message ?: "Unknown error").build())
        }
    }

    /**
     * Runs [block] while periodically updating progress from [startPct] to [endPct].
     * Progress is interpolated over the duration of the block, with updates every 500ms.
     * This prevents the progress bar from appearing frozen during long operations.
     */
    private suspend fun withPeriodicProgress(
        startPct: Float,
        endPct: Float,
        stage: String,
        block: suspend () -> Unit
    ) {
        val tracker = BackupProgressTracker
        val appContext = applicationContext
        val startTime = System.currentTimeMillis()
        var progressJob: Job? = null

        // Launch a coroutine that periodically nudges progress forward
        progressJob = CoroutineScope(Dispatchers.IO).launch {
            var elapsed: Long
            var estimatedDuration = 60000L // initial guess: 60 seconds
            while (true) {
                delay(500)
                elapsed = System.currentTimeMillis() - startTime
                // Interpolate progress, but never go past 95% of the range
                val interpolated = startPct + ((endPct - startPct) *
                    (elapsed.toFloat() / estimatedDuration.toFloat())).coerceIn(0f, 0.95f)
                val finalPct = interpolated.coerceAtMost(endPct - 0.01f)
                tracker.updateProgress(appContext, finalPct, stage)
            }
        }

        try {
            block()
        } finally {
            progressJob.cancel()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo("Preparing backup…")
    }

    private fun createForegroundInfo(statusText: String): ForegroundInfo {
        val notification = android.app.Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Glyph Backup")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(android.app.Notification.PRIORITY_LOW)
            .build()
        // Android 14+ requires explicit foreground service type
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun notifyProgress(statusText: String) {
        setForeground(createForegroundInfo(statusText))
    }

    private fun postProgressNotification(nm: NotificationManager, percent: Int, status: String) {
        try {
            val notification = android.app.Notification.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Glyph Backup")
                .setContentText(status)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(android.app.Notification.PRIORITY_LOW)
                .setProgress(100, percent, false)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post progress notification", e)
        }
    }

    private fun showCompletionNotification(context: Context, sizeBytes: Long) {
        try {
            val sizeStr = BackupProgressTracker.BackupProgressState.formatBytes(sizeBytes)
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIFICATION_ID + 1,
                android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Backup complete")
                    .setContentText("$sizeStr saved to Google Drive")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {}
    }

    private fun showFailureNotification(context: Context, error: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIFICATION_ID + 1,
                android.app.Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Backup failed")
                    .setContentText(error.take(200))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {}
    }

    private fun countTotalMessages(db: com.glyph.glyph_v3.data.local.AppDatabase): Long {
        return try {
            val supportDb = db.openHelper.readableDatabase
            val cursor = supportDb.query(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM messages"))
            val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            cursor.close()
            count
        } catch (_: Exception) { 0L }
    }

    private fun countMessagesInFile(file: File): Long {
        return try {
            var count = 0L
            var idx = 0
            val content = file.readText()
            while (true) {
                idx = content.indexOf("\"id\":", idx)
                if (idx < 0) break
                count++; idx++
            }
            count
        } catch (_: Exception) { 0L }
    }
}
