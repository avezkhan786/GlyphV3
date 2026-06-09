package com.glyph.glyph_v3.ui.chat.picker

import com.glyph.glyph_v3.data.remote.KlipyMediaItem

/**
 * Represents an item in the picker grid.
 * Can be either a Klipy media item (Image/GIF) or a System Emoji (Text).
 */
sealed class PickerItem {
    
    data class Klipy(val media: KlipyMediaItem) : PickerItem() {
        override val id: String get() = media.id
    }
    
    data class SystemEmoji(
        val unicode: String,
        val keywords: List<String> = emptyList()
    ) : PickerItem() {
        override val id: String get() = unicode
    }

    data class Header(val title: String) : PickerItem() {
        override val id: String get() = "header_$title"
    }

    abstract val id: String
}
