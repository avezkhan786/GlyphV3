package com.glyph.glyph_v3.ui.chat.translation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Message
import com.google.android.material.color.MaterialColors

/**
 * Floating translation toolbar shown above a tapped message bubble.
 *
 * Implementation: PopupWindow (lightweight, no layout recalculation on RecyclerView).
 *
 * Features:
 * - Anchored above the tapped view
 * - Fade+translate entry animation (180ms)
 * - Three actions: Translate, Play Voice, Change Language
 * - Separate preview popup for showing results
 * - Dismiss on outside touch
 * - Does NOT trigger RecyclerView rebind
 */
class TranslationToolbarPopup(
    private val recyclerView: RecyclerView,
    private val translationManager: TranslationManager
) {

    companion object {
        private const val ANIMATION_DURATION = 220L
        private const val TOOLBAR_Y_OFFSET_DP = 8
        private const val TOOLBAR_HORIZONTAL_MARGIN_DP = 12
        private const val POINTER_EDGE_PADDING_DP = 24
        private const val EXPANSION_ANIMATION_DURATION = 260L
    }

    private data class PopupPlacement(
        val x: Int,
        val y: Int,
        val pointerCenterX: Float
    )

    private var toolbarPopup: PopupWindow? = null
    private var previewPopup: PopupWindow? = null
    private var anchorView: View? = null
    var activeMessage: Message? = null
    private var toolbarView: View? = null
    private var isDismissing = false
    private var isCurrentlyTranslated = false
    private var translationStateObserver: Observer<TranslationUiState>? = null
    private var ttsBannerObserver: Observer<TtsBannerUiState>? = null
    private var lastStatusVisible = false
    private var lastActiveStep: TtsWorkflowStep? = null
    private var lastErrorState = false
    private var isStatusTransitionRunning = false
    private var statusVisibilityAnimator: ValueAnimator? = null
    private var lastVisiblePlacement: PopupPlacement? = null
    private var lastPopupX = Int.MIN_VALUE
    private var lastPopupY = Int.MIN_VALUE
    private var lastPreviewX = Int.MIN_VALUE
    private var lastPreviewY = Int.MIN_VALUE
    // Locked popup width so size never changes mid-session
    private var lockedPopupWidth = 0

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            updatePosition()
        }
    }

    private val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        if (view !== toolbarView) {
            updatePosition()
        }
    }

    private val recyclerTouchListener = object : RecyclerView.OnItemTouchListener {
        private val gestureDetector = GestureDetector(recyclerView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isPointInsideView(e.rawX, e.rawY, toolbarView) &&
                    !isPointInsideView(e.rawX, e.rawY, previewPopup?.contentView) &&
                    !isPointInsideView(e.rawX, e.rawY, anchorView)) {
                    dismiss()
                }
                return false
            }
        })
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(e)
            return false
        }
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    var onLanguageChangeRequested: (() -> Unit)? = null
    var onTranslateRequested: ((Message) -> Unit)? = null
    var onOriginalRequested: ((Message) -> Unit)? = null

    val isShowing: Boolean
        get() = toolbarPopup?.isShowing == true

    /**
     * Show the toolbar above the given anchor view (the chat bubble).
     * Does not modify the RecyclerView or trigger any rebind.
     */
    fun show(anchor: View, message: Message, isTranslated: Boolean = false) {
        
        // If already showing, we always dismiss.
        // If it's the same message, it's a "toggle off".
        // If it's a different message, we dismiss the old one but don't immediately open the new one
        // (to satisfy "first close the existing toolbar... not automatically transfer").
        if (isShowing) {
            dismiss()
            return
        }

        // Don't show for non-text messages, deleted messages, or emoji-only messages
        val isEmojiOnly = com.glyph.glyph_v3.ui.chat.EmojiUtils.isEmojiOnlyMessage(message.text)
        if (message.type != com.glyph.glyph_v3.data.models.MessageType.TEXT || 
            message.text.isBlank() || 
            message.isDeletedForAll || 
            isEmojiOnly) {
            return
        }

        isDismissing = false
        anchorView = anchor
        activeMessage = message
        isCurrentlyTranslated = isTranslated
        translationManager.setActiveMessage(message)
        lastStatusVisible = false
        lastActiveStep = null
        lastErrorState = false
        lockedPopupWidth = 0

        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val density = context.resources.displayMetrics.density

        // Inflate toolbar layout
        val toolbarView = inflater.inflate(R.layout.popup_translation_toolbar, null)

        // Setup click listeners
        // Translate / Original toggle button
        val tvTranslateLabel = toolbarView.findViewById<TextView>(R.id.tvTranslateLabel)
        tvTranslateLabel.text = if (isCurrentlyTranslated) "Original" else "Translate"

        toolbarView.findViewById<View>(R.id.btnTranslate).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val msg = activeMessage
            if (msg != null) {
                if (isCurrentlyTranslated) {
                    // Currently showing translation → revert to original
                    onOriginalRequested?.invoke(msg)
                    setTranslateButtonState(false)
                } else {
                    // Currently showing original → translate
                    onTranslateRequested?.invoke(msg)
                    setTranslateButtonState(true)
                }
            }
        }

        toolbarView.findViewById<View>(R.id.btnPlayVoice).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            val state = translationManager.translationState.value
            
            when {
                // Already playing → stop
                state is TranslationUiState.Success && state.isPlayingAudio -> {
                    translationManager.stopAudio()
                }
                // Loading audio → ignore (debounce)
                state is TranslationUiState.Success && state.isLoadingAudio -> {
                }
                state is TranslationUiState.Loading -> {
                }
                // Play TTS of the original text (independent of translation)
                else -> {
                    translationManager.translateAndPlayAudio()
                }
            }
        }

        toolbarView.findViewById<View>(R.id.btnChangeLanguage).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onLanguageChangeRequested?.invoke()
        }

        // Update language label
        val languageLabel = toolbarView.findViewById<TextView>(R.id.tvLanguageLabel)
        val selectedLang = translationManager.selectedLanguage.value ?: TranslationLanguage.DEFAULT
        languageLabel.text = selectedLang.code.uppercase()

        // Measure toolbar to calculate position
        toolbarView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val toolbarWidth = toolbarView.measuredWidth
        val toolbarHeight = toolbarView.measuredHeight
        lockedPopupWidth = toolbarWidth

        this.toolbarView = toolbarView
        toolbarView.addOnLayoutChangeListener(layoutListener)
        // Reposition popup whenever its own content changes height (expand/collapse)

        // Create PopupWindow with locked width so content changes never resize it
        val popup = PopupWindow(
            toolbarView,
            lockedPopupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false // Focusable = false to allow scrolling the chat while open
        )
        popup.isOutsideTouchable = false // We handle dismissal via recyclerTouchListener
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 0f

        popup.setOnDismissListener {
            translationStateObserver?.let { translationManager.translationState.removeObserver(it) }
            ttsBannerObserver?.let { translationManager.ttsBannerState.removeObserver(it) }
            translationStateObserver = null
            ttsBannerObserver = null
            dismissPreview()
            translationManager.dismiss()
            
            // Clean up listeners
            recyclerView.removeOnScrollListener(scrollListener)
            recyclerView.removeOnLayoutChangeListener(layoutListener)
            recyclerView.removeOnItemTouchListener(recyclerTouchListener)
            anchorView?.removeOnLayoutChangeListener(layoutListener)
            this.toolbarView?.removeOnLayoutChangeListener(layoutListener)

            anchorView = null
            activeMessage = null
            this.toolbarView = null
            lastPopupX = Int.MIN_VALUE
            lastPopupY = Int.MIN_VALUE
            lastPreviewX = Int.MIN_VALUE
            lastPreviewY = Int.MIN_VALUE
        }

        // Add listeners to keep it anchored
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener(layoutListener)
        recyclerView.addOnItemTouchListener(recyclerTouchListener)
        anchor.addOnLayoutChangeListener(layoutListener)

        val initialPlacement = calculatePlacement(anchor, toolbarWidth, toolbarHeight)

        // Show popup
        popup.showAtLocation(
            recyclerView,
            Gravity.NO_GRAVITY,
            initialPlacement.x,
            initialPlacement.y
        )
        lastPopupX = initialPlacement.x
        lastPopupY = initialPlacement.y

        toolbarPopup = popup

        // Observe state changes to reactively update the play button
        translationStateObserver = Observer { state ->
            when (state) {
                is TranslationUiState.Success -> {
                    when {
                        state.isLoadingAudio -> updatePlayButton(toolbarView, PlayButtonState.LOADING)
                        state.isPlayingAudio -> updatePlayButton(toolbarView, PlayButtonState.PLAYING)
                        else -> updatePlayButton(toolbarView, PlayButtonState.IDLE)
                    }
                }
                is TranslationUiState.Loading -> updatePlayButton(toolbarView, PlayButtonState.LOADING)
                else -> updatePlayButton(toolbarView, PlayButtonState.IDLE)
            }
        }
        translationManager.translationState.observeForever(translationStateObserver!!)

        ttsBannerObserver = Observer { state ->
            val tView = toolbarView ?: return@Observer
            renderStatusStrip(tView, state)
        }
        translationManager.ttsBannerState.observeForever(ttsBannerObserver!!)
        renderStatusStrip(toolbarView, translationManager.ttsBannerState.value ?: TtsBannerUiState.Hidden)

        // Animate in: fade + translate up
        toolbarView.alpha = 0f
        toolbarView.translationY = (10 * density)
        toolbarView.scaleX = 0.96f
        toolbarView.scaleY = 0.96f

        val fadeIn = ObjectAnimator.ofFloat(toolbarView, "alpha", 0f, 1f)
        val slideUp = ObjectAnimator.ofFloat(toolbarView, "translationY", 10 * density, 0f)
        val scaleX = ObjectAnimator.ofFloat(toolbarView, View.SCALE_X, 0.96f, 1f)
        val scaleY = ObjectAnimator.ofFloat(toolbarView, View.SCALE_Y, 0.96f, 1f)

        AnimatorSet().apply {
            playTogether(fadeIn, slideUp, scaleX, scaleY)
            duration = ANIMATION_DURATION
            interpolator = FastOutSlowInInterpolator()
            start()
        }
    }

    private fun updatePosition() {
        val popup = toolbarPopup ?: return
        val anchor = anchorView ?: return
        val tView = toolbarView ?: return

        if (!anchor.isAttachedToWindow) {
            dismiss()
            return
        }

        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val recyclerLocation = IntArray(2)
        recyclerView.getLocationInWindow(recyclerLocation)
        val recyclerTop = recyclerLocation[1]
        val recyclerBottom = recyclerTop + recyclerView.height

        // Measure if not measured (should be already)
        if (tView.measuredWidth == 0) {
            tView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        }
        // Use locked width so popup never changes horizontal size mid-session
        val toolbarWidth = if (lockedPopupWidth > 0) lockedPopupWidth else tView.measuredWidth
        val toolbarHeight = tView.measuredHeight
        val placement = calculatePlacement(anchor, toolbarWidth, toolbarHeight)

        // Check if anchor is scrolled too far out (completely above or below recycler)
        // We add a small buffer (4dp) to avoid flickering at the edge
        val buffer = (4 * density).toInt()
        val anchorTop = anchorLocation[1]
        val anchorBottom = anchorTop + anchor.height
        val anchorIsVisible = anchorBottom >= recyclerTop - buffer && anchorTop <= recyclerBottom + buffer
        if (!anchorIsVisible) {
            // Keep the popup parked at its last visible position until the bubble comes back.
            // This preserves the user's context during scroll.
            return
        }

        lastVisiblePlacement = placement

        if (popup.isShowing && (placement.x != lastPopupX || placement.y != lastPopupY || isStatusTransitionRunning)) {
            if (isStatusTransitionRunning) {
                popup.update(placement.x, placement.y, toolbarWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                popup.update(placement.x, placement.y, -1, -1)
            }
            lastPopupX = placement.x
            lastPopupY = placement.y
        }

        // --- Update Preview Popup if it exists ---
        previewPopup?.let { pPopup ->
            val pView = pPopup.contentView
            if (pView.measuredWidth == 0) {
                pView.measure(
                    View.MeasureSpec.makeMeasureSpec((280 * density).toInt(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.UNSPECIFIED
                )
            }
            
            val screenWidth = context.resources.displayMetrics.widthPixels
            val margin = (TOOLBAR_HORIZONTAL_MARGIN_DP * density).toInt()
            val previewX = (placement.x + toolbarWidth - pView.measuredWidth).coerceIn(
                margin,
                screenWidth - pView.measuredWidth - margin
            )
            val previewY = placement.y + toolbarHeight + (4 * density).toInt()

            if (previewX != lastPreviewX || previewY != lastPreviewY) {
                pPopup.update(previewX, previewY, -1, -1)
                lastPreviewX = previewX
                lastPreviewY = previewY
            }
        }
    }

    private fun calculatePlacement(anchor: View, contentWidth: Int, contentHeight: Int): PopupPlacement {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val recyclerLocation = IntArray(2)
        recyclerView.getLocationInWindow(recyclerLocation)

        val screenWidth = context.resources.displayMetrics.widthPixels
        val horizontalMargin = (TOOLBAR_HORIZONTAL_MARGIN_DP * density).toInt()
        val anchorCenterX = anchorLocation[0] + anchor.width / 2
        val anchorBias = anchorCenterX.toFloat() / screenWidth.toFloat()

        val preferredX = when {
            anchorBias <= 0.38f -> anchorLocation[0] - (6 * density).toInt()
            anchorBias >= 0.62f -> anchorLocation[0] + anchor.width - contentWidth + (6 * density).toInt()
            else -> anchorCenterX - contentWidth / 2
        }

        val clampedX = preferredX.coerceIn(
            horizontalMargin,
            screenWidth - contentWidth - horizontalMargin
        )

        val yOffset = (TOOLBAR_Y_OFFSET_DP * density).toInt()
        val minTop = recyclerLocation[1] + (6 * density).toInt()
        val y = (anchorLocation[1] - contentHeight - yOffset).coerceAtLeast(minTop)
        val pointerPadding = POINTER_EDGE_PADDING_DP * density
        val pointerCenterX = (anchorCenterX - clampedX).toFloat().coerceIn(
            pointerPadding,
            contentWidth - pointerPadding
        )

        return PopupPlacement(
            x = clampedX,
            y = y,
            pointerCenterX = pointerCenterX
        )
    }

    /**
     * Show the translation preview below the toolbar.
     */
    private fun showPreview(anchor: View) {
        dismissPreview()

        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val density = context.resources.displayMetrics.density

        val previewView = inflater.inflate(R.layout.popup_translation_preview, null)
        val tvTranslated = previewView.findViewById<TextView>(R.id.tvTranslatedText)
        val tvHeader = previewView.findViewById<TextView>(R.id.tvLanguageHeader)
        val progressBar = previewView.findViewById<ProgressBar>(R.id.progressLoading)
        val tvError = previewView.findViewById<TextView>(R.id.tvError)

        // Show loading state initially
        progressBar.visibility = View.VISIBLE
        tvTranslated.visibility = View.GONE
        tvError.visibility = View.GONE

        val selectedLang = translationManager.selectedLanguage.value ?: TranslationLanguage.DEFAULT
        tvHeader.text = "Translating to ${selectedLang.displayName}..."

        // Observe translation state
        translationManager.translationState.observeForever(object : androidx.lifecycle.Observer<TranslationUiState> {
            override fun onChanged(state: TranslationUiState) {
                when (state) {
                    is TranslationUiState.Loading -> {
                        progressBar.visibility = View.VISIBLE
                        tvTranslated.visibility = View.GONE
                        tvError.visibility = View.GONE
                        tvHeader.text = "Translating to ${selectedLang.displayName}..."
                    }
                    is TranslationUiState.Success -> {
                        progressBar.visibility = View.GONE
                        tvTranslated.visibility = View.VISIBLE
                        tvError.visibility = View.GONE
                        tvTranslated.text = state.translatedText
                        tvHeader.text = "${state.language.flag} Translated to ${state.language.displayName}"

                        // Update play button in toolbar
                        toolbarPopup?.contentView?.let { toolbarView ->
                            when {
                                state.isLoadingAudio -> updatePlayButton(toolbarView, PlayButtonState.LOADING)
                                state.isPlayingAudio -> updatePlayButton(toolbarView, PlayButtonState.PLAYING)
                                else -> updatePlayButton(toolbarView, PlayButtonState.IDLE)
                            }
                        }
                    }
                    is TranslationUiState.Error -> {
                        progressBar.visibility = View.GONE
                        tvTranslated.visibility = View.GONE
                        tvError.visibility = View.VISIBLE
                        tvError.text = state.message
                        tvHeader.text = "Translation failed"
                    }
                    is TranslationUiState.Idle -> {
                        // Remove observer when idle
                        translationManager.translationState.removeObserver(this)
                    }
                }
            }
        })

        // Measure
        previewView.measure(
            View.MeasureSpec.makeMeasureSpec(
                (280 * density).toInt(),
                View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popup = PopupWindow(
            previewView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false // Not focusable (toolbar handles focus)
        )
        popup.isOutsideTouchable = false
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 0f

        // Position: below the toolbar, aligned with anchor
        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)

        val toolbarPopupView = toolbarPopup?.contentView
        val toolbarBottom = if (toolbarPopupView != null) {
            val tLoc = IntArray(2)
            toolbarPopupView.getLocationInWindow(tLoc)
            tLoc[1] + toolbarPopupView.height
        } else {
            anchorLocation[1]
        }

        val previewX = anchorLocation[0]
        val screenWidth = context.resources.displayMetrics.widthPixels
        val clampedX = previewX.coerceIn(
            (8 * density).toInt(),
            screenWidth - previewView.measuredWidth - (8 * density).toInt()
        )

        popup.showAtLocation(
            recyclerView,
            Gravity.NO_GRAVITY,
            clampedX,
            toolbarBottom + (4 * density).toInt()
        )
        lastPreviewX = clampedX
        lastPreviewY = toolbarBottom + (4 * density).toInt()

        // Animate
        previewView.alpha = 0f
        previewView.translationY = (-8 * density)
        ObjectAnimator.ofFloat(previewView, "alpha", 0f, 1f).apply {
            duration = ANIMATION_DURATION
            start()
        }
        ObjectAnimator.ofFloat(previewView, "translationY", -8 * density, 0f).apply {
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            start()
        }

        previewPopup = popup
        updatePosition()
    }

    fun updateLanguageLabel(language: TranslationLanguage) {
        toolbarPopup?.contentView?.let { view ->
            view.findViewById<TextView>(R.id.tvLanguageLabel)?.text = language.code.uppercase()
        }
    }

    fun setTranslateButtonState(isTranslated: Boolean) {
        isCurrentlyTranslated = isTranslated
        toolbarPopup?.contentView?.let { view ->
            view.findViewById<TextView>(R.id.tvTranslateLabel)?.text = if (isTranslated) "Original" else "Translate"
        }
        updatePosition()
    }

    fun dismiss(force: Boolean = false) {
        if (!force && translationManager.shouldKeepToolbarVisible()) return
        if (isDismissing || toolbarPopup == null) return
        isDismissing = true

        val tView = toolbarView
        if (tView != null && tView.isAttachedToWindow) {
            val density = tView.resources.displayMetrics.density
            val fadeOut = ObjectAnimator.ofFloat(tView, "alpha", 1f, 0f)
            val slideDown = ObjectAnimator.ofFloat(tView, "translationY", 0f, 12 * density)

            AnimatorSet().apply {
                playTogether(fadeOut, slideDown)
                duration = ANIMATION_DURATION
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        dismissInternal()
                    }
                })
                start()
            }
        } else {
            dismissInternal()
        }
    }

    private enum class PlayButtonState { IDLE, LOADING, PLAYING }

    private fun updatePlayButton(toolbarView: View, state: PlayButtonState) {
        val playIcon = toolbarView.findViewById<ImageView>(R.id.tvPlayIcon)
        val playLabel = toolbarView.findViewById<TextView>(R.id.tvPlayLabel)
        val progressPlay = toolbarView.findViewById<ProgressBar>(R.id.progressPlay)
        when (state) {
            PlayButtonState.LOADING -> {
                progressPlay?.visibility = View.VISIBLE
                playIcon?.visibility = View.GONE
                playLabel?.text = "Loading…"
                playLabel?.textSize = 10f
            }
            PlayButtonState.PLAYING -> {
                progressPlay?.visibility = View.GONE
                playIcon?.visibility = View.VISIBLE
                playIcon?.setImageResource(R.drawable.ic_stop_glyph)
                playLabel?.text = "Stop"
                playLabel?.textSize = 13f
            }
            PlayButtonState.IDLE -> {
                progressPlay?.visibility = View.GONE
                playIcon?.visibility = View.VISIBLE
                playIcon?.setImageResource(R.drawable.ic_play_glyph)
                playLabel?.text = "Play"
                playLabel?.textSize = 13f
            }
        }
    }

    private fun dismissInternal() {
        try {
            toolbarPopup?.dismiss()
        } catch (_: Exception) {}
        toolbarPopup = null
        dismissPreview()
        isDismissing = false
    }

    private fun dismissPreview() {
        val pPopup = previewPopup ?: return
        val pView = pPopup.contentView

        if (pView != null && pView.isAttachedToWindow) {
            val density = pView.resources.displayMetrics.density
            val fadeOut = ObjectAnimator.ofFloat(pView, "alpha", 1f, 0f)
            val slideUp = ObjectAnimator.ofFloat(pView, "translationY", 0f, -8 * density)

            AnimatorSet().apply {
                playTogether(fadeOut, slideUp)
                duration = ANIMATION_DURATION
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        try {
                            pPopup.dismiss()
                        } catch (_: Exception) {}
                        if (previewPopup == pPopup) previewPopup = null
                    }
                })
                start()
            }
        } else {
            try {
                pPopup.dismiss()
            } catch (_: Exception) {}
            previewPopup = null
        }
    }

    private fun isPointInsideView(x: Float, y: Float, view: View?): Boolean {
        val v = view ?: return false
        val location = IntArray(2)
        v.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + v.width &&
                y >= location[1] && y <= location[1] + v.height
    }

    private fun renderStatusStrip(toolbarView: View, state: TtsBannerUiState) {
        val root = toolbarView.findViewById<ViewGroup>(R.id.popupRoot)
        val statusContainer = toolbarView.findViewById<ViewGroup>(R.id.ttsStatusContainer)
        val divider = toolbarView.findViewById<View>(R.id.ttsStatusDivider)
        val eyebrow = toolbarView.findViewById<TextView>(R.id.tvTtsStatusEyebrow)
        val title = toolbarView.findViewById<TextView>(R.id.tvTtsStatusTitle)
        val detail = toolbarView.findViewById<TextView>(R.id.tvTtsStatusDetail)
        val caption = toolbarView.findViewById<TextView>(R.id.tvPlaybackCaption)
        val stepTranslate = toolbarView.findViewById<TextView>(R.id.tvStepTranslate)
        val stepTranslateIndex = toolbarView.findViewById<TextView>(R.id.tvStepTranslateIndex)
        val stepTranslateDot = toolbarView.findViewById<View>(R.id.viewStepTranslateDot)
        val stepAudio = toolbarView.findViewById<TextView>(R.id.tvStepAudio)
        val stepAudioIndex = toolbarView.findViewById<TextView>(R.id.tvStepAudioIndex)
        val stepAudioDot = toolbarView.findViewById<View>(R.id.viewStepAudioDot)
        val stepPlayback = toolbarView.findViewById<TextView>(R.id.tvStepPlayback)
        val stepPlaybackIndex = toolbarView.findViewById<TextView>(R.id.tvStepPlaybackIndex)
        val stepPlaybackDot = toolbarView.findViewById<View>(R.id.viewStepPlaybackDot)
        val lineTranslateBase = toolbarView.findViewById<View>(R.id.viewLineTranslateBase)
        val lineTranslateFill = toolbarView.findViewById<View>(R.id.viewLineTranslateFill)
        val linePlaybackBase = toolbarView.findViewById<View>(R.id.viewLinePlaybackBase)
        val linePlaybackFill = toolbarView.findViewById<View>(R.id.viewLinePlaybackFill)
        val progressBar = toolbarView.findViewById<ProgressBar>(R.id.progressTts)
        val timing = toolbarView.findViewById<TextView>(R.id.tvPlaybackTiming)

        when (state) {
            is TtsBannerUiState.Hidden -> {
                caption.text = ""
                if (lastStatusVisible) {
                    animateStatusStrip(toolbarView, statusContainer, divider, show = false)
                } else {
                    statusContainer.isVisible = false
                    divider.isVisible = false
                }
            }
            is TtsBannerUiState.Status -> {
                eyebrow.text = when {
                    state.isError -> "voice blocked"
                    state.playbackProgress != null -> "playback live"
                    state.activeStep == TtsWorkflowStep.TRANSLATE -> "ai voice"
                    state.activeStep == TtsWorkflowStep.AUDIO -> "voice pipeline"
                    else -> "player handoff"
                }
                title.text = state.title
                detail.text = state.detail
                caption.text = when {
                    state.isError -> "Retry available"
                    state.playbackProgress != null -> "${(state.playbackProgress * 100f).toInt().coerceIn(0, 100)}% played"
                    state.activeStep == TtsWorkflowStep.TRANSLATE -> "Step 1 of 3"
                    state.activeStep == TtsWorkflowStep.AUDIO -> "Step 2 of 3"
                    else -> "Step 3 of 3"
                }

                styleStepNode(toolbarView, stepTranslateDot, stepTranslateIndex, stepTranslate, TtsWorkflowStep.TRANSLATE, state)
                styleStepNode(toolbarView, stepAudioDot, stepAudioIndex, stepAudio, TtsWorkflowStep.AUDIO, state)
                styleStepNode(toolbarView, stepPlaybackDot, stepPlaybackIndex, stepPlayback, TtsWorkflowStep.PLAYBACK, state)

                styleTimelineSegment(
                    toolbarView = toolbarView,
                    baseView = lineTranslateBase,
                    fillView = lineTranslateFill,
                    progress = calculateLineProgress(state, beforeStep = TtsWorkflowStep.AUDIO)
                )
                styleTimelineSegment(
                    toolbarView = toolbarView,
                    baseView = linePlaybackBase,
                    fillView = linePlaybackFill,
                    progress = calculateLineProgress(state, beforeStep = TtsWorkflowStep.PLAYBACK)
                )

                if (state.playbackProgress != null) {
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = false
                    progressBar.progress = (state.playbackProgress * 1000f).toInt().coerceIn(0, 1000)
                    timing.isVisible = true
                    timing.text = "${formatTime(state.playbackPositionMs)} / ${formatTime(state.playbackDurationMs)}"
                } else {
                    progressBar.isVisible = true
                    progressBar.isIndeterminate = true
                    progressBar.progress = 0
                    timing.isVisible = false
                }

                val progressTint = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorPrimary)
                val trackTint = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#334155"))
                progressBar.progressTintList = android.content.res.ColorStateList.valueOf(progressTint)
                progressBar.indeterminateTintList = android.content.res.ColorStateList.valueOf(progressTint)
                progressBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(trackTint)

                if (state.isError) {
                    val errorColor = ContextCompat.getColor(toolbarView.context, android.R.color.holo_red_light)
                    title.setTextColor(errorColor)
                    eyebrow.setTextColor(adjustAlpha(errorColor, 0.92f))
                } else {
                    title.setTextColor(MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorOnSurface))
                    eyebrow.setTextColor(MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorOnSurfaceVariant))
                }

                if (!lastStatusVisible) {
                    animateStatusStrip(toolbarView, statusContainer, divider, show = true)
                } else {
                    statusContainer.isVisible = true
                    divider.isVisible = true
                }
            }
        }

        val status = state as? TtsBannerUiState.Status
        lastStatusVisible = state !is TtsBannerUiState.Hidden
        lastActiveStep = status?.activeStep
        lastErrorState = status?.isError == true
    }

    private fun styleStepNode(
        toolbarView: View,
        dotView: View,
        indexView: TextView,
        labelView: TextView,
        step: TtsWorkflowStep,
        state: TtsBannerUiState.Status
    ) {
        val accent = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorPrimary)
        val onAccent = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorOnPrimary)
        val onSurface = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorOnSurface)
        val idleSurface = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorSurfaceVariant)
        val outline = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorOutlineVariant)

        val isCompleted = step in state.completedSteps
        val isActive = step == state.activeStep
        val dotBackground = when {
            state.isError && isActive -> ContextCompat.getColor(toolbarView.context, android.R.color.holo_red_light)
            isActive -> accent
            isCompleted -> adjustAlpha(accent, 0.18f)
            else -> adjustAlpha(idleSurface, 0.94f)
        }
        val strokeColor = when {
            state.isError && isActive -> ContextCompat.getColor(toolbarView.context, android.R.color.holo_red_light)
            isActive || isCompleted -> accent
            else -> adjustAlpha(outline, 0.9f)
        }
        val labelColor = when {
            state.isError && isActive -> ContextCompat.getColor(toolbarView.context, android.R.color.holo_red_light)
            isActive -> onSurface
            isCompleted -> accent
            else -> adjustAlpha(onSurface, 0.7f)
        }
        val indexColor = when {
            state.isError && isActive -> Color.WHITE
            isActive -> onAccent
            isCompleted -> accent
            else -> adjustAlpha(onSurface, 0.65f)
        }

        dotView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = toolbarView.resources.displayMetrics.density * 14f
            setColor(dotBackground)
            setStroke((1.25f * toolbarView.resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        }
        labelView.setTextColor(labelColor)
        labelView.alpha = if (isActive || isCompleted) 1f else 0.9f
        indexView.text = when {
            state.isError && isActive -> "!"
            else -> when (step) {
                TtsWorkflowStep.TRANSLATE -> "1"
                TtsWorkflowStep.AUDIO -> "2"
                TtsWorkflowStep.PLAYBACK -> "3"
            }
        }
        indexView.setTextColor(indexColor)
        dotView.animate()
            .scaleX(if (isActive) 1.08f else 1f)
            .scaleY(if (isActive) 1.08f else 1f)
            .setDuration(180L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun styleTimelineSegment(
        toolbarView: View,
        baseView: View,
        fillView: View,
        progress: Float
    ) {
        val accent = MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorPrimary)
        val base = adjustAlpha(
            MaterialColors.getColor(toolbarView, com.google.android.material.R.attr.colorOutlineVariant),
            0.75f
        )

        val radius = toolbarView.resources.displayMetrics.density * 6f
        baseView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(base)
        }
        fillView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(accent)
        }
        fillView.pivotX = 0f
        fillView.pivotY = (fillView.height / 2f).coerceAtLeast(1f)
        fillView.animate()
            .scaleX(progress.coerceIn(0f, 1f))
            .setDuration(220L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun animateStatusStrip(
        toolbarView: View,
        statusContainer: ViewGroup,
        divider: View,
        show: Boolean
    ) {
        statusVisibilityAnimator?.cancel()

        val density = toolbarView.resources.displayMetrics.density
        val layoutParams = statusContainer.layoutParams
        val targetWidth = toolbarView.measuredWidth.takeIf { it > 0 } ?: toolbarView.width
        val widthMeasureSpec = if (targetWidth > 0) {
            View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
        } else {
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }

        if (show) {
            statusContainer.isVisible = true
            divider.isVisible = true
            statusContainer.alpha = 1f
            divider.alpha = 1f

            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            statusContainer.layoutParams = layoutParams
            statusContainer.measure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            val targetHeight = statusContainer.measuredHeight.coerceAtLeast((1 * density).toInt())
            layoutParams.height = 0
            statusContainer.layoutParams = layoutParams

            isStatusTransitionRunning = true
            statusVisibilityAnimator = ValueAnimator.ofInt(0, targetHeight).apply {
                duration = EXPANSION_ANIMATION_DURATION
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { animator ->
                    val animatedHeight = animator.animatedValue as Int
                    layoutParams.height = animatedHeight
                    statusContainer.layoutParams = layoutParams
                    divider.alpha = animator.animatedFraction.coerceIn(0f, 1f)
                    updatePosition()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isStatusTransitionRunning = false
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        statusContainer.layoutParams = layoutParams
                        statusContainer.alpha = 1f
                        divider.alpha = 1f
                        updatePosition()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        isStatusTransitionRunning = false
                    }
                })
                start()
            }
        } else {
            val startHeight = statusContainer.height.takeIf { it > 0 }
                ?: statusContainer.measuredHeight.takeIf { it > 0 }
                ?: return run {
                    statusContainer.isVisible = false
                    divider.isVisible = false
                }

            layoutParams.height = startHeight
            statusContainer.layoutParams = layoutParams

            isStatusTransitionRunning = true
            statusVisibilityAnimator = ValueAnimator.ofInt(startHeight, 0).apply {
                duration = EXPANSION_ANIMATION_DURATION
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { animator ->
                    val animatedHeight = animator.animatedValue as Int
                    layoutParams.height = animatedHeight
                    statusContainer.layoutParams = layoutParams
                    divider.alpha = (1f - animator.animatedFraction).coerceIn(0f, 1f)
                    updatePosition()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isStatusTransitionRunning = false
                        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        statusContainer.layoutParams = layoutParams
                        statusContainer.isVisible = false
                        divider.isVisible = false
                        divider.alpha = 1f
                        updatePosition()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        isStatusTransitionRunning = false
                    }
                })
                start()
            }
        }
    }

    private fun calculateLineProgress(state: TtsBannerUiState.Status, beforeStep: TtsWorkflowStep): Float {
        return when (beforeStep) {
            TtsWorkflowStep.AUDIO -> when {
                state.activeStep == TtsWorkflowStep.TRANSLATE -> 0.14f
                state.activeStep == TtsWorkflowStep.AUDIO -> 1f
                state.activeStep == TtsWorkflowStep.PLAYBACK -> 1f
                TtsWorkflowStep.AUDIO in state.completedSteps -> 1f
                TtsWorkflowStep.PLAYBACK in state.completedSteps -> 1f
                else -> 0f
            }
            TtsWorkflowStep.PLAYBACK -> when {
                TtsWorkflowStep.PLAYBACK in state.completedSteps -> 1f
                state.activeStep == TtsWorkflowStep.PLAYBACK -> state.playbackProgress ?: 0.56f
                state.activeStep == TtsWorkflowStep.AUDIO -> 0.22f
                else -> 0f
            }
            TtsWorkflowStep.TRANSLATE -> 0f
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun formatTime(durationMs: Int): String {
        if (durationMs <= 0) return "0:00"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}
