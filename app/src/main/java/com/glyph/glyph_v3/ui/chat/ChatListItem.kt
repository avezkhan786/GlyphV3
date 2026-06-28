package com.glyph.glyph_v3.ui.chat

import com.glyph.glyph_v3.data.models.Message
import androidx.compose.runtime.Immutable

enum class BubbleGroupPosition {
    SINGLE, TOP, MIDDLE, BOTTOM
}

@Immutable
sealed class ChatListItem {
    @Immutable
    data class MessageItem(
        val message: Message,
        val groupPosition: BubbleGroupPosition = BubbleGroupPosition.SINGLE,
        val dateString: String = "",
        val isEmojiContent: Boolean = false,
        val shouldAnimateEntry: Boolean = false,
        // Inline translation state (ephemeral, not persisted)
        val translatedText: String? = null,
        val isShowingTranslation: Boolean = false,
        val isTranslating: Boolean = false
    ) : ChatListItem() {
        /**
         * Pre-measured text height in pixels. Stored as a mutable property (NOT a
         * constructor parameter) so it does not affect data-class equals/hashCode.
         * Otherwise DiffUtil would see cached items (height=0) as different from
         * live-flow items (height=computed) and rebind every item, causing layout shifts.
         *
         * Computed on a background thread via [TextLayoutPrecomputer].
         */
        var premeasuredTextHeightPx: Int = 0
    }

    @Immutable
    data class GroupIntroItem(
        val chatId: String,
        val groupName: String,
        val groupAvatarUrl: String,
        val description: String,
        val memberCount: Int
    ) : ChatListItem()
    
    @Immutable
    data class DateHeader(val dateString: String) : ChatListItem()
    
    @Immutable
    data class TypingIndicator(
        val isVisible: Boolean = true,
        val isExpressive: Boolean = false,
        val liveText: String = "",
        val synchronizeHideWithListMotion: Boolean = false,
        val sentiment: com.glyph.glyph_v3.ui.chat.expressive.SentimentType =
            com.glyph.glyph_v3.ui.chat.expressive.SentimentType.NEUTRAL
    ) : ChatListItem()
    
    // Helper to get stable ID for DiffUtil and Compose keys
    val id: String
        get() = when (this) {
            is MessageItem -> "msg_${message.id}"
            is GroupIntroItem -> "group_intro_$chatId"
            is DateHeader -> "header_${dateString}"
            is TypingIndicator -> "typing_indicator"
        }
}

/**
 * Stable wrappers for the message list and other collections to prevent 
 * unnecessary recompositions of the entire LazyColumn when other parts 
 * of the UI state change.
 */
@Immutable
data class ChatListItemList(val items: List<ChatListItem> = emptyList())

@Immutable
data class MediaProgressMap(val progress: Map<String, Float> = emptyMap())

@Immutable
data class SelectedMessagesSet(val ids: Set<String> = emptySet())
