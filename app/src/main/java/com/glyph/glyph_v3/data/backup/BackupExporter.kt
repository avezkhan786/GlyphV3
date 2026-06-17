package com.glyph.glyph_v3.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.util.JsonWriter
import android.util.Log
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.glyph.glyph_v3.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey

/**
 * Streaming exporter for backup data.
 *
 * All database exports use cursor-based chunking (5000 rows per chunk).
 * Media files are copied one at a time. The final backup package is
 * GZIP compressed then AES-256-GCM encrypted.
 */
class BackupExporter(private val context: Context) {

    companion object {
        private const val TAG = "BackupExporter"
        private const val CHUNK_SIZE = 5000
        private const val BUFFER_SIZE = 65536 // 64KB

        @Volatile
        private var instance: BackupExporter? = null

        fun getInstance(context: Context): BackupExporter {
            return instance ?: synchronized(this) {
                instance ?: BackupExporter(context.applicationContext).also { instance = it }
            }
        }
    }

    // ===== DATABASE EXPORT =====

    /**
     * Export messages table — cursor-based streaming, chunked at [CHUNK_SIZE] rows per file.
     */
    suspend fun exportMessages(
        db: AppDatabase,
        outputDir: File,
        progress: MutableStateFlow<Float> = MutableStateFlow(0f)
    ): List<File> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()

        // Count total messages first
        val supportDb = db.openHelper.readableDatabase
        val countCursor: Cursor = supportDb.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM messages"))
        val totalCount = if (countCursor.moveToFirst()) countCursor.getInt(0) else 0
        countCursor.close()

        val resultFiles = doExportMessagesInChunks(db, outputDir, totalCount, progress)

