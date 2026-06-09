package com.glyph.glyph_v3.ui.chat.picker

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.remote.KlipyMediaItem
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * WhatsApp-style emoji/GIF/sticker picker panel.
 *
 * This custom view replaces the keyboard area with a tabbed picker.
 * It supports two height modes:
 *
 * **COMPACT** (default):
 *   Height = measured keyboard height + navigation bar.
 *   Used when the picker is opened normally.
 *
 * **EXPANDED** (search mode):
 *   Height = full available window height (status bar to bottom).
 *   Activated when the search EditText inside a tab fragment gains focus
 *   and the keyboard opens. The panel expands to fill the screen so the
 *   search bar, results grid AND the keyboard all remain visible.
 *   The panel handles its own bottom padding for the IME so results
 *   aren't hidden behind the keyboard.
 */
class EmojiPickerPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), NestedScrollingParent3 {

    // ── Nested scroll parent (blocks CoordinatorLayout interference) ──
    private val nestedScrollHelper = NestedScrollingParentHelper(this)

    // ── Height modes ──────────────────────────────────────────────────
    enum class PanelMode { COMPACT, HALF, EXPANDED }

    // ── Views ─────────────────────────────────────────────────────────

    private val tabEmoji: LinearLayout
    private val tabGif: LinearLayout
    private val tabSticker: LinearLayout
    private val tabMeme: LinearLayout
    private val ivTabEmoji: ImageView
    private val ivTabGif: ImageView
    private val ivTabSticker: ImageView
    private val ivTabMeme: ImageView
    private val tvTabEmoji: TextView
    private val tvTabGif: TextView
    private val tvTabSticker: TextView
    private val tvTabMeme: TextView
    private val tabIndicator: View
    private val viewPager: ViewPager2
    private val tvPoweredBy: TextView

    // ── State ─────────────────────────────────────────────────────────

    private var viewModel: PickerViewModel? = null
    private var activity: FragmentActivity? = null
    private var bottomInsetPx: Int = 0

    /** Saved compact height (keyboard + nav bar). */
    private var compactHeightPx: Int = 0
    /** Keyboard height only (no nav bar). */
    private var savedKeyboardHeightPx: Int = 0
    /** Current mode of the panel. */
    var panelMode: PanelMode = PanelMode.COMPACT
        private set

    /** Tracks whether the expanded panel is currently snapped to half height. */
    private var isHalfExpanded = false

    var onEmojiSelected: ((KlipyMediaItem) -> Unit)? = null
    var onSystemEmojiSelected: ((String) -> Unit)? = null
    var onGifSelected: ((KlipyMediaItem) -> Unit)? = null
    var onStickerSelected: ((KlipyMediaItem) -> Unit)? = null
    var onMemeSelected: ((KlipyMediaItem) -> Unit)? = null

    /** Called when panel mode changes so the host activity can adjust its layout. */
    var onModeChanged: ((PanelMode) -> Unit)? = null

    var isPickerVisible: Boolean = false
        private set

    // ── Drag-to-close state ───────────────────────────────────────────
    private var isDragging = false
    private var dragStartY = 0f
    private var initialTranslationY = 0f
    private val dragToHalfThreshold = 0.32f  // Drag a meaningful distance to settle at half height
    private val dragToCloseThreshold = 0.18f // From half-height, a second deliberate pull closes
    private var gestureDetector: GestureDetector? = null

    /** Callback when user requests to close the panel via drag gesture. */
    var onDragClose: (() -> Unit)? = null
    
    /** Called when a drag in expanded search mode should collapse back to compact. */
    var onCollapseToCompactRequested: (() -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_emoji_picker_panel, this, true)

        tabEmoji = findViewById(R.id.tabEmoji)
        tabGif = findViewById(R.id.tabGif)
        tabSticker = findViewById(R.id.tabSticker)
        tabMeme = findViewById(R.id.tabMeme)
        ivTabEmoji = findViewById(R.id.ivTabEmoji)
        ivTabGif = findViewById(R.id.ivTabGif)
        ivTabSticker = findViewById(R.id.ivTabSticker)
        ivTabMeme = findViewById(R.id.ivTabMeme)
        tvTabEmoji = findViewById(R.id.tvTabEmoji)
        tvTabGif = findViewById(R.id.tvTabGif)
        tvTabSticker = findViewById(R.id.tvTabSticker)
        tvTabMeme = findViewById(R.id.tvTabMeme)
        tabIndicator = findViewById(R.id.tabIndicator)
        viewPager = findViewById(R.id.vpPickerPager)
        tvPoweredBy = findViewById(R.id.tvPoweredBy)

