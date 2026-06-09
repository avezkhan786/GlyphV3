package com.glyph.glyph_v3.data.media

import android.content.Context
import android.util.Log
import com.glyph.glyph_v3.data.repo.MediaProgressManager
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages media downloads with:
 * - Resume support for interrupted downloads
 * - Progress tracking
 * - Retry logic with exponential backoff
 * - State persistence across app restarts
 * - Concurrent download limits
 */
class MediaDownloadManager private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaDownloadManager"
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val PREFS_NAME = "media_downloads"
        
        @Volatile
        private var instance: MediaDownloadManager? = null
        
        fun getInstance(context: Context): MediaDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: MediaDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    data class DownloadState(
        val messageId: String,
        val chatId: String,
        val mediaType: MediaStorageManager.MediaType,
        val remoteUrl: String,
        val status: DownloadStatus,
        val progress: Float = 0f,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String? = null,
        val retryCount: Int = 0
    )
    
    enum class DownloadStatus {
        QUEUED,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        FAILED
    }
    
    private val storage = FirebaseStorage.getInstance()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    // Active downloads state
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    
    // Download queue
    private val downloadQueue = ConcurrentHashMap<String, DownloadRequest>()
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val groupActiveCounts = ConcurrentHashMap<String, Int>()
    
    data class DownloadRequest(
        val messageId: String,
        val chatId: String,
        val mediaType: MediaStorageManager.MediaType,
        val remoteUrl: String,
        val expectedSize: Long? = null,
        val onComplete: ((File?) -> Unit)? = null,
        val itemIndex: Int? = null,
        val groupMessageId: String? = null
    )
    
    init {
        // Restore pending downloads from preferences
        restorePendingDownloads()
    }
    
    /**
     * Queue a media download
     */
    fun queueDownload(
        messageId: String,
        chatId: String,
        mediaType: MediaStorageManager.MediaType,
        remoteUrl: String,
        expectedSize: Long? = null,
        onComplete: ((File?) -> Unit)? = null,
        itemIndex: Int? = null
    ) {
        // Extract base messageId for collage items (messageId_item_N -> messageId)
        val baseMessageId = if (messageId.contains("_item_")) {
            messageId.substringBefore("_item_")
        } else {
            messageId
        }
        
        // Check if already downloaded (use baseMessageId with itemIndex for collage items)
        val checkMessageId = if (itemIndex != null) baseMessageId else messageId
        if (MediaStorageManager.hasLocalFile(context, chatId, checkMessageId, mediaType, itemIndex = itemIndex)) {
            val file = MediaStorageManager.getMediaFile(context, chatId, checkMessageId, mediaType, itemIndex = itemIndex)
            onComplete?.invoke(file)
            return
        }
        
        // Check if already downloading
        if (activeDownloads.containsKey(messageId)) {
            return
        }
        
        val groupMessageId = if (itemIndex != null) baseMessageId else null
        val request = DownloadRequest(messageId, chatId, mediaType, remoteUrl, expectedSize, onComplete, itemIndex, groupMessageId)
        downloadQueue[messageId] = request
        
        // Update state
        updateState(messageId) { current ->
            current?.copy(status = DownloadStatus.QUEUED)
                ?: DownloadState(
                    messageId = messageId,
                    chatId = chatId,
                    mediaType = mediaType,
                    remoteUrl = remoteUrl,
                    status = DownloadStatus.QUEUED,
                    totalBytes = expectedSize ?: 0L
                )
        }
        
        // Persist to prefs for recovery
        savePendingDownload(request)
        
        // Process queue
        processQueue()
    }
    
    /**
     * Process the download queue
     */
    private fun processQueue() {
        if (activeDownloads.size >= MAX_CONCURRENT_DOWNLOADS) {
            return
        }
        
        val available = MAX_CONCURRENT_DOWNLOADS - activeDownloads.size
        val toStart = downloadQueue.entries
            .filter { !activeDownloads.containsKey(it.key) }
            .take(available)
        
        toStart.forEach { (messageId, request) ->
            startDownload(request)
        }
    }
    
    /**
     * Start a download
     */
    private fun startDownload(request: DownloadRequest) {
        val job = scope.launch {
            val groupId = request.groupMessageId
            if (groupId != null) {
                val newCount = groupActiveCounts.merge(groupId, 1) { old, one -> old + one } ?: 1
                if (newCount == 1) {
                    MediaProgressManager.startIndeterminate(groupId, isUploading = false)
                }
            }
            try {
                updateState(request.messageId) { it?.copy(status = DownloadStatus.DOWNLOADING) }
                
                val file = downloadWithRetry(request)
                
                if (file != null) {
                    updateState(request.messageId) { 
                        it?.copy(
                            status = DownloadStatus.COMPLETED,
                            progress = 1f,
                            bytesDownloaded = file.length(),
                            totalBytes = file.length()
                        )
                    }
                    removePendingDownload(request.messageId)
                    request.onComplete?.invoke(file)
                } else {
                    updateState(request.messageId) { 
                        it?.copy(status = DownloadStatus.FAILED, error = "Download failed after retries")
                    }
                    request.onComplete?.invoke(null)
                }
            } catch (e: CancellationException) {
                updateState(request.messageId) { it?.copy(status = DownloadStatus.PAUSED) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${request.messageId}", e)
                updateState(request.messageId) { 
                    it?.copy(status = DownloadStatus.FAILED, error = e.message)
                }
                request.onComplete?.invoke(null)
            } finally {
                if (groupId != null) {
                    val newCount = (groupActiveCounts[groupId] ?: 1) - 1
                    if (newCount <= 0) {
                        groupActiveCounts.remove(groupId)
                        MediaProgressManager.complete(groupId)
                    } else {
                        groupActiveCounts[groupId] = newCount
                    }
                }
                activeDownloads.remove(request.messageId)
                downloadQueue.remove(request.messageId)
                processQueue()
            }
        }
        
        activeDownloads[request.messageId] = job
    }
    
    /**
     * Download with retry logic and exponential backoff
     */
    private suspend fun downloadWithRetry(request: DownloadRequest): File? {
        var lastException: Exception? = null
        var retryDelay = INITIAL_RETRY_DELAY_MS
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                updateState(request.messageId) { it?.copy(retryCount = attempt) }
                
                val file = performDownload(request)
                if (file != null) {
                    return file
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Download attempt ${attempt + 1} failed: ${e.message}")
                
                if (attempt < MAX_RETRIES - 1) {
                    delay(retryDelay)
                    retryDelay *= 2 // Exponential backoff
                }
            }
        }
        
        Log.e(TAG, "All download attempts failed for ${request.messageId}", lastException)
        return null
    }
    
    /**
     * Perform the actual download.
     * Handles both Firebase Storage URLs (gs://) and HTTPS download URLs.
     */
    private suspend fun performDownload(request: DownloadRequest): File? = withContext(Dispatchers.IO) {
        
        // Get the temp file for download
        val tempFile = File(
            MediaStorageManager.getTempDirectory(context),
            "${request.messageId}.download"
        )
        
        // Delete any existing partial download to start fresh
        if (tempFile.exists()) {
            tempFile.delete()
        }
        
        try {
            // Determine if this is a Firebase Storage reference URL or HTTPS download URL
            val isStorageUrl = request.remoteUrl.startsWith("gs://")
            val isHttpsUrl = request.remoteUrl.startsWith("https://")
            
            if (isStorageUrl) {
                // Use Firebase Storage SDK for gs:// URLs
                return@withContext downloadViaStorageRef(request, tempFile)
            } else if (isHttpsUrl) {
                // Use HTTP download for HTTPS URLs (download URLs from Firebase)
                return@withContext downloadViaHttp(request, tempFile)
            } else {
                Log.e(TAG, "Unknown URL format: ${request.remoteUrl}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download exception: ${e.message}", e)
            tempFile.delete()
            throw e
        }
    }
    
    /**
     * Download via Firebase Storage reference (for gs:// URLs)
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadViaStorageRef(request: DownloadRequest, tempFile: File): File? = withContext(Dispatchers.IO) {
        val storageRef = try {
            storage.getReferenceFromUrl(request.remoteUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid storage URL: ${request.remoteUrl}", e)
            return@withContext null
        }
        
        // Start progress tracking
        MediaProgressManager.updateProgress(request.messageId, 0f, isUploading = false)
        
        // Get metadata
        val metadata = suspendCancellableCoroutine<com.google.firebase.storage.StorageMetadata?> { cont ->
            storageRef.metadata
                .addOnSuccessListener { cont.resume(it) {} }
                .addOnFailureListener { cont.resume(null) {} }
        }
        
        val totalBytes = metadata?.sizeBytes ?: request.expectedSize ?: 0L
        updateState(request.messageId) { it?.copy(totalBytes = totalBytes) }
        
        // Download
        val downloadTask = storageRef.getFile(tempFile)
        
        // Track progress
        downloadTask.addOnProgressListener { snapshot ->
            val progress = if (snapshot.totalByteCount > 0) {
                (snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat()) * 100f
            } else 0f
            
            scope.launch {
                updateState(request.messageId) {
                    it?.copy(
                        progress = progress / 100f,
                        bytesDownloaded = snapshot.bytesTransferred,
                        totalBytes = snapshot.totalByteCount
                    )
                }
                
                // Update MediaProgressManager for UI
                MediaProgressManager.updateProgress(
                    request.messageId,
                    progress,
                    isUploading = false,
                    totalBytes = snapshot.totalByteCount,
                    transferredBytes = snapshot.bytesTransferred
                )
            }
        }
        
        // Wait for completion
        val success = suspendCancellableCoroutine<Boolean> { cont ->
            downloadTask
                .addOnSuccessListener { cont.resume(true) {} }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Storage download failed: ${e.message}", e)
                    cont.resume(false) {}
                }
            
            cont.invokeOnCancellation { downloadTask.cancel() }
        }
        
        // Complete progress tracking
        MediaProgressManager.complete(request.messageId)
        
        if (success && tempFile.exists() && tempFile.length() > 0) {
            return@withContext moveToFinalLocation(tempFile, request)
        }
        
        return@withContext null
    }
    
    /**
     * Download via HTTP (for https:// download URLs from Firebase Storage)
     */
    private suspend fun downloadViaHttp(request: DownloadRequest, tempFile: File): File? = withContext(Dispatchers.IO) {
        
        // Start progress tracking (isUploading = false for downloads)
        MediaProgressManager.updateProgress(request.messageId, 0f, isUploading = false)
        
        var connection: java.net.HttpURLConnection? = null
        var inputStream: java.io.InputStream? = null
        var outputStream: java.io.FileOutputStream? = null
        
        try {
            val url = java.net.URL(request.remoteUrl)
            connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.connect()
            
            val responseCode = connection.responseCode
            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error code: $responseCode")
                MediaProgressManager.complete(request.messageId)
                return@withContext null
            }
            
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: request.expectedSize ?: 0L
            updateState(request.messageId) { it?.copy(totalBytes = totalBytes) }
            
            inputStream = connection.inputStream
            outputStream = java.io.FileOutputStream(tempFile)
            
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L
            var lastProgressUpdate = 0L
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                // Update progress (throttle to avoid too many updates)
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 100) { // Update every 100ms
                    val progress = if (totalBytes > 0) {
                        (totalBytesRead.toFloat() / totalBytes.toFloat()) * 100f
                    } else 0f
                    
                    updateState(request.messageId) {
                        it?.copy(
                            progress = progress / 100f,
                            bytesDownloaded = totalBytesRead,
                            totalBytes = totalBytes
                        )
                    }
                    
                    // Also update MediaProgressManager for UI
                    MediaProgressManager.updateProgress(
                        request.messageId, 
                        progress, 
                        isUploading = false,
                        totalBytes = totalBytes,
                        transferredBytes = totalBytesRead
                    )
                    
                    lastProgressUpdate = now
                }
            }
            
            outputStream.flush()
            outputStream.close()
            inputStream.close()
            
            
            // Mark progress as complete
            MediaProgressManager.complete(request.messageId)
            
            if (tempFile.exists() && tempFile.length() > 0) {
                return@withContext moveToFinalLocation(tempFile, request)
            }
            
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(TAG, "HTTP download failed: ${e.message}", e)
            MediaProgressManager.complete(request.messageId)
            throw e
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
                connection?.disconnect()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
    
    /**
     * Move completed download to final location
     */
    private suspend fun moveToFinalLocation(tempFile: File, request: DownloadRequest): File? {
        // Extract base messageId for collage items
        val baseMessageId = if (request.messageId.contains("_item_")) {
            request.messageId.substringBefore("_item_")
        } else {
            request.messageId
        }
        
        return MediaStorageManager.saveMediaFromFile(
            context = context,
            chatId = request.chatId,
            messageId = baseMessageId,
            mediaType = request.mediaType,
            sourceFile = tempFile,
            itemIndex = request.itemIndex
        ).also {
            // Clean up temp file
            tempFile.delete()
        }
    }
    
    /**
     * Pause a download
     */
    fun pauseDownload(messageId: String) {
        activeDownloads[messageId]?.cancel()
        updateState(messageId) { it?.copy(status = DownloadStatus.PAUSED) }
    }
    
    /**
     * Resume a paused download
     */
    fun resumeDownload(messageId: String) {
        val state = _downloads.value[messageId]
        if (state?.status == DownloadStatus.PAUSED || state?.status == DownloadStatus.FAILED) {
            queueDownload(
                messageId = state.messageId,
                chatId = state.chatId,
                mediaType = state.mediaType,
                remoteUrl = state.remoteUrl,
                expectedSize = state.totalBytes.takeIf { it > 0 }
            )
        }
    }
    
    /**
     * Cancel a download
     */
    fun cancelDownload(messageId: String) {
        activeDownloads[messageId]?.cancel()
        activeDownloads.remove(messageId)
        downloadQueue.remove(messageId)
        removePendingDownload(messageId)
        
        // Clean up temp file
        val tempFile = File(MediaStorageManager.getTempDirectory(context), "${messageId}.download")
        tempFile.delete()
        
        _downloads.update { it - messageId }
    }
    
    /**
     * Get download state for a message
     */
    fun getDownloadState(messageId: String): DownloadState? {
        return _downloads.value[messageId]
    }
    
    /**
     * Observe download state for a message
     */
    fun observeDownload(messageId: String): Flow<DownloadState?> {
        return _downloads.map { it[messageId] }
            .distinctUntilChanged()
    }
    
    /**
     * Update state helper
     */
    private fun updateState(messageId: String, update: (DownloadState?) -> DownloadState?) {
        _downloads.update { map ->
            val current = map[messageId]
            val updated = update(current)
            if (updated != null) {
                map + (messageId to updated)
            } else {
                map - messageId
            }
        }
    }
    
    /**
     * Save pending download to preferences for recovery
     */
    private fun savePendingDownload(request: DownloadRequest) {
        val key = "download_${request.messageId}"
        prefs.edit()
            .putString("${key}_chatId", request.chatId)
            .putString("${key}_mediaType", request.mediaType.name)
            .putString("${key}_url", request.remoteUrl)
            .putLong("${key}_size", request.expectedSize ?: 0L)
            .apply()
        
        // Add to pending list
        val pending = prefs.getStringSet("pending_downloads", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        pending.add(request.messageId)
        prefs.edit().putStringSet("pending_downloads", pending).apply()
    }
    
    /**
     * Remove pending download from preferences
     */
    private fun removePendingDownload(messageId: String) {
        val key = "download_$messageId"
        prefs.edit()
            .remove("${key}_chatId")
            .remove("${key}_mediaType")
            .remove("${key}_url")
            .remove("${key}_size")
            .apply()
        
        val pending = prefs.getStringSet("pending_downloads", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        pending.remove(messageId)
        prefs.edit().putStringSet("pending_downloads", pending).apply()
    }
    
    /**
     * Restore pending downloads after app restart
     */
    private fun restorePendingDownloads() {
        val pending = prefs.getStringSet("pending_downloads", emptySet()) ?: return
        
        pending.forEach { messageId ->
            val key = "download_$messageId"
            val chatId = prefs.getString("${key}_chatId", null) ?: return@forEach
            val mediaTypeName = prefs.getString("${key}_mediaType", null) ?: return@forEach
            val url = prefs.getString("${key}_url", null) ?: return@forEach
            val size = prefs.getLong("${key}_size", 0L)
            
            val mediaType = try {
                MediaStorageManager.MediaType.valueOf(mediaTypeName)
            } catch (e: Exception) {
                return@forEach
            }
            
            // Check if already downloaded
            if (!MediaStorageManager.hasLocalFile(context, chatId, messageId, mediaType)) {
                queueDownload(messageId, chatId, mediaType, url, size.takeIf { it > 0 })
            } else {
                removePendingDownload(messageId)
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
        activeDownloads.clear()
        downloadQueue.clear()
    }
}
