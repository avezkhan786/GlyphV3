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

    // STANDARD MODE (Gesture Nav): 64dp height.
    // This is the "Gold Standard" the user likes.
    private val STANDARD_CONTENT_HEIGHT_DP = 64f
    
    // COMPACT MODE (3-Button Nav): 52dp height.
    // We reduce the height to minimize the "double bar" look.
    private val THREE_BUTTON_CONTENT_HEIGHT_DP = 52f

    companion object {
        private const val TAG = "GlyphBottomNav"
    }

    init {
        elevation = 0f
        translationZ = 0f
        minimumHeight = 0 // Critical to allow shrinking below 80dp
        clipToPadding = false
        clipChildren = false
    }

    fun rebindColors() {
        itemIconTintList = null
        itemTextColor = null
        itemIconTintList = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav_icon)
        itemTextColor = context.getColorStateList(com.glyph.glyph_v3.R.color.selector_bottom_nav)
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

        Log.d(TAG, "onApplyWindowInsets: bottomInset=$bottomInset, isThreeButtonMode=$isThreeButtonMode")
        
        // Pass the inset into padding.
        super.setPadding(paddingLeft, paddingTop, paddingRight, bottomInset)
        
        // Consume insets so parent doesn't re-apply them
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             return WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars(), android.graphics.Insets.NONE).build()
        }
        @Suppress("DEPRECATION")
        return insets.consumeSystemWindowInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        val density = resources.displayMetrics.density
        
        // Select content height based on mode
        val desiredContentDp = if (isThreeButtonMode) THREE_BUTTON_CONTENT_HEIGHT_DP else STANDARD_CONTENT_HEIGHT_DP
        val contentHeightPx = (desiredContentDp * density).toInt()
        
        val finalHeightPx = contentHeightPx + paddingBottom
        
        Log.d(TAG, "onMeasure: mode=${if(isThreeButtonMode) "3-BTN" else "GESTURE"}, forcedHeight=$finalHeightPx (content=$contentHeightPx + pad=$paddingBottom)")
        setMeasuredDimension(measuredWidth, finalHeightPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        // 1. Disable clipping everywhere
        disableClipping(this)
        
        // 2. Position the internal MenuView
        val menuView = getChildAt(0)
        if (menuView != null) {
            val density = resources.displayMetrics.density
            
            // We want the visual center of the menu to match the STANDARD (Gesture) mode.
            // In Standard mode (64dp), the center is 32dp from the bottom padding.
            // We enforce this 32dp distance regardless of our actual shortened height.
            val targetCenterFromBottomDp = STANDARD_CONTENT_HEIGHT_DP / 2f
            val targetCenterFromBottomPx = targetCenterFromBottomDp * density
            
            val contentHeightPx = (bottom - top) - paddingBottom
            val menuHalfHeight = menuView.measuredHeight / 2f
            
            // Formula: TranslationY = AvailableHeight - DesiredDistance - ElementHalfHeight
            // This ensures ElementCenter is always 'DesiredDistance' away from the bottom line.
            val desiredTranslationY = contentHeightPx - targetCenterFromBottomPx - menuHalfHeight
            
            // Visual tweak: User previously asked for 10dp downward shift. 
            // In Gesture mode: (64 - 32 - 40) = -8. Shift by 10 -> +2.
            // Let's add a small positive tweak to push icons slightly lower as requested previously.
            val tweak = 2f * density
            
            menuView.translationY = desiredTranslationY + tweak
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
'''

with open('app/src/main/java/com/glyph/glyph_v3/ui/widgets/GlyphBottomNavigationView.kt', 'w', encoding='utf-8') as f:
    f.write(content)
