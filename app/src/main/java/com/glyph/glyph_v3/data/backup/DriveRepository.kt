package com.glyph.glyph_v3.data.backup

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.FileContent
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.api.services.drive.model.FileList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages Google Drive AppDataFolder operations for backup storage.
 *
 * Files stored in AppDataFolder are hidden from the user and accessible
 * only by the app. Chunked resumable uploads and downloads for reliability.
 */
class DriveRepository(private val context: Context) {

    companion object {
        private const val TAG = "DriveRepository"
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val BACKUP_FILENAME_PREFIX = "glyph_backup_"
        private const val MANIFEST_FILENAME_PREFIX = "glyph_manifest_"
        private const val MAX_BACKUP_RETENTION = 3
        private const val CHUNK_SIZE = 256 * 1024 // 256KB

        @Volatile
        private var instance: DriveRepository? = null

        fun getInstance(context: Context): DriveRepository {
            return instance ?: synchronized(this) {
                instance ?: DriveRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    data class BackupMetadata(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val createdTime: Long,
        val modifiedTime: Long,
        val md5Checksum: String?
    )

    private var driveService: Drive? = null

    fun init(account: GoogleSignInAccount, credential: GoogleAccountCredential) {
        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Glyph").build()
        Log.d(TAG, "Drive service initialized for ${account.email}")
    }

    fun isInitialized(): Boolean = driveService != null

    /**
     * Upload a file to AppDataFolder and report progress.
     * Returns the Drive file ID.
     */
    suspend fun uploadFile(
        file: File,
        mimeType: String,
        progress: MutableStateFlow<Float> = MutableStateFlow(0f)
    ): String = withContext(Dispatchers.IO) {
        val service = driveService ?: throw IllegalStateException("Drive service not initialized")

        val fileMetadata = DriveFile().apply {
            name = file.name
            parents = listOf(APP_DATA_FOLDER)
        }

        val mediaContent = FileContent(mimeType, file)

        val request = service.files().create(fileMetadata, mediaContent)
        request.fields = "id, name, size, createdTime, modifiedTime, md5Checksum"

        // Track upload progress via callback
        val totalSize = file.length()
        request.mediaHttpUploader.apply {
            setDirectUploadEnabled(false)
            setChunkSize(CHUNK_SIZE)
            setProgressListener { uploader ->
                val uploaded = uploader.numBytesUploaded
                val pct = if (totalSize > 0) uploaded.toFloat() / totalSize else 0f
                progress.value = pct.coerceIn(0f, 1f)
            }
        }

        val driveFile = request.execute()
        Log.d(TAG, "Uploaded ${file.name} (${file.length()} bytes) -> ${driveFile.id}")
        driveFile.id
    }

    /**
     * Upload the backup package file with a timestamped name.
     */
    suspend fun uploadBackup(
        backupFile: File,
        manifest: BackupMetadataManager.BackupManifest,
        progress: MutableStateFlow<Float> = MutableStateFlow(0f)
    ): String {
        return uploadFile(backupFile, "application/octet-stream", progress)
    }

    /**
     * Upload the manifest file alongside the backup.
     */
    suspend fun uploadManifest(
        manifestJson: String,
        backupId: String
    ): String = withContext(Dispatchers.IO) {
        val service = driveService ?: throw IllegalStateException("Drive service not initialized")

        val fileMetadata = DriveFile().apply {
            name = "${MANIFEST_FILENAME_PREFIX}${backupId}.json"
            parents = listOf(APP_DATA_FOLDER)
        }

        val content = InputStreamContent("application/json", manifestJson.byteInputStream())
        val request = service.files().create(fileMetadata, content)
        request.fields = "id"
        val driveFile = request.execute()
        driveFile.id
    }

    /**
     * List all backup files in AppDataFolder, sorted by creation time (newest first).
     */
    suspend fun listBackups(): List<BackupMetadata> = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext emptyList()

        try {
            val query = "parents in '$APP_DATA_FOLDER' and name contains '$BACKUP_FILENAME_PREFIX'"
            val result: FileList = service.files().list()
                .setQ(query)
                .setSpaces(APP_DATA_FOLDER)
                .setFields("files(id, name, size, createdTime, modifiedTime, md5Checksum)")
                .setOrderBy("createdTime desc")
                .execute()

            result.files?.map { driveFile ->
                val createdTime = try {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                        .parse(driveFile.createdTime?.toString() ?: "")?.time ?: 0L
                } catch (_: Exception) { 0L }

                val modifiedTime = try {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                        .parse(driveFile.modifiedTime?.toString() ?: "")?.time ?: 0L
                } catch (_: Exception) { 0L }

                BackupMetadata(
                    fileId = driveFile.id,
                    fileName = driveFile.name ?: "",
                    size = (driveFile.size as? Long) ?: 0L,
                    createdTime = createdTime,
                    modifiedTime = modifiedTime,
                    md5Checksum = driveFile.md5Checksum
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list backups", e)
            emptyList()
        }
    }

    /**
     * Download a backup file from Drive with chunked download.
     */
    suspend fun downloadBackup(
        fileId: String,
        outputFile: File,
        progress: MutableStateFlow<Float> = MutableStateFlow(0f)
    ) = withContext(Dispatchers.IO) {
        val service = driveService ?: throw IllegalStateException("Drive service not initialized")

        val request = service.files().get(fileId)

        request.mediaHttpDownloader.apply {
            setDirectDownloadEnabled(false)
            setProgressListener { downloader ->
                val downloaded = downloader.numBytesDownloaded.toFloat()
                val total = downloader.progress.toDouble().toFloat()
                progress.value = if (total > 0f) (downloaded / total).coerceIn(0f, 1f) else 0f
            }
        }

        FileOutputStream(outputFile).use { outputStream ->
            request.executeMediaAndDownloadTo(outputStream)
        }

        Log.d(TAG, "Downloaded backup $fileId (${outputFile.length()} bytes)")
    }

    /**
     * Delete old backup versions, keeping the most recent [retentionCount] backups.
     */
    suspend fun cleanupOldBackups(retentionCount: Int = MAX_BACKUP_RETENTION) {
        withContext(Dispatchers.IO) {
            val service = driveService ?: return@withContext
            val backups = listBackups()

            // Keep newest N, delete the rest
            if (backups.size <= retentionCount) return@withContext

            val toDelete = backups.drop(retentionCount)
            for (backup in toDelete) {
                try {
                    service.files().delete(backup.fileId).execute()
                    Log.d(TAG, "Deleted old backup: ${backup.fileName}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete old backup ${backup.fileId}", e)
                }
            }

            // Also clean up old manifests
            try {
                val manifestQuery = "parents in '$APP_DATA_FOLDER' and name contains '$MANIFEST_FILENAME_PREFIX'"
                val manifests = service.files().list()
                    .setQ(manifestQuery)
                    .setSpaces(APP_DATA_FOLDER)
                    .setFields("files(id, name, createdTime)")
                    .setOrderBy("createdTime desc")
                    .execute()

                manifests.files?.drop(retentionCount)?.forEach { manifest ->
                    try {
                        service.files().delete(manifest.id).execute()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete old manifest ${manifest.id}", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up old manifests", e)
            }
        }
    }

    /**
     * Get total storage used in AppDataFolder.
     */
    suspend fun getStorageUsage(): Long = withContext(Dispatchers.IO) {
        val service = driveService ?: return@withContext 0L
        try {
            val result = service.files().list()
                .setSpaces(APP_DATA_FOLDER)
                .setFields("files(size)")
                .execute()
            var total: Long = 0L
            result.files?.forEach { f -> total += f.size?.toLong() ?: 0L }
            total
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage usage", e)
            0L
        }
    }

    /**
     * Estimate backup size by checking local DB row counts and media files.
     */
    suspend fun estimateBackupSize(
        messageCount: Long,
        mediaFileCount: Int,
        mediaDirSize: Long,
        includeVideos: Boolean
    ): Long {
        // Rough estimate: messages ~2KB each, media varies
        val messageEstimate = messageCount * 2048L
        // Media: only count non-video if videos excluded
        val mediaEstimate = if (includeVideos) mediaDirSize else (mediaDirSize * 0.3).toLong()
        // Compression reduces size ~70%
        val compressedEstimate = ((messageEstimate + mediaEstimate) * 0.3).toLong()
        return compressedEstimate.coerceAtLeast(1024L)
    }
}
