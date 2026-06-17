package com.glyph.glyph_v3.data.backup

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Persists detailed backup/restore progress via DataStore so the UI can observe
 * real-time progress and restore state after process death or backgrounding.
 *
 * The WorkManager writes progress updates; the UI observes them via [progressState].
 */
object BackupProgressTracker {

    private val Context.progressDataStore by preferencesDataStore(name = "backup_progress")

    // ── Progress keys ──────────────────────────────────────────────
    private val KEY_OPERATION_ID = stringPreferencesKey("progress_operation_id")
    private val KEY_OPERATION_TYPE = stringPreferencesKey("progress_operation_type") // "backup" | "restore"
    private val KEY_IS_RUNNING = booleanPreferencesKey("progress_is_running")
    private val KEY_PROGRESS_PCT = floatPreferencesKey("progress_pct")
    private val KEY_STAGE = stringPreferencesKey("progress_stage")
    private val KEY_MESSAGES_PROCESSED = longPreferencesKey("progress_messages_processed")
    private val KEY_TOTAL_MESSAGES = longPreferencesKey("progress_total_messages")
    private val KEY_MEDIA_FILES_PROCESSED = intPreferencesKey("progress_media_files_processed")
    private val KEY_TOTAL_MEDIA_FILES = intPreferencesKey("progress_total_media_files")
    private val KEY_BACKUP_SIZE_BYTES = longPreferencesKey("progress_backup_size_bytes")
    private val KEY_UPLOADED_BYTES = longPreferencesKey("progress_uploaded_bytes")
    private val KEY_UPLOAD_SPEED_BPS = longPreferencesKey("progress_upload_speed_bps")
    private val KEY_STARTED_AT_MS = longPreferencesKey("progress_started_at_ms")
    private val KEY_COMPLETED_AT_MS = longPreferencesKey("progress_completed_at_ms")
    private val KEY_ERROR_MESSAGE = stringPreferencesKey("progress_error")
    private val KEY_GOOGLE_ACCOUNT = stringPreferencesKey("progress_google_account")
    private val KEY_RESULT_STATE = stringPreferencesKey("progress_result_state") // "running" | "success" | "failure" | "cancelled"

