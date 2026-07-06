package com.glyph.glyph_v3.ui.chat

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import kotlin.math.min

/**
 * 💬 TelegramBubbleDrawable - Custom Message Bubble Background
 *
 * Implements Telegram-style message bubbles with asymmetric rounded corners
 */
class TelegramBubbleDrawable(private val isOutgoing: Boolean = false) : GradientDrawable() {

    private var backgroundColor = 0
    private var selectionColor = 0x40FFFFFF.toInt()
    private var isSelected = false

    companion object {
        // Telegram corner radii in dp
        private const val CORNER_RADIUS_LARGE = 12f
        private const val CORNER_RADIUS_SMALL = 4f

        // Default Telegram colors
        const val TELEGRAM_GREEN = 0xFFE1F2C3.toInt() // Light mode green
        const val TELEGRAM_GREEN_DARK = 0xFF005C4B.toInt() // Dark mode green
        const val TELEGRAM_INCOMING = 0xFFFFFFFF.toInt() // White
        const val TELEGRAM_INCOMING_DARK = 0xFF1F2C34.toInt() // Dark gray

        fun createOutgoing(): TelegramBubbleDrawable {
            return TelegramBubbleDrawable(isOutgoing = true)
        }

        fun createIncoming(): TelegramBubbleDrawable {
            return TelegramBubbleDrawable(isOutgoing = false)
        }

        fun getTelegramGreen(isDark: Boolean): Int {
            return if (isDark) TELEGRAM_GREEN_DARK else TELEGRAM_GREEN
        }

        fun getTelegramIncoming(isDark: Boolean): Int {
            return if (isDark) TELEGRAM_INCOMING_DARK else TELEGRAM_INCOMING
        }
    }

    init {
        shape = GradientDrawable.RECTANGLE
        setCornerRadii(isOutgoing)
    }

    /**
     * Set asymmetric corner radii for Telegram-style bubbles
     */
    fun setCornerRadii(isOutgoing: Boolean) {
        val large = CORNER_RADIUS_LARGE.dpToPx()
        val small = CORNER_RADIUS_SMALL.dpToPx()

        val radii = if (isOutgoing) {
            // Outgoing: sharp bottom-right corner
            floatArrayOf(large, large, small, large)
        } else {
            // Incoming: sharp bottom-left corner
            floatArrayOf(large, large, large, small)
        }

        cornerRadii = radii
    }

    /**
     * Set the bubble background color
     */
    fun setBackgroundColor(color: Int) {
        backgroundColor = color
        setColor(color)
        invalidateSelf()
    }

    /**
     * Set gradient background for outgoing messages
     */
    fun setGradientBackground(colorStart: Int, colorEnd: Int) {
        val gradientOrientation = if (isOutgoing) {
            GradientDrawable.Orientation.TOP_BOTTOM
        } else {
            GradientDrawable.Orientation.BOTTOM_TOP
        }
        colors = intArrayOf(colorStart, colorEnd)
        gradientType = LINEAR_GRADIENT
        orientation = gradientOrientation
        invalidateSelf()
    }

    /**
     * Set selection state
     */
    fun setSelected(selected: Boolean) {
        isSelected = selected
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw selection overlay
        if (isSelected && selectionColor != 0) {
            val bounds = bounds
            val paint = Paint()
            paint.style = Paint.Style.FILL
            paint.color = selectionColor
            val cornerRadius = (bounds.right - bounds.left) / 2f
            canvas.drawRoundRect(
                RectF(bounds.left.toFloat(), bounds.top.toFloat(),
                     bounds.right.toFloat(), bounds.bottom.toFloat()),
                cornerRadius, cornerRadius,
                paint
            )
        }
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
    }

    override fun setAlpha(alpha: Int) {
        super.setAlpha(alpha)
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        super.setColorFilter(colorFilter)
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}

private fun Float.dpToPx(): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        android.content.res.Resources.getSystem().displayMetrics
    )
}
