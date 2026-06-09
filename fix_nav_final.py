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
        
    private var currentAnimatingIcon: ImageView? = null
    private var currentAnimatorSet: AnimatorSet? = null

    // Set a moderate compact height for the content area (excluding insets)
    // 64dp allows icon + text to fit comfortably without excessive whitespace.
    private val COMPACT_CONTENT_HEIGHT_DP = 64f

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
        // Just apply the system bottom inset as padding.
        // This is the "correct" way to handle navigation bars.
        
        val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }

        Log.d(TAG, "onApplyWindowInsets: applying bottom padding $bottomInset")
        
        // Preserve existing horizontal padding if any, set bottom padding to inset
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
        
        // Calculate the height we WANT: Compact Content + System Inset (Padding)
        val density = resources.displayMetrics.density
        val contentHeightPx = (COMPACT_CONTENT_HEIGHT_DP * density).toInt()
        val finalHeightPx = contentHeightPx + paddingBottom
        
        Log.d(TAG, "onMeasure: forcedHeight=$finalHeightPx (content=$contentHeightPx + pad=$paddingBottom)")
        setMeasuredDimension(measuredWidth, finalHeightPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        // 1. Disable clipping everywhere so nothing gets cut off by our compact height
        disableClipping(this)
        
        // 2. Vertically center the internal MenuView within our compact content area
        val menuView = getChildAt(0)
        if (menuView != null) {
            val density = resources.displayMetrics.density
            val contentHeightPx = (COMPACT_CONTENT_HEIGHT_DP * density).toInt()
            
            // The MenuView might be taller (e.g. 80dp standard). Center it.
            // If we are shorter than the menu, we shift it up negatively.
            val menuHeight = menuView.measuredHeight
            if (menuHeight > 0) {
                 val centerOffset = (contentHeightPx - menuHeight) / 2f
                 // Use translationY to center. 
                 // We use a small tweak to ensure correct visual balance
                 val visualTweak = 2f * density
                 menuView.translationY = centerOffset + visualTweak
            }
        }
    }
    
    // Recursive clipping disable
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
