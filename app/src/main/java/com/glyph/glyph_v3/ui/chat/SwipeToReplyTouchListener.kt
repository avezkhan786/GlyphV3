package com.glyph.glyph_v3.ui.chat

import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewParent
import androidx.recyclerview.widget.RecyclerView
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.glyph.glyph_v3.data.models.Message
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * RecyclerView-level swipe-to-reply detector that mirrors the Compose UX.
 * - Single OnItemTouchListener with zero per-item allocations.
 * - Applies translation without affecting opacity.
 * - Emits detent haptics from the first movement and a boom on reply.
 */
class SwipeToReplyTouchListener(
    private val recyclerView: RecyclerView,
    private val adapter: ChatAdapter,
    private val selectionManager: SelectionManager,
    private val onReply: (Message) -> Unit
) : RecyclerView.OnItemTouchListener {

    private val vibrator: Vibrator? = recyclerView.context.getSystemService(Vibrator::class.java)
    private val touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
    private val replyThresholdPx = recyclerView.context.resources.displayMetrics.density * 72f
    private val maxSwipePx = recyclerView.context.resources.displayMetrics.density * 120f
    private val resistFraction = 0.7f
    private val detentSpacing = recyclerView.context.resources.displayMetrics.density * 16f
    private val springStiffness = SpringForce.STIFFNESS_MEDIUM
    private val springDamping = 0.55f

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var dragging = false
    private var targetHolder: RecyclerView.ViewHolder? = null
    private var targetMessage: Message? = null
    private var lastOffset = 0f
    private var lastDetentUnit = 0
    private var hasTriggeredHaptic = false
    private var hasCrossedThreshold = false
    private val bubbleSprings = WeakHashMap<View, SpringBundle>()
    private val iconSprings = WeakHashMap<View, SpringBundle>()
    private val replyIconId by lazy {
        recyclerView.resources.getIdentifier("reply_icon", "id", recyclerView.context.packageName)
    }
    private val swipeReplyIconId by lazy {
        recyclerView.resources.getIdentifier("swipe_reply_icon", "id", recyclerView.context.packageName)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = e.getPointerId(0)
                startX = e.x
                startY = e.y
                lastX = e.x
                dragging = false
                targetHolder = findChildViewHolder(e)
                targetMessage = resolveMessage(targetHolder)
                lastOffset = 0f
                lastDetentUnit = 0
                hasTriggeredHaptic = false
                hasCrossedThreshold = false
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = e.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return false
                val dx = e.getX(pointerIndex) - startX
                val dy = e.getY(pointerIndex) - startY

                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    val msg = targetMessage ?: return false
                    if (selectionManager.hasSelection()) return false
                    requestDisallowInterceptTouch(true)
                    recyclerView.stopScroll()
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetState()
            }
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return
                val pointerIndex = e.findPointerIndex(activePointerId)
                if (pointerIndex == -1) return
                val x = e.getX(pointerIndex)
                val dragAmount = x - lastX
                lastX = x
                val msg = targetMessage ?: return
                val holder = targetHolder ?: return

                val isSelf = !msg.isIncoming
                val currentOffset = lastOffset
                val desiredSign = if (isSelf) -1f else 1f
                if (currentOffset == 0f && dragAmount * desiredSign <= 0f) {
                    return
                }

                val absoluteTarget = abs(currentOffset + dragAmount)
                val resistance = if (absoluteTarget > replyThresholdPx) {
                    1f - ((absoluteTarget - replyThresholdPx) / (maxSwipePx - replyThresholdPx)).coerceIn(0f, 1f) * resistFraction
                } else 1f

                val resistedDelta = dragAmount * resistance
                val newOffset = currentOffset + resistedDelta
                val clamped = if (isSelf) newOffset.coerceIn(-maxSwipePx, 0f) else newOffset.coerceIn(0f, maxSwipePx)
                applyOffset(holder, clamped)
                lastOffset = clamped

                val absClamped = abs(clamped)
                if (!hasTriggeredHaptic && absClamped >= replyThresholdPx) {
                    hasTriggeredHaptic = true
                    holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                } else if (hasTriggeredHaptic && absClamped < replyThresholdPx * 0.85f) {
                    hasTriggeredHaptic = false
                }

                val detentUnit = (absClamped / detentSpacing).toInt()
                if (detentUnit != lastDetentUnit) {
                    holder.itemView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    lastDetentUnit = detentUnit
                }

                if (absClamped >= replyThresholdPx) {
                    hasCrossedThreshold = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val holder = targetHolder
                    val msg = targetMessage
                    animateReset(holder)
                    if (abs(lastOffset) >= replyThresholdPx && msg != null) {
                        if (hasCrossedThreshold) {
                            triggerBoomVibration()
                        }
                        onReply(msg)
                    }
                }
                resetState()
            }
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // No-op
    }

    private fun resolveMessage(holder: RecyclerView.ViewHolder?): Message? {
        val position = holder?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
        if (position == RecyclerView.NO_POSITION) return null
        val item = adapter.currentList.getOrNull(position) as? ChatListItem.MessageItem ?: return null
        return item.message.takeIf { !it.isDeletedForAll }
    }

    private fun findChildViewHolder(e: MotionEvent): RecyclerView.ViewHolder? {
        val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return null
        return recyclerView.getChildViewHolder(child)
    }

    private fun applyOffset(holder: RecyclerView.ViewHolder, offset: Float) {
        holder.itemView.translationX = offset
        holder.itemView.alpha = 1f
    }

    private fun animateReset(holder: RecyclerView.ViewHolder?) {
        val view = holder?.itemView ?: return
        springToRest(view, 0f, 1f, 1f, bubbleSprings)

        val replyIcon = findReplyIcon(view)
        if (replyIcon != null) {
            springToRest(replyIcon, 0f, 0.6f, 0f, iconSprings)
        }
    }

    private fun resetState() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        dragging = false
        targetHolder = null
        targetMessage = null
        lastOffset = 0f
        lastDetentUnit = 0
        hasTriggeredHaptic = false
        hasCrossedThreshold = false
    }

    private fun triggerBoomVibration() {
        vibrator?.vibrate(VibrationEffect.createOneShot(25L, 90))
    }

    private fun requestDisallowInterceptTouch(disallow: Boolean) {
        var parent: ViewParent? = recyclerView.parent
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow)
            parent = parent.parent
        }
    }

    private fun springToRest(view: View, endTranslationX: Float, endScale: Float, endAlpha: Float, cache: WeakHashMap<View, SpringBundle>) {
        val bundle = cache.getOrPut(view) {
            SpringBundle(
                SpringAnimation(view, SpringAnimation.TRANSLATION_X),
                SpringAnimation(view, SpringAnimation.SCALE_X),
                SpringAnimation(view, SpringAnimation.SCALE_Y),
                SpringAnimation(view, SpringAnimation.ALPHA)
            ).apply {
                configureSpring(translation)
                configureSpring(scaleX)
                configureSpring(scaleY)
                configureSpring(alpha)
            }
        }

        bundle.translation.cancel(); bundle.translation.setStartValue(view.translationX); bundle.translation.animateToFinalPosition(endTranslationX)
        bundle.scaleX.cancel(); bundle.scaleX.setStartValue(view.scaleX); bundle.scaleX.animateToFinalPosition(endScale)
        bundle.scaleY.cancel(); bundle.scaleY.setStartValue(view.scaleY); bundle.scaleY.animateToFinalPosition(endScale)
        bundle.alpha.cancel(); bundle.alpha.setStartValue(view.alpha); bundle.alpha.animateToFinalPosition(endAlpha)
    }

    private fun findReplyIcon(root: View): View? {
        val tagged = root.findViewWithTag<View?>("reply_icon")
        if (tagged != null) return tagged
        val id = when {
            swipeReplyIconId != 0 -> swipeReplyIconId
            replyIconId != 0 -> replyIconId
            else -> 0
        }
        return if (id != 0) root.findViewById(id) else null
    }

    private inner class SpringBundle(
        val translation: SpringAnimation,
        val scaleX: SpringAnimation,
        val scaleY: SpringAnimation,
        val alpha: SpringAnimation
    ) {
        fun configureSpring(animation: SpringAnimation) {
            val force = animation.spring ?: SpringForce()
            force.dampingRatio = springDamping
            force.stiffness = springStiffness
            animation.spring = force
        }
    }
}
