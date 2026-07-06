package com.glyph.glyph_v3.ui.chat

import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

/**
 * 🌊 BlurredBackgroundDrawable - Glassmorphism Effect
 *
 * Creates a frosted glass effect by capturing content behind a view
 * and applying blur with a semi-transparent overlay.
 */
class BlurredBackgroundDrawable(
    view: View? = null,
    parent: ViewGroup? = null
) : Drawable() {

    private var parentView: ViewGroup? = parent
    private var blurBitmap: Bitmap? = null
    private var overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var overlayColor = Color.TRANSPARENT
    private var blurRadius = 8f // dp

    init {
        overlayPaint.style = Paint.Style.FILL
    }

    fun setColor(color: Int) {
        if (overlayColor != color) {
            overlayColor = color
            overlayPaint.color = color
            invalidateSelf()
        }
    }

    fun setBlurRadius(radius: Float) {
        blurRadius = radius
    }

    fun updateBlur() {
        invalidateSelf()
    }

    private fun captureBlur() {
        val parent = parentView ?: return
        if (parent.width <= 0 || parent.height <= 0) return

        // Recycle old bitmap
        blurBitmap?.recycle()

        try {
            val visibleBounds = getVisibleBounds()
            if (visibleBounds.isEmpty) return

            blurBitmap = Bitmap.createBitmap(
                visibleBounds.width(),
                visibleBounds.height(),
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(blurBitmap!!)
            canvas.save()
            canvas.translate(-visibleBounds.left.toFloat(), -visibleBounds.top.toFloat())
            parent.draw(canvas)
            canvas.restore()

            applyBlur(blurBitmap!!, blurRadius.dpToPx().toInt())
            invalidateSelf()
        } catch (e: Exception) {
            // Fallback to no blur on error
        }
    }

    private fun getVisibleBounds(): Rect {
        val parent = parentView ?: return Rect()
        return Rect(0, 0, parent.width, parent.height)
    }

    private fun applyBlur(bitmap: Bitmap, radius: Int) {
        if (radius <= 0) return
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val blurred = IntArray(width * height)
        val alpha = (radius * 2 + 1).coerceAtMost(25)
        val scales = FloatArray(alpha * alpha) { i ->
            1f - i.toFloat() / (alpha - 1)
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var a = 0
                var count = 0

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                            val pixel = pixels[ny * width + nx]
                            r += Color.red(pixel)
                            g += Color.green(pixel)
                            b += Color.blue(pixel)
                            a += Color.alpha(pixel)
                            count++
                        }
                    }
                }

                blurred[y * width + x] = Color.argb(
                    a / count,
                    r / count,
                    g / count,
                    b / count
                )
            }
        }

        bitmap.setPixels(blurred, 0, width, 0, 0, width, height)
    }

    override fun draw(canvas: Canvas) {
        blurBitmap?.let {
            val src = Rect(0, 0, it.width, it.height)
            canvas.drawBitmap(it, src, bounds, null)
        }
        canvas.drawRect(bounds, overlayPaint)
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun setAlpha(alpha: Int) { overlayPaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}

private fun Float.dpToPx(): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        android.content.res.Resources.getSystem().displayMetrics
    )
}

private fun Int.dpToPx(): Int {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        android.content.res.Resources.getSystem().displayMetrics
    ).toInt()
}
