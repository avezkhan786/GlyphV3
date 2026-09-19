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
            // orEmpty(): this property is declared non-null, but items deserialized
            // by Gson (Message.mediaItems / mediaItemsList, MediaDownloadWorker,
            // MediaTransferManager, RealtimeMessageRepository) never run the
            // constructor, so a JSON payload without a "url" key leaves the field
            // null and this getter used to return that null — crashing callers that
            // trust the declared type (observed: CollageImageView.getFullResKey →
            // NPE "Object.getClass() on a null object reference" when opening a
            // group chat with a collage message). Empty string behaves exactly like
            // a blank URL at every call site.
            return url.orEmpty()
        }
    
    /**
     * Returns true if this is a video item
     */
    val isVideo: Boolean
        get() = type == MediaType.VIDEO
}

/**
 * Gson deserialization bypasses the constructor, so a mediaItems JSON payload that
 * omits `"url"` or `"type"` leaves those non-null-declared fields null at runtime.
 * Call this right after every `Gson().fromJson<List<MediaItem>>` so all downstream
 * consumers see the contract the type system promises.
 *
 * Crashes this fixes (both seen in release, opening a group chat with a collage):
 *  - CollageImageView.getFullResKey   → NPE "Object.getClass() on a null object" (null url)
 *  - CollageImageView.buildItemsSignature → NPE "Enum.name() on a null object" (null type)
 */
fun List<MediaItem>.sanitizedMediaItems(): List<MediaItem> = map { it.sanitizedMediaItem() }

/** @see sanitizedMediaItems */
fun MediaItem.sanitizedMediaItem(): MediaItem {
    val safeUrl = url.orEmpty()
    val resolvedType: MediaType = type ?: MediaType.IMAGE
    return if (safeUrl == url && resolvedType == type) {
        this
    } else {
        // Safe: every other non-null field is a primitive (Gson defaults those to 0).
        copy(url = safeUrl, type = resolvedType)
    }
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
