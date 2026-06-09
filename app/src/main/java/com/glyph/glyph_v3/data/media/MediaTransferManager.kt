package com.glyph.glyph_v3.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import com.glyph.glyph_v3.data.local.dao.MessageDao
import com.glyph.glyph_v3.data.local.entity.LocalMessage
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MediaType
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * Unified manager for all media transfer operations.
 * Coordinates between MediaStorageManager, MediaDownloadManager, and MediaAcknowledgmentService.
 * 
 * This is the single entry point for:
 * - Sender: Save local copy after upload, register for ACK tracking
 * - Receiver: Download media, save to persistent storage, send ACK
 * - UI: Get media state and local file paths
 */
class MediaTransferManager private constructor(
    private val context: Context,
    private val messageDao: MessageDao
) {
    
    companion object {
        private const val TAG = "MediaTransferManager"
        
        @Volatile
        private var instance: MediaTransferManager? = null
        
        fun getInstance(context: Context, messageDao: MessageDao): MediaTransferManager {
            return instance ?: synchronized(this) {
                instance ?: MediaTransferManager(context.applicationContext, messageDao).also { instance = it }
            }
        }
    }
    
    private val downloadManager = MediaDownloadManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Media transfer state for UI
     */
    data class MediaState(
        val messageId: String,
        val hasLocalFile: Boolean,
        val localFilePath: String?,
        val isUploading: Boolean = false,
        val isDownloading: Boolean = false,
        val uploadProgress: Float = 0f,
        val downloadProgress: Float = 0f,
        val error: String? = null,
        val canRetry: Boolean = false
    )
    
    // ===== SENDER SIDE =====
    
    /**
     * Called after a media upload completes successfully.
     * Saves a copy to local persistent storage and registers for ACK tracking.
     * 
     * @param messageId The message ID
     * @param chatId The chat ID
     * @param mediaType The type of media
     * @param sourceUri The URI of the uploaded file (usually from compression cache)
     * @param storageRef The Firebase Storage reference path
     * @param recipientIds List of recipient user IDs
     */
    suspend fun onMediaUploaded(
        messageId: String,
        chatId: String,
        mediaType: MediaStorageManager.MediaType,
        sourceUri: Uri,
        storageRef: String,
        recipientIds: List<String>
    ): File? = withContext(Dispatchers.IO) {
        
        // Save to persistent local storage
        val localFile = MediaStorageManager.saveMediaFromUri(
            context = context,
            chatId = chatId,
            messageId = messageId,
            mediaType = mediaType,
            sourceUri = sourceUri
        )
        
        if (localFile != null) {
            
            // Update message with local path
            updateMessageLocalPath(messageId, localFile.absolutePath, mediaType)
            
            // Register for ACK tracking
            MediaAcknowledgmentService.registerMediaUpload(
                messageId = messageId,
                storageRef = storageRef,
                recipientIds = recipientIds
            )
        } else {
            Log.e(TAG, "Failed to save local copy for: $messageId")
        }
        
        localFile
    }
    
    /**
     * Save thumbnail after upload.
     */
    suspend fun saveThumbnail(
        messageId: String,
        chatId: String,
        thumbnailUri: Uri
    ): File? {
        return MediaStorageManager.saveMediaFromUri(
            context = context,
            chatId = chatId,
            messageId = messageId,
            mediaType = MediaStorageManager.MediaType.THUMBNAIL,
            sourceUri = thumbnailUri
        )
    }
    
    // ===== RECEIVER SIDE =====
    
    /**
     * Start downloading media for a message.
     * Called when user taps "download" or automatically for small files.
     */
    fun startDownload(
        messageId: String,
        chatId: String,
        mediaType: MessageType,
        remoteUrl: String,
        expectedSize: Long? = null,
        onComplete: ((success: Boolean, localPath: String?) -> Unit)? = null
    ) {
        val storageMediaType = messageTypeToStorageType(mediaType) ?: return
        
        downloadManager.queueDownload(
            messageId = messageId,
            chatId = chatId,
            mediaType = storageMediaType,
            remoteUrl = remoteUrl,
            expectedSize = expectedSize,
            onComplete = { file ->
                scope.launch {
                    if (file != null) {
                        
                        // Update message with local path
                        updateMessageLocalPath(messageId, file.absolutePath, storageMediaType)
                        
                        // Update message status
                        messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOADED)
                        
                        // Delete from Firebase Storage now that we have local copy
                        deleteFromFirebaseStorage(remoteUrl)
                        
                        // For videos and documents, also delete the thumbnail
                        if (mediaType == MessageType.VIDEO || mediaType == MessageType.DOCUMENT) {
                            val message = messageDao.getMessageById(messageId)
                            if (message?.thumbnailUrl != null && message.thumbnailUrl.isNotEmpty()) {
                                deleteFromFirebaseStorage(message.thumbnailUrl)
                            }
                        }
                        
                        // Also try to send ACK (for future use when sender registers)
                        try {
                            MediaAcknowledgmentService.sendAcknowledgment(messageId)
                        } catch (e: Exception) {
                            Log.w(TAG, "ACK failed (sender may not have registered): ${e.message}")
                        }
                        
                        onComplete?.invoke(true, file.absolutePath)
                    } else {
                        Log.e(TAG, "Download failed: $messageId")
                        messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOAD_FAILED)
                        onComplete?.invoke(false, null)
                    }
                }
            }
        )
    }

    /**
     * Start downloading all items in a media group.
     */
    fun startGroupDownload(message: Message) {
        val items = message.mediaItemsList
        if (items.isEmpty()) {
            Log.w(TAG, "No media items found in message: ${message.id}")
            return
        }

        scope.launch {
            var completedCount = 0
            var failedCount = 0
            val totalCount = items.size
            
            items.forEachIndexed { index, item ->
                
                // Check if already downloaded
                if (!item.localUri.isNullOrEmpty() && File(item.localUri).exists()) {
                    completedCount++
                    if (completedCount == totalCount) {
                         messageDao.updateMessageStatus(message.id, MessageStatus.DOWNLOADED)
                    }
                    return@forEachIndexed
                }

                val storageMediaType = if (item.type == MediaType.VIDEO) 
                    MediaStorageManager.MediaType.VIDEO 
                else 
                    MediaStorageManager.MediaType.IMAGE
                
                // Use a unique ID for each item's download tracking
                val itemDownloadId = "${message.id}_$index"
                
                downloadManager.queueDownload(
                    messageId = itemDownloadId,
                    chatId = message.chatId,
                    mediaType = storageMediaType,
                    remoteUrl = item.url,
                    expectedSize = item.fileSize,
                    onComplete = { file ->
                        scope.launch {
                            if (file != null) {
                                // Update the specific item in the message
                                updateMessageMediaItem(message.id, item.url, file.absolutePath)
                                
                                // Delete from firebase storage
                                deleteFromFirebaseStorage(item.url)
                                
                                completedCount++
                            } else {
                                failedCount++
                                Log.e(TAG, "Item download failed. Progress: $completedCount failed + $failedCount")
                            }
                            
                            // Check if all items are processed
                            if (completedCount + failedCount == totalCount) {
                                val finalStatus = if (completedCount > 0) MessageStatus.DOWNLOADED else MessageStatus.DOWNLOAD_FAILED
                                messageDao.updateMessageStatus(message.id, finalStatus)
                                
                                // Send ACK if at least one item downloaded
                                if (completedCount > 0) {
                                    try {
                                        MediaAcknowledgmentService.sendAcknowledgment(message.id)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "ACK failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }

    private suspend fun updateMessageMediaItem(messageId: String, itemUrl: String, localPath: String) {
        val localMsg = messageDao.getMessageById(messageId) ?: return
        val json = localMsg.mediaItems ?: return
        
        try {
            // Parse JSON manually since LocalMessage doesn't have getMediaItemsList()
            val type = object : TypeToken<List<MediaItem>>() {}.type
            val items = Gson().fromJson<List<MediaItem>>(json, type)?.toMutableList() ?: mutableListOf()
            
            val index = items.indexOfFirst { it.url == itemUrl }
            
            if (index != -1) {
                items[index] = items[index].copy(localUri = localPath)
                
                val updatedJson = Message.mediaItemsToJson(items)
                
                // Use updateMessageMediaItems instead of insertMessage to avoid conflicts
                messageDao.updateMessageMediaItems(messageId, updatedJson)
            } else {
                Log.w(TAG, "Could not find mediaItem with URL: $itemUrl in message $messageId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update mediaItem localUri for $messageId", e)
        }
    }
    
    /**
     * Delete a file from Firebase Storage using its download URL.
     */
    private fun deleteFromFirebaseStorage(downloadUrl: String) {
        if (downloadUrl.isEmpty() || !downloadUrl.startsWith("http")) {
            return
        }
        
        scope.launch {
            try {
                
                // Try to get reference directly from URL first
                val storage = com.google.firebase.storage.FirebaseStorage.getInstance()
                
                try {
                    val storageRef = storage.getReferenceFromUrl(downloadUrl)
                    storageRef.delete()
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to delete from Firebase Storage (direct): ${e.message}")
                        }
                    return@launch
                } catch (e: Exception) {
                    // Ignore
                }
                
                // Parse the storage path from HTTPS URL
                // Format: https://firebasestorage.googleapis.com/v0/b/{bucket}/o/{encoded_path}?...
                val uri = android.net.Uri.parse(downloadUrl)
                val path = uri.path ?: return@launch
                
                // Extract the encoded path after /o/
                val oIndex = path.indexOf("/o/")
                if (oIndex != -1) {
                    val encodedPath = path.substring(oIndex + 3)
                    
                    // URL decode the path
                    val storagePath = java.net.URLDecoder.decode(encodedPath, "UTF-8")
                    
                    storage.reference.child(storagePath).delete()
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to delete from Firebase Storage: ${e.message}")
                        }
                } else {
                    Log.e(TAG, "Could not parse storage path from URL: $downloadUrl")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Exception deleting from Firebase Storage: ${e.message}", e)
            }
        }
    }
    
    /**
     * Download thumbnail for a video message.
     */
    fun downloadThumbnail(
        messageId: String,
        chatId: String,
        thumbnailUrl: String,
        onComplete: ((localPath: String?) -> Unit)? = null
    ) {
        downloadManager.queueDownload(
            messageId = "${messageId}_thumb",
            chatId = chatId,
            mediaType = MediaStorageManager.MediaType.THUMBNAIL,
            remoteUrl = thumbnailUrl,
            onComplete = { file ->
                scope.launch {
                    if (file != null) {
                        // Update message with thumbnail path
                        val message = messageDao.getMessageById(messageId)
                        if (message != null) {
                            messageDao.insertMessage(message.copy(thumbnailUrl = file.absolutePath))
                        }
                    }
                    onComplete?.invoke(file?.absolutePath)
                }
            }
        )
    }
    
    /**
     * Pause a download.
     */
    fun pauseDownload(messageId: String) {
        downloadManager.pauseDownload(messageId)
    }
    
    /**
     * Resume a paused or failed download.
     */
    fun resumeDownload(messageId: String) {
        downloadManager.resumeDownload(messageId)
    }
    
    /**
     * Cancel a download.
     */
    fun cancelDownload(messageId: String) {
        downloadManager.cancelDownload(messageId)
    }
    
    // ===== STATE QUERIES =====
    
    /**
     * Get the local file path for a media message.
     * Returns null if the media is not downloaded.
     */
    fun getLocalFilePath(chatId: String, messageId: String, mediaType: MessageType): String? {
        val storageType = messageTypeToStorageType(mediaType) ?: return null
        val file = MediaStorageManager.getMediaFile(context, chatId, messageId, storageType)
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }
    
    /**
     * Check if media is available locally.
     */
    fun hasLocalFile(chatId: String, messageId: String, mediaType: MessageType): Boolean {
        val storageType = messageTypeToStorageType(mediaType) ?: return false
        return MediaStorageManager.hasLocalFile(context, chatId, messageId, storageType)
    }
    
    /**
     * Get the local thumbnail path.
     */
    fun getThumbnailPath(chatId: String, messageId: String): String? {
        val file = MediaStorageManager.getMediaFile(
            context, chatId, messageId, MediaStorageManager.MediaType.THUMBNAIL
        )
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }
    
    /**
     * Observe download progress for a message.
     */
    fun observeDownloadProgress(messageId: String): Flow<MediaDownloadManager.DownloadState?> {
        return downloadManager.observeDownload(messageId)
    }
    
    /**
     * Get the complete media state for UI.
     */
    fun getMediaState(message: LocalMessage): MediaState {
        val mediaType = messageTypeToStorageType(message.type)
        val hasLocal = mediaType != null && MediaStorageManager.hasLocalFile(
            context, message.chatId, message.id, mediaType
        )
        val localPath = if (hasLocal) {
            MediaStorageManager.getMediaFile(context, message.chatId, message.id, mediaType).absolutePath
        } else {
            message.localUri
        }
        
        val downloadState = downloadManager.getDownloadState(message.id)
        val uploadProgress = MediaProgressManager.getProgress(message.id)
        
        return MediaState(
            messageId = message.id,
            hasLocalFile = hasLocal || !message.localUri.isNullOrEmpty(),
            localFilePath = localPath,
            isUploading = uploadProgress?.isUploading == true,
            isDownloading = downloadState?.status == MediaDownloadManager.DownloadStatus.DOWNLOADING,
            uploadProgress = uploadProgress?.progress ?: 0f,
            downloadProgress = downloadState?.progress ?: 0f,
            error = downloadState?.error,
            canRetry = downloadState?.status == MediaDownloadManager.DownloadStatus.FAILED
        )
    }
    
    /**
     * Get the playback URI for a media message.
     * Returns local file if available, otherwise remote URL.
     * 
     * IMPORTANT: For offline-first experience, always prefer local file.
     */
    fun getPlaybackUri(message: LocalMessage): String? {
        // First, check for persisted local file
        val mediaType = messageTypeToStorageType(message.type)
        if (mediaType != null) {
            val localFile = MediaStorageManager.getMediaFile(
                context, message.chatId, message.id, mediaType
            )
            if (localFile.exists() && localFile.length() > 0) {
                return localFile.absolutePath
            }
        }
        
        // Fallback to localUri field (may be temp file)
        if (!message.localUri.isNullOrEmpty()) {
            val file = File(message.localUri)
            if (file.exists()) {
                return message.localUri
            }
        }
        
        // Last resort: remote URL (requires network)
        return when (message.type) {
            MessageType.IMAGE -> message.imageUrl
            MessageType.VIDEO -> message.videoUrl
            MessageType.AUDIO -> message.audioUrl
            else -> null
        }
    }
    
    /**
     * Check if media is ready for playback (local file exists).
     */
    fun isReadyForPlayback(message: LocalMessage): Boolean {
        val uri = getPlaybackUri(message)
        if (uri.isNullOrEmpty()) return false
        
        // If it's a local file path, verify it exists
        if (!uri.startsWith("http")) {
            return File(uri).exists()
        }
        
        // Remote URLs are technically "ready" but require network
        return true
    }
    
    // ===== CLEANUP =====
    
    /**
     * Delete all media for a message.
     */
    fun deleteMediaForMessage(chatId: String, messageId: String) {
        MediaStorageManager.deleteAllMediaForMessage(context, chatId, messageId)
    }
    
    /**
     * Delete all media for a chat.
     */
    fun deleteMediaForChat(chatId: String) {
        MediaStorageManager.deleteAllMediaForChat(context, chatId)
    }
    
    /**
     * Clean up temp files and stale media.
     */
    suspend fun performCleanup() {
        MediaStorageManager.cleanupTempFiles(context)
        MediaAcknowledgmentService.cleanupStaleMedia()
    }
    
    /**
     * Get total media storage size.
     */
    fun getTotalStorageSize(): Long {
        return MediaStorageManager.getTotalMediaSize(context)
    }
    
    // ===== HELPERS =====
    
    private fun messageTypeToStorageType(type: MessageType): MediaStorageManager.MediaType? {
        return when (type) {
            MessageType.IMAGE -> MediaStorageManager.MediaType.IMAGE
            MessageType.VIDEO -> MediaStorageManager.MediaType.VIDEO
            MessageType.AUDIO -> MediaStorageManager.MediaType.AUDIO
            MessageType.DOCUMENT -> MediaStorageManager.MediaType.DOCUMENT
            else -> null
        }
    }
    
    private suspend fun updateMessageLocalPath(
        messageId: String,
        localPath: String,
        mediaType: MediaStorageManager.MediaType
    ) {
        val message = messageDao.getMessageById(messageId) ?: return
        
        val updatedMessage = when (mediaType) {
            MediaStorageManager.MediaType.IMAGE -> message.copy(
                localUri = localPath,
                imageUrl = message.imageUrl // Keep remote URL as backup
            )
            MediaStorageManager.MediaType.VIDEO -> message.copy(
                localUri = localPath,
                videoUrl = message.videoUrl
            )
            MediaStorageManager.MediaType.AUDIO -> message.copy(
                localUri = localPath,
                audioUrl = message.audioUrl
            )
            MediaStorageManager.MediaType.THUMBNAIL -> message.copy(
                thumbnailUrl = localPath
            )
            else -> message.copy(localUri = localPath)
        }
        
        messageDao.insertMessage(updatedMessage)
    }
}
