package com.glyph.glyph_v3.data.repo

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages user privacy settings stored in Firestore.
 *
 * Firestore path: users/{uid}/settings/privacy
 *
 * Fields:
 *  - lastSeenVisibility: "everyone" | "contacts" | "nobody"
 *  - onlineVisibility: "everyone" | "contacts" | "nobody"
 *  - profilePhotoVisibility: "everyone" | "contacts" | "nobody"
 *  - aboutVisibility: "everyone" | "contacts" | "nobody"
 *  - readReceipts: Boolean
 */
object PrivacySettingsRepository {

    private const val TAG = "PrivacySettingsRepo"
    private const val PREFS_NAME_PREFIX = "privacy_settings_cache"
    private const val KEY_HAS_CACHE = "has_cache"
    private const val KEY_LAST_SEEN_VISIBILITY = "last_seen_visibility"
    private const val KEY_ONLINE_VISIBILITY = "online_visibility"
    private const val KEY_PROFILE_PHOTO_VISIBILITY = "profile_photo_visibility"
    private const val KEY_ABOUT_VISIBILITY = "about_visibility"
    private const val KEY_READ_RECEIPTS = "read_receipts"
    private const val KEY_NORMALIZED_PHONE = "normalized_phone"
    private const val CONTACTS_CACHE_PREFS = "privacy_contacts_cache"
    private const val KEY_DEVICE_CONTACT_NUMBERS = "device_contact_numbers"
    private const val KEY_DEVICE_CONTACT_NUMBERS_AT = "device_contact_numbers_at"
    private const val DEVICE_CONTACTS_CACHE_TTL_MS = 60_000L
    private val firestore = FirebaseFirestore.getInstance()
    @Volatile private var appContext: Context? = null
    @Volatile private var cachedCurrentUserId: String? = null
    @Volatile private var cachedCurrentUserSettings: PrivacySettings? = null
    @Volatile private var cachedDeviceContactNumbers: Set<String> = emptySet()
    @Volatile private var cachedDeviceContactNumbersAt: Long = 0L
    private val cachedUserPhoneNumbers = ConcurrentHashMap<String, String>()

    private val uid: String?
        get() = FirebaseAuth.getInstance().currentUser?.uid

    private fun settingsDoc() =
        firestore.collection("users").document(uid ?: "").collection("settings").document("privacy")

    private fun settingsDocForUser(userId: String) =
        firestore.collection("users").document(userId).collection("settings").document("privacy")

