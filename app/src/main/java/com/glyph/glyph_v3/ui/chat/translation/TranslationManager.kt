package com.glyph.glyph_v3.ui.chat.translation

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.repo.TranslationRepository
import com.glyph.glyph_v3.utils.NetworkDiagnostic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages translation state and operations for the chat screen.
 *
 * This is NOT a ViewModel (to avoid requiring ViewModelProvider in RecyclerView context).
 * It is lifecycle-scoped to the Activity and handles:
 * - Translation requests (with caching)
 * - Audio playback via ExoPlayer
 * - Language selection state
 * - UI state (loading, result, error)
 *
 * Thread safety: All state mutations happen on Main thread, IO work via repository.
 */
class TranslationManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "TranslationMgr"
    }

    private val repository = TranslationRepository.getInstance(context)
    val audioPlayer = TtsAudioPlayer(context)

    // ─── Observable state ─────────────────────────

    /**
     * Pre-warms the translation system (auth, network) to ensure reliability on first use.
     * Should be called as early as possible (e.g., onCreate).
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

    private val _translationState = MutableLiveData<TranslationUiState>(TranslationUiState.Idle)
    val translationState: LiveData<TranslationUiState> = _translationState

    private val _ttsBannerState = MutableLiveData<TtsBannerUiState>(TtsBannerUiState.Hidden)
    val ttsBannerState: LiveData<TtsBannerUiState> = _ttsBannerState

    private val _selectedLanguage = MutableLiveData(TranslationLanguage.DEFAULT)
    val selectedLanguage: LiveData<TranslationLanguage> = _selectedLanguage

    /**
     * Currently active message (the one whose toolbar is visible).
     */
    var activeMessage: Message? = null
        private set

    private var translateJob: Job? = null
    private var audioJob: Job? = null

    // ─── Public API ───────────────────────────────

    /**
     * Set the active message (when toolbar is shown).
     */
    fun setActiveMessage(message: Message?) {
        activeMessage = message
        if (message == null) {
            _translationState.value = TranslationUiState.Idle
            _ttsBannerState.value = TtsBannerUiState.Hidden
            audioPlayer.stop()
        }
    }

    /**
     * Set target language.
     */
    fun setLanguage(language: TranslationLanguage) {
        _selectedLanguage.value = language
    }

    /**
     * Reset translation state after a language change.
     * Keeps activeMessage intact so the next play-tap works.
     */
    fun resetStateForLanguageChange() {
        translateJob?.cancel()
        audioJob?.cancel()
        audioPlayer.stop()
        _translationState.value = TranslationUiState.Idle
        _ttsBannerState.value = TtsBannerUiState.Hidden
    }

    /**
     * Translate the active message to the selected language.
     */
    fun translateActiveMessage() {
        val t0 = System.currentTimeMillis()
        val message = activeMessage
        
        if (message == null) {
            Log.w(TAG, "No active message to translate")
            return
        }
        
        val language = _selectedLanguage.value ?: TranslationLanguage.DEFAULT

        if (message.text.isBlank()) {
            Log.w(TAG, "Message text is blank")
            return
        }

        // Cancel any previous translate job
        translateJob?.cancel()
        _translationState.value = TranslationUiState.Loading(
            title = "Translating message",
            detail = "Analyzing text and preparing a translated preview."
        )
        _ttsBannerState.value = TtsBannerUiState.Status(
            title = "Translating message",
            detail = "Analyzing text and preparing a translated preview.",
            activeStep = TtsWorkflowStep.TRANSLATE,
            completedSteps = emptySet(),
            lockDismissal = true
        )
        

        translateJob = scope.launch(Dispatchers.Main) {
            repository.translateFlow(
                messageId = message.id,
                text = message.text,
                targetLanguage = language.code
            )
            .catch { e ->
                 Log.e(TAG, "Translation failed after ${System.currentTimeMillis() - t0}ms", e)
                 val uiError = when (e) {
                    is TranslationRepository.TranslationError.RateLimited -> TranslationUiState.Error("Too many requests. Please wait.")
                    is TranslationRepository.TranslationError.TextTooLong -> TranslationUiState.Error("Text too long.")
                    is TranslationRepository.TranslationError.NoInternetConnection -> TranslationUiState.Error("No internet connection.")
                    is TranslationRepository.TranslationError.NetworkError -> TranslationUiState.Error("Network error. Check connection.")
                    else -> TranslationUiState.Error("Translation failed: ${e.message}")
                 }
                 _translationState.value = uiError
                 _ttsBannerState.value = TtsBannerUiState.Status(
                    title = "Translation failed",
                    detail = uiError.message,
                    activeStep = TtsWorkflowStep.TRANSLATE,
                    completedSteps = emptySet(),
                    lockDismissal = false,
                    isError = true
                 )
            }
            .collect { result ->
                _translationState.value = TranslationUiState.Success(
                    originalText = message.text,
                    translatedText = result.translatedText,
                    language = language,
                    hasAudio = result.audioUrl != null || result.audioFilePath != null,
                    audioUrl = result.audioUrl
                )
                _ttsBannerState.value = TtsBannerUiState.Hidden
            }
        }
    }

    /**
     * Translate the active message AND re-translate for a new language.
     */
    fun translateWithLanguage(language: TranslationLanguage) {
        _selectedLanguage.value = language
        translateActiveMessage()
    }

    /**
     * Translate the active message AND auto-play audio when ready.
     * This is the primary action when the play button is tapped and
     * the message hasn't been translated yet.
     */
    fun translateAndPlayAudio() {
        val message = activeMessage
        if (message == null) {
            Log.w(TAG, "No active message for translateAndPlay")
            return
        }
        val language = _selectedLanguage.value ?: TranslationLanguage.DEFAULT

        // Cancel any previous jobs
        translateJob?.cancel()
        audioJob?.cancel()
        audioPlayer.stop()

        _translationState.value = TranslationUiState.Loading(
            title = "Preparing voice playback",
            detail = "Translating the message before generating audio."
        )
        _ttsBannerState.value = TtsBannerUiState.Status(
            title = "Preparing voice playback",
            detail = "Translating the message before generating audio.",
            activeStep = TtsWorkflowStep.TRANSLATE,
            completedSteps = emptySet(),
            lockDismissal = true
        )

        translateJob = scope.launch {
            try {
                val result = repository.translate(
                    messageId = message.id,
                    text = message.text,
                    targetLanguage = language.code
                )

                val successState = TranslationUiState.Success(
                    originalText = message.text,
                    translatedText = result.translatedText,
                    language = language,
                    hasAudio = result.audioUrl != null || result.audioFilePath != null,
                    audioUrl = result.audioUrl,
                    isLoadingAudio = true
                )
                _translationState.postValue(successState)
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Generating voice",
                        detail = "Building the AI voice track for playback.",
                        activeStep = TtsWorkflowStep.AUDIO,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
                        lockDismissal = true
                    )
                )

                // Now automatically play
                playAudioInternal(message, language)
            } catch (e: TranslationRepository.TranslationError.RateLimited) {
                _translationState.postValue(TranslationUiState.Error("Rate limited. Try again in a minute."))
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Voice generation blocked",
                        detail = "Rate limited. Try again in a minute.",
                        activeStep = TtsWorkflowStep.AUDIO,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
                        lockDismissal = false,
                        isError = true
                    )
                )
            } catch (e: TranslationRepository.TranslationError.NoInternetConnection) {
                _translationState.postValue(TranslationUiState.Error("No internet connection."))
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "No internet connection",
                        detail = "Reconnect to generate the AI voice track.",
                        activeStep = TtsWorkflowStep.AUDIO,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
                        lockDismissal = false,
                        isError = true
                    )
                )
            } catch (e: TranslationRepository.TranslationError) {
                _translationState.postValue(TranslationUiState.Error(e.message ?: "Translation failed."))
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Voice generation failed",
                        detail = e.message ?: "Translation failed.",
                        activeStep = TtsWorkflowStep.AUDIO,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
                        lockDismissal = false,
                        isError = true
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "translateAndPlay failed", e)
                _translationState.postValue(TranslationUiState.Error("Failed: ${e.message?.take(80)}"))
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Voice playback failed",
                        detail = e.message?.take(80) ?: "Unknown error",
                        activeStep = TtsWorkflowStep.AUDIO,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
                        lockDismissal = false,
                        isError = true
                    )
                )
            }
        }
    }

    /**
     * Play TTS audio for the active message.
     * Only call when state is already Success (translation exists).
     */
    fun playAudio() {
        val message = activeMessage
        if (message == null) {
            Log.e(TAG, "playAudio() called but activeMessage is NULL!")
            return
        }
        val language = _selectedLanguage.value ?: TranslationLanguage.DEFAULT

        audioJob?.cancel()
        audioPlayer.stop()

        // Show loading state on button
        _translationState.value?.let { state ->
            if (state is TranslationUiState.Success) {
                _translationState.value = state.copy(isLoadingAudio = true, isPlayingAudio = false)
            }
        }
        _ttsBannerState.value = TtsBannerUiState.Status(
            title = "Loading voice",
            detail = "Fetching the generated AI audio.",
            activeStep = TtsWorkflowStep.AUDIO,
            completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
            lockDismissal = true
        )

        audioJob = scope.launch {
            playAudioInternal(message, language)
        }
    }

    /**
     * Internal audio play logic — downloads audio if needed, then plays it.
     * Updates state to isPlayingAudio=true only when playback actually starts.
     */
    private suspend fun playAudioInternal(message: Message, language: TranslationLanguage) {
        try {

            // Setup callbacks BEFORE playing
            audioPlayer.onPlaybackPrepared = { durationMs ->
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Audio ready",
                        detail = if (durationMs > 0) {
                            "Voice loaded. Starting playback."
                        } else {
                            "Voice loaded. Starting playback."
                        },
                        activeStep = TtsWorkflowStep.PLAYBACK,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE, TtsWorkflowStep.AUDIO),
                        lockDismissal = true,
                        playbackProgress = 0f,
                        playbackPositionMs = 0,
                        playbackDurationMs = durationMs
                    )
                )
            }
            audioPlayer.onPlaybackStarted = {
                _translationState.value?.let { state ->
                    if (state is TranslationUiState.Success) {
                        _translationState.postValue(
                            state.copy(
                                isPlayingAudio = true,
                                isLoadingAudio = false,
                                playbackProgress = 0f,
                                playbackPositionMs = 0,
                                playbackDurationMs = state.playbackDurationMs
                            )
                        )
                    }
                }
            }
            audioPlayer.onPlaybackProgress = { positionMs, durationMs ->
                val progress = if (durationMs > 0) {
                    positionMs.toFloat() / durationMs.toFloat()
                } else {
                    0f
                }.coerceIn(0f, 1f)
                _translationState.value?.let { state ->
                    if (state is TranslationUiState.Success) {
                        _translationState.postValue(
                            state.copy(
                                isPlayingAudio = true,
                                isLoadingAudio = false,
                                playbackProgress = progress,
                                playbackPositionMs = positionMs,
                                playbackDurationMs = durationMs
                            )
                        )
                    }
                }
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Playing AI voice",
                        detail = "Streaming translated speech through the device player.",
                        activeStep = TtsWorkflowStep.PLAYBACK,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE, TtsWorkflowStep.AUDIO),
                        lockDismissal = true,
                        playbackProgress = progress,
                        playbackPositionMs = positionMs,
                        playbackDurationMs = durationMs
                    )
                )
            }
            audioPlayer.onPlaybackComplete = {
                _translationState.value?.let { state ->
                    if (state is TranslationUiState.Success) {
                        _translationState.postValue(
                            state.copy(
                                isPlayingAudio = false,
                                isLoadingAudio = false,
                                playbackProgress = 1f,
                                playbackPositionMs = state.playbackDurationMs,
                                playbackDurationMs = state.playbackDurationMs
                            )
                        )
                    }
                }
                val durationMs = (_translationState.value as? TranslationUiState.Success)?.playbackDurationMs ?: 0
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Playback finished",
                        detail = "Tap play to listen again or tap anywhere to dismiss.",
                        activeStep = TtsWorkflowStep.PLAYBACK,
                        completedSteps = setOf(
                            TtsWorkflowStep.TRANSLATE,
                            TtsWorkflowStep.AUDIO,
                            TtsWorkflowStep.PLAYBACK
                        ),
                        lockDismissal = false,
                        playbackProgress = 1f,
                        playbackPositionMs = durationMs,
                        playbackDurationMs = durationMs
                    )
                )
            }
            audioPlayer.onPlaybackError = { error ->
                Log.e(TAG, "Audio playback error", error)
                _translationState.value?.let { state ->
                    if (state is TranslationUiState.Success) {
                        _translationState.postValue(
                            state.copy(
                                isPlayingAudio = false,
                                isLoadingAudio = false
                            )
                        )
                    }
                }
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Playback error",
                        detail = error.message?.take(80) ?: "The audio player could not start.",
                        activeStep = TtsWorkflowStep.PLAYBACK,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE, TtsWorkflowStep.AUDIO),
                        lockDismissal = false,
                        isError = true
                    )
                )
            }

            // Download audio file
            _ttsBannerState.postValue(
                TtsBannerUiState.Status(
                    title = "Loading voice",
                    detail = "Downloading the generated AI audio for playback.",
                    activeStep = TtsWorkflowStep.AUDIO,
                    completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
                    lockDismissal = true
                )
            )
            val audioPath = repository.getOrDownloadAudio(message.id, language.code)

            if (audioPath != null) {
                _ttsBannerState.postValue(
                    TtsBannerUiState.Status(
                        title = "Buffering audio",
                        detail = "Finalizing the voice stream in the player.",
                        activeStep = TtsWorkflowStep.PLAYBACK,
                        completedSteps = setOf(TtsWorkflowStep.TRANSLATE, TtsWorkflowStep.AUDIO),
                        lockDismissal = true
                    )
                )
                withContext(Dispatchers.Main) {
                    audioPlayer.play(audioPath, "${message.id}_${language.code}")
                }
            } else {
                // Fallback: stream from URL
                val cached = repository.getCached(message.id, language.code)
                if (cached?.audioUrl != null) {
                    _ttsBannerState.postValue(
                        TtsBannerUiState.Status(
                            title = "Buffering audio",
                            detail = "Streaming the AI voice directly from the network.",
                            activeStep = TtsWorkflowStep.PLAYBACK,
                            completedSteps = setOf(TtsWorkflowStep.TRANSLATE, TtsWorkflowStep.AUDIO),
                            lockDismissal = true
                        )
                    )
                    withContext(Dispatchers.Main) {
                        audioPlayer.play(cached.audioUrl, "${message.id}_${language.code}")
                    }
                } else {
                    Log.w(TAG, "No audio available")
                    _translationState.value?.let { state ->
                        if (state is TranslationUiState.Success) {
                            _translationState.postValue(state.copy(isLoadingAudio = false))
                        }
                    }
                    _ttsBannerState.postValue(
                        TtsBannerUiState.Status(
                            title = "Voice unavailable",
                            detail = "Translation finished, but no playable audio was returned.",
                            activeStep = TtsWorkflowStep.AUDIO,
                            completedSteps = setOf(TtsWorkflowStep.TRANSLATE),
                            lockDismissal = false,
                            isError = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio playback failed", e)
            _translationState.value?.let { state ->
                if (state is TranslationUiState.Success) {
                    _translationState.postValue(state.copy(isPlayingAudio = false, isLoadingAudio = false))
                }
            }
            _ttsBannerState.postValue(
                TtsBannerUiState.Status(
                    title = "Voice playback failed",
                    detail = e.message?.take(80) ?: "Could not prepare the audio player.",
                    activeStep = TtsWorkflowStep.PLAYBACK,
                    completedSteps = setOf(TtsWorkflowStep.TRANSLATE, TtsWorkflowStep.AUDIO),
                    lockDismissal = false,
                    isError = true
                )
            )
        }
    }

    /**
     * Stop audio playback.
     */
    fun stopAudio() {
        audioJob?.cancel()
        audioPlayer.stop()
        _translationState.value?.let { state ->
            if (state is TranslationUiState.Success) {
                _translationState.value = state.copy(
                    isPlayingAudio = false,
                    isLoadingAudio = false,
                    playbackProgress = 0f,
                    playbackPositionMs = 0,
                    playbackDurationMs = state.playbackDurationMs
                )
            }
        }
        _ttsBannerState.value = TtsBannerUiState.Hidden
    }

    fun shouldKeepToolbarVisible(): Boolean {
        return (ttsBannerState.value as? TtsBannerUiState.Status)?.lockDismissal == true
    }

    /**
     * Dismiss translation state (when toolbar is dismissed).
     */
    fun dismiss() {
        translateJob?.cancel()
        audioJob?.cancel()
        audioPlayer.stop()
        activeMessage = null
        _translationState.value = TranslationUiState.Idle
        _ttsBannerState.value = TtsBannerUiState.Hidden
    }

    /**
     * Release resources (call in onDestroy).
     */
    fun release() {
        dismiss()
        audioPlayer.release()
        scope.launch(Dispatchers.IO) {
            repository.cleanupExpired()
        }
    }
}

/**
 * UI state for translation feature.
 */
sealed class TranslationUiState {
    data object Idle : TranslationUiState()
    data class Loading(
        val title: String = "Working on translation",
        val detail: String = "Preparing the translated output."
    ) : TranslationUiState()
    data class Success(
        val originalText: String,
        val translatedText: String,
        val language: TranslationLanguage,
        val hasAudio: Boolean,
        val audioUrl: String? = null,
        val isPlayingAudio: Boolean = false,
        val isLoadingAudio: Boolean = false,
        val playbackProgress: Float = 0f,
        val playbackPositionMs: Int = 0,
        val playbackDurationMs: Int = 0
    ) : TranslationUiState()
    data class Error(val message: String) : TranslationUiState()
}

enum class TtsWorkflowStep {
    TRANSLATE,
    AUDIO,
    PLAYBACK
}

sealed class TtsBannerUiState {
    data object Hidden : TtsBannerUiState()
    data class Status(
        val title: String,
        val detail: String,
        val activeStep: TtsWorkflowStep,
        val completedSteps: Set<TtsWorkflowStep>,
        val lockDismissal: Boolean,
        val playbackProgress: Float? = null,
        val playbackPositionMs: Int = 0,
        val playbackDurationMs: Int = 0,
        val isError: Boolean = false
    ) : TtsBannerUiState()
}
