package com.glyph.glyph_v3.data.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the single active live-camera share session so the foreground service can recover
 * after process death and keep streaming while live location remains active.
 */
object ActiveMapCameraShareStore {

    data class Session(
        val chatId: String,
        val targetUserId: String,
        val otherUserName: String,
        val otherUserAvatar: String
    ) {
        fun matches(chatId: String?, targetUserId: String?): Boolean {
            return this.chatId == chatId && this.targetUserId == targetUserId
        }
    }

    private const val PREFS_NAME = "glyph_live_camera_share"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_TARGET_USER_ID = "target_user_id"
    private const val KEY_OTHER_USER_NAME = "other_user_name"
    private const val KEY_OTHER_USER_AVATAR = "other_user_avatar"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        return prefs ?: synchronized(this) {
            prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .also { prefs = it }
        }
    }

    fun save(context: Context, session: Session) {
        prefs(context).edit()
            .putString(KEY_CHAT_ID, session.chatId)
            .putString(KEY_TARGET_USER_ID, session.targetUserId)
            .putString(KEY_OTHER_USER_NAME, session.otherUserName)
            .putString(KEY_OTHER_USER_AVATAR, session.otherUserAvatar)
            .apply()
    }

    fun get(context: Context): Session? {
        val store = prefs(context)
        val chatId = store.getString(KEY_CHAT_ID, null)
        val targetUserId = store.getString(KEY_TARGET_USER_ID, null)
        if (chatId.isNullOrBlank() || targetUserId.isNullOrBlank()) return null
        return Session(
            chatId = chatId,
            targetUserId = targetUserId,
            otherUserName = store.getString(KEY_OTHER_USER_NAME, "") ?: "",
            otherUserAvatar = store.getString(KEY_OTHER_USER_AVATAR, "") ?: ""
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
