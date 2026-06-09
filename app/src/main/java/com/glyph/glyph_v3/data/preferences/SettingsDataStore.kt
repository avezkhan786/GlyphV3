package com.glyph.glyph_v3.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Central DataStore for all local app settings.
 * Settings here are device-local and not synced to Firebase.
 */
object SettingsDataStore {

    // ── Chat Settings ──────────────────────────────────────────────
    private val KEY_FONT_SIZE = intPreferencesKey("font_size")
    private val KEY_ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
    private val KEY_MEDIA_VISIBILITY = booleanPreferencesKey("media_visibility")

    /** Font size: 0 = Small, 1 = Medium (default), 2 = Large */
    fun fontSizeFlow(context: Context): Flow<Int> =
        context.settingsDataStore.data.map { it[KEY_FONT_SIZE] ?: 1 }

    suspend fun setFontSize(context: Context, size: Int) {
        context.settingsDataStore.edit { it[KEY_FONT_SIZE] = size.coerceIn(0, 2) }
    }

    fun enterToSendFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_ENTER_TO_SEND] ?: false }

    suspend fun setEnterToSend(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_ENTER_TO_SEND] = enabled }
    }

    fun mediaVisibilityFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_MEDIA_VISIBILITY] ?: true }

    suspend fun setMediaVisibility(context: Context, visible: Boolean) {
        context.settingsDataStore.edit { it[KEY_MEDIA_VISIBILITY] = visible }
    }

    // ── Notification Settings ──────────────────────────────────────
    private val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    private val KEY_NOTIFICATION_SOUND = stringPreferencesKey("notification_sound")
    private val KEY_NOTIFICATION_VIBRATE = booleanPreferencesKey("notification_vibrate")
    private val KEY_NOTIFICATION_POPUP = booleanPreferencesKey("notification_popup")
    private val KEY_GROUP_NOTIFICATIONS_ENABLED = booleanPreferencesKey("group_notifications_enabled")
    private val KEY_GROUP_NOTIFICATION_SOUND = stringPreferencesKey("group_notification_sound")
    private val KEY_GROUP_NOTIFICATION_VIBRATE = booleanPreferencesKey("group_notification_vibrate")
    private val KEY_CALL_RINGTONE = stringPreferencesKey("call_ringtone")
    private val KEY_CALL_VIBRATE = booleanPreferencesKey("call_vibrate")

    fun notificationsEnabledFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }
    }

    fun notificationSoundFlow(context: Context): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_NOTIFICATION_SOUND] ?: "Default" }

    suspend fun setNotificationSound(context: Context, sound: String) {
        context.settingsDataStore.edit { it[KEY_NOTIFICATION_SOUND] = sound }
    }

    fun notificationVibrateFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_NOTIFICATION_VIBRATE] ?: true }

    suspend fun setNotificationVibrate(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_NOTIFICATION_VIBRATE] = enabled }
    }

    fun notificationPopupFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_NOTIFICATION_POPUP] ?: true }

    suspend fun setNotificationPopup(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_NOTIFICATION_POPUP] = enabled }
    }

    fun groupNotificationsEnabledFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_GROUP_NOTIFICATIONS_ENABLED] ?: true }

    suspend fun setGroupNotificationsEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_GROUP_NOTIFICATIONS_ENABLED] = enabled }
    }

    fun groupNotificationSoundFlow(context: Context): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_GROUP_NOTIFICATION_SOUND] ?: "Default" }

    suspend fun setGroupNotificationSound(context: Context, sound: String) {
        context.settingsDataStore.edit { it[KEY_GROUP_NOTIFICATION_SOUND] = sound }
    }

    fun groupNotificationVibrateFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_GROUP_NOTIFICATION_VIBRATE] ?: true }

    suspend fun setGroupNotificationVibrate(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_GROUP_NOTIFICATION_VIBRATE] = enabled }
    }

    fun callRingtoneFlow(context: Context): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_CALL_RINGTONE] ?: "Default" }

    suspend fun setCallRingtone(context: Context, ringtone: String) {
        context.settingsDataStore.edit { it[KEY_CALL_RINGTONE] = ringtone }
    }

    fun callVibrateFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_CALL_VIBRATE] ?: true }

    suspend fun setCallVibrate(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_CALL_VIBRATE] = enabled }
    }

    // ── Auto-download Settings ─────────────────────────────────────
    private val KEY_AUTO_DOWNLOAD_WIFI = booleanPreferencesKey("auto_download_wifi")
    private val KEY_AUTO_DOWNLOAD_MOBILE = booleanPreferencesKey("auto_download_mobile")
    private val KEY_AUTO_DOWNLOAD_ROAMING = booleanPreferencesKey("auto_download_roaming")

    fun autoDownloadWifiFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTO_DOWNLOAD_WIFI] ?: true }

    suspend fun setAutoDownloadWifi(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_DOWNLOAD_WIFI] = enabled }
    }

    fun autoDownloadMobileFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTO_DOWNLOAD_MOBILE] ?: false }

    suspend fun setAutoDownloadMobile(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_DOWNLOAD_MOBILE] = enabled }
    }

    fun autoDownloadRoamingFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTO_DOWNLOAD_ROAMING] ?: false }

    suspend fun setAutoDownloadRoaming(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTO_DOWNLOAD_ROAMING] = enabled }
    }

    // ── Security Settings ──────────────────────────────────────────
    private val KEY_APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
    private val KEY_APP_LOCK_TYPE = stringPreferencesKey("app_lock_type") // "biometric" | "pin"
    private val KEY_APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
    private val KEY_APP_LOCK_TIMEOUT = intPreferencesKey("app_lock_timeout") // minutes

    fun appLockEnabledFlow(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_APP_LOCK_ENABLED] ?: false }

    suspend fun setAppLockEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_APP_LOCK_ENABLED] = enabled }
    }

    fun appLockTypeFlow(context: Context): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_APP_LOCK_TYPE] ?: "biometric" }

    suspend fun setAppLockType(context: Context, type: String) {
        context.settingsDataStore.edit { it[KEY_APP_LOCK_TYPE] = type }
    }

    fun appLockPinHashFlow(context: Context): Flow<String?> =
        context.settingsDataStore.data.map { it[KEY_APP_LOCK_PIN_HASH] }

    suspend fun setAppLockPinHash(context: Context, hash: String) {
        context.settingsDataStore.edit { it[KEY_APP_LOCK_PIN_HASH] = hash }
    }

    fun appLockTimeoutFlow(context: Context): Flow<Int> =
        context.settingsDataStore.data.map { it[KEY_APP_LOCK_TIMEOUT] ?: 1 }

    suspend fun setAppLockTimeout(context: Context, minutes: Int) {
        context.settingsDataStore.edit { it[KEY_APP_LOCK_TIMEOUT] = minutes }
    }

    // ── Language ───────────────────────────────────────────────────
    private val KEY_APP_LANGUAGE = stringPreferencesKey("app_language")

    fun appLanguageFlow(context: Context): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_APP_LANGUAGE] ?: "system" }

    suspend fun setAppLanguage(context: Context, languageCode: String) {
        context.settingsDataStore.edit { it[KEY_APP_LANGUAGE] = languageCode }
    }
}
