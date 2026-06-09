package com.glyph.glyph_v3.ui.aiagent

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.glyph.glyph_v3.R

/**
 * A single-path Drawable that renders a chat bubble with an optional WhatsApp-style tail.
 *
 * Because both the body and the tail are drawn as ONE unified path, a single stroke
 * traces the complete outline with no gaps, breaks, or misalignment.
 *
 * @param context  Used to resolve theme colour attributes.
 * @param isOwn    true  → user/sent  bubble (tail top-right, fill = glyphBubbleOwn)
 *                 false → AI/received bubble (tail top-left,  fill = glyphBubbleOther)
 * @param hasPointer  true → draw the tail, false → plain rounded rectangle (stacked messages)
 */
class BubbleDrawable(
    context: Context,
    val isOwn: Boolean,
    val hasPointer: Boolean,
    fillColor: Int = context.resolveAttrColor(
        if (isOwn) R.attr.glyphBubbleOwn else R.attr.glyphBubbleOther
    )
) : Drawable() {

    // ── Dimensions (pre-converted to px) ────────────────────────────────────
    private val density = context.resources.displayMetrics.density
    private val cornerRadius = 18f * density   // bubble body corner radius
    private val tailWidth    = 9f  * density   // tail horizontal protrusion
    private val tailHeight   = 13f * density   // tail vertical extent
    private val strokeWidth  = 0.75f * density  // thin border

    // ── Paints ───────────────────────────────────────────────────────────────
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@BubbleDrawable.strokeWidth
        color = context.resolveAttrColor(R.attr.glyphDivider)
    }

    private val path = Path()

    // ── Path construction ─────────────────────────────────────────────────────
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        buildPath(bounds)
    }

    private fun buildPath(b: Rect) {
        path.reset()

        // Inset by half the stroke width so the stroke sits fully inside the bounds
        val sw = strokeWidth / 2f
        val l = b.left  + sw
        val t = b.top   + sw
        val r = b.right  - sw
        val bot = b.bottom - sw
        val cr = cornerRadius

        if (!hasPointer) {
            // Plain fully-rounded rectangle (used for "stacked" messages)
            path.addRoundRect(RectF(l, t, r, bot), cr, cr, Path.Direction.CW)
            return
        }

        if (isOwn) {
            // ── Outgoing: tail protrudes at the TOP-RIGHT corner ──────────────
            // The bubble body occupies l → (r - tailWidth).
            // The tail occupies (r - tailWidth) → r.
            val bodyR = r - tailWidth

            // Start at the end of the top-left arc (top edge, left side)
            path.moveTo(l + cr, t)

            // Top edge  →  bodyR
            path.lineTo(bodyR, t)

            // Tail: two straight lines → sharp point at tip, then straight back
            path.lineTo(r, t)                   // straight out to the tip (top-right)
            path.lineTo(bodyR, t + tailHeight)  // straight back down to body right wall

            // Right wall  →  bottom-right corner start
            path.lineTo(bodyR, bot - cr)

            // Bottom-right corner arc
            path.quadTo(bodyR, bot, bodyR - cr, bot)

            // Bottom edge  →  bottom-left corner start
            path.lineTo(l + cr, bot)

            // Bottom-left corner arc
            path.quadTo(l, bot, l, bot - cr)

            // Left wall  →  top-left corner start
            path.lineTo(l, t + cr)

            // Top-left corner arc
            path.quadTo(l, t, l + cr, t)

            path.close()

        } else {
            // ── Incoming: tail protrudes at the TOP-LEFT corner ───────────────
            // The bubble body occupies (l + tailWidth) → r.
            val bodyL = l + tailWidth

            // Start right at the top-left junction (where tail ends / body begins)
            path.moveTo(bodyL, t)

            // Top edge  →  top-right corner start
            path.lineTo(r - cr, t)

            // Top-right corner arc
            path.quadTo(r, t, r, t + cr)

            // Right wall  →  bottom-right corner start
            path.lineTo(r, bot - cr)

            // Bottom-right corner arc
            path.quadTo(r, bot, r - cr, bot)

            // Bottom edge  →  bottom-left corner start
            path.lineTo(bodyL + cr, bot)

            // Bottom-left corner arc
            path.quadTo(bodyL, bot, bodyL, bot - cr)

            // Body left wall up  →  tail base
            path.lineTo(bodyL, t + tailHeight)

            // Tail: two straight lines → sharp point at tip, then straight back
            path.lineTo(l, t)                   // straight out to the tip (top-left)
            path.lineTo(bodyL, t)               // straight back right to body top

            path.close()
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────
    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha   = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        fillPaint.colorFilter   = cf
        strokePaint.colorFilter = cf
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    // ── Helpers ───────────────────────────────────────────────────────────────
    companion object {
        /** Width of the tail protrusion in dp — used to offset stacked bubbles for body alignment. */
        const val TAIL_WIDTH_DP = 9

        /** Horizontal padding (dp) to apply on the TAIL side so content doesn't overlap the tail. */
        const val TAIL_PADDING_DP = 22

        /** Standard horizontal padding (dp) on non-tail sides. */
        const val BODY_PADDING_DP = 12

        /** Standard vertical padding (dp). */
        const val VERT_PADDING_DP = 8
    }
}

// ── Theme attribute colour resolver (top-level extension) ────────────────────
private fun Context.resolveAttrColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}
