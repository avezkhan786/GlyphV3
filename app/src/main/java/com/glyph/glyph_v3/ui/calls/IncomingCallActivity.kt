package com.glyph.glyph_v3.ui.calls

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.glyph.glyph_v3.data.models.CallState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.widget.CheckBox
import android.widget.PopupWindow
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.repo.CallSignalingRepository
import com.glyph.glyph_v3.data.service.CallNotificationHelper
import com.glyph.glyph_v3.data.service.GlyphTelecomManager
import com.glyph.glyph_v3.data.webrtc.CallManager
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.Dispatchers
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.Locale
import android.annotation.SuppressLint
import kotlinx.coroutines.tasks.await
import android.app.PictureInPictureParams
import android.util.Rational
import androidx.annotation.RequiresApi

/**
 * Fullscreen incoming call screen matching WhatsApp.
 * Shows caller info, avatar, and Decline/Accept/Message buttons.
 * For video calls, shows local camera preview as background.
 */
class IncomingCallActivity : AppCompatActivity() {

    companion object {
        const val TAG = "IncomingCall"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_PHONE = "caller_phone"
        const val EXTRA_CALLER_AVATAR = "caller_avatar"
        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_AUTO_ACCEPT = "auto_accept"

        fun createIntent(
            context: Context,
            callId: String,
            callerName: String,
            callerPhone: String,
            callerAvatar: String,
            callType: CallType
        ): Intent = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALLER_NAME, callerName)
            putExtra(EXTRA_CALLER_PHONE, callerPhone)
            putExtra(EXTRA_CALLER_AVATAR, callerAvatar)
            putExtra(EXTRA_CALL_TYPE, callType.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private val callerPhoneExtraKeys = arrayOf(
        EXTRA_CALLER_PHONE,
        "phone_number",
        "caller_number",
        "phone",
        "contact_phone",
        "mobile"
    )

    private var callId = ""
    private var callerName = ""
    private var callerPhone = ""
    private var callerAvatar = ""
    private var callType = CallType.VOICE
    private var autoAccept = false

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    // View references
    private lateinit var rootLayout: FrameLayout
    private lateinit var tvCallerName: TextView
    private lateinit var tvCallerInfo: TextView
    private lateinit var ivCallerAvatar: ImageView
    private lateinit var ivCallTypeIcon: ImageView
    private lateinit var voiceFallbackBackground: ImageView
    private lateinit var btnDecline: FrameLayout
    private lateinit var btnAccept: FrameLayout
    private lateinit var btnMessage: FrameLayout
    private lateinit var messageButtonStack: FrameLayout
    private lateinit var ivMessageIcon: ImageView
    private lateinit var ivAcceptIcon: ImageView
    private lateinit var ivDeclineIcon: ImageView
    private lateinit var tvAcceptLabel: TextView
    private lateinit var tvDeclineLabel: TextView
    private lateinit var localVideoPreviewContainer: FrameLayout
    private lateinit var localVideoPreviewBg: SurfaceViewRenderer
    private lateinit var scrimOverlay: View
    private lateinit var videoHintContainer: LinearLayout
    private lateinit var ivVideoToggleHint: ImageView
    private lateinit var tvVideoHintText: TextView
    private lateinit var contentRoot: FrameLayout
    private lateinit var bottomActionsRow: LinearLayout
    private lateinit var acceptSwipeContainer: LinearLayout
    private lateinit var acceptButtonStack: FrameLayout
    private lateinit var swipeArrowContainer: LinearLayout
    private lateinit var declineSwipeContainer: LinearLayout
    private lateinit var declineButtonStack: FrameLayout

    private val acceptSwipeArrows = mutableListOf<ImageView>()

    private var acceptFloatAnimator: AnimatorSet? = null
    private var acceptIconAnimator: AnimatorSet? = null
    private var acceptArrowAnimatorSet: AnimatorSet? = null
    private var acceptTransitionAnimator: AnimatorSet? = null
    private var acceptReturnSpring: SpringAnimation? = null
    private var acceptSettleSpring: SpringAnimation? = null

    private var declineReturnSpring: SpringAnimation? = null
    private var declineSettleSpring: SpringAnimation? = null
    private var messageReturnSpring: SpringAnimation? = null
    private var messageSettleSpring: SpringAnimation? = null

    private var acceptIdleTranslation = 0f
    private var declineIdleTranslation = 0f
    private var messageIdleTranslation = 0f
    private var touchDownRawY = 0f
    private var messageTouchDownRawY = 0f
    private var swipeAccepted = false
    private var swipeDeclined = false
    private var swipeMessaged = false
    private var lastHapticProgress = 0f
    private val hapticStep = 0.25f

    private val floatRangePx by lazy(LazyThreadSafetyMode.NONE) { dpToPxF(8f) }
    private val swipeThresholdPx by lazy(LazyThreadSafetyMode.NONE) { dpToPxF(72f) }
    private val arrowTravelPx by lazy(LazyThreadSafetyMode.NONE) { dpToPxF(10f) }
    private val wiggleAngle = 10f
    private val baseArrowAlpha = floatArrayOf(0.22f, 0.34f, 0.48f, 0.62f)
    private var pipDragOffsetX = 0f
    private var pipDragOffsetY = 0f

    private var eglBase: EglBase? = null
    private var incomingLocalTrack: VideoTrack? = null
    private var acceptSignalSent = false
    private var activeLaunchStarted = false
    private var activeVideoUiVisible = false
    private var systemBottomInset = 0
    private var localPreviewCornerRadius = 0f
    private var lastObservedSoundState: CallState? = null

    private var activeRemoteVideoView: SurfaceViewRenderer? = null
    private var activeLocalVideoView: VideoTextureViewRenderer? = null
    private var activeRemoteTrack: VideoTrack? = null
    private var activeLocalTrack: VideoTrack? = null
    private var activeAutoHideJob: Job? = null

    // PiP state for accepted video call
    private var isInPipMode = false
    private var pipActiveTopBarView: View? = null
    private var pipActiveBottomBarView: View? = null
    private var pipActiveLocalContainerView: View? = null
    private var pipActiveChromeCallback: ((hidden: Boolean, immediate: Boolean) -> Unit)? = null
    private val pipLocalPreviewScale = 0.28f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            acceptCall()
        } else {
            Log.w(TAG, "Permissions not granted, cannot accept call")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CallLockScreenHelper.prepareActivityWindow(this)
        CallLockScreenHelper.pulseScreenWake(this, "$TAG:IncomingActivityWake")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        setContentView(R.layout.activity_incoming_call)

        readIntentExtras(intent)

        bindViews()
        applyWindowInsets()
        setupUI()
        setupAcceptInteraction()
        startRinging()
        applyEntranceAnimation()
        resolveCallerPhoneIfMissing()
        // Start background Firestore listener so we can react when the caller
        // cancels even if the activity is paused/stopped.
        CallManager.startRingingObservation(callId, callerName, callerAvatar, callType, this)
        observeCallState()

        maybeAutoAcceptFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CallLockScreenHelper.pulseScreenWake(this, "$TAG:IncomingActivityWake")
        readIntentExtras(intent)
        maybeAutoAcceptFromIntent()
    }

