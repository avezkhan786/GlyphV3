package com.glyph.glyph_v3.data.cache

import android.content.Context

/**
 * Lightweight local cache for the current user's profile data.
 * Used to instantly render Settings screen without refetching Firestore.
 */
object UserProfileCache {
    private const val PREFS_NAME = "user_profile_cache"

    private const val KEY_USER_ID = "user_id"
    private const val KEY_PHONE = "phone"
    private const val KEY_USERNAME = "username"
    private const val KEY_BIO = "bio"
    private const val KEY_AVATAR_URL = "avatar_url"
    private const val KEY_AVATAR_FULL_URL = "avatar_full_url"

    data class CachedUserProfile(
        val userId: String,
        val phone: String?,
        val username: String,
        val bio: String,
        val avatarUrl: String?,
        val avatarFullUrl: String? = null
    )

    fun get(context: Context, userId: String?): CachedUserProfile? {
        if (userId.isNullOrEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedUserId = prefs.getString(KEY_USER_ID, null) ?: return null
        if (cachedUserId != userId) return null

        return CachedUserProfile(
            userId = cachedUserId,
            phone = prefs.getString(KEY_PHONE, null),
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            bio = prefs.getString(KEY_BIO, "") ?: "",
            avatarUrl = prefs.getString(KEY_AVATAR_URL, null),
            avatarFullUrl = prefs.getString(KEY_AVATAR_FULL_URL, null)
        )
    }

    fun save(context: Context, profile: CachedUserProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_USER_ID, profile.userId)
            .putString(KEY_PHONE, profile.phone)
            .putString(KEY_USERNAME, profile.username)
            .putString(KEY_BIO, profile.bio)
            .putString(KEY_AVATAR_URL, profile.avatarUrl)
            .putString(KEY_AVATAR_FULL_URL, profile.avatarFullUrl)
            .apply()
    }

    fun update(
        context: Context,
        userId: String?,
        phone: String? = null,
        username: String? = null,
        bio: String? = null,
        avatarUrl: String? = null,
        avatarFullUrl: String? = null
    ) {
        if (userId.isNullOrEmpty()) return
        val current = get(context, userId) ?: CachedUserProfile(
            userId = userId,
            phone = phone,
            username = username ?: "",
            bio = bio ?: "",
            avatarUrl = avatarUrl,
            avatarFullUrl = avatarFullUrl
        )
        save(
            context,
            current.copy(
                phone = phone ?: current.phone,
                username = username ?: current.username,
                bio = bio ?: current.bio,
                avatarUrl = avatarUrl ?: current.avatarUrl,
                avatarFullUrl = avatarFullUrl ?: current.avatarFullUrl
            )
        )
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}
