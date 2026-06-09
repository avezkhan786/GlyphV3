package com.glyph.glyph_v3.ui.chat.reactions

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.setPadding
import com.glyph.glyph_v3.R

/**
 * WhatsApp-style reaction popup overlay.
 *
 * Displays a floating reaction-bar pill (6 emojis + a "+") above the long-pressed message
 * bubble. There is intentionally NO scrim, NO dim, and NO bubble lift / snapshot — the
 * underlying chat remains fully visible exactly like WhatsApp's reaction picker. Tap an
 * emoji to fire [onEmojiSelected]; tap the "+" to fire [onMoreRequested]; tap outside or
 * press back to dismiss.
 *
 * The overlay is added directly to the activity's `android.R.id.content` and removed on
 * dismiss — there is no fragment/lifecycle binding so it can be shown from a RecyclerView
 * holder without any extra plumbing.
 */
class ReactionPopupOverlay private constructor(
    private val activity: Activity,
    private val anchor: View,
    private val isOutgoing: Boolean,
    private val ownReaction: String?,
    private val onEmojiSelected: (String) -> Unit,
    private val onMoreRequested: () -> Unit,
    private val onDismiss: () -> Unit,
    /** Screen-space Y of the long-press touch point. -1 means "use bubble mid-point". */
    private val touchYScreen: Float = -1f
) {

    private val rootContainer: ViewGroup =
        activity.findViewById(android.R.id.content) as ViewGroup

    private val density = Resources.getSystem().displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    private val container: FrameLayout = FrameLayout(activity).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.TRANSPARENT)
    }

    private val reactionBar: LinearLayout       // emoji-only inner row inside the scroll
    private val scroll: HorizontalScrollView    // scroller hosting the emoji row (NOT the "+")
    private val barContainer: LinearLayout      // outer pill: [scroll] [plus]

    private var dismissed = false

    /**
     * Default WhatsApp-style quick reaction set. We expose a generous list (14) so that on
     * narrow phones the strip overflows and the horizontal scroll + fading edges become
     * visible just like the real WhatsApp reaction bar.
     */
    private val emojis = listOf(
        "\uD83D\uDC4D", // 👍
        "\u2764\uFE0F", // ❤️
        "\uD83D\uDE02", // 😂
        "\uD83D\uDE2E", // 😮
        "\uD83D\uDE22", // 😢
        "\uD83D\uDE4F", // 🙏
        "\uD83D\uDD25", // 🔥
        "\uD83D\uDC4F", // 👏
        "\uD83C\uDF89", // 🎉
        "\uD83D\uDCAF", // 💯
        "\uD83D\uDE0D", // 😍
        "\uD83E\uDD14", // 🤔
        "\uD83D\uDE0E", // 😎
        "\uD83D\uDC40"  // 👀
    )

    init {
        // ── Build reaction bar pill ─────────────────────────────────────────
        val pillBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28f).toFloat()
            setColor(Color.parseColor("#2A2A2A"))
            setStroke(dp(0.5f).coerceAtLeast(1), Color.parseColor("#33FFFFFF"))
        }

        // Inner emoji row — lives inside the HorizontalScrollView only.
        reactionBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        emojis.forEachIndexed { idx, emoji ->
            reactionBar.addView(buildEmojiButton(emoji, isOwn = (emoji == ownReaction), index = idx))
        }

        // Horizontal scroll wrapper with WhatsApp-style fading edges. The fade automatically
        // appears only when content actually overflows in that direction.
        scroll = object : HorizontalScrollView(activity) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                // Cap scroll width so the "+" button stays visible on narrow screens.
                val screenW = Resources.getSystem().displayMetrics.widthPixels
                val plusReserve = dp(40f) + dp(6f)            // plus button + its margin
                val pillPadding = dp(8f) * 2                  // outer pill horizontal padding
                val sideMargin = dp(16f) * 2                  // viewport side gutters
                val maxW = (screenW - plusReserve - pillPadding - sideMargin).coerceAtLeast(dp(160f))
                val mode = MeasureSpec.getMode(widthSpec)
                val size = MeasureSpec.getSize(widthSpec)
                val newSize = if (mode == MeasureSpec.UNSPECIFIED) maxW else size.coerceAtMost(maxW)
                super.onMeasure(MeasureSpec.makeMeasureSpec(newSize, MeasureSpec.AT_MOST), heightSpec)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(20f))
            addView(
                reactionBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Outer pill: holds the scrollable emoji strip on the left and a fixed "+" on the right.
        barContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBg
            elevation = dp(12f).toFloat()
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            gravity = Gravity.CENTER_VERTICAL
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(28f).toFloat())
                }
            }
            clipToOutline = true
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(buildPlusButton())
        }

        container.addView(barContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        container.isClickable = true
        container.isFocusable = true
        container.setOnClickListener { dismiss() }
        barContainer.setOnTouchListener { _, _ -> false }
    }

    private fun buildEmojiButton(emoji: String, isOwn: Boolean, index: Int): TextView {
        return TextView(activity).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            val size = dp(40f)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = if (index == 0) 0 else dp(2f)
                marginEnd = dp(2f)
            }
            if (isOwn) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#33FFFFFF"))
                }
            }
            // Pre-state for staggered entry animation
            scaleX = 0f
            scaleY = 0f
            alpha = 0f

            setOnClickListener {
                animateSelectionAndDismiss(this) {
                    onEmojiSelected(emoji)
                }
            }
        }
    }

    private fun buildPlusButton(): View {
        val size = dp(40f)
        val plusBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#3A3A3A"))
        }
        return TextView(activity).apply {
            text = "+"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = plusBg
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(6f)
            }
            scaleX = 0f
            scaleY = 0f
            alpha = 0f
            setOnClickListener {
                onMoreRequested()
                dismiss(animate = false)
            }
        }
    }

    fun show() {
        if (rootContainer.findViewById<View>(R.id.glyph_reaction_overlay_root) != null) return
        container.id = R.id.glyph_reaction_overlay_root
        rootContainer.addView(container)

        triggerHaptic()

        // Position must wait for layout — measure/layout pass.
        container.viewTreeObserver.addOnPreDrawListener(object :
            android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                container.viewTreeObserver.removeOnPreDrawListener(this)
                positionElements()
                runEntryAnimations()
                return true
            }
        })
    }

    private fun triggerHaptic() {
        runCatching {
            anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun positionElements() {
        val anchorRect = Rect()
        anchor.getGlobalVisibleRect(anchorRect)

        val containerLoc = IntArray(2)
        container.getLocationOnScreen(containerLoc)

        val anchorLeftInContainer = anchorRect.left - containerLoc[0]
        val anchorTopInContainer = anchorRect.top - containerLoc[1]

        // Reaction bar: above the touch point (or mid-bubble if no touch recorded); fall
        // back to below if there is not enough room at the top of the screen.
        val barWidth = barContainer.measuredWidth.takeIf { it > 0 } ?: scroll.measuredWidth
        val barHeight = barContainer.measuredHeight.takeIf { it > 0 } ?: scroll.measuredHeight
        val sidePadding = dp(8f)
        val gap = dp(10f)

        // Center horizontally over the bubble, then clamp to viewport.
        var barX = anchorLeftInContainer + (anchor.width - barWidth) / 2f
        val maxX = (container.width - barWidth - sidePadding).toFloat()
        if (barX > maxX) barX = maxX
        if (barX < sidePadding) barX = sidePadding.toFloat()

        // Vertical reference: the touch point inside the container, or bubble mid-point.
        val touchRefInContainer: Float = if (touchYScreen >= 0f) {
            touchYScreen - containerLoc[1]
        } else {
            anchorTopInContainer + anchor.height / 2f
        }
        // Clamp the reference to inside the bubble so edge taps still work cleanly.
        val clampedRef = touchRefInContainer
            .coerceAtLeast(anchorTopInContainer.toFloat())
            .coerceAtMost((anchorTopInContainer + anchor.height).toFloat())

        val spaceAbove = clampedRef
        val barY: Float = if (spaceAbove >= barHeight + gap) {
            clampedRef - barHeight - gap
        } else {
            clampedRef + gap
        }

        barContainer.translationX = barX
        barContainer.translationY = barY

        // Initial state for entry animation
        barContainer.alpha = 0f
        barContainer.scaleX = 0.9f
        barContainer.scaleY = 0.9f
        barContainer.pivotX = barWidth / 2f
        barContainer.pivotY = if (barY < anchorTopInContainer) barHeight.toFloat() else 0f
    }

    private fun runEntryAnimations() {
        // No dim animation — the scrim is intentionally transparent (WhatsApp parity).

        // Bar pop-in
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(barContainer, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(barContainer, "scaleX", 0.9f, 1f),
                ObjectAnimator.ofFloat(barContainer, "scaleY", 0.9f, 1f)
            )
            duration = 180
            interpolator = DecelerateInterpolator()
            start()
        }

        // Staggered emoji pop-in (emojis inside the scroll + the fixed "+" sibling).
        val animatedChildren = reactionBar.children.toList() +
            (barContainer.getChildAt(barContainer.childCount - 1)) // the "+" button
        animatedChildren.forEachIndexed { idx, child ->
            child.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(60L + idx * 25L)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(2f))
                .start()
        }
    }

    private fun animateSelectionAndDismiss(target: View, after: () -> Unit) {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(target, "scaleX", 1f, 1.45f, 1f),
                ObjectAnimator.ofFloat(target, "scaleY", 1f, 1.45f, 1f)
            )
            duration = 220
            interpolator = OvershootInterpolator(3f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    after()
                    dismiss(animate = true)
                }
            })
            start()
        }
    }

    fun dismiss(animate: Boolean = true) {
        if (dismissed) return
        dismissed = true
        if (!animate) {
            removeFromParent()
            return
        }
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(barContainer, "alpha", barContainer.alpha, 0f),
                ObjectAnimator.ofFloat(barContainer, "scaleX", barContainer.scaleX, 0.9f),
                ObjectAnimator.ofFloat(barContainer, "scaleY", barContainer.scaleY, 0.9f)
            )
            duration = 140
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    removeFromParent()
                }
            })
            start()
        }
    }

    private fun removeFromParent() {
        (container.parent as? ViewGroup)?.removeView(container)
        onDismiss()
    }

    companion object {
        /** Show the popup. Convenience for callers (handles the back-stack via a back-press dismiss). */
        fun show(
            activity: Activity,
            anchor: View,
            isOutgoing: Boolean,
            ownReaction: String?,
            onEmojiSelected: (String) -> Unit,
            onMoreRequested: () -> Unit,
            onDismiss: () -> Unit = {},
            touchYScreen: Float = -1f
        ): ReactionPopupOverlay {
            return ReactionPopupOverlay(
                activity, anchor, isOutgoing, ownReaction,
                onEmojiSelected, onMoreRequested, onDismiss, touchYScreen
            ).also { it.show() }
        }
    }
}