    // ── Data class ─────────────────────────────────────────────────
    data class PrivacySettings(
        val lastSeenVisibility: String = "everyone",
        val onlineVisibility: String = "everyone",
        val profilePhotoVisibility: String = "everyone",
        val aboutVisibility: String = "everyone",
        val readReceipts: Boolean = true
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getCachedPrivacySettings(): PrivacySettings? {
        val currentUid = uid ?: return null
        if (cachedCurrentUserId == currentUid) {
            cachedCurrentUserSettings?.let { return it }
        }

        val prefs = prefsForUser(currentUid) ?: return null
        if (!prefs.getBoolean(KEY_HAS_CACHE, false)) return null

        return PrivacySettings(
            lastSeenVisibility = prefs.getString(KEY_LAST_SEEN_VISIBILITY, "everyone") ?: "everyone",
            onlineVisibility = prefs.getString(KEY_ONLINE_VISIBILITY, "everyone") ?: "everyone",
            profilePhotoVisibility = prefs.getString(KEY_PROFILE_PHOTO_VISIBILITY, "everyone") ?: "everyone",
            aboutVisibility = prefs.getString(KEY_ABOUT_VISIBILITY, "everyone") ?: "everyone",
            readReceipts = prefs.getBoolean(KEY_READ_RECEIPTS, true)
        ).also { settings ->
            cachedCurrentUserId = currentUid
            cachedCurrentUserSettings = settings
        }
    }

    fun getCachedPrivacySettingsForUser(userId: String): PrivacySettings? {
        if (userId.isBlank()) return null
        if (userId == uid) return getCachedPrivacySettings()

        val prefs = prefsForUser(userId) ?: return null
        if (!prefs.getBoolean(KEY_HAS_CACHE, false)) return null

        return PrivacySettings(
            lastSeenVisibility = prefs.getString(KEY_LAST_SEEN_VISIBILITY, "everyone") ?: "everyone",
            onlineVisibility = prefs.getString(KEY_ONLINE_VISIBILITY, "everyone") ?: "everyone",
            profilePhotoVisibility = prefs.getString(KEY_PROFILE_PHOTO_VISIBILITY, "everyone") ?: "everyone",
            aboutVisibility = prefs.getString(KEY_ABOUT_VISIBILITY, "everyone") ?: "everyone",
            readReceipts = prefs.getBoolean(KEY_READ_RECEIPTS, true)
        )
    }

    fun canViewerSeeCached(ownerUserId: String, viewerUid: String, visibility: String): Boolean? {
        return when (visibility) {
            "everyone" -> true
            "nobody" -> false
            "contacts" -> {
                if (ownerUserId == viewerUid) {
                    true
                } else if (viewerUid == uid) {
                    isUserSavedInCachedViewerContacts(ownerUserId)
                } else {
                    null
                }
            }
            else -> true
        }
    }

    fun getCachedNormalizedPhoneForUser(userId: String): String {
        if (userId.isBlank()) return ""
        cachedUserPhoneNumbers[userId]?.let { return it }
        val cached = prefsForUser(userId)?.getString(KEY_NORMALIZED_PHONE, "").orEmpty()
        if (cached.isNotBlank()) {
            cachedUserPhoneNumbers[userId] = cached
        }
        return cached
    }

    fun getCachedNormalizedDeviceContactNumbers(): Set<String> {
        val context = appContext ?: return emptySet()
        val now = System.currentTimeMillis()
        if (cachedDeviceContactNumbers.isNotEmpty() && now - cachedDeviceContactNumbersAt < DEVICE_CONTACTS_CACHE_TTL_MS) {
            return cachedDeviceContactNumbers
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptySet()
        }

        val prefs = contactsCachePrefs() ?: return cachedDeviceContactNumbers
        val cached = prefs.getStringSet(KEY_DEVICE_CONTACT_NUMBERS, emptySet()).orEmpty()
        val cachedAt = prefs.getLong(KEY_DEVICE_CONTACT_NUMBERS_AT, 0L)
        if (cached.isNotEmpty()) {
            cachedDeviceContactNumbers = cached
            cachedDeviceContactNumbersAt = cachedAt
        }
        return if (now - cachedAt < DEVICE_CONTACTS_CACHE_TTL_MS) cached else cachedDeviceContactNumbers
    }

    suspend fun warmCacheIfNeeded(forceRefresh: Boolean = false): PrivacySettings? {
        if (!forceRefresh) {
            getCachedPrivacySettings()?.let { return it }
        }
        return getPrivacySettings().takeIf { uid != null }
    }

    // ── Real-time listener ─────────────────────────────────────────
    fun privacySettingsFlow(): Flow<PrivacySettings> = callbackFlow {
        val currentUid = uid
        if (currentUid == null) {
            trySend(PrivacySettings())
            close()
            return@callbackFlow
        }

        getCachedPrivacySettings()?.let { trySend(it) }

        val registration: ListenerRegistration = settingsDoc()
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to privacy settings", error)
                    return@addSnapshotListener
                }
                val settings = snapshot.toPrivacySettings()
                cacheCurrentUserSettings(settings)
                trySend(settings)
            }

