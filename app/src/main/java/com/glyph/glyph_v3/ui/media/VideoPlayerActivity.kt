package com.glyph.glyph_v3.ui.media

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.glyph.glyph_v3.databinding.ActivityVideoPlayerBinding

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var isControlsVisible = true
    private var playWhenReady = true
    private var mediaItemIndex = 0
    private var playbackPosition = 0L
    private var windowInsetsController: WindowInsetsControllerCompat? = null
    private var platformBackCallback: OnBackInvokedCallback? = null
    private var isPlatformBackCallbackRegistered = false

    companion object {
        private const val TAG = "VideoPlayerActivity"
        private const val EXTRA_VIDEO_URL = "video_url"
        private const val EXTRA_VIDEO_TITLE = "video_title"
        private const val KEY_PLAY_WHEN_READY = "play_when_ready"
        private const val KEY_MEDIA_ITEM_INDEX = "media_item_index"
        private const val KEY_PLAYBACK_POSITION = "playback_position"

        fun newIntent(context: Context, videoUrl: String, title: String = "Video"): Intent {
            return Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_VIDEO_URL, videoUrl)
                putExtra(EXTRA_VIDEO_TITLE, title)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                    isFitInsetsIgnoringVisibility = true
                }
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)

        // Restore state
        savedInstanceState?.let {
            playWhenReady = it.getBoolean(KEY_PLAY_WHEN_READY, true)
            mediaItemIndex = it.getInt(KEY_MEDIA_ITEM_INDEX, 0)
            playbackPosition = it.getLong(KEY_PLAYBACK_POSITION, 0L)
        }

        showSystemBars()
        setupBackHandling()
        setupWindowInsets()

        val title = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video"
        binding.tvTitle.text = title
        
        // Set controller timeout to auto-hide after 3 seconds
        binding.playerView.setControllerShowTimeoutMs(3000)

        setupClickListeners()
    }

    override fun onStart() {
        super.onStart()
        registerPlatformBackCallbackIfNeeded()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        applySystemBarsVisibility()
        if (player == null) {
            initializePlayer()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemBarsVisibility()
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't release on pause for Android N+ multi-window
    }

    override fun onStop() {
        unregisterPlatformBackCallbackIfNeeded()
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        unregisterPlatformBackCallbackIfNeeded()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            outState.putBoolean(KEY_PLAY_WHEN_READY, it.playWhenReady)
            outState.putInt(KEY_MEDIA_ITEM_INDEX, it.currentMediaItemIndex)
            outState.putLong(KEY_PLAYBACK_POSITION, it.currentPosition)
        }
    }

    private fun showSystemBars() {
        val windowInsetsController = windowInsetsController ?: return
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    }

    private fun hideSystemBars() {
        val windowInsetsController = windowInsetsController ?: return
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applySystemBarsVisibility() {
        if (isControlsVisible) {
            showSystemBars()
        } else {
            hideSystemBars()
        }
    }

    private fun setupWindowInsets() {
        val baseToolbarStart = binding.toolbar.paddingStart
        val baseToolbarTop = binding.toolbar.paddingTop
        val baseToolbarEnd = binding.toolbar.paddingEnd
        val baseToolbarBottom = binding.toolbar.paddingBottom
        val controlsBottomBar = binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val baseControlsBottomPadding = controlsBottomBar?.paddingBottom ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
            val safeTopInsetPx = kotlin.math.max(systemBars.top, displayCutout.top)
            val safeBottomInsetPx = kotlin.math.max(systemBars.bottom, displayCutout.bottom)

            binding.toolbar.setPaddingRelative(
                baseToolbarStart,
                baseToolbarTop + safeTopInsetPx,
                baseToolbarEnd,
                baseToolbarBottom
            )
            controlsBottomBar?.updatePadding(bottom = baseControlsBottomPadding + safeBottomInsetPx)
            insets
        }

        ViewCompat.requestApplyInsets(window.decorView)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            platformBackCallback = OnBackInvokedCallback {
                finish()
            }
        }
    }

    private fun registerPlatformBackCallbackIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (isPlatformBackCallbackRegistered) return
        val callback = platformBackCallback ?: return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback
        )
        isPlatformBackCallbackRegistered = true
    }

    private fun unregisterPlatformBackCallbackIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (!isPlatformBackCallbackRegistered) return
        val callback = platformBackCallback ?: return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        isPlatformBackCallbackRegistered = false
    }

    private fun initializePlayer() {
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL)
        if (videoUrl.isNullOrEmpty()) {
            Log.e(TAG, "No video URL provided")
            finish()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE
        
        // Check if this is a local file path
        val isLocalFile = videoUrl.startsWith("/") || videoUrl.startsWith("file://")
        
        if (isLocalFile) {
            // Verify file exists before attempting playback
            val file = java.io.File(if (videoUrl.startsWith("file://")) videoUrl.removePrefix("file://") else videoUrl)
            if (!file.exists()) {
                Log.e(TAG, "Local file does not exist: ${file.absolutePath}")
                binding.progressBar.visibility = View.GONE
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "Video file not found"
                return
            }
            if (file.length() == 0L) {
                Log.e(TAG, "Local file is empty: ${file.absolutePath}")
                binding.progressBar.visibility = View.GONE
                binding.tvError.visibility = View.VISIBLE
                binding.tvError.text = "Video file is empty"
                return
            }
        }

        // Create a DataSource.Factory that handles both local files and remote URLs
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                binding.playerView.player = exoPlayer

                // Build media item - for local files, use file:// URI scheme
                val uri = if (isLocalFile && !videoUrl.startsWith("file://")) {
                    Uri.fromFile(java.io.File(videoUrl))
                } else {
                    Uri.parse(videoUrl)
                }
                
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer.setMediaItem(mediaItem)

                // Add listener for playback events
                exoPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_IDLE -> {
                            }
                            Player.STATE_BUFFERING -> {
                                binding.progressBar.visibility = View.VISIBLE
                            }
                            Player.STATE_READY -> {
                                binding.progressBar.visibility = View.GONE
                            }
                            Player.STATE_ENDED -> {
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Playback error: ${error.message}", error)
                        Log.e(TAG, "Error code: ${error.errorCode}")
                        error.cause?.let { cause ->
                            Log.e(TAG, "Cause: ${cause.message}", cause)
                        }
                        binding.progressBar.visibility = View.GONE
                        binding.tvError.visibility = View.VISIBLE
                        binding.tvError.text = "Failed to play video: ${error.message}"
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            // Auto-hide controls after a brief delay when playback starts
                            binding.playerView.postDelayed({
                                binding.playerView.hideController()
                            }, 300) // Small delay to ensure smooth transition
                        }
                    }
                })

                // Restore position if available
                exoPlayer.seekTo(mediaItemIndex, playbackPosition)
                exoPlayer.playWhenReady = playWhenReady
                exoPlayer.prepare()
            }
    }

    private fun releasePlayer() {
        player?.let { exoPlayer ->
            playbackPosition = exoPlayer.currentPosition
            mediaItemIndex = exoPlayer.currentMediaItemIndex
            playWhenReady = exoPlayer.playWhenReady
            exoPlayer.release()
        }
        player = null
    }

    private fun toggleControls() {
        if (isControlsVisible) {
            binding.toolbar.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    binding.toolbar.visibility = View.GONE
                    hideSystemBars()
                }
                .start()
            binding.playerView.hideController()
        } else {
            showSystemBars()
            binding.toolbar.visibility = View.VISIBLE
            binding.toolbar.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
            binding.playerView.showController()
        }
        isControlsVisible = !isControlsVisible
    }

    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Toggle our custom toolbar when player controller visibility changes
        binding.playerView.setControllerVisibilityListener(
            androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    showSystemBars()
                    binding.toolbar.visibility = View.VISIBLE
                    binding.toolbar.alpha = 1f
                    isControlsVisible = true
                } else {
                    binding.toolbar.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            binding.toolbar.visibility = View.GONE
                            hideSystemBars()
                        }
                        .start()
                    isControlsVisible = false
                }
            }
        )
    }
}
