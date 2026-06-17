package com.glyph.glyph_v3.data.backup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-based backup preferences.
 *
 * Persists the user's backup configuration: frequency, network policy,
 * video inclusion, and the linked Google account email.
 */
object BackupPreferences {

    private val Context.backupDataStore by preferencesDataStore(name = "backup_prefs")

    // Keys
    private val KEY_BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
    private val KEY_BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency")
    private val KEY_BACKUP_NETWORK = stringPreferencesKey("backup_network")
    private val KEY_BACKUP_INCLUDE_VIDEOS = booleanPreferencesKey("backup_include_videos")
    private val KEY_LAST_BACKUP_TIME = longPreferencesKey("last_backup_time")
    private val KEY_LAST_BACKUP_SIZE = longPreferencesKey("last_backup_size")
    private val KEY_NEXT_BACKUP_TIME = longPreferencesKey("next_backup_time")
    private val KEY_GOOGLE_ACCOUNT_EMAIL = stringPreferencesKey("backup_google_account")

    enum class BackupFrequency(val durationMinutes: Long, val displayName: String) {
        DAILY(1440L, "Daily"),
        WEEKLY(10080L, "Weekly"),
        MONTHLY(43200L, "Monthly"),
        MANUAL(0L, "Manual only");

        companion object {
            fun fromName(name: String): BackupFrequency {
                return entries.find { it.name.equals(name, ignoreCase = true) } ?: MANUAL
            }
        }
    }

    enum class NetworkPolicy(val displayName: String) {
        WIFI_ONLY("Wi-Fi only"),
        WIFI_MOBILE("Wi-Fi + Mobile Data");

        companion object {
            fun fromName(name: String): NetworkPolicy {
                return entries.find { it.name.equals(name, ignoreCase = true) } ?: WIFI_ONLY
            }
        }
    }

    fun backupEnabledFlow(context: Context): Flow<Boolean> {
        return context.backupDataStore.data.map { it[KEY_BACKUP_ENABLED] ?: false }
    }

    fun backupFrequencyFlow(context: Context): Flow<BackupFrequency> {
        return context.backupDataStore.data.map {
            BackupFrequency.fromName(it[KEY_BACKUP_FREQUENCY] ?: BackupFrequency.WEEKLY.name)
        }
    }

    fun networkPolicyFlow(context: Context): Flow<NetworkPolicy> {
        return context.backupDataStore.data.map {
            NetworkPolicy.fromName(it[KEY_BACKUP_NETWORK] ?: NetworkPolicy.WIFI_ONLY.name)
        }
    }

    fun includeVideosFlow(context: Context): Flow<Boolean> {
        return context.backupDataStore.data.map { it[KEY_BACKUP_INCLUDE_VIDEOS] ?: true }
    }

    fun lastBackupTimeFlow(context: Context): Flow<Long> {
        return context.backupDataStore.data.map { it[KEY_LAST_BACKUP_TIME] ?: 0L }
    }

    fun lastBackupSizeFlow(context: Context): Flow<Long> {
        return context.backupDataStore.data.map { it[KEY_LAST_BACKUP_SIZE] ?: 0L }
    }

    fun googleAccountEmailFlow(context: Context): Flow<String?> {
        return context.backupDataStore.data.map { it[KEY_GOOGLE_ACCOUNT_EMAIL] }
    }

    suspend fun setBackupEnabled(context: Context, enabled: Boolean) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply { set(KEY_BACKUP_ENABLED, enabled) } }
    }

    suspend fun setBackupFrequency(context: Context, frequency: BackupFrequency) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply { set(KEY_BACKUP_FREQUENCY, frequency.name) } }
    }

    suspend fun setNetworkPolicy(context: Context, policy: NetworkPolicy) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply { set(KEY_BACKUP_NETWORK, policy.name) } }
    }

    suspend fun setIncludeVideos(context: Context, include: Boolean) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply { set(KEY_BACKUP_INCLUDE_VIDEOS, include) } }
    }

    suspend fun setLastBackupTime(context: Context, timeMs: Long) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply { set(KEY_LAST_BACKUP_TIME, timeMs) } }
    }

    suspend fun setLastBackupSize(context: Context, sizeBytes: Long) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply { set(KEY_LAST_BACKUP_SIZE, sizeBytes) } }
    }

    suspend fun setNextBackupTime(context: Context, timeMs: Long) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply { set(KEY_NEXT_BACKUP_TIME, timeMs) } }
    }

    suspend fun setGoogleAccountEmail(context: Context, email: String?) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply {
            if (email != null) set(KEY_GOOGLE_ACCOUNT_EMAIL, email)
            else remove(KEY_GOOGLE_ACCOUNT_EMAIL)
        } }
    }

    // Prevent restore offer from showing on every app open after a backup exists in Drive.
    private val KEY_RESTORE_OFFER_LAST_SEEN = longPreferencesKey("restore_offer_last_seen")

    suspend fun markRestoreOfferSeen(context: Context) {
        context.backupDataStore.updateData { it.toMutablePreferences().apply {
            set(KEY_RESTORE_OFFER_LAST_SEEN, System.currentTimeMillis())
        } }
    }

    /**
     * Returns true if the restore offer should be shown (hasn't been seen recently,
     * i.e. not in the last 30 days).
     */
    suspend fun shouldShowRestoreOffer(context: Context): Boolean {
        val lastSeen = context.backupDataStore.data.first()[KEY_RESTORE_OFFER_LAST_SEEN] ?: 0L
        return lastSeen == 0L || (System.currentTimeMillis() - lastSeen) > 30 * 24 * 60 * 60 * 1000L
    }

    fun nextBackupTimeFlow(context: Context): Flow<Long> {
        return context.backupDataStore.data.map { it[KEY_NEXT_BACKUP_TIME] ?: 0L }
    }
}
