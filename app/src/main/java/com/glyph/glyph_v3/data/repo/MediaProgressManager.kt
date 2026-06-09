package com.glyph.glyph_v3.data.repo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton manager to track upload/download progress for media messages.
 * Uses StateFlow for efficient, lifecycle-aware UI updates.
 */
object MediaProgressManager {
    
    /**
     * Progress state for a media upload/download operation
     */
    data class MediaProgress(
        val messageId: String,
        val progress: Float, // 0-100
        val isUploading: Boolean, // true = upload, false = download
        val totalBytes: Long = 0,
        val transferredBytes: Long = 0
    ) {
        val isComplete: Boolean get() = progress >= 100f
        val isIndeterminate: Boolean get() = progress < 0f
    }
    
    // Map of messageId to progress state
    private val _progressMap = MutableStateFlow<Map<String, MediaProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, MediaProgress>> = _progressMap.asStateFlow()
    
    // Emits (messageId, progress) pairs whenever progress changes
    private val _progressUpdates = MutableSharedFlow<Pair<String, Float>>(extraBufferCapacity = 64)
    val progressUpdates: SharedFlow<Pair<String, Float>> = _progressUpdates.asSharedFlow()
    
    /**
     * Update progress for a specific message
     */
    fun updateProgress(messageId: String, progress: Float, isUploading: Boolean = true, totalBytes: Long = 0, transferredBytes: Long = 0) {
        val current = _progressMap.value.toMutableMap()
        current[messageId] = MediaProgress(messageId, progress, isUploading, totalBytes, transferredBytes)
        _progressMap.value = current
        // Emit the update for UI notification
        _progressUpdates.tryEmit(messageId to progress)
    }
    
    /**
     * Start indeterminate progress (progress = -1)
     */
    fun startIndeterminate(messageId: String, isUploading: Boolean = true) {
        updateProgress(messageId, -1f, isUploading)
    }
    
    /**
     * Mark upload/download as complete and remove from tracking
     */
    fun complete(messageId: String) {
        val current = _progressMap.value.toMutableMap()
        current.remove(messageId)
        _progressMap.value = current
        _progressUpdates.tryEmit(messageId to 100f)
    }
    
    /**
     * Get current progress for a message (null if not tracked)
     */
    fun getProgress(messageId: String): MediaProgress? {
        return _progressMap.value[messageId]
    }
    
    /**
     * Get current progress value as Int percentage (0-100), or null if not tracked
     */
    fun getProgressValue(messageId: String): Int? {
        val progress = _progressMap.value[messageId] ?: return null
        return if (progress.progress >= 0) progress.progress.toInt() else null
    }
    
    /**
     * Check if a message has active upload/download
     */
    fun isActive(messageId: String): Boolean {
        return _progressMap.value.containsKey(messageId)
    }
    
    /**
     * Clear all progress tracking
     */
    fun clearAll() {
        _progressMap.value = emptyMap()
    }
}
