package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.random.Random

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var waveformPoints: List<Float> = emptyList()
    private var progress: Float = 0f
    private var barWidth = 0f
    private var gap = 0f
    private var activeColor = 0
    private var inactiveColor = 0

    private var onSeekListener: ((Float) -> Unit)? = null

    init {
        val density = context.resources.displayMetrics.density
        barWidth = 2.5f * density
        gap = 1.5f * density
        activeColor = ContextCompat.getColor(context, android.R.color.white)
        inactiveColor = (activeColor and 0x00FFFFFF) or 0x40000000 // 25% alpha
    }

    fun setColors(active: Int, inactive: Int) {
        activeColor = active
        inactiveColor = inactive
        invalidate()
    }

    fun setDuration(durationMs: Long) {
        // Generate deterministic waveform based on message duration
        val random = Random(durationMs)
        waveformPoints = List(40) { (0.2f + (random.nextFloat() * 0.8f)) }
        invalidate()
    }

    fun setProgress(prog: Float) {
        progress = prog.coerceIn(0f, 1f)
        invalidate()
    }

    fun setOnSeekListener(listener: (Float) -> Unit) {
        onSeekListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (waveformPoints.isEmpty()) return

        val totalBarWidth = barWidth + gap
        val maxBars = (width / totalBarWidth).toInt()
        val centerY = height / 2f
        
        val drawCount = minOf(maxBars, waveformPoints.size)

        for (i in 0 until drawCount) {
            val x = i * totalBarWidth
            val pointValue = waveformPoints[i]
            val barHeight = pointValue * height * 0.75f
            
            // Calculate if this bar is "played"
            // We use the center of the bar to determine if it is played
            val barProgress = (x + barWidth / 2f) / width
            
            paint.color = if (barProgress <= progress) activeColor else inactiveColor

            canvas.drawRoundRect(
                x,
                centerY - barHeight / 2f,
                x + barWidth,
                centerY + barHeight / 2f,
                barWidth / 2f,
                barWidth / 2f,
                paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val newProgress = (event.x / width).coerceIn(0f, 1f)
                onSeekListener?.invoke(newProgress)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val newProgress = (event.x / width).coerceIn(0f, 1f)
                onSeekListener?.invoke(newProgress)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val newProgress = (event.x / width).coerceIn(0f, 1f)
                onSeekListener?.invoke(newProgress)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
