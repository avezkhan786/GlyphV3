import os

content = r'''package com.glyph.glyph_v3.ui.widgets

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.bottomnavigation.BottomNavigationView

class GlyphBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    ): BottomNavigationView(context, attrs, defStyleAttr) {
        
    private var isThreeButtonMode = false
    private var currentAnimatingIcon: ImageView? = null
    private var currentAnimatorSet: AnimatorSet? = null

    // Standard Gesture Height
    private val CONTENT_HEIGHT_GESTURE_DP = 64f
    
    // 3-Button Mode: EXTREME TRIM
    // We aim for the absolute minimum height needed to show config.
    private val CONTENT_HEIGHT_THREE_BUTTON_DP = 30f

    companion object {
        private const val TAG = "GlyphBottomNav"
    }

    init {
        elevation = 0f
        translationZ = 0f
        minimumHeight = 0
        clipToPadding = false
        clipChildren = false
    }

    fun rebindColors() {
        itemIconTintList = null
        itemTextColor = null
        itemIconTintList = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav_icon)
        itemTextColor = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav)
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // FORCE TOP PADDING TO 0. The user hates top space.
        super.setPadding(left, 0, right, bottom)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val density = resources.displayMetrics.density
        val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        
        isThreeButtonMode = (bottomInset / density) > 30

        Log.d(TAG, "onApplyWindowInsets: inset=$bottomInset, isThreeButtonMode=$isThreeButtonMode")
        
        // Pass the inset into padding. 
        // Force TOP to 0.
        super.setPadding(paddingLeft, 0, paddingRight, bottomInset)
        
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             return WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars(), android.graphics.Insets.NONE).build()
        }
        @Suppress("DEPRECATION")
        return insets.consumeSystemWindowInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        
        // 1. Calculate EXACT total height we want
        val contentHeightDp = if (isThreeButtonMode) CONTENT_HEIGHT_THREE_BUTTON_DP else CONTENT_HEIGHT_GESTURE_DP
        val contentHeightPx = (contentHeightDp * density).toInt()
        val totalHeightPx = contentHeightPx + paddingBottom
        
        // 2. FORCE super to measure with EXACTLY this height.
        // This prevents it from doing its own 80dp logic.
        val heightMode = MeasureSpec.EXACTLY
        val newHeightSpec = MeasureSpec.makeMeasureSpec(totalHeightPx, heightMode)
        
        super.onMeasure(widthMeasureSpec, newHeightSpec)
        
        // 3. Confirm dimensions
        setMeasuredDimension(measuredWidth, totalHeightPx)
        
        Log.d(TAG, "onMeasure: Forced Height=$totalHeightPx (content=$contentHeightPx)")
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        disableClipping(this)
        
        val menuView = getChildAt(0)
        if (menuView != null) {
            val density = resources.displayMetrics.density
            
            // SHIFT UP LOGIC
            // The user says "large above the icon". 
            // This means we need to pull the icons UP closer to the top edge.
            // Move UP by 6dp in 3-button mode to close the gap.
            
            val shiftUpDp = if (isThreeButtonMode) -6f else 0f
            val shiftY = shiftUpDp * density
            
            menuView.translationY = shiftY
        }
    }
    
    private fun disableClipping(view: View) {
        if (view is ViewGroup) {
            view.clipChildren = false
            view.clipToPadding = false
            for (i in 0 until view.childCount) {
                disableClipping(view.getChildAt(i))
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

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val x = ev.x
            val y = ev.y
            val menuView = getChildAt(0) as? ViewGroup
            if (menuView != null) {
                for (i in 0 until menuView.childCount) {
                    val itemView = menuView.getChildAt(i)
                    if (x >= itemView.left && x <= itemView.right && y >= itemView.top + menuView.translationY && y <= itemView.bottom + menuView.translationY) {
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
'''

with open('app/src/main/java/com/glyph/glyph_v3/ui/widgets/GlyphBottomNavigationView.kt', 'w', encoding='utf-8') as f:
    f.write(content)
