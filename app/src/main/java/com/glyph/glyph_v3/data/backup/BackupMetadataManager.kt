package com.glyph.glyph_v3.data.backup

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Tracks file hashes for incremental backup detection and backup metadata.
 *
 * Uses a DataStore to persist file hash entries so subsequent backups
 * can skip unchanged files.
 */
class BackupMetadataManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupMetadataManager"
        private const val HASHES_KEY = "backup_file_hashes"
        private const val MANIFEST_FILENAME = "backup_manifest.json"

        @Volatile
        private var instance: BackupMetadataManager? = null

        fun getInstance(context: Context): BackupMetadataManager {
            return instance ?: synchronized(this) {
                instance ?: BackupMetadataManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val Context.metadataDataStore by preferencesDataStore(name = "backup_metadata")
    private val hashesKey = stringPreferencesKey(HASHES_KEY)

    data class FileHashEntry(
        val path: String,
        val sha256: String,
        val lastModified: Long,
        val size: Long
    )

    data class BackupDelta(
        val isFull: Boolean,
        val changedFiles: List<FileHashEntry>,
        val totalMessageCount: Long,
        val totalMediaCount: Int,
        val totalSizeBytes: Long
    )

    data class BackupManifest(
        val backupId: String,
        val timestamp: Long,
        val appVersion: String,
        val messageCount: Long,
        val chatCount: Int,
        val mediaCount: Int,
        val totalSizeBytes: Long,
        val isFull: Boolean,
        val fileHashes: List<FileHashEntry>,
        val googleAccountEmail: String
    )

    /**
     * Compute SHA-256 hash of a file using streaming (no full read into memory).
     */
    suspend fun computeFileHash(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext ""
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536) // 64KB chunks
        FileInputStream(file).use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Compare current file state vs last backup state to detect changed files.
     * Returns a [BackupDelta] with only changed/new files.
     */
    suspend fun detectChanges(
        exportedFiles: List<File>,
        previousHashes: Map<String, FileHashEntry>,
        messageCount: Long,
        mediaCount: Int
    ): BackupDelta = withContext(Dispatchers.IO) {
        val changedFiles = mutableListOf<FileHashEntry>()
        var totalSize = 0L

        for (file in exportedFiles) {
            val hash = computeFileHash(file)
            val entry = FileHashEntry(
                path = file.absolutePath,
                sha256 = hash,
                lastModified = file.lastModified(),
                size = file.length()
            )
            val prevEntry = previousHashes[file.name]
            if (prevEntry == null || prevEntry.sha256 != hash) {
                changedFiles.add(entry)
            }
            totalSize += file.length()
        }

        val isFull = previousHashes.isEmpty()
        BackupDelta(
            isFull = isFull,
            changedFiles = changedFiles,
            totalMessageCount = messageCount,
            totalMediaCount = mediaCount,
            totalSizeBytes = totalSize
        )
    }

    /**
     * Persist hash map after successful backup.
     */
    suspend fun saveBackupState(entries: List<FileHashEntry>) {
        withContext(Dispatchers.IO) {
            val json = JSONArray()
            for (entry in entries) {
                val obj = JSONObject().apply {
                    put("path", entry.path)
                    put("sha256", entry.sha256)
                    put("lastModified", entry.lastModified)
                    put("size", entry.size)
                }
                json.put(obj)
            }
            context.metadataDataStore.updateData { prefs ->
                prefs.toMutablePreferences().apply {
                    set(hashesKey, json.toString())
                }
            }
        }
    }

    /**
     * Load previously saved file hash entries.
     */
    suspend fun loadPreviousHashes(): Map<String, FileHashEntry> = withContext(Dispatchers.IO) {
        val prefs = context.metadataDataStore.data.first()
        val jsonStr = prefs[hashesKey] ?: return@withContext emptyMap()
        val result = mutableMapOf<String, FileHashEntry>()
        try {
            val json = JSONArray(jsonStr)
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val entry = FileHashEntry(
                    path = obj.getString("path"),
                    sha256 = obj.getString("sha256"),
                    lastModified = obj.getLong("lastModified"),
                    size = obj.getLong("size")
                )
                result[File(entry.path).name] = entry
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse previous hashes, starting fresh", e)
        }
        result
    }

    /**
     * Generate the backup manifest JSON.
     */
    fun generateManifest(
        backupId: String,
        timestamp: Long,
        delta: BackupDelta,
        googleAccountEmail: String,
        appVersion: String,
        chatCount: Int
    ): BackupManifest {
        return BackupManifest(
            backupId = backupId,
            timestamp = timestamp,
            appVersion = appVersion,
            messageCount = delta.totalMessageCount,
            chatCount = chatCount,
            mediaCount = delta.totalMediaCount,
            totalSizeBytes = delta.totalSizeBytes,
            isFull = delta.isFull,
            fileHashes = delta.changedFiles,
            googleAccountEmail = googleAccountEmail
        )
    }

    /**
     * Serialize a BackupManifest to JSON string.
     */
    fun manifestToJson(manifest: BackupManifest): String {
        return JSONObject().apply {
            put("backupId", manifest.backupId)
            put("timestamp", manifest.timestamp)
            put("appVersion", manifest.appVersion)
            put("messageCount", manifest.messageCount)
            put("chatCount", manifest.chatCount)
            put("mediaCount", manifest.mediaCount)
            put("totalSizeBytes", manifest.totalSizeBytes)
            put("isFull", manifest.isFull)
            put("googleAccountEmail", manifest.googleAccountEmail)
            val hashesJson = JSONArray()
            for (entry in manifest.fileHashes) {
                hashesJson.put(JSONObject().apply {
                    put("path", entry.path)
                    put("sha256", entry.sha256)
                    put("lastModified", entry.lastModified)
                    put("size", entry.size)
                })
            }
            put("fileHashes", hashesJson)
        }.toString()
    }

    /**
     * Parse a BackupManifest from JSON string.
     */
    fun manifestFromJson(json: String): BackupManifest? {
        return try {
            val obj = JSONObject(json)
            val hashesJson = obj.getJSONArray("fileHashes")
            val hashes = mutableListOf<FileHashEntry>()
            for (i in 0 until hashesJson.length()) {
                val h = hashesJson.getJSONObject(i)
                hashes.add(FileHashEntry(
                    path = h.getString("path"),
                    sha256 = h.getString("sha256"),
                    lastModified = h.getLong("lastModified"),
                    size = h.getLong("size")
                ))
            }
            BackupManifest(
                backupId = obj.getString("backupId"),
                timestamp = obj.getLong("timestamp"),
                appVersion = obj.optString("appVersion", ""),
                messageCount = obj.getLong("messageCount"),
                chatCount = obj.optInt("chatCount", 0),
                mediaCount = obj.optInt("mediaCount", 0),
                totalSizeBytes = obj.getLong("totalSizeBytes"),
                isFull = obj.optBoolean("isFull", true),
                fileHashes = hashes,
                googleAccountEmail = obj.optString("googleAccountEmail", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse manifest JSON", e)
            null
        }
    }
}