    private fun readIntentExtras(intent: Intent) {
        callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Unknown"
        callerPhone = readCallerPhone(intent)
        callerAvatar = intent.getStringExtra(EXTRA_CALLER_AVATAR) ?: ""
        callType = try {
            CallType.valueOf(intent.getStringExtra(EXTRA_CALL_TYPE) ?: "VOICE")
        } catch (_: Exception) { CallType.VOICE }
        autoAccept = intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)
    }

    private fun maybeAutoAcceptFromIntent() {
        if (!autoAccept || acceptSignalSent || isFinishing || isDestroyed) return

        window.decorView.post {
            if (!isFinishing && !isDestroyed && !acceptSignalSent) {
                requestPermissionsAndAccept()
            }
        }
    }

    /**
     * Keeps the receiver in a single surface from ringing into the active call UI.
     * Also closes the screen if the caller hangs up before we answer.
     */
    private fun observeCallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.callState.collectLatest { state ->
                    handleIncomingCallStateSound(state)

                    when (state) {
                        CallState.ACCEPTED -> {
                            stopRinging()
                            if (callType == CallType.VIDEO && !activeVideoUiVisible) {
                                showActiveVideoCallUi()
                            } else if (!acceptSignalSent) {
                                finish()
                            }
                        }
                        CallState.CONNECTED -> {
                            stopRinging()
                            if (callType == CallType.VIDEO && !activeVideoUiVisible) {
                                showActiveVideoCallUi()
                            }
                        }
                        CallState.ENDED, CallState.DECLINED, CallState.MISSED, CallState.NO_ANSWER -> {
                            stopRinging()
                            finish()
                        }
                        else -> { /* still ringing or null — stay open */ }
                    }
                }
            }
        }
    }

    private fun handleIncomingCallStateSound(state: CallState?) {
        if (state == lastObservedSoundState) {
            return
        }
        lastObservedSoundState = state

        when (state) {
            CallState.CONNECTED -> {
                CallSoundPlayer.stop()
                CallSoundPlayer.playConnected(this)
            }
            CallState.ENDED -> {
                CallSoundPlayer.stop()
                CallSoundPlayer.playCallEnded(this)
            }
            CallState.DECLINED,
            CallState.MISSED,
            CallState.NO_ANSWER,
            null -> {
                CallSoundPlayer.stop()
            }
            else -> Unit
        }
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootLayout)
        tvCallerName = findViewById(R.id.tvCallerName)
        tvCallerInfo = findViewById(R.id.tvCallerInfo)
        ivCallerAvatar = findViewById(R.id.ivCallerAvatar)
        ivCallTypeIcon = findViewById(R.id.ivCallTypeIcon)
        voiceFallbackBackground = findViewById(R.id.voiceFallbackBackground)
        btnDecline = findViewById(R.id.btnDecline)
        btnAccept = findViewById(R.id.btnAccept)
        btnMessage = findViewById(R.id.btnMessage)
        messageButtonStack = findViewById(R.id.messageButtonStack)
        ivMessageIcon = findViewById(R.id.ivMessageIcon)
        ivAcceptIcon = findViewById(R.id.ivAcceptIcon)
        ivDeclineIcon = findViewById(R.id.ivDeclineIcon)
        tvAcceptLabel = findViewById(R.id.tvAcceptLabel)
        tvDeclineLabel = findViewById(R.id.tvDeclineLabel)
        localVideoPreviewContainer = findViewById(R.id.localVideoPreviewContainer)
        localVideoPreviewBg = findViewById(R.id.localVideoPreviewBg)
        scrimOverlay = findViewById(R.id.scrimOverlay)
        videoHintContainer = findViewById(R.id.videoHintContainer)
        ivVideoToggleHint = findViewById(R.id.ivVideoToggleHint)
        tvVideoHintText = findViewById(R.id.tvVideoHintText)
        contentRoot = findViewById(R.id.incomingCallContentRoot)
        bottomActionsRow = findViewById(R.id.incomingCallBottomActions)
        acceptSwipeContainer = findViewById(R.id.acceptSwipeContainer)
        acceptButtonStack = findViewById(R.id.acceptButtonStack)
        swipeArrowContainer = findViewById(R.id.swipeArrowContainer)
        declineSwipeContainer = findViewById(R.id.declineSwipeContainer)
        declineButtonStack = findViewById(R.id.declineButtonStack)

        acceptSwipeArrows.clear()
        acceptSwipeArrows += findViewById<ImageView>(R.id.ivSwipeArrow1)
        acceptSwipeArrows += findViewById<ImageView>(R.id.ivSwipeArrow2)
        acceptSwipeArrows += findViewById<ImageView>(R.id.ivSwipeArrow3)
        acceptSwipeArrows += findViewById<ImageView>(R.id.ivSwipeArrow4)

        localVideoPreviewContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, localPreviewCornerRadius)
            }
        }
        localVideoPreviewContainer.clipToOutline = true
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            systemBottomInset = bars.bottom
            contentRoot.updatePadding(
                left = contentRoot.paddingLeft,
                top = bars.top + dpToPx(20),
                right = contentRoot.paddingRight,
                bottom = contentRoot.paddingBottom
            )
            bottomActionsRow.updatePadding(
                left = bottomActionsRow.paddingLeft,
                top = bottomActionsRow.paddingTop,
                right = bottomActionsRow.paddingRight,
                bottom = bars.bottom + dpToPx(16)
            )
            insets
        }
    }

    private fun setupUI() {
        tvCallerName.text = callerName
        val resolvedCallerInfo = formatCallerPhoneForDisplay(callerPhone).ifBlank { "Unknown number" }
        tvCallerInfo.text = resolvedCallerInfo
        tvCallerInfo.visibility = View.VISIBLE
        ivCallTypeIcon.visibility = View.VISIBLE
        ivCallTypeIcon.setImageResource(R.drawable.ic_phone)

        if (callType == CallType.VIDEO) {
            ivAcceptIcon.setImageResource(R.drawable.ic_videocam)
            tvAcceptLabel.text = "Swipe up to accept"
            videoHintContainer.visibility = View.VISIBLE
            voiceFallbackBackground.alpha = 0f
            setupVideoPreview()
            observeVideoHintState()
            videoHintContainer.setOnClickListener {
                CallManager.toggleVideo()
            }
        } else {
            ivAcceptIcon.setImageResource(R.drawable.ic_call)
            tvAcceptLabel.text = "Swipe up to accept"
        }

        if (callerAvatar.isNotEmpty()) {
            Glide.with(this)
                .load(callerAvatar)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .into(ivCallerAvatar)
        }

        btnDecline.setOnClickListener { declineCall() }
        // Message click is handled via swipe — setOnClickListener intentionally left empty
        // so accidental taps without a proper upward swipe do nothing.
    }

    private fun resolveCallerPhoneIfMissing() {
        if (callerPhone.isNotBlank() || callId.isBlank()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val callData = runCatching { CallSignalingRepository.getCallData(callId) }.getOrNull()
            val resolvedPhone = callData?.callerPhone
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: fetchUserPhone(callData?.callerId.orEmpty())

            if (resolvedPhone.isBlank()) return@launch

            callerPhone = resolvedPhone
            launch(Dispatchers.Main) {
                tvCallerInfo.text = formatCallerPhoneForDisplay(resolvedPhone)
            }
        }
    }

    private suspend fun fetchUserPhone(userId: String): String {
        if (userId.isBlank()) return ""

        return try {
            val snapshot = FirebaseFirestore.getInstance()
                .collection("users")
                .document(userId)
                .get()
                .await()

            listOf("phoneNumber", "phone", "mobile")
                .asSequence()
                .mapNotNull { key -> snapshot.getString(key) }
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve caller phone from Firestore", e)
            ""
        }
    }

    private fun readCallerPhone(intent: Intent): String {
        val extras = intent.extras
        val phone = callerPhoneExtraKeys
            .asSequence()
            .mapNotNull { key -> extras?.getString(key) }
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }

        return phone ?: ""
    }

    private fun setupAcceptInteraction() {
        btnAccept.setOnTouchListener { _, event ->
            handleAcceptSwipe(event)
        }

        btnDecline.setOnTouchListener { _, event ->
            handleDeclineSwipe(event)
        }

        btnMessage.setOnTouchListener { _, event ->
            handleMessageSwipe(event)
        }

        acceptReturnSpring = createTranslationSpring(acceptButtonStack, SpringForce.STIFFNESS_MEDIUM)
        acceptSettleSpring = createTranslationSpring(acceptButtonStack, SpringForce.STIFFNESS_LOW).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }

        declineReturnSpring = createTranslationSpring(declineButtonStack, SpringForce.STIFFNESS_MEDIUM)
        declineSettleSpring = createTranslationSpring(declineButtonStack, SpringForce.STIFFNESS_LOW).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }

        messageReturnSpring = createTranslationSpring(messageButtonStack, SpringForce.STIFFNESS_MEDIUM)
        messageSettleSpring = createTranslationSpring(messageButtonStack, SpringForce.STIFFNESS_LOW).apply {
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
    }

    private fun handleDeclineSwipe(event: MotionEvent): Boolean {
        if (swipeDeclined || swipeAccepted) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawY = event.rawY
                pauseCallAnimations()
                declineReturnSpring?.cancel()
                declineSettleSpring?.cancel()
                declineIdleTranslation = declineButtonStack.translationY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - touchDownRawY
                val dragTranslation = (declineIdleTranslation + deltaY).coerceAtMost(0f).coerceAtLeast(-swipeThresholdPx * 1.25f)
                declineButtonStack.translationY = dragTranslation

                val progress = (-dragTranslation / swipeThresholdPx).coerceIn(0f, 1f)
                
                // Sequential haptic clicks as user swipes
                if (progress >= lastHapticProgress + hapticStep) {
                    btnDecline.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    lastHapticProgress = (progress / hapticStep).toInt() * hapticStep
                }

                val scale = 1f + (progress * 0.08f)
                btnDecline.scaleX = scale
                btnDecline.scaleY = scale
                ivDeclineIcon.rotation = 135f + (progress * -18f)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val reachedThreshold = declineButtonStack.translationY <= -swipeThresholdPx
                if (reachedThreshold) {
                    completeDeclineSwipe()
                } else {
                    springDeclineControlBack()
                }
                return true
            }
        }

        return false
    }

    private fun completeDeclineSwipe() {
        swipeDeclined = true
        stopCallAnimations(resetVisualState = false)
        btnDecline.isEnabled = false

        declineSettleSpring?.apply {
            setStartValue(declineButtonStack.translationY)
            animateToFinalPosition(-swipeThresholdPx * 1.35f)
        }

        declineButtonStack.animate()
            .alpha(0f)
            .setDuration(140L)
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                declineCall()
            }
            .start()
    }

    private fun springDeclineControlBack() {
        btnDecline.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
        declineReturnSpring?.apply {
            setStartValue(declineButtonStack.translationY)
            animateToFinalPosition(0f)
        }
        resumeCallAnimations()
    }

    private fun handleMessageSwipe(event: MotionEvent): Boolean {
        if (swipeMessaged || swipeAccepted || swipeDeclined) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                messageTouchDownRawY = event.rawY
                messageReturnSpring?.cancel()
                messageSettleSpring?.cancel()
                messageIdleTranslation = messageButtonStack.translationY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - messageTouchDownRawY
                val dragTranslation = (messageIdleTranslation + deltaY)
                    .coerceAtMost(0f)
                    .coerceAtLeast(-swipeThresholdPx * 1.25f)
                messageButtonStack.translationY = dragTranslation

                val progress = (-dragTranslation / swipeThresholdPx).coerceIn(0f, 1f)

                if (progress >= lastHapticProgress + hapticStep) {
                    btnMessage.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    lastHapticProgress = (progress / hapticStep).toInt() * hapticStep
                }

                val scale = 1f + (progress * 0.08f)
                btnMessage.scaleX = scale
                btnMessage.scaleY = scale
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastHapticProgress = 0f
                val reachedThreshold = messageButtonStack.translationY <= -swipeThresholdPx
                if (reachedThreshold) {
                    completeMessageSwipe()
                } else {
                    springMessageControlBack()
                }
                return true
            }
        }

        return false
    }

    private fun completeMessageSwipe() {
        swipeMessaged = true
        btnMessage.isEnabled = false
        // Cancel any in-flight spring so it doesn't fight the animator
        messageReturnSpring?.cancel()
        messageSettleSpring?.cancel()
        messageButtonStack.animate().cancel()
        btnMessage.animate().cancel()

        // Fly up and fade out as a single animator — no spring conflict
        messageButtonStack.animate()
            .translationY(-swipeThresholdPx * 1.35f)
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                // Reset fully before showing the dialog
                messageButtonStack.translationY = 0f
                messageButtonStack.alpha = 1f
                btnMessage.scaleX = 1f
                btnMessage.scaleY = 1f
                btnMessage.isEnabled = true
                swipeMessaged = false
                showQuickReplyDialog()
            }
            .start()
    }

    private fun springMessageControlBack() {
        messageReturnSpring?.cancel()
        messageSettleSpring?.cancel()
        messageButtonStack.animate().cancel()
        btnMessage.animate().cancel()

        // Snap scale back instantly, then spring the position
        btnMessage.scaleX = 1f
        btnMessage.scaleY = 1f
        messageReturnSpring?.apply {
            setStartValue(messageButtonStack.translationY)
            animateToFinalPosition(0f)
        }
    }

    private fun handleAcceptSwipe(event: MotionEvent): Boolean {
        if (swipeAccepted || swipeDeclined) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawY = event.rawY
                pauseCallAnimations()
                acceptReturnSpring?.cancel()
                acceptSettleSpring?.cancel()
                acceptIdleTranslation = acceptButtonStack.translationY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaY = event.rawY - touchDownRawY
                val dragTranslation = (acceptIdleTranslation + deltaY).coerceAtMost(floatRangePx).coerceAtLeast(-swipeThresholdPx * 1.25f)
                acceptButtonStack.translationY = dragTranslation

                val progress = (-dragTranslation / swipeThresholdPx).coerceIn(0f, 1f)

                // Sequential haptic clicks as user swipes
                if (progress >= lastHapticProgress + hapticStep) {
                    btnAccept.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    lastHapticProgress = (progress / hapticStep).toInt() * hapticStep
                }

                val scale = 1f + (progress * 0.08f)
                btnAccept.scaleX = scale
                btnAccept.scaleY = scale
                ivAcceptIcon.rotation = progress * -18f
                updateArrowProgress(acceptSwipeArrows, progress)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val reachedThreshold = acceptButtonStack.translationY <= -swipeThresholdPx
                if (reachedThreshold) {
                    completeAcceptSwipe()
                } else {
                    springAcceptControlBack()
                }
                return true
            }
        }

        return false
    }

    private fun completeAcceptSwipe() {
        swipeAccepted = true
        stopCallAnimations(resetVisualState = false)
        btnAccept.isEnabled = false

        acceptSettleSpring?.apply {
            setStartValue(acceptButtonStack.translationY)
            animateToFinalPosition(-swipeThresholdPx * 1.35f)
        }

        swipeArrowContainer.animate()
            .alpha(0f)
            .setDuration(120L)
            .setInterpolator(LinearInterpolator())
            .start()

        acceptButtonStack.animate()
            .alpha(0f)
            .setDuration(140L)
            .setInterpolator(LinearInterpolator())
            .withEndAction {
                requestPermissionsAndAccept()
            }
            .start()
    }

    private fun springAcceptControlBack() {
        btnAccept.animate().scaleX(1f).scaleY(1f).setDuration(150L).start()
        acceptReturnSpring?.apply {
            setStartValue(acceptButtonStack.translationY)
            animateToFinalPosition(0f)
        }
        resetArrowState(acceptSwipeArrows)
        resumeCallAnimations()
    }

    private fun startCallAnimations() {
        if (swipeAccepted || swipeDeclined) return

        // Accept animations
        if (acceptFloatAnimator == null) {
            val drift = ObjectAnimator.ofFloat(
                acceptButtonStack,
                View.TRANSLATION_Y,
                0f,
                -floatRangePx,
                0f,
                floatRangePx * 0.55f,
                0f
            ).apply {
                duration = 2200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
            acceptFloatAnimator = AnimatorSet().apply {
                playTogether(drift)
            }
        }
        if (acceptIconAnimator == null) {
            val wiggle = ObjectAnimator.ofFloat(
                ivAcceptIcon,
                View.ROTATION,
                0f,
                -wiggleAngle,
                wiggleAngle * 0.6f,
                -wiggleAngle * 0.45f,
                0f
            ).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
            acceptIconAnimator = AnimatorSet().apply {
                playTogether(wiggle)
            }
        }
        if (acceptArrowAnimatorSet == null) {
            acceptArrowAnimatorSet = buildArrowAnimator(acceptSwipeArrows)
        }

        if (acceptFloatAnimator?.isStarted != true) acceptFloatAnimator?.start()
        if (acceptIconAnimator?.isStarted != true) acceptIconAnimator?.start()
        if (acceptArrowAnimatorSet?.isStarted != true) acceptArrowAnimatorSet?.start()
    }

    private fun pauseCallAnimations() {
        acceptFloatAnimator?.pause()
        acceptIconAnimator?.pause()
        acceptArrowAnimatorSet?.pause()
    }

    private fun resumeCallAnimations() {
        if (swipeAccepted || swipeDeclined) return
        btnAccept.post {
            acceptFloatAnimator?.resume()
            acceptIconAnimator?.resume()
            acceptArrowAnimatorSet?.resume()
            
            // Safety: if they were canceled, restart them
            if (acceptFloatAnimator?.isStarted != true) {
                stopCallAnimations()
                startCallAnimations()
            }
        }
    }

    private fun stopCallAnimations(resetVisualState: Boolean = true) {
        acceptFloatAnimator?.cancel()
        acceptIconAnimator?.cancel()
        acceptArrowAnimatorSet?.cancel()
        acceptTransitionAnimator?.cancel()
        
        acceptFloatAnimator = null
        acceptIconAnimator = null
        acceptArrowAnimatorSet = null
        acceptTransitionAnimator = null
        
        acceptReturnSpring?.cancel()
        acceptSettleSpring?.cancel()
        declineReturnSpring?.cancel()
        declineSettleSpring?.cancel()
        messageReturnSpring?.cancel()
        messageSettleSpring?.cancel()
        
        btnAccept.animate().cancel()
        swipeArrowContainer.animate().cancel()
        acceptButtonStack.animate().cancel()
        declineButtonStack.animate().cancel()
        btnDecline.animate().cancel()
        messageButtonStack.animate().cancel()
        btnMessage.animate().cancel()

        if (resetVisualState) {
            acceptButtonStack.translationY = 0f
            btnAccept.alpha = 1f
            acceptButtonStack.alpha = 1f
            swipeArrowContainer.alpha = 1f
            btnAccept.scaleX = 1f
            btnAccept.scaleY = 1f
            ivAcceptIcon.rotation = 0f
            
            declineButtonStack.translationY = 0f
            btnDecline.alpha = 1f
            declineButtonStack.alpha = 1f
            btnDecline.scaleX = 1f
            btnDecline.scaleY = 1f
            ivDeclineIcon.rotation = 135f

            messageButtonStack.translationY = 0f
            messageButtonStack.alpha = 1f
            btnMessage.scaleX = 1f
            btnMessage.scaleY = 1f
            
            resetArrowState(acceptSwipeArrows)
        }
    }

    private fun buildArrowAnimator(arrows: List<ImageView>): AnimatorSet {
        val waveAnimators = arrows.mapIndexed { index, arrow ->
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(arrow, View.ALPHA, baseArrowAlpha[index], 1f, 0.15f).apply {
                        duration = 750L
                        startDelay = index * 140L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        interpolator = LinearInterpolator()
                    },
                    ObjectAnimator.ofFloat(arrow, View.TRANSLATION_Y, 0f, -arrowTravelPx, -arrowTravelPx * 1.3f).apply {
                        duration = 750L
                        startDelay = index * 140L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        interpolator = LinearInterpolator()
                    },
                    ObjectAnimator.ofFloat(arrow, View.SCALE_X, 0.96f, 1.08f, 0.92f).apply {
                        duration = 750L
                        startDelay = index * 140L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        interpolator = LinearInterpolator()
                    },
                    ObjectAnimator.ofFloat(arrow, View.SCALE_Y, 0.96f, 1.08f, 0.92f).apply {
                        duration = 750L
                        startDelay = index * 140L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.RESTART
                        interpolator = LinearInterpolator()
                    }
                )
            }
        }
        return AnimatorSet().apply {
            playTogether(waveAnimators)
        }
    }

    private fun updateArrowProgress(arrows: List<ImageView>, progress: Float) {
        arrows.forEachIndexed { index, arrow ->
            val activeThreshold = (index + 1) / arrows.size.toFloat()
            val activation = ((progress - (activeThreshold - 0.25f)) / 0.25f).coerceIn(0f, 1f)
            arrow.alpha = baseArrowAlpha[index] + ((1f - baseArrowAlpha[index]) * activation)
            arrow.translationY = -arrowTravelPx * (1f + activation)
            val scale = 1f + (activation * 0.12f)
            arrow.scaleX = scale
            arrow.scaleY = scale
        }
    }

    private fun resetArrowState(arrows: List<ImageView>) {
        arrows.forEachIndexed { index, arrow ->
            arrow.alpha = baseArrowAlpha[index]
            arrow.translationY = 0f
            arrow.scaleX = 1f
            arrow.scaleY = 1f
        }
    }

    private fun createTranslationSpring(target: View, stiffness: Float): SpringAnimation {
        return SpringAnimation(target, SpringAnimation.TRANSLATION_Y).apply {
            spring = SpringForce(0f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
                this.stiffness = stiffness
            }
        }
    }

    private fun setupVideoPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            CallManager.prepareIncomingVideoPreview(this, callId)
            val sharedContext = CallManager.rtcClient?.eglBaseContext

            eglBase = if (sharedContext != null) {
                EglBase.create(sharedContext)
            } else {
                EglBase.create()
            }

            localVideoPreviewBg.init(eglBase!!.eglBaseContext, null)
            localVideoPreviewBg.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            localVideoPreviewBg.setEnableHardwareScaler(true)
            localVideoPreviewContainer.visibility = View.VISIBLE
            scrimOverlay.alpha = 0.75f

            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val client = CallManager.rtcClient
                    if (client != null) {
                        launch {
                            client.isFrontCameraFlow.collectLatest { isFront ->
                                localVideoPreviewBg.setMirror(isFront)
                            }
                        }
                        client.localVideoTrack.collectLatest { track ->
                            incomingLocalTrack?.removeSink(localVideoPreviewBg)
                            incomingLocalTrack = track
                            track?.addSink(localVideoPreviewBg)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup video preview", e)
        }
    }

    private fun observeVideoHintState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.isVideoEnabled.collectLatest { videoEnabled ->
                    ivVideoToggleHint.setImageResource(
                        if (videoEnabled) R.drawable.ic_videocam_off else R.drawable.ic_videocam
                    )
                    tvVideoHintText.text = if (videoEnabled) {
                        "Turn off your video"
                    } else {
                        "Turn on your video"
                    }
                    localVideoPreviewContainer.visibility = if (videoEnabled) View.VISIBLE else View.INVISIBLE
                    voiceFallbackBackground.alpha = if (videoEnabled) 0f else 1f
                    scrimOverlay.alpha = if (videoEnabled) 0.75f else 0.9f
                }
            }
        }
    }

    private fun formatCallerPhoneForDisplay(rawPhone: String): String {
        val e164Phone = PhoneNumberUtil.formatAsE164(
            rawPhone = rawPhone,
            regionIso = resolvePhoneRegionIso(),
            fallbackE164Phone = FirebaseAuth.getInstance().currentUser?.phoneNumber
        )

        return PhoneNumberUtil.formatForDisplay(e164Phone.ifBlank { rawPhone })
    }

    private fun resolvePhoneRegionIso(): String {
        val telephonyManager = getSystemService(TelephonyManager::class.java)
        val regionCandidates = listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )

        return regionCandidates
            .asSequence()
            .map { it.orEmpty().trim() }
            .firstOrNull { it.length == 2 }
            ?.uppercase(Locale.US)
            ?: "US"
    }

    private fun requestPermissionsAndAccept() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (callType == CallType.VIDEO &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            validateAndAcceptCall()
        }
    }

    private fun validateAndAcceptCall() {
        lifecycleScope.launch {
            val stillRinging = CallSignalingRepository.isCallStillRinging(callId)
            if (!stillRinging) {
                stopRinging()
                CallManager.stopRingingObservation()
                CallNotificationHelper.cancelIncomingNotification(this@IncomingCallActivity)
                finish()
                return@launch
            }

            val callData = CallSignalingRepository.getCallData(callId)
            val otherUserId = callData?.callerId?.takeIf { it.isNotBlank() }
            if (otherUserId != null && com.glyph.glyph_v3.data.repo.BlockRepository.fetchBlockStatus(otherUserId).isBlocked) {
                runCatching {
                    CallSignalingRepository.updateCallStatus(callId, "declined")
                }
                stopRinging()
                CallManager.stopRingingObservation()
                CallNotificationHelper.cancelIncomingNotification(this@IncomingCallActivity)
                finish()
                return@launch
            }

            acceptCall()
        }
    }

    private fun acceptCall() {
        if (callType == CallType.VIDEO && localVideoPreviewContainer.visibility == View.VISIBLE) {
            startVideoAcceptTransition()
            return
        }

        markIncomingCallAccepted()
        if (callType == CallType.VIDEO) {
            showActiveVideoCallUi()
        } else {
            launchActiveCallScreen()
        }
    }

    private fun markIncomingCallAccepted() {
        if (acceptSignalSent) return

        stopCallAnimations()
        stopRinging()
        CallManager.stopRingingObservation()
        CallNotificationHelper.cancelIncomingNotification(this)
        CallManager.acceptIncomingCall(this, callId, callType)
        GlyphTelecomManager.markCallAnswered(callId)
        if (callType == CallType.VIDEO) {
            CallManager.reapplyAudioRoute()
            window.decorView.post {
                if (!isFinishing && !isDestroyed) {
                    CallManager.reapplyAudioRoute()
                }
            }
        }
        acceptSignalSent = true
    }

    private fun launchActiveCallScreen() {
        if (activeLaunchStarted) return
        activeLaunchStarted = true

        val intent = Intent(this, ActiveCallActivity::class.java).apply {
            putExtra(ActiveCallActivity.EXTRA_CALL_ID, callId)
            putExtra(ActiveCallActivity.EXTRA_CALL_TYPE, callType.name)
            putExtra(ActiveCallActivity.EXTRA_CONTACT_NAME, callerName)
            putExtra(ActiveCallActivity.EXTRA_CONTACT_AVATAR, callerAvatar)
        }
        startActivity(intent)
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showActiveVideoCallUi() {
        if (activeVideoUiVisible || isFinishing || isDestroyed) return

        activeVideoUiVisible = true
        activeLaunchStarted = true
        stopCallAnimations()
        stopRinging()

        try {
            incomingLocalTrack?.removeSink(localVideoPreviewBg)
        } catch (_: Exception) {}
        incomingLocalTrack = null
        try {
            localVideoPreviewBg.release()
        } catch (_: Exception) {}
        eglBase?.release()
        eglBase = null

        setContentView(R.layout.activity_video_call)

        val rootVideoCall = findViewById<View>(R.id.rootVideoCall)
        val videoTapLayer = findViewById<View>(R.id.videoTapLayer)
        val topBar = findViewById<View>(R.id.topBar)
        val bottomControlBar = findViewById<View>(R.id.bottomControlBar)
        val tvName = findViewById<TextView>(R.id.tvContactName)
        val tvTimer = findViewById<TextView>(R.id.tvCallTimer)
        val btnMore = findViewById<FrameLayout>(R.id.btnMore)
        val btnCamera = findViewById<FrameLayout>(R.id.btnCamera)
        val btnSpeaker = findViewById<FrameLayout>(R.id.btnSpeaker)
        val btnMute = findViewById<FrameLayout>(R.id.btnMute)
        val btnEnd = findViewById<FrameLayout>(R.id.btnEndCall)
        val btnMinimize = findViewById<ImageView>(R.id.btnMinimize)
        val btnSwitchCameraPreview = findViewById<ImageView>(R.id.btnSwitchCameraPreview)
        val localContainer = findViewById<FrameLayout>(R.id.localVideoContainer)
        val ivSpeaker = findViewById<ImageView>(R.id.ivSpeakerIcon)
        val ivMute = findViewById<ImageView>(R.id.ivMuteIcon)
        val ivCamera = findViewById<ImageView>(R.id.ivCameraIcon)
        val ivHdBadge = findViewById<ImageView>(R.id.ivHdBadge)
        var controlsHidden = false

        // Store view refs for PiP visibility management
        pipActiveTopBarView = topBar
        pipActiveBottomBarView = bottomControlBar
        pipActiveLocalContainerView = localContainer

        fun animateActiveChrome(hidden: Boolean, immediate: Boolean = false) {
            rootVideoCall.post {
                if (rootVideoCall.width == 0 || rootVideoCall.height == 0) return@post

                val topMargin = (topBar.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: 0
                val rightMargin = (localContainer.layoutParams as? FrameLayout.LayoutParams)?.rightMargin ?: dpToPx(12)
                val shownBottomMargin = (bottomControlBar.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin
                    ?: (systemBottomInset + dpToPx(112))

                val topTarget = if (hidden) -(topBar.height + topMargin + dpToPx(18)).toFloat() else 0f
                val bottomTarget = if (hidden) (bottomControlBar.height + systemBottomInset + dpToPx(28)).toFloat() else 0f
                    val targetScale = if (hidden) {
                        if (isInPipMode) pipLocalPreviewScale else 0.45f
                    } else {
                        1f
                    }
                        val edgeMargin = if (isInPipMode && hidden) dpToPxF(5f) else dpToPxF(10f)
                        val hiddenBottomInset = if (isInPipMode && hidden) {
                            edgeMargin
                        } else {
                            systemBottomInset.toFloat() + edgeMargin
                        }

                localContainer.pivotX = localContainer.width.toFloat()
                localContainer.pivotY = localContainer.height.toFloat()

                val targetX = if (hidden) {
                    (rootVideoCall.width - localContainer.width - edgeMargin).toFloat()
                } else {
                    (rootVideoCall.width - localContainer.width - rightMargin).toFloat()
                }

                val shownTargetY = (rootVideoCall.height - bottomControlBar.height - shownBottomMargin - localContainer.height - dpToPxF(10f)).coerceAtLeast(dpToPxF(20f))
                val targetY = if (hidden) {
                    (rootVideoCall.height - localContainer.height - hiddenBottomInset).toFloat()
                } else {
                    shownTargetY
                }
                val pipButtonAlpha = if (hidden) 0f else 1f

                if (immediate) {
                    topBar.translationY = topTarget
                    topBar.alpha = if (hidden) 0f else 1f
                    bottomControlBar.translationY = bottomTarget
                    bottomControlBar.alpha = if (hidden) 0f else 1f
                    localContainer.x = targetX
                    localContainer.y = targetY
                    localContainer.scaleX = targetScale
                    localContainer.scaleY = targetScale
                    btnSwitchCameraPreview.alpha = pipButtonAlpha
                    btnSwitchCameraPreview.isEnabled = !hidden
                    return@post
                }

                topBar.animate()
                    .translationY(topTarget)
                    .alpha(if (hidden) 0f else 1f)
                    .setDuration(260)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                bottomControlBar.animate()
                    .translationY(bottomTarget)
                    .alpha(if (hidden) 0f else 1f)
                    .setDuration(260)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                localContainer.animate()
                    .x(targetX)
                    .y(targetY)
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(280)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                btnSwitchCameraPreview.animate()
                    .alpha(pipButtonAlpha)
                    .setDuration(180)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withStartAction { btnSwitchCameraPreview.isEnabled = !hidden }
                    .start()
            }
        }

        // Capture reference so onPictureInPictureModeChanged can trigger chrome animation
        pipActiveChromeCallback = { hidden, immediate -> animateActiveChrome(hidden, immediate) }

        ViewCompat.setOnApplyWindowInsetsListener(rootVideoCall) { _, insets ->
            // Don't re-layout while in PiP — the window is tiny and insets are wrong
            if (isInPipMode) return@setOnApplyWindowInsetsListener insets
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            systemBottomInset = bars.bottom
            (topBar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.topMargin = bars.top + dpToPx(8)
                topBar.layoutParams = params
            }
            topBar.updatePadding(
                left = dpToPx(18) + bars.left,
                top = dpToPx(10),
                right = dpToPx(18) + bars.right,
                bottom = dpToPx(8)
            )

            (bottomControlBar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = bars.bottom + dpToPx(18)
                bottomControlBar.layoutParams = params
            }

            (localContainer.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.rightMargin = dpToPx(12)
                params.bottomMargin = bars.bottom + dpToPx(112)
                localContainer.layoutParams = params
            }
            animateActiveChrome(controlsHidden, immediate = true)
            insets
        }
        ViewCompat.requestApplyInsets(rootVideoCall)

        activeRemoteVideoView = findViewById<SurfaceViewRenderer>(R.id.remoteVideoView)
        activeLocalVideoView = findViewById<VideoTextureViewRenderer>(R.id.localVideoView)

        val activePipCornerRadius = dpToPxF(22f)
        localContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, activePipCornerRadius)
            }
        }
        localContainer.clipToOutline = true

        val sharedContext = CallManager.rtcClient?.eglBaseContext
        eglBase = if (sharedContext != null) EglBase.create(sharedContext) else EglBase.create()
        val eglContext = sharedContext ?: eglBase!!.eglBaseContext

        activeRemoteVideoView?.init(eglContext, null)
        activeRemoteVideoView?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        activeRemoteVideoView?.setEnableHardwareScaler(true)
        activeRemoteVideoView?.setMirror(false)

        activeLocalVideoView?.init(
            eglContext,
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit

                override fun onFrameResolutionChanged(
                    videoWidth: Int,
                    videoHeight: Int,
                    rotation: Int
                ) = Unit
            }
        )
        activeLocalVideoView?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val client = CallManager.rtcClient
                if (client != null) {
                    launch {
                        client.isFrontCameraFlow.collectLatest { isFront ->
                            activeLocalVideoView?.setMirror(isFront)
                        }
                    }
                    client.localVideoTrack.collectLatest { track ->
                        activeLocalTrack?.removeSink(activeLocalVideoView!!)
                        activeLocalTrack = track
                        track?.addSink(activeLocalVideoView!!)
                    }
                }
            }
        }

        val callData = CallManager.callData.value
        tvName.text = callerName.ifBlank {
            callData?.callerName ?: callData?.receiverName ?: ""
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.callDurationSeconds.collectLatest { secs ->
                    tvTimer.text = "%d:%02d".format(secs / 60, secs % 60)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.rtcClient?.remoteVideoTrack?.collectLatest { track ->
                    activeRemoteVideoView?.let { renderer ->
                        activeRemoteTrack?.removeSink(renderer)
                        activeRemoteTrack = track
                        track?.addSink(renderer)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.rtcClient?.localVideoTrack?.collectLatest { track ->
                    activeLocalVideoView?.let { renderer ->
                        activeLocalTrack?.removeSink(renderer)
                        activeLocalTrack = track
                        track?.addSink(renderer)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.isMicMuted.collectLatest { muted ->
                    btnMute.setBackgroundResource(
                        if (muted) R.drawable.bg_call_control_active else R.drawable.bg_call_control_button
                    )
                    if (muted) {
                        ivMute.setImageResource(R.drawable.ic_mic_off)
                        ivMute.setColorFilter(0xFFFF0000.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        ivMute.setImageResource(R.drawable.ic_mic_custom)
                        ivMute.setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.isSpeakerOn.collectLatest { speaker ->
                    btnSpeaker.setBackgroundResource(
                        if (speaker) R.drawable.bg_call_control_active else R.drawable.bg_call_control_button
                    )
                    ivSpeaker.setColorFilter(if (speaker) 0xFF333333.toInt() else 0xFFFFFFFF.toInt())
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.isVideoEnabled.collectLatest { videoOn ->
                    btnCamera.setBackgroundResource(
                        if (videoOn) R.drawable.bg_call_control_active else R.drawable.bg_call_control_button
                    )
                    ivCamera.setColorFilter(if (videoOn) 0xFF333333.toInt() else 0xFFFFFFFF.toInt())
                    localContainer.visibility = when {
                        !videoOn -> View.GONE
                        else -> View.VISIBLE
                    }
                }
            }
        }

        // HD badge: shown on own preview when HD mode is active
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.isHdMode.collectLatest { hd ->
                    ivHdBadge.visibility = if (hd) View.VISIBLE else View.GONE
                }
            }
        }

        // 5-second auto-hide: show controls on connect, then hide after 5s
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.callState.collectLatest { state ->
                    if (state == com.glyph.glyph_v3.data.models.CallState.CONNECTED) {
                        controlsHidden = false
                        animateActiveChrome(false, immediate = true)
                        activeAutoHideJob?.cancel()
                        activeAutoHideJob = lifecycleScope.launch {
                            delay(5_000)
                            if (!controlsHidden) {
                                controlsHidden = true
                                animateActiveChrome(true)
                            }
                        }
                    }
                }
            }
        }

        btnEnd.setOnClickListener {
            CallManager.endCall(this)
            finish()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.slide_in_left, R.anim.slide_out_right)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.slide_in_left, R.anim.slide_out_right)
            }
        }
        btnMute.setOnClickListener { CallManager.toggleMicrophone() }
        btnSpeaker.setOnClickListener { CallManager.toggleSpeaker() }
        btnCamera.setOnClickListener { CallManager.toggleVideo() }
        btnSwitchCameraPreview.setOnClickListener { CallManager.switchCamera() }
        btnMinimize.setOnClickListener { enterPipIfSupported() }
        btnMore.setOnClickListener { showMoreMenuInVideoCall(bottomControlBar) }
        videoTapLayer.setOnClickListener {
            controlsHidden = !controlsHidden
            animateActiveChrome(controlsHidden)
            if (!controlsHidden && CallManager.callState.value == com.glyph.glyph_v3.data.models.CallState.CONNECTED) {
                // Re-show: restart the 5-second auto-hide timer
                activeAutoHideJob?.cancel()
                activeAutoHideJob = lifecycleScope.launch {
                    delay(5_000)
                    if (!controlsHidden) {
                        controlsHidden = true
                        animateActiveChrome(true)
                    }
                }
            } else {
                activeAutoHideJob?.cancel()
            }
        }
        animateActiveChrome(hidden = false, immediate = true)

        localContainer.setOnTouchListener { view, event ->
            val parent = view.parent as View
            val leftBound = dpToPxF(16f)
            val rightBound = (parent.width - view.width - dpToPxF(10f)).coerceAtLeast(leftBound)
            val topBound = dpToPxF(20f)
            val bottomBound = if (controlsHidden) {
                (parent.height - view.height - systemBottomInset - dpToPxF(10f)).coerceAtLeast(topBound)
            } else {
                (bottomControlBar.y - view.height - dpToPxF(16f)).coerceAtLeast(topBound)
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pipDragOffsetX = view.x - event.rawX
                    pipDragOffsetY = view.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val clampedX = (event.rawX + pipDragOffsetX).coerceIn(leftBound, rightBound)
                    val clampedY = (event.rawY + pipDragOffsetY).coerceIn(topBound, bottomBound)
                    view.animate()
                        .x(clampedX)
                        .y(clampedY)
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val centerX = view.x + view.width / 2f
                    val targetX = if (centerX < parent.width / 2f) {
                        leftBound
                    } else {
                        rightBound
                    }
                    view.animate()
                        .x(targetX)
                        .y(view.y.coerceIn(topBound, bottomBound))
                        .setDuration(200)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    private fun startVideoAcceptTransition() {
        if (activeLaunchStarted && !activeVideoUiVisible) return

        markIncomingCallAccepted()
        btnAccept.isEnabled = false
        btnDecline.isEnabled = false
        btnMessage.isEnabled = false
        localVideoPreviewContainer.bringToFront()

        rootLayout.post {
            val startWidth = rootLayout.width
            val startHeight = rootLayout.height
            val endWidth = dpToPx(160)
            val endHeight = dpToPx(218)
            val endLeft = rootLayout.width - endWidth - dpToPx(12)
            val endTop = rootLayout.height - endHeight - systemBottomInset - dpToPx(112)
            val endRadius = dpToPxF(22f)

            val previewMorph = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    val fraction = animator.animatedFraction
                    val params = localVideoPreviewContainer.layoutParams as FrameLayout.LayoutParams
                    params.width = lerpInt(startWidth, endWidth, fraction)
                    params.height = lerpInt(startHeight, endHeight, fraction)
                    params.leftMargin = lerpInt(0, endLeft, fraction)
                    params.topMargin = lerpInt(0, endTop, fraction)
                    params.rightMargin = 0
                    params.bottomMargin = 0
                    localVideoPreviewContainer.layoutParams = params

                    localPreviewCornerRadius = lerpFloat(0f, endRadius, fraction)
                    localVideoPreviewContainer.elevation = lerpFloat(0f, dpToPxF(14f), fraction)
                    localVideoPreviewContainer.invalidateOutline()
                    scrimOverlay.alpha = lerpFloat(0.75f, 0.12f, fraction)
                }
            }

            val chromeFade = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(contentRoot, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(contentRoot, View.TRANSLATION_Y, 0f, -dpToPxF(18f))
                )
                duration = 220L
                interpolator = LinearInterpolator()
            }

            acceptTransitionAnimator = AnimatorSet().apply {
                playTogether(previewMorph, chromeFade)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        showActiveVideoCallUi()
                    }
                })
                start()
            }
        }
    }

    private fun declineCall() {
        stopCallAnimations()
        stopRinging()
        CallManager.stopRingingObservation()
        CallNotificationHelper.cancelIncomingNotification(this)
        CallManager.declineCall(this, callId)
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    // ── Quick reply (Message button) ────────────────────────────────

    private var quickReplyOverlay: View? = null

    private fun showQuickReplyDialog() {
        if (quickReplyOverlay != null) return  // already showing

        val predefined = listOf(
            "Can't talk now. What's up?",
            "I'll call you right back.",
            "I'll call you later.",
            "Can't talk now. Call me later?"
        )

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Full-screen scrim — tapping outside dismisses the panel
        val scrim = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { dismissQuickReplyOverlay() }
        }

        // Card container (rounded corners, dark background)
        val cardBg = GradientDrawable().apply {
            setColor(0xFF1E1E1E.toInt())
            cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            elevation = dp(8).toFloat()
            // Block touch so scrim click-through doesn't fire when touching the card
            setOnClickListener { }
        }

        // Title
        val title = TextView(this).apply {
            text = "Send a message"
            textSize = 12f
            setTextColor(0xFFAAAAAA.toInt())
            typeface = android.graphics.Typeface.DEFAULT
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        card.addView(title)

        // Divider under title
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x33FFFFFF)
        })

        // Predefined reply rows
        predefined.forEachIndexed { index, message ->
            val row = TextView(this).apply {
                text = message
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(24), dp(18), dp(24), dp(18))
                isClickable = true
                isFocusable = true
                background = android.util.TypedValue().also {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                }.resourceId.let { ContextCompat.getDrawable(this@IncomingCallActivity, it) }
                setOnClickListener {
                    dismissQuickReplyOverlay()
                    sendQuickReplyAndDecline(message)
                }
            }
            card.addView(row)
            // Divider between rows
            if (index < predefined.lastIndex) {
                card.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                        marginStart = dp(24)
                    }
                    setBackgroundColor(0x1AFFFFFF)
                })
            }
        }

        // Divider before custom
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0x33FFFFFF)
        })

        // Custom message row
        val customRow = TextView(this).apply {
            text = "Custom message…"
            textSize = 16f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(dp(24), dp(18), dp(24), dp(18))
            isClickable = true
            isFocusable = true
            background = android.util.TypedValue().also {
                theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId.let { ContextCompat.getDrawable(this@IncomingCallActivity, it) }
            setOnClickListener {
                dismissQuickReplyOverlay()
                declineAndOpenChat()
            }
        }
        card.addView(customRow)

        // Bottom safe-area padding row
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, systemBottomInset + dp(8))
        })

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        scrim.addView(card, cardParams)

        // Add to the root layout of the activity
        rootLayout.addView(scrim)
        quickReplyOverlay = scrim

        // Slide-up entrance animation
        card.translationY = card.height.toFloat().coerceAtLeast(dp(400).toFloat())
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        card.post {
            card.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun dismissQuickReplyOverlay() {
        val overlay = quickReplyOverlay ?: return
        overlay.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction { rootLayout.removeView(overlay) }
            .start()
        quickReplyOverlay = null
    }

    private fun sendQuickReplyAndDecline(message: String) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            declineCall()
            return
        }

        // Resolve callerId from the live CallManager state first (already in memory),
        // fall back to a Firestore fetch if not available.
        val callerIdFromState = CallManager.callData.value?.callerId

        lifecycleScope.launch(Dispatchers.IO) {
            val callerId = callerIdFromState
                ?: runCatching { CallSignalingRepository.getCallData(callId) }
                    .getOrNull()?.callerId

            if (callerId.isNullOrBlank()) {
                // Can't identify caller — just decline
                launch(Dispatchers.Main) { declineCall() }
                return@launch
            }

            val chatId = listOf(myUid, callerId).sorted().joinToString("_")
            val db = AppDatabase.getDatabase(applicationContext)
            val repo = RealtimeMessageRepository(
                db.messageDao(), db.chatDao(), db.deletedMessageDao(), applicationContext
            )
            runCatching {
                repo.sendMessage(
                    chatId = chatId,
                    text = message,
                    otherUserId = callerId,
                    otherUsername = callerName,
                    otherUserAvatar = callerAvatar
                )
            }
            launch(Dispatchers.Main) { declineCall() }
        }
    }

    private fun declineAndOpenChat() {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            declineCall()
            return
        }

        val callerIdFromState = CallManager.callData.value?.callerId

        lifecycleScope.launch(Dispatchers.IO) {
            val callerId = callerIdFromState
                ?: runCatching { CallSignalingRepository.getCallData(callId) }
                    .getOrNull()?.callerId

            launch(Dispatchers.Main) {
                declineCall()
                if (!callerId.isNullOrBlank()) {
                    val chatId = listOf(myUid, callerId).sorted().joinToString("_")
                    val intent = ChatActivity.newIntent(
                        context = this@IncomingCallActivity,
                        chatId = chatId,
                        otherUserId = callerId,
                        otherUsername = callerName,
                        otherUserAvatar = callerAvatar
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    private fun startRinging() {
        // Ringtone
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = true
            }
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing ringtone", e)
        }

        // Vibration
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // Subtle sequential pattern: Short tick, pause, slightly longer vibration, long pause
            // Pattern: 0ms delay, 40ms ON, 200ms OFF, 80ms ON, 1500ms OFF
            val pattern = longArrayOf(0, 40, 200, 80, 1500)
            // Complementary amplitudes for API 26+ (0-255)
            val amplitudes = intArrayOf(0, 60, 0, 100, 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting vibration", e)
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        ringtone = null
        vibrator = null
    }

    private fun applyEntranceAnimation() {
        val rootView = findViewById<View>(R.id.rootLayout)
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 400
        }
        rootView.startAnimation(fadeIn)
    }

    /**
     * Overflow menu for the active video call UI, positioned above [anchorBar] with a 5dp gap.
     * Identical logic to ActiveCallActivity.showMoreMenuAboveBar().
     */
    @android.annotation.SuppressLint("InflateParams")
    private fun showMoreMenuInVideoCall(anchorBar: View) {
        val isHd = CallManager.isHdMode.value

        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(14))
        }
        val label = android.widget.TextView(this).apply {
            text = "HD video (720p)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val check = CheckBox(this).apply {
            isChecked = isHd
            isClickable = false
            scaleX = 0.9f
            scaleY = 0.9f
        }
        row.addView(label)
        row.addView(check)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF151a1e.toInt())
                setStroke(dpToPx(1), 0x2EFFFFFF)
                cornerRadius = dpToPx(16).toFloat()
            }
            addView(row)
        }

        val barWidth = anchorBar.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        container.measure(
            View.MeasureSpec.makeMeasureSpec(barWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight = container.measuredHeight
        val barHeight = anchorBar.height.takeIf { it > 0 } ?: dpToPx(76)

        val popup = PopupWindow(
            container,
            barWidth,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dpToPx(4).toFloat()
        }

        val gap = dpToPx(5)
        popup.showAsDropDown(anchorBar, 0, -(barHeight + popupHeight + gap))

        row.setOnClickListener {
            CallManager.toggleHdMode()
            check.isChecked = CallManager.isHdMode.value
            popup.dismiss()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun dpToPxF(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun enterPipIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(9, 16))
                .build()
            enterPictureInPictureMode(params)
        } else {
            moveTaskToBack(true)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (!activeVideoUiVisible) return
        if (isInPictureInPictureMode) {
                // Snap chrome off-screen while keeping the local preview overlay visible.
            pipActiveChromeCallback?.invoke(true, true)
                pipActiveLocalContainerView?.visibility =
                    if (CallManager.isVideoEnabled.value) View.VISIBLE else View.GONE
            pipActiveLocalContainerView?.scaleX = pipLocalPreviewScale
            pipActiveLocalContainerView?.scaleY = pipLocalPreviewScale
        } else {
            // Restore local preview, then restore chrome to shown state
            pipActiveLocalContainerView?.visibility =
                if (CallManager.isVideoEnabled.value) View.VISIBLE else View.GONE
            pipActiveChromeCallback?.invoke(false, true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (activeVideoUiVisible && CallManager.callState.value == CallState.CONNECTED) {
            enterPipIfSupported()
        }
    }

    override fun onStart() {
        super.onStart()
        CallNotificationHelper.cancelIncomingNotification(this)
        if (activeVideoUiVisible) return
        swipeAccepted = false
        swipeDeclined = false
        btnAccept.isEnabled = true
        btnDecline.isEnabled = true
        acceptButtonStack.alpha = 1f
        declineButtonStack.alpha = 1f
        btnAccept.alpha = 1f
        acceptButtonStack.translationY = 0f
        btnDecline.alpha = 1f
        declineButtonStack.translationY = 0f
        swipeArrowContainer.alpha = 1f
        if (!acceptSignalSent && !activeLaunchStarted) {
            startCallAnimations()
        }
    }

    override fun onStop() {
        if (!activeVideoUiVisible) {
            stopCallAnimations()
        }
        super.onStop()
    }

    override fun onDestroy() {
        stopCallAnimations()
        stopRinging()
        try {
            incomingLocalTrack?.removeSink(localVideoPreviewBg)
        } catch (_: Exception) {}
        activeRemoteVideoView?.let { renderer ->
            try {
                activeRemoteTrack?.removeSink(renderer)
            } catch (_: Exception) {}
        }
        activeLocalVideoView?.let { renderer ->
            try {
                activeLocalTrack?.removeSink(renderer)
            } catch (_: Exception) {}
        }
        try {
            localVideoPreviewBg.release()
        } catch (_: Exception) {}
        try {
            activeRemoteVideoView?.release()
        } catch (_: Exception) {}
        eglBase?.release()
        eglBase = null
        if (!acceptSignalSent) {
            CallManager.releaseIncomingVideoPreview(callId)
        }
        super.onDestroy()
    }

    private fun lerpInt(start: Int, end: Int, fraction: Float): Int {
        return start + ((end - start) * fraction).toInt()
    }

    private fun lerpFloat(start: Float, end: Float, fraction: Float): Float {
        return start + ((end - start) * fraction)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Do not allow back press to dismiss incoming call
    }
}
