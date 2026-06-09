package com.glyph.glyph_v3.ui.camera

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.glyph.glyph_v3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * In-app camera activity with WhatsApp-like experience.
 * Supports Photo, Video, and Video Note modes.
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_OTHER_USER_ID = "extra_other_user_id"
        const val EXTRA_OTHER_USERNAME = "extra_other_username"
        const val EXTRA_OTHER_USER_AVATAR = "extra_other_user_avatar"

        const val RESULT_MEDIA_URI = "result_media_uri"
        const val RESULT_MEDIA_TYPE = "result_media_type"  // "image/*" or "video/*"
        const val RESULT_IS_VIDEO_NOTE = "result_is_video_note"
        const val RESULT_CAPTION = "result_caption"

        fun newIntent(
            context: Context,
            chatId: String,
            otherUserId: String,
            otherUsername: String,
            otherUserAvatar: String
        ): Intent {
            return Intent(context, CameraActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_OTHER_USER_ID, otherUserId)
                putExtra(EXTRA_OTHER_USERNAME, otherUsername)
                putExtra(EXTRA_OTHER_USER_AVATAR, otherUserAvatar)
            }
        }
    }

    // Camera mode
    enum class CameraMode { VIDEO, PHOTO, VIDEO_NOTE }

    // Flash mode
    enum class FlashMode { OFF, ON, AUTO }

    // Views
    private lateinit var previewView: PreviewView
    private lateinit var btnClose: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var tvTimer: TextView
    private lateinit var btnGallery: ImageButton
    private lateinit var btnEffects: ImageButton
    private lateinit var shutterContainer: View
    private lateinit var shutterInner: View
    private lateinit var shutterOuterRing: View
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var modeVideo: TextView
    private lateinit var modePhoto: TextView
    private lateinit var modeVideoNote: TextView
    private lateinit var recentMediaStrip: RecyclerView
    private lateinit var videoNoteOverlay: View
    private lateinit var videoNoteProgress: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var recordingProgress: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var focusIndicator: View
    private lateinit var lensSelector: LinearLayout
    private lateinit var btnHd: ImageButton

    // CameraX
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    // State
    private var currentMode: CameraMode = CameraMode.PHOTO
    private var currentFlash: FlashMode = FlashMode.OFF
    private var isHdEnabled: Boolean = true
    private var isUsingFrontCamera: Boolean = false
    private var isRecording: Boolean = false
    private var recordingStartTime: Long = 0L
    private var rotateClockwise: Boolean = true
    private val timerHandler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null

    // Physical lens state
    data class PhysicalLens(
        val zoomRatio: Float,  // The zoom ratio to apply via CameraX
        val label: String      // e.g. "0.5x", "1x", "2x", "3x"
    )
    private var physicalLenses = listOf<PhysicalLens>()
    private var currentLensIndex: Int = 0
    private var focusIndicatorRunnable: Runnable? = null
    private var isFocusLocked: Boolean = false
    private var focusLockX: Float = 0f
    private var focusLockY: Float = 0f

    // Recent media
    private val recentMediaItems = mutableListOf<RecentMediaItem>()
    private var recentMediaAdapter: RecentMediaAdapter? = null

    // Orientation handling
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var currentUIOrientation: Int = 0 // 0, 90, 180, 270

    // Gallery picker
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val mimeType = contentResolver.getType(uri) ?: "image/*"
            launchPreview(uri, mimeType, isVideoNote = false)
        }
    }

    // Permission
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startCamera()
            loadRecentMedia()
        } else {
            Toast.makeText(this, "Camera & audio permissions are required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        setContentView(R.layout.activity_camera)

        cameraExecutor = Executors.newSingleThreadExecutor()

        bindViews()

        // Set video note progress size to be slightly OUTSIDE the circular cutout
        // Cutout is 92% of screen width. Progress bar is 96% to sit just outside.
        val screenWidth = resources.displayMetrics.widthPixels
        val indicatorSize = (screenWidth * 0.96f).toInt()
        videoNoteProgress.indicatorSize = indicatorSize

        setupListeners()
        setupModeSelector()
        setupRecentMediaStrip()
        setupTouchToFocus()
        setupLensSelector()
        setupWindowInsets()
        setupOrientationListener()

        if (hasRequiredPermissions()) {
            startCamera()
            loadRecentMedia()
        } else {
            requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener?.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener?.disable()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        btnClose = findViewById(R.id.btnClose)
        btnFlash = findViewById(R.id.btnFlash)
        tvTimer = findViewById(R.id.tvTimer)
        btnGallery = findViewById(R.id.btnGallery)
        btnEffects = findViewById(R.id.btnEffects)
        shutterContainer = findViewById(R.id.shutterContainer)
        shutterInner = findViewById(R.id.shutterInner)
        shutterOuterRing = findViewById(R.id.shutterOuterRing)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        modeVideo = findViewById(R.id.modeVideo)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideoNote = findViewById(R.id.modeVideoNote)
        recentMediaStrip = findViewById(R.id.recentMediaStrip)
        videoNoteOverlay = findViewById(R.id.videoNoteOverlay)
        videoNoteProgress = findViewById(R.id.videoNoteProgress)
        recordingProgress = findViewById(R.id.recordingProgress)
        focusIndicator = findViewById(R.id.focusIndicator)
        lensSelector = findViewById(R.id.lensSelector)
        btnHd = findViewById(R.id.btnHd)
        updateHdIcon()
    }

    private fun setupListeners() {
        btnClose.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (isRecording) {
                stopRecording()
            }
            finish()
        }

        btnFlash.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            cycleFlashMode()
        }

        btnHd.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            toggleHdMode()
        }

        btnSwitchCamera.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            isUsingFrontCamera = !isUsingFrontCamera
            
            // Reset lens to primary when switching cameras
            currentLensIndex = physicalLenses.indexOfFirst { lens -> lens.label == "1x" }.coerceAtLeast(0)
            unlockFocus()
            
            // Show/hide lens selector
            lensSelector.visibility = if (isUsingFrontCamera || physicalLenses.size <= 1) View.GONE else View.VISIBLE
            
            // Animate rotation - alternate between clockwise and anti-clockwise
            val rotationDegrees = if (rotateClockwise) 180f else -180f
            rotateClockwise = !rotateClockwise
            
            it.animate()
                .rotationBy(rotationDegrees)
                .setDuration(450)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            startCamera()
        }

        btnGallery.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            galleryLauncher.launch("*/*")
        }

        btnEffects.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            Toast.makeText(this, "Effects coming soon", Toast.LENGTH_SHORT).show()
        }

        // Shutter: tap = photo, long press = video recording
        setupShutterButton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupShutterButton() {
        var longPressStarted = false
        var longPressHandler = Handler(Looper.getMainLooper())
        val longPressThreshold = 300L

        shutterContainer.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressStarted = false
                    // Scale down animation
                    shutterInner.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start()
                    
                    if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.VIDEO_NOTE) {
                        // In video/video-note mode, tap starts/stops recording
                        // No long-press needed
                    } else {
                        // In photo mode, long press starts video recording
                        longPressHandler.postDelayed({
                            longPressStarted = true
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            startRecording()
                        }, longPressThreshold)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    shutterInner.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    longPressHandler.removeCallbacksAndMessages(null)

                    if (event.action == MotionEvent.ACTION_UP) {
                        if (currentMode == CameraMode.PHOTO && !longPressStarted) {
                            // Tap in photo mode → capture photo
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            capturePhoto()
                        } else if (currentMode == CameraMode.PHOTO && longPressStarted && isRecording) {
                            // Release after long press in photo mode → stop recording
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            stopRecording()
                        } else if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.VIDEO_NOTE) {
                            // Tap in video/video-note mode → toggle recording
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (isRecording) {
                                stopRecording()
                            } else {
                                startRecording()
                            }
                        }
                    } else {
                        // ACTION_CANCEL
                        if (isRecording && currentMode == CameraMode.PHOTO) {
                            stopRecording()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupModeSelector() {
        selectMode(CameraMode.PHOTO)

        modeVideo.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            selectMode(CameraMode.VIDEO) 
        }
        modePhoto.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            selectMode(CameraMode.PHOTO) 
        }
        modeVideoNote.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            selectMode(CameraMode.VIDEO_NOTE) 
        }
    }

    private fun selectMode(mode: CameraMode) {
        val previousMode = currentMode
        currentMode = mode

        // Always clear focus lock on mode change
        unlockFocus()

        // Restore back camera when switching away from video note
        if (previousMode == CameraMode.VIDEO_NOTE && mode != CameraMode.VIDEO_NOTE && isUsingFrontCamera) {
            isUsingFrontCamera = false
            resetPreviewTransform()  // Reset preview framing when leaving video note mode
        }

        // Reset all modes
        listOf(modeVideo, modePhoto, modeVideoNote).forEach { tv ->
            tv.setBackgroundResource(android.R.color.transparent)
            tv.setTextColor(Color.parseColor("#80FFFFFF"))
        }

        // Highlight selected
        val selectedView = when (mode) {
            CameraMode.VIDEO -> modeVideo
            CameraMode.PHOTO -> modePhoto
            CameraMode.VIDEO_NOTE -> modeVideoNote
        }
        selectedView.setBackgroundResource(R.drawable.bg_mode_active)
        selectedView.setTextColor(Color.WHITE)

        // Update UI for mode
        when (mode) {
            CameraMode.PHOTO -> {
                tvTimer.visibility = View.GONE
                btnGallery.visibility = View.VISIBLE
                videoNoteOverlay.visibility = View.GONE
                animateShutterSize(62)
                // Show lens selector for back camera
                lensSelector.visibility = if (!isUsingFrontCamera && physicalLenses.size > 1) View.VISIBLE else View.GONE
                // Show recent media strip in photo mode
                if (recentMediaItems.isNotEmpty()) {
                    recentMediaStrip.visibility = View.VISIBLE
                }
            }
            CameraMode.VIDEO -> {
                tvTimer.visibility = View.VISIBLE
                tvTimer.text = "00:00"
                btnGallery.visibility = View.VISIBLE
                videoNoteOverlay.visibility = View.GONE
                animateShutterSize(46)
                // Show lens selector for back camera
                lensSelector.visibility = if (!isUsingFrontCamera && physicalLenses.size > 1) View.VISIBLE else View.GONE
                recentMediaStrip.visibility = View.GONE
            }
            CameraMode.VIDEO_NOTE -> {
                tvTimer.visibility = View.VISIBLE
                tvTimer.text = "00:00"
                btnGallery.visibility = View.GONE
                videoNoteOverlay.visibility = View.VISIBLE
                // Always hide lens selector for video note (front camera)
                lensSelector.visibility = View.GONE
                // Switch to front camera for video notes
                if (!isUsingFrontCamera) {
                    isUsingFrontCamera = true
                }
                setupVideoNoteOverlay()
                animateShutterSize(62)
                recentMediaStrip.visibility = View.GONE
            }
        }

        // Rebind camera with appropriate use cases
        startCamera()
    }

    private fun animateShutterSize(targetSizeDp: Int) {
        val targetPx = (targetSizeDp * resources.displayMetrics.density).toInt()
        val currentSize = shutterInner.width
        
        if (currentSize <= 0 || currentSize == targetPx) {
            val params = shutterInner.layoutParams
            params.width = targetPx
            params.height = targetPx
            shutterInner.layoutParams = params
            return
        }

        val animator = android.animation.ValueAnimator.ofInt(currentSize, targetPx)
        animator.duration = 200
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val animatedValue = animation.animatedValue as Int
            val params = shutterInner.layoutParams
            params.width = animatedValue
            params.height = animatedValue
            shutterInner.layoutParams = params
        }
        animator.start()
    }

    private fun setupVideoNoteOverlay() {
        // Create a custom view overlay that shows a circular mask
        videoNoteOverlay.visibility = View.VISIBLE
        videoNoteOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        videoNoteOverlay.background = VideoNoteOverlayDrawable()
    }

    /**
     * Apply enhanced framing for video note recording (front camera only).
     * Zooms out to 0.72x and shifts preview upward by 16% to naturally frame
     * the user's face within the circular recording window.
     */
    private fun applyVideoNoteFraming() {
        // Use minimum hardware zoom ratio (usually 1.0x)
        val minZoom = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
        camera?.cameraControl?.setZoomRatio(minZoom)

        // Force a square aspect ratio for the preview view so that what the user sees
        // matches the circular crop of the final 1:1 recording. 
        previewView.post {
            val params = previewView.layoutParams
            val width = previewView.width
            // Use 1.5x width for height. 
            // This ensures a vertical buffer so the circle is always fully covered.
            val previewHeight = (width * 1.8f).toInt()
            params.height = previewHeight
            previewView.layoutParams = params
            
            // FIXED: Keep the circular cutout vertically centered (50% screen height)
            val screenHeight = (previewView.parent as View).height
            val circleCenterY = screenHeight / 2f
            
            // Move the video contents UPWARDS inside the centered cutout.
            // 1. Center the view normally first (at 50% of its height)
            val previewTop = circleCenterY - (previewHeight * 0.50f)
            previewView.y = previewTop
            
            // 2. PAN the video inside the cutout by translating the view UP
            // Negative translationY slides the content up while the circle window stays at circleCenterY.
            // Using -350px as a starting point to frame the face better.
            previewView.translationY = -350f

            // Reset scaling and translations since we are now moving the View itself
            previewView.scaleX = 1.0f
            previewView.scaleY = 1.0f
            previewView.translationY = 0f
            
        }
    }

    /**
     * Reset preview transformations to default (for non-video-note modes).
     */
    private fun resetPreviewTransform() {
        val params = previewView.layoutParams
        params.height = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        previewView.layoutParams = params
        previewView.y = 0f
        
        previewView.scaleX = 1.0f
        previewView.scaleY = 1.0f
        previewView.translationY = 0f
    }

    private fun setupRecentMediaStrip() {
        recentMediaAdapter = RecentMediaAdapter { item ->
            launchPreview(item.uri, item.mimeType, isVideoNote = false)
        }
        recentMediaStrip.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        recentMediaStrip.adapter = recentMediaAdapter
    }

    private fun setupWindowInsets() {
        val bottomControls = findViewById<View>(R.id.bottomControls)
        val topBar = findViewById<View>(R.id.topBar)

        ViewCompat.setOnApplyWindowInsetsListener(bottomControls) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Base padding is 20dp, add system bar bottom inset
            val basePaddingBottom = (20 * resources.displayMetrics.density).toInt()
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemBars.bottom + basePaddingBottom)
            insets
        }

        topBar?.let { tb ->
            ViewCompat.setOnApplyWindowInsetsListener(tb) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                // Add 16dp of safe padding on top of the system bars (status bar)
                val topPadding = systemBars.top + (16 * resources.displayMetrics.density).toInt()
                v.setPadding(v.paddingLeft, topPadding, v.paddingRight, v.paddingBottom)
                insets
            }
        }
    }

    // --- Camera Operations ---

    private fun hasRequiredPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && audioGranted
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Unbind all existing use cases
                provider.unbindAll()

                // Always use default back/front camera (CameraX manages physical lens switching via zoom)
                val cameraSelector = if (isUsingFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val quality = if (isHdEnabled) Quality.HD else Quality.SD

                when (currentMode) {
                    CameraMode.PHOTO -> {
                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setFlashMode(getImageCaptureFlashMode())
                            .build()

                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(quality))
                            .build()
                        videoCapture = VideoCapture.withOutput(recorder)

                        camera = provider.bindToLifecycle(
                            this, cameraSelector, preview, imageCapture, videoCapture
                        )
                    }
                    CameraMode.VIDEO, CameraMode.VIDEO_NOTE -> {
                        val recorder = Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(quality))
                            .build()
                        videoCapture = VideoCapture.withOutput(recorder)
                        imageCapture = null

                        camera = provider.bindToLifecycle(
                            this, cameraSelector, preview, videoCapture
                        )
                    }
                }

                // Apply flash/torch
                updateFlashState()

                // Detect lenses on first run (after camera is bound so we can query zoom range)
                if (physicalLenses.isEmpty() && !isUsingFrontCamera) {
                    detectPhysicalLenses()
                }

                // Apply the selected lens zoom ratio
                if (!isUsingFrontCamera && physicalLenses.isNotEmpty() && currentLensIndex in physicalLenses.indices) {
                    val targetZoom = physicalLenses[currentLensIndex].zoomRatio
                    camera?.cameraControl?.setZoomRatio(targetZoom)
                }

                // For video note (front camera), apply enhanced framing
                if (currentMode == CameraMode.VIDEO_NOTE && isUsingFrontCamera) {
                    applyVideoNoteFraming()
                } else {
                    resetPreviewTransform()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Detect available lens zoom ratios by querying the bound camera's zoom range
     * and the physical cameras behind the logical camera. Uses zoom-ratio-based
     * switching (CameraX setZoomRatio) rather than camera-ID-based rebinding.
     */
    private fun detectPhysicalLenses() {
        val cam = camera ?: return
        val lenses = mutableListOf<PhysicalLens>()

        try {
            val zoomState = cam.cameraInfo.zoomState.value
            val minZoom = zoomState?.minZoomRatio ?: 1.0f
            val maxZoom = zoomState?.maxZoomRatio ?: 1.0f


            // Try to detect physical lenses via Camera2 interop for richer info
            val physicalFocalLengths = mutableListOf<Float>()
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val camera2Info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
                val logicalId = camera2Info.cameraId

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val logicalChars = cameraManager.getCameraCharacteristics(logicalId)
                    val physicalIds = logicalChars.physicalCameraIds

                    if (physicalIds.isNotEmpty()) {
                        for (physId in physicalIds) {
                            try {
                                val physChars = cameraManager.getCameraCharacteristics(physId)
                                val focalLengths = physChars.get(
                                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                                )
                                focalLengths?.firstOrNull()?.let { physicalFocalLengths.add(it) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to read physical camera $physId", e)
                            }
                        }
                        physicalFocalLengths.sort()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Camera2 interop unavailable, using zoom range only", e)
            }

            if (physicalFocalLengths.size >= 2) {
                // We have physical lens info — map focal lengths to zoom ratios
                // The main (1x) lens is typically the one closest to ~4-5mm
                val mainFocal = physicalFocalLengths.firstOrNull { it in 3.0f..7.0f }
                    ?: physicalFocalLengths[physicalFocalLengths.size / 2]

                for (focal in physicalFocalLengths) {
                    val ratio = focal / mainFocal
                    // Clamp to actual zoom range
                    val clampedRatio = ratio.coerceIn(minZoom, maxZoom)

                    val label = when {
                        clampedRatio < 0.55f -> "0.5x"
                        clampedRatio < 0.85f -> String.format(Locale.US, "%.1fx", clampedRatio)
                        clampedRatio in 0.85f..1.15f -> "1x"
                        else -> String.format(Locale.US, "%.0fx", clampedRatio)
                    }
                    lenses.add(PhysicalLens(clampedRatio, label))
                }
            } else {
                // Fallback: build lenses from zoom range
                // Always include 1x
                lenses.add(PhysicalLens(1.0f, "1x"))

                // Ultra-wide if min zoom < 1
                if (minZoom < 0.9f) {
                    val uwLabel = String.format(Locale.US, "%.1fx", minZoom)
                    lenses.add(0, PhysicalLens(minZoom, uwLabel))
                }

                // Telephoto options
                if (maxZoom >= 1.9f) {
                    lenses.add(PhysicalLens(2.0f, "2x"))
                }
                if (maxZoom >= 4.5f) {
                    lenses.add(PhysicalLens(5.0f, "5x"))
                }
                if (maxZoom >= 9.0f) {
                    lenses.add(PhysicalLens(10.0f, "10x"))
                }
            }

            // Deduplicate by label
            physicalLenses = lenses.distinctBy { it.label }

            // Default to 1x
            currentLensIndex = physicalLenses.indexOfFirst { it.label == "1x" }.coerceAtLeast(0)


        } catch (e: Exception) {
            Log.e(TAG, "Error detecting physical lenses", e)
            physicalLenses = listOf(PhysicalLens(1.0f, "1x"))
            currentLensIndex = 0
        }

        // Build the lens selector UI dynamically
        runOnUiThread { buildLensSelectorUI() }
    }

    // --- Touch-to-Focus with AE/AF Lock ---

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOrientationListener() {
        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val newOrientation = when (orientation) {
                    in 45..134 -> 270
                    in 135..224 -> 180
                    in 225..314 -> 90
                    else -> 0
                }

                if (newOrientation != currentUIOrientation) {
                    currentUIOrientation = newOrientation
                    rotateUI(newOrientation)
                    updateCaptureRotation(newOrientation)
                }
            }
        }
    }

    private fun rotateUI(rotation: Int) {
        // WhatsApp style: keep layout but rotate the icon views
        val rotationValue = when (rotation) {
            0 -> 0f
            90 -> 90f
            180 -> 180f
            270 -> 270f
            else -> 0f
        }

        val viewsToRotate = mutableListOf<View>(
            btnClose, btnFlash, btnHd, btnGallery, btnEffects, btnSwitchCamera, tvTimer
        )

        // Also rotate lens selector children (0.5x, 1x)
        for (i in 0 until lensSelector.childCount) {
            lensSelector.getChildAt(i)?.let { viewsToRotate.add(it) }
        }

        viewsToRotate.forEach { view ->
            view.animate()
                .rotation(rotationValue)
                .setDuration(400)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun updateCaptureRotation(rotation: Int) {
        val targetRotation = when (rotation) {
            0 -> android.view.Surface.ROTATION_0
            270 -> android.view.Surface.ROTATION_270 // UI 270 (Landscape Right) -> Surface 270
            180 -> android.view.Surface.ROTATION_180
            90 -> android.view.Surface.ROTATION_90   // UI 90 (Landscape Left) -> Surface 90
            else -> android.view.Surface.ROTATION_0
        }
        
        imageCapture?.targetRotation = targetRotation
        videoCapture?.targetRotation = targetRotation
    }

    private fun setupTouchToFocus() {
        var gestureDownTime = 0L
        val longPressThreshold = 400L
        var longPressDetected = false
        val longPressHandler = Handler(Looper.getMainLooper())

        previewView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureDownTime = System.currentTimeMillis()
                    longPressDetected = false
                    val x = event.x
                    val y = event.y

                    // Schedule long-press detection for focus lock
                    longPressHandler.postDelayed({
                        longPressDetected = true
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        lockFocusAtPoint(x, y)
                    }, longPressThreshold)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    val x = event.x
                    val y = event.y

                    if (!longPressDetected) {
                        // Short tap
                        if (isFocusLocked) {
                            // Second tap while locked → unlock
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            unlockFocus()
                        } else {
                            // Normal tap → auto-focus on point (transient)
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            focusOnPoint(x, y)
                            showFocusIndicator(x, y, locked = false)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacksAndMessages(null)
                    true
                }
                else -> false
            }
        }
    }

    private fun focusOnPoint(x: Float, y: Float) {
        val cam = camera ?: return

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)

        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun lockFocusAtPoint(x: Float, y: Float) {
        val cam = camera ?: return

        isFocusLocked = true
        focusLockX = x
        focusLockY = y

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(x, y)

        // Lock focus indefinitely (no auto-cancel)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()

        cam.cameraControl.startFocusAndMetering(action)
        showFocusIndicator(x, y, locked = true)
    }

    private fun unlockFocus() {
        isFocusLocked = false
        camera?.cameraControl?.cancelFocusAndMetering()

        // Cancel pulse animation if running
        (focusIndicator.tag as? ObjectAnimator)?.cancel()
        focusIndicator.tag = null

        // Hide the lock indicator
        focusIndicatorRunnable?.let { timerHandler.removeCallbacks(it) }
        focusIndicator.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { focusIndicator.visibility = View.GONE }
            .start()
    }

    private fun showFocusIndicator(x: Float, y: Float, locked: Boolean) {
        // Cancel any pending hide
        focusIndicatorRunnable?.let { timerHandler.removeCallbacks(it) }

        val indicatorSize = (64 * resources.displayMetrics.density).toInt()
        val halfSize = indicatorSize / 2

        // Position centered on tap point
        focusIndicator.translationX = x - halfSize
        focusIndicator.translationY = y - halfSize

        // Set appearance based on lock state
        if (locked) {
            focusIndicator.setBackgroundResource(R.drawable.bg_focus_indicator_locked)
        } else {
            focusIndicator.setBackgroundResource(R.drawable.bg_focus_indicator)
        }

        // Animate in: scale down from large then settle
        focusIndicator.scaleX = 1.4f
        focusIndicator.scaleY = 1.4f
        focusIndicator.alpha = 1f
        focusIndicator.visibility = View.VISIBLE

        focusIndicator.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (locked) {
            // Locked: indicator stays visible indefinitely (pulsing alpha)
            val pulseAnim = ObjectAnimator.ofFloat(focusIndicator, "alpha", 1f, 0.5f, 1f)
            pulseAnim.duration = 1500
            pulseAnim.repeatCount = ObjectAnimator.INFINITE
            pulseAnim.start()
            focusIndicator.tag = pulseAnim  // store to cancel later
        } else {
            // Stop any pulse animation
            (focusIndicator.tag as? ObjectAnimator)?.cancel()
            focusIndicator.tag = null

            // Fade out after 1.5 seconds
            focusIndicatorRunnable = Runnable {
                focusIndicator.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction { focusIndicator.visibility = View.GONE }
                    .start()
            }
            timerHandler.postDelayed(focusIndicatorRunnable!!, 1500)
        }
    }

    // --- Lens Selection ---

    private fun setupLensSelector() {
        // Lens UI is built dynamically after camera detection in detectPhysicalLenses()
    }

    /**
     * Dynamically build lens selector buttons from the detected physical lenses.
     */
    private fun buildLensSelectorUI() {
        lensSelector.removeAllViews()

        if (physicalLenses.size <= 1 || isUsingFrontCamera) {
            lensSelector.visibility = View.GONE
            return
        }

        lensSelector.visibility = View.VISIBLE

        val density = resources.displayMetrics.density
        val btnSizePx = (36 * density).toInt()
        val marginPx = (3 * density).toInt()

        physicalLenses.forEachIndexed { index, lens ->
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(btnSizePx, btnSizePx).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
                gravity = android.view.Gravity.CENTER
                text = lens.label
                textSize = 12f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(if (index == currentLensIndex) Color.WHITE else Color.parseColor("#99FFFFFF"))
                setBackgroundResource(if (index == currentLensIndex) R.drawable.bg_lens_active else R.drawable.bg_lens_inactive)
                tag = index

                setOnClickListener { view ->
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    selectLens(view.tag as Int)
                }
            }
            lensSelector.addView(tv)
        }
    }

    private fun selectLens(lensIndex: Int) {
        if (lensIndex == currentLensIndex) return
        if (lensIndex !in physicalLenses.indices) return

        currentLensIndex = lensIndex
        unlockFocus()
        highlightSelectedLens()

        // Smooth crossfade transition while switching zoom
        previewView.animate()
            .alpha(0.7f)
            .setDuration(100)
            .withEndAction {
                // Switch lens via zoom ratio — instant, no camera rebinding
                val targetZoom = physicalLenses[lensIndex].zoomRatio
                camera?.cameraControl?.setZoomRatio(targetZoom)
                previewView.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun highlightSelectedLens() {
        for (i in 0 until lensSelector.childCount) {
            val child = lensSelector.getChildAt(i) as? TextView ?: continue
            val index = child.tag as? Int ?: continue

            if (index == currentLensIndex) {
                child.setBackgroundResource(R.drawable.bg_lens_active)
                child.setTextColor(Color.WHITE)
                // Scale bounce for selected
                child.animate()
                    .scaleX(1.15f).scaleY(1.15f)
                    .setDuration(150)
                    .setInterpolator(OvershootInterpolator())
                    .withEndAction {
                        child.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    }
                    .start()
            } else {
                child.setBackgroundResource(R.drawable.bg_lens_inactive)
                child.setTextColor(Color.parseColor("#99FFFFFF"))
                child.scaleX = 1f
                child.scaleY = 1f
            }
        }
    }

    private fun getImageCaptureFlashMode(): Int {
        return when (currentFlash) {
            FlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
            FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
        }
    }

    private fun cycleFlashMode() {
        currentFlash = when (currentFlash) {
            FlashMode.OFF -> FlashMode.ON
            FlashMode.ON -> FlashMode.AUTO
            FlashMode.AUTO -> FlashMode.OFF
        }
        updateFlashState()
        updateFlashIcon()
    }

    private fun toggleHdMode() {
        isHdEnabled = !isHdEnabled
        updateHdIcon()
        
        // Quality change requires rebinding use cases
        if (!isRecording) {
            startCamera()
        } else {
            Toast.makeText(this, "Stop recording to change resolution", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHdIcon() {
        if (isHdEnabled) {
            btnHd.setImageResource(R.drawable.ic_hd)
            btnHd.alpha = 1.0f
        } else {
            btnHd.setImageResource(R.drawable.ic_sd)
            btnHd.alpha = 1.0f
        }
        btnHd.setColorFilter(Color.WHITE)
    }

    private fun updateFlashState() {
        imageCapture?.flashMode = getImageCaptureFlashMode()

        // For video mode, use torch
        if (currentMode == CameraMode.VIDEO || currentMode == CameraMode.VIDEO_NOTE) {
            camera?.cameraControl?.enableTorch(currentFlash == FlashMode.ON)
        }
    }

    private fun updateFlashIcon() {
        val iconRes = when (currentFlash) {
            FlashMode.OFF -> R.drawable.ic_camera_flash_off
            FlashMode.ON -> R.drawable.ic_camera_flash_on
            FlashMode.AUTO -> R.drawable.ic_camera_flash_auto
        }
        btnFlash.setImageResource(iconRes)

        // Animate icon change
        btnFlash.animate()
            .scaleX(0.8f).scaleY(0.8f)
            .setDuration(100)
            .withEndAction {
                btnFlash.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
            .start()
    }

    // --- Photo Capture ---

    private fun capturePhoto() {
        val capture = imageCapture ?: return

        // Flash animation
        animateShutterFlash()

        val outputFile = createTempMediaFile("jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(outputFile)
                    launchPreview(uri, "image/jpeg", isVideoNote = false)
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exc)
                    Toast.makeText(this@CameraActivity, "Failed to capture photo", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun animateShutterFlash() {
        val flashOverlay = View(this).apply {
            setBackgroundColor(Color.WHITE)
            alpha = 0f
        }
        val decorView = window.decorView as ViewGroup
        decorView.addView(flashOverlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        flashOverlay.animate()
            .alpha(0.7f)
            .setDuration(50)
            .withEndAction {
                flashOverlay.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .withEndAction { decorView.removeView(flashOverlay) }
                    .start()
            }
            .start()
    }

    // --- Video Recording ---

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val videoCapture = videoCapture ?: return
        if (isRecording) return

        val outputFile = createTempMediaFile("mp4")
        val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()

        activeRecording = videoCapture.output
            .prepareRecording(this, fileOutputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(this@CameraActivity, Manifest.permission.RECORD_AUDIO) 
                    == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        onRecordingStarted()
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        onRecordingStopped()
                        if (!event.hasError()) {
                            val uri = Uri.fromFile(outputFile)
                            launchPreview(uri, "video/mp4", isVideoNote = currentMode == CameraMode.VIDEO_NOTE)
                        } else {
                            Log.e(TAG, "Video recording error: ${event.error}")
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    private fun onRecordingStarted() {
        recordingStartTime = SystemClock.elapsedRealtime()

        // Update UI
        tvTimer.visibility = View.VISIBLE
        tvTimer.text = "00:00"

        // Animate shutter to recording state
        shutterInner.animate()
            .scaleX(0.5f).scaleY(0.5f)
            .setDuration(200)
            .start()
        
        // Change inner to red rounded square
        val recordBg = resources.getDrawable(R.drawable.bg_shutter_recording, theme)
        shutterInner.background = recordBg

        // Show recording progress
        recordingProgress.visibility = View.VISIBLE
        recordingProgress.progress = 0

        if (currentMode == CameraMode.VIDEO_NOTE) {
            videoNoteProgress.visibility = View.VISIBLE
            videoNoteProgress.progress = 0
        }

        // Haptic feedback
        shutterContainer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        // Start timer
        timerRunnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - recordingStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                tvTimer.text = String.format(Locale.US, "%02d:%02d", minutes, seconds)

                // Update progress ring (max 60 seconds for video note)
                if (currentMode == CameraMode.VIDEO_NOTE) {
                    val progressMs = elapsed.toInt().coerceAtMost(60000)
                    videoNoteProgress.setProgressCompat(progressMs, true)
                    
                    // Also update the shutter recording progress if needed
                    recordingProgress.progress = (progressMs / 600).coerceAtMost(100)

                    if (elapsed >= 60000) {
                        stopRecording()
                        return
                    }
                }

                timerHandler.postDelayed(this, 16)
            }
        }
        timerHandler.post(timerRunnable!!)
    }

    private fun onRecordingStopped() {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null

        // Reset shutter
        shutterInner.animate()
            .scaleX(1f).scaleY(1f)
            .setDuration(200)
            .start()
        shutterInner.setBackgroundResource(R.drawable.bg_shutter_inner_circle)
        
        recordingProgress.visibility = View.GONE
        videoNoteProgress.visibility = View.GONE

        if (currentMode == CameraMode.PHOTO) {
            tvTimer.visibility = View.GONE
        }
    }

    // Preview result launcher
    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val mediaUri = data?.getStringExtra(RESULT_MEDIA_URI)
            val mediaType = data?.getStringExtra(RESULT_MEDIA_TYPE)
            val isVideoNote = data?.getBooleanExtra(RESULT_IS_VIDEO_NOTE, false) ?: false
            val caption = data?.getStringExtra(RESULT_CAPTION) ?: ""

            val resultIntent = Intent().apply {
                putExtra(RESULT_MEDIA_URI, mediaUri)
                putExtra(RESULT_MEDIA_TYPE, mediaType)
                putExtra(RESULT_IS_VIDEO_NOTE, isVideoNote)
                putExtra(RESULT_CAPTION, caption)
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

    // --- Preview Launch ---

    private fun launchPreview(uri: Uri, mimeType: String, isVideoNote: Boolean) {
        val intent = Intent(this, CameraPreviewActivity::class.java).apply {
            putExtra(CameraPreviewActivity.EXTRA_MEDIA_URI, uri.toString())
            putExtra(CameraPreviewActivity.EXTRA_MEDIA_TYPE, mimeType)
            putExtra(CameraPreviewActivity.EXTRA_IS_VIDEO_NOTE, isVideoNote)
            // Forward chat info
            putExtra(EXTRA_CHAT_ID, this@CameraActivity.intent.getStringExtra(EXTRA_CHAT_ID))
            putExtra(EXTRA_OTHER_USER_ID, this@CameraActivity.intent.getStringExtra(EXTRA_OTHER_USER_ID))
            putExtra(EXTRA_OTHER_USERNAME, this@CameraActivity.intent.getStringExtra(EXTRA_OTHER_USERNAME))
            putExtra(EXTRA_OTHER_USER_AVATAR, this@CameraActivity.intent.getStringExtra(EXTRA_OTHER_USER_AVATAR))
        }
        previewLauncher.launch(intent)
    }

    // --- Recent Media ---

    private fun loadRecentMedia() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                queryRecentMedia(limit = 20)
            }
            recentMediaItems.clear()
            recentMediaItems.addAll(items)
            recentMediaAdapter?.submitList(items.toList())
            
            if (items.isNotEmpty() && currentMode == CameraMode.PHOTO) {
                recentMediaStrip.visibility = View.VISIBLE
            }
        }
    }

    private fun queryRecentMedia(limit: Int): List<RecentMediaItem> {
        val items = mutableListOf<RecentMediaItem>()
        
        // Query images
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        )
        val imageSortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                null, null,
                "$imageSortOrder LIMIT $limit"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val mime = cursor.getString(mimeCol) ?: "image/*"
                    val date = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    items.add(RecentMediaItem(uri, mime, date, isVideo = false))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying images", e)
        }

        // Query videos
        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE
        )
        val videoSortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null, null,
                "$videoSortOrder LIMIT $limit"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val mime = cursor.getString(mimeCol) ?: "video/*"
                    val date = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    items.add(RecentMediaItem(uri, mime, date, isVideo = true))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying videos", e)
        }

        // Sort by date descending and limit
        return items.sortedByDescending { it.dateAdded }.take(limit)
    }

    // --- Helpers ---

    private fun createTempMediaFile(extension: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val dir = File(cacheDir, "camera_captures").apply { mkdirs() }
        return File(dir, "GLYPH_${timeStamp}.$extension")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        focusIndicatorRunnable?.let { timerHandler.removeCallbacks(it) }
    }

    // --- Inner classes ---

    data class RecentMediaItem(
        val uri: Uri,
        val mimeType: String,
        val dateAdded: Long,
        val isVideo: Boolean
    )

    inner class RecentMediaAdapter(
        private val onClick: (RecentMediaItem) -> Unit
    ) : RecyclerView.Adapter<RecentMediaAdapter.ViewHolder>() {

        private val items = mutableListOf<RecentMediaItem>()

        fun submitList(list: List<RecentMediaItem>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_recent_media, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val ivVideoBadge: ImageView = itemView.findViewById(R.id.ivVideoBadge)

            fun bind(item: RecentMediaItem) {
                Glide.with(this@CameraActivity)
                    .load(item.uri)
                    .transform(CenterCrop(), RoundedCorners(16))
                    .placeholder(R.drawable.bg_media_thumb)
                    .into(ivThumbnail)

                ivVideoBadge.visibility = if (item.isVideo) View.VISIBLE else View.GONE

                itemView.setOnClickListener { onClick(item) }
            }
        }
    }

    /**
     * Custom drawable that creates a circular window in the center with
     * the rest of the screen blacked out (for Video Note mode).
     */
    inner class VideoNoteOverlayDrawable : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
        }
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val cx = bounds.width() / 2f
            val cy = bounds.height() / 2f  // Position circle exactly in the center vertically
            // Base radius on screen WIDTH to ensure it fits horizontally without clipping
            // Use 0.46 of width (92% diameter) for a large circle that still has margin
            val radius = bounds.width() * 0.46f

            // Save layer to support clear mode
            val saveCount = canvas.saveLayer(
                0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), null
            )

            // Draw black overlay
            canvas.drawRect(bounds, paint)

            // Cut out circle
            canvas.drawCircle(cx, cy, radius, clearPaint)

            canvas.restoreToCount(saveCount)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in API")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
