package com.glyph.glyph_v3.ui.calls

import android.annotation.SuppressLint
import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallData
import com.glyph.glyph_v3.data.models.CallMode
import com.glyph.glyph_v3.data.models.CallState
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.models.OutgoingCallUiStatus
import com.glyph.glyph_v3.data.models.VideoUpgradeRequestState
import com.glyph.glyph_v3.data.repo.CallSignalingRepository
import com.glyph.glyph_v3.data.service.CallNotificationHelper
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.data.webrtc.CallManager
import com.glyph.glyph_v3.data.webrtc.GroupCallManager
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.getstream.webrtc.android.ui.VideoTextureViewRenderer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.util.Rational
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.PopupWindow
import androidx.annotation.RequiresApi

/**
 * Active call screen — handles both voice and video call UIs.
 * Switches layout based on call type. Matches WhatsApp pixel-for-pixel.
 */
class ActiveCallActivity : AppCompatActivity() {

    companion object {
        const val TAG = "ActiveCall"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_CONTACT_AVATAR = "contact_avatar"
        const val EXTRA_AUTO_ACCEPT = "auto_accept"

        fun createIntent(
            context: Context,
            callId: String,
            callType: CallType,
            contactName: String = "",
            contactAvatar: String = "",
            autoAccept: Boolean = false
        ): Intent = Intent(context, ActiveCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_CALL_TYPE, callType.name)
            putExtra(EXTRA_CONTACT_NAME, contactName)
            putExtra(EXTRA_CONTACT_AVATAR, contactAvatar)
            putExtra(EXTRA_AUTO_ACCEPT, autoAccept)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private var callId = ""
    private var callType = CallType.VOICE
    private var contactName = ""
    private var contactAvatar = ""
    private var autoAccept = false
    private var autoAcceptHandled = false
    private var pendingCameraEnableAfterPermission = false

    private var eglBase: EglBase? = null
    private var remoteVideoView: SurfaceViewRenderer? = null
    private var localVideoView: VideoTextureViewRenderer? = null

    private var currentRemoteTrack: VideoTrack? = null
    private var currentLocalTrack: VideoTrack? = null

    // Drag state for local video preview
    private var dX = 0f
    private var dY = 0f
    private var systemBottomInset = 0
    private var localPreviewConnectedMode = false
    private var localPreviewLayoutInitialized = false
    private var calleePhone = ""
    private var controlsHidden = false
    private var lastObservedCallState: CallState? = null
    private var autoHideJob: kotlinx.coroutines.Job? = null
    private var terminalTransitionHandled = false

    // PiP state
    private var isInPipMode = false
    private var pipTopBarView: View? = null
    private var pipBottomBarView: View? = null
    private var pipLocalContainerView: View? = null
    private var pipChromeCallback: ((hidden: Boolean, immediate: Boolean) -> Unit)? = null
    private val pipLocalPreviewScale = 0.28f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            if (pendingCameraEnableAfterPermission) {
                pendingCameraEnableAfterPermission = false
                CallManager.toggleVideo()
            } else {
                acceptCallFromLaunch()
            }
        } else {
            Log.w(TAG, "Permissions denied during notification accept")
            pendingCameraEnableAfterPermission = false
            if (autoAcceptHandled) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureLockScreenPresentation()

        // Allow the background to render behind the status bar and navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        readIntent(intent)

        // Lock orientation programmatically (manifest has no screenOrientation for PiP compatibility)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContentView(R.layout.activity_video_call)
        setupVideoCallUI()

        observeCallState()
        observeGroupCallUpgrade()
        maybeAutoAcceptFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val previousCallId = callId
        setIntent(intent)
        readIntent(intent)

        if (previousCallId.isNotBlank() && previousCallId != callId) {
            recreate()
            return
        }

        maybeAutoAcceptFromIntent()
    }

    // ══════════════════════════════════════════════════════════════════
    // VOICE CALL UI
    // ══════════════════════════════════════════════════════════════════

