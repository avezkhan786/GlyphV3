package com.glyph.glyph_v3.ui.chat.translation

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth

/**
 * Persists auto-translate settings (enable/disable + target language).
 * Thread-safe via SharedPreferences' internal synchronization.
 */
class TranslationPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAutoTranslateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRANSLATE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TRANSLATE, value).apply()

    var targetLanguageCode: String
        get() = getOrInitializeTargetLanguageCode()
        set(value) = prefs.edit().putString(KEY_TARGET_LANGUAGE, value).apply()

    var recentLanguageCodes: List<String>
        get() = prefs.getString(KEY_RECENT_LANGUAGES, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = prefs.edit().putString(KEY_RECENT_LANGUAGES, value.joinToString(",")).apply()

    fun addRecentLanguage(code: String) {
        val current = recentLanguageCodes.toMutableList()
        current.remove(code) // Remove if exists (to move to top)
        current.add(0, code) // Add to front
        if (current.size > 4) {
             // Keep max 4
             current.subList(4, current.size).clear()
        }
        recentLanguageCodes = current
    }

    val targetLanguage: TranslationLanguage
        get() = TranslationLanguage.fromCode(targetLanguageCode) ?: TranslationLanguage.DEFAULT

    private fun getOrInitializeTargetLanguageCode(): String {
        val saved = prefs.getString(KEY_TARGET_LANGUAGE, null)
        if (!saved.isNullOrBlank()) {
            return saved
        }

        val derivedFromPhone = resolveLanguageCodeFromRegisteredPhone()
        if (derivedFromPhone != null) {
            prefs.edit().putString(KEY_TARGET_LANGUAGE, derivedFromPhone).apply()
            return derivedFromPhone
        }

        return TranslationLanguage.DEFAULT.code
    }

    private fun resolveLanguageCodeFromRegisteredPhone(): String? {
        val phoneNumber = FirebaseAuth.getInstance().currentUser?.phoneNumber.orEmpty()
        if (phoneNumber.isBlank()) return null

        val normalized = normalizeInternationalNumber(phoneNumber)
        if (normalized.isBlank()) return null

        val languageCode = ISD_TO_LANGUAGE_CODE
            .entries
            .sortedByDescending { it.key.length }
            .firstOrNull { normalized.startsWith(it.key) }
            ?.value

        return languageCode?.takeIf { TranslationLanguage.fromCode(it) != null }
    }

    private fun normalizeInternationalNumber(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^0-9+]"), "")
        val withoutPlus = if (cleaned.startsWith("+")) cleaned.substring(1) else cleaned
        return if (withoutPlus.startsWith("00")) withoutPlus.substring(2) else withoutPlus
    }

    companion object {
        private const val PREFS_NAME = "glyph_translation_prefs"
        private const val KEY_AUTO_TRANSLATE = "auto_translate_enabled"
        private const val KEY_TARGET_LANGUAGE = "auto_translate_target_lang"
        private const val KEY_RECENT_LANGUAGES = "recent_languages"

        private val ISD_TO_LANGUAGE_CODE: Map<String, String> = mapOf(
            "91" to TranslationLanguage.HINDI.code,
            "92" to TranslationLanguage.URDU.code,
            "880" to TranslationLanguage.BENGALI.code,
            "1" to TranslationLanguage.ENGLISH.code,
            "44" to TranslationLanguage.ENGLISH.code,
            "34" to TranslationLanguage.SPANISH.code,
            "33" to TranslationLanguage.FRENCH.code,
            "49" to TranslationLanguage.GERMAN.code,
            "39" to TranslationLanguage.ITALIAN.code,
            "55" to TranslationLanguage.PORTUGUESE.code,
            "7" to TranslationLanguage.RUSSIAN.code,
            "81" to TranslationLanguage.JAPANESE.code,
            "82" to TranslationLanguage.KOREAN.code,
            "86" to TranslationLanguage.CHINESE.code,
            "20" to TranslationLanguage.ARABIC.code,
            "966" to TranslationLanguage.ARABIC.code,
            "971" to TranslationLanguage.ARABIC.code,
            "90" to TranslationLanguage.TURKISH.code,
            "48" to TranslationLanguage.POLISH.code,
            "31" to TranslationLanguage.DUTCH.code,
            "46" to TranslationLanguage.SWEDISH.code,
            "380" to TranslationLanguage.UKRAINIAN.code,
            "84" to TranslationLanguage.VIETNAMESE.code,
            "66" to TranslationLanguage.THAI.code,
            "62" to TranslationLanguage.INDONESIAN.code
        )

        @Volatile
        private var instance: TranslationPreferences? = null

        fun getInstance(context: Context): TranslationPreferences {
            return instance ?: synchronized(this) {
                instance ?: TranslationPreferences(context).also { instance = it }
            }
        }
    }
}
