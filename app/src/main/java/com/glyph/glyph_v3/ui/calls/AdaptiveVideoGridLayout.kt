package com.glyph.glyph_v3.ui.calls

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Non-scrollable adaptive grid that fills the entire viewport.
 *
 * Calculates the optimal number of rows × columns based on
 * active child count and available width/height, then sizes
 * each child to fill its cell exactly — no scrolling, no
 * cropping, no wasted space.
 *
 * Grid strategy (portrait phone):
 *  1 → 1×1  (full screen)
 *  2 → 2×1  (stacked vertically)
 *  3 → 2×2  (top 2, bottom 1 centred)
 *  4 → 2×2
 *  5–6 → 3×2
 *  7–8 → 4×2
 *
 * Spacing between tiles is configurable via [tileGap].
 */
class AdaptiveVideoGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** Gap in pixels between tiles. */
    var tileGap: Int = dpToPx(3)
        set(value) { field = value; requestLayout() }

    /** Top inset (for top bar overlap area). */
    var topInset: Int = 0
        set(value) { field = value; requestLayout() }

    /** Bottom inset (for bottom control bar overlap area). */
    var bottomInset: Int = 0
        set(value) { field = value; requestLayout() }

    // ── Layout ───────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)

        val visible = visibleChildren()
        val count = visible.size
        if (count == 0) return

        val (rows, cols) = gridDimensions(count)

        val totalGapX = tileGap * (cols - 1)
        val totalGapY = tileGap * (rows - 1)
        val cellW = (w - totalGapX) / cols
        val cellH = (h - topInset - bottomInset - totalGapY) / rows

        val childWidthSpec = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)

        for (child in visible) {
            child.measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val visible = visibleChildren()
        val count = visible.size
        if (count == 0) return

        val w = r - l
        val h = b - t

        val (rows, cols) = gridDimensions(count)

        val totalGapX = tileGap * (cols - 1)
        val totalGapY = tileGap * (rows - 1)
        val cellW = (w - totalGapX) / cols
        val cellH = (h - topInset - bottomInset - totalGapY) / rows

        var index = 0
        for (row in 0 until rows) {
            // How many cells in this row? If it's the last row and items don't
            // fill it completely, we centre the remaining items.
            val itemsInRow = min(cols, count - index)
            val rowOffsetX = if (itemsInRow < cols) {
                // Centre the incomplete last row
                (w - (itemsInRow * cellW + (itemsInRow - 1) * tileGap)) / 2
            } else {
                0
            }

            for (col in 0 until itemsInRow) {
                val child = visible[index]
                val cl = rowOffsetX + col * (cellW + tileGap)
                val ct = topInset + row * (cellH + tileGap)
                child.layout(cl, ct, cl + cellW, ct + cellH)
                index++
            }
        }
    }

    // ── Grid calculation ─────────────────────────────────────────────

    /**
     * Returns (rows, cols) for [count] items optimised for portrait mode.
     * Always ensures rows × cols >= count.
     */
    private fun gridDimensions(count: Int): Pair<Int, Int> {
        return when (count) {
            0    -> Pair(0, 0)
            1    -> Pair(1, 1)
            2    -> Pair(2, 1)
            3, 4 -> Pair(2, 2)
            5, 6 -> Pair(3, 2)
            7, 8 -> Pair(4, 2)
            else -> {
                // Generic: 2 columns, as many rows as needed
                val cols = 2
                val rows = ceil(count.toDouble() / cols).toInt()
                Pair(rows, cols)
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun visibleChildren(): List<View> {
        val list = mutableListOf<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.GONE) {
                list.add(child)
            }
        }
        return list
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    // ViewGroup must implement these to be a valid container
    override fun generateDefaultLayoutParams(): LayoutParams {
        return LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p != null

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return LayoutParams(context, attrs)
    }
}
