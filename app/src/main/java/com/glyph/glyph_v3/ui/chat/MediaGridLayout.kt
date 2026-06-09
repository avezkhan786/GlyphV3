package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.MediaItem
import java.util.concurrent.TimeUnit

class MediaGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private var mediaItems: List<MediaItem> = emptyList()
    private var isScrolling: Boolean = false
    var onItemClickListener: ((Int, MediaItem) -> Unit)? = null
    var onItemLongClickListener: ((Int, MediaItem) -> Boolean)? = null
    private val gap = (2 * resources.displayMetrics.density).toInt()
    private var cornerRadius = (12 * resources.displayMetrics.density).toInt()
    private var cornerTopLeft = cornerRadius
    private var cornerTopRight = cornerRadius
    private var cornerBottomRight = cornerRadius
    private var cornerBottomLeft = cornerRadius

    fun setCornerRadius(radiusDp: Float) {
        val newRadius = (radiusDp * resources.displayMetrics.density).toInt()
        if (this.cornerRadius != newRadius) {
            this.cornerRadius = newRadius
            cornerTopLeft = newRadius
            cornerTopRight = newRadius
            cornerBottomRight = newRadius
            cornerBottomLeft = newRadius
            if (mediaItems.isNotEmpty()) {
                setMediaItems(mediaItems, isScrolling)
            }
        }
    }

    fun setBubbleCorners(isIncoming: Boolean, groupPosition: BubbleGroupPosition, fullRadiusDp: Float) {
        val fullPx = (fullRadiusDp * resources.displayMetrics.density).toInt()
        val reducedPx = 0

        var topLeft = fullPx
        var topRight = fullPx
        var bottomRight = fullPx
        var bottomLeft = fullPx

        when (groupPosition) {
            BubbleGroupPosition.SINGLE -> Unit
            BubbleGroupPosition.TOP -> {
                if (isIncoming) bottomLeft = reducedPx else bottomRight = reducedPx
            }
            BubbleGroupPosition.MIDDLE -> {
                if (isIncoming) {
                    topLeft = reducedPx
                    bottomLeft = reducedPx
                } else {
                    topRight = reducedPx
                    bottomRight = reducedPx
                }
            }
            BubbleGroupPosition.BOTTOM -> {
                if (isIncoming) topLeft = reducedPx else topRight = reducedPx
            }
        }

        if (topLeft != cornerTopLeft || topRight != cornerTopRight || bottomRight != cornerBottomRight || bottomLeft != cornerBottomLeft) {
            cornerTopLeft = topLeft
            cornerTopRight = topRight
            cornerBottomRight = bottomRight
            cornerBottomLeft = bottomLeft
            if (mediaItems.isNotEmpty()) {
                setMediaItems(mediaItems, isScrolling)
            }
        }
    }

    fun setMediaItems(items: List<MediaItem>, isScrolling: Boolean = false) {
        this.mediaItems = items
        this.isScrolling = isScrolling
        removeAllViews()
        createGridCells()
    }

    fun refreshMediaItems(isScrolling: Boolean) {
        if (this.isScrolling == isScrolling) return
        this.isScrolling = isScrolling
        
        // Instead of full recreation, just update the images in existing cells
        for (i in 0 until childCount) {
            val cellView = getChildAt(i)
            val ivThumbnail = cellView.findViewById<ImageView>(R.id.ivMediaThumbnail) ?: continue
            if (i < mediaItems.size) {
                val item = mediaItems[i]
                val corners = getCornersForPosition(i, mediaItems.size.coerceAtMost(4))
                loadThumbnail(ivThumbnail, item, corners, isScrolling)
            }
        }
    }

    private fun createGridCells() {
        if (mediaItems.isEmpty()) return

        val count = mediaItems.size.coerceAtMost(4)
        val views = ArrayList<View>()

        // Create views
        for (i in 0 until count) {
            val item = mediaItems[i]
            val isLast = i == 3 && mediaItems.size > 4
            val remaining = if (isLast) mediaItems.size - 4 else 0
            
            val corners = getCornersForPosition(i, count)
            
            val view = createMediaCell(
                item, i, 
                corners[0], corners[1], corners[2], corners[3],
                isLast, remaining
            )
            view.id = View.generateViewId()
            addView(view)
            views.add(view)
        }

        val set = ConstraintSet()
        set.clone(this)

        when (count) {
            1 -> applySingleItemLayout(set, views[0])
            2 -> applyTwoItemLayout(set, views[0], views[1])
            3 -> applyThreeItemLayout(set, views[0], views[1], views[2])
            4 -> applyFourPlusItemLayout(set, views[0], views[1], views[2], views[3])
        }

        set.applyTo(this)
    }
    
    private fun getCornersForPosition(index: Int, totalCount: Int): IntArray {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val tl = if (rtl) cornerTopRight else cornerTopLeft
        val tr = if (rtl) cornerTopLeft else cornerTopRight
        val br = if (rtl) cornerBottomLeft else cornerBottomRight
        val bl = if (rtl) cornerBottomRight else cornerBottomLeft
        val z = 0
        
        return when (totalCount) {
            1 -> intArrayOf(tl, tr, br, bl)
            2 -> when (index) {
                0 -> intArrayOf(tl, z, z, bl)
                1 -> intArrayOf(z, tr, br, z)
                else -> intArrayOf(z, z, z, z)
            }
            3 -> when (index) {
                0 -> intArrayOf(tl, z, z, bl)
                1 -> intArrayOf(z, tr, z, z)
                2 -> intArrayOf(z, z, br, z)
                else -> intArrayOf(z, z, z, z)
            }
            4 -> when (index) {
                0 -> intArrayOf(tl, z, z, z)
                1 -> intArrayOf(z, tr, z, z)
                2 -> intArrayOf(z, z, z, bl)
                3 -> intArrayOf(z, z, br, z)
                else -> intArrayOf(z, z, z, z)
            }
            else -> intArrayOf(z, z, z, z)
        }
    }

    private fun applySingleItemLayout(set: ConstraintSet, view: View) {
        set.connect(view.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(view.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(view.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(view.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.constrainWidth(view.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(view.id, ConstraintSet.WRAP_CONTENT)
        set.constrainMinHeight(view.id, (200 * resources.displayMetrics.density).toInt())
    }

    private fun applyTwoItemLayout(set: ConstraintSet, v1: View, v2: View) {
        val guidelineId = View.generateViewId()
        set.create(guidelineId, ConstraintSet.VERTICAL_GUIDELINE)
        set.setGuidelinePercent(guidelineId, 0.5f)

        // V1 (Left)
        set.connect(v1.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(v1.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(v1.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(v1.id, ConstraintSet.END, guidelineId, ConstraintSet.START, gap / 2)
        set.constrainWidth(v1.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v1.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(v1.id, "H,1:1.2")

        // V2 (Right)
        set.connect(v2.id, ConstraintSet.START, guidelineId, ConstraintSet.END, gap / 2)
        set.connect(v2.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(v2.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(v2.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.constrainWidth(v2.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v2.id, ConstraintSet.MATCH_CONSTRAINT)
    }

    private fun applyThreeItemLayout(set: ConstraintSet, v1: View, v2: View, v3: View) {
        val guidelineId = View.generateViewId()
        set.create(guidelineId, ConstraintSet.VERTICAL_GUIDELINE)
        set.setGuidelinePercent(guidelineId, 0.6f)

        // V1 (Left Large)
        set.connect(v1.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(v1.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(v1.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.connect(v1.id, ConstraintSet.END, guidelineId, ConstraintSet.START, gap / 2)
        set.constrainWidth(v1.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v1.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(v1.id, "H,1:1.3")

        // V2 (Top Right)
        set.connect(v2.id, ConstraintSet.START, guidelineId, ConstraintSet.END, gap / 2)
        set.connect(v2.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(v2.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.connect(v2.id, ConstraintSet.BOTTOM, v3.id, ConstraintSet.TOP, gap / 2)
        set.constrainWidth(v2.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v2.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setVerticalWeight(v2.id, 1f)

        // V3 (Bottom Right)
        set.connect(v3.id, ConstraintSet.START, guidelineId, ConstraintSet.END, gap / 2)
        set.connect(v3.id, ConstraintSet.TOP, v2.id, ConstraintSet.BOTTOM, gap / 2)
        set.connect(v3.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.connect(v3.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.constrainWidth(v3.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v3.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setVerticalWeight(v3.id, 1f)
        
        // Chain V2-V3
        set.createVerticalChain(
            ConstraintSet.PARENT_ID, ConstraintSet.TOP,
            ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM,
            intArrayOf(v2.id, v3.id),
            null,
            ConstraintSet.CHAIN_SPREAD
        )
    }

    private fun applyFourPlusItemLayout(set: ConstraintSet, v1: View, v2: View, v3: View, v4: View) {
        val guidelineV = View.generateViewId()
        set.create(guidelineV, ConstraintSet.VERTICAL_GUIDELINE)
        set.setGuidelinePercent(guidelineV, 0.5f)
        
        // V1 (Top Left)
        set.connect(v1.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(v1.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(v1.id, ConstraintSet.END, guidelineV, ConstraintSet.START, gap / 2)
        set.connect(v1.id, ConstraintSet.BOTTOM, v3.id, ConstraintSet.TOP, gap / 2)
        set.constrainWidth(v1.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v1.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(v1.id, "H,1:1")

        // V2 (Top Right)
        set.connect(v2.id, ConstraintSet.START, guidelineV, ConstraintSet.END, gap / 2)
        set.connect(v2.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        set.connect(v2.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.connect(v2.id, ConstraintSet.BOTTOM, v4.id, ConstraintSet.TOP, gap / 2)
        set.constrainWidth(v2.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v2.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(v2.id, "H,1:1")

        // V3 (Bottom Left)
        set.connect(v3.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
        set.connect(v3.id, ConstraintSet.TOP, v1.id, ConstraintSet.BOTTOM, gap / 2)
        set.connect(v3.id, ConstraintSet.END, guidelineV, ConstraintSet.START, gap / 2)
        set.connect(v3.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.constrainWidth(v3.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v3.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(v3.id, "H,1:1")

        // V4 (Bottom Right)
        set.connect(v4.id, ConstraintSet.START, guidelineV, ConstraintSet.END, gap / 2)
        set.connect(v4.id, ConstraintSet.TOP, v2.id, ConstraintSet.BOTTOM, gap / 2)
        set.connect(v4.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
        set.connect(v4.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        set.constrainWidth(v4.id, ConstraintSet.MATCH_CONSTRAINT)
        set.constrainHeight(v4.id, ConstraintSet.MATCH_CONSTRAINT)
        set.setDimensionRatio(v4.id, "H,1:1")
    }

    private fun createMediaCell(
        item: MediaItem,
        index: Int,
        topLeft: Int = 0,
        topRight: Int = 0,
        bottomRight: Int = 0,
        bottomLeft: Int = 0,
        showMoreCount: Boolean = false,
        moreCount: Int = 0
    ): View {
        val cellView = LayoutInflater.from(context).inflate(R.layout.item_media_grid_cell, this, false)
        
        val ivThumbnail = cellView.findViewById<ImageView>(R.id.ivMediaThumbnail)
        val ivPlayOverlay = cellView.findViewById<ImageView>(R.id.ivPlayOverlay)
        val tvDuration = cellView.findViewById<TextView>(R.id.tvDuration)
        val overlayMoreCount = cellView.findViewById<FrameLayout>(R.id.overlayMoreCount)
        val tvMoreCount = cellView.findViewById<TextView>(R.id.tvMoreCount)
        val cardMediaCell = cellView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardMediaCell)

        cardMediaCell.shapeAppearanceModel = cardMediaCell.shapeAppearanceModel.toBuilder()
            .setTopLeftCornerSize(topLeft.toFloat())
            .setTopRightCornerSize(topRight.toFloat())
            .setBottomRightCornerSize(bottomRight.toFloat())
            .setBottomLeftCornerSize(bottomLeft.toFloat())
            .build()
        cardMediaCell.clipToOutline = true
        
        loadThumbnail(ivThumbnail, item, intArrayOf(topLeft, topRight, bottomRight, bottomLeft), isScrolling)

        if (item.isVideo) {
            ivPlayOverlay.visibility = View.VISIBLE
            if (item.duration > 0) {
                tvDuration.visibility = View.VISIBLE
                tvDuration.text = formatDuration(item.duration)
            }
        } else {
            ivPlayOverlay.visibility = View.GONE
            tvDuration.visibility = View.GONE
        }

        if (showMoreCount && moreCount > 0) {
            overlayMoreCount.visibility = View.VISIBLE
            tvMoreCount.text = "+$moreCount"
        } else {
            overlayMoreCount.visibility = View.GONE
        }

        cellView.setOnClickListener {
            onItemClickListener?.invoke(index, item)
        }
        
        cellView.setOnLongClickListener {
            onItemLongClickListener?.invoke(index, item) ?: false
        }

        return cellView
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        return String.format("%d:%02d", seconds / 60, seconds % 60)
    }

    private fun loadThumbnail(ivThumbnail: ImageView, item: MediaItem, corners: IntArray, isScrolling: Boolean) {
        // Always load images, using cache efficiently during scroll
        Glide.with(context)
            .load(item.displayUrl)
            .transform(CenterCrop())
            .placeholder(if (item.isVideo) R.drawable.ic_video_placeholder else R.drawable.ic_image_placeholder)
            .error(if (item.isVideo) R.drawable.ic_video_placeholder else R.drawable.ic_image_error)
            .into(ivThumbnail)
    }
}
