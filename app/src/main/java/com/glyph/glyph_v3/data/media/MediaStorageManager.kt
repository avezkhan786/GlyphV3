package com.glyph.glyph_v3.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Manages persistent local storage for media files.
 * 
 * Key responsibilities:
 * - Save media files to app's internal persistent storage (NOT cache)
 * - Handle file naming and deduplication
 * - Provide file paths for playback
 * - Clean up orphaned files
 * 
 * Storage structure:
 * /data/data/com.glyph.glyph_v3/files/media/
 *   ├── images/
 *   │   └── {chatId}/
 *   │       └── {messageId}_{hash}.jpg
 *   ├── videos/
 *   │   └── {chatId}/
 *   │       └── {messageId}_{hash}.mp4
 *   ├── audio/
 *   │   └── {chatId}/
 *   │       └── {messageId}_{hash}.aac
 *   ├── thumbnails/
 *   │   └── {chatId}/
 *   │       └── {messageId}_thumb.jpg
 *   └── documents/
 *       └── {chatId}/
 *           └── {messageId}_{originalName}
 */
object MediaStorageManager {
    
    private const val TAG = "MediaStorageManager"
    
    private const val MEDIA_ROOT = "media"
    private const val DIR_IMAGES = "images"
    private const val DIR_VIDEOS = "videos"
    private const val DIR_AUDIO = "audio"
    private const val DIR_THUMBNAILS = "thumbnails"
    private const val DIR_DOCUMENTS = "documents"
    private const val DIR_TEMP = "temp"
    
    enum class MediaType {
        IMAGE, VIDEO, AUDIO, THUMBNAIL, DOCUMENT
    }
    
    /**
     * Get the root media directory (persistent, not cache)
     */
    fun getMediaRoot(context: Context): File {
        val mediaDir = File(context.filesDir, MEDIA_ROOT)
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        return mediaDir
    }
    
    /**
     * Get directory for a specific media type and chat
     */
    fun getMediaDirectory(context: Context, mediaType: MediaType, chatId: String): File {
        val typeDir = when (mediaType) {
            MediaType.IMAGE -> DIR_IMAGES
            MediaType.VIDEO -> DIR_VIDEOS
            MediaType.AUDIO -> DIR_AUDIO
            MediaType.THUMBNAIL -> DIR_THUMBNAILS
            MediaType.DOCUMENT -> DIR_DOCUMENTS
        }
        
        val dir = File(getMediaRoot(context), "$typeDir/$chatId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Get temp directory for in-progress downloads
     */
    fun getTempDirectory(context: Context): File {
        val tempDir = File(getMediaRoot(context), DIR_TEMP)
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return tempDir
    }
    
    /**
     * Generate a unique filename for a media file
     */
    fun generateFilename(messageId: String, mediaType: MediaType, extension: String? = null, itemIndex: Int? = null): String {
        val ext = extension ?: when (mediaType) {
            MediaType.IMAGE -> "jpg"
            MediaType.VIDEO -> "mp4"
            MediaType.AUDIO -> "aac"
            MediaType.THUMBNAIL -> "jpg"
            MediaType.DOCUMENT -> "bin"
        }
        
        val thumbSuffix = if (mediaType == MediaType.THUMBNAIL) "_thumb" else ""
        val indexSuffix = if (itemIndex != null) "_item_$itemIndex" else ""
        return "${messageId}${indexSuffix}${thumbSuffix}.$ext"
    }
    
    /**
     * Get the expected file path for a media item
     */
    fun getMediaFile(context: Context, chatId: String, messageId: String, mediaType: MediaType, extension: String? = null, itemIndex: Int? = null): File {
        val dir = getMediaDirectory(context, mediaType, chatId)
        val filename = generateFilename(messageId, mediaType, extension, itemIndex)
        return File(dir, filename)
    }
    
    /**
     * Check if a media file exists locally
     */
    fun hasLocalFile(context: Context, chatId: String, messageId: String, mediaType: MediaType, itemIndex: Int? = null): Boolean {
        val file = getMediaFile(context, chatId, messageId, mediaType, itemIndex = itemIndex)
        return file.exists() && file.length() > 0
    }
    
    /**
     * Get the local URI for a media file if it exists
     */
    fun getLocalUri(context: Context, chatId: String, messageId: String, mediaType: MediaType): Uri? {
        val file = getMediaFile(context, chatId, messageId, mediaType)
        return if (file.exists() && file.length() > 0) {
            Uri.fromFile(file)
        } else {
            null
        }
    }
    
    /**
     * Save media from an input stream to persistent storage.
     * Uses atomic write (write to temp, then move) to prevent corruption.
     * 
     * @return The saved file, or null if saving failed
     */
    suspend fun saveMedia(
        context: Context,
        chatId: String,
        messageId: String,
        mediaType: MediaType,
        inputStream: InputStream,
        extension: String? = null,
        expectedSize: Long? = null,
        itemIndex: Int? = null,
        onProgress: ((bytesWritten: Long, totalBytes: Long?) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val targetFile = getMediaFile(context, chatId, messageId, mediaType, extension, itemIndex)
        
        // Check if file already exists (idempotency)
        if (targetFile.exists() && targetFile.length() > 0) {
            // Verify file size if expected size is known
            if (expectedSize == null || targetFile.length() == expectedSize) {
                return@withContext targetFile
            } else {
                // File is corrupt or incomplete, delete and re-download
                Log.w(TAG, "Existing file size mismatch, re-downloading: ${targetFile.absolutePath}")
                targetFile.delete()
            }
        }
        
        // Write to temp file first (atomic write pattern)
        val tempFile = File(getTempDirectory(context), "${messageId}_${System.currentTimeMillis()}.tmp")
        
        try {
            var bytesWritten = 0L
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress?.invoke(bytesWritten, expectedSize)
                }
                output.flush()
                output.fd.sync() // Ensure data is written to disk
            }
            
            // Verify the download completed successfully
            if (expectedSize != null && bytesWritten != expectedSize) {
                Log.e(TAG, "Download incomplete: expected $expectedSize bytes, got $bytesWritten")
                tempFile.delete()
                return@withContext null
            }
            
            // Ensure target directory exists
            targetFile.parentFile?.mkdirs()
            
            // Atomic move from temp to final location
            if (tempFile.renameTo(targetFile)) {
                targetFile
            } else {
                // Fallback: copy if rename fails (across filesystems)
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save media: ${e.message}", e)
            tempFile.delete()
            null
        }
    }
    
    /**
     * Save media from a local URI (e.g., after compression) to persistent storage.
     */
    suspend fun saveMediaFromUri(
        context: Context,
        chatId: String,
        messageId: String,
        mediaType: MediaType,
        sourceUri: Uri,
        extension: String? = null,
        itemIndex: Int? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            // Optimization: If URI is a file, use file operations directly
            if (sourceUri.scheme == "file" && sourceUri.path != null) {
                val sourceFile = File(sourceUri.path!!)
                if (sourceFile.exists()) {
                    return@withContext saveMediaFromFile(context, chatId, messageId, mediaType, sourceFile, extension, itemIndex)
                }
            }
            
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                saveMedia(context, chatId, messageId, mediaType, inputStream, extension, itemIndex = itemIndex)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save media from URI: ${e.message}", e)
            null
        }
    }
    
    /**
     * Copy an existing file to persistent storage
     */
    suspend fun saveMediaFromFile(
        context: Context,
        chatId: String,
        messageId: String,
        mediaType: MediaType,
        sourceFile: File,
        extension: String? = null,
        itemIndex: Int? = null
    ): File? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) {
            Log.e(TAG, "Source file does not exist: ${sourceFile.absolutePath}")
            return@withContext null
        }
        
