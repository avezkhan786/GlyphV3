package com.glyph.glyph_v3.ui.chat

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.databinding.ActivityChatBinding

internal class ScrollToBottomFabController(
    private val binding: ActivityChatBinding,
    private val recyclerView: RecyclerView,
    private val dpToPx: (Float) -> Int,
    private val isTypingTransitionRunningProvider: () -> Boolean,
    private val isInteractiveQuickReplyOverlayActiveProvider: () -> Boolean,
    private val isGroupChatProvider: () -> Boolean,
    private val isOtherUserTypingProvider: () -> Boolean,
    private val hasUserScrolledUpProvider: () -> Boolean,
    private val resolveTypingIndicatorShiftPx: () -> Int,
    private val onScrollToBottomClick: () -> Unit,
    private val onRecyclerScrolled: () -> Unit,
    private val onRecyclerScrollIdle: () -> Unit,
    private val onFabLayoutChanged: () -> Unit
) {
    private val collapsedFabSizePx: Int by lazy(LazyThreadSafetyMode.NONE) { dpToPx(44f) }

    var suppressUntilAtBottom: Boolean = false

    var unreadCount: Int = 0
        private set

    private var isAtBottom: Boolean = true
    private var isScrollFabVisible: Boolean = false
    private var isScrollFabExpanded: Boolean = false
    private var keepVisibleUntilStrictBottom: Boolean = false
    private var pendingUiUpdate: Boolean = false
    private var deferLabelExpansionUntilIdle: Boolean = false
    private var cachedExpandedLabel: String? = null
    private var cachedExpandedWidthPx: Int = 0

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            onRecyclerScrolled()
            scheduleUpdate()
        }

        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                onRecyclerScrollIdle()
            }
            scheduleUpdate()
        }
    }

    fun attach() {
        val fab = binding.fabScrollToBottom
        fab.visibility = View.INVISIBLE
        fab.alpha = 0f
        fab.scaleX = 0.82f
        fab.scaleY = 0.82f
        fab.isClickable = false

        binding.ivScrollFabIconStart.visibility = View.GONE
        binding.tvScrollFabLabel.visibility = View.GONE
        binding.tvScrollFabUnreadBadge.visibility = View.GONE
        binding.ivScrollFabIconCenter.alpha = 1f
        binding.ivScrollFabIconStart.alpha = 0f
        binding.tvScrollFabLabel.alpha = 0f

        fab.setOnClickListener {
            fab.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            suppressUntilAtBottom = true
            unreadCount = 0
            deferLabelExpansionUntilIdle = false
            hideImmediately()
            onScrollToBottomClick()
        }

        recyclerView.addOnScrollListener(scrollListener)
        fab.post { scheduleUpdate() }
    }

    fun scheduleUpdate() {
        if (pendingUiUpdate) return
        pendingUiUpdate = true
        binding.fabScrollToBottom.postOnAnimation {
            pendingUiUpdate = false
            updateUi()
        }
    }

    fun setUnreadCount(newCount: Int, animate: Boolean) {
        val clamped = newCount.coerceAtLeast(0)
        if (clamped == unreadCount) return

        val oldCount = unreadCount
        unreadCount = clamped

        if (unreadCount > 0) {
            val nextLabel = formatUnreadLabel(unreadCount)
            if (binding.tvScrollFabLabel.text?.toString() != nextLabel) {
                binding.tvScrollFabLabel.text = nextLabel
                cachedExpandedLabel = null
            }
            binding.tvScrollFabUnreadBadge.text = formatUnreadBadge(unreadCount)
        } else {
            deferLabelExpansionUntilIdle = false
        }

        val activelyScrolling = recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
        if (unreadCount > 0 && activelyScrolling) {
            deferLabelExpansionUntilIdle = true
        }

        if (animate && unreadCount > oldCount && isScrollFabVisible && !activelyScrolling) {
            pulseVisibleFab()
        }

        scheduleUpdate()
    }

    fun hideImmediately() {
        val fab = binding.fabScrollToBottom

        fab.animate().cancel()
        binding.ivScrollFabIconCenter.animate().cancel()
        binding.ivScrollFabIconStart.animate().cancel()
        binding.tvScrollFabLabel.animate().cancel()
        binding.tvScrollFabUnreadBadge.animate().cancel()

        isScrollFabVisible = false
        keepVisibleUntilStrictBottom = false
        fab.isClickable = false
        fab.alpha = 0f
        fab.scaleX = 0.82f
        fab.scaleY = 0.82f
        fab.visibility = View.INVISIBLE
        fab.translationY = 0f

        collapse(animate = false)
        hideUnreadBadge(animate = false)
        dispatchFabLayoutChanged()
    }

    fun collapse(animate: Boolean) {
        val fab = binding.fabScrollToBottom
        val collapsedPx = collapsedFabSizePx
        val currentWidth = (fab.layoutParams?.width ?: collapsedPx).takeIf { it > 0 } ?: collapsedPx

        if (!isScrollFabExpanded &&
            binding.ivScrollFabIconStart.visibility != View.VISIBLE &&
            binding.tvScrollFabLabel.visibility != View.VISIBLE &&
            currentWidth == collapsedPx
        ) {
            binding.ivScrollFabIconCenter.alpha = 1f
            return
        }

        isScrollFabExpanded = false

        binding.ivScrollFabIconCenter.animate().cancel()
        binding.ivScrollFabIconStart.animate().cancel()
        binding.tvScrollFabLabel.animate().cancel()

        if (animate) {
            binding.tvScrollFabLabel.animate().alpha(0f).setDuration(90).setInterpolator(FastOutSlowInInterpolator()).start()
            binding.ivScrollFabIconStart.animate().alpha(0f).setDuration(90).setInterpolator(FastOutSlowInInterpolator()).withEndAction {
                binding.ivScrollFabIconStart.visibility = View.GONE
                binding.tvScrollFabLabel.visibility = View.GONE
            }.start()
            binding.ivScrollFabIconCenter.animate().alpha(1f).setDuration(110).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            binding.ivScrollFabIconCenter.alpha = 1f
            binding.ivScrollFabIconStart.alpha = 0f
            binding.tvScrollFabLabel.alpha = 0f
            binding.ivScrollFabIconStart.visibility = View.GONE
            binding.tvScrollFabLabel.visibility = View.GONE
        }

        if (currentWidth != collapsedPx) {
            fab.layoutParams = fab.layoutParams.apply { width = collapsedPx }
            fab.requestLayout()
            dispatchFabLayoutChanged()
        }
    }

    private fun updateUi() {
        if (isInteractiveQuickReplyOverlayActiveProvider()) {
            hideImmediately()
            return
        }

        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount == 0) {
            isAtBottom = true
            suppressUntilAtBottom = false
            setVisible(false)
            return
        }

        val density = binding.root.resources.displayMetrics.density
        val distanceFromBottom = distanceFromBottomPx()
        val fabShowThresholdPx = (24 * density).toInt()
        val fabHideThresholdPx = (12 * density).toInt()
        val strictlyAtBottom = isStrictlyAtBottom()
        val hasUserScrolledUp = hasUserScrolledUpProvider()
        val typingInternalMotion = !hasUserScrolledUp && unreadCount == 0 && isTypingTransitionRunningProvider()
        val nearBottomNow = when {
            typingInternalMotion -> true
            distanceFromBottom <= fabHideThresholdPx -> true
            distanceFromBottom >= fabShowThresholdPx -> false
            else -> isAtBottom
        }
        isAtBottom = nearBottomNow

        if (strictlyAtBottom) {
            suppressUntilAtBottom = false
            keepVisibleUntilStrictBottom = false
            unreadCount = 0
            deferLabelExpansionUntilIdle = false
            collapse(animate = true)
            hideUnreadBadge(animate = true)
        }

        if (typingInternalMotion) {
            setVisible(false)
            collapse(animate = false)
            return
        }

        if (!isGroupChatProvider() && isOtherUserTypingProvider() && unreadCount == 0) {
            val typingShiftTolerance = resolveTypingIndicatorShiftPx() + (64 * density).toInt()
            if (distanceFromBottom <= typingShiftTolerance) {
                setVisible(false)
                collapse(animate = true)
                return
            }
        }

        if (suppressUntilAtBottom && !isAtBottom) {
            hideImmediately()
            return
        }

        val shouldKeepVisibleForScrollIntent = when {
            strictlyAtBottom -> {
                keepVisibleUntilStrictBottom = false
                false
            }
            !hasUserScrolledUp -> {
                keepVisibleUntilStrictBottom = false
                false
            }
            keepVisibleUntilStrictBottom -> true
            !isAtBottom -> {
                keepVisibleUntilStrictBottom = true
                true
            }
            else -> false
        }

        val shouldKeepVisibleForUnread = unreadCount > 0 && !strictlyAtBottom
        val shouldShow = itemCount > 0 && (shouldKeepVisibleForScrollIntent || shouldKeepVisibleForUnread)
        setVisible(shouldShow)
        if (!shouldShow) return

        val activelyScrolling = recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE
        if (unreadCount > 0) {
            if (activelyScrolling) {
                showUnreadBadge(animate = true)
                collapse(animate = false)
                return
            }

            hideUnreadBadge(animate = true)
            expandIfNeeded(animate = true)
            deferLabelExpansionUntilIdle = false
        } else {
            hideUnreadBadge(animate = true)
            collapse(animate = true)
        }
    }

    private fun setVisible(visible: Boolean) {
        if (visible && suppressUntilAtBottom) {
            return
        }
        if (visible == isScrollFabVisible) return
        isScrollFabVisible = visible

        val fab = binding.fabScrollToBottom
        fab.animate().cancel()
        val interpolator = DecelerateInterpolator(1.4f)

        if (visible) {
            fab.visibility = View.VISIBLE
            fab.isClickable = true
            fab.elevation = dpToPx(28f).toFloat()
            fab.translationZ = dpToPx(28f).toFloat()
            fab.bringToFront()
            fab.scaleX = 0.82f
            fab.scaleY = 0.82f
            fab.alpha = 0f
            fab.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(210)
                .setInterpolator(interpolator)
                .withEndAction { dispatchFabLayoutChanged() }
                .start()
        } else {
            fab.isClickable = false
            fab.animate()
                .alpha(0f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(190)
                .setInterpolator(interpolator)
                .withEndAction {
                    fab.visibility = View.INVISIBLE
                    fab.translationY = 0f
                    collapse(animate = false)
                    dispatchFabLayoutChanged()
                }
                .start()
        }
    }

    private fun pulseVisibleFab() {
        val fab = binding.fabScrollToBottom
        fab.animate().cancel()
        fab.animate()
            .scaleX(1.04f)
            .scaleY(1.04f)
            .setDuration(110)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                fab.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
            .start()
    }

    private fun expandIfNeeded(animate: Boolean) {
        if (isScrollFabExpanded) {
            if (binding.tvScrollFabLabel.visibility == View.VISIBLE) {
                applyExpandedWidth()
            }
            return
        }
        if (unreadCount <= 0) return

        isScrollFabExpanded = true

        binding.ivScrollFabIconCenter.animate().cancel()
        binding.ivScrollFabIconStart.animate().cancel()
        binding.tvScrollFabLabel.animate().cancel()

        binding.ivScrollFabIconStart.visibility = View.VISIBLE
        binding.tvScrollFabLabel.visibility = View.VISIBLE
        binding.ivScrollFabIconStart.alpha = 0f
        binding.tvScrollFabLabel.alpha = 0f

        if (animate) {
            binding.ivScrollFabIconCenter.animate().alpha(0f).setDuration(80).setInterpolator(FastOutSlowInInterpolator()).start()
            binding.ivScrollFabIconStart.animate().alpha(1f).setDuration(110).setInterpolator(FastOutSlowInInterpolator()).start()
            binding.tvScrollFabLabel.animate().alpha(1f).setDuration(140).setStartDelay(40).setInterpolator(FastOutSlowInInterpolator()).start()
        } else {
            binding.ivScrollFabIconCenter.alpha = 0f
            binding.ivScrollFabIconStart.alpha = 1f
            binding.tvScrollFabLabel.alpha = 1f
        }

        applyExpandedWidth()
    }

    private fun applyExpandedWidth() {
        val fab = binding.fabScrollToBottom
        val collapsedPx = collapsedFabSizePx

        if (binding.ivScrollFabIconStart.visibility != View.VISIBLE) binding.ivScrollFabIconStart.visibility = View.VISIBLE
        if (binding.tvScrollFabLabel.visibility != View.VISIBLE) binding.tvScrollFabLabel.visibility = View.VISIBLE

        val currentWidth = (fab.layoutParams?.width ?: collapsedPx).takeIf { it > 0 } ?: collapsedPx
        val label = binding.tvScrollFabLabel.text?.toString().orEmpty()
        val targetWidth = if (cachedExpandedLabel == label && cachedExpandedWidthPx > 0) {
            cachedExpandedWidthPx
        } else {
            val lp = fab.layoutParams
            val previousWidth = lp.width
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            fab.layoutParams = lp

            val parentWidth = (binding.root.width).takeIf { it > 0 } ?: binding.root.resources.displayMetrics.widthPixels
            val widthSpec = View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(fab.height.takeIf { it > 0 } ?: collapsedFabSizePx, View.MeasureSpec.EXACTLY)
            fab.measure(widthSpec, heightSpec)
            val measuredWidth = fab.measuredWidth.coerceAtLeast(collapsedPx)

            fab.layoutParams = fab.layoutParams.apply { width = previousWidth.takeIf { it > 0 } ?: currentWidth }
            cachedExpandedLabel = label
            cachedExpandedWidthPx = measuredWidth
            measuredWidth
        }

        if (currentWidth != targetWidth) {
            fab.layoutParams = fab.layoutParams.apply { width = targetWidth }
            fab.requestLayout()
            dispatchFabLayoutChanged()
        }
    }

    private fun showUnreadBadge(animate: Boolean) {
        if (unreadCount <= 0) {
            hideUnreadBadge(animate)
            return
        }

        val badge = binding.tvScrollFabUnreadBadge
        badge.text = formatUnreadBadge(unreadCount)
        badge.animate().cancel()
        if (badge.visibility == View.VISIBLE && badge.alpha >= 1f) return

        badge.visibility = View.VISIBLE
        if (!animate) {
            badge.alpha = 1f
            badge.scaleX = 1f
            badge.scaleY = 1f
            return
        }

        badge.alpha = 0f
        badge.scaleX = 0.82f
        badge.scaleY = 0.82f
        badge.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun hideUnreadBadge(animate: Boolean) {
        val badge = binding.tvScrollFabUnreadBadge
        badge.animate().cancel()
        if (badge.visibility != View.VISIBLE) return

        if (!animate) {
            badge.alpha = 0f
            badge.visibility = View.GONE
            return
        }

        badge.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(90)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                badge.visibility = View.GONE
                badge.scaleX = 1f
                badge.scaleY = 1f
            }
            .start()
    }

    private fun distanceFromBottomPx(): Int {
        val verticalOffset = recyclerView.computeVerticalScrollOffset()
        val verticalExtent = recyclerView.computeVerticalScrollExtent()
        val verticalRange = recyclerView.computeVerticalScrollRange()
        if (verticalRange <= verticalExtent) return 0
        return (verticalRange - verticalExtent - verticalOffset).coerceAtLeast(0)
    }

    private fun isStrictlyAtBottom(): Boolean {
        return !recyclerView.canScrollVertically(1)
    }

    private fun formatUnreadLabel(count: Int): String {
        return if (count == 1) {
            "1 new message"
        } else {
            "$count new messages"
        }
    }

    private fun formatUnreadBadge(count: Int): String {
        return if (count > 99) "99+" else count.toString()
    }

    private fun dispatchFabLayoutChanged() {
        binding.fabScrollToBottom.post { onFabLayoutChanged() }
    }
}