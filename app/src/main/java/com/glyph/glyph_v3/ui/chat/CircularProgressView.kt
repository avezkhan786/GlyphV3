package com.glyph.glyph_v3.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.R

/**
 * A lightweight circular progress indicator for media uploads/downloads.
 * Features smooth animations, minimal overdraw, and efficient rendering.
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    
    // Paint objects - reused for efficiency
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0x40FFFFFF // Semi-transparent white background
        strokeCap = Paint.Cap.ROUND
    }
    
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFFFFFF.toInt() // White progress
        strokeCap = Paint.Cap.ROUND
    }
    
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x60000000 // Semi-transparent black center
    }
    
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
    }
    
    private val bounds = RectF()
    private var currentProgress = 0f
    private var targetProgress = 0f
    private var progressAnimator: ValueAnimator? = null
    private var showPercentage = true
    
    // Indeterminate mode
    private var isIndeterminate = false
    private var indeterminateRotation = 0f
    private var indeterminateAnimator: ValueAnimator? = null

    init {
        // Hardware acceleration for smooth rendering
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val strokeWidth = backgroundPaint.strokeWidth
        val padding = strokeWidth / 2f + 2f * density
        bounds.set(padding, padding, w - padding, h - padding)
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        
        // Draw semi-transparent center circle
        canvas.drawCircle(cx, cy, radius - backgroundPaint.strokeWidth / 2f, centerPaint)
        
        // Draw background arc
        canvas.drawArc(bounds, 0f, 360f, false, backgroundPaint)
        
        if (isIndeterminate) {
            // Indeterminate mode: rotating arc
            canvas.save()
            canvas.rotate(indeterminateRotation, cx, cy)
            canvas.drawArc(bounds, 0f, 90f, false, progressPaint)
            canvas.restore()
        } else {
            // Determinate mode: progress arc
            val sweepAngle = currentProgress * 360f / 100f
            canvas.drawArc(bounds, -90f, sweepAngle, false, progressPaint)
            
            // Draw percentage text
            if (showPercentage && currentProgress >= 0) {
                val text = "${currentProgress.toInt()}%"
                val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(text, cx, textY, textPaint)
            }
        }
    }
    
    /**
     * Set progress with smooth animation (0-100)
     */
    fun setProgress(progress: Float, animate: Boolean = true) {
        stopIndeterminate()
        targetProgress = progress.coerceIn(0f, 100f)
        
        if (animate && currentProgress != targetProgress) {
            progressAnimator?.cancel()
            progressAnimator = ValueAnimator.ofFloat(currentProgress, targetProgress).apply {
                duration = 200 // Quick, responsive animation
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    currentProgress = animator.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            currentProgress = targetProgress
            invalidate()
        }
    }
    
    /**
     * Start indeterminate spinning animation
     */
    fun startIndeterminate() {
        isIndeterminate = true
        indeterminateAnimator?.cancel()
        indeterminateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                indeterminateRotation = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    /**
     * Stop indeterminate animation
     */
    fun stopIndeterminate() {
        isIndeterminate = false
        indeterminateAnimator?.cancel()
        indeterminateAnimator = null
    }
    
    /**
     * Set whether to show percentage text
     */
    fun setShowPercentage(show: Boolean) {
        showPercentage = show
        invalidate()
    }
    
    /**
     * Set progress color
     */
    fun setProgressColor(color: Int) {
        progressPaint.color = color
        invalidate()
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressAnimator?.cancel()
        indeterminateAnimator?.cancel()
    }
}
