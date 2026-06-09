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

    companion object {
        private const val TAG = "GlyphBottomNav"
    }

    init {
        elevation = 0f
        translationZ = 0f
        minimumHeight = 0
        clipToPadding = false
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }

        Log.d(TAG, "onApplyWindowInsets: applying bottom padding $bottomInset")
        
        // Pass the inset into padding. This moves icons UP.
        super.setPadding(paddingLeft, paddingTop, paddingRight, bottomInset)
        
        // Return a consumed inset.
        @Suppress("DEPRECATION")
        return insets.consumeSystemWindowInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        // Target 75dp for the interactive content area.
        val targetContentHeightPx = (75 * density).toInt()
        
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        // Measured height should be content + padding (which is the inset).
        val totalHeightPx = targetContentHeightPx + paddingBottom
        
        Log.d(TAG, "onMeasure: measuredHeight=$measuredHeight, target=$totalHeightPx")
        setMeasuredDimension(measuredWidth, totalHeightPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        clipChildren = false
        clipToPadding = false
        (getChildAt(0) as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
            for (i in 0 until it.childCount) {
                (it.getChildAt(i) as? ViewGroup)?.let { item ->
                    item.clipChildren = false
                    item.clipToPadding = false
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
