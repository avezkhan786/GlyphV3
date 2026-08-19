package com.glyph.glyph_v3.ui.chat

import com.glyph.glyph_v3.data.models.MessageType
import kotlin.math.roundToInt

internal object ChatMediaLayoutSizing {
    private const val MEDIA_SIZE_SCALE = 0.75f
    private const val STICKER_WIDTH_DP = 180f * MEDIA_SIZE_SCALE
    private const val PORTRAIT_WIDTH_DP = 260f * MEDIA_SIZE_SCALE
    private const val LANDSCAPE_WIDTH_DP = 320f * MEDIA_SIZE_SCALE
    private const val VIDEO_NOTE_WIDTH_DP = 240f
    private const val DOCUMENT_WIDTH_DP = 220f
    private const val MEDIA_GROUP_GRID_GAP_PX = 4

    private const val ROW_HORIZONTAL_PADDING_DP = 16f
    private const val FORWARD_BUTTON_WIDTH_DP = 36f
    private const val FORWARD_BUTTON_GAP_DP = 10f
    private const val BUBBLE_MEDIA_PADDING_DP = 1f
    private const val EDGE_SAFETY_DP = 2f

    data class MediaSize(val widthPx: Int, val heightPx: Int)

    fun preferredSingleMediaWidthDp(
        type: MessageType,
        aspect: Float,
        isVideoNote: Boolean = false
    ): Float = when {
        type == MessageType.VIDEO && isVideoNote -> VIDEO_NOTE_WIDTH_DP
        type == MessageType.STICKER || type == MessageType.KLIPY_EMOJI -> STICKER_WIDTH_DP
        type == MessageType.DOCUMENT -> DOCUMENT_WIDTH_DP
        aspect > 1.0f -> LANDSCAPE_WIDTH_DP
        else -> PORTRAIT_WIDTH_DP
    }

    fun singleMediaSizePx(
        type: MessageType,
        aspect: Float,
        density: Float,
        viewportWidthPx: Int,
        isVideoNote: Boolean = false,
        rootHorizontalPaddingPx: Int = defaultRootHorizontalPaddingPx(density),
        forwardSideSpacePx: Int = defaultForwardSideSpacePx(density),
        bubbleHorizontalPaddingPx: Int = defaultBubbleHorizontalPaddingPx(density)
    ): MediaSize {
        val safeAspect = aspect.coerceAtLeast(0.1f)
        val preferredWidthPx = dpToPx(preferredSingleMediaWidthDp(type, safeAspect, isVideoNote), density)
        val widthPx = cappedMediaWidthPx(
            preferredWidthPx = preferredWidthPx,
            density = density,
            viewportWidthPx = viewportWidthPx,
            rootHorizontalPaddingPx = rootHorizontalPaddingPx,
            forwardSideSpacePx = forwardSideSpacePx,
            bubbleHorizontalPaddingPx = bubbleHorizontalPaddingPx
        )
        val rawHeightPx = when {
            type == MessageType.VIDEO && isVideoNote -> widthPx
            type == MessageType.DOCUMENT -> (widthPx / 1.6f).roundToInt()
            else -> (widthPx / safeAspect).roundToInt()
        }
        val isSticker = type == MessageType.STICKER || type == MessageType.KLIPY_EMOJI
        val maxHeightPx = maxSingleMediaHeightPx(type, density)
        val minHeightPx = minSingleMediaHeightPx(type, density)
        return MediaSize(widthPx, rawHeightPx.coerceIn(minHeightPx, maxHeightPx))
    }

    fun minSingleMediaHeightPx(type: MessageType, density: Float): Int {
        val isSticker = type == MessageType.STICKER || type == MessageType.KLIPY_EMOJI
        return dpToPx((if (isSticker) 50f else 100f) * MEDIA_SIZE_SCALE, density)
    }

    fun maxSingleMediaHeightPx(type: MessageType, density: Float): Int {
        val isSticker = type == MessageType.STICKER || type == MessageType.KLIPY_EMOJI
        return dpToPx((if (isSticker) 180f else 800f) * MEDIA_SIZE_SCALE, density)
    }

    fun cappedLandscapeMediaWidthPx(
        density: Float,
        viewportWidthPx: Int,
        rootHorizontalPaddingPx: Int = defaultRootHorizontalPaddingPx(density),
        forwardSideSpacePx: Int = defaultForwardSideSpacePx(density),
        bubbleHorizontalPaddingPx: Int = defaultBubbleHorizontalPaddingPx(density)
    ): Int {
        return cappedMediaWidthPx(
            preferredWidthPx = dpToPx(LANDSCAPE_WIDTH_DP, density),
            density = density,
            viewportWidthPx = viewportWidthPx,
            rootHorizontalPaddingPx = rootHorizontalPaddingPx,
            forwardSideSpacePx = forwardSideSpacePx,
            bubbleHorizontalPaddingPx = bubbleHorizontalPaddingPx
        )
    }

    fun mediaGroupTilePreloadSizePx(
        density: Float,
        viewportWidthPx: Int,
        rootHorizontalPaddingPx: Int = defaultRootHorizontalPaddingPx(density),
        forwardSideSpacePx: Int = defaultForwardSideSpacePx(density),
        bubbleHorizontalPaddingPx: Int = defaultBubbleHorizontalPaddingPx(density)
    ): Int {
        val groupWidth = cappedLandscapeMediaWidthPx(
            density = density,
            viewportWidthPx = viewportWidthPx,
            rootHorizontalPaddingPx = rootHorizontalPaddingPx,
            forwardSideSpacePx = forwardSideSpacePx,
            bubbleHorizontalPaddingPx = bubbleHorizontalPaddingPx
        )
        return ((groupWidth - MEDIA_GROUP_GRID_GAP_PX).coerceAtLeast(1) / 2).coerceAtLeast(1)
    }

    fun cappedMediaWidthPx(
        preferredWidthPx: Int,
        density: Float,
        viewportWidthPx: Int,
        rootHorizontalPaddingPx: Int = defaultRootHorizontalPaddingPx(density),
        forwardSideSpacePx: Int = defaultForwardSideSpacePx(density),
        bubbleHorizontalPaddingPx: Int = defaultBubbleHorizontalPaddingPx(density)
    ): Int {
        val safeWidthPx = viewportWidthPx - rootHorizontalPaddingPx - forwardSideSpacePx -
            bubbleHorizontalPaddingPx - dpToPx(EDGE_SAFETY_DP, density)
        return preferredWidthPx.coerceAtMost(safeWidthPx.coerceAtLeast(1))
    }

    fun defaultRootHorizontalPaddingPx(density: Float): Int = dpToPx(ROW_HORIZONTAL_PADDING_DP, density)

    fun defaultForwardSideSpacePx(density: Float): Int {
        return dpToPx(FORWARD_BUTTON_WIDTH_DP + FORWARD_BUTTON_GAP_DP, density)
    }

    fun defaultBubbleHorizontalPaddingPx(density: Float): Int = dpToPx(BUBBLE_MEDIA_PADDING_DP, density)

    fun dpToPx(dp: Float, density: Float): Int = (dp * density).roundToInt()
}