        awaitClose { registration.remove() }
    }

    // ── One-shot fetch ─────────────────────────────────────────────
    suspend fun getPrivacySettings(): PrivacySettings {
        val currentUid = uid ?: return PrivacySettings()
        return try {
            val doc = settingsDoc().get().await()
            doc.toPrivacySettings().also { cacheCurrentUserSettings(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching privacy settings", e)
            getCachedPrivacySettings() ?: PrivacySettings()
        }
    }

    // ── Update helpers ─────────────────────────────────────────────
    suspend fun updateLastSeenVisibility(visibility: String) {
        update("lastSeenVisibility", visibility)
    }

    suspend fun updateOnlineVisibility(visibility: String) {
        update("onlineVisibility", visibility)
    }

    suspend fun updateProfilePhotoVisibility(visibility: String) {
        update("profilePhotoVisibility", visibility)
    }

    suspend fun updateAboutVisibility(visibility: String) {
        update("aboutVisibility", visibility)
    }

    suspend fun updateReadReceipts(enabled: Boolean) {
        update("readReceipts", enabled)
    }

    private suspend fun update(field: String, value: Any) {
        val currentUid = uid ?: return
        try {
            settingsDoc().set(mapOf(field to value), SetOptions.merge()).await()
            getCachedPrivacySettings()?.let { cached ->
                cacheCurrentUserSettings(cached.withUpdatedField(field, value))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update $field", e)
            throw e
        }
    }

    // ── Fetch another user's privacy settings (for enforcement by the viewer) ──
    /**
     * Fetches the privacy settings for a specific user (not the current user).
     * Used by the viewer to determine what data should be displayed.
     */
    suspend fun getPrivacySettingsForUser(userId: String): PrivacySettings {
        return try {
            val doc = settingsDocForUser(userId).get().await()
            if (doc.exists()) {
                PrivacySettings(
                    lastSeenVisibility = doc.getString("lastSeenVisibility") ?: "everyone",
                    onlineVisibility = doc.getString("onlineVisibility") ?: "everyone",
                    profilePhotoVisibility = doc.getString("profilePhotoVisibility") ?: "everyone",
                    aboutVisibility = doc.getString("aboutVisibility") ?: "everyone",
                    readReceipts = doc.getBoolean("readReceipts") ?: true
                ).also { cachePrivacySettingsForUser(userId, it) }
            } else {
                PrivacySettings()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching privacy settings for user $userId", e)
            getCachedPrivacySettingsForUser(userId) ?: PrivacySettings()
        }
    }

    /**
     * Real-time listener for another user's privacy settings.
     */
    fun privacySettingsFlowForUser(userId: String): Flow<PrivacySettings> = callbackFlow {
        getCachedPrivacySettingsForUser(userId)?.let { trySend(it) }
        val registration: ListenerRegistration = settingsDocForUser(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to privacy settings for $userId", error)
                    return@addSnapshotListener
                }
                val settings = snapshot.toPrivacySettings()
                cachePrivacySettingsForUser(userId, settings)
                trySend(settings)
            }
        awaitClose { registration.remove() }
    }

    // ── Check if a specific user can see our privacy-gated data ────
    /**
     * Checks whether [viewerUid] is allowed to see data gated by [visibility].
     * "everyone" → always visible
     * "contacts" → visible if viewerUid is in the current user's contacts
     * "nobody" → never visible
     */
    suspend fun canViewerSee(viewerUid: String, visibility: String): Boolean {
        val currentUid = uid ?: return false
        return canViewerSee(ownerUserId = currentUid, viewerUid = viewerUid, visibility = visibility)
    }

    suspend fun canViewerSee(ownerUserId: String, viewerUid: String, visibility: String): Boolean {
        return when (visibility) {
            "everyone" -> true
            "nobody" -> false
            "contacts" -> {
                if (ownerUserId == viewerUid) return true
                try {
                    if (viewerUid == uid) {
                        isUserSavedInViewerContacts(ownerUserId)
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking contacts for visibility", e)
                    false
                }
            }
            else -> true
        }
    }

    private suspend fun isUserSavedInViewerContacts(ownerUserId: String): Boolean {
        val normalizedPhone = getNormalizedPhoneForUser(ownerUserId)
        if (normalizedPhone.isBlank()) {
            return false
        }
        val deviceContactNumbers = getNormalizedDeviceContactNumbers()
        // When device contacts are empty (no READ_CONTACTS permission, or contacts
        // haven't been synced yet), we can't verify whether the user is a contact.
        // Treat this as optimistic — show presence rather than hiding it for every
        // single "contacts"-privacy user.
        if (deviceContactNumbers.isEmpty()) {
            return true
        }
        val found = normalizedPhone in deviceContactNumbers
        return found
    }

    fun isUserSavedInCachedViewerContacts(ownerUserId: String): Boolean? {
        val normalizedPhone = getCachedNormalizedPhoneForUser(ownerUserId)
        if (normalizedPhone.isBlank()) return null
        val deviceContactNumbers = getCachedNormalizedDeviceContactNumbers()
        // When device contacts are empty (no READ_CONTACTS permission or not synced),
        // we can't verify the restriction — return null so the caller treats it as
        // "not yet determined" rather than "definitely not a contact."
        if (deviceContactNumbers.isEmpty()) return null
        return normalizedPhone in deviceContactNumbers
    }

    private suspend fun getNormalizedPhoneForUser(userId: String): String {
        getCachedNormalizedPhoneForUser(userId).takeIf { it.isNotBlank() }?.let { cached ->
            return cached
        }

        var rawPhone = ""
        val normalizedPhone = runCatching {
            val snapshot = firestore.collection("users").document(userId).get().await()
            rawPhone = snapshot.getString("phoneNumber").orEmpty()
            PhoneNumberUtil.normalizeToLast10Digits(rawPhone)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to resolve phone number for privacy check: $userId", error)
            ""
        }

        // Only cache successful results — caching "" would poison the cache
        // and prevent future successful lookups from SharedPreferences.
        if (normalizedPhone.isNotBlank()) {
            cacheNormalizedPhoneForUser(userId, normalizedPhone)
        }
        return normalizedPhone
    }

    private suspend fun getNormalizedDeviceContactNumbers(forceRefresh: Boolean = false): Set<String> = withContext(Dispatchers.IO) {
        val context = appContext
        if (context == null) {
            Log.w(TAG, "PrivacySettingsRepository not initialized; device contacts unavailable")
            return@withContext emptySet()
        }

        val now = System.currentTimeMillis()
        if (!forceRefresh && now - cachedDeviceContactNumbersAt < DEVICE_CONTACTS_CACHE_TTL_MS) {
            return@withContext cachedDeviceContactNumbers
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            cachedDeviceContactNumbers = emptySet()
            cachedDeviceContactNumbersAt = now
            return@withContext emptySet()
        }

        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val normalizedNumbers = linkedSetOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val normalized = PhoneNumberUtil.normalizeToLast10Digits(
                    cursor.getString(numberIndex).orEmpty()
                )
                if (normalized.isNotBlank()) {
                    normalizedNumbers.add(normalized)
                }
            }
        }

        cachedDeviceContactNumbers = normalizedNumbers
        cachedDeviceContactNumbersAt = now
        contactsCachePrefs()?.edit()
            ?.putStringSet(KEY_DEVICE_CONTACT_NUMBERS, normalizedNumbers)
            ?.putLong(KEY_DEVICE_CONTACT_NUMBERS_AT, now)
            ?.apply()
        normalizedNumbers
    }

    private fun prefsForUser(userId: String): SharedPreferences? {
        val context = appContext ?: return null
        return context.getSharedPreferences("${PREFS_NAME_PREFIX}_$userId", Context.MODE_PRIVATE)
    }

    private fun contactsCachePrefs(): SharedPreferences? {
        val context = appContext ?: return null
        return context.getSharedPreferences(CONTACTS_CACHE_PREFS, Context.MODE_PRIVATE)
    }

    private fun cachePrivacySettingsForUser(userId: String, settings: PrivacySettings) {
        if (userId.isBlank()) return
        if (userId == uid) {
            cacheCurrentUserSettings(settings)
            return
        }

        prefsForUser(userId)?.edit()
            ?.putBoolean(KEY_HAS_CACHE, true)
            ?.putString(KEY_LAST_SEEN_VISIBILITY, settings.lastSeenVisibility)
            ?.putString(KEY_ONLINE_VISIBILITY, settings.onlineVisibility)
            ?.putString(KEY_PROFILE_PHOTO_VISIBILITY, settings.profilePhotoVisibility)
            ?.putString(KEY_ABOUT_VISIBILITY, settings.aboutVisibility)
            ?.putBoolean(KEY_READ_RECEIPTS, settings.readReceipts)
            ?.apply()
    }

    private fun cacheNormalizedPhoneForUser(userId: String, normalizedPhone: String) {
        if (userId.isBlank()) return
        cachedUserPhoneNumbers[userId] = normalizedPhone
        prefsForUser(userId)?.edit()?.putString(KEY_NORMALIZED_PHONE, normalizedPhone)?.apply()
    }

    private fun cacheCurrentUserSettings(settings: PrivacySettings) {
        val currentUid = uid ?: return
        cachedCurrentUserId = currentUid
        cachedCurrentUserSettings = settings
        prefsForUser(currentUid)?.edit()
            ?.putBoolean(KEY_HAS_CACHE, true)
            ?.putString(KEY_LAST_SEEN_VISIBILITY, settings.lastSeenVisibility)
            ?.putString(KEY_ONLINE_VISIBILITY, settings.onlineVisibility)
            ?.putString(KEY_PROFILE_PHOTO_VISIBILITY, settings.profilePhotoVisibility)
            ?.putString(KEY_ABOUT_VISIBILITY, settings.aboutVisibility)
            ?.putBoolean(KEY_READ_RECEIPTS, settings.readReceipts)
            ?.apply()
    }

    private fun com.google.firebase.firestore.DocumentSnapshot?.toPrivacySettings(): PrivacySettings {
        if (this == null || !exists()) return PrivacySettings()
        return PrivacySettings(
            lastSeenVisibility = getString("lastSeenVisibility") ?: "everyone",
            onlineVisibility = getString("onlineVisibility") ?: "everyone",
            profilePhotoVisibility = getString("profilePhotoVisibility") ?: "everyone",
            aboutVisibility = getString("aboutVisibility") ?: "everyone",
            readReceipts = getBoolean("readReceipts") ?: true
        )
    }

    private fun PrivacySettings.withUpdatedField(field: String, value: Any): PrivacySettings {
        return when (field) {
            "lastSeenVisibility" -> copy(lastSeenVisibility = value as String)
            "onlineVisibility" -> copy(onlineVisibility = value as String)
            "profilePhotoVisibility" -> copy(profilePhotoVisibility = value as String)
            "aboutVisibility" -> copy(aboutVisibility = value as String)
            "readReceipts" -> copy(readReceipts = value as Boolean)
            else -> this
        }
    }
}
