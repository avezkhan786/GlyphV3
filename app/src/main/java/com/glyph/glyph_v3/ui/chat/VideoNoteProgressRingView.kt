package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A thin circular progress ring drawn around a video note during playback.
 * Shows real-time playback progress as a smooth arc that wraps the circle.
 */
class VideoNoteProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val strokeWidthPx = 3.5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = 0x40FFFFFF // Subtle white track
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = 0xFFFFFFFF.toInt() // White progress
        strokeCap = Paint.Cap.ROUND
    }

    private val bounds = RectF()
    private var progress = 0f // 0.0 to 1.0

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeWidthPx / 2f
        bounds.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        // Track (full circle, subtle)
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        // Progress arc starting from 12 o'clock (-90°)
        val sweep = progress * 360f
        if (sweep > 0f) {
            canvas.drawArc(bounds, -90f, sweep, false, progressPaint)
        }
    }

    /**
     * Set playback progress. 0f = start, 1f = complete.
     */
    fun setPlaybackProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        invalidate()
    }

    fun reset() {
        progress = 0f
        invalidate()
    }
}
