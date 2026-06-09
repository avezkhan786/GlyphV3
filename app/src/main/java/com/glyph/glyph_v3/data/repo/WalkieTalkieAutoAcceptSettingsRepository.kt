package com.glyph.glyph_v3.data.repo

import android.content.Context
import com.glyph.glyph_v3.data.models.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WalkieTalkieAutoAcceptSettings(
    val enabled: Boolean = false,
    val scope: WalkieTalkieAutoAcceptScope = WalkieTalkieAutoAcceptScope.CONTACTS,
    val allowedUserIds: Set<String> = emptySet()
)

enum class WalkieTalkieAutoAcceptScope {
    CONTACTS,
    SELECTED_USERS
}

class WalkieTalkieAutoAcceptSettingsRepository {

    companion object {
        private const val PREFS_NAME = "wt_auto_accept_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SCOPE = "scope"
        private const val KEY_ALLOWED_USERS = "allowed_users"
        private const val CONTACTS_CACHE_TTL_MS = 60_000L

        @Volatile
        private var cachedSelectableContacts: List<User>? = null

        @Volatile
        private var cachedSelectableContactsAt: Long = 0L
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getSettings(context: Context): WalkieTalkieAutoAcceptSettings = withContext(Dispatchers.IO) {
        val prefs = prefs(context)
        val scope = runCatching {
            WalkieTalkieAutoAcceptScope.valueOf(
                prefs.getString(KEY_SCOPE, WalkieTalkieAutoAcceptScope.CONTACTS.name)
                    ?: WalkieTalkieAutoAcceptScope.CONTACTS.name
            )
        }.getOrDefault(WalkieTalkieAutoAcceptScope.CONTACTS)

        WalkieTalkieAutoAcceptSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            scope = scope,
            allowedUserIds = prefs.getStringSet(KEY_ALLOWED_USERS, emptySet()).orEmpty()
        )
    }

    suspend fun saveSettings(
        context: Context,
        settings: WalkieTalkieAutoAcceptSettings
    ) = withContext(Dispatchers.IO) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_SCOPE, settings.scope.name)
            .putStringSet(KEY_ALLOWED_USERS, settings.allowedUserIds)
            .apply()
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    suspend fun shouldAutoAcceptIncomingCall(
        context: Context,
        callerUserId: String
    ): Boolean {
        if (callerUserId.isBlank()) return false

        val settings = getSettings(context)
        if (!settings.enabled) return false

        return when (settings.scope) {
            WalkieTalkieAutoAcceptScope.CONTACTS -> {
                StatusRepository.getAllContacts().any { it.id == callerUserId }
            }
            WalkieTalkieAutoAcceptScope.SELECTED_USERS -> {
                callerUserId in settings.allowedUserIds
            }
        }
    }

    suspend fun getSelectableContacts(): List<User> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedSelectableContacts
        if (cached != null && now - cachedSelectableContactsAt < CONTACTS_CACHE_TTL_MS) {
            return@withContext cached
        }

        StatusRepository.getAllContacts().also { contacts ->
            cachedSelectableContacts = contacts
            cachedSelectableContactsAt = System.currentTimeMillis()
        }
    }
}