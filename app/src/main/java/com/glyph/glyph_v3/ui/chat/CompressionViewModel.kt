package com.glyph.glyph_v3.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.glyph.glyph_v3.data.models.CompressionEstimateResult
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.data.models.CompressionUiState
import com.glyph.glyph_v3.data.models.SelectedMediaItem
import com.glyph.glyph_v3.util.MediaEstimationUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for managing media compression selection state.
 * Survives configuration changes and handles async estimation calculations.
 */
class CompressionViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(CompressionUiState())
    val uiState: StateFlow<CompressionUiState> = _uiState.asStateFlow()
    
    // Callback when user confirms selection
    var onConfirmSelection: ((CompressionQuality, List<SelectedMediaItem>) -> Unit)? = null
    
    // Callback when user cancels
    var onCancelSelection: (() -> Unit)? = null
    
    /**
     * Initialize with selected media URIs and their mime types.
     * Extracts metadata and calculates estimates for all quality levels.
     */
    fun initializeWithMedia(mediaList: List<Pair<Uri, String>>) {
        if (mediaList.isEmpty()) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isCalculating = true) }
            
            try {
                // Extract metadata for all items (off main thread)
                val selectedItems = withContext(Dispatchers.IO) {
                    mediaList.map { (uri, mimeType) ->
                        MediaEstimationUtil.extractMediaMetadata(
                            getApplication(),
                            uri,
                            mimeType
                        )
                    }
                }
                
                // Calculate estimates for all quality levels (cached for instant switching)
                val estimates = withContext(Dispatchers.Default) {
                    MediaEstimationUtil.calculateAllEstimates(getApplication(), selectedItems)
                }
                
                _uiState.update { state ->
                    state.copy(
                        selectedItems = selectedItems,
                        estimates = estimates,
                        isCalculating = false,
                        selectedQuality = CompressionQuality.MEDIUM // Default to recommended
                    )
                }
            } catch (e: Exception) {
                // On error, allow upload without compression info
                _uiState.update { it.copy(isCalculating = false) }
            }
        }
    }
    
    /**
     * Change selected compression quality.
     * Instant update since all estimates are pre-calculated.
     */
    fun selectQuality(quality: CompressionQuality) {
        _uiState.update { it.copy(selectedQuality = quality) }
    }
    
    /**
     * Toggle the per-item details list expansion.
     */
    fun togglePerItemList() {
        _uiState.update { it.copy(showPerItemList = !it.showPerItemList) }
    }
    
    /**
     * Set custom quality override for a specific item (only in CUSTOM mode).
     */
    fun setItemOverride(uri: Uri, quality: CompressionQuality) {
        _uiState.update { state ->
            val newOverrides = state.customOverrides.toMutableMap()
            newOverrides[uri] = quality
            state.copy(customOverrides = newOverrides)
        }
    }
    
    /**
     * Get the effective quality for a specific item.
     */
    fun getEffectiveQuality(uri: Uri): CompressionQuality {
        val state = _uiState.value
        return if (state.selectedQuality == CompressionQuality.CUSTOM) {
            state.customOverrides[uri] ?: CompressionQuality.MEDIUM
        } else {
            state.selectedQuality
        }
    }
    
    /**
     * Get the current estimate result for display.
     */
    fun getCurrentEstimate(): CompressionEstimateResult? {
        return _uiState.value.currentEstimate
    }
    
    /**
     * Confirm selection and trigger upload with chosen quality.
     */
    fun confirmSelection() {
        val state = _uiState.value
        onConfirmSelection?.invoke(state.selectedQuality, state.selectedItems)
    }
    
    /**
     * Cancel selection.
     */
    fun cancelSelection() {
        onCancelSelection?.invoke()
    }
    
    /**
     * Reset state for new selection.
     */
    fun reset() {
        _uiState.value = CompressionUiState()
    }
}
