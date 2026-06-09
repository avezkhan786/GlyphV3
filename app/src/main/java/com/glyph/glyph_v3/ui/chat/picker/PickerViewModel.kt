package com.glyph.glyph_v3.ui.chat.picker

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.local.SystemEmojiData
import com.glyph.glyph_v3.data.remote.KlipyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * ViewModel for the Emoji / GIF / Sticker picker panel.
 * Manages data loading, search, caching, and pagination for all three tabs.
 * Supports dual emoji sources: System (Unicode) and Klipy (AI).
 */
class PickerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = KlipyRepository()
    private val prefs: SharedPreferences = application.getSharedPreferences("emoji_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── Tab enum ──────────────────────────────────────────────────────

    enum class PickerTab { EMOJI, GIF, STICKER, MEME }
    enum class EmojiSource { SYSTEM, KLIPY }

    // ── UI State ──────────────────────────────────────────────────────

    data class TabState(
        val items: List<PickerItem> = emptyList(),
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
        val searchQuery: String = "",
        val isSearchActive: Boolean = false,
        /** scroll position (first visible item index) — restored when switching tabs */
        val scrollPosition: Int = 0
    )

    private val _emojiSource = MutableStateFlow(EmojiSource.SYSTEM)
    val emojiSource = _emojiSource.asStateFlow()

    private val _emojiState = MutableStateFlow(TabState())
    val emojiState: StateFlow<TabState> = _emojiState.asStateFlow()

    private val _gifState = MutableStateFlow(TabState())
    val gifState: StateFlow<TabState> = _gifState.asStateFlow()

    private val _stickerState = MutableStateFlow(TabState())
    val stickerState: StateFlow<TabState> = _stickerState.asStateFlow()

    private val _memeState = MutableStateFlow(TabState())
    val memeState: StateFlow<TabState> = _memeState.asStateFlow()

    private val _selectedTab = MutableStateFlow(PickerTab.EMOJI)

    val selectedTab: StateFlow<PickerTab> = _selectedTab.asStateFlow()

    private var searchJob: Job? = null

    // ── Public actions ───────────────────────────────────────────────

    fun setEmojiSource(source: EmojiSource) {
        if (_emojiSource.value != source) {
            _emojiSource.value = source
            // Refresh emoji tab with new source
            loadTrendingEmojis(force = false)
        }
    }

    fun selectTab(tab: PickerTab) {
        _selectedTab.value = tab
        ensureDataLoaded(tab)
    }

    /** Save scroll position for a tab (call from RecyclerView scroll listener). */
    fun saveScrollPosition(tab: PickerTab, position: Int) {
        when (tab) {
            PickerTab.EMOJI -> _emojiState.value = _emojiState.value.copy(scrollPosition = position)
            PickerTab.GIF -> _gifState.value = _gifState.value.copy(scrollPosition = position)
            PickerTab.STICKER -> _stickerState.value = _stickerState.value.copy(scrollPosition = position)
            PickerTab.MEME -> _memeState.value = _memeState.value.copy(scrollPosition = position)
        }
    }

    /** Ensure initial data is loaded for the given tab. */
    fun ensureDataLoaded(tab: PickerTab) {
        when (tab) {
            PickerTab.EMOJI -> {
                // If system source, allow loading even if items are empty
                // If items are empty, load using current source logic
                if (_emojiState.value.items.isEmpty() && !_emojiState.value.isLoading) loadTrendingEmojis()
            }
            PickerTab.GIF -> {
                if (_gifState.value.items.isEmpty() && !_gifState.value.isLoading) loadTrendingGifs()
            }
            PickerTab.STICKER -> {
                if (_stickerState.value.items.isEmpty() && !_stickerState.value.isLoading) loadTrendingStickers()
            }
            PickerTab.MEME -> {
                if (_memeState.value.items.isEmpty() && !_memeState.value.isLoading) loadTrendingMemes()
            }
        }
    }

    /** Submit search query with debounce. */
    fun onSearchQueryChanged(tab: PickerTab, query: String) {
        val stateRef = stateFlowForTab(tab)
        updateState(tab) { it.copy(searchQuery = query, isSearchActive = query.isNotBlank()) }

        searchJob?.cancel()
        if (query.isBlank()) {
            // Reset to trending
            when (tab) {
                PickerTab.EMOJI -> loadTrendingEmojis(force = false)
                PickerTab.GIF -> loadTrendingGifs(force = false)
                PickerTab.STICKER -> loadTrendingStickers(force = false)
                PickerTab.MEME -> loadTrendingMemes(force = false)
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(400) // debounce
            performSearch(tab, query)
        }
    }

    fun loadMore(tab: PickerTab) {
        val state = stateFlowForTab(tab).value
        if (state.isLoadingMore || state.isLoading) return
        if (state.isSearchActive) return // no pagination for search yet
        
        // No pagination for System Emojis
        if (tab == PickerTab.EMOJI && _emojiSource.value == EmojiSource.SYSTEM) return

        viewModelScope.launch {
            updateState(tab) { it.copy(isLoadingMore = true) }
            val result = when (tab) {
                PickerTab.EMOJI -> repository.loadMoreTrendingEmojis()
                PickerTab.GIF -> repository.loadMoreTrendingGifs()
                PickerTab.STICKER -> repository.loadMoreTrendingStickers()
                PickerTab.MEME -> repository.loadMoreTrendingMemes()
            }
            result.onSuccess { items ->
                val pickerItems = items.map { PickerItem.Klipy(it) }
                updateState(tab) { it.copy(items = pickerItems, isLoadingMore = false) }
            }.onFailure {
                updateState(tab) { it.copy(isLoadingMore = false) }
            }
        }
    }

    fun onEmojiSelected(item: PickerItem.SystemEmoji) {
        viewModelScope.launch(Dispatchers.IO) {
            val recents = getRecentEmojis().map { it.unicode }.toMutableList()
            recents.remove(item.unicode)
            recents.add(0, item.unicode)
            if (recents.size > 30) {
                recents.removeAt(recents.lastIndex)
            }
            prefs.edit().putString("recent_emojis", gson.toJson(recents)).apply()
        }
    }

    private fun getRecentEmojis(): List<PickerItem.SystemEmoji> {
        val json = prefs.getString("recent_emojis", "[]")
        val type = object : TypeToken<List<String>>() {}.type
        val unicodes: List<String> = try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
        
        return unicodes.mapNotNull { unicode ->
            val data = SystemEmojiData.allEmojis.find { it.unicode == unicode }
            if (data != null) PickerItem.SystemEmoji(data.unicode, data.keywords) else null
        }
    }

    // ── Private helpers ─────────────────────────────────────────────

    private fun loadTrendingEmojis(force: Boolean = false) {
        viewModelScope.launch {
            _emojiState.value = _emojiState.value.copy(isLoading = true, error = null)

            if (_emojiSource.value == EmojiSource.SYSTEM) {
                val allItems = mutableListOf<PickerItem>()
                
                // 1. Recent
                val recents = withContext(Dispatchers.IO) { getRecentEmojis() }
                if (recents.isNotEmpty()) {
                    allItems.add(PickerItem.Header("Recent"))
                    allItems.addAll(recents)
                }

                // 2. Categories
                SystemEmojiData.categorizedEmojis.forEach { (category, emojis) ->
                    allItems.add(PickerItem.Header(category))
                    allItems.addAll(emojis.map { PickerItem.SystemEmoji(it.unicode, it.keywords) })
                }
                
                _emojiState.value = _emojiState.value.copy(items = allItems, isLoading = false)
            } else {
                // Load Klipy Emojis
                repository.getTrendingEmojis(forceRefresh = force)
                    .onSuccess { items ->
                        val pickerItems = items.map { PickerItem.Klipy(it) }
                        _emojiState.value = _emojiState.value.copy(items = pickerItems, isLoading = false)
                    }
                    .onFailure { e ->
                        Log.e("PickerViewModel", "loadTrendingEmojis FAILED: ${e.message}", e)
                        _emojiState.value = _emojiState.value.copy(
                            isLoading = false,
                            error = e.message ?: "Failed to load emojis"
                        )
                    }
            }
        }
    }

    private fun loadTrendingGifs(force: Boolean = false) {
        viewModelScope.launch {
            _gifState.value = _gifState.value.copy(isLoading = true, error = null)
            repository.getTrendingGifs(forceRefresh = force)
                .onSuccess { items ->
                    val pickerItems = items.map { PickerItem.Klipy(it) }
                    _gifState.value = _gifState.value.copy(items = pickerItems, isLoading = false)
                }
                .onFailure { e ->
                    _gifState.value = _gifState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load GIFs"
                    )
                }
        }
    }

    private fun loadTrendingStickers(force: Boolean = false) {
        viewModelScope.launch {
            _stickerState.value = _stickerState.value.copy(isLoading = true, error = null)
            repository.getTrendingStickers(forceRefresh = force)
                .onSuccess { items ->
                    val pickerItems = items.map { PickerItem.Klipy(it) }
                    _stickerState.value = _stickerState.value.copy(items = pickerItems, isLoading = false)
                }
                .onFailure { e ->
                    _stickerState.value = _stickerState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load stickers"
                    )
                }
        }
    }

    private fun loadTrendingMemes(force: Boolean = false) {
        viewModelScope.launch {
            _memeState.value = _memeState.value.copy(isLoading = true, error = null)
            repository.getTrendingMemes(forceRefresh = force)
                .onSuccess { items ->
                    val pickerItems = items.map { PickerItem.Klipy(it) }
                    _memeState.value = _memeState.value.copy(items = pickerItems, isLoading = false)
                }
                .onFailure { e ->
                    _memeState.value = _memeState.value.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load memes"
                    )
                }
        }
    }

    private suspend fun performSearch(tab: PickerTab, query: String) {
        if (tab == PickerTab.EMOJI && _emojiSource.value == EmojiSource.SYSTEM) {
            // Client-side keyword search for System Emoji
            val queryLower = query.lowercase().trim()
            val filteredEmojis = SystemEmojiData.allEmojis.filter { emojiData ->
                emojiData.keywords.any { keyword -> keyword.contains(queryLower) }
            }.map { PickerItem.SystemEmoji(it.unicode, it.keywords) }
            
            updateState(tab) { 
                it.copy(
                    items = filteredEmojis, 
                    isLoading = false,
                    error = if (filteredEmojis.isEmpty()) "No emojis found" else null
                ) 
            }
            return 
        }

        updateState(tab) { it.copy(isLoading = true, error = null) }
        val result = when (tab) {
            PickerTab.EMOJI -> repository.searchEmojis(query)
            PickerTab.GIF -> repository.searchGifs(query)
            PickerTab.STICKER -> repository.searchStickers(query)
            PickerTab.MEME -> repository.searchMemes(query)
        }
        result.onSuccess { items ->
            val pickerItems = items.map { PickerItem.Klipy(it) }
            updateState(tab) { it.copy(items = pickerItems, isLoading = false) }
        }.onFailure { e ->
            updateState(tab) { it.copy(isLoading = false, error = e.message) }
        }
    }

    private fun stateFlowForTab(tab: PickerTab): StateFlow<TabState> = when (tab) {
        PickerTab.EMOJI -> _emojiState
        PickerTab.GIF -> _gifState
        PickerTab.STICKER -> _stickerState
        PickerTab.MEME -> _memeState
    }

    private fun updateState(tab: PickerTab, transform: (TabState) -> TabState) {
        when (tab) {
            PickerTab.EMOJI -> _emojiState.value = transform(_emojiState.value)
            PickerTab.GIF -> _gifState.value = transform(_gifState.value)
            PickerTab.STICKER -> _stickerState.value = transform(_stickerState.value)
            PickerTab.MEME -> _memeState.value = transform(_memeState.value)
        }
    }
}