    private fun setupVoiceCallUI() {
        val tvName = findViewById<TextView>(R.id.tvContactName)
        val tvTimer = findViewById<TextView>(R.id.tvCallTimer)
        val tvStatus = findViewById<TextView>(R.id.tvCallStatus)
        val ivAvatar = findViewById<ImageView>(R.id.ivContactAvatar)
        val btnMore = findViewById<FrameLayout>(R.id.btnMore)
        val btnVideo = findViewById<FrameLayout>(R.id.btnVideo)
        val btnSpeaker = findViewById<FrameLayout>(R.id.btnSpeaker)
        val btnMute = findViewById<FrameLayout>(R.id.btnMute)
        val btnEnd = findViewById<FrameLayout>(R.id.btnEndCall)
        val btnMinimize = findViewById<FrameLayout>(R.id.btnMinimize)
        val ivSpeaker = findViewById<ImageView>(R.id.ivSpeakerIcon)
        val ivMute = findViewById<ImageView>(R.id.ivMuteIcon)

        // ── WindowInsets: respect status bar and navigation bar ──────
        val topBarContainer  = findViewById<View>(R.id.topBarContainer)
        val controlBarWrapper = findViewById<View>(R.id.controlBarWrapper)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootVoiceCall)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBarContainer.updatePadding(top = bars.top + dpToPx(10))
            controlBarWrapper.updatePadding(bottom = bars.bottom + dpToPx(16))
            insets
        }

        // Fill from CallManager data or intent extras
        val callData = CallManager.callData.value
        val receiverId = callData?.receiverId.orEmpty()
        val peerId = callData?.let { cd ->
            val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            if (cd.callerId == myUid) cd.receiverId else cd.callerId
        }.orEmpty()
        val resolvedName = ContactDisplayNameResolver.getDisplayName(
            otherUserId = peerId,
            remoteProfileName = contactName.ifEmpty { callData?.receiverName ?: callData?.callerName ?: "" }
        )
        val avatar = contactAvatar.ifEmpty { callData?.receiverAvatar ?: callData?.callerAvatar ?: "" }
        tvName.text = resolvedName

        if (avatar.isNotEmpty()) {
            Glide.with(this)
                .load(avatar)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .into(ivAvatar)
        }

        tvTimer.text = if (CallManager.callState.value == CallState.CONNECTED) {
            formatDuration(CallManager.callDurationSeconds.value)
        } else {
            formatPrimaryCallLine(callData, CallManager.callState.value, CallManager.callDurationSeconds.value)
        }
        resolveCalleePhoneIfMissing(receiverId) { resolvedPhone ->
            if (isCallerSide() && CallManager.callState.value != CallState.CONNECTED) {
                setPrimaryLabelText(tvTimer, resolvedPhone)
            }
        }
        bindVoiceHeader(tvTimer, tvStatus)

        // Button states
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
                    ivSpeaker.setColorFilter(
                        if (speaker) 0xFF333333.toInt() else 0xFFFFFFFF.toInt()
                    )
                }
            }
        }

        btnEnd.setOnClickListener { endAndFinish() }
        btnMute.setOnClickListener { CallManager.toggleMicrophone() }
        btnSpeaker.setOnClickListener { CallManager.toggleSpeaker() }
        btnVideo.setOnClickListener {
            // Upgrade to video call — could be implemented as call renegotiation
        }
        btnMinimize.setOnClickListener { moveTaskToBack(true) }
        btnMore.setOnClickListener { /* More options menu */ }
    }

    // ══════════════════════════════════════════════════════════════════
    // VIDEO CALL UI
    // ══════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVideoCallUI() {
        val tvName = findViewById<TextView>(R.id.tvContactName)
        val tvTimer = findViewById<TextView>(R.id.tvCallTimer)
        val tvStatus = findViewById<TextView>(R.id.tvCallStatus)
        val rootVideoCall = findViewById<View>(R.id.rootVideoCall)
        val videoTapLayer = findViewById<View>(R.id.videoTapLayer)
        val topBar = findViewById<View>(R.id.topBar)
        val bottomControlBar = findViewById<View>(R.id.bottomControlBar)
        val btnMore = findViewById<FrameLayout>(R.id.btnMore)
        val btnCamera = findViewById<FrameLayout>(R.id.btnCamera)
        val btnSpeaker = findViewById<FrameLayout>(R.id.btnSpeaker)
        val btnMute = findViewById<FrameLayout>(R.id.btnMute)
        val btnEnd = findViewById<FrameLayout>(R.id.btnEndCall)
        val btnMinimize = findViewById<ImageView>(R.id.btnMinimize)
        val btnAddParticipant = findViewById<ImageView>(R.id.btnAddParticipant)
        val btnSwitchCameraPreview = findViewById<ImageView>(R.id.btnSwitchCameraPreview)
        val ivVoiceAvatar = findViewById<ImageView>(R.id.ivVoiceAvatar)
        val localContainer = findViewById<FrameLayout>(R.id.localVideoContainer)
        val ivHdBadge = findViewById<ImageView>(R.id.ivHdBadge)
        val ivSpeaker = findViewById<ImageView>(R.id.ivSpeakerIcon)
        val ivMute = findViewById<ImageView>(R.id.ivMuteIcon)
        val ivCamera = findViewById<ImageView>(R.id.ivCameraIcon)
        val pipCornerRadius = dpToPx(22).toFloat()

        // Store view refs for PiP visibility management
        pipTopBarView = topBar
        pipBottomBarView = bottomControlBar
        pipLocalContainerView = localContainer

        val callData = CallManager.callData.value
        val receiverId = callData?.receiverId.orEmpty()
        val peerId = callData?.let { cd ->
            val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            if (cd.callerId == myUid) cd.receiverId else cd.callerId
        }.orEmpty()
        val resolvedName = ContactDisplayNameResolver.getDisplayName(
            otherUserId = peerId,
            remoteProfileName = contactName.ifEmpty { callData?.receiverName ?: callData?.callerName ?: "" }
        )
        val avatar = contactAvatar.ifEmpty { callData?.receiverAvatar ?: callData?.callerAvatar ?: "" }

        tvName.text = resolvedName
        if (avatar.isNotEmpty()) {
            Glide.with(this)
                .load(avatar)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .into(ivVoiceAvatar)
        }
        tvTimer.text = formatPrimaryCallLine(callData, CallManager.callState.value, CallManager.callDurationSeconds.value)
        resolveCalleePhoneIfMissing(receiverId) { resolvedPhone ->
            if (isCallerSide() && CallManager.callState.value != CallState.CONNECTED) {
                setPrimaryLabelText(tvTimer, resolvedPhone)
            }
        }
        bindVideoHeader(tvTimer, tvStatus)

        localContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = if (localPreviewConnectedMode) pipCornerRadius else 0f
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        localContainer.clipToOutline = true

        fun animateActiveChrome(hidden: Boolean, immediate: Boolean = false) {
            rootVideoCall.post {
                if (rootVideoCall.width == 0 || rootVideoCall.height == 0) return@post

                if (!localPreviewConnectedMode) {
                    val topTarget = if (hidden) -(topBar.height + dpToPx(18)).toFloat() else 0f
                    val bottomTarget = if (hidden) (bottomControlBar.height + systemBottomInset + dpToPx(28)).toFloat() else 0f
                    if (immediate) {
                        topBar.translationY = topTarget
                        topBar.alpha = if (hidden) 0f else 1f
                        bottomControlBar.translationY = bottomTarget
                        bottomControlBar.alpha = if (hidden) 0f else 1f
                    } else {
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
                    }
                    return@post
                }

                val topMargin = (topBar.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: 0
                val rightMargin = (localContainer.layoutParams as? FrameLayout.LayoutParams)?.rightMargin ?: dpToPx(12)
                val shownBottomMargin = (bottomControlBar.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin
                    ?: (systemBottomInset + dpToPx(18))
                val topTarget = if (hidden) -(topBar.height + topMargin + dpToPx(18)).toFloat() else 0f
                val bottomTarget = if (hidden) (bottomControlBar.height + systemBottomInset + dpToPx(28)).toFloat() else 0f
                    val targetScale = if (hidden) {
                        if (isInPipMode) pipLocalPreviewScale else 0.45f
                    } else {
                        1f
                    }
                        val edgeMargin = if (isInPipMode && hidden) dpToPx(5).toFloat() else dpToPx(10).toFloat()
                        val hiddenBottomInset = if (isInPipMode && hidden) {
                            edgeMargin
                        } else {
                            systemBottomInset.toFloat() + edgeMargin
                        }

                localContainer.pivotX = localContainer.width.toFloat()
                localContainer.pivotY = localContainer.height.toFloat()
                localContainer.invalidateOutline()

                val targetX = if (hidden) {
                    (rootVideoCall.width - localContainer.width - edgeMargin).toFloat()
                } else {
                    (rootVideoCall.width - localContainer.width - rightMargin).toFloat()
                }
                val shownTargetY = (rootVideoCall.height - bottomControlBar.height - shownBottomMargin - localContainer.height - dpToPx(10).toFloat())
                    .coerceAtLeast(dpToPx(20).toFloat())
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
        pipChromeCallback = { hidden, immediate -> animateActiveChrome(hidden, immediate) }

        ViewCompat.setOnApplyWindowInsetsListener(rootVideoCall) { _, insets ->
            // Don't re-layout while in PiP — the window is tiny and insets are wrong
            if (isInPipMode) return@setOnApplyWindowInsetsListener insets
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            systemBottomInset = bars.bottom
            topBar.updatePadding(
                left = topBar.paddingLeft,
                top = bars.top + dpToPx(10),
                right = topBar.paddingRight,
                bottom = topBar.paddingBottom
            )

            (bottomControlBar.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.bottomMargin = bars.bottom + dpToPx(18)
                bottomControlBar.layoutParams = params
            }

            (localContainer.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.rightMargin = dpToPx(12)
                localContainer.layoutParams = params
            }
            applyOutgoingLocalPreviewLayout(
                rootVideoCall = rootVideoCall,
                bottomControlBar = bottomControlBar,
                localContainer = localContainer,
                btnSwitchCameraPreview = btnSwitchCameraPreview,
                connected = localPreviewConnectedMode,
                animate = false
            )
            animateActiveChrome(controlsHidden, immediate = true)
            insets
        }
        ViewCompat.requestApplyInsets(rootVideoCall)

        remoteVideoView = findViewById(R.id.remoteVideoView)
        localVideoView = findViewById(R.id.localVideoView)

        // Initialize WebRTC renderers
        eglBase = CallManager.rtcClient?.eglBaseContext?.let { ctx ->
            EglBase.create(ctx)
        } ?: EglBase.create()

        val eglCtx = CallManager.rtcClient?.eglBaseContext ?: eglBase!!.eglBaseContext

        // Start transparent — fade in smoothly when the first remote frame arrives.
        // This avoids a jarring black-flash while the decoder produces its first frame
        // and makes the video appearance feel instant rather than abrupt.
        remoteVideoView?.alpha = 0f
        remoteVideoView?.init(eglCtx, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() {
                runOnUiThread {
                    remoteVideoView?.animate()
                        ?.alpha(1f)
                        ?.setDuration(250)
                        ?.start()
                }
            }
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) = Unit
        })
        remoteVideoView?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        remoteVideoView?.setEnableHardwareScaler(true)
        remoteVideoView?.setMirror(false)

        localVideoView?.init(
            eglCtx,
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() = Unit

                override fun onFrameResolutionChanged(
                    videoWidth: Int,
                    videoHeight: Int,
                    rotation: Int
                ) = Unit
            }
        )
        localVideoView?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        // Attach video tracks via rtcClientFlow so that if the client is ever
        // replaced (e.g. call re-init), the subscriptions automatically follow.
        // Nested collectLatest: outer cancels+restarts on client change, inner
        // reacts to each new track (null or non-null).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.rtcClientFlow.collectLatest { client ->
                    if (client != null) {
                        launch {
                            client.isFrontCameraFlow.collectLatest { isFront ->
                                localVideoView?.setMirror(isFront)
                            }
                        }
                        launch {
                            client.remoteVideoTrack.collectLatest { track ->
                                currentRemoteTrack?.removeSink(remoteVideoView!!)
                                currentRemoteTrack = track
                                track?.addSink(remoteVideoView!!)
                            }
                        }
                        launch {
                            client.localVideoTrack.collectLatest { track ->
                                currentLocalTrack?.removeSink(localVideoView!!)
                                currentLocalTrack = track
                                track?.addSink(localVideoView!!)
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    CallManager.callState,
                    CallManager.isVideoEnabled,
                    CallManager.isRemoteVideoEnabled
                ) { state, localVideoEnabled, remoteVideoEnabled ->
                    Triple(state, localVideoEnabled, remoteVideoEnabled)
                }
                    .distinctUntilChanged()
                    .collectLatest { (state, localVideoEnabled, remoteVideoEnabled) ->
                    val connected = state == CallState.CONNECTED && localVideoEnabled && remoteVideoEnabled
                    applyOutgoingLocalPreviewLayout(
                        rootVideoCall = rootVideoCall,
                        bottomControlBar = bottomControlBar,
                        localContainer = localContainer,
                        btnSwitchCameraPreview = btnSwitchCameraPreview,
                        connected = connected,
                        animate = localPreviewLayoutInitialized && localPreviewConnectedMode != connected
                    )
                    localPreviewConnectedMode = connected
                    localPreviewLayoutInitialized = true
                    localContainer.invalidateOutline()

                    if (connected) {
                        // Show controls first, then auto-hide after 5 seconds
                        controlsHidden = false
                        animateActiveChrome(false, immediate = true)
                        autoHideJob?.cancel()
                        autoHideJob = lifecycleScope.launch {
                            kotlinx.coroutines.delay(5_000)
                            if (!controlsHidden) {
                                controlsHidden = true
                                animateActiveChrome(true)
                            }
                        }
                    } else {
                        autoHideJob?.cancel()
                        animateActiveChrome(controlsHidden, immediate = true)
                    }

                    if (connected) {
                    }

                    remoteVideoView?.visibility = if (remoteVideoEnabled) View.VISIBLE else View.INVISIBLE
                    ivVoiceAvatar.visibility = if (!localVideoEnabled && !remoteVideoEnabled) View.VISIBLE else View.GONE
                }
            }
        }

        // Button states
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.isRemoteVideoEnabled.collectLatest { remoteVideoEnabled ->
                    remoteVideoView?.visibility = if (remoteVideoEnabled) View.VISIBLE else View.INVISIBLE
                    if (!remoteVideoEnabled) {
                        remoteVideoView?.alpha = 0f
                    }
                }
            }
        }

        // Show HD badge on own preview when HD mode is active
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.isHdMode.collectLatest { hd ->
                    ivHdBadge.visibility = if (hd) View.VISIBLE else View.GONE
                }
            }
        }

        // Button actions
        btnEnd.setOnClickListener { endAndFinish() }
        btnMute.setOnClickListener { CallManager.toggleMicrophone() }
        btnSpeaker.setOnClickListener { CallManager.toggleSpeaker() }
        btnCamera.setOnClickListener {
            ensureCameraPermissionForVideoToggle()
        }
        btnSwitchCameraPreview.setOnClickListener { CallManager.switchCamera() }
        btnMinimize.setOnClickListener { enterPipIfSupported() }
        btnMore.setOnClickListener {
            showMoreMenuAboveBar(bottomControlBar)
        }
        btnAddParticipant.setOnClickListener {
            upgradeToGroupCall()
        }
        videoTapLayer.setOnClickListener {
            controlsHidden = !controlsHidden
            animateActiveChrome(controlsHidden)
            if (!controlsHidden && CallManager.callState.value == CallState.CONNECTED) {
                // Re-show controls and restart the 5s auto-hide timer
                autoHideJob?.cancel()
                autoHideJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(5_000)
                    if (!controlsHidden) {
                        controlsHidden = true
                        animateActiveChrome(true)
                    }
                }
            } else {
                autoHideJob?.cancel()
            }
        }

        rootVideoCall.post {
            localContainer.bringToFront()
            topBar.bringToFront()
            bottomControlBar.bringToFront()
            animateActiveChrome(hidden = false, immediate = true)
        }

        // Draggable local video preview
        localContainer.setOnTouchListener { v, event ->
            if (!localPreviewConnectedMode) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = v.parent as View
                    val leftBound = dpToPx(16).toFloat()
                    val rightBound = (parent.width - v.width - dpToPx(10)).toFloat().coerceAtLeast(leftBound)
                    val topBound = dpToPx(20).toFloat()
                    val bottomBound = (bottomControlBar.y - v.height - dpToPx(16)).coerceAtLeast(topBound)
                    v.animate()
                        .x((event.rawX + dX).coerceIn(leftBound, rightBound))
                        .y((event.rawY + dY).coerceIn(topBound, bottomBound))
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Snap to nearest edge
                    val parent = v.parent as View
                    val leftBound = dpToPx(16).toFloat()
                    val rightBound = (parent.width - v.width - dpToPx(10)).toFloat().coerceAtLeast(leftBound)
                    val topBound = dpToPx(20).toFloat()
                    val bottomBound = (bottomControlBar.y - v.height - dpToPx(16)).coerceAtLeast(topBound)
                    val centerX = v.x + v.width / 2
                    val targetX = if (centerX < parent.width / 2f) {
                        leftBound
                    } else {
                        rightBound
                    }
                    v.animate()
                        .x(targetX)
                        .y(v.y.coerceIn(topBound, bottomBound))
                        .setDuration(200)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // STATE OBSERVATION
    // ══════════════════════════════════════════════════════════════════

    private fun observeCallState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.callState.collectLatest { state ->
                    handleCallStateSound(state)

                    val isTerminalState = state == CallState.BUSY ||
                        state == CallState.NO_ANSWER ||
                        state == CallState.DECLINED ||
                        state == CallState.ENDED ||
                        state == CallState.MISSED
                    if (!isTerminalState) {
                        terminalTransitionHandled = false
                    } else if (terminalTransitionHandled) {
                        return@collectLatest
                    }

                    when (state) {
                        CallState.BUSY -> {
                            terminalTransitionHandled = true
                            val callData = CallManager.callData.value
                            val name   = contactName.ifEmpty { callData?.receiverName ?: "" }
                            val avatar = contactAvatar.ifEmpty { callData?.receiverAvatar ?: "" }
                            val rcvId  = callData?.receiverId ?: ""
                            startActivity(
                                UnansweredCallActivity.createIntent(
                                    context        = this@ActiveCallActivity,
                                    contactName    = name,
                                    contactAvatar  = avatar,
                                    receiverId     = rcvId,
                                    callType       = callType,
                                    callReason     = "User busy"
                                )
                            )
                            finish()
                            @Suppress("DEPRECATION")
                            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                        }
                        CallState.NO_ANSWER -> {
                            terminalTransitionHandled = true
                            // Show the "No answer" screen instead of silently finishing
                            val callData = CallManager.callData.value
                            val name   = contactName.ifEmpty { callData?.receiverName ?: "" }
                            val avatar = contactAvatar.ifEmpty { callData?.receiverAvatar ?: "" }
                            val rcvId  = callData?.receiverId ?: ""
                            startActivity(
                                UnansweredCallActivity.createIntent(
                                    context        = this@ActiveCallActivity,
                                    contactName    = name,
                                    contactAvatar  = avatar,
                                    receiverId     = rcvId,
                                    callType       = callType,
                                    callReason     = "No answer"
                                )
                            )
                            finish()
                            @Suppress("DEPRECATION")
                            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                        }
                        CallState.DECLINED -> {
                            terminalTransitionHandled = true
                            // Receiver actively declined — show screen with "Call declined" message
                            val callData = CallManager.callData.value
                            val name   = contactName.ifEmpty { callData?.receiverName ?: "" }
                            val avatar = contactAvatar.ifEmpty { callData?.receiverAvatar ?: "" }
                            val rcvId  = callData?.receiverId ?: ""
                            startActivity(
                                UnansweredCallActivity.createIntent(
                                    context        = this@ActiveCallActivity,
                                    contactName    = name,
                                    contactAvatar  = avatar,
                                    receiverId     = rcvId,
                                    callType       = callType,
                                    callReason     = "Call declined"
                                )
                            )
                            finish()
                            @Suppress("DEPRECATION")
                            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                        }
                        CallState.ENDED, CallState.MISSED -> {
                            terminalTransitionHandled = true
                            finish()
                            @Suppress("DEPRECATION")
                            overridePendingTransition(android.R.anim.slide_in_left, R.anim.slide_out_right)
                        }
                        CallState.CONNECTED -> {
                        }
                        null -> {
                        }
                        else -> {
                        }
                    }
                }
            }
        }
    }

    private fun handleCallStateSound(state: CallState?) {
        if (state == lastObservedCallState) {
            return
        }
        lastObservedCallState = state

        when (state) {
            CallState.RINGING, CallState.ACCEPTED -> {
                if (isCallerSide()) {
                    CallSoundPlayer.startOutgoingRinging(this)
                }
            }
            CallState.CONNECTED -> {
                CallSoundPlayer.stop()
                CallSoundPlayer.playConnected(this)
            }
            CallState.BUSY -> {
                CallSoundPlayer.stop()
                if (isCallerSide()) {
                    CallSoundPlayer.playBusy(this)
                }
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

    /**
     * Observes the [CallManager.upgradedToGroupCallId] signal emitted when the
     * remote peer upgrades the 1:1 call to a group call. When received,
     * seamlessly detaches the local WebRTC client and migrates to the group call.
     */
    private fun observeGroupCallUpgrade() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallManager.upgradedToGroupCallId.collect { groupCallId ->
                    if (groupCallId.isBlank()) return@collect

                    val callData = CallManager.callData.value ?: return@collect
                    val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@collect
                    val peerId = if (callData.callerId == myUid) callData.receiverId else callData.callerId
                    val peerName = if (callData.callerId == myUid) callData.receiverName else callData.callerName
                    val peerAvatar = if (callData.callerId == myUid) callData.receiverAvatar else callData.callerAvatar

                    // Detach the live WebRTC client
                    val detached = CallManager.detachForGroupUpgrade(groupCallId) ?: return@collect

                    // Join the group call with the existing peer connection
                    GroupCallManager.migrateFromOneToOneCall(
                        context = this@ActiveCallActivity,
                        detachedInfo = detached,
                        groupCallId = groupCallId,
                        existingPeerId = peerId,
                        existingPeerName = peerName,
                        existingPeerAvatar = peerAvatar
                    )

                    // Navigate to GroupCallActivity
                    val intent = GroupCallActivity.createIntent(
                        context = this@ActiveCallActivity,
                        groupCallId = groupCallId,
                        callType = detached.callData.callType(),
                        isJoining = false
                    )
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }
        }
    }

    private fun isCallerSide(): Boolean {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val currentCallData = CallManager.callData.value
        return myUid.isNotBlank() && currentCallData?.callerId == myUid
    }

    private data class HeaderCallState(
        val callData: CallData?,
        val outgoingStatus: OutgoingCallUiStatus?,
        val callState: CallState?,
        val durationSeconds: Int
    )

    private data class VideoUpgradeHeaderState(
        val upgradeState: VideoUpgradeRequestState,
        val upgradeRequesterId: String,
        val localVideoEnabled: Boolean,
        val remoteVideoEnabled: Boolean
    )

    private fun bindVoiceHeader(primaryView: TextView, statusView: TextView) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    CallManager.callData,
                    CallManager.outgoingCallUiStatus,
                    CallManager.callState,
                    CallManager.callDurationSeconds
                ) { callData, outgoingStatus, callState, durationSeconds ->
                    if (isCallerSide(callData)) {
                        Pair(
                            formatPrimaryCallLine(callData, callState, durationSeconds),
                            formatOutgoingStatusText(outgoingStatus, callState)
                        )
                    } else {
                        Pair(
                            formatPrimaryCallLine(callData, callState, durationSeconds),
                            formatIncomingVoiceStatusText(callState)
                        )
                    }
                }
                    .distinctUntilChanged()
                    .collectLatest { (primaryText, statusText) ->
                    setPrimaryLabelText(primaryView, primaryText)
                    setAnimatedStatusText(statusView, statusText)
                }
            }
        }
    }

    private fun bindVideoHeader(primaryView: TextView, statusView: TextView) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    combine(
                        CallManager.callData,
                        CallManager.outgoingCallUiStatus,
                        CallManager.callState,
                        CallManager.callDurationSeconds
                    ) { callData, outgoingStatus, callState, durationSeconds ->
                        HeaderCallState(callData, outgoingStatus, callState, durationSeconds)
                    },
                    combine(
                        CallManager.videoUpgradeRequestState,
                        CallManager.videoUpgradeRequesterId,
                        CallManager.isVideoEnabled,
                        CallManager.isRemoteVideoEnabled
                    ) { upgradeState, upgradeRequesterId, localVideoEnabled, remoteVideoEnabled ->
                        VideoUpgradeHeaderState(
                            upgradeState = upgradeState,
                            upgradeRequesterId = upgradeRequesterId,
                            localVideoEnabled = localVideoEnabled,
                            remoteVideoEnabled = remoteVideoEnabled
                        )
                    }
                ) { headerState, videoState ->
                    val callData = headerState.callData
                    val outgoingStatus = headerState.outgoingStatus
                    val callState = headerState.callState
                    val durationSeconds = headerState.durationSeconds
                    val upgradeState = videoState.upgradeState
                    val upgradeRequesterId = videoState.upgradeRequesterId
                    val localVideoEnabled = videoState.localVideoEnabled
                    val remoteVideoEnabled = videoState.remoteVideoEnabled
                    val statusText = when {
                        callState == CallState.CONNECTED &&
                            upgradeState == VideoUpgradeRequestState.PENDING &&
                            upgradeRequesterId.isNotBlank() &&
                            upgradeRequesterId != FirebaseAuth.getInstance().currentUser?.uid.orEmpty() &&
                            !localVideoEnabled -> "${ContactDisplayNameResolver.getDisplayName(
                                otherUserId = upgradeRequesterId,
                                remoteProfileName = contactName
                            )} wants to enable video. Tap camera to join"
                        callState == CallState.CONNECTED &&
                            upgradeState == VideoUpgradeRequestState.PENDING &&
                            localVideoEnabled &&
                            !remoteVideoEnabled -> "Waiting for video acceptance"
                        callState == CallState.CONNECTED &&
                            upgradeState == VideoUpgradeRequestState.REJECTED &&
                            !localVideoEnabled -> "Video request declined"
                        isCallerSide(callData) -> formatOutgoingStatusText(outgoingStatus, callState)
                        else -> ""
                    }
                    if (isCallerSide(callData)) {
                        Pair(
                            formatPrimaryCallLine(callData, callState, durationSeconds),
                            statusText
                        )
                    } else {
                        Pair(
                            formatPrimaryCallLine(callData, callState, durationSeconds),
                            statusText
                        )
                    }
                }
                    .distinctUntilChanged()
                    .collectLatest { (primaryText, statusText) ->
                    setPrimaryLabelText(primaryView, primaryText)
                    setAnimatedStatusText(statusView, statusText)
                }
            }
        }
    }

    private fun isCallerSide(callData: CallData?): Boolean {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        return myUid.isNotBlank() && callData?.callerId == myUid
    }

    private fun formatOutgoingStatusText(
        outgoingStatus: OutgoingCallUiStatus?,
        callState: CallState?
    ): String {
        return when (outgoingStatus) {
            OutgoingCallUiStatus.CONNECTING -> getString(R.string.call_status_connecting)
            OutgoingCallUiStatus.CALLING -> getString(R.string.call_status_calling)
            OutgoingCallUiStatus.RINGING -> getString(R.string.call_status_ringing)
            OutgoingCallUiStatus.CONNECTED -> ""
            null -> when (callState) {
                CallState.CONNECTED -> ""
                CallState.ACCEPTED,
                CallState.INITIATING -> getString(R.string.call_status_connecting)
                CallState.RINGING -> getString(R.string.call_status_ringing)
                else -> ""
            }
        }
    }

    private fun formatIncomingVoiceStatusText(callState: CallState?): String {
        return when (callState) {
            CallState.RINGING,
            CallState.INITIATING,
            CallState.ACCEPTED -> getString(R.string.call_status_ringing)
            else -> ""
        }
    }

    private fun formatPrimaryCallLine(
        callData: CallData?,
        callState: CallState?,
        durationSeconds: Int
    ): String {
        return if (callState == CallState.CONNECTED) {
            formatDuration(durationSeconds)
        } else {
            val otherPhone = if (isCallerSide(callData)) {
                calleePhone
            } else {
                callData?.callerPhone.orEmpty()
            }
            formatCalleePhoneForDisplay(otherPhone).ifBlank { "Unknown number" }
        }
    }

    private fun formatDuration(durationSeconds: Int): String {
        return "%d:%02d".format(durationSeconds / 60, durationSeconds % 60)
    }

    private fun setPrimaryLabelText(labelView: TextView, labelText: String) {
        if (labelView.text == labelText) {
            return
        }
        labelView.animate().cancel()
        labelView.text = labelText
        labelView.alpha = 1f
    }

    private fun setAnimatedStatusText(statusView: TextView, statusText: String) {
        val shouldShow = statusText.isNotBlank()
        val currentText = statusView.text?.toString().orEmpty()
        val isVisible = statusView.visibility == View.VISIBLE

        if (shouldShow && isVisible && currentText == statusText) {
            return
        }
        if (!shouldShow && !isVisible) {
            return
        }

        statusView.animate().cancel()

        if (!shouldShow) {
            if (!statusView.isLaidOut) {
                statusView.text = ""
                statusView.alpha = 1f
                statusView.visibility = View.GONE
                return
            }
            statusView.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    statusView.text = ""
                    statusView.visibility = View.GONE
                    statusView.alpha = 1f
                }
                .start()
            return
        }

        if (!statusView.isLaidOut || !isVisible) {
            statusView.text = statusText
            statusView.visibility = View.VISIBLE
            statusView.alpha = 0f
            statusView.animate()
                .alpha(1f)
                .setDuration(160)
                .start()
            return
        }

        statusView.animate()
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                statusView.text = statusText
                statusView.visibility = View.VISIBLE
                statusView.animate()
                    .alpha(1f)
                    .setDuration(160)
                    .start()
            }
            .start()
    }

    /**
     * Shows the overflow menu as a [PopupWindow] positioned above [anchorBar] with a 5dp gap,
     * matching the bar's background color and corner radius.
     */
    @SuppressLint("InflateParams")
    private fun showMoreMenuAboveBar(anchorBar: View) {
        val isHd = CallManager.isHdMode.value

        // Build menu content programmatically — a single row with label + checkbox
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(14))
        }
        val label = TextView(this).apply {
            text = "HD video (720p)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val check = CheckBox(this).apply {
            isChecked = isHd
            isClickable = false
            scaleX = 0.9f
            scaleY = 0.9f
        }
        row.addView(label)
        row.addView(check)

        // Wrap in a container that carries the same background as the bottom bar
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xFF151a1e.toInt())
                setStroke(dpToPx(1), 0x2EFFFFFF)
                cornerRadius = dpToPx(16).toFloat()
            }
            addView(row)
        }

        // Measure popup height so we can compute the upward offset
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
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true                                 // focusable so it dismisses on outside tap
        ).apply {
            isOutsideTouchable = true
            elevation = dpToPx(4).toFloat()
        }

        // showAsDropDown is anchor-relative and works correctly regardless of window
        // decoration flags (setDecorFitsSystemWindows, etc.). The offset places the
        // popup just above the anchor bar with a 5dp gap.
        val gap = dpToPx(5)
        popup.showAsDropDown(anchorBar, 0, -(barHeight + popupHeight + gap))

        row.setOnClickListener {
            CallManager.toggleHdMode()
            check.isChecked = CallManager.isHdMode.value
            popup.dismiss()
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private fun formatCalleePhoneForDisplay(rawPhone: String): String {
        val e164Phone = PhoneNumberUtil.formatAsE164(
            rawPhone = rawPhone,
            regionIso = resolvePhoneRegionIso(),
            fallbackE164Phone = FirebaseAuth.getInstance().currentUser?.phoneNumber
        )

        return PhoneNumberUtil.formatForDisplay(e164Phone.ifBlank { rawPhone })
    }

    private fun resolvePhoneRegionIso(): String {
        val telephonyManager = getSystemService(TelephonyManager::class.java)
        return listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            resources.configuration.locales.get(0)?.country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase()
            .orEmpty()
    }

    private fun resolveCalleePhoneIfMissing(receiverId: String, onResolved: (String) -> Unit) {
        if (calleePhone.isNotBlank()) {
            onResolved(formatCalleePhoneForDisplay(calleePhone).ifBlank { "Unknown number" })
            return
        }
        if (receiverId.isBlank()) {
            onResolved("Unknown number")
            return
        }

        lifecycleScope.launch {
            val resolvedPhone = try {
                val snapshot = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(receiverId)
                    .get()
                    .await()

                listOf("phoneNumber", "phone", "mobile")
                    .asSequence()
                    .mapNotNull { key -> snapshot.getString(key) }
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    .orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve callee phone for outgoing call UI", e)
                ""
            }

            calleePhone = resolvedPhone
            onResolved(formatCalleePhoneForDisplay(resolvedPhone).ifBlank { "Unknown number" })
        }
    }

    private fun applyOutgoingLocalPreviewLayout(
        rootVideoCall: View,
        bottomControlBar: View,
        localContainer: FrameLayout,
        btnSwitchCameraPreview: ImageView,
        connected: Boolean,
        animate: Boolean
    ) {
        rootVideoCall.post {
            if (rootVideoCall.width == 0 || rootVideoCall.height == 0) return@post

            val params = (localContainer.layoutParams as? FrameLayout.LayoutParams) ?: return@post
            val targetWidth = if (connected) dpToPx(190) else FrameLayout.LayoutParams.MATCH_PARENT
            val targetHeight = if (connected) dpToPx(320) else FrameLayout.LayoutParams.MATCH_PARENT
            val shownBottomMargin = (bottomControlBar.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin
                ?: (systemBottomInset + dpToPx(18))
            val targetX = if (connected) {
                (rootVideoCall.width - dpToPx(190) - dpToPx(12)).toFloat()
            } else {
                0f
            }
            val targetY = if (connected) {
                (rootVideoCall.height - bottomControlBar.height - shownBottomMargin - dpToPx(320) - dpToPx(10))
                    .toFloat()
                    .coerceAtLeast(dpToPx(20).toFloat())
            } else {
                0f
            }

            val applyState: (Int, Int, Float, Float) -> Unit = { width, height, x, y ->
                params.width = width
                params.height = height
                params.gravity = if (connected) android.view.Gravity.BOTTOM or android.view.Gravity.END else android.view.Gravity.TOP or android.view.Gravity.START
                params.rightMargin = if (connected) dpToPx(12) else 0
                params.bottomMargin = if (connected) shownBottomMargin else 0
                params.topMargin = 0
                params.leftMargin = 0
                localContainer.layoutParams = params
                localContainer.x = x
                localContainer.y = y
                localContainer.scaleX = 1f
                localContainer.scaleY = 1f
            }

            localContainer.animate().cancel()
            btnSwitchCameraPreview.animate().cancel()

            if (!animate || !localPreviewLayoutInitialized) {
                applyState(targetWidth, targetHeight, targetX, targetY)
                localContainer.background = if (connected) {
                    ContextCompat.getDrawable(this, R.drawable.bg_local_video_preview)
                } else {
                    null
                }
                localContainer.elevation = if (connected) dpToPx(16).toFloat() else 0f
                btnSwitchCameraPreview.alpha = if (connected) 1f else 0f
                btnSwitchCameraPreview.isEnabled = connected
                localContainer.invalidateOutline()
                return@post
            }

            val startWidth = localContainer.width
            val startHeight = localContainer.height
            val startX = localContainer.x
            val startY = localContainer.y

            if (connected) {
                localContainer.background = ContextCompat.getDrawable(this, R.drawable.bg_local_video_preview)
            }

            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 360L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    val width = (startWidth + ((targetWidth - startWidth) * progress)).toInt()
                    val height = (startHeight + ((targetHeight - startHeight) * progress)).toInt()
                    val x = startX + ((targetX - startX) * progress)
                    val y = startY + ((targetY - startY) * progress)
                    applyState(width, height, x, y)
                    localContainer.elevation = if (connected) dpToPx(16).toFloat() * progress else 0f
                    localContainer.invalidateOutline()
                }
                start()
            }

            btnSwitchCameraPreview.animate()
                .alpha(if (connected) 1f else 0f)
                .setDuration(220L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            btnSwitchCameraPreview.isEnabled = connected
        }
    }

    private fun configureLockScreenPresentation() {
        CallLockScreenHelper.prepareActivityWindow(this)
        CallLockScreenHelper.pulseScreenWake(this, "$TAG:ActiveCallWake", durationMs = 3_000L)
    }

    private fun readIntent(intent: Intent) {
        callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
        callType = try {
            CallType.valueOf(intent.getStringExtra(EXTRA_CALL_TYPE) ?: "VOICE")
        } catch (_: Exception) { CallType.VOICE }
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""
        contactAvatar = intent.getStringExtra(EXTRA_CONTACT_AVATAR) ?: ""
        autoAccept = intent.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)
    }

    private fun maybeAutoAcceptFromIntent() {
        if (!autoAccept || autoAcceptHandled || callId.isBlank()) return
        autoAcceptHandled = true

        if (isCallAlreadyAccepted(callId)) {
            CallNotificationHelper.cancelIncomingNotification(this)
            return
        }

        val neededPermissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (callType == CallType.VIDEO &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }

        if (neededPermissions.isNotEmpty()) {
            permissionLauncher.launch(neededPermissions.toTypedArray())
            return
        }

        acceptCallFromLaunch()
    }

    private fun ensureCameraPermissionForVideoToggle() {
        val needsCameraPermission = !CallManager.isVideoEnabled.value &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED

        if (!needsCameraPermission) {
            CallManager.toggleVideo()
            return
        }

        pendingCameraEnableAfterPermission = true
        permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun acceptCallFromLaunch() {
        lifecycleScope.launch {
            if (isCallAlreadyAccepted(callId)) {
                CallNotificationHelper.cancelIncomingNotification(this@ActiveCallActivity)
                return@launch
            }

            val stillRinging = CallSignalingRepository.isCallStillRinging(callId)
            if (!stillRinging) {
                CallNotificationHelper.cancelIncomingNotification(this@ActiveCallActivity)
                finish()
                return@launch
            }

            CallNotificationHelper.cancelIncomingNotification(this@ActiveCallActivity)
            CallManager.acceptIncomingCall(this@ActiveCallActivity, callId, callType)
        }
    }

    private fun isCallAlreadyAccepted(targetCallId: String): Boolean {
        val state = CallManager.callState.value
        val currentCallId = CallManager.callData.value?.callId
        if (currentCallId != null && currentCallId != targetCallId) {
            return false
        }

        return state == CallState.ACCEPTED || state == CallState.CONNECTED
    }

    private fun endAndFinish() {
        CallManager.endCall(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.slide_in_left, R.anim.slide_out_right)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    /**
     * Shows the add-participant bottom sheet as an overlay on the call UI.
     * When a user is selected, seamlessly migrates the 1:1 call to a group
     * call without dropping the existing peer connection, then navigates
     * to GroupCallActivity.
     */
    private fun upgradeToGroupCall() {
        val callData = CallManager.callData.value ?: return
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val peerId = if (callData.callerId == myUid) callData.receiverId else callData.callerId
        val peerName = if (callData.callerId == myUid) callData.receiverName else callData.callerName
        val peerAvatar = if (callData.callerId == myUid) callData.receiverAvatar else callData.callerAvatar

        // Generate the group call ID up front so it's the same everywhere
        val groupCallId = java.util.UUID.randomUUID().toString()

        val bottomSheet = AddParticipantBottomSheet.newInstance(
            excludeUserIds = listOf(myUid, peerId)
        )
        bottomSheet.onUserSelected = { selectedUser ->
            // Detach the live WebRTC client (keeps peer connection alive)
            val detached = CallManager.detachForGroupUpgrade(groupCallId)
            if (detached != null) {
                // Migrate to group call with the existing peer + newly selected user
                GroupCallManager.migrateFromOneToOneCall(
                    context = this,
                    detachedInfo = detached,
                    groupCallId = groupCallId,
                    existingPeerId = peerId,
                    existingPeerName = peerName,
                    existingPeerAvatar = peerAvatar,
                    newParticipantIds = listOf(selectedUser.id),
                    newParticipantNames = mapOf(selectedUser.id to selectedUser.username),
                    newParticipantAvatars = mapOf(selectedUser.id to selectedUser.profileImageUrl)
                )

                // Navigate to GroupCallActivity — no transition animation for seamless feel
                val intent = GroupCallActivity.createIntent(
                    context = this,
                    groupCallId = groupCallId,
                    callType = detached.callData.callType(),
                    isJoining = false
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                // Finish with no animation so user doesn't see a screen transition
                window.decorView.post {
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            }
        }
        bottomSheet.show(supportFragmentManager, AddParticipantBottomSheet.TAG)
    }

    private fun enterPipIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        if (isInPictureInPictureMode) {
            // Snap chrome off-screen using the same animation path used by tap-to-hide
            pipChromeCallback?.invoke(true, true)
                pipLocalContainerView?.visibility =
                    if (CallManager.isVideoEnabled.value) View.VISIBLE else View.GONE
            pipLocalContainerView?.scaleX = pipLocalPreviewScale
            pipLocalContainerView?.scaleY = pipLocalPreviewScale
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Restore local preview, then restore chrome to whatever state it was in
            pipLocalContainerView?.visibility =
                if (CallManager.isVideoEnabled.value) View.VISIBLE else View.GONE
            pipChromeCallback?.invoke(controlsHidden, true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (CallManager.callMode.value == CallMode.VIDEO && CallManager.callState.value == CallState.CONNECTED) {
            enterPipIfSupported()
        }
    }

    override fun onStop() {
        super.onStop()
        // Notify adaptive video controller: reduce resolution/bitrate/FPS in background
        // to save bandwidth and battery while the user isn't looking at the video.
        CallManager.setAppInBackground(true)
    }

    override fun onStart() {
        super.onStart()
        // Restore full quality when the user returns to the call screen
        CallManager.setAppInBackground(false)
    }

    override fun onDestroy() {
        remoteVideoView?.let { renderer: SurfaceViewRenderer ->
            currentRemoteTrack?.removeSink(renderer)
        }
        localVideoView?.let { renderer: VideoTextureViewRenderer ->
            currentLocalTrack?.removeSink(renderer)
        }
        remoteVideoView?.release()
        currentRemoteTrack = null
        currentLocalTrack = null
        remoteVideoView = null
        localVideoView = null
        eglBase?.release()
        eglBase = null
        super.onDestroy()
    }
}
