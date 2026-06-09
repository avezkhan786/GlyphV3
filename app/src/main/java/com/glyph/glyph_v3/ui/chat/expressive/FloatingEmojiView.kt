package com.glyph.glyph_v3.ui.chat.expressive

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Overlay view that displays subtle floating emojis that drift upward and fade out.
 *
 * Design principles:
 *   - Elegant, not childish: max 2 emojis visible at once
 *   - Small size (16-20sp), low alpha start (0.6-0.8)
 *   - Gentle upward drift with slight horizontal sway
 *   - Duration ~1200ms per emoji
 *   - GPU layer for performance
 *
 * Usage:
 *   floatingEmojiView.emitEmojis(sentimentType)
 */
class FloatingEmojiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_VISIBLE = 2
        private const val EMOJI_SIZE_SP = 18f
        private const val ANIM_DURATION_MS = 1400L
        private const val EMIT_MIN_INTERVAL_MS = 800L
    }

    private var activeCount = 0
    private var lastEmitTime = 0L

    init {
        clipChildren = false
        clipToPadding = false
    }

    /**
     * Emit a floating emoji based on the given sentiment.
     * Respects rate limiting and maximum visible count.
     */
    fun emitEmojis(sentiment: SentimentType) {
        if (sentiment.emojis.isEmpty()) return
        if (activeCount >= MAX_VISIBLE) return

        val now = System.currentTimeMillis()
        if (now - lastEmitTime < EMIT_MIN_INTERVAL_MS) return
        lastEmitTime = now

        val emoji = sentiment.emojis[Random.nextInt(sentiment.emojis.size)]
        spawnEmoji(emoji)
    }

    private fun spawnEmoji(emoji: String) {
        val tv = TextView(context).apply {
            text = emoji
            textSize = EMOJI_SIZE_SP
            gravity = Gravity.CENTER
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val lp = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Random horizontal offset within the view bounds
            val maxOffset = (width * 0.3f).toInt().coerceAtLeast(20)
            marginStart = Random.nextInt(-maxOffset, maxOffset + 1) + (width / 2).coerceAtLeast(40)
        }

        addView(tv, lp)
        activeCount++

        // Start alpha
        tv.alpha = 0.7f + Random.nextFloat() * 0.2f

        val floatDistance = height.toFloat().coerceAtLeast(120f) * 0.6f
        val swayX = (Random.nextFloat() - 0.5f) * 40f * resources.displayMetrics.density

        val translateY = ObjectAnimator.ofFloat(tv, View.TRANSLATION_Y, 0f, -floatDistance)
        val translateX = ObjectAnimator.ofFloat(tv, View.TRANSLATION_X, 0f, swayX)
        val fadeOut = ObjectAnimator.ofFloat(tv, View.ALPHA, tv.alpha, 0f).apply {
            startDelay = ANIM_DURATION_MS / 3
        }
        val scaleDown = ObjectAnimator.ofFloat(tv, View.SCALE_X, 1f, 0.6f)
        val scaleDownY = ObjectAnimator.ofFloat(tv, View.SCALE_Y, 1f, 0.6f)

        val animatorSet = AnimatorSet().apply {
            playTogether(translateY, translateX, fadeOut, scaleDown, scaleDownY)
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator(1.5f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeView(tv)
                    activeCount--
                }
            })
        }

        animatorSet.start()
    }

    fun reset() {
        removeAllViews()
        activeCount = 0
    }
}
