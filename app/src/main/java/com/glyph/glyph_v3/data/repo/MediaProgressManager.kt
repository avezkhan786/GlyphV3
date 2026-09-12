package com.glyph.glyph_v3.data.repo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton manager to track upload/download progress for media messages.
 * Uses StateFlow for efficient, lifecycle-aware UI updates.
 *
 * Emissions to [progressUpdates] are throttled (min 100 ms AND min 1% delta per
 * message) so large transfers don't flood the UI with rebinds; progress 0 and
 * completion are always emitted. A smoothed transfer speed is tracked per message.
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
        val transferredBytes: Long = 0,
        val speedBps: Float = 0f // smoothed transfer rate in bytes/second
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

    /** Min interval between emissions for the same message. */
    private const val MIN_EMIT_INTERVAL_MS = 100L
    /** Min progress delta between emissions for the same message. */
    private const val MIN_EMIT_DELTA_PERCENT = 1f
    /** EMA weight applied to a fresh speed sample. */
    private const val SPEED_EMA_ALPHA = 0.3f

    // Per-message throttle bookkeeping: last emission wall-clock and progress
    private data class EmitRecord(val atMs: Long, val progress: Float)
    private val lastEmit = ConcurrentHashMap<String, EmitRecord>()

    // Per-message speed sampling: (lastTimeMs, lastBytes, emaBps)
    private class SpeedSample(var atMs: Long, var bytes: Long, var emaBps: Float = 0f)
    private val speedSamples = ConcurrentHashMap<String, SpeedSample>()

    /**
     * Update progress for a specific message
     */
    fun updateProgress(messageId: String, progress: Float, isUploading: Boolean = true, totalBytes: Long = 0, transferredBytes: Long = 0) {
        val speed = sampleSpeed(messageId, transferredBytes)
        val current = _progressMap.value.toMutableMap()
        current[messageId] = MediaProgress(messageId, progress, isUploading, totalBytes, transferredBytes, speed)
        _progressMap.value = current

        // Throttled emission: skip if we emitted recently and the delta is tiny.
        // progress <= 0 (start/indeterminate) always passes so the UI flips immediately.
        val now = System.currentTimeMillis()
        val last = lastEmit[messageId]
        val shouldEmit = progress <= 0f || last == null ||
            (now - last.atMs >= MIN_EMIT_INTERVAL_MS &&
                kotlin.math.abs(progress - last.progress) >= MIN_EMIT_DELTA_PERCENT)
        if (shouldEmit) {
            lastEmit[messageId] = EmitRecord(now, progress)
            _progressUpdates.tryEmit(messageId to progress)
        }
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
        lastEmit.remove(messageId)
        speedSamples.remove(messageId)
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
        lastEmit.clear()
        speedSamples.clear()
    }

    /**
     * Compute a smoothed transfer rate from consecutive byte samples.
     * Returns 0 until at least one interval has elapsed.
     */
    private fun sampleSpeed(messageId: String, transferredBytes: Long): Float {
        val now = System.currentTimeMillis()
        val sample = speedSamples[messageId]
        if (sample == null) {
            speedSamples[messageId] = SpeedSample(now, transferredBytes)
            return 0f
        }
        val elapsedMs = now - sample.atMs
        val deltaBytes = transferredBytes - sample.bytes
        return if (elapsedMs >= 500 && deltaBytes >= 0) {
            val instantBps = deltaBytes * 1000f / elapsedMs
            sample.emaBps = if (sample.emaBps == 0f) instantBps else sample.emaBps + SPEED_EMA_ALPHA * (instantBps - sample.emaBps)
            sample.atMs = now
            sample.bytes = transferredBytes
            sample.emaBps
        } else {
            sample.emaBps
        }
    }
}