        val targetFile = getMediaFile(context, chatId, messageId, mediaType, extension, itemIndex)
        
        // Check if already saved (idempotency)
        if (targetFile.exists() && targetFile.length() == sourceFile.length()) {
            return@withContext targetFile
        }
        
        try {
            targetFile.parentFile?.mkdirs()
            sourceFile.copyTo(targetFile, overwrite = true)
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy media: ${e.message}", e)
            null
        }
    }
    
    /**
     * Delete a specific media file
     */
    fun deleteMedia(context: Context, chatId: String, messageId: String, mediaType: MediaType): Boolean {
        val file = getMediaFile(context, chatId, messageId, mediaType)
        return if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }
    
    /**
     * Delete all media for a message
     */
    fun deleteAllMediaForMessage(context: Context, chatId: String, messageId: String) {
        MediaType.values().forEach { type ->
            deleteMedia(context, chatId, messageId, type)
        }
    }
    
    /**
     * Delete all media for a chat
     */
    fun deleteAllMediaForChat(context: Context, chatId: String) {
        MediaType.values().forEach { type ->
            val dir = getMediaDirectory(context, type, chatId)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
    
    /**
     * Clean up temp files older than the specified age
     */
    fun cleanupTempFiles(context: Context, maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        val tempDir = getTempDirectory(context)
        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        
        tempDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                file.delete()
            }
        }
    }
    
    /**
     * Get total size of all media storage
     */
    fun getTotalMediaSize(context: Context): Long {
        return getMediaRoot(context).walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
    
    /**
     * Get size of media for a specific chat
     */
    fun getChatMediaSize(context: Context, chatId: String): Long {
        var totalSize = 0L
        MediaType.values().forEach { type ->
            val dir = getMediaDirectory(context, type, chatId)
            if (dir.exists()) {
                totalSize += dir.walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() }
            }
        }
        return totalSize
    }
    
    /**
     * Generate a hash of file content for deduplication
     */
    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
