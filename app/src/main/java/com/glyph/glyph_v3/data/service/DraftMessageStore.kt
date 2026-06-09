package com.glyph.glyph_v3.data.service

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager

/**
 * Lightweight conversation draft storage with in-memory cache + SharedPreferences persistence.
 */
object DraftMessageStore {

    private const val PREFS_NAME = "draft_messages_prefs"
    private const val KEY_PREFIX = "draft_"

    private var prefs: SharedPreferences? = null
    private val drafts = mutableMapOf<String, String>()

    fun init(context: Context) {
        if (prefs != null) return

        val baseContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(UserManager::class.java)
            if (um != null && !um.isUserUnlocked) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                runCatching { deviceContext.moveSharedPreferencesFrom(context, PREFS_NAME) }
                deviceContext
            } else {
                context
            }
        } else {
            context
        }

        prefs = baseContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    fun getDraft(chatId: String): String {
        return drafts[chatId].orEmpty()
    }

    fun setDraft(chatId: String, text: String) {
        if (text.isBlank()) {
            clearDraft(chatId)
            return
        }

        drafts[chatId] = text
        prefs?.edit()?.putString(KEY_PREFIX + chatId, text)?.apply()
    }

    fun clearDraft(chatId: String) {
        drafts.remove(chatId)
        prefs?.edit()?.remove(KEY_PREFIX + chatId)?.apply()
    }

    private fun loadFromPrefs() {
        val snapshot = prefs?.all ?: return
        for ((key, value) in snapshot) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                drafts[key.removePrefix(KEY_PREFIX)] = value
            }
        }
    }
}
