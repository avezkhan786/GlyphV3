package com.glyph.glyph_v3.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.glyph.glyph_v3.data.models.CompressionEstimateResult
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.data.models.MediaEstimate
import com.glyph.glyph_v3.data.models.SelectedMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Utility for fast media size estimation without full encoding.
 * Uses metadata-only analysis to estimate compressed output sizes.
 */
object MediaEstimationUtil {
    
    // Typical compression efficiency factors
    private const val H264_EFFICIENCY_FACTOR = 0.85f // MediaCodec H.264 typical efficiency
    private const val HEVC_EFFICIENCY_FACTOR = 0.70f // HEVC is more efficient
    private const val JPEG_BASELINE_BPP = 0.5f // Bits per pixel for JPEG at quality 100
    private const val WEBP_EFFICIENCY = 0.75f // WebP vs JPEG
    
    /**
     * Extract metadata from a URI without loading full content into memory
     */
    suspend fun extractMediaMetadata(
        context: Context,
        uri: Uri,
        mimeType: String
    ): SelectedMediaItem = withContext(Dispatchers.IO) {
        val isVideo = mimeType.startsWith("video/")
        
        // Get file size and display name
        var fileSize = 0L
        var displayName = ""
        
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: ""
            }
        }
        
        // Fallback for file size
        if (fileSize == 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { 
                    fileSize = it.available().toLong()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        if (isVideo) {
            extractVideoMetadata(context, uri, mimeType, fileSize, displayName)
        } else {
            extractImageMetadata(context, uri, mimeType, fileSize, displayName)
        }
    }
    
    private fun extractVideoMetadata(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileSize: Long,
        displayName: String
    ): SelectedMediaItem {
        var width = 0
        var height = 0
        var duration = 0L
        var bitrate = 0
        
        // Try MediaMetadataRetriever first (more reliable)
        try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                
                // Handle rotation
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val temp = width
                    width = height
                    height = temp
                }
            }
        } catch (e: Exception) {
            // Fallback to MediaExtractor
            try {
                MediaExtractor().apply {
                    setDataSource(context, uri, null)
                    for (i in 0 until trackCount) {
                        val format = getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/")) {
                            width = format.getIntegerSafe(MediaFormat.KEY_WIDTH) ?: 0
                            height = format.getIntegerSafe(MediaFormat.KEY_HEIGHT) ?: 0
                            duration = format.getLongSafe(MediaFormat.KEY_DURATION)?.div(1000) ?: 0 // Convert to ms
                            bitrate = format.getIntegerSafe(MediaFormat.KEY_BIT_RATE) ?: 0
                            break
                        }
                    }
                    release()
                }
            } catch (e: Exception) {
                // Use defaults
            }
        }
        
        // Estimate bitrate if not available
        if (bitrate == 0 && duration > 0 && fileSize > 0) {
            bitrate = ((fileSize * 8) / (duration / 1000.0)).toInt()
        }
        
        return SelectedMediaItem(
            uri = uri,
            mimeType = mimeType,
            originalSize = fileSize,
            width = width,
            height = height,
            duration = duration,
            bitrate = bitrate,
            displayName = displayName
        )
    }
    
    private fun extractImageMetadata(
        context: Context,
        uri: Uri,
        mimeType: String,
        fileSize: Long,
        displayName: String
    ): SelectedMediaItem {
        var width = 0
        var height = 0
        
        // Use BitmapFactory.Options to get dimensions without loading bitmap
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                width = options.outWidth
                height = options.outHeight
            }
            
            // Handle EXIF rotation for images
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270
                ) {
                    val temp = width
                    width = height
                    height = temp
                }
            }
        } catch (e: Exception) {
            // Fallback to default dimensions
            width = 1920
            height = 1080
        }
        
        return SelectedMediaItem(
            uri = uri,
            mimeType = mimeType,
            originalSize = fileSize,
            width = width,
            height = height,
            duration = 0,
            bitrate = 0,
            displayName = displayName
        )
    }
    
    /**
     * Estimate compressed size for a single media item at a given quality level.
     * Uses actual compression measure for images, and fast calculation for videos.
     */
    suspend fun estimateCompressedSize(
        context: Context,
        item: SelectedMediaItem,
        quality: CompressionQuality
    ): MediaEstimate {
        return if (item.isVideo) {
            estimateVideoSize(item, quality)
        } else {
            estimateImageSize(context, item, quality)
        }
    }
    
    private suspend fun estimateVideoSize(
        item: SelectedMediaItem,
        quality: CompressionQuality
    ): MediaEstimate {
        if (quality == CompressionQuality.ORIGINAL) {
            return MediaEstimate(
                item = item,
                quality = quality,
                estimatedSize = item.originalSize,
                reductionPercent = 0,
                outputWidth = item.width,
                outputHeight = item.height
            )
        }
        
        // Calculate output resolution
        val (outputWidth, outputHeight) = calculateOutputResolution(
            item.width, item.height, quality.videoMaxResolution,
            ensureEven = true
        )
        
        // Calculate target bitrate based on quality preset and resolution
        val targetBitrateBps = (quality.videoTargetBitrateMbps * 1_000_000).toLong()
        
        // Adjust bitrate based on resolution scaling
        val resolutionScale = (outputWidth * outputHeight).toFloat() / (item.width * item.height).toFloat()
        val adjustedBitrate = (targetBitrateBps * resolutionScale).toLong()
        
        // Estimate duration in seconds
        val durationSec = item.duration / 1000.0
        
        // Calculate estimated video size (bitrate * duration)
        val videoSize = (adjustedBitrate * durationSec / 8).roundToLong()
        
        // Add audio overhead (typically 128kbps AAC)
        val audioBitrate = 128_000L // 128 kbps
        val audioSize = (audioBitrate * durationSec / 8).roundToLong()
        
        // Add container overhead (~5%)
        val containerOverhead = ((videoSize + audioSize) * 0.05).roundToLong()
        
        val estimatedSize = videoSize + audioSize + containerOverhead
        
        // Don't estimate larger than original
        val finalSize = min(estimatedSize, item.originalSize)
        
        val reductionPercent = if (item.originalSize > 0) {
            ((item.originalSize - finalSize) * 100 / item.originalSize).toInt()
        } else 0
        
        return MediaEstimate(
            item = item,
            quality = quality,
            estimatedSize = finalSize,
            reductionPercent = max(0, reductionPercent),
            outputWidth = outputWidth,
            outputHeight = outputHeight
        )
    }
    
    private suspend fun estimateImageSize(
        context: Context,
        item: SelectedMediaItem,
        quality: CompressionQuality
    ): MediaEstimate {
        if (quality == CompressionQuality.ORIGINAL) {
            return MediaEstimate(
                item = item,
                quality = quality,
                estimatedSize = item.originalSize,
                reductionPercent = 0,
                outputWidth = item.width,
                outputHeight = item.height
            )
        }
        
        // Calculate output resolution to display in UI
        val (outputWidth, outputHeight) = calculateOutputResolution(
            item.width, item.height, quality.imageMaxResolution
        )

        // Perform actual compression measurement for perfect accuracy
        // This is slower but guarantees the "what you see is what you get" principle
        val actualSize = MediaCompressor.calculateCompressedImageSize(context, item, quality)
        
        val reductionPercent = if (item.originalSize > 0) {
            ((item.originalSize - actualSize) * 100 / item.originalSize).toInt()
        } else 0
        
        return MediaEstimate(
            item = item,
            quality = quality,
            estimatedSize = actualSize,
            reductionPercent = max(0, reductionPercent),
            outputWidth = outputWidth,
            outputHeight = outputHeight
        )
    }
    
    private fun calculateOutputResolution(
        width: Int,
        height: Int,
        maxResolution: Int,
        ensureEven: Boolean = false
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return Pair(maxResolution, maxResolution)
        
        val maxDimension = max(width, height)
        if (maxDimension <= maxResolution) {
            return if (ensureEven) {
                Pair(max(2, width - (width % 2)), max(2, height - (height % 2)))
            } else {
                Pair(width, height)
            }
        }
        
        val scale = maxResolution.toFloat() / maxDimension.toFloat()
        val newWidth = (width * scale).roundToInt()
        val newHeight = (height * scale).roundToInt()
        
        return if (ensureEven) {
            Pair(
                max(2, newWidth - (newWidth % 2)),
                max(2, newHeight - (newHeight % 2))
            )
        } else {
            Pair(max(1, newWidth), max(1, newHeight))
        }
    }
    
    /**
     * Calculate estimates for all quality levels for the given items.
     * Use parallel execution for faster results since we are now doing actual compression.
     */
    suspend fun calculateAllEstimates(
        context: Context,
        items: List<SelectedMediaItem>
    ): Map<CompressionQuality, CompressionEstimateResult> = coroutineScope {
        CompressionQuality.values().map { quality ->
            async {
                val estimates = items.map { item ->
                    estimateCompressedSize(context, item, quality)
                }
                
                val totalOriginal = items.sumOf { it.originalSize }
                val totalEstimated = estimates.sumOf { it.estimatedSize }
                val totalReduction = if (totalOriginal > 0) {
                    ((totalOriginal - totalEstimated) * 100 / totalOriginal).toInt()
                } else 0
                
                CompressionEstimateResult(
                    quality = quality,
                    estimates = estimates,
                    totalOriginalSize = totalOriginal,
                    totalEstimatedSize = totalEstimated,
                    totalReductionPercent = max(0, totalReduction)
                )
            }
        }.awaitAll().associateBy { it.quality }
    }
    
    // Extension functions for safe MediaFormat access
    private fun MediaFormat.getIntegerSafe(key: String): Int? {
        return try {
            if (containsKey(key)) getInteger(key) else null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun MediaFormat.getLongSafe(key: String): Long? {
        return try {
            if (containsKey(key)) getLong(key) else null
        } catch (e: Exception) {
            null
        }
    }
}