    // ── Data class ─────────────────────────────────────────────────
    data class BackupProgressState(
        val operationId: String = "",
        val operationType: String = "",
        val isRunning: Boolean = false,
        val progressPct: Float = 0f,
        val stage: String = "",
        val messagesProcessed: Long = 0L,
        val totalMessages: Long = 0L,
        val mediaFilesProcessed: Int = 0,
        val totalMediaFiles: Int = 0,
        val backupSizeBytes: Long = 0L,
        val uploadedBytes: Long = 0L,
        val uploadSpeedBps: Long = 0L,
        val startedAtMs: Long = 0L,
        val completedAtMs: Long = 0L,
        val errorMessage: String? = null,
        val googleAccount: String = "",
        val resultState: String = "" // "running" | "success" | "failure" | "cancelled"
    ) {
        val stageDisplayName: String
            get() = stageDisplayName(stage)

        val estimatedTimeRemainingSeconds: Long
            get() {
                if (progressPct <= 0f || progressPct >= 1f) return 0L
                val elapsed = System.currentTimeMillis() - startedAtMs
                if (elapsed <= 0L) return 0L
                val totalEstimated = (elapsed / progressPct).toLong()
                return ((totalEstimated - elapsed) / 1000L).coerceAtLeast(0L)
            }

        val formattedBackupSize: String
            get() = formatBytes(backupSizeBytes)

        val formattedUploadSpeed: String
            get() = formatBytesPerSecond(uploadSpeedBps)

        val formattedUploaded: String
            get() = formatBytes(uploadedBytes)

        companion object {
            fun formatBytes(bytes: Long): String = when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
                else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
            }

            fun formatBytesPerSecond(bps: Long): String = when {
                bps < 1024 -> "$bps B/s"
                bps < 1024 * 1024 -> "${"%.1f".format(bps.toDouble() / 1024)} KB/s"
                else -> "${"%.1f".format(bps.toDouble() / (1024 * 1024))} MB/s"
            }

            fun stageDisplayName(stage: String): String = when (stage) {
                "preparing" -> "Preparing backup..."
                "collecting_messages" -> "Collecting messages..."
                "exporting_media" -> "Exporting media files..."
                "creating_package" -> "Creating backup package..."
                "encrypting" -> "Encrypting data..."
                "compressing" -> "Compressing backup..."
                "uploading" -> "Uploading to Google Drive..."
                "verifying" -> "Verifying upload..."
                "complete" -> "Backup completed"
                "restoring_messages" -> "Restoring messages..."
                "restoring_media" -> "Restoring media files..."
                "restoring_settings" -> "Restoring settings..."
                "downloading" -> "Downloading backup..."
                "decrypting" -> "Decrypting backup..."
                "validating" -> "Validating backup..."
                else -> stage.replace("_", " ").replaceFirstChar { it.uppercase() }
            }
        }
    }

    // ── Flow observation ───────────────────────────────────────────
    fun progressState(context: Context): Flow<BackupProgressState> {
        return context.progressDataStore.data.map { prefs ->
            BackupProgressState(
                operationId = prefs[KEY_OPERATION_ID] ?: "",
                operationType = prefs[KEY_OPERATION_TYPE] ?: "",
                isRunning = prefs[KEY_IS_RUNNING] ?: false,
                progressPct = prefs[KEY_PROGRESS_PCT] ?: 0f,
                stage = prefs[KEY_STAGE] ?: "",
                messagesProcessed = prefs[KEY_MESSAGES_PROCESSED] ?: 0L,
                totalMessages = prefs[KEY_TOTAL_MESSAGES] ?: 0L,
                mediaFilesProcessed = prefs[KEY_MEDIA_FILES_PROCESSED] ?: 0,
                totalMediaFiles = prefs[KEY_TOTAL_MEDIA_FILES] ?: 0,
                backupSizeBytes = prefs[KEY_BACKUP_SIZE_BYTES] ?: 0L,
                uploadedBytes = prefs[KEY_UPLOADED_BYTES] ?: 0L,
                uploadSpeedBps = prefs[KEY_UPLOAD_SPEED_BPS] ?: 0L,
                startedAtMs = prefs[KEY_STARTED_AT_MS] ?: 0L,
                completedAtMs = prefs[KEY_COMPLETED_AT_MS] ?: 0L,
                errorMessage = prefs[KEY_ERROR_MESSAGE],
                googleAccount = prefs[KEY_GOOGLE_ACCOUNT] ?: "",
                resultState = prefs[KEY_RESULT_STATE] ?: ""
            )
        }
    }

    // ── Write (called from Worker) ─────────────────────────────────
    suspend fun startBackup(
        context: Context,
        totalMessages: Long = 0L,
        totalMediaFiles: Int = 0,
        googleAccount: String = ""
    ) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        Log.d("BackupProgressTracker", "startBackup: id=$id msgs=$totalMessages media=$totalMediaFiles account=$googleAccount")
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_OPERATION_ID, id)
                set(KEY_OPERATION_TYPE, "backup")
                set(KEY_IS_RUNNING, true)
                set(KEY_PROGRESS_PCT, 0f)
                set(KEY_STAGE, "preparing")
                set(KEY_MESSAGES_PROCESSED, 0L)
                set(KEY_TOTAL_MESSAGES, totalMessages)
                set(KEY_MEDIA_FILES_PROCESSED, 0)
                set(KEY_TOTAL_MEDIA_FILES, totalMediaFiles)
                set(KEY_BACKUP_SIZE_BYTES, 0L)
                set(KEY_UPLOADED_BYTES, 0L)
                set(KEY_UPLOAD_SPEED_BPS, 0L)
                set(KEY_STARTED_AT_MS, now)
                set(KEY_COMPLETED_AT_MS, 0L)
                remove(KEY_ERROR_MESSAGE)
                set(KEY_GOOGLE_ACCOUNT, googleAccount)
                set(KEY_RESULT_STATE, "running")
            }
        }
    }

    suspend fun updateProgress(
        context: Context,
        progressPct: Float,
        stage: String,
        messagesProcessed: Long? = null,
        mediaFilesProcessed: Int? = null,
        backupSizeBytes: Long? = null,
        uploadedBytes: Long? = null,
        uploadSpeedBps: Long? = null
    ) {
        Log.d("BackupProgressTracker", "updateProgress: pct=${(progressPct*100).toInt()}% stage=$stage msgs=$messagesProcessed media=$mediaFilesProcessed size=$backupSizeBytes")
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                if (progressPct > (prefs[KEY_PROGRESS_PCT] ?: 0f)) {
                    set(KEY_PROGRESS_PCT, progressPct)
                }
                set(KEY_STAGE, stage)
                if (messagesProcessed != null) set(KEY_MESSAGES_PROCESSED, messagesProcessed)
                if (mediaFilesProcessed != null) set(KEY_MEDIA_FILES_PROCESSED, mediaFilesProcessed)
                if (backupSizeBytes != null) set(KEY_BACKUP_SIZE_BYTES, backupSizeBytes)
                if (uploadedBytes != null) set(KEY_UPLOADED_BYTES, uploadedBytes)
                if (uploadSpeedBps != null) set(KEY_UPLOAD_SPEED_BPS, uploadSpeedBps)
            }
        }
    }

    suspend fun completeBackup(context: Context, backupSizeBytes: Long) {
        Log.d("BackupProgressTracker", "completeBackup: size=$backupSizeBytes")
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_IS_RUNNING, false)
                set(KEY_PROGRESS_PCT, 1f)
                set(KEY_STAGE, "complete")
                set(KEY_BACKUP_SIZE_BYTES, backupSizeBytes)
                set(KEY_COMPLETED_AT_MS, System.currentTimeMillis())
                set(KEY_RESULT_STATE, "success")
            }
        }
    }

    suspend fun fail(context: Context, errorMessage: String) {
        Log.e("BackupProgressTracker", "fail: $errorMessage")
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_IS_RUNNING, false)
                set(KEY_RESULT_STATE, "failure")
                set(KEY_ERROR_MESSAGE, errorMessage)
                set(KEY_COMPLETED_AT_MS, System.currentTimeMillis())
            }
        }
    }

    suspend fun markCancelled(context: Context) {
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_IS_RUNNING, false)
                set(KEY_RESULT_STATE, "cancelled")
                set(KEY_COMPLETED_AT_MS, System.currentTimeMillis())
            }
        }
    }

    suspend fun clear(context: Context) {
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_IS_RUNNING, false)
                set(KEY_RESULT_STATE, "")
                set(KEY_STAGE, "")
                set(KEY_PROGRESS_PCT, 0f)
                set(KEY_OPERATION_ID, "")
            }
        }
    }

    /**
     * Clears stale progress state if the backup/restore has been "running"
     * for more than [staleTimeoutMs] without completing. Called when the
     * backup screen opens to recover from crashed/killed worker processes.
     */
    suspend fun clearStaleIfNeeded(context: Context, staleTimeoutMs: Long = 3_600_000L) {
        val prefs = context.progressDataStore.data.first()
        val isRunning = prefs[KEY_IS_RUNNING] ?: false
        if (!isRunning) return
        val startedAt = prefs[KEY_STARTED_AT_MS] ?: 0L
        val elapsed = System.currentTimeMillis() - startedAt
        if (elapsed > staleTimeoutMs || startedAt == 0L) {
            Log.w("BackupProgressTracker", "Clearing stale progress state — elapsed=${elapsed/1000}s")
            clear(context)
        }
    }

    // ── Restore progress helpers ────────────────────────────────────
    suspend fun startRestore(
        context: Context,
        totalMessages: Long = 0L,
        totalMediaFiles: Int = 0
    ) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_OPERATION_ID, id)
                set(KEY_OPERATION_TYPE, "restore")
                set(KEY_IS_RUNNING, true)
                set(KEY_PROGRESS_PCT, 0f)
                set(KEY_STAGE, "downloading")
                set(KEY_MESSAGES_PROCESSED, 0L)
                set(KEY_TOTAL_MESSAGES, totalMessages)
                set(KEY_MEDIA_FILES_PROCESSED, 0)
                set(KEY_TOTAL_MEDIA_FILES, totalMediaFiles)
                set(KEY_STARTED_AT_MS, now)
                set(KEY_COMPLETED_AT_MS, 0L)
                remove(KEY_ERROR_MESSAGE)
                set(KEY_RESULT_STATE, "running")
            }
        }
    }

    suspend fun completeRestore(context: Context, backupSizeBytes: Long) {
        context.progressDataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(KEY_IS_RUNNING, false)
                set(KEY_PROGRESS_PCT, 1f)
                set(KEY_STAGE, "complete")
                set(KEY_BACKUP_SIZE_BYTES, backupSizeBytes)
                set(KEY_COMPLETED_AT_MS, System.currentTimeMillis())
                set(KEY_RESULT_STATE, "success")
            }
        }
    }
}
