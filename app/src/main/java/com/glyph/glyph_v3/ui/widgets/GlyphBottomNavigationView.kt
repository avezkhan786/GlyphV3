package com.glyph.glyph_v3.ui.widgets

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.glyph.glyph_v3.R
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Compact bottom navigation bar.
 *
 * Height is controlled entirely by resource overrides in dimens.xml:
 *   - design_bottom_navigation_height
 *   - m3_bottom_nav_min_height
 *   - m3_bottom_nav_item_padding_top / bottom
 *   - m3_bottom_nav_item_active_indicator_height
 *   - design_bottom_navigation_margin
 *   - design_bottom_navigation_label_padding
 *
 * This class only handles:
 *   - Three-button vs gesture mode detection
 *   - Label visibility toggling in three-button mode
 *   - Icon tap animation
 *   - Color rebinding
 */
class GlyphBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BottomNavigationView(context, attrs, defStyleAttr) {

    private var isThreeButtonMode = false
    private var currentAnimatingIcon: ImageView? = null
    private var currentAnimatorSet: AnimatorSet? = null
    private var systemBottomInset = 0
    private val statusIndicatorView = View(context)
    private var isStatusIndicatorVisible = false
    private var menuLayoutUpdatePosted = false

    companion object {
        private const val TAG = "GlyphBottomNav"
    }

    init {
        elevation = 0f
        translationZ = 0f
        minimumHeight = 0          // prevent Material from enforcing 80dp
        setPadding(0, 0, 0, 0)     // remove all internal padding
        labelVisibilityMode = LABEL_VISIBILITY_LABELED
        initialiseStatusIndicator()
    }

    fun rebindColors() {
        itemIconTintList = null
        itemTextColor = null
        itemIconTintList = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav_icon)
        itemTextColor = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        
        // Ensure no clipping for the icon animation
        clipChildren = false
        clipToPadding = false
        (getChildAt(0) as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }

        // Single listener handles BOTH safe-area margin AND mode detection
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density

            // Extend the nav surface through the bottom inset so three-button mode
            // doesn't leave a mismatched strip between app chrome and system nav.
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            if (params.bottomMargin != 0) {
                params.bottomMargin = 0
                view.layoutParams = params
            }

            if (systemBottomInset != systemBars.bottom) {
                systemBottomInset = systemBars.bottom
                updatePaddingIfNeeded(view, 0, 0, 0, systemBottomInset)
                scheduleMenuLayoutUpdate()
            }

            // Detect three-button vs gesture mode
            val nextThreeButtonMode = (systemBars.bottom / density) > 30
            if (isThreeButtonMode != nextThreeButtonMode) {
                isThreeButtonMode = nextThreeButtonMode
                scheduleMenuLayoutUpdate()
            }

            // Always show labels in both modes
            if (labelVisibilityMode != LABEL_VISIBILITY_LABELED) {
                labelVisibilityMode = LABEL_VISIBILITY_LABELED
            }

