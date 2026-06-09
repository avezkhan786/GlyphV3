package com.glyph.glyph_v3.ui.chat.picker

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.remote.KlipyMediaItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Fragment for a single picker tab (Emoji / GIF / Sticker).
 * Three instances of this fragment live inside a ViewPager2.
 *
 * The fragment observes the correct [PickerViewModel.TabState] based on
 * its [tabType] argument and displays items in a RecyclerView grid.
 */
class PickerTabFragment : Fragment() {

    companion object {
        private const val ARG_TAB_TYPE = "tab_type"

        fun newInstance(tab: PickerViewModel.PickerTab): PickerTabFragment {
            return PickerTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TAB_TYPE, tab.name)
                }
            }
        }
    }

    private val viewModel: PickerViewModel by activityViewModels()

    private lateinit var tabType: PickerViewModel.PickerTab
    private lateinit var adapter: PickerGridAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText

    var onEmojiSelected: ((KlipyMediaItem) -> Unit)? = null
    var onSystemEmojiSelected: ((String) -> Unit)? = null
    var onGifSelected: ((KlipyMediaItem) -> Unit)? = null
    var onStickerSelected: ((KlipyMediaItem) -> Unit)? = null
    var onMemeSelected: ((KlipyMediaItem) -> Unit)? = null

    /** Called when the search EditText gains/loses focus. */
    var onSearchFocusChanged: ((hasFocus: Boolean) -> Unit)? = null
    
    /** Track if initial data load has completed to prevent scroll reset loops */
    private var hasRestoredScrollPosition = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabType = PickerViewModel.PickerTab.valueOf(
            arguments?.getString(ARG_TAB_TYPE) ?: PickerViewModel.PickerTab.EMOJI.name
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_picker_tab, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        
        recyclerView = view.findViewById(R.id.rvPickerGrid)
        progressBar = view.findViewById(R.id.pbPickerLoading)
        tvEmpty = view.findViewById(R.id.tvPickerEmpty)
        etSearch = view.findViewById(R.id.etPickerSearch)

        
        if (tabType == PickerViewModel.PickerTab.EMOJI) {
            setupEmojiSourceSwitcher(view)
        }
        
        setupCategoryStrip(view)

        setupSearchBar()
        setupRecyclerView()
        observeState()

        // Load data for this tab
        viewModel.ensureDataLoaded(tabType)
    }

    private fun setupEmojiSourceSwitcher(view: View) {
        val layoutSource = view.findViewById<View>(R.id.layoutEmojiSource) ?: return
        layoutSource.visibility = View.VISIBLE
        
        val tvSystem = view.findViewById<TextView>(R.id.tvSourceSystem)
        val tvKlipy = view.findViewById<TextView>(R.id.tvSourceKlipy)

        tvSystem.setOnClickListener { viewModel.setEmojiSource(PickerViewModel.EmojiSource.SYSTEM) }
        tvKlipy.setOnClickListener { viewModel.setEmojiSource(PickerViewModel.EmojiSource.KLIPY) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.emojiSource.collectLatest { source ->
                    updateSourceSwitcherUI(source, tvSystem, tvKlipy)
                    
                    // Hide search bar if system emoji (to save space)
                    etSearch.visibility = if (source == PickerViewModel.EmojiSource.SYSTEM) View.GONE else View.VISIBLE
                    
                    // Update search bar hint based on source
                    if (source == PickerViewModel.EmojiSource.SYSTEM) {
                        etSearch.hint = "Search Emoji"
                    } else {
                        etSearch.hint = "Search KLIPY"
                    }
                }
            }
        }
    }

    private fun updateSourceSwitcherUI(source: PickerViewModel.EmojiSource, tvSystem: TextView, tvKlipy: TextView) {
        val selectedBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_tab_selected) 
                         ?: ContextCompat.getDrawable(requireContext(), R.drawable.bg_chat_input_themed) // Fallback
        
        val activeTextColor = ContextCompat.getColor(requireContext(), R.color.light_text) // Changed from text_primary
        
        if (source == PickerViewModel.EmojiSource.SYSTEM) {
            tvSystem.background = selectedBg
            tvSystem.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tvSystem.alpha = 1f
            
            tvKlipy.background = null
            tvKlipy.typeface = android.graphics.Typeface.DEFAULT
            tvKlipy.alpha = 0.6f
        } else {
            tvKlipy.background = selectedBg
            tvKlipy.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tvKlipy.alpha = 1f
            
            tvSystem.background = null
            tvSystem.typeface = android.graphics.Typeface.DEFAULT
            tvSystem.alpha = 0.6f
        }
    }

    // ── Category Strip (Emoji Tab) ───────────────────────────────────

    private fun setupCategoryStrip(view: View) {
        val container = view.findViewById<View>(R.id.layoutCategoryContainer)
        val divider = view.findViewById<View>(R.id.viewCategoryDivider)
        val layoutStrip = view.findViewById<android.widget.LinearLayout>(R.id.layoutCategoryStrip)
        val btnDelete = view.findViewById<View>(R.id.btnPickerDelete)
        
        // Only show for EMOJI tab
        if (tabType != PickerViewModel.PickerTab.EMOJI) {
             container?.visibility = View.GONE
             divider?.visibility = View.GONE
             return
        }

        btnDelete?.setOnClickListener {
            // Check if the host activity has a method to delete a character (backspace)
            // This is a common pattern in your custom emoji picker setup
            val chatActivity = activity as? com.glyph.glyph_v3.ui.chat.ChatActivity
            if (chatActivity != null) {
                val etInput = chatActivity.findViewById<android.widget.EditText>(R.id.etMessageInput)
                if (etInput != null) {
                    val event = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DEL)
                    etInput.dispatchKeyEvent(event)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.emojiSource.collectLatest { source ->
                    if (source == PickerViewModel.EmojiSource.SYSTEM) {
                        container?.visibility = View.VISIBLE
                        divider?.visibility = View.VISIBLE
                        populateCategoryStrip(layoutStrip)
                    } else {
                        container?.visibility = View.GONE
                        divider?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun populateCategoryStrip(layout: android.widget.LinearLayout) {
        layout.removeAllViews()

        // Add Search Icon at the beginning
        val searchView = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_search)
            setPadding(20, 4, 20, 4) // Reduced padding to make icon feel larger
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                width = 56.dpToPx() // Specific width for consistent feel
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
            background = ContextCompat.getDrawable(context, R.drawable.bg_picker_category_item)
            setOnClickListener {
                if (etSearch.visibility == View.GONE) {
                    etSearch.visibility = View.VISIBLE
                    etSearch.requestFocus()
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                } else {
                    etSearch.text = null
                    etSearch.visibility = View.GONE
                    etSearch.clearFocus()
                }
            }
        }
        layout.addView(searchView)

        val categories = listOf(
            "Recent" to "🕒",
            "Smileys & Emotion" to "😀",
            "People & Body" to "👋",
            "Animals & Nature" to "🐻",
            "Food & Drink" to "🍔",
            "Travel & Places" to "🚗",
            "Activities" to "⚽",
            "Objects" to "💡",
            "Symbols" to "#️⃣",
            "Flags" to "🚩"
        )
        
        categories.forEach { (name, icon) ->
            val textView = TextView(requireContext()).apply {
                text = icon
                textSize = 20f
                setPadding(24, 8, 24, 8)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                )
                gravity = android.view.Gravity.CENTER
                background = ContextCompat.getDrawable(context, R.drawable.bg_picker_category_item) // Helper drawable
                setOnClickListener { scrollToCategory(name) }
            }
            layout.addView(textView)
        }
    }

    private fun scrollToCategory(categoryName: String) {
        val currentState = viewModel.emojiState.value
        val items = currentState.items
        val index = items.indexOfFirst { it is PickerItem.Header && it.title == categoryName }
        if (index != -1) {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            layoutManager?.scrollToPositionWithOffset(index, 0)
        }
    }

    // ── RecyclerView setup ───────────────────────────────────────────

    private fun setupRecyclerView() {
        // Initial span count, updated dynamically for Emoji tab
        val initialSpanCount = when (tabType) {
            PickerViewModel.PickerTab.EMOJI -> 8  // emojis are small
            PickerViewModel.PickerTab.GIF -> 3     // GIFs
            PickerViewModel.PickerTab.STICKER -> 4 // stickers medium
            PickerViewModel.PickerTab.MEME -> 2    // memes need width for text
        }


        adapter = PickerGridAdapter(
            onKlipyClick = { item ->
                when (tabType) {
                    PickerViewModel.PickerTab.EMOJI -> {
                        // Klipy Emojis are images, but if treated as Emojis, they might not be 'recent' in system sense
                        // Or we can add them to recent if we want. For now, only System emojis.
                        onEmojiSelected?.invoke(item)
                    }
                    PickerViewModel.PickerTab.GIF -> onGifSelected?.invoke(item)
                    PickerViewModel.PickerTab.STICKER -> onStickerSelected?.invoke(item)
                    PickerViewModel.PickerTab.MEME -> onMemeSelected?.invoke(item)
                }
            },
            onSystemEmojiClick = { emoji ->
                viewModel.onEmojiSelected(PickerItem.SystemEmoji(emoji))
                onSystemEmojiSelected?.invoke(emoji)
            }
        )

        val layoutManager = GridLayoutManager(requireContext(), initialSpanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    PickerGridAdapter.TYPE_HEADER -> layoutManager.spanCount // Full width
                    else -> 1
                }
            }
        }
        
        // For Emoji tab: Use a fixed spanCount and adjust via SpanSizeLookup
        // System emoji = 7 columns (each takes 1 span out of 7)
        // Klipy emoji = 4 columns (each takes 1 span, but base spanCount changes to 4)
        if (tabType == PickerViewModel.PickerTab.EMOJI) {
            // Update base span count when source changes
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.emojiSource.collectLatest { source ->
                        layoutManager.spanCount = if (source == PickerViewModel.EmojiSource.SYSTEM) 8 else 4
                        // Force refresh span cache
                        layoutManager.spanSizeLookup.invalidateSpanIndexCache()
                    }
                }
            }
        }
        
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        recyclerView.setHasFixedSize(true)
        recyclerView.setItemViewCacheSize(20)
        recyclerView.isNestedScrollingEnabled = false


        // Save scroll position when scrolling
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                viewModel.saveScrollPosition(tabType, lm.findFirstVisibleItemPosition())
            }

            // Load more when near bottom
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val totalItems = lm.itemCount
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible >= totalItems - 10) {
                        viewModel.loadMore(tabType)
                    }
                }
            }
        })
    }

    // ── Search bar ───────────────────────────────────────────────────

    private fun setupSearchBar() {
        etSearch.hint = when (tabType) {
            PickerViewModel.PickerTab.EMOJI -> "Search KLIPY emojis"
            PickerViewModel.PickerTab.GIF -> "Search KLIPY GIFs"
            PickerViewModel.PickerTab.STICKER -> "Search KLIPY stickers"
            PickerViewModel.PickerTab.MEME -> "Search KLIPY memes"
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.onSearchQueryChanged(tabType, s?.toString() ?: "")
            }
        })

        // Notify the panel when search gains/loses focus so it can expand/collapse
        etSearch.setOnFocusChangeListener { _, hasFocus ->
            onSearchFocusChanged?.invoke(hasFocus)
        }
    }

    /**
     * Called by the host when the panel collapses back to compact mode.
     * Clears search focus without triggering another collapse.
     */
    fun clearSearchFocus() {
        if (etSearch.hasFocus()) {
            etSearch.clearFocus()
        }
    }

    // ── State observation ────────────────────────────────────────────

    private fun observeState() {
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = when (tabType) {
                    PickerViewModel.PickerTab.EMOJI -> viewModel.emojiState
                    PickerViewModel.PickerTab.GIF -> viewModel.gifState
                    PickerViewModel.PickerTab.STICKER -> viewModel.stickerState
                    PickerViewModel.PickerTab.MEME -> viewModel.memeState
                }


                flow.collectLatest { state ->
                    
                    // Loading spinner
                    progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    // Empty state
                    if (!state.isLoading && state.items.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                        tvEmpty.text = state.error ?: "No results"
                        Log.w("PickerTabFragment", "[$tabType] Empty state - error: ${state.error}")
                    } else {
                        tvEmpty.visibility = View.GONE
                    }

                    // Submit list
                    if (state.items.isNotEmpty()) {
                        val firstItem = state.items[0]
                        val previewInfo = when (firstItem) {
                            is PickerItem.Klipy -> "preview=${firstItem.media.previewUrl}"
                            is PickerItem.SystemEmoji -> "system=${firstItem.unicode}"
                            is PickerItem.Header -> "header=${firstItem.title}"
                        }
                    }
                    
                    adapter.submitList(state.items) {
                        // ONLY restore scroll position on the FIRST data load, not on every state update
                        // This prevents scroll jumps when the user is actively scrolling
                        if (!hasRestoredScrollPosition && state.scrollPosition > 0 && !state.isLoading && state.items.isNotEmpty()) {
                            (recyclerView.layoutManager as? GridLayoutManager)
                                ?.scrollToPositionWithOffset(state.scrollPosition, 0)
                            hasRestoredScrollPosition = true
                        }
                    }
                }
            }
        }
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
