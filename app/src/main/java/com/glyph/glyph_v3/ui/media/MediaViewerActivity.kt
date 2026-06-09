package com.glyph.glyph_v3.ui.media

import android.animation.ValueAnimator
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Toast
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.glyph.glyph_v3.BuildConfig
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.data.models.MediaType
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.databinding.ActivityMediaViewerBinding
import com.glyph.glyph_v3.ui.chat.reactions.ReactionEmojiPickerSheet
import com.glyph.glyph_v3.ui.chat.reactions.ReactionPopupOverlay
import com.glyph.glyph_v3.utils.ThemeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Media viewer activity supporting both single and multiple media items.
 * Features:
 * - Pinch to zoom and pan (retained from original)
 * - Vertical free-scroll browsing for grouped media
 * - Tap-to-focus grouped media without leaving the scroll flow
 * - Video playback support
 */
class MediaViewerActivity : AppCompatActivity() {

    private data class SingleMediaTransformSnapshot(
        val scale: Float,
        val offsetXFraction: Float,
        val offsetYFraction: Float
    )

    private lateinit var binding: ActivityMediaViewerBinding
    
    // Single media mode state
    private val matrix = Matrix()
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
    
    // Multi-media mode state
    private var mediaItems: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0
    private var isMultiMediaMode = false
    private var mediaPagerAdapter: MediaPagerAdapter? = null
    private var focusedMediaIndex: Int? = null
    private var focusTouchStartX = 0f
    private var focusTouchStartY = 0f
    private val focusTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var focusSourceBounds: RectF? = null
    private var focusSourceAdapterOffset = 0
    private var focusTransitionAnimator: ValueAnimator? = null
    private var focusOverlayView: ImageView? = null
    private var focusScrimView: View? = null
    private var isFocusTransitionRunning = false
    private var pendingFocusRevealIndex: Int? = null
    private var pendingFocusedDrawable: Drawable? = null
    private val selectedMediaIndices = linkedSetOf<Int>()
    private var safeTopInsetPx = 0
    private var safeBottomInsetPx = 0
    private var isChromeVisible = true
    private var singleMediaLayoutListenerAttached = false
    private var windowInsetsController: WindowInsetsControllerCompat? = null
    private var platformBackCallback: OnBackInvokedCallback? = null
    private var isPlatformBackCallbackRegistered = false

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }
    private val repository by lazy {
        RealtimeMessageRepository(
            messageDao = db.messageDao(),
            chatDao = db.chatDao(),
            deletedMessageDao = db.deletedMessageDao(),
            context = applicationContext
        )
    }
    private var parentIsOutgoing = false
    private var parentOwnReaction: String? = null
    
    private var messageId: String? = null
    private var chatId: String? = null

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
        
        private const val EXTRA_IMAGE_URL = "image_url"
        private const val EXTRA_TIMESTAMP = "timestamp"
        private const val EXTRA_MEDIA_ITEMS = "media_items"
        private const val EXTRA_START_INDEX = "start_index"
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_CHAT_ID = "chat_id"

        const val RESULT_ACTION = "media_viewer_result_action"
        const val RESULT_CHAT_ID = "media_viewer_result_chat_id"
        const val RESULT_MESSAGE_ID = "media_viewer_result_message_id"
        const val RESULT_MEDIA_INDEX = "media_viewer_result_media_index"
        const val RESULT_MEDIA_INDICES = "media_viewer_result_media_indices"

        const val ACTION_REPLY = "reply"
        const val ACTION_FORWARD = "forward"
        const val ACTION_DELETE = "delete"
        const val ACTION_SHOW_IN_CHAT = "show_in_chat"

        private const val MENU_SHOW_IN_CHAT = 1
        private const val FOCUS_DEBUG_TAG = "MediaFocusDebug"
        
        /**
         * Create intent for single media viewing (backward compatible)
         */
        fun newIntent(context: Context, imageUrl: String, timestamp: Long = 0, messageId: String? = null, chatId: String? = null): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_IMAGE_URL, imageUrl)
                putExtra(EXTRA_TIMESTAMP, timestamp)
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_CHAT_ID, chatId)
            }
        }
        
        /**
         * Create intent for multiple media viewing
         */
        fun newIntentWithMultipleMedia(
            context: Context, 
            mediaItems: List<MediaItem>, 
            startIndex: Int = 0,
            timestamp: Long = 0,
            messageId: String? = null,
            chatId: String? = null
        ): Intent {
            return Intent(context, MediaViewerActivity::class.java).apply {
                putParcelableArrayListExtra(EXTRA_MEDIA_ITEMS, ArrayList(mediaItems))
                putExtra(EXTRA_START_INDEX, startIndex)
                putExtra(EXTRA_TIMESTAMP, timestamp)
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_CHAT_ID, chatId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                    isFitInsetsIgnoringVisibility = true
                }
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        
        binding = ActivityMediaViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        
        showSystemBars()
        setupWindowInsets()
        setupClickListeners()
        setupBackHandling()

        // Get optional message/chat context
        messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
        chatId = intent.getStringExtra(EXTRA_CHAT_ID)
        loadReactionState()
        
        // Check if we have multiple media items
        val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_MEDIA_ITEMS, MediaItem::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<MediaItem>(EXTRA_MEDIA_ITEMS)
        }
        
        if (items != null && items.isNotEmpty()) {
            mediaItems = items
            currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, items.size - 1)
            isMultiMediaMode = true
            setupMultiMediaMode()
        } else {
            val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
            if (imageUrl.isNullOrEmpty()) {
                finish()
                return
            }
            isMultiMediaMode = false
            setupSingleMediaMode(imageUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        registerPlatformBackCallbackIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyChromeVisibility(animated = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyChromeVisibility(animated = false)
        }
    }

    override fun onStop() {
        unregisterPlatformBackCallbackIfNeeded()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterPlatformBackCallbackIfNeeded()
        zoomAnimator?.cancel()
        focusTransitionAnimator?.cancel()
        removeFocusTransitionViews()
        mediaPagerAdapter = null
        binding.mediaPager.adapter = null
        binding.mediaPager.clearOnScrollListeners()
        clearGlideViewSafely(binding.ivFullscreenImage)
        super.onDestroy()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            handleViewerBackPress()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformBackCallback = OnBackInvokedCallback {
                handleViewerBackPress()
            }
        }
    }

    private fun handleViewerBackPress() {
        if (selectedMediaIndices.isNotEmpty()) {
            clearMediaSelection()
        } else if (focusedMediaIndex != null) {
            clearFocusedMedia()
        } else {
            finish()
        }
    }

    private fun registerPlatformBackCallbackIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isPlatformBackCallbackRegistered) return
        val callback = platformBackCallback ?: return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback
        )
        isPlatformBackCallbackRegistered = true
    }

    private fun unregisterPlatformBackCallbackIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!isPlatformBackCallbackRegistered) return
        val callback = platformBackCallback ?: return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        isPlatformBackCallbackRegistered = false
    }

    private fun loadReactionState() {
        val id = messageId ?: return
        lifecycleScope.launch {
            val local = withContext(Dispatchers.IO) { db.messageDao().getMessageById(id) }
            val currentUid = FirebaseAuth.getInstance().uid
            parentIsOutgoing = local?.senderId == currentUid
            parentOwnReaction = parseOwnReaction(local?.reactionsJson, currentUid)
        }
    }

    private fun parseOwnReaction(reactionsJson: String?, currentUid: String?): String? {
        if (reactionsJson.isNullOrBlank() || currentUid.isNullOrBlank()) return null
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(reactionsJson, type)?.get(currentUid)
        }.getOrNull()
    }
    
    // ==================== MULTI-MEDIA MODE ====================
    
    private fun setupMultiMediaMode() {
        // Show vertical media list, hide single image view
        binding.mediaPager.visibility = View.VISIBLE
        binding.ivFullscreenImage.visibility = View.GONE
        binding.tvMediaCounter.visibility = if (mediaItems.size > 1) View.VISIBLE else View.GONE
        
        // Setup continuous vertical media list
        mediaPagerAdapter = MediaPagerAdapter(mediaItems) { item, index ->
            if (item.isVideo) {
                startActivity(VideoPlayerActivity.newIntent(this, item.displayUrl, "Video"))
            }
        }
        
        binding.mediaPager.apply {
            layoutManager = LinearLayoutManager(this@MediaViewerActivity, LinearLayoutManager.VERTICAL, false)
            adapter = mediaPagerAdapter
            itemAnimator = null
            clipToPadding = false
            applyMediaPagerPadding(isFocused = false)

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (!isFocusTransitionRunning && newState == RecyclerView.SCROLL_STATE_DRAGGING && focusedMediaIndex != null) {
                        clearFocusedMedia()
                    }
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        updateCurrentIndexFromScroll()
                    }
                }
            })

            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            focusTouchStartX = e.x
                            focusTouchStartY = e.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (!isFocusTransitionRunning && focusedMediaIndex != null && hasVerticalScrollIntent(e)) {
                                clearFocusedMedia()
                            }
                        }
                    }
                    return false
                }
            })
            
            scrollToPosition(currentIndex)
        }

        updateMediaCounter()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun RecyclerView.applyMediaPagerPadding(isFocused: Boolean) {
        val top = if (isFocused) 0 else dp(80f) + safeTopInsetPx
        val bottom = if (isFocused) 0 else dp(32f) + safeBottomInsetPx
        setPadding(0, top, 0, bottom)
    }

    private fun hasVerticalScrollIntent(event: MotionEvent): Boolean {
        val deltaX = event.x - focusTouchStartX
        val deltaY = event.y - focusTouchStartY
        return kotlin.math.abs(deltaY) > focusTouchSlop &&
            kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)
    }
    
    private fun updateMediaCounter() {
        if (isSelectionMode()) {
            updateSelectionToolbarState()
            return
        }
        binding.tvMediaCounter.text = "${currentIndex + 1} / ${mediaItems.size}"
    }

    private fun updateCurrentIndexFromScroll() {
        val layoutManager = binding.mediaPager.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        if (firstVisible != RecyclerView.NO_POSITION && firstVisible != currentIndex) {
            currentIndex = firstVisible.coerceIn(0, mediaItems.lastIndex)
            updateMediaCounter()
        }
    }

    private fun focusMediaAt(index: Int, sourceView: View? = null) {
        if (index !in mediaItems.indices) return
        if (isFocusTransitionRunning) return
        if (focusedMediaIndex == index) return

        val source = sourceView ?: binding.mediaPager.findViewHolderForAdapterPosition(index)?.itemView
        val startBounds = source?.let(::boundsInRoot)
        if (startBounds == null || startBounds.width() <= 0f || startBounds.height() <= 0f) {
            applyFocusedMediaState(index)
            binding.mediaPager.post { binding.mediaPager.scrollToPosition(index) }
            updateMediaCounter()
            return
        }

        val layoutManager = binding.mediaPager.layoutManager as? LinearLayoutManager
        focusSourceAdapterOffset = (source.top - binding.mediaPager.paddingTop)
        focusSourceBounds = RectF(startBounds)

        val item = mediaItems[index]
        val transitionDrawable = transitionDrawableFrom(source)
        pendingFocusedDrawable = copyTransitionDrawable(transitionDrawable)
        debugFocus("focusMediaAt start index=$index startBounds=$startBounds sourceAlpha=${source.alpha} transitionDrawable=${transitionDrawable != null}")
        val targetBounds = fullscreenBoundsInRoot()
        removeFocusTransitionViews()
        val scrim = createFocusScrim(startAlpha = 0f)
        val overlay = createFocusOverlay(item, startBounds, copyTransitionDrawable(transitionDrawable))

        isFocusTransitionRunning = true
        binding.mediaPager.isEnabled = false
        source.alpha = 0f
        currentIndex = index

        animateFocusOverlay(
            overlay = overlay,
            scrim = scrim,
            from = startBounds,
            to = targetBounds,
            entering = true
        ) {
            pendingFocusRevealIndex = index
            debugFocus("focus animation ended index=$index; applying focused state")
            applyFocusedMediaState(index)
            layoutManager?.scrollToPositionWithOffset(index, 0)
            awaitFocusedItemPreDraw(index) { focusedItemView ->
                debugFocus("focused item preDraw index=$index width=${focusedItemView.width} height=${focusedItemView.height} overlayPresent=${focusOverlayView != null}")
                pendingFocusRevealIndex = null
                focusedItemView.findViewById<ZoomableImageView>(R.id.zoomableImage)?.let { imageView ->
                    val displayUrl = mediaItems.getOrNull(index)?.displayUrl
                    if (pendingFocusedDrawable != null && displayUrl != null) {
                        debugFocus("focused item preDraw index=$index applying pending drawable at fullscreen size")
                        imageView.setTag(R.id.zoomableImage, displayUrl)
                        imageView.setImageDrawable(copyTransitionDrawable(pendingFocusedDrawable))
                        pendingFocusedDrawable = null
                    }
                }
                focusedItemView.alpha = 0f
                source.alpha = 0f
                val overlayView = focusOverlayView
                if (overlayView == null) {
                    debugFocus("overlay missing before reveal index=$index")
                    focusedItemView.alpha = 1f
                    source.alpha = 1f
                    removeFocusTransitionViews()
                    binding.mediaPager.isEnabled = true
                    isFocusTransitionRunning = false
                    updateMediaCounter()
                    return@awaitFocusedItemPreDraw
                }

                focusedItemView.animate().cancel()
                overlayView.animate().cancel()
                debugFocus("starting overlay->focused handoff index=$index overlayAlpha=${overlayView.alpha}")
                binding.mediaPager.postOnAnimation {
                    focusedItemView.alpha = 1f
                    binding.mediaPager.postOnAnimation {
                        debugFocus("overlay->focused handoff ended index=$index sourceAlpha=${source.alpha}")
                        source.alpha = 1f
                        removeFocusTransitionViews()
                        binding.mediaPager.isEnabled = true
                        isFocusTransitionRunning = false
                        updateMediaCounter()
                    }
                }
            }
        }
    }

    private fun applyFocusedMediaState(index: Int) {
        debugFocus("applyFocusedMediaState index=$index")
        focusedMediaIndex = index
        currentIndex = index
        binding.mediaPager.applyMediaPagerPadding(isFocused = true)
        mediaPagerAdapter?.notifyDataSetChanged()
    }

    private fun clearFocusedMedia(animateToolbar: Boolean = true) {
        val previous = focusedMediaIndex ?: return
        if (isFocusTransitionRunning) return
        debugFocus("clearFocusedMedia index=$previous animateToolbar=$animateToolbar")

        if (!animateToolbar) {
            restoreMediaListState(previous, updateCounter = false)
            return
        }

        val item = mediaItems.getOrNull(previous) ?: run {
            restoreMediaListState(previous, updateCounter = true)
            return
        }
        val focusedView = binding.mediaPager.findViewHolderForAdapterPosition(previous)?.itemView
        val startBounds = focusedView?.let(::boundsInRoot)?.takeIf { it.width() > 0f && it.height() > 0f }
            ?: fullscreenBoundsInRoot()
        val targetBounds = focusSourceBounds?.takeIf { it.width() > 0f && it.height() > 0f }
            ?: startBounds
        removeFocusTransitionViews()
        val scrim = createFocusScrim(startAlpha = 0.92f)
        val overlay = createFocusOverlay(item, startBounds, transitionDrawableFrom(focusedView))

        isFocusTransitionRunning = true
        binding.mediaPager.isEnabled = false
        focusedView?.alpha = 0f
        restoreMediaListState(previous, updateCounter = false)

        binding.mediaPager.postOnAnimation {
            animateFocusOverlay(
                overlay = overlay,
                scrim = scrim,
                from = startBounds,
                to = targetBounds,
                entering = false
            ) {
                focusedView?.alpha = 1f
                binding.mediaPager.findViewHolderForAdapterPosition(previous)?.itemView?.alpha = 1f
                binding.mediaPager.postOnAnimation {
                    removeFocusTransitionViews()
                    binding.mediaPager.isEnabled = true
                    isFocusTransitionRunning = false
                    focusSourceBounds = null
                    if (animateToolbar) updateMediaCounter()
                }
            }
        }
    }

    private fun restoreMediaListState(index: Int, updateCounter: Boolean = true) {
        debugFocus("restoreMediaListState index=$index updateCounter=$updateCounter")
        pendingFocusRevealIndex = null
        pendingFocusedDrawable = null
        focusedMediaIndex = null
        currentIndex = index.coerceIn(0, mediaItems.lastIndex)
        binding.mediaPager.applyMediaPagerPadding(isFocused = false)
        mediaPagerAdapter?.notifyDataSetChanged()
        (binding.mediaPager.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(currentIndex, focusSourceAdapterOffset)
        if (updateCounter) updateMediaCounter()
    }

    private fun boundsInRoot(view: View): RectF {
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        binding.root.getLocationOnScreen(rootLocation)
        view.getLocationOnScreen(viewLocation)
        val left = (viewLocation[0] - rootLocation[0]).toFloat()
        val top = (viewLocation[1] - rootLocation[1]).toFloat()
        return RectF(left, top, left + view.width, top + view.height)
    }

    private fun fullscreenBoundsInRoot(): RectF {
        return RectF(0f, 0f, binding.root.width.toFloat(), binding.root.height.toFloat())
    }

    private fun createFocusScrim(startAlpha: Float): View {
        return View(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = startAlpha
            isClickable = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            binding.root.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            binding.toolbar.bringToFront()
            focusScrimView = this
        }
    }

    private fun transitionDrawableFrom(container: View?): Drawable? {
        return container?.findViewById<ImageView>(R.id.zoomableImage)?.drawable
    }

    private fun copyTransitionDrawable(drawable: Drawable?): Drawable? {
        val constantState = drawable?.constantState
        return if (constantState != null) {
            constantState.newDrawable(resources).mutate()
        } else {
            drawable
        }
    }

    private fun createFocusOverlay(item: MediaItem, bounds: RectF, transitionDrawable: Drawable? = null): ImageView {
        return ImageView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            applyBounds(bounds)
            val preparedDrawable = transitionDrawable?.constantState?.newDrawable(resources)?.mutate()
                ?: transitionDrawable
            if (preparedDrawable != null) {
                setImageDrawable(preparedDrawable)
                if (preparedDrawable is android.graphics.drawable.Animatable) {
                    preparedDrawable.start()
                }
            }
            binding.root.addView(this)
            binding.toolbar.bringToFront()
            if (preparedDrawable == null) {
                Glide.with(this@MediaViewerActivity)
                    .load(item.displayUrl)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                    .dontAnimate()
                    .placeholder(R.drawable.ic_video_placeholder)
                    .into(this)
            }
            focusOverlayView = this
        }
    }

    private fun ImageView.applyBounds(bounds: RectF) {
        x = bounds.left
        y = bounds.top
        layoutParams = FrameLayout.LayoutParams(
            bounds.width().roundToInt().coerceAtLeast(1),
            bounds.height().roundToInt().coerceAtLeast(1)
        )
    }

    private fun animateFocusOverlay(
        overlay: ImageView,
        scrim: View,
        from: RectF,
        to: RectF,
        entering: Boolean,
        onEnd: () -> Unit
    ) {
        debugFocus("animateFocusOverlay entering=$entering from=$from to=$to")
        focusTransitionAnimator?.cancel()
        focusTransitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            var cancelled = false
            duration = 280L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                val current = RectF(
                    lerp(from.left, to.left, progress),
                    lerp(from.top, to.top, progress),
                    lerp(from.right, to.right, progress),
                    lerp(from.bottom, to.bottom, progress)
                )
                overlay.applyBounds(current)
                scrim.alpha = if (entering) 0.92f * progress else 0.92f * (1f - progress)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) {
                        debugFocus("animateFocusOverlay end entering=$entering overlayAlpha=${overlay.alpha} scrimAlpha=${scrim.alpha}")
                        focusTransitionAnimator = null
                        onEnd()
                    }
                }
            })
            start()
        }
    }

    private fun awaitFocusedItemPreDraw(index: Int, onReady: (View) -> Unit) {
        val itemView = binding.mediaPager.findViewHolderForAdapterPosition(index)?.itemView
        if (itemView == null) {
            binding.mediaPager.postOnAnimation { awaitFocusedItemPreDraw(index, onReady) }
            return
        }
        if (itemView.width == 0 || itemView.height == 0) {
            binding.mediaPager.postOnAnimation { awaitFocusedItemPreDraw(index, onReady) }
            return
        }
        val observer = itemView.viewTreeObserver
        if (!observer.isAlive) {
            binding.mediaPager.postOnAnimation { awaitFocusedItemPreDraw(index, onReady) }
            return
        }
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (itemView.viewTreeObserver.isAlive) {
                    itemView.viewTreeObserver.removeOnPreDrawListener(this)
                }
                onReady(itemView)
                return true
            }
        })
        itemView.invalidate()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress
    }

    private fun removeFocusTransitionViews() {
        debugFocus("removeFocusTransitionViews overlayPresent=${focusOverlayView != null} scrimPresent=${focusScrimView != null}")
        val animator = focusTransitionAnimator
        focusTransitionAnimator = null
        animator?.cancel()
        focusOverlayView?.let { overlay ->
            clearGlideViewSafely(overlay)
            binding.root.removeView(overlay)
        }
        focusScrimView?.let(binding.root::removeView)
        focusOverlayView = null
        focusScrimView = null
    }

    private fun clearGlideViewSafely(view: ImageView) {
        runCatching {
            Glide.with(applicationContext).clear(view)
        }
    }

    private fun mediaItemHeight(item: MediaItem, focused: Boolean): Int {
        val screen = resources.displayMetrics
        val availableWidth = screen.widthPixels
        val aspectRatio = when {
            item.width > 0 && item.height > 0 -> item.width.toFloat() / item.height.toFloat()
            item.isVideo -> 16f / 9f
            else -> 1f
        }.coerceIn(0.45f, 2.4f)
        val naturalHeight = (availableWidth / aspectRatio).toInt()
        return if (focused) {
            (binding.mediaPager.height.takeIf { it > 0 } ?: screen.heightPixels)
        } else {
            naturalHeight.coerceIn(dp(220f), (screen.heightPixels * 0.68f).toInt())
        }
    }

    private fun isSelectionMode(): Boolean = selectedMediaIndices.isNotEmpty()

    private fun toggleMediaSelection(index: Int) {
        if (index !in mediaItems.indices) return
        if (focusedMediaIndex != null) {
            clearFocusedMedia(animateToolbar = false)
        }
        if (!selectedMediaIndices.add(index)) {
            selectedMediaIndices.remove(index)
        }
        mediaPagerAdapter?.notifyItemChanged(index)
        updateSelectionToolbarState()
    }

    private fun clearMediaSelection() {
        if (selectedMediaIndices.isEmpty()) return
        val changed = selectedMediaIndices.toList()
        selectedMediaIndices.clear()
        changed.forEach { index ->
            mediaPagerAdapter?.notifyItemChanged(index)
        }
        updateSelectionToolbarState()
    }

    private fun selectedResultIndices(): ArrayList<Int> {
        val indices = if (selectedMediaIndices.isNotEmpty()) {
            selectedMediaIndices.sorted()
        } else {
            listOf(currentIndex)
        }
        return ArrayList(indices)
    }

    private fun updateSelectionToolbarState() {
        val selectionMode = isSelectionMode()
        if (selectionMode) {
            binding.toolbar.animate().cancel()
            binding.toolbar.visibility = View.VISIBLE
            binding.toolbar.alpha = 1f
        }
        binding.tvMediaCounter.visibility = if (selectionMode || mediaItems.size > 1) View.VISIBLE else View.GONE
        binding.tvMediaCounter.text = if (selectionMode) {
            "${selectedMediaIndices.size} selected"
        } else {
            "${currentIndex + 1} / ${mediaItems.size}"
        }

        binding.btnReply.visibility = if (selectionMode) View.GONE else View.VISIBLE
        binding.btnShare.visibility = if (selectionMode) View.GONE else View.VISIBLE
        binding.btnSave.visibility = if (selectionMode) View.GONE else View.VISIBLE
        binding.btnMore.visibility = if (selectionMode) View.GONE else View.VISIBLE
    }
    
    // ==================== SINGLE MEDIA MODE ====================
    
    private fun setupSingleMediaMode(imageUrl: String) {
        // Show single image view, hide pager
        binding.mediaPager.visibility = View.GONE
        binding.ivFullscreenImage.visibility = View.VISIBLE
        binding.tvMediaCounter.visibility = View.GONE
        
        ensureSingleMediaLayoutListener()
        setupGestureDetectors()
        loadImage(imageUrl)
    }

    private fun ensureSingleMediaLayoutListener() {
        if (singleMediaLayoutListenerAttached) return
        singleMediaLayoutListenerAttached = true
        binding.ivFullscreenImage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val newWidth = right - left
            val newHeight = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (newWidth <= 0 || newHeight <= 0) return@addOnLayoutChangeListener
            if (newWidth == oldWidth && newHeight == oldHeight) return@addOnLayoutChangeListener

            val drawable = binding.ivFullscreenImage.drawable ?: return@addOnLayoutChangeListener
            val snapshot = captureSingleMediaTransformSnapshot()
            setupSingleMediaImage(drawable)
            snapshot?.let {
                applySingleMediaTransform(it.scale, it.offsetXFraction, it.offsetYFraction)
            }
        }
    }
    
    private fun setupGestureDetectors() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = ZOOM
                zoomAnimator?.cancel()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
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
                
                if (origWidth * saveScale <= viewWidth || origHeight * saveScale <= viewHeight) {
                    matrix.postScale(mScaleFactor, mScaleFactor, viewWidth / 2, viewHeight / 2)
                } else {
                    matrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
                }
                
                fixTranslation()
                return true
            }
        })
        
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val targetScale = if (saveScale > minScale) minScale else minScale * 2f
                
                zoomAnimator?.cancel()
                zoomAnimator = android.animation.ValueAnimator.ofFloat(saveScale, targetScale).apply {
                    duration = 300
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { animator ->
                        val newScale = animator.animatedValue as Float
                        val factor = newScale / saveScale
                        saveScale = newScale
                        
                        matrix.postScale(factor, factor, e.x, e.y)
                        fixTranslation()
                        binding.ivFullscreenImage.imageMatrix = matrix
                        binding.ivFullscreenImage.invalidate()
                    }
                    start()
                }
                return true
            }
            
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleToolbar()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showReactionPicker(binding.ivFullscreenImage, e.rawY)
            }
        })
        
        binding.ivFullscreenImage.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            
            val curr = PointF(event.x, event.y)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    last.set(curr)
                    start.set(last)
                    mode = DRAG
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        val deltaX = curr.x - last.x
                        val deltaY = curr.y - last.y
                        val fixTransX = getFixDragTrans(deltaX, viewWidth, origWidth * saveScale)
                        val fixTransY = getFixDragTrans(deltaY, viewHeight, origHeight * saveScale)
                        matrix.postTranslate(fixTransX, fixTransY)
                        fixTranslation()
                        last.set(curr.x, curr.y)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    mode = NONE
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                }
            }
            
            binding.ivFullscreenImage.imageMatrix = matrix
            binding.ivFullscreenImage.invalidate()
            true
        }
    }
    
    private fun fixTranslation() {
        matrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]
        
        val fixTransX = getFixTrans(transX, viewWidth, origWidth * saveScale)
        val fixTransY = getFixTrans(transY, viewHeight, origHeight * saveScale)
        
        if (fixTransX != 0f || fixTransY != 0f) {
            matrix.postTranslate(fixTransX, fixTransY)
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
    
    private fun loadImage(imageUrl: String) {
        binding.ivFullscreenImage.post {
            Glide.with(this)
                .load(imageUrl)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        binding.ivFullscreenImage.setImageDrawable(resource)
                        if (resource is android.graphics.drawable.Animatable) {
                            resource.start()
                        }
                        setupSingleMediaImage(resource)
                    }
                    
                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        }
    }

    private fun setupSingleMediaImage(drawable: Drawable) {
        val width = binding.ivFullscreenImage.width
        val height = binding.ivFullscreenImage.height
        if (width <= 0 || height <= 0) return

        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
        binding.ivFullscreenImage.scaleType = ImageView.ScaleType.MATRIX

        val scaleX = viewWidth / drawable.intrinsicWidth.toFloat()
        val scaleY = viewHeight / drawable.intrinsicHeight.toFloat()
        val scale = kotlin.math.min(scaleX, scaleY)

        matrix.reset()
        matrix.setScale(scale, scale)

        origWidth = drawable.intrinsicWidth.toFloat() * scale
        origHeight = drawable.intrinsicHeight.toFloat() * scale

        val redundantYSpace = viewHeight - origHeight
        val redundantXSpace = viewWidth - origWidth

        matrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)

        binding.ivFullscreenImage.imageMatrix = matrix
        saveScale = 1f
        minScale = 1f
    }

    private fun applySingleMediaTransform(scale: Float, offsetXFraction: Float, offsetYFraction: Float) {
        val drawable = binding.ivFullscreenImage.drawable ?: return
        if (origWidth <= 0f || origHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return

        val clampedScale = scale.coerceIn(minScale, maxScale)
        val contentWidth = origWidth * clampedScale
        val contentHeight = origHeight * clampedScale
        val centeredX = (viewWidth - contentWidth) / 2f
        val centeredY = (viewHeight - contentHeight) / 2f
        val maxShiftX = ((contentWidth - viewWidth) / 2f).coerceAtLeast(0f)
        val maxShiftY = ((contentHeight - viewHeight) / 2f).coerceAtLeast(0f)
        val targetTransX = centeredX + offsetXFraction.coerceIn(-1f, 1f) * maxShiftX
        val targetTransY = centeredY + offsetYFraction.coerceIn(-1f, 1f) * maxShiftY
        val baseScale = origWidth / drawable.intrinsicWidth.toFloat()

        matrix.reset()
        matrix.setScale(clampedScale * baseScale, clampedScale * baseScale)
        matrix.postTranslate(targetTransX, targetTransY)
        saveScale = clampedScale
        fixTranslation()
        binding.ivFullscreenImage.imageMatrix = matrix
        binding.ivFullscreenImage.invalidate()
    }

    private fun captureSingleMediaTransformSnapshot(): SingleMediaTransformSnapshot? {
        val drawable = binding.ivFullscreenImage.drawable ?: return null
        if (origWidth <= 0f || origHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null

        matrix.getValues(matrixValues)
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

        return SingleMediaTransformSnapshot(
            scale = saveScale,
            offsetXFraction = offsetXFraction,
            offsetYFraction = offsetYFraction
        )
    }

    private fun showReactionPicker(anchor: View, touchYScreen: Float) {
        val cid = chatId ?: return
        val mid = messageId ?: return
        ReactionPopupOverlay.show(
            activity = this,
            anchor = anchor,
            isOutgoing = parentIsOutgoing,
            ownReaction = parentOwnReaction,
            onEmojiSelected = { emoji -> toggleReaction(cid, mid, emoji) },
            onMoreRequested = {
                ReactionEmojiPickerSheet.show(supportFragmentManager) { emoji ->
                    toggleReaction(cid, mid, emoji)
                }
            },
            touchYScreen = touchYScreen
        )
    }

    private fun toggleReaction(chatId: String, messageId: String, emoji: String) {
        val nextOwnReaction = if (parentOwnReaction == emoji) null else emoji
        parentOwnReaction = nextOwnReaction
        lifecycleScope.launch {
            repository.toggleReaction(chatId, messageId, emoji)
        }
    }
    
    // ==================== COMMON METHODS ====================
    
    private fun setupWindowInsets() {
        val baseToolbarStart = binding.toolbar.paddingStart
        val baseToolbarTop = binding.toolbar.paddingTop
        val baseToolbarEnd = binding.toolbar.paddingEnd
        val baseToolbarBottom = binding.toolbar.paddingBottom

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val systemBars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())

            safeTopInsetPx = kotlin.math.max(systemBars.top, displayCutout.top)
            safeBottomInsetPx = kotlin.math.max(systemBars.bottom, displayCutout.bottom)

            binding.toolbar.setPaddingRelative(
                baseToolbarStart,
                baseToolbarTop + safeTopInsetPx,
                baseToolbarEnd,
                baseToolbarBottom
            )

            if (isMultiMediaMode) {
                binding.mediaPager.applyMediaPagerPadding(isFocused = focusedMediaIndex != null)
            }

            insets
        }

        androidx.core.view.ViewCompat.requestApplyInsets(window.decorView)
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

    private fun applyChromeVisibility(animated: Boolean) {
        binding.toolbar.animate().cancel()
        if (isChromeVisible) {
            showSystemBars()
            binding.toolbar.alpha = 1f
            binding.toolbar.visibility = View.VISIBLE
            return
        }

        hideSystemBars()
        if (animated && binding.toolbar.visibility == View.VISIBLE) {
            binding.toolbar.alpha = 0f
        } else {
            binding.toolbar.alpha = 0f
            binding.toolbar.visibility = View.GONE
        }
    }
    
    private fun toggleToolbar() {
        val wasVisible = isChromeVisible
        isChromeVisible = !isChromeVisible

        if (isChromeVisible) {
            showSystemBars()
            binding.toolbar.visibility = View.VISIBLE
            binding.toolbar.alpha = 0f
        }

        binding.toolbar.animate()
            .alpha(if (isChromeVisible) 1f else 0f)
            .setDuration(200)
            .withEndAction {
                if (isChromeVisible) {
                    binding.toolbar.visibility = View.VISIBLE
                } else {
                    binding.toolbar.visibility = View.GONE
                    hideSystemBars()
                }
            }
            .start()

        if (!wasVisible && isChromeVisible) {
            showSystemBars()
        }
    }
    
    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener { 
            if (selectedMediaIndices.isNotEmpty()) {
                clearMediaSelection()
            } else if (focusedMediaIndex != null) {
                clearFocusedMedia()
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }
        
        binding.btnReply.setOnClickListener { 
            finishWithMediaAction(ACTION_REPLY)
        }
        
        binding.btnForward.setOnClickListener { 
            finishWithMediaAction(ACTION_FORWARD)
        }
        
        binding.btnShare.setOnClickListener {
            shareCurrentMedia()
        }

        binding.btnSave.setOnClickListener {
            saveCurrentMediaToGallery()
        }
        
        binding.btnDelete.setOnClickListener { 
            finishWithMediaAction(ACTION_DELETE)
        }
        
        binding.btnMore.setOnClickListener {
            showOverflowMenu(it)
        }
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SHOW_IN_CHAT, 0, "Show in chat")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SHOW_IN_CHAT -> {
                        finishWithMediaAction(ACTION_SHOW_IN_CHAT)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun finishWithMediaAction(action: String) {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(RESULT_ACTION, action)
                putExtra(RESULT_CHAT_ID, chatId)
                putExtra(RESULT_MESSAGE_ID, messageId)
                putExtra(RESULT_MEDIA_INDEX, currentIndex)
                putIntegerArrayListExtra(RESULT_MEDIA_INDICES, selectedResultIndices())
            }
        )
        finish()
    }

    private fun currentMediaItem(): MediaItem? {
        if (isMultiMediaMode) return mediaItems.getOrNull(currentIndex)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)?.takeIf { it.isNotBlank() } ?: return null
        return MediaItem(url = imageUrl, type = MediaType.IMAGE)
    }

    private fun mediaMimeType(item: MediaItem): String = if (item.isVideo) "video/*" else "image/*"

    private fun shareCurrentMedia() {
        val item = currentMediaItem() ?: return
        val source = item.displayUrl.takeIf { it.isNotBlank() } ?: return
        shareUriForLocalSource(source)?.let { uri ->
            launchShareIntent(uri, item)
            return
        }

        Toast.makeText(this, "Preparing media...", Toast.LENGTH_SHORT).show()
        Glide.with(this)
            .asFile()
            .load(source)
            .into(object : CustomTarget<File>() {
                override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                    try {
                        val shareFile = copyToShareCache(resource, item)
                        val contentUri = FileProvider.getUriForFile(
                            this@MediaViewerActivity,
                            "$packageName.fileprovider",
                            shareFile
                        )
                        launchShareIntent(contentUri, item)
                    } catch (e: Exception) {
                        Toast.makeText(this@MediaViewerActivity, "Failed to share media", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    Toast.makeText(this@MediaViewerActivity, "Failed to prepare media", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun shareUriForLocalSource(source: String): Uri? {
        val parsed = runCatching { Uri.parse(source) }.getOrNull() ?: return null
        return when {
            parsed.scheme == "content" -> parsed
            parsed.scheme == "file" -> parsed.path?.let(::File)?.takeIf { it.exists() }?.let { file ->
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
            parsed.scheme.isNullOrBlank() -> File(source).takeIf { it.exists() }?.let { file ->
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }
            else -> null
        }
    }

    private fun copyToShareCache(source: File, item: MediaItem): File {
        val extension = if (item.isVideo) "mp4" else "jpg"
        val shareDir = File(cacheDir, "shared_media").apply { mkdirs() }
        val destination = File(shareDir, "glyph_${System.currentTimeMillis()}.$extension")
        source.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }

    private fun launchShareIntent(uri: Uri, item: MediaItem) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mediaMimeType(item)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(contentResolver, "media", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share"))
    }

    private fun saveCurrentMediaToGallery() {
        val item = currentMediaItem() ?: return
        val url = item.displayUrl.takeIf { it.isNotBlank() } ?: return

        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()

        Glide.with(this)
            .asFile()
            .load(url)
            .into(object : CustomTarget<java.io.File>() {
                override fun onResourceReady(resource: java.io.File, transition: Transition<in java.io.File>?) {
                    val filename = if (item.isVideo) "VID_${System.currentTimeMillis()}.mp4" else "IMG_${System.currentTimeMillis()}.jpg"
                    val mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
                    val directory = if (item.isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES
                    var fos: java.io.OutputStream? = null
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            contentResolver?.also { resolver ->
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                                }
                                val collection = if (item.isVideo) {
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                } else {
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                }
                                val mediaUri: Uri? = resolver.insert(collection, contentValues)
                                fos = mediaUri?.let { resolver.openOutputStream(it) }
                            }
                        } else {
                            val mediaDir = android.os.Environment.getExternalStoragePublicDirectory(directory)
                            if (!mediaDir.exists()) mediaDir.mkdirs()
                            val mediaFile = java.io.File(mediaDir, filename)
                            fos = java.io.FileOutputStream(mediaFile)
                        }

                        fos?.use { outputStream ->
                            // Copy file directly without re-encoding to preserve exact bytes and validation
                            java.io.FileInputStream(resource).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                            Toast.makeText(this@MediaViewerActivity, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this@MediaViewerActivity, "Failed to save", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
                
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    Toast.makeText(this@MediaViewerActivity, "Failed to load media for saving", Toast.LENGTH_SHORT).show()
                }
            })
    }
    
    // ==================== ADAPTERS ====================
    
    /**
     * Adapter for the main media pager with zoom/pan support for each item
     */
    inner class MediaPagerAdapter(
        private val items: List<MediaItem>,
        private val onVideoClick: (MediaItem, Int) -> Unit
    ) : RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder>() {
        
        inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val imageView: ZoomableImageView = itemView.findViewById(R.id.zoomableImage)
            private val playButton: ImageView = itemView.findViewById(R.id.ivPlayButton)
            private val selectionOverlay: View = itemView.findViewById(R.id.selectionOverlay)
            private val selectionCheck: ImageView = itemView.findViewById(R.id.selectionCheck)
            
            fun bind(item: MediaItem, position: Int) {
                val focusedIndex = focusedMediaIndex
                val focused = focusedMediaIndex == position
                val hiddenByFocus = focusedIndex != null && !focused
                itemView.layoutParams = itemView.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = if (hiddenByFocus) 0 else mediaItemHeight(item, focused)
                    (this as? ViewGroup.MarginLayoutParams)?.bottomMargin = when {
                        hiddenByFocus || focused -> 0
                        else -> dp(8f)
                    }
                }

                if (hiddenByFocus) {
                    itemView.animate().cancel()
                    itemView.visibility = View.GONE
                    itemView.alpha = 0f
                    itemView.scaleX = 1f
                    itemView.scaleY = 1f
                    itemView.isEnabled = false
                    imageView.isEnabled = false
                    playButton.visibility = View.GONE
                    selectionOverlay.visibility = View.GONE
                    selectionCheck.visibility = View.GONE
                    return
                }

                itemView.visibility = View.VISIBLE
                itemView.isEnabled = true
                imageView.isEnabled = true
                itemView.animate().cancel()
                itemView.alpha = if (focused && pendingFocusRevealIndex == position) 0f else 1f
                itemView.scaleX = 1f
                itemView.scaleY = 1f

                val displayUrl = item.displayUrl
                if (focused && pendingFocusedDrawable != null && pendingFocusRevealIndex == position) {
                    debugFocus("bind focused item=$position deferring pending drawable until fullscreen preDraw")
                    imageView.setTag(R.id.zoomableImage, displayUrl)
                    imageView.suspendImageLayoutUntilNextDrawable()
                } else if (imageView.getTag(R.id.zoomableImage) != displayUrl || imageView.drawable == null) {
                    if (focused || pendingFocusRevealIndex == position) {
                        debugFocus("bind item=$position loading via Glide focused=$focused pendingReveal=${pendingFocusRevealIndex == position}")
                    }
                    imageView.setTag(R.id.zoomableImage, displayUrl)
                    Glide.with(itemView.context)
                        .load(displayUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                        .dontAnimate()
                        .placeholder(R.drawable.ic_video_placeholder)
                        .into(imageView)
                }
                
                // Show play button for videos
                playButton.visibility = if (item.isVideo) View.VISIBLE else View.GONE
                val selected = position in selectedMediaIndices
                selectionOverlay.visibility = if (selected) View.VISIBLE else View.GONE
                selectionCheck.visibility = if (selected) View.VISIBLE else View.GONE
                imageView.alpha = 1f
                playButton.alpha = 1f
                
                // Handle clicks
                imageView.setOnSingleTapListener {
                    if (isSelectionMode()) {
                        toggleMediaSelection(position)
                    } else if (focusedMediaIndex == position) {
                        if (item.isVideo) {
                            onVideoClick(item, position)
                        } else {
                            toggleToolbar()
                        }
                    } else {
                        focusMediaAt(position, itemView)
                    }
                }

                imageView.setOnLongPressListener { rawY ->
                    if (isMultiMediaMode && mediaItems.size > 1) {
                        toggleMediaSelection(position)
                    } else {
                        showReactionPicker(itemView, rawY)
                    }
                }
                
                playButton.setOnClickListener {
                    if (isSelectionMode()) {
                        toggleMediaSelection(position)
                    } else if (focusedMediaIndex == position) {
                        currentIndex = position
                        onVideoClick(item, position)
                    } else {
                        focusMediaAt(position, itemView)
                    }
                }

                itemView.setOnLongClickListener {
                    if (isMultiMediaMode && mediaItems.size > 1) {
                        toggleMediaSelection(position)
                        true
                    } else {
                        false
                    }
                }
            }
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_pager, parent, false)
            return MediaViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            holder.bind(items[position], position)
        }
        
        override fun getItemCount() = items.size
    }

    private fun debugFocus(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(FOCUS_DEBUG_TAG, message)
        }
    }
    
}
