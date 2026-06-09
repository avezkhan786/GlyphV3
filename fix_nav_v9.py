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

    // Gesture Mode: Standard height (64dp content + small inset)
    private val CONTENT_HEIGHT_GESTURE_DP = 64f
    
    // 3-Button Mode: AGGRESSIVELY COMPACT (38dp content + large inset)
    // This removes the "tall header" effect while keeping icons safe.
    private val CONTENT_HEIGHT_THREE_BUTTON_DP = 38f

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
        
        // Threshold check: > 30dp usually means navigation bar is present
        isThreeButtonMode = (bottomInset / density) > 30

        Log.d(TAG, "onApplyWindowInsets: inset=$bottomInset, 3-btn=$isThreeButtonMode")
        
        // Apply system inset as padding.
        super.setPadding(paddingLeft, paddingTop, paddingRight, bottomInset)
        
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
        
        // Calculate target height
        val contentHeightDp = if (isThreeButtonMode) CONTENT_HEIGHT_THREE_BUTTON_DP else CONTENT_HEIGHT_GESTURE_DP
        val contentHeightPx = (contentHeightDp * density).toInt()
        
        // Total = Content + Inset (Padding)
        val finalHeightPx = contentHeightPx + paddingBottom
        
        setMeasuredDimension(measuredWidth, finalHeightPx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        
        disableClipping(this)
        
        val menuView = getChildAt(0)
        if (menuView != null) {
            // ALIGNMENT LOGIC:
            // We want the bottoms of the icons to be roughly consistent relative to the "Safety Line" (Padding Top).
            // Since MenuView centers itself in the available space (ContentHeight),
            // and ContentHeight changes between modes, we strictly offset it.
            
            // We want the visual center of the menu to be at the same vertical offset 
            // from the top of the Navigation Bar Buttons (Padding Bottom) in both modes.
            
            val density = resources.displayMetrics.density
            val contentHeightPx = (bottom - top) - paddingBottom
            val menuHeight = menuView.measuredHeight
            
            // Standard Center in Content Area
            val centerInContent = (contentHeightPx - menuHeight) / 2f
            
            // Correction:
            // In 3-button mode, ContentHeight (38dp) is TIGHT. 
            // In Gesture mode, ContentHeight (64dp) is SPACIOUS.
            // If we just center, 3-button icons might sit differently than gesture icons.
            
            // Let's enforce that the Menu Visual Center is at a fixed height from the bottom safety line.
            // Target: Center of menu should be at 32dp from bottom.
            val targetCenterFromBottom = 32f * density
            
            // Current center from bottom = (ContentHeight / 2)
            val currentCenterFromBottom = contentHeightPx / 2f
            
            // Diff
            val shift = targetCenterFromBottom - currentCenterFromBottom
            
            // If Target (32) > Current (19), we need to move UP (Negative Y).
            // Wait, coordinate system: Y increases downwards.
            // Bottom of content area is at Y = ContentHeight.
            // Center is at Y = ContentHeight / 2.
            // We want Center to be at Y = ContentHeight - 32.
            
            val targetY = contentHeightPx - targetCenterFromBottom - (menuHeight / 2f) 
            // Note: menuHeight/2 is just to align center-to-center points roughly, 
            // but standard translation moves Top-Left. 
            // Standard layout places View at (0, centerInContent). 
            // So translation adds to centerInContent.
            
            // Simpler: Just center it in the 64dp logic, and apply that offset.
            // The logic: Just "Center it" works fine if the heights are consistent.
            // But they aren't.
            
            // Let's just stick to "Center in Content" but adding the 'user requested tweak' (Down 10dp)
            // But ONLY in Gesture mode? No, inconsistent.
            
            // User: "Reduce height... without touching label".
            // If we reduced Content Height from 52 -> 38. Use standard centering.
            
            menuView.translationY = centerInContent + (4f * density) // Small positive tweak to settle it
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
                // Adjust touch target check for global coordinates if needed, 
                // but usually translationY shifts the hit rect too.
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
