package com.glyph.glyph_v3.data.backup

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.data.auth.GoogleSignInRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WorkManager CoroutineWorker that restores a backup from Google Drive.
 *
 * Downloads the latest backup, validates integrity, decrypts, decompresses,
 * and restores all data into the local database and filesystem.
 */
class RestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "RestoreWorker"

        fun enqueueRestore(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag("glyph_restore")
                .build()

            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Enqueued restore work: ${request.id}")
            return request.id
        }

        fun observeProgress(context: Context, workId: UUID): kotlinx.coroutines.flow.Flow<RestoreProgress> {
            return WorkManager.getInstance(context).getWorkInfoByIdLiveData(workId)
                .let { liveData ->
                    kotlinx.coroutines.flow.callbackFlow {
                        val observer = androidx.lifecycle.Observer<androidx.work.WorkInfo> { info ->
                            if (info != null) {
                                val progress = RestoreProgress(
                                    state = when (info.state) {
                                        androidx.work.WorkInfo.State.ENQUEUED -> "enqueued"
                                        androidx.work.WorkInfo.State.RUNNING -> "running"
                                        androidx.work.WorkInfo.State.SUCCEEDED -> "completed"
                                        androidx.work.WorkInfo.State.FAILED -> "failed"
                                        androidx.work.WorkInfo.State.BLOCKED -> "blocked"
                                        androidx.work.WorkInfo.State.CANCELLED -> "cancelled"
                                    },
                                    progress = info.progress.getFloat("progress", 0f),
                                    stage = info.progress.getString("stage") ?: ""
                                )
                                trySend(progress)
                                if (info.state.isFinished) {
                                    close()
                                }
                            }
                        }
                        liveData.observeForever(observer)
                        awaitClose { liveData.removeObserver(observer) }
                    }
                }
        }
    }

    data class RestoreProgress(
        val state: String,
        val progress: Float,
        val stage: String
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val appContext = applicationContext
        val tracker = BackupProgressTracker
        Log.d(TAG, "RestoreWorker started")

        try {
            // 1. Verify Google account signed in
            val googleRepo = GoogleSignInRepository.getInstance(appContext)
            val account = googleRepo.silentSignIn()
            if (account == null) {
                return@withContext Result.failure(Data.Builder().putString("error", "no_google_account").build())
            }

            // 2. Initialize Drive
            val credential = googleRepo.getDriveCredential(account)
            val driveRepo = DriveRepository.getInstance(appContext)
            driveRepo.init(account, credential)

            // 3. List backups
            tracker.startRestore(appContext)
            tracker.updateProgress(appContext, 0f, "listing_backups")
            val backups = driveRepo.listBackups()
            if (backups.isEmpty()) {
                tracker.fail(appContext, "No backups found in your Google Drive")
                return@withContext Result.failure(Data.Builder().putString("error", "no_backups_found").build())
            }
            val latestBackup = backups.first()
            Log.d(TAG, "Found latest backup: ${latestBackup.fileName} (${latestBackup.size} bytes)")

            // 4. Download
            val restoreId = UUID.randomUUID().toString()
            val tempDir = File(appContext.cacheDir, "restore_temp_$restoreId")
            tempDir.mkdirs()
            val downloadFile = File(tempDir, "glyph_backup_download.bin")
            withPeriodicProgress(0.02f, 0.30f, "downloading") {
                driveRepo.downloadBackup(latestBackup.fileId, downloadFile)
            }
            tracker.updateProgress(appContext, 0.30f, "downloading",
                backupSizeBytes = downloadFile.length())

            // 5. Decrypt
            val keyManager = BackupKeyManager.getInstance(appContext)
            var decryptedData: ByteArray? = null
            var decryptionFailed = false
            withPeriodicProgress(0.30f, 0.40f, "decrypting") {
                try {
                    decryptedData = keyManager.decrypt(downloadFile.readBytes())
                } catch (e: Exception) {
                    Log.e(TAG, "Decryption failed", e)
                    decryptionFailed = true
                }
            }
            if (decryptionFailed || decryptedData == null) {
                tempDir.deleteRecursively()
                tracker.fail(appContext, "Decryption failed — the backup key may be from a different device")
                return@withContext Result.failure(Data.Builder().putString("error", "decryption_failed").build())
            }

            // 6. Decompress
            val extractDir = File(tempDir, "extracted"); extractDir.mkdirs()
            withPeriodicProgress(0.40f, 0.50f, "decompressing") {
                decompressArchive(decryptedData!!, extractDir)
            }

            // 7. Validate
            val importer = BackupImporter.getInstance(appContext)
            val validation = importer.validateBackup(extractDir)
            if (!validation.isValid) {
                tempDir.deleteRecursively()
                tracker.fail(appContext, validation.error ?: "Backup is corrupted or invalid")
                return@withContext Result.failure(Data.Builder().putString("error", validation.error ?: "invalid_backup").build())
            }
            Log.d(TAG, "Backup validated: ${validation.messageCount} msgs / ${validation.chatCount} chats")
            tracker.updateProgress(appContext, 0.50f, "validating")

            // 8. Restore messages
            val db = (appContext as GlyphApplication).getOrCreateAppDatabase()
            withPeriodicProgress(0.52f, 0.78f, "restoring_messages") {
                importer.importMessages(db, extractDir)
            }
            tracker.updateProgress(appContext, 0.80f, "restoring_messages",
                messagesProcessed = validation.messageCount)

            clearTombstonesForRestoredMessages(db)

            // 9. Restore chats + call logs + AI + deleted
            importer.importChats(db, extractDir)
            importer.importCallLogs(db, extractDir)
            importer.importAiMessages(db, extractDir)
            importer.importDeletedMessages(db, extractDir)

            // 10. Restore settings
            importer.importAppSettings(extractDir)
            importer.importChatSettings(extractDir)
            tracker.updateProgress(appContext, 0.85f, "restoring_settings")

            // 11. Restore media
            val mediaDir = File(appContext.filesDir, "media")
            withPeriodicProgress(0.85f, 0.96f, "restoring_media") {
                importer.importMedia(extractDir, mediaDir)
            }
            tracker.updateProgress(appContext, 0.97f, "restoring_media",
                mediaFilesProcessed = validation.mediaCount)

            // 12. Repair isIncoming on restored messages
            tracker.updateProgress(appContext, 0.98f, "repairing")
            repairMessageIncomingFlags(db, appContext)

            // 13. Cleanup
            tempDir.deleteRecursively()
            BackupPreferences.setLastBackupTime(appContext, validation.backupTime)
            BackupPreferences.setLastBackupSize(appContext, validation.totalSizeBytes)
            tracker.completeRestore(appContext, validation.totalSizeBytes)
            setProgress(Data.Builder().putFloat("progress", 1f).putString("stage", "complete").build())
            Log.d(TAG, "Restore completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            tracker.fail(appContext, e.message ?: "Unknown error")
            Result.failure(Data.Builder().putString("error", e.message ?: "Unknown error").build())
        }
    }

    /**
     * Clears tombstones for all restored messages so that messages deleted locally
     * before restore are visible again after the backup is imported.
     */
    private fun clearTombstonesForRestoredMessages(
        db: com.glyph.glyph_v3.data.local.AppDatabase
    ) {
        try {
            val supportDb = db.openHelper.writableDatabase
            supportDb.execSQL("DELETE FROM deleted_messages")
            Log.d(TAG, "Cleared all deleted_messages tombstones for restore")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear tombstones", e)
        }
    }

    /**
     * Repairs the isIncoming flag on all restored messages by comparing senderId
     * with the current Firebase user's UID. This is needed because the JSON importer
     * reads isIncoming as INTEGER (0/1) — if an older version of the importer used
     * optBoolean (which always returns false for numbers), all messages would have
     * isIncoming=false, causing them to display on the wrong side.
     */
    private suspend fun repairMessageIncomingFlags(
        db: com.glyph.glyph_v3.data.local.AppDatabase,
        context: Context
    ) {
        try {
            val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
            val supportDb = db.openHelper.writableDatabase
            // Set isIncoming=1 where senderId is NOT the current user
            supportDb.execSQL(
                "UPDATE messages SET isIncoming = 1 WHERE isIncoming = 0 AND senderId != ?",
                arrayOf(currentUid)
            )
            // Set isIncoming=0 where senderId IS the current user
            supportDb.execSQL(
                "UPDATE messages SET isIncoming = 0 WHERE isIncoming = 1 AND senderId = ?",
                arrayOf(currentUid)
            )
            Log.d(TAG, "Repaired isIncoming flags using currentUid=$currentUid")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to repair isIncoming flags", e)
        }
    }

    private suspend fun withPeriodicProgress(
        startPct: Float,
        endPct: Float,
        stage: String,
        block: suspend () -> Unit
    ) {
        val tracker = BackupProgressTracker
        val appContext = applicationContext
        val startTime = System.currentTimeMillis()
        var job: Job? = null
        job = CoroutineScope(Dispatchers.IO).launch {
            var estimatedDuration = 60000L
            while (true) {
                delay(500)
                val elapsed = System.currentTimeMillis() - startTime
                val interpolated = startPct + ((endPct - startPct) *
                    (elapsed.toFloat() / estimatedDuration.toFloat())).coerceIn(0f, 0.95f)
                tracker.updateProgress(appContext, interpolated.coerceAtMost(endPct - 0.01f), stage)
            }
        }
        try { block() } finally { job.cancel() }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            1002,
            android.app.Notification.Builder(applicationContext, "glyph_wakeup_channel")
                .setContentTitle("Restoring backup...")
                .setContentText("Restoring your chats from Google Drive")
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setOngoing(true)
                .build()
        )
    }

    /**
     * Decompress the archive format produced by BackupExporter.createBackupPackage.
     */
    private fun decompressArchive(data: ByteArray, outputDir: File) {
        // The archive was GZIP compressed
        val decompressed = java.io.ByteArrayInputStream(data).use { input ->
            java.util.zip.GZIPInputStream(input).use { gzip ->
                gzip.readBytes()
            }
        }

        // Parse the archive format
        val buffer = decompressed
        var offset = 0

        // Read file count (4 bytes)
        val fileCount = ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
        offset += 4

        for (i in 0 until fileCount) {
            // Read name length (4 bytes)
            val nameLen = ((buffer[offset].toInt() and 0xFF) shl 24) or
                ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
                ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
                (buffer[offset + 3].toInt() and 0xFF)
            offset += 4

            // Read name
            val name = String(buffer, offset, nameLen, Charsets.UTF_8)
            offset += nameLen

            // Read file size (8 bytes)
            var fileSize = 0L
            for (j in 0..7) {
                fileSize = (fileSize shl 8) or (buffer[offset + j].toLong() and 0xFF)
            }
            offset += 8

            // Read file data
            val fileData = buffer.copyOfRange(offset, offset + fileSize.toInt())
            offset += fileSize.toInt()

            // Write to output
            val outFile = File(outputDir, name)
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(fileData)
        }

        Log.d(TAG, "Decompressed $fileCount files to $outputDir")
    }
}
