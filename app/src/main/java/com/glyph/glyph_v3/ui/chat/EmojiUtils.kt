package com.glyph.glyph_v3.ui.chat

object EmojiUtils {
    fun isEmojiOnlyMessage(text: String?): Boolean {
        val trimmed = text?.trim() ?: return false
        if (trimmed.isEmpty()) return false

        var hasEmoji = false
        var hasKeycap = false
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            if (cp == 0x20E3) hasKeycap = true
            i += Character.charCount(cp)
        }

        i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            if (Character.isWhitespace(cp)) {
                i += Character.charCount(cp)
                continue
            }
            if (isEmojiCodePoint(cp)) {
                hasEmoji = true
                i += Character.charCount(cp)
                continue
            }
            if (isEmojiModifier(cp) || isEmojiJoiner(cp) || (hasKeycap && isKeycapBase(cp))) {
                i += Character.charCount(cp)
                continue
            }
            return false
        }

        return hasEmoji
    }

    private fun isEmojiCodePoint(cp: Int): Boolean {
        return (cp in 0x1F600..0x1F64F) || // Emoticons
            (cp in 0x1F300..0x1F5FF) || // Misc Symbols and Pictographs
            (cp in 0x1F680..0x1F6FF) || // Transport and Map
            (cp in 0x1F700..0x1F77F) || // Alchemical Symbols
            (cp in 0x1F780..0x1F7FF) || // Geometric Symbols Extended
            (cp in 0x1F800..0x1F8FF) || // Supplemental Arrows-C
            (cp in 0x1F900..0x1F9FF) || // Supplemental Symbols and Pictographs
            (cp in 0x1FA70..0x1FAFF) || // Symbols and Pictographs Extended-A
            (cp in 0x2600..0x26FF) || // Misc symbols
            (cp in 0x2700..0x27BF) || // Dingbats
            (cp in 0x1F1E6..0x1F1FF) || // Regional indicator symbols (flags)
            (cp == 0x2764) || // Heart
            (cp == 0x263A) || // Smiling face
            (cp == 0x2620) // Skull and crossbones
    }

    private fun isEmojiModifier(cp: Int): Boolean {
        return (cp in 0x1F3FB..0x1F3FF) || // Skin tone modifiers
            (cp == 0xFE0F) || // Variation Selector-16 (emoji)
            (cp == 0xFE0E) // Variation Selector-15 (text)
    }

    private fun isEmojiJoiner(cp: Int): Boolean {
        return (cp == 0x200D) || // ZWJ
            (cp == 0x20E3) // Combining enclosing keycap
    }

    private fun isKeycapBase(cp: Int): Boolean {
        return (cp in 0x30..0x39) || cp == 0x23 || cp == 0x2A
    }
}