            insets
        }
        requestApplyInsets()
        scheduleMenuLayoutUpdate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            scheduleMenuLayoutUpdate()
        }
        updateStatusIndicatorPosition()
    }

    private fun scheduleMenuLayoutUpdate() {
        if (menuLayoutUpdatePosted) return

        menuLayoutUpdatePosted = true
        post {
            menuLayoutUpdatePosted = false
            if (!isAttachedToWindow) return@post
            updateStatusIndicatorPosition()
        }
    }

    private fun updatePaddingIfNeeded(view: View, left: Int, top: Int, right: Int, bottom: Int) {
        if (view.paddingLeft == left &&
            view.paddingTop == top &&
            view.paddingRight == right &&
            view.paddingBottom == bottom
        ) {
            return
        }
        view.setPadding(left, top, right, bottom)
    }

    fun setStatusIndicatorVisible(visible: Boolean) {
        if (isStatusIndicatorVisible == visible) return
        isStatusIndicatorVisible = visible
        updateStatusIndicatorPosition()
        animateStatusIndicator(visible)
    }

    private fun initialiseStatusIndicator() {
        val density = resources.displayMetrics.density
        val indicatorSize = (8f * density).toInt()
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.getColor(R.color.whatsapp_green))
        }

        statusIndicatorView.apply {
            background = drawable
            alpha = 0f
            scaleX = 0.72f
            scaleY = 0.72f
            visibility = View.INVISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 6f * density
                translationZ = 6f * density
            }
            layoutParams = FrameLayout.LayoutParams(indicatorSize, indicatorSize)
        }

        addView(statusIndicatorView)
        statusIndicatorView.bringToFront()
    }

    private fun animateStatusIndicator(visible: Boolean) {
        statusIndicatorView.animate().cancel()
        if (visible) {
            statusIndicatorView.visibility = View.VISIBLE
            statusIndicatorView.bringToFront()
            statusIndicatorView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        } else {
            statusIndicatorView.animate()
                .alpha(0f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(140L)
                .setInterpolator(FastOutSlowInInterpolator())
                .withEndAction {
                    if (!isStatusIndicatorVisible) {
                        statusIndicatorView.visibility = View.INVISIBLE
                    }
                }
                .start()
        }
    }

    private fun updateStatusIndicatorPosition() {
        val itemView = findMenuItemView(R.id.navigation_status) ?: return
        val iconView = findFirstImageView(itemView) ?: return

        val itemLocation = IntArray(2)
        val iconLocation = IntArray(2)
        val navLocation = IntArray(2)
        itemView.getLocationOnScreen(itemLocation)
        iconView.getLocationOnScreen(iconLocation)
        getLocationOnScreen(navLocation)

        val horizontalOffset = 1f * resources.displayMetrics.density
        val verticalOffset = 3f * resources.displayMetrics.density

        val targetX = (iconLocation[0] - navLocation[0]) + iconView.width + horizontalOffset
        val targetY = (iconLocation[1] - navLocation[1]) + verticalOffset

        statusIndicatorView.x = targetX
        statusIndicatorView.y = targetY
        statusIndicatorView.bringToFront()
    }

    private fun findMenuItemView(menuItemId: Int): View? {
        val menuView = getChildAt(0) as? ViewGroup ?: return null
        val itemIndex = (0 until menu.size()).firstOrNull { menu.getItem(it).itemId == menuItemId } ?: return null
        return menuView.getChildAt(itemIndex)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Keep a 60dp interactive area while allowing the background to extend
        // into the bottom system inset for seamless three-button navigation.
        val targetHeight = (60 * resources.displayMetrics.density).toInt() + systemBottomInset
        val exactHeightSpec = View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)

        // Completely ignore what parent/Material wants - force our height
        super.onMeasure(widthMeasureSpec, exactHeightSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), targetHeight)
    }

    // --- Icon tap animation ---

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val menuView = getChildAt(0) as? ViewGroup ?: return super.dispatchTouchEvent(ev)
            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i)
                if (ev.x >= itemView.left && ev.x <= itemView.right
                    && ev.y >= itemView.top && ev.y <= itemView.bottom
                ) {
                    // Check if the item is already selected
                    if (menu.size() > i && menu.getItem(i).isChecked) {
                        // If already selected, do NOT animate and do NOT propagate event to super
                        // This prevents re-selection logic and repeated animations
                        return true
                    }

                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    findFirstImageView(itemView)?.let { animateIconTap(it) }
                    break
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun findFirstImageView(root: View): ImageView? {
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is ImageView) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
            }
        }
        return null
    }

    private fun animateIconTap(icon: ImageView) {
        if (currentAnimatingIcon == icon && currentAnimatorSet?.isRunning == true) return
        currentAnimatingIcon = icon
        currentAnimatorSet?.cancel()
        
        // Reset properties
        icon.scaleX = 1f
        icon.scaleY = 1f
        icon.translationY = 0f

        val set = AnimatorSet()
        
        // Phase 1: Anticipation (Press/Shrink)
        // Scale down significantly before the bloom
        val scaleShrinkX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1.0f, 0.75f)
        val scaleShrinkY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1.0f, 0.75f)
        
        val shrinkDuration = 200L
        val shrinkInterpolator = DecelerateInterpolator()
        
        scaleShrinkX.duration = shrinkDuration
        scaleShrinkX.interpolator = shrinkInterpolator
        scaleShrinkY.duration = shrinkDuration
        scaleShrinkY.interpolator = shrinkInterpolator

        // Phase 2: Expansion (Bloom up)
        val scaleUpX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.85f, 1.03f)
        val scaleUpY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.85f, 1.03f)
        val moveUp   = ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -3f)
        
        val expandDuration = 400L
        val expandInterpolator = DecelerateInterpolator()
        
        scaleUpX.duration = expandDuration
        scaleUpX.interpolator = expandInterpolator
        scaleUpY.duration = expandDuration
        scaleUpY.interpolator = expandInterpolator
        moveUp.duration = expandDuration
        moveUp.interpolator = expandInterpolator

        // Phase 3: Settle (Bounce back to normal)
        val scaleDownX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1.05f, 1.0f)
val scaleDownY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1.05f, 1.0f)
val moveDown   = ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, -4f, 0f)

        val settleDuration = 400L
        val settleInterpolator = OvershootInterpolator(1.5f) 
        
        scaleDownX.duration = settleDuration
        scaleDownX.interpolator = settleInterpolator
        scaleDownY.duration = settleDuration
        scaleDownY.interpolator = settleInterpolator
        moveDown.duration = settleDuration
        moveDown.interpolator = settleInterpolator

        // Play sequentially
        set.play(scaleShrinkX).with(scaleShrinkY)
        set.play(scaleUpX).with(scaleUpY).with(moveUp).after(scaleShrinkX)
        set.play(scaleDownX).with(scaleDownY).with(moveDown).after(scaleUpX)
        
        currentAnimatorSet = set
        set.start()
    }
}
