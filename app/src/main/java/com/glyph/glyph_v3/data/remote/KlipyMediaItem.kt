package com.glyph.glyph_v3.data.remote

import com.glyph.glyph_v3.data.remote.dto.KlipyMediaItemDto

/**
 * Domain model for a media item (GIF / Sticker / AI Emoji) coming from Klipy.
 */
data class KlipyMediaItem(
    val id: String,
    val slug: String,
    val title: String,
    /** Low-quality preview URL (small / xs webp or gif). Used in grid. */
    val previewUrl: String,
    /** High-quality URL (hd or md gif/webp). Sent as message content. */
    val fullUrl: String,
    val previewWidth: Int,
    val previewHeight: Int,
    val fullWidth: Int,
    val fullHeight: Int
) {
    companion object {
        /**
         * Map a DTO from the API into a domain [KlipyMediaItem].
         * Returns null if essential data is missing.
         */
        fun fromDto(dto: KlipyMediaItemDto): KlipyMediaItem? {
            // Skip advertisements
            if (dto.type == "ad") return null
            val id = dto.id?.toString() ?: dto.slug ?: return null

            val dims = dto.file ?: return null
            val meta = dto.fileMeta

            // Handle flat structure (clips/memes) first
            if (dims.clip_webp != null || dims.clip_gif != null || dims.clip_mp4 != null) {
                // For preview (fast load): WebP > GIF
                val previewUrl = dims.clip_webp ?: dims.clip_gif ?: dims.clip_mp4 ?: return null
                
                // For full view (reliability/animation): GIF > WebP
                val fullUrl = dims.clip_gif ?: dims.clip_webp ?: dims.clip_mp4 ?: return null
                
                // Extract actual dimensions matching the full URL
                val targetMeta = when (fullUrl) {
                    dims.clip_gif -> meta?.gif
                    dims.clip_webp -> meta?.webp
                    else -> meta?.mp4
                }
                
                val w = targetMeta?.width ?: 320
                val h = targetMeta?.height ?: 180

                return KlipyMediaItem(
                    id = id,
                    slug = dto.slug ?: id,
                    title = dto.title ?: "",
                    previewUrl = previewUrl,
                    fullUrl = fullUrl,
                    previewWidth = w,
                    previewHeight = h,
                    fullWidth = w,
                    fullHeight = h
                )
            }

            // Regular GIF/Sticker/Emoji
            // Preview: prefer sm > xs > md, use webp > gif
            val previewFileTypes = dims.sm ?: dims.xs ?: dims.md ?: dims.hd ?: return null
            val previewMeta = previewFileTypes.webp ?: previewFileTypes.gif ?: return null
            val previewUrl = previewMeta.url ?: return null

            // Full quality: prefer hd > md > sm
            val fullFileTypes = dims.hd ?: dims.md ?: dims.sm ?: previewFileTypes
            val fullMeta = fullFileTypes.gif ?: fullFileTypes.webp ?: fullFileTypes.mp4 ?: previewMeta
            val fullUrl = fullMeta.url ?: previewUrl

            // Dimensional fallbacks: Try item-level fileMeta first as it's often more accurate for Klipy
            val fallbackW = meta?.gif?.width ?: meta?.webp?.width ?: 400
            val fallbackH = meta?.gif?.height ?: meta?.webp?.height ?: 400

            val pw = previewMeta.width ?: meta?.webp?.width ?: meta?.gif?.width ?: 200
            val ph = previewMeta.height ?: meta?.webp?.height ?: meta?.gif?.height ?: 200
            
            val fw = fullMeta.width ?: meta?.gif?.width ?: meta?.webp?.width ?: fallbackW
            val fh = fullMeta.height ?: meta?.gif?.height ?: meta?.webp?.height ?: fallbackH

            return KlipyMediaItem(
                id = id,
                slug = dto.slug ?: id,
                title = dto.title ?: "",
                previewUrl = previewUrl,
                fullUrl = fullUrl,
                previewWidth = pw,
                previewHeight = ph,
                fullWidth = fw,
                fullHeight = fh
            )
        }
    }
}
