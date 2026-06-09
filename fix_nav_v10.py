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

    // GESTURE MODE: Standard M3-like height
    private val CONTENT_HEIGHT_GESTURE_DP = 64f
    
    // 3-BUTTON MODE: Ultra Compact
    // We force the internal content to be very short so it sits tight against the buttons.
    private val CONTENT_HEIGHT_THREE_BUTTON_DP = 40f

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

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val density = resources.displayMetrics.density
        val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.systemBars()).bottom
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        
        // Check for navigation bar presence
        isThreeButtonMode = (bottomInset / density) > 30

        Log.d(TAG, "onApplyWindowInsets: inset=$bottomInset, isThreeButtonMode=$isThreeButtonMode")
        
        // We set padding to the inset. This pushes the content UP.
        // The View height will include this padding.
        super.setPadding(paddingLeft, paddingTop, paddingRight, bottomInset)
        
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             return WindowInsets.Builder(insets).setInsets(WindowInsets.Type.systemBars(), android.graphics.Insets.NONE).build()
        }
        @Suppress("DEPRECATION")
        return insets.consumeSystemWindowInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        
        // 1. Determine how tall the functional part of the bar should be
        val contentHeightDp = if (isThreeButtonMode) CONTENT_HEIGHT_THREE_BUTTON_DP else CONTENT_HEIGHT_GESTURE_DP
        val contentHeightPx = (contentHeightDp * density).toInt()
        
        // 2. Total Height = Content + System Button Area (Padding)
        val totalHeightPx = contentHeightPx + paddingBottom
        
        // 3. Trick the superclass? 
        // If we call super.onMeasure with UNSPECIFIED or WRAP_CONTENT, it might default to 80dp internally.
        // Let's call super normally first to let it initialize children.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        
        // 4. Force the DIMENSION of the whole view. 
        // This is what the parent ConstraintLayout sees.
        setMeasuredDimension(measuredWidth, totalHeightPx)
        
        // 5. CRITICAL FIX: Manually re-measure the internal MenuView.
        // The default implementation might have measured it to be 80dp tall.
        // We force it to be exactly 'contentHeightPx' tall.
        val menuView = getChildAt(0)
        if (menuView != null) {
            val menuWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
            val menuHeightSpec = MeasureSpec.makeMeasureSpec(contentHeightPx, MeasureSpec.EXACTLY)
            menuView.measure(menuWidthSpec, menuHeightSpec)
        }
        
        Log.d(TAG, "onMeasure: Forced Total=$totalHeightPx, Content=$contentHeightPx, Pad=$paddingBottom")
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        disableClipping(this)
        
        val menuView = getChildAt(0)
        if (menuView != null) {
            // Remeasure fix ensures size is correct, but we need to ensure position is correct.
            // If the View thinks it has paddingBottom, it usually places content *above* that padding.
            // Since we manually sized the menuView to fit exactly in the space above padding,
            // we should ensure it's aligned to the top of the view (y=0).
            
            // Standard BottomNavigationView layout logic places the menuview at 0?
            // Or does it center it?
            
            // Let's force it to 0, or add a small offset if needed.
            // Given the previous requirement of "shift down by 10", we can re-apply a small tweak.
            
            val density = resources.displayMetrics.density
            val shiftDown = (4f * density).toInt() 
            // Just a small touch to ensure it's not jammed against the top edge.
            
            menuView.translationY = shiftDown.toFloat()
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
