import os

content = r'''package com.glyph.glyph_v3.ui.widgets

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
    
    // Animation state tracking
    private var currentAnimatingIcon: ImageView? = null
    private var currentAnimatorSet: AnimatorSet? = null

    companion object {
        private const val TAG = "GlyphBottomNav"
    }

    init {
        elevation = 0f
        translationZ = 0f
        minimumHeight = 0
        Log.d(TAG, "Initialized")
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        // Block bottom padding to keep height short
        Log.d(TAG, "setPadding: forcing bottom to 0 (was $bottom)")
        super.setPadding(left, top, right, 0)
    }

    override fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {
        Log.d(TAG, "setPaddingRelative: forcing bottom to 0 (was $bottom)")
        super.setPaddingRelative(start, top, end, 0)
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
        
        // Consume the insets if we are in 3-button mode to prevent the view from growing.
        return if (isThreeButtonMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars(), android.graphics.Insets.NONE).build()
            } else {
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        } else {
            super.onApplyWindowInsets(insets)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        // Use 80dp as target (M3 standard) but ensured no padding.
        // We can go to 75dp later once visibility is confirmed.
        val targetHeightPx = (80 * density).toInt()
        
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        Log.d(TAG, "onMeasure: measuredHeight=$measuredHeight, target=$targetHeightPx")
        setMeasuredDimension(measuredWidth, targetHeightPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        // CRITICAL: Disable clipping on ALL levels
        clipChildren = false
        clipToPadding = false
        
        val menuView = getChildAt(0) as? ViewGroup
        if (menuView != null) {
            menuView.clipChildren = false
            menuView.clipToPadding = false
            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? ViewGroup
                if (itemView != null) {
                    itemView.clipChildren = false
                    itemView.clipToPadding = false
                    
                    // Traverse all children of the item view (icon container, labels)
                    for (j in 0 until itemView.childCount) {
                        val child = itemView.getChildAt(j)
                        child.translationY = 0f // Reset any previous translations
                        if (child is ViewGroup) {
                            child.clipChildren = false
                            child.clipToPadding = false
                        }
                    }
                }
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