        progress.value = 1f
        Log.d(TAG, "Exported $totalCount messages into ${resultFiles.size} chunks")
        resultFiles
    }

    private suspend fun doExportMessagesInChunks(
        db: AppDatabase,
        outputDir: File,
        totalCount: Int,
        progress: MutableStateFlow<Float>
    ): List<File> = withContext(Dispatchers.IO) {
        val files = mutableListOf<File>()
        val supportDb = db.openHelper.readableDatabase
        val chunkCount = ((totalCount - 1) / CHUNK_SIZE) + 1

        for (chunk in 0 until chunkCount) {
            val offset = chunk * CHUNK_SIZE
            val cursor: Cursor = supportDb.query(
                SimpleSQLiteQuery("SELECT * FROM messages ORDER BY COALESCE(serverTimestamp, timestamp) ASC LIMIT $CHUNK_SIZE OFFSET $offset")
            )
            val chunkFile = File(outputDir, "messages_chunk_${String.format("%04d", chunk + 1)}.json")
            FileOutputStream(chunkFile).buffered(BUFFER_SIZE).use { fos ->
                val writer = JsonWriter(OutputStreamWriter(fos, "UTF-8"))
                writer.setIndent("  ")
                writer.beginArray()
                cursor.use {
                    while (cursor.moveToNext()) {
                        writer.beginObject()
                        val columnNames = cursor.columnNames
                        for (col in columnNames) {
                            val value = when (cursor.getType(cursor.getColumnIndexOrThrow(col))) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(cursor.getColumnIndexOrThrow(col))
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(cursor.getColumnIndexOrThrow(col))
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(cursor.getColumnIndexOrThrow(col))
                                Cursor.FIELD_TYPE_BLOB -> null // Skip blobs
                                else -> null
                            }
                            writer.name(col)
                            when (value) {
                                null -> writer.nullValue()
                                is Long -> writer.value(value)
                                is Double -> writer.value(value)
                                is String -> writer.value(value)
                                is Boolean -> writer.value(value)
                            }
                        }
                        writer.endObject()
                    }
                }
                writer.endArray()
                writer.close()
            }
            files.add(chunkFile)
            progress.value = ((chunk + 1).toFloat() / chunkCount) * 0.8f // Messages are 80% of total progress
        }
        files
    }

    /**
     * Export chats table — typically small, single file.
     */
    suspend fun exportChats(db: AppDatabase, outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val chats = db.chatDao().getAllChatsOnce()
        val file = File(outputDir, "chats.json")
        FileOutputStream(file).buffered(BUFFER_SIZE).use { fos ->
            val writer = JsonWriter(OutputStreamWriter(fos, "UTF-8"))
            writer.setIndent("  ")
            writer.beginArray()
            for (chat in chats) {
                writer.beginObject()
                writer.name("id").value(chat.id)
                writer.name("otherUserId").value(chat.otherUserId)
                writer.name("otherUsername").value(chat.otherUsername)
                writer.name("otherUserAvatar").value(chat.otherUserAvatar)
                writer.name("lastMessage").value(chat.lastMessage)
                writer.name("lastMessageTimestamp").value(chat.lastMessageTimestamp)
                writer.name("lastMessageSenderId").value(chat.lastMessageSenderId)
                writer.name("lastMessageStatus").value(chat.lastMessageStatus)
                writer.name("unreadCount").value(chat.unreadCount)
                writer.name("isGroup").value(chat.isGroup)
                writer.name("isArchived").value(chat.isArchived)
                writer.name("groupName").value(chat.groupName)
                writer.name("groupIconUrl").value(chat.groupIconUrl)
                writer.name("groupDescription").value(chat.groupDescription)
                writer.name("createdBy").value(chat.createdBy)
                writer.name("createdAt").value(chat.createdAt)
                writer.name("participantsJson").value(chat.participantsJson)
                writer.name("adminsJson").value(chat.adminsJson)
                writer.endObject()
            }
            writer.endArray()
            writer.close()
        }
        Log.d(TAG, "Exported ${chats.size} chats")
        file
    }

    /**
     * Export call logs.
     */
    suspend fun exportCallLogs(db: AppDatabase, outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val supportDb = db.openHelper.readableDatabase
        val cursor: Cursor = supportDb.query(SimpleSQLiteQuery("SELECT * FROM call_logs ORDER BY createdAt ASC"))
        val logs = readCursorRows(cursor)

        val file = File(outputDir, "call_logs.json")
        writeJsonArrayFile(file, logs)
        Log.d(TAG, "Exported ${logs.size} call logs")
        file
    }

    /**
     * Export AI messages.
     */
    suspend fun exportAiMessages(db: AppDatabase, outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val supportDb = db.openHelper.readableDatabase
        val cursor: Cursor = supportDb.query(SimpleSQLiteQuery("SELECT * FROM ai_messages ORDER BY timestamp ASC"))
        val messages = readCursorRows(cursor)

        val file = File(outputDir, "ai_messages.json")
        writeJsonArrayFile(file, messages)
        Log.d(TAG, "Exported ${messages.size} AI messages")
        file
    }

    /**
     * Export deleted message markers (needed for restore fidelity).
     */
    suspend fun exportDeletedMessages(db: AppDatabase, outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val supportDb = db.openHelper.readableDatabase
        val cursor: Cursor = supportDb.query(SimpleSQLiteQuery("SELECT * FROM deleted_messages"))
        val entries = readCursorRows(cursor)

        val file = File(outputDir, "deleted_messages.json")
        writeJsonArrayFile(file, entries)
        Log.d(TAG, "Exported ${entries.size} deleted message markers")
        file
    }

    // ── Helpers for cursor-to-JSON export ──

    private fun readCursorRows(cursor: Cursor): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        cursor.use {
            val columns = cursor.columnNames
            while (cursor.moveToNext()) {
                val row = mutableMapOf<String, Any?>()
                for (col in columns) {
                    row[col] = when (cursor.getType(cursor.getColumnIndexOrThrow(col))) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(cursor.getColumnIndexOrThrow(col))
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(cursor.getColumnIndexOrThrow(col))
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(cursor.getColumnIndexOrThrow(col))
                        else -> null
                    }
                }
                rows.add(row)
            }
        }
        return rows
    }

    private fun writeJsonArrayFile(file: File, rows: List<Map<String, Any?>>) {
        FileOutputStream(file).buffered(BUFFER_SIZE).use { fos ->
            val writer = JsonWriter(OutputStreamWriter(fos, "UTF-8"))
            writer.setIndent("  ")
            writer.beginArray()
            for (row in rows) {
                writer.beginObject()
                for ((key, value) in row) {
                    writer.name(key)
                    when (value) {
                        null -> writer.nullValue()
                        is Long -> writer.value(value)
                        is Double -> writer.value(value)
                        is String -> writer.value(value)
                        is Boolean -> writer.value(value)
                    }
                }
                writer.endObject()
            }
            writer.endArray()
            writer.close()
        }
    }

    // ===== SETTINGS EXPORT =====

    /**
     * Export all DataStore preferences as JSON.
     */
    suspend fun exportAppSettings(prefs: Map<String, *>, outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val file = File(outputDir, "app_settings.json")
        FileOutputStream(file).buffered(BUFFER_SIZE).use { fos ->
            val writer = JsonWriter(OutputStreamWriter(fos, "UTF-8"))
            writer.setIndent("  ")
            writer.beginObject()
            for ((key, value) in prefs) {
                writer.name(key)
                when (value) {
                    null -> writer.nullValue()
                    is Boolean -> writer.value(value)
                    is Int -> writer.value(value.toLong())
                    is Long -> writer.value(value)
                    is Float -> writer.value(value.toDouble())
                    is Double -> writer.value(value)
                    is String -> writer.value(value)
                    else -> writer.value(value.toString())
                }
            }
            writer.endObject()
            writer.close()
        }
        file
    }

    /**
     * Export per-chat SharedPreferences as JSON.
     * Preferences are stored in files named "chat_prefs_<chatId>".
     */
    suspend fun exportChatSettings(context: Context, outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        val prefsDir = File(context.filesDir.parent, "shared_prefs")
        val chatPrefsFiles = if (prefsDir.exists()) {
            prefsDir.listFiles()?.filter { it.name.startsWith("chat_prefs_") } ?: emptyList()
        } else emptyList()

        val file = File(outputDir, "chat_settings.json")
        FileOutputStream(file).buffered(BUFFER_SIZE).use { fos ->
            val writer = JsonWriter(OutputStreamWriter(fos, "UTF-8"))
            writer.setIndent("  ")
            writer.beginObject()
            for (prefsFile in chatPrefsFiles) {
                val chatId = prefsFile.name.removePrefix("chat_prefs_").removeSuffix(".xml")
                writer.name(chatId)
                writer.beginObject()
                val prefs = context.getSharedPreferences("chat_prefs_$chatId", Context.MODE_PRIVATE)
                for ((key, value) in prefs.all) {
                    writer.name(key)
                    when (value) {
                        null -> writer.nullValue()
                        is Boolean -> writer.value(value)
                        is Int -> writer.value(value.toLong())
                        is Long -> writer.value(value)
                        is Float -> writer.value(value.toDouble())
                        is String -> writer.value(value)
                        else -> writer.value(value.toString())
                    }
                }
                writer.endObject()
            }
            writer.endObject()
            writer.close()
        }
        Log.d(TAG, "Exported ${chatPrefsFiles.size} chat settings")
        file
    }

    /**
     * Export chat wallpaper assignments.
     */
    suspend fun exportWallpapers(context: Context, outputDir: File): File = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        // Wallpapers are stored in DataStore via ChatWallpaperStore
        val wallpaperPrefs = context.getSharedPreferences("chat_wallpaper_prefs", Context.MODE_PRIVATE)
        val file = File(outputDir, "wallpapers.json")
        FileOutputStream(file).buffered(BUFFER_SIZE).use { fos ->
            val writer = JsonWriter(OutputStreamWriter(fos, "UTF-8"))
            writer.setIndent("  ")
            writer.beginObject()
            for ((key, value) in wallpaperPrefs.all) {
                writer.name(key)
                when (value) {
                    null -> writer.nullValue()
                    is Boolean -> writer.value(value)
                    is Int -> writer.value(value.toLong())
                    is Long -> writer.value(value)
                    is Float -> writer.value(value.toDouble())
                    is String -> writer.value(value)
                    else -> writer.value(value.toString())
                }
            }
            writer.endObject()
            writer.close()
        }
        file
    }

    // ===== MEDIA EXPORT =====

    /**
     * Export media files from filesDir/media/. Copies with dedup by hash,
     * skipping videos if [includeVideos] is false.
     */
    suspend fun exportMedia(
        mediaDir: File,
        outputDir: File,
        includeVideos: Boolean,
        progress: MutableStateFlow<Float> = MutableStateFlow(0f)
    ): List<File> = withContext(Dispatchers.IO) {
        val exportedFiles = mutableListOf<File>()
        if (!mediaDir.exists()) return@withContext exportedFiles

        outputDir.mkdirs()
        val mediaOutputDir = File(outputDir, "media")
        mediaOutputDir.mkdirs()

        val videoExtensions = setOf("mp4", "3gp", "mkv", "webm", "avi")
        val seenHashes = mutableSetOf<String>()

        // Walk all subdirectories
        val allFiles = mediaDir.walkTopDown()
            .filter { it.isFile }
            .filter { includeVideos || it.extension.lowercase() !in videoExtensions }
            .toList()
        val totalFiles = allFiles.size
        var copiedCount = 0

        for (file in allFiles) {
            // Compute hash for dedup
            val hash = BackupMetadataManager.getInstance(context).computeFileHash(file)
            if (hash.isNotEmpty() && !seenHashes.add(hash)) {
                // Duplicate, skip
                copiedCount++
                continue
            }

            // Preserve relative path structure
            val relativePath = file.relativeTo(mediaDir).path
            val destFile = File(mediaOutputDir, relativePath)
            destFile.parentFile?.mkdirs()

            try {
                file.copyTo(destFile, overwrite = true)
                exportedFiles.add(destFile)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy media file: ${file.absolutePath}", e)
            }

            copiedCount++
            if (copiedCount % 50 == 0) {
                progress.value = copiedCount.toFloat() / totalFiles.coerceAtLeast(1)
            }
        }

        progress.value = 1f
        Log.d(TAG, "Exported ${exportedFiles.size} media files (${copiedCount} total, skipped duplicates)")
        exportedFiles
    }

    // ===== BACKUP PACKAGE CREATION =====

    /**
     * Create the final backup package: GZIP compress all exported files into a tar-like
     * archive, then encrypt with AES-256-GCM. Returns the final encrypted backup file.
     */
    suspend fun createBackupPackage(
        exportedDir: File,
        manifest: BackupMetadataManager.BackupManifest,
        encryptionKey: SecretKey,
        outputFile: File,
        progress: MutableStateFlow<Float> = MutableStateFlow(0f)
    ): File = withContext(Dispatchers.IO) {
        // 1. Write manifest to a file inside the export dir
        val manifestJson = BackupMetadataManager.getInstance(context).manifestToJson(manifest)
        val manifestFile = File(exportedDir, "backup_manifest.json")
        manifestFile.writeText(manifestJson)

        // 2. Collect all files to package
        val allFiles = exportedDir.walkTopDown().filter { it.isFile }.toList()
        val totalSize = allFiles.sumOf { it.length() }
        var processedSize = 0L

        // 3. Create a simple archive: concatenate files with header
        // Format: [fileCount:4bytes][for each file: nameLen:4bytes][name:UTF8][size:8bytes][data]
        val tempArchive = File(outputFile.parent, "${outputFile.name}.tar")
        FileOutputStream(tempArchive).buffered(BUFFER_SIZE).use { fos ->
            // Write file count
            val countBytes = ByteArray(4)
            countBytes[0] = ((allFiles.size shr 24) and 0xFF).toByte()
            countBytes[1] = ((allFiles.size shr 16) and 0xFF).toByte()
            countBytes[2] = ((allFiles.size shr 8) and 0xFF).toByte()
            countBytes[3] = (allFiles.size and 0xFF).toByte()
            fos.write(countBytes)

            for (file in allFiles) {
                val relativePath = file.relativeTo(exportedDir).path
                val nameBytes = relativePath.toByteArray(Charsets.UTF_8)

                // Write name length (4 bytes)
                val nameLenBytes = ByteArray(4)
                nameLenBytes[0] = ((nameBytes.size shr 24) and 0xFF).toByte()
                nameLenBytes[1] = ((nameBytes.size shr 16) and 0xFF).toByte()
                nameLenBytes[2] = ((nameBytes.size shr 8) and 0xFF).toByte()
                nameLenBytes[3] = (nameBytes.size and 0xFF).toByte()
                fos.write(nameLenBytes)

                // Write name
                fos.write(nameBytes)

                // Write file size (8 bytes)
                val size = file.length()
                val sizeBytes = ByteArray(8)
                for (i in 0..7) {
                    sizeBytes[i] = ((size shr (56 - i * 8)) and 0xFF).toByte()
                }
                fos.write(sizeBytes)

                // Write file data
                val fileBytes = file.readBytes()
                fos.write(fileBytes)

                processedSize += size
                progress.value = 0.3f + (0.3f * processedSize / totalSize.coerceAtLeast(1))
            }
        }

        // 4. GZIP compress the archive
        progress.value = 0.6f
        val tempGzip = File(outputFile.parent, "${outputFile.name}.gz")
        GZIPOutputStream(FileOutputStream(tempGzip)).use { gzipOut ->
            FileInputStream(tempArchive).use { input ->
                input.copyTo(gzipOut, BUFFER_SIZE)
            }
        }
        tempArchive.delete()
        progress.value = 0.8f

        // 5. AES-256-GCM encrypt
        val keyManager = BackupKeyManager.getInstance(context)
        val encrypted = keyManager.encrypt(tempGzip.readBytes())
        outputFile.writeBytes(encrypted)
        tempGzip.delete()

        progress.value = 1f
        Log.d(TAG, "Created backup package: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
        outputFile
    }
}
