package com.glyph.glyph_v3.ui.media

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import com.glyph.glyph_v3.BuildConfig
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Custom ImageView with pinch-to-zoom and pan functionality.
 * Used in the grouped media list to provide consistent zoom/pan experience.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrix = Matrix()
    private var mode = NONE
    
    private val start = PointF()
    private val last = PointF()
    private val matrixValues = FloatArray(9)
    
    private var minScale = 1f
    private var maxScale = 5f
    private var saveScale = 1f
    
    private var viewWidth = 0f
    private var viewHeight = 0f
    private var origWidth = 0f
    private var origHeight = 0f
    
    private var zoomAnimator: android.animation.ValueAnimator? = null
    
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector
    
    private var onSingleTapListener: (() -> Unit)? = null
    private var onLongPressListener: ((Float) -> Unit)? = null
    private var onTransformChangedListener: ((Float, Float, Float, Int, Int) -> Unit)? = null
    private var isImageLoaded = false
    private var pendingScale = 1f
    private var pendingOffsetXFraction = 0f
    private var pendingOffsetYFraction = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        private const val DEBUG_TAG = "MediaFocusDebug"
    }

    private data class TransformSnapshot(
        val scale: Float,
        val offsetXFraction: Float,
        val offsetYFraction: Float
    )

    init {
        scaleType = ScaleType.MATRIX
        setupGestureDetectors()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        debugLog("onSizeChanged w=$w h=$h oldw=$oldw oldh=$oldh isImageLoaded=$isImageLoaded")
        // When the view is resized (e.g. layout switch), re-run setupImage so that
        // viewWidth/viewHeight/origWidth/origHeight stay in sync with the actual size.
        if (w > 0 && h > 0 && isImageLoaded && (w != oldw || h != oldh)) {
            val transformSnapshot = captureCurrentTransformSnapshot()
            drawable?.let {
                setupImage(it)
                transformSnapshot?.let { snapshot ->
                    setTransformState(snapshot.scale, snapshot.offsetXFraction, snapshot.offsetYFraction)
                }
            }
        }
    }
    
    fun setOnSingleTapListener(listener: () -> Unit) {
        onSingleTapListener = listener
    }

    fun setOnLongPressListener(listener: (Float) -> Unit) {
        onLongPressListener = listener
    }

    fun setOnTransformChangedListener(listener: (Float, Float, Float, Int, Int) -> Unit) {
        onTransformChangedListener = listener
        if (isImageLoaded) {
            notifyTransformChanged()
        }
    }

    fun setTransformState(scale: Float, offsetXFraction: Float, offsetYFraction: Float) {
        pendingScale = scale
        pendingOffsetXFraction = offsetXFraction
        pendingOffsetYFraction = offsetYFraction

        if (!isImageLoaded || drawable == null) return

        val clampedScale = scale.coerceIn(minScale, maxScale)
        val contentWidth = origWidth * clampedScale
        val contentHeight = origHeight * clampedScale
        val centeredX = (viewWidth - contentWidth) / 2f
        val centeredY = (viewHeight - contentHeight) / 2f
        val maxShiftX = ((contentWidth - viewWidth) / 2f).coerceAtLeast(0f)
        val maxShiftY = ((contentHeight - viewHeight) / 2f).coerceAtLeast(0f)
        val targetTransX = centeredX + offsetXFraction.coerceIn(-1f, 1f) * maxShiftX
        val targetTransY = centeredY + offsetYFraction.coerceIn(-1f, 1f) * maxShiftY

        imageMatrix.reset()
        imageMatrix.setScale(clampedScale * (origWidth / drawable!!.intrinsicWidth), clampedScale * (origHeight / drawable!!.intrinsicHeight))
        imageMatrix.postTranslate(targetTransX, targetTransY)
        saveScale = clampedScale
        fixTranslation()
        setImageMatrix(imageMatrix)
        notifyTransformChanged()
    }

    fun suspendImageLayoutUntilNextDrawable() {
        zoomAnimator?.cancel()
        mode = NONE
        isImageLoaded = false
        saveScale = 1f
        imageMatrix.reset()
        setImageMatrix(imageMatrix)
        super.setImageDrawable(null)
        debugLog("suspendImageLayoutUntilNextDrawable")
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        drawable?.let {
            debugLog("setImageDrawable width=$width height=$height intrinsic=${it.intrinsicWidth}x${it.intrinsicHeight}")
            if (it is android.graphics.drawable.Animatable) {
                it.start()
            }
            if (width > 0 && height > 0) {
                debugLog("setImageDrawable immediate setup")
                setupImage(it)
            } else {
                post {
                    if (this.drawable === it && width > 0 && height > 0) {
                        debugLog("setImageDrawable deferred setup width=$width height=$height")
                        setupImage(it)
                    }
                }
            }
        }
    }

    private fun setupImage(drawable: Drawable) {
        if (width == 0 || height == 0) {
            debugLog("setupImage skipped width=$width height=$height")
            return
        }
        
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        
        // Start in a fit-inside state so fullscreen media preserves aspect ratio
        // and opens uncropped, while still allowing pinch-to-zoom for closer inspection.
        val scaleX = viewWidth / drawable.intrinsicWidth.toFloat()
        val scaleY = viewHeight / drawable.intrinsicHeight.toFloat()
        val scale = kotlin.math.min(scaleX, scaleY)
        debugLog("setupImage apply scale=$scale view=${viewWidth}x${viewHeight} intrinsic=${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
        
        imageMatrix.setScale(scale, scale)
        
        // Set origWidth/Height to the FITTED size
        origWidth = drawable.intrinsicWidth.toFloat() * scale
        origHeight = drawable.intrinsicHeight.toFloat() * scale
        
        // Center
        val redundantYSpace = viewHeight - origHeight
        val redundantXSpace = viewWidth - origWidth
        
        imageMatrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)
        
        setImageMatrix(imageMatrix)
        saveScale = 1f
        minScale = 1f
        isImageLoaded = true
        setTransformState(pendingScale, pendingOffsetXFraction, pendingOffsetYFraction)
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(DEBUG_TAG, "ZoomableImageView@${System.identityHashCode(this)} $message")
        }
    }

    private fun setupGestureDetectors() {
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var lastFocusX = 0f
            private var lastFocusY = 0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = ZOOM
                zoomAnimator?.cancel()
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!isImageLoaded) return false
                
                var mScaleFactor = detector.scaleFactor
                val prevScale = saveScale
                saveScale *= mScaleFactor
                
                if (saveScale > maxScale) {
                    saveScale = maxScale
                    mScaleFactor = maxScale / prevScale
                } else if (saveScale < minScale) {
                    saveScale = minScale
                    mScaleFactor = minScale / prevScale
                }
                
                // Apply scale around the pinch focus point
                imageMatrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)

                // Simultaneous pan: translate by focus-point shift so the image
                // follows both fingers while zooming (like native gallery apps).
                val focusDx = detector.focusX - lastFocusX
                val focusDy = detector.focusY - lastFocusY
                imageMatrix.postTranslate(focusDx, focusDy)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY

                fixTranslation()
                setImageMatrix(imageMatrix)
                notifyTransformChanged()
                return true
            }
        })
        // Reduce the span-slop so the detector responds faster to pinch gestures
        @Suppress("PrivateApi")
        try {
            val spanSlopField = ScaleGestureDetector::class.java.getDeclaredField("mSpanSlop")
            spanSlopField.isAccessible = true
            val currentSlop = spanSlopField.getFloat(scaleDetector)
            spanSlopField.setFloat(scaleDetector, currentSlop / 2f)
        } catch (_: Exception) { /* non-critical optimisation */ }
        
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isImageLoaded) return false
                
                val targetScale = if (saveScale > minScale) minScale else minScale * 2f
                
                zoomAnimator?.cancel()
                zoomAnimator = android.animation.ValueAnimator.ofFloat(saveScale, targetScale).apply {
                    duration = 300
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        val newScale = animator.animatedValue as Float
                        val factor = newScale / saveScale
                        saveScale = newScale
                        
                        imageMatrix.postScale(factor, factor, e.x, e.y)
                        fixTranslation()
                        setImageMatrix(imageMatrix)
                    }
                    start()
                }
                return true
            }
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongPressListener?.invoke(e.rawY)
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        
        if (!isImageLoaded) return true
        
        val curr = PointF(event.x, event.y)
        
        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                last.set(curr)
                start.set(last)
                mode = DRAG
                parent?.requestDisallowInterceptTouchEvent(saveScale > minScale)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                mode = ZOOM
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val letParentScroll = shouldLetParentHandleVerticalScroll(curr)
                parent?.requestDisallowInterceptTouchEvent(!letParentScroll)
                if (letParentScroll) {
                    last.set(curr)
                    return true
                }

                // Single-finger drag (pan) — only when NOT in a pinch gesture.
                // During pinch, onScale already handles focus-point panning.
                if (mode == DRAG && !scaleDetector.isInProgress) {
                    val deltaX = curr.x - last.x
                    val deltaY = curr.y - last.y
                    
                    val fixTransX = getFixDragTrans(deltaX, viewWidth, origWidth * saveScale)
                    val fixTransY = getFixDragTrans(deltaY, viewHeight, origHeight * saveScale)
                    imageMatrix.postTranslate(fixTransX, fixTransY)
                    fixTranslation()
                    setImageMatrix(imageMatrix)
                    notifyTransformChanged()
                    last.set(curr.x, curr.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                mode = NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // When lifting one finger, seamlessly transition to single-finger drag
                // by resetting the "last" point to the remaining finger's position.
                if (event.pointerCount <= 2) {
                    mode = DRAG
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    last.set(event.getX(remainingIndex), event.getY(remainingIndex))
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                mode = NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        
        return true
    }

    private fun shouldLetParentHandleVerticalScroll(curr: PointF): Boolean {
        if (scaleDetector.isInProgress || mode == ZOOM) return false
        if (saveScale > minScale + 0.01f) return false

        val totalDx = curr.x - start.x
        val totalDy = curr.y - start.y
        return kotlin.math.abs(totalDy) > touchSlop &&
            kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
    }

    private fun fixTranslation() {
        imageMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        
        val fixTransX = getFixTrans(transX, viewWidth, origWidth * saveScale)
        val fixTransY = getFixTrans(transY, viewHeight, origHeight * saveScale)
        
        if (fixTransX != 0f || fixTransY != 0f) {
            imageMatrix.postTranslate(fixTransX, fixTransY)
        }
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        if (contentSize <= viewSize) {
            val desiredTrans = (viewSize - contentSize) / 2f
            return desiredTrans - trans
        } else {
            val minTrans = viewSize - contentSize
            val maxTrans = 0f
            
            if (trans < minTrans) return -trans + minTrans
            if (trans > maxTrans) return -trans + maxTrans
            return 0f
        }
    }

    private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
        if (contentSize <= viewSize) return 0f
        return delta
    }

    private fun notifyTransformChanged() {
        val drawable = drawable ?: return
        if (!isImageLoaded || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return

        val snapshot = captureCurrentTransformSnapshot() ?: return

        onTransformChangedListener?.invoke(
            snapshot.scale,
            snapshot.offsetXFraction,
            snapshot.offsetYFraction,
            drawable.intrinsicWidth,
            drawable.intrinsicHeight
        )
    }

    private fun captureCurrentTransformSnapshot(): TransformSnapshot? {
        val drawable = drawable ?: return null
        if (!isImageLoaded || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null

        imageMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        val contentWidth = origWidth * saveScale
        val contentHeight = origHeight * saveScale
        val centeredX = (viewWidth - contentWidth) / 2f
        val centeredY = (viewHeight - contentHeight) / 2f
        val maxShiftX = ((contentWidth - viewWidth) / 2f).coerceAtLeast(0f)
        val maxShiftY = ((contentHeight - viewHeight) / 2f).coerceAtLeast(0f)
        val offsetXFraction = if (maxShiftX == 0f) 0f else ((transX - centeredX) / maxShiftX).coerceIn(-1f, 1f)
        val offsetYFraction = if (maxShiftY == 0f) 0f else ((transY - centeredY) / maxShiftY).coerceIn(-1f, 1f)
        return TransformSnapshot(
            scale = saveScale,
            offsetXFraction = offsetXFraction,
            offsetYFraction = offsetYFraction
        )
    }
    
    /**
     * Reset zoom to default
     */
    fun resetZoom() {
        drawable?.let { setupImage(it) }
    }
}
