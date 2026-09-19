package com.glyph.glyph_v3.data.media

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.data.models.MessageType
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.glyph.glyph_v3.data.models.MessageType as UiMessageType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background worker for downloading media files.
 * This runs even when the app is in background or killed.
 *
 * Features:
 * - Downloads media to permanent local storage (atomic: temp file → verify → final path)
 * - Verifies transferred size against the message's expected fileSize
 * - Keeps the Firebase Storage copy (source of truth) and sends a download ACK so the
 *   sender can clean up once ALL recipients have acknowledged
 * - Retries on failure with exponential backoff
 * - Works for both IMAGE and VIDEO types
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "MediaDownloadWorker"
        
        // Input data keys
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_MEDIA_TYPE = "media_type"
        const val KEY_REMOTE_URL = "remote_url"
        const val KEY_FILE_SIZE = "file_size"
        /** For MEDIA_GROUP items: the real parent message ID that owns the mediaItems list. */
        const val KEY_GROUP_MESSAGE_ID = "group_message_id"
        /** For MEDIA_GROUP items: zero-based index of this item inside the mediaItems list. */
        const val KEY_ITEM_INDEX = "item_index"
        
        // Unique work name prefix
        private const val WORK_NAME_PREFIX = "media_download_"
        
        /**
         * Schedule a media download as background work.
         * Uses unique work to avoid duplicate downloads.
         */
        fun scheduleDownload(
            context: Context,
            messageId: String,
            chatId: String,
            mediaType: MessageType,
            remoteUrl: String,
            fileSize: Long? = null,
            groupMessageId: String? = null,
            itemIndex: Int = -1
        ) {
            if (remoteUrl.isBlank()) return
            
            val inputData = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_CHAT_ID to chatId,
                KEY_MEDIA_TYPE to mediaType.name,
                KEY_REMOTE_URL to remoteUrl,
                KEY_FILE_SIZE to (fileSize ?: 0L),
                KEY_GROUP_MESSAGE_ID to groupMessageId,
                KEY_ITEM_INDEX to itemIndex
            )
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val downloadWork = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS
                )
                .addTag("media_download")
                .addTag("message_$messageId")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "$WORK_NAME_PREFIX$messageId",
                    ExistingWorkPolicy.KEEP, // Don't restart if already running
                    downloadWork
                )
        }
        
        /**
         * Schedule downloads for all pending media messages.
         * Called on app startup to resume any interrupted downloads.
         */
        fun schedulePendingDownloads(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Backstop: the sender removes cloud media for ACK records older than
                    // MEDIA_TTL (recipients who never downloaded). Runs once per app start.
                    runCatching {
                        com.glyph.glyph_v3.data.media.MediaAcknowledgmentService.cleanupStaleMedia()
                    }.onFailure { e ->
                        Log.w(TAG, "ACK TTL cleanup failed: ${e.message}")
                    }

                    val db = AppDatabase.getDatabase(context)
                    val pendingMessages = db.messageDao().getMessagesWithPendingDownload()
                    
                    for (msg in pendingMessages) {
                        val remoteUrl = when (msg.type) {
                            MessageType.IMAGE,
                            MessageType.GIF,
                            MessageType.STICKER,
                            MessageType.KLIPY_EMOJI,
                            MessageType.MEME -> msg.imageUrl
                            MessageType.VIDEO -> msg.videoUrl
                            MessageType.AUDIO -> msg.audioUrl
                            else -> null
                        }

                        if (msg.type == MessageType.MEDIA_GROUP && !msg.mediaItems.isNullOrBlank()) {
                            val listType = object : TypeToken<List<MediaItem>>() {}.type
                            val items: List<MediaItem> = runCatching {
                                Gson().fromJson<List<MediaItem>>(msg.mediaItems, listType) ?: emptyList()
                            }.getOrDefault(emptyList())
                            items.forEachIndexed { index, item ->
                                if (item.url.orEmpty().isBlank() || hasExistingLocalUriCandidate(item.localUri)) return@forEachIndexed
                                val itemType = if (item.type == com.glyph.glyph_v3.data.models.MediaType.VIDEO) {
                                    MessageType.VIDEO
                                } else {
                                    MessageType.IMAGE
                                }
                                scheduleDownload(
                                    context = context,
                                    messageId = "${msg.id}_$index",
                                    chatId = msg.chatId,
                                    mediaType = itemType,
                                    remoteUrl = item.url,
                                    fileSize = item.fileSize,
                                    groupMessageId = msg.id,
                                    itemIndex = index
                                )
                            }
                            continue
                        }
                        
                        if (!remoteUrl.isNullOrEmpty()) {
                            val workerType = when (msg.type) {
                                MessageType.GIF,
                                MessageType.STICKER,
                                MessageType.KLIPY_EMOJI,
                                MessageType.MEME -> MessageType.IMAGE
                                else -> msg.type
                            }
                            scheduleDownload(
                                context = context,
                                messageId = msg.id,
                                chatId = msg.chatId,
                                mediaType = workerType,
                                remoteUrl = remoteUrl,
                                fileSize = msg.fileSize
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error scheduling pending downloads", e)
                }
            }
        }
        
        /**
         * Cancel a scheduled/running download.
         */
        fun cancelDownload(context: Context, messageId: String) {
            WorkManager.getInstance(context)
                .cancelUniqueWork("$WORK_NAME_PREFIX$messageId")
        }

        private fun hasExistingLocalUriCandidate(localUri: String?): Boolean {
            val candidate = localUri?.takeIf { it.isNotBlank() } ?: return false
            return isUsableLocalUri(candidate)
        }

        private fun isUsableLocalUri(candidate: String): Boolean {
            if (candidate.isBlank()) return false
            return runCatching {
                val parsed = android.net.Uri.parse(candidate)
                when {
                    parsed.scheme == "content" -> true
                    parsed.scheme == "file" || candidate.startsWith("/") -> {
                        val path = parsed.path ?: candidate
                        val file = java.io.File(path)
                        file.exists() && file.length() > 0L
                    }
                    parsed.scheme != null -> true
                    else -> {
                        val file = java.io.File(candidate)
                        file.exists() && file.length() > 0L
                    }
                }
            }.getOrDefault(false)
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return@withContext Result.failure()
        val chatId = inputData.getString(KEY_CHAT_ID) ?: return@withContext Result.failure()
        val mediaTypeStr = inputData.getString(KEY_MEDIA_TYPE) ?: return@withContext Result.failure()
        val remoteUrl = inputData.getString(KEY_REMOTE_URL) ?: return@withContext Result.failure()
        val fileSize = inputData.getLong(KEY_FILE_SIZE, 0L)
        val groupMsgId = inputData.getString(KEY_GROUP_MESSAGE_ID)
        val itemIdx = inputData.getInt(KEY_ITEM_INDEX, -1)
        
        val mediaType = try {
            MessageType.valueOf(mediaTypeStr)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid media type: $mediaTypeStr")
            return@withContext Result.failure()
        }
        
        val db = AppDatabase.getDatabase(applicationContext)
        val messageDao = db.messageDao()
        
        // Check if already downloaded
        val storageType = when (mediaType) {
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.STICKER,
            MessageType.KLIPY_EMOJI,
            MessageType.MEME -> MediaStorageManager.MediaType.IMAGE
            MessageType.VIDEO -> MediaStorageManager.MediaType.VIDEO
            MessageType.AUDIO -> MediaStorageManager.MediaType.AUDIO
            else -> return@withContext Result.failure()
        }

        if (!groupMsgId.isNullOrBlank() && itemIdx >= 0) {
            val parentMsg = messageDao.getMessageById(groupMsgId)
            val currentJson = parentMsg?.mediaItems?.takeIf { it.isNotBlank() }
            val listType = object : TypeToken<List<MediaItem>>() {}.type
            val items: List<MediaItem> = runCatching {
                Gson().fromJson<List<MediaItem>>(currentJson, listType) ?: emptyList()
            }.getOrDefault(emptyList())
            val existingLocal = items.getOrNull(itemIdx)?.localUri
            if (hasExistingLocalUri(existingLocal)) {
                updateGroupMessageDownloadProgress(messageDao, groupMsgId)
                return@withContext Result.success()
            }
        }
        
        if (MediaStorageManager.hasLocalFile(applicationContext, chatId, messageId, storageType)) {
            val existingFile = MediaStorageManager.getMediaFile(applicationContext, chatId, messageId, storageType)
            if (existingFile.exists() && existingFile.length() > 0L &&
                (fileSize <= 0L || existingFile.length() == fileSize)) {
                updateMessageLocalPath(messageDao, messageId, existingFile.absolutePath, storageType)
                if (!groupMsgId.isNullOrBlank() && itemIdx >= 0) {
                    updateGroupMessageItemLocalPath(messageDao, groupMsgId, itemIdx, existingFile.absolutePath)
                    updateGroupMessageDownloadProgress(messageDao, groupMsgId)
                }
                messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOADED)
                return@withContext Result.success()
            }
            // Truncated or size-mismatched leftover from an older non-atomic run —
            // remove it so the download below produces a complete file.
            Log.w(TAG, "Discarding incomplete local file for $messageId (${existingFile.length()} bytes, expected $fileSize)")
            existingFile.delete()
        }
        
        // Update status to downloading
        messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOADING)
        
        try {
            // Perform download (atomic: temp file → size verification → final location)
            val file = downloadMedia(chatId, messageId, storageType, remoteUrl, fileSize)

            if (file != null && file.exists()) {

                // Update local path in database
                updateMessageLocalPath(messageDao, messageId, file.absolutePath, storageType)

                // For MEDIA_GROUP items: update the parent message's mediaItems list
                if (!groupMsgId.isNullOrBlank() && itemIdx >= 0) {
                    updateGroupMessageItemLocalPath(messageDao, groupMsgId, itemIdx, file.absolutePath)
                    updateGroupMessageDownloadProgress(messageDao, groupMsgId)
                }

                // Update status
                messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOADED)

                // Refresh chat notification to attach local media
                try {
                    com.glyph.glyph_v3.data.service.ChatNotificationUpdater.refreshChatNotification(
                        context = applicationContext,
                        chatId = chatId
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Notification refresh failed: ${e.message}")
                }

                // The remote copy stays in Firebase Storage as the source of truth so
                // other recipients/devices/reinstalls can still download it. It is
                // removed by the SENDER once every registered recipient acknowledges
                // (MediaAcknowledgmentService). Acknowledge our copy now; never fatal.
                val ackKey = if (!groupMsgId.isNullOrBlank() && itemIdx >= 0) {
                    "${groupMsgId}_item_$itemIdx"
                } else {
                    messageId
                }
                try {
                    com.glyph.glyph_v3.data.media.MediaAcknowledgmentService.sendAcknowledgment(ackKey)
                } catch (e: Exception) {
                    Log.w(TAG, "Media ACK send failed for $ackKey: ${e.message}")
                }

                return@withContext Result.success()
            } else {
                Log.e(TAG, "Download returned null file")
                messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOAD_FAILED)
                return@withContext Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            
            // Check if we should retry
            if (runAttemptCount < 3) {
                messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOADING)
                return@withContext Result.retry()
            } else {
                messageDao.updateMessageStatus(messageId, MessageStatus.DOWNLOAD_FAILED)
                return@withContext Result.failure()
            }
        }
    }
    
    private suspend fun downloadMedia(
        chatId: String,
        messageId: String,
        mediaType: MediaStorageManager.MediaType,
        remoteUrl: String,
        expectedSize: Long
    ): java.io.File? {
        val tempFile = java.io.File(
            MediaStorageManager.getTempDirectory(applicationContext),
            "$messageId.download"
        )
        return try {
            if (tempFile.exists()) tempFile.delete()

            // Determine if this is an HTTPS URL or gs:// URL
            val downloaded = if (remoteUrl.startsWith("https://")) {
                downloadViaHttp(remoteUrl, tempFile) || downloadViaStorageRef(remoteUrl, tempFile)
            } else {
                downloadViaStorageRef(remoteUrl, tempFile)
            }

            if (!downloaded || !tempFile.exists() || tempFile.length() == 0L) {
                tempFile.delete()
                return null
            }

            // Verify the transfer completed fully before it becomes the playable file
            if (expectedSize > 0L && tempFile.length() != expectedSize) {
                Log.e(TAG, "Downloaded size mismatch for $messageId: expected $expectedSize, got ${tempFile.length()}")
                tempFile.delete()
                return null
            }

            // Atomic finalize: temp → final location
            val targetFile = MediaStorageManager.getMediaFile(applicationContext, chatId, messageId, mediaType)
            targetFile.parentFile?.mkdirs()
            if (targetFile.exists()) targetFile.delete()
            if (tempFile.renameTo(targetFile)) {
                targetFile
            } else {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading media", e)
            tempFile.delete()
            null
        }
    }

    private suspend fun downloadViaHttp(
        httpsUrl: String,
        tempFile: java.io.File
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: java.net.HttpURLConnection? = null
        try {
            val url = java.net.URL(httpsUrl)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.connect()

            if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error: ${connection.responseCode}")
                return@withContext false
            }

            val contentLength = connection.contentLengthLong

            connection.inputStream.use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }

            // Reject truncated reads against the advertised content length
            if (contentLength > 0 && tempFile.length() != contentLength) {
                Log.e(TAG, "HTTP transfer incomplete: expected $contentLength bytes, got ${tempFile.length()}")
                tempFile.delete()
                return@withContext false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "HTTP download error", e)
            tempFile.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun downloadViaStorageRef(
        remoteUrl: String,
        tempFile: java.io.File
    ): Boolean = withContext(Dispatchers.IO) {
        try {

            val storage = com.google.firebase.storage.FirebaseStorage.getInstance()
            val storageRef = storage.getReferenceFromUrl(remoteUrl)

            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { continuation ->
                storageRef.getFile(tempFile)
                    .addOnSuccessListener {
                        continuation.resume(true, null)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Storage download failed", e)
                        tempFile.delete()
                        continuation.resume(false, null)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Storage ref download error", e)
            tempFile.delete()
            false
        }
    }
    
    private suspend fun updateMessageLocalPath(
        messageDao: com.glyph.glyph_v3.data.local.dao.MessageDao,
        messageId: String,
        localPath: String,
        mediaType: MediaStorageManager.MediaType
    ) {
        when (mediaType) {
            MediaStorageManager.MediaType.IMAGE,
            MediaStorageManager.MediaType.VIDEO -> {
                messageDao.updateLocalUri(messageId, localPath)
            }
            MediaStorageManager.MediaType.THUMBNAIL -> Unit
            MediaStorageManager.MediaType.AUDIO -> {
                messageDao.updateLocalUri(messageId, localPath)
            }
            MediaStorageManager.MediaType.DOCUMENT -> Unit
        }
    }

    /**
     * After a MEDIA_GROUP item is downloaded, update the parent message's mediaItems JSON
     * so the adapter reads the local path instead of attempting the (possibly deleted) remote URL.
     */
    private suspend fun updateGroupMessageItemLocalPath(
        messageDao: com.glyph.glyph_v3.data.local.dao.MessageDao,
        groupMessageId: String,
        itemIndex: Int,
        localPath: String
    ) {
        try {
            val parentMsg = messageDao.getMessageById(groupMessageId) ?: return
            val currentJson = parentMsg.mediaItems?.takeIf { it.isNotBlank() } ?: return
            val listType = object : com.google.gson.reflect.TypeToken<MutableList<MediaItem>>() {}.type
            val items: MutableList<MediaItem> = Gson().fromJson(currentJson, listType) ?: return
            if (itemIndex >= items.size) return
            items[itemIndex] = items[itemIndex].copy(localUri = localPath)
            messageDao.updateMessageMediaItems(groupMessageId, Gson().toJson(items))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update MEDIA_GROUP item[$itemIndex] local path: ${e.message}")
        }
    }

    private suspend fun updateGroupMessageDownloadProgress(
        messageDao: com.glyph.glyph_v3.data.local.dao.MessageDao,
        groupMessageId: String
    ) {
        try {
            val parentMsg = messageDao.getMessageById(groupMessageId)
            val currentJson = parentMsg?.mediaItems?.takeIf { it.isNotBlank() }
            if (currentJson.isNullOrBlank()) {
                MediaProgressManager.complete(groupMessageId)
                return
            }

            val listType = object : TypeToken<List<MediaItem>>() {}.type
            val items: List<MediaItem> = Gson().fromJson(currentJson, listType) ?: emptyList()
            if (items.isEmpty()) {
                MediaProgressManager.complete(groupMessageId)
                return
            }

            val downloadedCount = items.count { hasExistingLocalUri(it.localUri) }
            if (downloadedCount >= items.size) {
                messageDao.updateMessageStatus(groupMessageId, MessageStatus.DOWNLOADED)
                MediaProgressManager.complete(groupMessageId)
            } else {
                messageDao.updateMessageStatus(groupMessageId, MessageStatus.DOWNLOADING)
                val progress = (downloadedCount * 100f) / items.size.toFloat()
                MediaProgressManager.updateProgress(
                    groupMessageId,
                    progress,
                    isUploading = false,
                    totalBytes = items.size.toLong(),
                    transferredBytes = downloadedCount.toLong()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update MEDIA_GROUP progress for $groupMessageId: ${e.message}")
        }
    }

    private fun hasExistingLocalUri(localUri: String?): Boolean {
        val candidate = localUri?.takeIf { it.isNotBlank() } ?: return false
        return isUsableLocalUri(candidate)
    }

}
