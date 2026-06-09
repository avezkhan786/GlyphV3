package com.glyph.glyph_v3.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Utility for generating video thumbnails with correct orientation handling.
 */
object VideoThumbnailUtil {
    
    private const val THUMBNAIL_MAX_SIZE = 480
    private const val THUMBNAIL_QUALITY = 85
    
    /**
     * Result containing thumbnail data and video metadata.
     */
    data class ThumbnailResult(
        val thumbnailFile: File,
        val thumbnailBytes: ByteArray,
        val width: Int,
        val height: Int,
        val duration: Long,
        val rotation: Int
    )
    
    /**
     * Generate a thumbnail from a video with correct orientation.
     * @param context Application context
     * @param videoUri URI of the video file
     * @param outputDir Directory to save the thumbnail
     * @param targetSize Maximum dimension for the thumbnail
     * @return ThumbnailResult containing thumbnail file and metadata, or null on failure
     */
    suspend fun generateThumbnail(
        context: Context,
        videoUri: Uri,
        outputDir: File,
        targetSize: Int = THUMBNAIL_MAX_SIZE
    ): ThumbnailResult? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            
            // Extract metadata
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            
            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            
            // Get frame at 1 second (or at 10% of video for short videos)
            val frameTimeUs = if (durationMs > 2000) {
                1_000_000L // 1 second
            } else {
                (durationMs * 100) // 10% into the video
            }
            
            // Get the frame
            var bitmap = retriever.getFrameAtTime(
                frameTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0) // Fallback to first frame
            ?: return@withContext null
            
            // Apply rotation only if getFrameAtTime() didn't already apply it.
            // On many devices (especially API 28+), getFrameAtTime() returns an
            // already-rotated frame. Detect this by comparing frame dimensions
            // with raw video dimensions.
            if (rotation == 90 || rotation == 270) {
                // For 90/270 rotation, the rotated frame should have swapped dimensions.
                // If the frame already matches the swapped (rotated) dimensions,
                // getFrameAtTime() already applied rotation — skip.
                val frameMatchesRaw = bitmap.width == videoWidth && bitmap.height == videoHeight
                if (frameMatchesRaw) {
                    bitmap = rotateBitmap(bitmap, rotation)
                }
                // else: frame is already rotated (dimensions are swapped) — skip rotation
            } else if (rotation == 180) {
                // For 180, dimensions don't change, so we can't detect auto-rotation
                // by dimension alone. Apply it conservatively — 180 is very rare from cameras.
                bitmap = rotateBitmap(bitmap, rotation)
            }
            
            // Calculate scaled dimensions maintaining aspect ratio
            val (scaledWidth, scaledHeight) = calculateScaledDimensions(
                bitmap.width, bitmap.height, targetSize
            )
            
            // Scale the bitmap
            if (bitmap.width != scaledWidth || bitmap.height != scaledHeight) {
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = scaledBitmap
                }
            }
            
            // Save to file
            val thumbnailFile = File(outputDir, "thumb_${System.currentTimeMillis()}.jpg")
            
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, baos)
            val thumbnailBytes = baos.toByteArray()
            
            FileOutputStream(thumbnailFile).use { fos ->
                fos.write(thumbnailBytes)
            }
            
            // Calculate actual dimensions after rotation
            val finalWidth: Int
            val finalHeight: Int
            if (rotation == 90 || rotation == 270) {
                finalWidth = videoHeight
                finalHeight = videoWidth
            } else {
                finalWidth = videoWidth
                finalHeight = videoHeight
            }
            
            bitmap.recycle()
            
            ThumbnailResult(
                thumbnailFile = thumbnailFile,
                thumbnailBytes = thumbnailBytes,
                width = finalWidth,
                height = finalHeight,
                duration = durationMs,
                rotation = rotation
            )
            
        } catch (e: Exception) {
            android.util.Log.e("VideoThumbnailUtil", "Failed to generate thumbnail", e)
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
    
    /**
     * Generate thumbnail and return as byte array (for direct upload).
     */
    suspend fun generateThumbnailBytes(
        context: Context,
        videoUri: Uri,
        targetSize: Int = THUMBNAIL_MAX_SIZE
    ): Pair<ByteArray, Int>? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0

            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            
            // Get frame at 1 second or 10%
            val frameTimeUs = if (durationMs > 2000) 1_000_000L else (durationMs * 100)
            
            var bitmap = retriever.getFrameAtTime(
                frameTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0)
            ?: return@withContext null
            
            // Apply rotation only if getFrameAtTime() didn't already apply it.
            // On many devices (especially API 28+), getFrameAtTime() returns an
            // already-rotated frame. Detect this by comparing frame dimensions
            // with raw video dimensions.
            if (rotation == 90 || rotation == 270) {
                val frameMatchesRaw = bitmap.width == videoWidth && bitmap.height == videoHeight
                if (frameMatchesRaw) {
                    bitmap = rotateBitmap(bitmap, rotation)
                }
            } else if (rotation == 180) {
                bitmap = rotateBitmap(bitmap, rotation)
            }
            
            // Scale
            val (scaledWidth, scaledHeight) = calculateScaledDimensions(
                bitmap.width, bitmap.height, targetSize
            )
            
            if (bitmap.width != scaledWidth || bitmap.height != scaledHeight) {
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = scaledBitmap
                }
            }
            
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, baos)
            val bytes = baos.toByteArray()
            
            bitmap.recycle()
            
            Pair(bytes, rotation)
            
        } catch (e: Exception) {
            android.util.Log.e("VideoThumbnailUtil", "Failed to generate thumbnail bytes", e)
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * Rotate bitmap by the given degrees.
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        
        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }
        
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        
        return rotated
    }
    
    /**
     * Calculate scaled dimensions maintaining aspect ratio.
     */
    private fun calculateScaledDimensions(
        width: Int,
        height: Int,
        maxSize: Int
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return Pair(maxSize, maxSize)
        
        val maxDimension = maxOf(width, height)
        if (maxDimension <= maxSize) {
            return Pair(width, height)
        }
        
        val scale = maxSize.toFloat() / maxDimension.toFloat()
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Pair(newWidth, newHeight)
    }
    
    /**
     * Get video metadata without generating thumbnail.
     */
    suspend fun getVideoMetadata(
        context: Context,
        videoUri: Uri
    ): VideoMetadata? = withContext(Dispatchers.IO) {
        var retriever: MediaMetadataRetriever? = null
        
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            
            // Adjust dimensions based on rotation
            val finalWidth: Int
            val finalHeight: Int
            if (rotation == 90 || rotation == 270) {
                finalWidth = height
                finalHeight = width
            } else {
                finalWidth = width
                finalHeight = height
            }
            
            VideoMetadata(
                duration = duration,
                width = finalWidth,
                height = finalHeight,
                rotation = rotation,
                bitrate = bitrate
            )
            
        } catch (e: Exception) {
            android.util.Log.e("VideoThumbnailUtil", "Failed to get video metadata", e)
            null
        } finally {
            try {
                retriever?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * Video metadata holder.
     */
    data class VideoMetadata(
        val duration: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val bitrate: Int
    )
}
