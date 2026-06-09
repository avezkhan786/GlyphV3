package com.glyph.glyph_v3.ui.chat.composer

import android.content.Context
import android.util.Log
import com.glyph.glyph_v3.data.repo.MessageEnhancerRepository
import com.glyph.glyph_v3.ui.chat.translation.TranslationLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the AI message composer feature lifecycle and state.
 *
 * Architecture:
 * - Activity-scoped (matches TranslationManager pattern)
 * - Uses StateFlow for Compose observation
 * - All AI processing happens off-main-thread via repository
 * - Supports cancellation of in-flight requests
 *
 * Thread safety: State mutations on Main; IO via repository.
 */
class AiComposerManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AiComposerMgr"
    }

    private val repository = MessageEnhancerRepository.getInstance(context)

    // ─── Observable state ─────────────────────────────────
    private val _uiState = MutableStateFlow<AiComposerUiState>(AiComposerUiState.Hidden)
    val uiState: StateFlow<AiComposerUiState> = _uiState.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(TranslationLanguage.DEFAULT)
    val selectedLanguage: StateFlow<TranslationLanguage> = _selectedLanguage.asStateFlow()

    private val _selectedTone = MutableStateFlow(ToneOption.FRIENDLY)
    val selectedTone: StateFlow<ToneOption> = _selectedTone.asStateFlow()

    private var enhanceJob: Job? = null

    // ─── Lifecycle ────────────────────────────────────────

    /**
     * Pre-warm the Cloud Function for faster first invocation.
     * Call early (e.g., in onCreate).
     */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            try {
                repository.warmUp()
            } catch (e: Exception) {
                Log.e(TAG, "Warmup failed (non-fatal)", e)
            }
        }
    }

    /**
     * Clean up resources. Call in onDestroy.
     */
    fun release() {
        enhanceJob?.cancel()
        _uiState.value = AiComposerUiState.Hidden
        repository.clearCache()
    }

    // ─── Public API: Sheet navigation ─────────────────────

    /**
     * Open the AI composer sheet with the action picker.
     * @param currentText The current message text (must not be blank)
     */
    fun openSheet(currentText: String) {
        if (currentText.isBlank()) {
            return
        }
        _uiState.value = AiComposerUiState.ActionPicker
    }

    /**
     * Dismiss the AI composer sheet and cancel any in-flight request.
     */
    fun dismiss() {
        enhanceJob?.cancel()
        _uiState.value = AiComposerUiState.Hidden
    }

    /**
     * Navigate back within the sheet (e.g., from language selector to action picker).
     */
    fun navigateBack() {
        enhanceJob?.cancel()
        when (_uiState.value) {
            is AiComposerUiState.LanguageSelector,
            is AiComposerUiState.ToneSelector,
            is AiComposerUiState.Loading,
            is AiComposerUiState.Error -> {
                _uiState.value = AiComposerUiState.ActionPicker
            }
            is AiComposerUiState.Preview -> {
                _uiState.value = AiComposerUiState.ActionPicker
            }
            else -> dismiss()
        }
    }

    // ─── Public API: Actions ──────────────────────────────

    /**
     * User selected an action from the picker.
     */
    fun selectAction(action: AiAction, currentText: String) {
        when (action) {
            AiAction.ENHANCE -> executeEnhancement(currentText, AiAction.ENHANCE)
            AiAction.GRAMMAR -> executeEnhancement(currentText, AiAction.GRAMMAR)
            AiAction.TRANSLATE -> _uiState.value = AiComposerUiState.LanguageSelector(_selectedLanguage.value)
            AiAction.TONE -> _uiState.value = AiComposerUiState.ToneSelector
        }
    }

    /**
     * User selected a language for translation.
     */
    fun selectLanguageAndTranslate(language: TranslationLanguage, currentText: String) {
        _selectedLanguage.value = language
        executeEnhancement(currentText, AiAction.TRANSLATE, mapOf("targetLanguage" to language.code))
    }

    /**
     * User selected a tone for tone adjustment.
     */
    fun selectToneAndAdjust(tone: ToneOption, currentText: String) {
        _selectedTone.value = tone
        executeEnhancement(currentText, AiAction.TONE, mapOf("tone" to tone.apiValue))
    }

    /**
     * Retry the last failed action.
     */
    fun retry(currentText: String) {
        val errorState = _uiState.value as? AiComposerUiState.Error ?: return
        val action = errorState.action ?: return
        selectAction(action, currentText)
    }

    // ─── Internal ─────────────────────────────────────────

    private fun executeEnhancement(
        text: String,
        action: AiAction,
        options: Map<String, String>? = null
    ) {
        enhanceJob?.cancel()
        _uiState.value = AiComposerUiState.Loading(action = action, originalText = text)

        enhanceJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.enhance(
                        text = text,
                        action = action.name.lowercase(),
                        options = options
                    )
                }

                _uiState.value = AiComposerUiState.Preview(
                    action = action,
                    originalText = text,
                    enhancedText = result.enhancedText,
                    language = if (action == AiAction.TRANSLATE) _selectedLanguage.value else null,
                    tone = if (action == AiAction.TONE) _selectedTone.value else null
                )
            } catch (e: MessageEnhancerRepository.EnhancementError.RateLimited) {
                _uiState.value = AiComposerUiState.Error(
                    message = e.message ?: "Rate limited",
                    action = action,
                    originalText = text
                )
            } catch (e: MessageEnhancerRepository.EnhancementError.TextTooLong) {
                _uiState.value = AiComposerUiState.Error(
                    message = e.message ?: "Text too long",
                    action = action,
                    originalText = text
                )
            } catch (e: MessageEnhancerRepository.EnhancementError.NoInternetConnection) {
                _uiState.value = AiComposerUiState.Error(
                    message = e.message ?: "No internet",
                    action = action,
                    originalText = text
                )
            } catch (e: MessageEnhancerRepository.EnhancementError) {
                _uiState.value = AiComposerUiState.Error(
                    message = e.message ?: "Enhancement failed",
                    action = action,
                    originalText = text
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during enhancement", e)
                _uiState.value = AiComposerUiState.Error(
                    message = "Something went wrong. Please try again.",
                    action = action,
                    originalText = text
                )
            }
        }
    }
}
