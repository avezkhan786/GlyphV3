package com.glyph.glyph_v3.ui.chat.expressive

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Custom View that draws an animated gradient background.
 * The gradient smoothly transitions colors based on sentiment changes.
 *
 * Performance:
 *   - Uses hardware layer for GPU acceleration
 *   - Color transitions are smooth via ValueAnimator (not per-frame allocation)
 *   - Gradient angle animates slowly for a "magical" flowing effect
 */
class AnimatedGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentColors: IntArray = SentimentType.NEUTRAL.gradientColors.copyOf()
    private var targetColors: IntArray = currentColors.copyOf()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradientAngle = 0f
    private var colorTransitionAnimator: ValueAnimator? = null
    private var angleAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()
    private val cornerRadius = 16f * resources.displayMetrics.density

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        startAngleAnimation()
    }

    /**
     * Animate to a new sentiment's gradient colors.
     * Smoothly interpolates between current and target colors.
     */
    fun animateToSentiment(sentiment: SentimentType) {
        val newColors = sentiment.gradientColors
        if (newColors.contentEquals(targetColors)) return

        targetColors = newColors.copyOf()
        val startColors = currentColors.copyOf()

        colorTransitionAnimator?.cancel()
        colorTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                for (i in currentColors.indices) {
                    val startIdx = i.coerceAtMost(startColors.lastIndex)
                    val endIdx = i.coerceAtMost(newColors.lastIndex)
                    currentColors[i] = argbEvaluator.evaluate(
                        fraction,
                        startColors[startIdx],
                        newColors[endIdx]
                    ) as Int
                }
                invalidate()
            }
            start()
        }
    }

    fun setSentimentImmediate(sentiment: SentimentType) {
        colorTransitionAnimator?.cancel()
        currentColors = sentiment.gradientColors.copyOf()
        targetColors = currentColors.copyOf()
        invalidate()
    }

    /**
     * Continuously rotates the gradient angle for the "flowing" magical effect.
     */
    private fun startAngleAnimation() {
        angleAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 8000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                gradientAngle = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val radians = Math.toRadians(gradientAngle.toDouble())
        val cos = Math.cos(radians).toFloat()
        val sin = Math.sin(radians).toFloat()

        val cx = width / 2f
        val cy = height / 2f
        val halfDiag = Math.hypot(cx.toDouble(), cy.toDouble()).toFloat()

        val startX = cx - cos * halfDiag
        val startY = cy - sin * halfDiag
        val endX = cx + cos * halfDiag
        val endY = cy + sin * halfDiag

        val gradient = LinearGradient(
            startX, startY, endX, endY,
            currentColors,
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }

    fun reset() {
        colorTransitionAnimator?.cancel()
        currentColors = SentimentType.NEUTRAL.gradientColors.copyOf()
        targetColors = currentColors.copyOf()
        invalidate()
    }

    fun cleanup() {
        colorTransitionAnimator?.cancel()
        angleAnimator?.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cleanup()
    }
}
