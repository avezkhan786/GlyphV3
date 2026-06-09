package com.glyph.glyph_v3.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.File

/**
 * Represents a single media item (image or video) within a message.
 * Used for both single media messages and grouped multi-media messages.
 */
@Parcelize
data class MediaItem(
    val url: String,
    val localUri: String? = null,
    val type: MediaType = MediaType.IMAGE,
    val thumbnailUrl: String? = null,
    val thumbnailBase64: String? = null, // Tiny blurred thumbnail for instant display
    val duration: Long = 0, // Video duration in milliseconds
    val fileSize: Long = 0,
    val width: Int = 0,
    val height: Int = 0
) : Parcelable {
    
    /**
     * Returns the best available URL for display (local first, then remote)
     */
    val displayUrl: String
        get() {
            val candidate = localUri?.takeIf { it.isNotBlank() }
            if (candidate != null) {
                if (isUsableLocalUri(candidate)) {
                    return candidate
                }
            }
            return url
        }
    
    /**
     * Returns true if this is a video item
     */
    val isVideo: Boolean
        get() = type == MediaType.VIDEO
}

enum class MediaType {
    IMAGE,
    VIDEO
}

private fun isUsableLocalUri(candidate: String): Boolean {
    if (candidate.isBlank()) return false
    return runCatching {
        val parsed = android.net.Uri.parse(candidate)
        when {
            parsed.scheme == "content" -> true
            parsed.scheme == "file" || candidate.startsWith("/") -> {
                val path = parsed.path ?: candidate
                File(path).exists()
            }
            parsed.scheme != null -> true
            else -> File(candidate).exists()
        }
    }.getOrDefault(false)
}
