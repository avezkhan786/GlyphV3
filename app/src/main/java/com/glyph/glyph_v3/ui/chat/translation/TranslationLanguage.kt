package com.glyph.glyph_v3.ui.chat.translation

/**
 * Supported translation languages.
 * Each entry provides the BCP-47 code and the display name shown in the language selector.
 */
enum class TranslationLanguage(val code: String, val displayName: String, val nativeName: String, val flag: String) {
    ENGLISH("en", "English", "English", "🇺🇸"),
    SPANISH("es", "Spanish", "Español", "🇪🇸"),
    FRENCH("fr", "French", "Français", "🇫🇷"),
    GERMAN("de", "German", "Deutsch", "🇩🇪"),
    ITALIAN("it", "Italian", "Italiano", "🇮🇹"),
    PORTUGUESE("pt", "Portuguese", "Português", "🇧🇷"),
    RUSSIAN("ru", "Russian", "Русский", "🇷🇺"),
    JAPANESE("ja", "Japanese", "日本語", "🇯🇵"),
    KOREAN("ko", "Korean", "한국어", "🇰🇷"),
    CHINESE("zh", "Chinese", "中文", "🇨🇳"),
    ARABIC("ar", "Arabic", "العربية", "🇸🇦"),
    HINDI("hi", "Hindi", "हिन्दी", "🇮🇳"),
    URDU("ur", "Urdu", "اردو", "🇵🇰"),
    HINGLISH("hi-Latn", "Hinglish", "Hinglish", "🇮🇳"),
    BENGALI("bn", "Bengali", "বাংলা", "🇮🇳"),
    TAMIL("ta", "Tamil", "தமிழ்", "🇮🇳"),
    TELUGU("te", "Telugu", "తెలుగు", "🇮🇳"),
    MARATHI("mr", "Marathi", "मराठी", "🇮🇳"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી", "🇮🇳"),
    KANNADA("kn", "Kannada", "ಕನ್ನಡ", "🇮🇳"),
    MALAYALAM("ml", "Malayalam", "മലയാളം", "🇮🇳"),
    PUNJABI("pa", "Punjabi", "ਪੰਜਾਬੀ", "🇮🇳"),
    TURKISH("tr", "Turkish", "Türkçe", "🇹🇷"),
    POLISH("pl", "Polish", "Polski", "🇵🇱"),
    DUTCH("nl", "Dutch", "Nederlands", "🇳🇱"),
    SWEDISH("sv", "Swedish", "Svenska", "🇸🇪"),
    UKRAINIAN("uk", "Ukrainian", "Українська", "🇺🇦"),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt", "🇻🇳"),
    THAI("th", "Thai", "ไทย", "🇹🇭"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia", "🇮🇩");

    companion object {
        fun fromCode(code: String): TranslationLanguage? {
            return entries.find { it.code.equals(code, ignoreCase = true) }
        }

        /**
         * Default target language.
         */
        val DEFAULT = ENGLISH
    }
}
