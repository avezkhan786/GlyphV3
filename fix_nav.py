content = r"""package com.glyph.glyph_v3.ui.widgets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.Px
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import com.google.android.material.bottomnavigation.BottomNavigationView

class GlyphBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    ): BottomNavigationView(context, attrs, defStyleAttr) {
        
    private var isThreeButtonMode = false
    private var lastAppliedWidth: Int = -1
    private var lastAppliedHeight: Int = -1
    
    // Animation state tracking
    private var currentAnimatingIcon: ImageView? = null
    private var currentAnimatorSet: AnimatorSet? = null

    companion object {
        private const val TAG = "GlyphBottomNav"
    }

    init {
        // Remove the top divider/border
        elevation = 0f
        translationZ = 0f
        
        // Ensure no minimum height is forcing it to be tall
        minimumHeight = 0
        
        // Prevent default system behavior
        super.setPadding(paddingLeft, paddingTop, paddingRight, 0)
    }

    /**
     * Force rebind color state lists for icons and text to ensure theme changes take effect immediately.
     */
    fun rebindColors() {
        // Always reload color state lists from context to pick up correct theme
        itemIconTintList = null
        itemTextColor = null
        itemIconTintList = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav_icon)
        itemTextColor = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // Block large bottom padding (system nav bars) to keep height short
        if (isThreeButtonMode && bottom > 0) {
            Log.d(TAG, "setPadding: Blocking bottom padding ($bottom)")
            super.setPadding(left, top, right, 0)
        } else {
            super.setPadding(left, top, right, bottom)
        }
    }

    override fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {
        if (isThreeButtonMode && bottom > 0) {
            Log.d(TAG, "setPaddingRelative: Blocking bottom padding ($bottom)")
            super.setPaddingRelative(start, top, end, 0)
        } else {
            super.setPaddingRelative(start, top, end, bottom)
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val systemWindowInsetBottom = insets.systemWindowInsetBottom
        val density = resources.displayMetrics.density
        val bottomInsetDp = systemWindowInsetBottom / density
        
        Log.d(TAG, "onApplyWindowInsets: systemWindowInsetBottom=$systemWindowInsetBottom ($bottomInsetDp dp)")

        if (bottomInsetDp > 30) {
            isThreeButtonMode = true
            
            // Apply Margin to lift view
            val params = layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                params.bottomMargin = systemWindowInsetBottom
                layoutParams = params
            }
            
            // Consume entirely to stop parent from adding padding
            return WindowInsets.Builder().build()
        }
        
        isThreeButtonMode = false
        val params = layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null && params.bottomMargin != 0) {
            params.bottomMargin = 0
            layoutParams = params
        }

        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Force 0 padding in 3-button mode or if any bottom padding exists
        if (paddingBottom != 0 && (isThreeButtonMode || paddingBottom > 100)) {
            super.setPadding(paddingLeft, paddingTop, paddingRight, 0)
        }
        
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        // Target height in dp. M3 default is 80dp. 
        // 75dp is a safe compact height that prevents clipping.
        val density = resources.displayMetrics.density
        val targetHeightPx = (75 * density).toInt()
        
        if (measuredHeight > targetHeightPx) {
            Log.d(TAG, "onMeasure: Forcing height from $measuredHeight to $targetHeightPx")
            setMeasuredDimension(measuredWidth, targetHeightPx)
        } else {
            Log.d(TAG, "onMeasure: measuredHeight=$measuredHeight")
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        // Ensure everything is visible by disabling clipping
        clipChildren = false
        clipToPadding = false
        val menuView = getChildAt(0) as? ViewGroup
        menuView?.clipChildren = false
        menuView?.clipToPadding = false
        for (i in 0 until (menuView?.childCount ?: 0)) {
            val itemView = menuView?.getChildAt(i) as? ViewGroup
            itemView?.clipChildren = false
            itemView?.clipToPadding = false
        }

        applyCompactIconLabelGapIfNeeded(changed)
        reduceIconTopPadding(changed)
    }

    private fun applyCompactIconLabelGapIfNeeded(changed: Boolean) {
        val w = width
        val h = height
        if (!changed && w == lastAppliedWidth && h == lastAppliedHeight) return

        lastAppliedWidth = w
        lastAppliedHeight = h

        val desiredGapPx = dpToPx(2f)
        val menuView = getChildAt(0) as? android.view.ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i) as? android.view.ViewGroup ?: continue
            val icon = findFirstImageView(itemView) ?: continue
            val labels = findAllTextViews(itemView)
            if (labels.isEmpty()) continue

            val label = labels.firstOrNull { it.visibility == View.VISIBLE } ?: labels.first()
            label.includeFontPadding = false

            val currentGap = label.top - icon.bottom
            val delta = currentGap - desiredGapPx
            label.translationY = if (delta > 0) (-delta).toFloat() else 0f
        }
    }

    private fun reduceIconTopPadding(changed: Boolean) {
        val density = resources.displayMetrics.density
        
        // Move icons UP slightly to reduce the \"top padding\".
        var translateYDp = -2f
        if (isThreeButtonMode) {
             translateYDp = -8f 
        }

        val translateYPx = (translateYDp * density).toInt()
        val menuView = getChildAt(0) as? android.view.ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val itemView = menuView.getChildAt(i) as? android.view.ViewGroup ?: continue
            
            // In Material 3, child 0 is typically the icon container (pill indicator + icon)
            val iconContainer = itemView.getChildAt(0)
            if (iconContainer != null) {
                iconContainer.translationY = translateYPx.toFloat()
            }
            
            val labels = findAllTextViews(itemView)
            labels.forEach { label ->
                label.translationY = translateYPx.toFloat() * 0.5f
            }
        }
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

    private fun findAllTextViews(root: View): List<TextView> {
        val results = mutableListOf<TextView>()
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            if (v is TextView) results.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
            }
        }
        return results
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val x = ev.x
            val y = ev.y
            val menuView = getChildAt(0) as? ViewGroup
            if (menuView != null) {
                for (i in 0 until menuView.childCount) {
                    val itemView = menuView.getChildAt(i)
                    if (x >= itemView.left && x <= itemView.right && y >= itemView.top && y <= itemView.bottom) {
                        val icon = findFirstImageView(itemView)
                        if (icon != null) {
                            animateIconTap(icon)
                        }
                        break
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun animateIconTap(icon: ImageView) {
        if (currentAnimatingIcon == icon && currentAnimatorSet?.isRunning == true) return
        currentAnimatingIcon = icon
        currentAnimatorSet?.cancel()
        val scaleX = ObjectAnimator.ofFloat(icon, View.SCALE_X, 1.0f, 0.85f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1.0f, 0.85f, 1.0f)
        currentAnimatorSet = AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 150
            interpolator = FastOutSlowInInterpolator()
            start()
        }
    }
}
"""
with open("app/src/main/java/com/glyph/glyph_v3/ui/widgets/GlyphBottomNavigationView.kt", "w", encoding="utf-16") as f:
    f.write(content)
