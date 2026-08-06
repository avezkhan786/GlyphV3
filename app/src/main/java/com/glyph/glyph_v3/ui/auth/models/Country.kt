package com.glyph.glyph_v3.ui.auth.models

/**
 * Represents a country for the phone number country picker.
 *
 * @param iso2 Two-letter ISO 3166-1 alpha-2 country code (e.g. "IN", "US").
 * @param name Display name of the country.
 * @param callingCode Numeric calling code WITHOUT the "+" prefix (e.g. "91", "1").
 * @param flagEmoji Emoji flag for the country, derived from the ISO2 code.
 */
data class Country(
    val iso2: String,
    val name: String,
    val callingCode: String,
    val flagEmoji: String
) {
    companion object {
        /**
         * Derives a flag emoji from a 2-letter ISO country code using
         * Unicode Regional Indicator Symbol codepoints.
         *
         * Each letter A-Z maps to U+1F1E6 (A) through U+1F1FF (Z).
         */
        fun flagEmojiFromIso2(iso2: String): String {
            if (iso2.length != 2) return ""
            val base = 0x1F1E6
            val firstChar = iso2[0].uppercaseChar()
            val secondChar = iso2[1].uppercaseChar()
            val first = base + (firstChar - 'A')
            val second = base + (secondChar - 'A')
            return String(Character.toChars(first)) + String(Character.toChars(second))
        }
    }
}
