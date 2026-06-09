package com.glyph.glyph_v3.data.remote.dto

import com.google.gson.annotations.SerializedName

// ── Top-level response wrappers ────────────────────────────────────────

/** Response for /categories endpoints. */
data class KlipyCategoriesResponse(
    @SerializedName("result") val result: Boolean? = null,
    @SerializedName("data") val data: List<String>? = null
)

/** Response for /trending and /search endpoints. */
data class KlipyMediaResponse(
    @SerializedName("result") val result: Boolean? = null,
    @SerializedName("data") val data: KlipyDataDto? = null
)

data class KlipyDataDto(
    @SerializedName("data") val items: List<KlipyMediaItemDto>? = null,
    @SerializedName("has_next") val hasNext: Boolean? = null,
    @SerializedName("meta") val meta: KlipyMetaDto? = null
)

data class KlipyMetaDto(
    @SerializedName("item_min_width") val itemMinWidth: Int? = null,
    @SerializedName("ad_max_resize_percent") val adMaxResizePercentage: Int? = null
)

// ── Individual media item ──────────────────────────────────────────────

data class KlipyMediaItemDto(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("slug") val slug: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("type") val type: String? = null,     // "gif", "sticker", "emoji", or "ad"
    @SerializedName("file") val file: KlipyDimensionsDto? = null,
    @SerializedName("file_meta") val fileMeta: KlipyFileMetaWrapperDto? = null
)

data class KlipyFileMetaWrapperDto(
    @SerializedName("gif") val gif: KlipyFileMetaDto? = null,
    @SerializedName("webp") val webp: KlipyFileMetaDto? = null,
    @SerializedName("mp4") val mp4: KlipyFileMetaDto? = null
)

data class KlipyDimensionsDto(
    @SerializedName("hd") val hd: KlipyFileTypesDto? = null,
    @SerializedName("md") val md: KlipyFileTypesDto? = null,
    @SerializedName("sm") val sm: KlipyFileTypesDto? = null,
    @SerializedName("xs") val xs: KlipyFileTypesDto? = null,

    // Flat fields for clips (memes) which don't have nested dimensions
    @SerializedName("gif") val clip_gif: String? = null,
    @SerializedName("webp") val clip_webp: String? = null,
    @SerializedName("mp4") val clip_mp4: String? = null
)

data class KlipyFileTypesDto(
    @SerializedName("gif") val gif: KlipyFileMetaDto? = null,
    @SerializedName("webp") val webp: KlipyFileMetaDto? = null,
    @SerializedName("mp4") val mp4: KlipyFileMetaDto? = null
)

data class KlipyFileMetaDto(
    @SerializedName("url") val url: String? = null,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("size") val size: Long? = null
)
