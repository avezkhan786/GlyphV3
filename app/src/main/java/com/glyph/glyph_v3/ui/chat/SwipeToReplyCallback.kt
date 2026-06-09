package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import android.os.Build
import androidx.core.graphics.drawable.DrawableCompat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.max

/**
 * A custom ItemTouchHelper callback to implement WhatsApp-style swipe-to-reply.
 */
class SwipeToReplyCallback(
    private val context: Context,
    private val canSwipe: (position: Int) -> Boolean = { true },
    private val onSwipeAction: (position: Int) -> Unit
) : ItemTouchHelper.Callback() {

    private val replyActionSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        36f,
        context.resources.displayMetrics
    )
    private val replyActionIconSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        22f,
        context.resources.displayMetrics
    )

    private val replyIcon: Drawable?
    private val iconColor: Int
    private val circlePaint: Paint
    private val swipeThreshold = 0.25f 
    private var isTriggered = false
    private val replyBaseMarginPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        32f,
        context.resources.displayMetrics
    )
    private val buttonFadeDistancePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        72f,
        context.resources.displayMetrics
    )
    
    // Track state for release handling
    private var wasActive = false
    private var lastDx = 0f
    private var activationDxAbs = 0f
    private var activeSwipeSign = 0
    
    // Haptics tracking
    private var lastHapticTriggerX = 0f
    private val hapticStepPx = 40f // Trigger tick every 40px
    
    // Vibration effects
    private val vibrationHelper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    
    // Pre-create impacts for performance
    private val tickEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
         VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    } else {
         VibrationEffect.createOneShot(5, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    
    private val releaseEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
         VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    } else {
         VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    init {
        // Prepare icon
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_reply_swipe)
        replyIcon = icon?.let { DrawableCompat.wrap(it).mutate() }
        iconColor = 0xFFFFFFFF.toInt()
        replyIcon?.let {
            DrawableCompat.setTint(it, iconColor)
        }
        
        circlePaint = Paint().apply {
            color = 0xFF16252D.toInt()
            isAntiAlias = true
            style = Paint.Style.FILL
            setShadowLayer(8f, 0f, 2f, 0x40000000)
        }
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        if (viewHolder !is ChatAdapter.BaseViewHolder) return 0
        
        val position = viewHolder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || !canSwipe(position)) return 0
        
        val viewType = viewHolder.itemViewType
        
        // Incoming types (Odd numbers in Adapter) -> Swipe RIGHT
        val isIncoming = viewType in setOf(1, 3, 5, 7, 9, 11, 15, 19)
        // Outgoing types (Even numbers in Adapter) -> Swipe LEFT
        val isOutgoing = viewType in setOf(2, 4, 6, 8, 10, 12, 16, 20)
        
        return when {
            isIncoming -> makeMovementFlags(0, ItemTouchHelper.RIGHT)
            isOutgoing -> makeMovementFlags(0, ItemTouchHelper.LEFT)
            else -> 0
        }
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // This won't be called because we set a very high threshold.
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        return 2.0f // Unreachable threshold so ItemTouchHelper never manages the "dismiss"
    }
    
    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            resetSwipeState()
            resetForwardButton(viewHolder.itemView)
            return
        }

        val recyclerWidth = recyclerView.width.toFloat()
        val inputSign = when {
            dX > 0f -> 1
            dX < 0f -> -1
            else -> activeSwipeSign
        }
        if (isCurrentlyActive && !wasActive) {
            activeSwipeSign = inputSign
            activationDxAbs = abs(dX)
        } else if (isCurrentlyActive && inputSign != 0 && inputSign != activeSwipeSign) {
            activeSwipeSign = inputSign
            activationDxAbs = abs(dX)
        }

        val effectiveDxAbs = (abs(dX) - activationDxAbs).coerceAtLeast(0f)
        val effectiveDx = effectiveDxAbs * inputSign
        val cappedDx = computeSmoothedSwipeDx(effectiveDx, recyclerWidth)
        val cappedDxAbs = abs(cappedDx)

        // 2. Detect Release for Trigger
        if (wasActive && !isCurrentlyActive) {
            if (abs(lastDx) > recyclerWidth * swipeThreshold) {
                 onSwipeAction(viewHolder.bindingAdapterPosition)
                 vibrationHelper?.vibrate(releaseEffect)
            }
            resetSwipeState()
        }
        
        if (isCurrentlyActive) {
            lastDx = effectiveDx
            
            // Rhythmic Haptics
            if (abs(effectiveDxAbs - lastHapticTriggerX) >= hapticStepPx) {
                lastHapticTriggerX = effectiveDxAbs
                vibrationHelper?.vibrate(tickEffect)
            }
            
            // Check if we just crossed the trigger threshold for the first time
            val isOverThreshold = effectiveDxAbs > recyclerWidth * swipeThreshold
            if (isOverThreshold && !isTriggered) {
                isTriggered = true
                vibrationHelper?.vibrate(releaseEffect)
            } else if (!isOverThreshold) {
                isTriggered = false
            }
        }
        wasActive = isCurrentlyActive

        updateForwardButton(viewHolder.itemView, effectiveDxAbs)

        // 3. Draw the View with resistance
        super.onChildDraw(c, recyclerView, viewHolder, cappedDx, dY, actionState, isCurrentlyActive)

        // 4. Draw the Icon logic
        if (cappedDxAbs > 0f) {
            val itemView = viewHolder.itemView
            val revealDistancePx = recyclerWidth * 0.18f
            val revealProgress = smoothStep((effectiveDxAbs / revealDistancePx).coerceIn(0f, 1f))
            
            if (replyIcon != null) {
                val iconSize = replyActionIconSizePx
                val circleRadius = replyActionSizePx / 2f
                
                val forwardButton = itemView.findViewById<View>(R.id.btnForwardMessage)
                val itemCentery = forwardButton?.let { itemView.top + it.top + (it.height / 2f) }
                    ?: (itemView.top + ((itemView.bottom - itemView.top) / 2f))
                
                val isIncomingSwipe = cappedDx > 0f
                val finalIconX = if (isIncomingSwipe) {
                    itemView.left + replyBaseMarginPx
                } else {
                    itemView.right - replyBaseMarginPx
                }
                val startIconX = if (isIncomingSwipe) {
                    recyclerView.left - replyActionSizePx
                } else {
                    recyclerView.right + replyActionSizePx
                }
                val iconX = lerp(startIconX, finalIconX, easeOut(revealProgress))
                val scale = lerp(0.86f, 1f, easeOut(revealProgress))
                val alpha = (255f * smoothStep(revealProgress)).toInt().coerceIn(0, 255)
                
                if (alpha > 0) {
                    c.save()
                    c.scale(scale, scale, iconX, itemCentery)
                    circlePaint.alpha = alpha
                    replyIcon.alpha = alpha
                    
                    c.drawCircle(iconX, itemCentery, circleRadius, circlePaint)
                    
                    val halfIcon = iconSize / 2f
                    replyIcon.setBounds(
                        (iconX - halfIcon).toInt(),
                        (itemCentery - halfIcon).toInt(),
                        (iconX + halfIcon).toInt(),
                        (itemCentery + halfIcon).toInt()
                    )
                    replyIcon.draw(c)
                    circlePaint.alpha = 255
                    replyIcon.alpha = 255
                    
                    c.restore()
                }
            }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        resetSwipeState()
        resetForwardButton(viewHolder.itemView)
    }

    private fun updateForwardButton(itemView: View, swipeDistanceAbs: Float) {
        val button = itemView.findViewById<View>(R.id.btnForwardMessage) ?: return
        val progress = smoothStep((swipeDistanceAbs / buttonFadeDistancePx).coerceIn(0f, 1f))
        val eased = easeOut(progress)
        val scale = lerp(1f, 0.82f, eased)
        button.scaleX = scale
        button.scaleY = scale
        button.alpha = lerp(1f, 0f, progress)
    }

    private fun resetForwardButton(itemView: View) {
        val button = itemView.findViewById<View>(R.id.btnForwardMessage) ?: return
        if (button.scaleX != 1f) button.scaleX = 1f
        if (button.scaleY != 1f) button.scaleY = 1f
        if (button.alpha != 1f) button.alpha = 1f
    }

    private fun computeSmoothedSwipeDx(effectiveDx: Float, recyclerWidth: Float): Float {
        val sign = if (effectiveDx >= 0f) 1f else -1f
        val distance = abs(effectiveDx)
        val linearRegionPx = recyclerWidth * 0.09f
        val resistanceRegionPx = recyclerWidth * 0.20f
        val smoothedDistance = if (distance <= linearRegionPx) {
            distance
        } else {
            val overshoot = distance - linearRegionPx
            linearRegionPx + (resistanceRegionPx * (1f - exp(-overshoot / resistanceRegionPx)))
        }
        return smoothedDistance * sign
    }

    private fun resetSwipeState() {
        wasActive = false
        lastDx = 0f
        activationDxAbs = 0f
        activeSwipeSign = 0
        isTriggered = false
        lastHapticTriggerX = 0f
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + ((end - start) * progress)
    }

    private fun smoothStep(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return clamped * clamped * (3f - (2f * clamped))
    }

    private fun easeOut(progress: Float): Float {
        val clamped = progress.coerceIn(0f, 1f)
        return 1f - ((1f - clamped) * (1f - clamped))
    }
}
