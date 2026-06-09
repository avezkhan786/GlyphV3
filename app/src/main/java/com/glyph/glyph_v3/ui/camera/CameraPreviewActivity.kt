package com.glyph.glyph_v3.ui.camera

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.glyph.glyph_v3.R

/**
 * Preview screen shown after capturing a photo/video.
 * User can review and send the media to chat, or retake.
 */
class CameraPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraPreview"
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_IS_VIDEO_NOTE = "extra_is_video_note"
    }

    private var player: ExoPlayer? = null
    private var mediaUri: Uri? = null
    private var mediaType: String = "image/*"
    private var isVideoNote: Boolean = false
    private var isPlaying: Boolean = true
    private var orientationEventListener: android.view.OrientationEventListener? = null
    private var currentUIOrientation: Int = 0

    // Views
    private lateinit var imagePreview: ImageView
    private lateinit var videoPreview: PlayerView
    private lateinit var videoNoteContainer: View
    private lateinit var videoNotePreview: PlayerView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnRetake: ImageButton
    private lateinit var tvVideoDuration: TextView
    private lateinit var etCaption: EditText
    private lateinit var btnSend: FloatingActionButton
    private lateinit var previewBottomBar: View
    private lateinit var videoNoteProgress: com.google.android.material.progressindicator.CircularProgressIndicator
    private lateinit var tvRecipient: TextView
    private lateinit var tvMediaDetails: TextView
    private lateinit var mediaInfoContainer: View

    private var otherUsername: String? = null
    
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

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

        setContentView(R.layout.activity_camera_preview)

        // Parse intent
        val uriString = intent.getStringExtra(EXTRA_MEDIA_URI)
        mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "image/*"
        isVideoNote = intent.getBooleanExtra(EXTRA_IS_VIDEO_NOTE, false)
        otherUsername = intent.getStringExtra(CameraActivity.EXTRA_OTHER_USERNAME)

        if (uriString == null) {
            Log.e(TAG, "No media URI provided")
            finish()
            return
        }
        mediaUri = Uri.parse(uriString)

        bindViews()
        setupListeners()
        setupWindowInsets()
        setupOrientationListener()
        
        if (isVideoNote) {
            setupVideoNoteUI()
        }
        
        displayMedia()
        displayMediaDetails()
    }

    override fun onStart() {
        super.onStart()
        orientationEventListener?.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationEventListener?.disable()
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return

                val newOrientation = when (orientation) {
                    in 315..360, in 0..45 -> 0
                    in 45..135 -> 270
                    in 135..225 -> 180
                    in 225..315 -> 90
                    else -> 0
                }

                if (newOrientation != currentUIOrientation) {
                    currentUIOrientation = newOrientation
                    rotateUI(newOrientation)
                }
            }
        }
    }

    private fun rotateUI(rotation: Int) {
        // UI elements (Retake, Send, top bars) remain fixed in portrait for the preview screen
        // as requested to maintain visual consistency.
    }

    private fun bindViews() {
        imagePreview = findViewById(R.id.imagePreview)
        videoPreview = findViewById(R.id.videoPreview)
        videoNoteContainer = findViewById(R.id.videoNoteContainer)
        videoNotePreview = findViewById(R.id.videoNotePreview)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnRetake = findViewById(R.id.btnRetake)
        tvVideoDuration = findViewById(R.id.tvVideoDuration)
        etCaption = findViewById(R.id.etCaption)
        btnSend = findViewById(R.id.btnSend)
        previewBottomBar = findViewById(R.id.previewBottomBar)
        videoNoteProgress = findViewById(R.id.videoNoteProgress)
        tvRecipient = findViewById(R.id.tvRecipient)
        tvMediaDetails = findViewById(R.id.tvMediaDetails)
        mediaInfoContainer = findViewById(R.id.mediaInfoContainer)

        tvRecipient.text = "Sending to: ${otherUsername ?: "User"}"
    }

    private fun setupVideoNoteUI() {
        // Set video note progress size to be slightly OUTSIDE the circular cutout
        // Cutout is 92% of screen width. Progress bar is 96% to sit just outside.
        val screenWidth = resources.displayMetrics.widthPixels
        val indicatorSize = (screenWidth * 0.96f).toInt()
        videoNoteProgress.indicatorSize = indicatorSize
        
        // Hide standard playback controls for circular video note
        btnPlayPause.visibility = View.GONE
    }

    private fun displayMediaDetails() {
        val uri = mediaUri ?: return
        mediaInfoContainer.visibility = View.VISIBLE
        
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            
            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0"
            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0"
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0
            
            val fileDescriptor = contentResolver.openAssetFileDescriptor(uri, "r")
            val fileSize = fileDescriptor?.length ?: 0
            fileDescriptor?.close()
            retriever.release()

            val sizeStr = if (fileSize > 1024 * 1024) {
                String.format("%.1f MB", fileSize / (1024.0 * 1024.0))
            } else {
                "${fileSize / 1024} KB"
            }

            tvMediaDetails.text = "${width}×${height} • $sizeStr"
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching media details", e)
            mediaInfoContainer.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        btnRetake.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            // Discard and go back to camera
            finish()
        }

        btnSend.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            sendMedia()
        }

        btnPlayPause.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            togglePlayback()
        }

        // Tap on video to toggle play/pause
        videoPreview.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            togglePlayback() 
        }
        videoNotePreview.setOnClickListener { 
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            togglePlayback() 
        }
    }

    private fun displayMedia() {
        val uri = mediaUri ?: return

        // New consistent bottom strip implementation
        tvRecipient.visibility = View.VISIBLE
        etCaption.visibility = View.GONE  // Hiding caption for now as requested for the strip style

        if (mediaType.startsWith("video/")) {
            displayVideo(uri)
        } else {
            displayImage(uri)
        }
    }

    private fun displayImage(uri: Uri) {
        imagePreview.visibility = View.VISIBLE
        videoPreview.visibility = View.GONE
        videoNoteContainer.visibility = View.GONE
        btnPlayPause.visibility = View.GONE
        tvVideoDuration.visibility = View.GONE

        Glide.with(this)
            .load(uri)
            .into(imagePreview)
    }

    private fun displayVideo(uri: Uri) {
        if (isVideoNote) {
            videoNoteContainer.visibility = View.VISIBLE
            videoPreview.visibility = View.GONE
            imagePreview.visibility = View.GONE

            // Apply exactly the same "Video Note framing" geometry as CameraActivity to ensure WYSIWYG.
            videoNotePreview.post {
                val width = videoNotePreview.width
                val params = videoNotePreview.layoutParams
                
                // Match CameraActivity: Use 1.8x height for full vertical coverage
                val previewHeight = (width * 1.8f).toInt()
                params.height = previewHeight
                videoNotePreview.layoutParams = params
                
                // FIXED: Keep the circular cutout vertically centered (50% screen height)
                val screenHeight = (videoNotePreview.parent as View).height
                val circleCenterY = screenHeight / 2f
                
                // Align the view center with the screen center
                val previewTop = circleCenterY - (previewHeight * 0.50f)
                videoNotePreview.y = previewTop
                
                // PAN the video inside the cutout by translating the INNER surface view UP.
                // This keeps the circular cutout centered while shifting the video content.
                val panPx = (resources.displayMetrics.density * 70f)
                videoNotePreview.videoSurfaceView?.translationY = -panPx

                // Reset parent view transforms
                videoNotePreview.translationY = 0f
                videoNotePreview.scaleX = 1.0f
                videoNotePreview.scaleY = 1.0f

                // Circular mask stays centered in the view (cutout is fixed)
                videoNotePreview.clipToOutline = true
                videoNotePreview.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        val radius = view.width * 0.46f
                        val cx = view.width / 2
                        val cy = view.height / 2f

                        outline.setOval(
                            (cx - radius).toInt(),
                            (cy - radius).toInt(),
                            (cx + radius).toInt(),
                            (cy + radius).toInt()
                        )
                    }
                }
            }

            setupPlayer(uri, videoNotePreview)
        } else {
            videoPreview.visibility = View.VISIBLE
            imagePreview.visibility = View.GONE
            videoNoteContainer.visibility = View.GONE

            setupPlayer(uri, videoPreview)
        }

        btnPlayPause.visibility = View.GONE  // Auto-play, show on pause
    }

    private fun setupPlayer(uri: Uri, playerView: PlayerView) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.repeatMode = Player.REPEAT_MODE_ALL
            exo.prepare()
            exo.play()
            isPlaying = true
            
            if (isVideoNote) {
                videoNoteProgress.visibility = View.VISIBLE
                startProgressUpdates()
            } else {
                videoNoteProgress.visibility = View.GONE
            }

            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val durationMs = exo.duration
                        if (durationMs > 0) {
                            val seconds = (durationMs / 1000) % 60
                            val minutes = (durationMs / 1000) / 60
                            tvVideoDuration.text = String.format("%02d:%02d", minutes, seconds)
                            tvVideoDuration.visibility = View.VISIBLE
                            
                            if (isVideoNote) {
                                videoNoteProgress.max = durationMs.toInt()
                            }
                        }
                    }
                }
                
                override fun onIsPlayingChanged(playing: Boolean) {
                    if (playing) startProgressUpdates() else stopProgressUpdates()
                }
            })
        }
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (isVideoNote && p.duration > 0) {
                        val pos = p.currentPosition.toInt()
                        videoNoteProgress.setProgressCompat(pos, true)
                        
                        // Handle loop reset for progress indicator smoothly
                        if (pos < 100) {
                            videoNoteProgress.setProgressCompat(0, false)
                        }
                    }
                    progressHandler.postDelayed(this, 16)
                }
            }
        }
        progressHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { progressHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun togglePlayback() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            isPlaying = false
            btnPlayPause.visibility = View.VISIBLE
            btnPlayPause.setImageResource(R.drawable.ic_play_white)
        } else {
            p.play()
            isPlaying = true
            btnPlayPause.visibility = View.GONE
        }
    }

    private fun sendMedia() {
        val uri = mediaUri ?: return
        val captionText = etCaption.text.toString().trim()

        val result = Intent().apply {
            putExtra(CameraActivity.RESULT_MEDIA_URI, uri.toString())
            putExtra(CameraActivity.RESULT_MEDIA_TYPE, mediaType)
            putExtra(CameraActivity.RESULT_IS_VIDEO_NOTE, isVideoNote)
            putExtra(CameraActivity.RESULT_CAPTION, captionText)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying) {
            player?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun setupWindowInsets() {
        val topBar = findViewById<View>(R.id.previewTopBar)
        val bottomBar = findViewById<View>(R.id.previewBottomBar)

        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Add 16dp of safe padding on top of the system bars (status bar)
            val topPadding = systemBars.top + (16 * resources.displayMetrics.density).toInt()
            v.setPadding(v.paddingLeft, topPadding, v.paddingRight, v.paddingBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val pVertical = (10 * density).toInt()
            val pHorizontal = (20 * density).toInt()

            // Apply equal padding top and bottom (10dp) inside the strip
            v.setPadding(pHorizontal, pVertical, pHorizontal, pVertical)
            
            // "Lift" the strip by applying the system bar inset as a margin
            val params = v.layoutParams as android.widget.FrameLayout.LayoutParams
            params.bottomMargin = systemBars.bottom + (12 * density).toInt() // Base 12dp margin from bottom
            v.layoutParams = params
            
            insets
        }
    }

    /**
     * Outline provider that clips the view to a circle (for video notes).
     */
    private class CircularOutlineProvider : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            val size = minOf(view.width, view.height)
            val left = (view.width - size) / 2
            val top = (view.height - size) / 2
            outline.setOval(left, top, left + size, top + size)
        }
    }
}