        visibility = View.GONE

        setupTabs()
        setupWindowInsets()
        setupDragToClose()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Update tab indicator width to be 1/4 of total width (since we have 4 tabs)
        val tabCount = 4
        val params = tabIndicator.layoutParams
        params.width = w / tabCount
        tabIndicator.layoutParams = params
        
        // Also update translation to match current page
        val tabWidth = w / tabCount.toFloat()
        tabIndicator.translationX = viewPager.currentItem * tabWidth
    }

    // ── Public API ────────────────────────────────────────────────────

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            bottomInsetPx = navBar.bottom.coerceAtLeast(systemBars.bottom)

            if (panelMode == PanelMode.COMPACT) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomInsetPx)
            }
            // In EXPANDED mode, bottom padding is managed by updateExpandedBottomPadding()

            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    fun attachToActivity(hostActivity: FragmentActivity) {
        activity = hostActivity
        viewModel = ViewModelProvider(hostActivity)[PickerViewModel::class.java]

        val adapter = PickerPagerAdapter(hostActivity)
        viewPager.adapter = adapter
        viewPager.isUserInputEnabled = true
        viewPager.offscreenPageLimit = 2

        // Disable nested scrolling on ViewPager2's internal RecyclerView
        // to prevent CoordinatorLayout from receiving scroll events
        val vpChild = viewPager.getChildAt(0)
        if (vpChild is androidx.recyclerview.widget.RecyclerView) {
            vpChild.isNestedScrollingEnabled = false
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val tab = tabFromPosition(position)
                viewModel?.selectTab(tab)
                updateTabSelection(tab)
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                animateTabIndicator(position, positionOffset)
            }
        })
    }

    // ── Drag-to-close gesture ─────────────────────────────────────────

    private fun setupDragToClose() {
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Only handle drag in expanded mode
                if (panelMode != PanelMode.EXPANDED || !isPickerVisible) return false
                
                // Check if the current tab's RecyclerView is at top
                if (!isCurrentRecyclerViewAtTop()) return false
                
                // Only drag downward (positive distanceY means scrolling up, we want down)
                if (distanceY > 0) return false
                
                return true
            }
        })
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Only intercept in expanded mode
        if (panelMode != PanelMode.EXPANDED || !isPickerVisible) {
            return super.onInterceptTouchEvent(ev)
        }

        val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val startDragThreshold = (touchSlop * 2.5f).toInt()

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = ev.rawY - dragStartY
                
                // Only start dragging if:
                // 1. Moving downward (deltaY > touchSlop)
                // 2. Either in top bar OR RecyclerView is at top
                if (deltaY > startDragThreshold && !isDragging) {
                    val isAtTop = isCurrentRecyclerViewAtTop()
                    val startedInTopBar = ev.y < (resources.displayMetrics.density * 100) // ~100dp top area
                    
                    if (isAtTop || startedInTopBar) {
                        isDragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
        }

        return if (isDragging) true else super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Only handle touch in expanded mode when dragging
        if (panelMode != PanelMode.EXPANDED || !isPickerVisible) {
            return super.onTouchEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaY = event.rawY - dragStartY
                    // Only allow downward drag
                    if (deltaY > 0) {
                        translationY = deltaY
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val deltaY = event.rawY - dragStartY
                    val panelHeight = height.toFloat().coerceAtLeast(1f)
                    
                    
                    if (!isHalfExpanded) {
                        val minHalfDistance = maxOf(
                            panelHeight * dragToHalfThreshold,
                            (120 * resources.displayMetrics.density)
                        )
                        if (deltaY > minHalfDistance) {
                            if (onCollapseToCompactRequested != null) {
                                onCollapseToCompactRequested?.invoke()
                                animatePanelSnapBack()
                            } else {
                                snapToCompactHeight()
                            }
                        } else {
                            animatePanelSnapBack()
                        }
                    } else {
                        val minCloseDistance = maxOf(
                            panelHeight * dragToCloseThreshold,
                            (80 * resources.displayMetrics.density)
                        )
                        if (deltaY > minCloseDistance) {
                            animatePanelClose()
                        } else {
                            animatePanelSnapBack()
                        }
                    }
                }
                
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun isCurrentRecyclerViewAtTop(): Boolean {
        // More robust way to find current fragment's RecyclerView
        val rv = viewPager.getChildAt(0) as? RecyclerView ?: return true
        val lm = rv.layoutManager ?: return true
        val currentView = lm.findViewByPosition(viewPager.currentItem) ?: return true
        
        val contentRv = currentView.findViewById<RecyclerView>(R.id.rvPickerGrid)
        return contentRv == null || !contentRv.canScrollVertically(-1)
    }

    private fun animatePanelClose() {
        val targetY = height.toFloat()
        animate()
            .translationY(targetY)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Set visible false and GONE immediately before callback
                // to prevent hide() from re-animating
                isHalfExpanded = false
                panelMode = PanelMode.COMPACT
                isPickerVisible = false
                visibility = View.GONE
                translationY = 0f
                onModeChanged?.invoke(PanelMode.COMPACT)
                onDragClose?.invoke()
            }
            .start()
    }

    private fun animatePanelSnapBack() {
        animate()
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Set the panel height to match keyboard height (COMPACT mode).
     */
    fun setPickerHeight(keyboardHeightPx: Int, navPaddingPx: Int = 0) {
        bottomInsetPx = maxOf(bottomInsetPx, navPaddingPx)
        savedKeyboardHeightPx = keyboardHeightPx
        compactHeightPx = keyboardHeightPx + bottomInsetPx

        // Only apply if we're in compact mode
        if (panelMode == PanelMode.COMPACT) {
            applyCompactHeight()
        }
    }

    private fun applyCompactHeight() {
        val lp = layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, compactHeightPx)
        lp.height = compactHeightPx
        layoutParams = lp
        setPadding(paddingLeft, 0, paddingRight, bottomInsetPx)
    }

    private fun applyExpandedHeight() {
        val expandedHeightPx = getExpandedHeightPx()
        val lp = layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, expandedHeightPx)
        lp.height = expandedHeightPx
        layoutParams = lp
        setPadding(paddingLeft, 0, paddingRight, bottomInsetPx)
    }

    private fun applyHalfHeight() {
        val halfHeightPx = getHalfHeightPx()
        val lp = layoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, halfHeightPx)
        lp.height = halfHeightPx
        layoutParams = lp
        setPadding(paddingLeft, 0, paddingRight, bottomInsetPx)
    }

    private fun getExpandedHeightPx(): Int {
        val windowHeight = getWindowHeight()
        val statusBarHeight = getStatusBarHeight()
        return (windowHeight - statusBarHeight).coerceAtLeast(1)
    }

    private fun getHalfHeightPx(): Int {
        val expandedHeightPx = getExpandedHeightPx()
        return (expandedHeightPx / 2f).roundToInt().coerceAtLeast(bottomInsetPx + 1)
    }

    private fun snapToCompactHeight() {
        isHalfExpanded = false
        panelMode = PanelMode.COMPACT
        applyCompactHeight()
        onModeChanged?.invoke(PanelMode.COMPACT)
        animate()
            .translationY(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ── Expanded search mode ──────────────────────────────────────────

    /**
     * Expand the panel to full screen height.
     * Called when the search EditText in a tab fragment gains focus and the keyboard opens.
     * The panel grows to fill the entire window so both the search bar and the
     * keyboard-overlapping results grid remain visible.
     */
    fun expandForSearch(imeHeightPx: Int) {
        if (panelMode == PanelMode.EXPANDED && !isHalfExpanded) {
            // Already expanded — just update bottom padding for the keyboard
            updateExpandedBottomPadding(imeHeightPx)
            return
        }

        if (panelMode == PanelMode.HALF) {
            // Stay half-height until the user intentionally swipes down again.
            updateExpandedBottomPadding(imeHeightPx)
            return
        }

        panelMode = PanelMode.EXPANDED
        isHalfExpanded = false

        applyExpandedHeight()

        // Pad bottom for the keyboard so grid results remain scrollable above it
        updateExpandedBottomPadding(imeHeightPx)

        onModeChanged?.invoke(PanelMode.EXPANDED)
    }

    /**
     * Collapse back to compact (keyboard-height) mode.
     * Called when the search EditText loses focus or the back button is pressed.
     */
    fun collapseToCompact() {
        if (panelMode == PanelMode.COMPACT) return

        panelMode = PanelMode.COMPACT
        isHalfExpanded = false

        applyCompactHeight()
        onModeChanged?.invoke(PanelMode.COMPACT)
    }

    /**
     * In expanded mode, the keyboard overlaps the bottom of the panel.
     * We set bottom padding = keyboard height so the RecyclerView content
     * scrolls above the keyboard and the search bar stays visible at top.
     */
    private fun updateExpandedBottomPadding(imeHeightPx: Int) {
        setPadding(paddingLeft, 0, paddingRight, imeHeightPx.coerceAtLeast(bottomInsetPx))
    }

    // ── Show / Hide ───────────────────────────────────────────────────

    fun show(animate: Boolean = true) {
        if (isPickerVisible) return
        isPickerVisible = true
        visibility = View.VISIBLE

        panelMode = PanelMode.COMPACT
        isHalfExpanded = false
        applyCompactHeight()

        if (animate) {
            val targetHeight = layoutParams?.height ?: 0
            if (targetHeight > 0) {
                translationY = targetHeight.toFloat()
                alpha = 1f
                animate()
                    .translationY(0f)
                    .setDuration(250)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                alpha = 0f
                animate().alpha(1f).setDuration(250).start()
            }
        } else {
            animate().cancel()
            translationY = 0f
            alpha = 1f
        }

        viewModel?.ensureDataLoaded(viewModel?.selectedTab?.value ?: PickerViewModel.PickerTab.EMOJI)
    }

    fun hide() {
        if (!isPickerVisible || visibility == View.GONE) return
        isPickerVisible = false

        // Always collapse back to compact when hiding
        panelMode = PanelMode.COMPACT
        isHalfExpanded = false

        val targetHeight = height.toFloat()
        animate()
            .translationY(targetHeight)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                visibility = View.GONE
                translationY = 0f
                applyCompactHeight()
            }
            .start()
    }

    fun toggle() {
        if (isPickerVisible) hide() else show()
    }

    // ── Tab management ────────────────────────────────────────────────

    private fun setupTabs() {
        tabEmoji.setOnClickListener { selectTab(PickerViewModel.PickerTab.EMOJI) }
        tabGif.setOnClickListener { selectTab(PickerViewModel.PickerTab.GIF) }
        tabSticker.setOnClickListener { selectTab(PickerViewModel.PickerTab.STICKER) }
        tabMeme.setOnClickListener { selectTab(PickerViewModel.PickerTab.MEME) }
    }

    private fun selectTab(tab: PickerViewModel.PickerTab) {
        val position = positionFromTab(tab)
        viewPager.setCurrentItem(position, true)
        viewModel?.selectTab(tab)
        updateTabSelection(tab)
    }

    /** Programmatically navigate to the GIF tab. */
    fun showGifTab() { selectTab(PickerViewModel.PickerTab.GIF) }
    /** Programmatically navigate to the Sticker tab. */
    fun showStickerTab() { selectTab(PickerViewModel.PickerTab.STICKER) }
    /** Programmatically navigate to the Meme tab. */
    fun showMemeTab() { selectTab(PickerViewModel.PickerTab.MEME) }

    private fun updateTabSelection(tab: PickerViewModel.PickerTab) {
        val activeColor = ContextCompat.getColor(context, R.color.picker_tab_active)
        val inactiveColor = ContextCompat.getColor(context, R.color.picker_tab_inactive)

        ivTabEmoji.setColorFilter(if (tab == PickerViewModel.PickerTab.EMOJI) activeColor else inactiveColor)
        ivTabGif.setColorFilter(if (tab == PickerViewModel.PickerTab.GIF) activeColor else inactiveColor)
        ivTabSticker.setColorFilter(if (tab == PickerViewModel.PickerTab.STICKER) activeColor else inactiveColor)
        ivTabMeme.setColorFilter(if (tab == PickerViewModel.PickerTab.MEME) activeColor else inactiveColor)

        tvTabEmoji.setTextColor(if (tab == PickerViewModel.PickerTab.EMOJI) activeColor else inactiveColor)
        tvTabGif.setTextColor(if (tab == PickerViewModel.PickerTab.GIF) activeColor else inactiveColor)
        tvTabSticker.setTextColor(if (tab == PickerViewModel.PickerTab.STICKER) activeColor else inactiveColor)
        tvTabMeme.setTextColor(if (tab == PickerViewModel.PickerTab.MEME) activeColor else inactiveColor)
    }

    private fun animateTabIndicator(position: Int, offset: Float) {
        val tabCount = 4
        val tabWidth = width / tabCount.toFloat()
        val translationX = (position + offset) * tabWidth
        tabIndicator.translationX = translationX
    }

    // ── ViewPager adapter ────────────────────────────────────────────

    private inner class PickerPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount() = 4

        override fun createFragment(position: Int): PickerTabFragment {
            val tab = tabFromPosition(position)
            return PickerTabFragment.newInstance(tab).also { fragment ->
                fragment.onEmojiSelected = { item -> onEmojiSelected?.invoke(item) }
                fragment.onSystemEmojiSelected = { emoji -> onSystemEmojiSelected?.invoke(emoji) }
                fragment.onGifSelected = { item -> onGifSelected?.invoke(item) }
                fragment.onStickerSelected = { item -> onStickerSelected?.invoke(item) }
                fragment.onMemeSelected = { item -> onMemeSelected?.invoke(item) } // Assuming PickerTabFragment exposes this
                fragment.onSearchFocusChanged = { hasFocus ->
                    onSearchFocusChanged?.invoke(hasFocus)
                }
            }
        }
    }

    /**
     * Callback when any tab fragment's search EditText gains/loses focus.
     * The host activity should listen for this to open/close the keyboard
     * and call [expandForSearch] / [collapseToCompact].
     */
    var onSearchFocusChanged: ((hasFocus: Boolean) -> Unit)? = null

    /**
     * Tells all attached tab fragments to clear search focus.
     * Called before collapsing so the focus-change listener
     * doesn't re-trigger expansion.
     */
    fun clearAllSearchFocus() {
        val act = activity ?: return
        val fm = act.supportFragmentManager
        for (i in 0 until 4) {
            val tag = "f$i"
            (fm.findFragmentByTag(tag) as? PickerTabFragment)?.clearSearchFocus()
        }
        // Also try the ViewPager2 internal tag format
        for (i in 0 until 4) {
            val fragment = fm.findFragmentByTag("f${viewPager.id}$i") as? PickerTabFragment
            fragment?.clearSearchFocus()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun tabFromPosition(position: Int): PickerViewModel.PickerTab = when (position) {
        0 -> PickerViewModel.PickerTab.EMOJI
        1 -> PickerViewModel.PickerTab.GIF
        2 -> PickerViewModel.PickerTab.STICKER
        3 -> PickerViewModel.PickerTab.MEME
        else -> PickerViewModel.PickerTab.EMOJI
    }

    private fun positionFromTab(tab: PickerViewModel.PickerTab): Int = when (tab) {
        PickerViewModel.PickerTab.EMOJI -> 0
        PickerViewModel.PickerTab.GIF -> 1
        PickerViewModel.PickerTab.STICKER -> 2
        PickerViewModel.PickerTab.MEME -> 3
    }

    private fun getWindowHeight(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels
        }
    }

    private fun getStatusBarHeight(): Int {
        val insets = ViewCompat.getRootWindowInsets(this)
        return insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    }

    // ── NestedScrollingParent3 implementation ─────────────────────────
    // Absorb all nested vertical scrolls inside the picker so
    // CoordinatorLayout / AppBarLayout never react to them.

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        // Consume all vertical scroll events from children
        return (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        nestedScrollHelper.onNestedScrollAccepted(child, target, axes, type)
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // Don't consume in pre-scroll — let the RecyclerView scroll normally.
        // We only need to stop propagation upward.
    }

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray
    ) {
        // Absorb any unconsumed scroll so it doesn't propagate to CoordinatorLayout
        consumed[1] += dyUnconsumed
    }

    override fun onNestedScroll(
        target: View, dxConsumed: Int, dyConsumed: Int,
        dxUnconsumed: Int, dyUnconsumed: Int, type: Int
    ) {
        // Absorb unconsumed scroll
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        nestedScrollHelper.onStopNestedScroll(target, type)
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean {
        return false // Let child fling naturally
    }

    override fun onNestedFling(target: View, velocityX: Float, velocityY: Float, consumed: Boolean): Boolean {
        return false // Don't interfere with flings
    }
}
