package com.glyph.glyph_v3.ui.chat.composer

import androidx.compose.runtime.Immutable
import com.glyph.glyph_v3.ui.chat.translation.TranslationLanguage

/**
 * AI Composer actions available to the user.
 */
enum class AiAction(val displayName: String, val description: String, val icon: String) {
    ENHANCE("Enhance", "Improve clarity & impact", "✨"),
    GRAMMAR("Grammar", "Fix grammar & spelling", "📝"),
    TRANSLATE("Translate", "Translate to another language", "🌐"),
    TONE("Adjust Tone", "Change the message tone", "🎭")
}

/**
 * Tone options for the tone adjustment action.
 */
enum class ToneOption(val displayName: String, val apiValue: String, val emoji: String) {
    FORMAL("Formal", "formal", "👔"),
    FRIENDLY("Friendly", "friendly", "😊"),
    CASUAL("Casual", "casual", "✌️"),
    PROFESSIONAL("Professional", "professional", "💼")
}

/**
 * UI state for the AI Composer feature.
 */
sealed class AiComposerUiState {

    /** Sheet is hidden, no active operation. */
    data object Hidden : AiComposerUiState()

    /** Sheet is visible, showing action picker. No operation running. */
    data object ActionPicker : AiComposerUiState()

    /** Sheet shows language selector for translate action. */
    data class LanguageSelector(
        val currentLanguage: TranslationLanguage = TranslationLanguage.DEFAULT
    ) : AiComposerUiState()

    /** Sheet shows tone selector for tone action. */
    data object ToneSelector : AiComposerUiState()

    /** AI is processing the request. */
    @Immutable
    data class Loading(
        val action: AiAction,
        val originalText: String
    ) : AiComposerUiState()

    /** AI has returned a result. User can preview, replace, edit, or cancel. */
    @Immutable
    data class Preview(
        val action: AiAction,
        val originalText: String,
        val enhancedText: String,
        val language: TranslationLanguage? = null,
        val tone: ToneOption? = null
    ) : AiComposerUiState()

    /** An error occurred during processing. */
    @Immutable
    data class Error(
        val message: String,
        val action: AiAction? = null,
        val originalText: String = ""
    ) : AiComposerUiState()
}
