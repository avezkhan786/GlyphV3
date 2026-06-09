package com.glyph.glyph_v3.data.models

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Compression quality presets for media uploads
 */
enum class CompressionQuality(
    val displayName: String,
    val description: String,
    val videoTargetBitrateMbps: Float,
    val videoMaxResolution: Int,
    val imageQuality: Int, // JPEG quality 0-100
    val imageMaxResolution: Int
) {
    ORIGINAL(
        displayName = "Original",
        description = "Best quality, larger size",
        videoTargetBitrateMbps = -1f, // No compression
        videoMaxResolution = Int.MAX_VALUE,
        imageQuality = 100,
        imageMaxResolution = Int.MAX_VALUE
    ),
    HIGH(
        displayName = "High",
        description = "Excellent quality, moderate size",
        videoTargetBitrateMbps = 8f,
        videoMaxResolution = 1920, // 1080p
        imageQuality = 90,
        imageMaxResolution = 2048
    ),
    MEDIUM(
        displayName = "Medium",
        description = "Balanced quality & size (Recommended)",
        videoTargetBitrateMbps = 4f,
        videoMaxResolution = 1280, // 720p
        imageQuality = 80,
        imageMaxResolution = 1600
    ),
    LOW(
        displayName = "Low",
        description = "Smaller size, reduced quality",
        videoTargetBitrateMbps = 2f,
        videoMaxResolution = 854, // 480p
        imageQuality = 60,
        imageMaxResolution = 1024
    ),
    CUSTOM(
        displayName = "Custom",
        description = "Advanced settings per item",
        videoTargetBitrateMbps = 4f,
        videoMaxResolution = 1280,
        imageQuality = 80,
        imageMaxResolution = 1600
    );
    
    val isRecommended: Boolean get() = this == MEDIUM
}

/**
 * Represents a media item selected for upload with its metadata
 */
@Parcelize
data class SelectedMediaItem(
    val uri: Uri,
    val mimeType: String,
    val originalSize: Long,
    val width: Int,
    val height: Int,
    val duration: Long = 0, // For videos, in milliseconds
    val bitrate: Int = 0, // For videos, in bps
    val displayName: String = ""
) : Parcelable {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/**
 * Estimated output size for a media item at a given compression quality
 */
data class MediaEstimate(
    val item: SelectedMediaItem,
    val quality: CompressionQuality,
    val estimatedSize: Long,
    val reductionPercent: Int, // 0-100
    val outputWidth: Int,
    val outputHeight: Int
) {
    val formattedSize: String get() = formatFileSize(estimatedSize)
    val formattedReduction: String get() = if (reductionPercent > 0) "-$reductionPercent%" else "No change"
    val formattedOriginalSize: String get() = formatFileSize(item.originalSize)
    
    companion object {
        fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}

/**
 * Complete estimation result for all selected media at a given quality
 */
data class CompressionEstimateResult(
    val quality: CompressionQuality,
    val estimates: List<MediaEstimate>,
    val totalOriginalSize: Long,
    val totalEstimatedSize: Long,
    val totalReductionPercent: Int
) {
    val formattedTotalOriginal: String get() = MediaEstimate.formatFileSize(totalOriginalSize)
    val formattedTotalEstimated: String get() = MediaEstimate.formatFileSize(totalEstimatedSize)
    val formattedTotalReduction: String get() = if (totalReductionPercent > 0) "-$totalReductionPercent%" else "No change"
    val itemCount: Int get() = estimates.size
}

/**
 * State for the compression selection UI
 */
data class CompressionUiState(
    val selectedItems: List<SelectedMediaItem> = emptyList(),
    val selectedQuality: CompressionQuality = CompressionQuality.MEDIUM,
    val estimates: Map<CompressionQuality, CompressionEstimateResult> = emptyMap(),
    val isCalculating: Boolean = false,
    val showPerItemList: Boolean = false,
    val customOverrides: Map<Uri, CompressionQuality> = emptyMap() // For CUSTOM mode
) {
    val currentEstimate: CompressionEstimateResult? 
        get() = estimates[selectedQuality]
    
    val hasMultipleItems: Boolean 
        get() = selectedItems.size > 1
}
