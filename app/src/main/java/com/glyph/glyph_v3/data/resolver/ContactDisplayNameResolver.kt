package com.glyph.glyph_v3.data.resolver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized contact display name resolver that follows WhatsApp's priority:
 *
 * **Resolution priority (1:1 chats and group members):**
 *   1. Device contact name (user's locally saved contact)
 *   2. Remote user's profile/display name (Firestore username)
 *   3. Phone number (formatted for display)
 *   4. Fallback string (e.g. "Unknown")
 *
 * **Privacy guarantee:** Device contact names are resolved in-memory only
 * and are NEVER persisted to Room, SharedPreferences, or transmitted to any server.
 *
 * **Thread safety:** All public methods are safe to call from any thread.
 * Cache lookups are lock-free via ConcurrentHashMap; ContentResolver queries
 * run on Dispatchers.IO.
 *
 * **Real-time updates:** A ContentObserver on ContactsContract detects when the
 * user adds, renames, or deletes a device contact and automatically refreshes
 * the in-memory cache. UI screens observe [cacheVersion] to recompose/re-bind.
 */
object ContactDisplayNameResolver {

    private const val TAG = "ContactDisplayNameResolver"
    private const val PREFS_USER_PHONES = "glyph_user_phone_cache"

    // ── In-memory caches ──────────────────────────────────────────────────────

    /** Normalized 10-digit phone number → device contact DISPLAY_NAME */
    private val contactNameCache = ConcurrentHashMap<String, String>()

    /** Firebase userId → phone number (lazily populated from Firestore) */
    private val userPhoneCache = ConcurrentHashMap<String, String>()

    // ── Reactive version counter for UI recomposition ──────────────────────────

    private val _cacheVersion = MutableStateFlow(0L)

    /**
     * Version counter incremented each time [refreshContactCache] completes.
     * Compose screens should collect this as State and re-resolve names on change.
     * Legacy View-based screens can observe it in a lifecycle-aware coroutine.
     */
    val cacheVersion: StateFlow<Long> = _cacheVersion.asStateFlow()

    // ── Internal state ─────────────────────────────────────────────────────────

    private var appContext: Context? = null
    private var contactObserver: ContentObserver? = null
    private var debounceJob: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var initialized = false

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Initialize the resolver. Must be called once from [Application.onCreate].
     *
     * - Loads all device contacts into the in-memory cache
     * - Registers a ContentObserver for real-time contact change detection
     * - Safe to call multiple times (subsequent calls are no-ops)
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        // Recreate scope if it was cancelled by a previous shutdown()
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }
        val initStartMs = android.os.SystemClock.elapsedRealtime()
        Log.d(TAG, "[PerfTrace] init() called at elapsedRealtime=$initStartMs ms")
        scope.launch(Dispatchers.IO) {
            // Restore persisted userId→phone mappings from the last Firestore fetch.
            // This runs before loadAllContacts so that legacy call-log entries with
            // blank phone columns can resolve saved contact names on the very first paint
            // without waiting for a Firestore round-trip (~2-3 s).
            restorePersistedUserPhones(context.applicationContext)
            val loadStartMs = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "[PerfTrace] loadAllContacts() START at elapsedRealtime=$loadStartMs ms (delay since init=${loadStartMs - initStartMs} ms)")
            loadAllContacts(context)
            val loadEndMs = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "[PerfTrace] loadAllContacts() END  at elapsedRealtime=$loadEndMs ms (took ${loadEndMs - loadStartMs} ms, contacts=${contactNameCache.size})")
            _cacheVersion.value++ // Signal observers that the initial cache is ready
            Log.d(TAG, "[PerfTrace] cacheVersion incremented to ${_cacheVersion.value} at elapsedRealtime=${android.os.SystemClock.elapsedRealtime()} ms")
            registerContactObserver(context)
        }
    }

    /**
     * Resolve the best display name for a user.
     *
     * Priority: Device contact name → Remote profile name → Phone number → Fallback
     *
     * @param otherUserId       Firebase UID of the other user (used to look up cached phone)
     * @param remoteProfileName The username/display name from Firestore
     * @param remotePhoneNumber The phone number from Firestore (if available)
     * @param fallback          String to return when all inputs are blank (default "Unknown")
     * @return The resolved display name
     */
    fun getDisplayName(
        otherUserId: String? = null,
        remoteProfileName: String? = null,
        remotePhoneNumber: String? = null,
        fallback: String = "Unknown"
    ): String {
        // 1. Try device contact name via phone number
        val effectivePhone = remotePhoneNumber?.takeIf { it.isNotBlank() }
            ?: otherUserId?.let { userPhoneCache[it] }?.takeIf { it.isNotBlank() }

        if (!effectivePhone.isNullOrBlank()) {
            val normalized = PhoneNumberUtil.normalizeToLast10Digits(effectivePhone)
            val contactName = contactNameCache[normalized]
            if (!contactName.isNullOrBlank()) {
                return contactName
            }
        }

        // 2. Fall back to remote profile name
        if (!remoteProfileName.isNullOrBlank()) {
            return remoteProfileName
        }

        // 3. Fall back to phone number (formatted for display)
        val displayPhone = effectivePhone ?: remotePhoneNumber
        if (!displayPhone.isNullOrBlank()) {
            return PhoneNumberUtil.formatForDisplay(displayPhone)
        }

        // 4. Ultimate fallback
        return fallback
    }

    /**
     * Convenience overload: resolve display name using only a userId and remote profile name.
     * Looks up the cached phone number for the userId.
     */
    fun getDisplayName(
        otherUserId: String?,
        remoteProfileName: String?,
        fallback: String = "Unknown"
    ): String = getDisplayName(
        otherUserId = otherUserId,
        remoteProfileName = remoteProfileName,
        remotePhoneNumber = null,
        fallback = fallback
    )

    /**
     * Cache a userId → phoneNumber mapping for future lookups.
     * Call this whenever user data is fetched from Firestore.
     */
    fun cacheUserPhone(userId: String, phoneNumber: String) {
        if (userId.isNotBlank() && phoneNumber.isNotBlank()) {
            userPhoneCache[userId] = phoneNumber
        }
    }

    /**
     * Batch cache userId → phoneNumber mappings.
     */
    fun cacheUserPhones(phones: Map<String, String>) {
        phones.forEach { (uid, phone) ->
            if (uid.isNotBlank() && phone.isNotBlank()) {
                userPhoneCache[uid] = phone
            }
        }
    }

    /**
     * Persist the current [userPhoneCache] (userId→phone) to SharedPreferences.
     * Call this after a bulk Firestore user-phone fetch so subsequent cold starts
     * can restore the mappings synchronously — eliminating the Firestore round-trip
     * delay for legacy call-log entries with blank stored phone columns.
     *
     * Safe to call from any thread.
     */
    fun persistUserPhones(context: Context) {
        val snapshot = HashMap<String, String>(userPhoneCache)
        if (snapshot.isEmpty()) return
        try {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_USER_PHONES, Context.MODE_PRIVATE)
            prefs.edit().apply {
                clear()
                snapshot.forEach { (uid, phone) -> putString(uid, phone) }
            }.apply()
            Log.d(TAG, "[PerfTrace] persistUserPhones: saved ${snapshot.size} entries")
        } catch (e: Exception) {
            Log.w(TAG, "persistUserPhones failed", e)
        }
    }

    /**
     * Load userId→phone mappings previously saved by [persistUserPhones].
     * Entries are merged into [userPhoneCache] without overwriting newer in-memory values.
     * Intended to be called from the IO coroutine inside [init].
     */
    private fun restorePersistedUserPhones(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_USER_PHONES, Context.MODE_PRIVATE)
            var restored = 0
            for ((uid, value) in prefs.all) {
                if (uid.isNotBlank() && value is String && value.isNotBlank()) {
                    userPhoneCache.putIfAbsent(uid, value)
                    restored++
                }
            }
            if (restored > 0) {
                Log.d(TAG, "[PerfTrace] restorePersistedUserPhones: restored $restored userId→phone entries")
            }
        } catch (e: Exception) {
            Log.w(TAG, "restorePersistedUserPhones failed", e)
        }
    }

    /**
     * Force-refresh the in-memory contact name cache from the device Contacts Provider.
     * Called automatically by the ContentObserver; call manually when permission is
     * granted after init.
     */
    fun refreshContactCache(context: Context) {
        scope.launch(Dispatchers.IO) {
            loadAllContacts(context)
            _cacheVersion.value++
        }
    }

    /**
     * Clear all caches (device contact names and userId→phone mappings).
     * Does NOT unregister the ContentObserver or cancel the coroutine scope.
     * For full cleanup on logout, use [shutdown] instead.
     */
    fun clearCache() {
        contactNameCache.clear()
        userPhoneCache.clear()
        _cacheVersion.value = 0L
    }

    /**
     * Full cleanup: clears caches, unregisters the ContentObserver, cancels pending
     * work, and resets [initialized] so [init] can be called again. Use this on logout
     * to prevent data leakage between accounts and to allow re-initialization after
     * re-login without process death.
     */
    fun shutdown() {
        appContext?.let { ctx ->
            contactObserver?.let { ctx.contentResolver.unregisterContentObserver(it) }
        }
        contactObserver = null
        debounceJob?.cancel()
        debounceJob = null
        scope.cancel()
        initialized = false
        clearCache()
    }

    // ── Internal implementation ────────────────────────────────────────────────

    /**
     * Load all device contacts into [contactNameCache].
     * Each phone number → DISPLAY_NAME mapping is stored keyed by normalized 10-digit number.
     */
    private fun loadAllContacts(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CONTACTS permission not granted; device contact names unavailable")
            return
        }

        val newCache = ConcurrentHashMap<String, String>()

        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )

            val queryStartMs = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "[PerfTrace] ContentResolver.query(Phone) START at elapsedRealtime=$queryStartMs ms")
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                Log.d(TAG, "[PerfTrace] ContentResolver.query(Phone) returned cursor in ${android.os.SystemClock.elapsedRealtime() - queryStartMs} ms, count=${cursor.count}")
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val rawNumber = cursor.getString(numberIdx).orEmpty()
                    val displayName = cursor.getString(nameIdx).orEmpty()
                    if (rawNumber.isBlank() || displayName.isBlank()) continue

                    val normalized = PhoneNumberUtil.normalizeToLast10Digits(rawNumber)
                    if (normalized.isNotBlank()) {
                        // Keep the first display name encountered for a given normalized number
                        newCache.putIfAbsent(normalized, displayName)
                    }
                }
            }

            // Atomic swap: clear old entries and put all new ones
            contactNameCache.clear()
            contactNameCache.putAll(newCache)
            Log.d(TAG, "Loaded ${contactNameCache.size} device contacts into cache")

        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException loading device contacts", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load device contacts", e)
        }
    }

    /**
     * Register a ContentObserver that refreshes the cache when the device contacts change.
     * Debounced to coalesce rapid-fire change notifications.
     */
    private fun registerContactObserver(context: Context) {
        // Unregister previous observer if any
        contactObserver?.let { context.contentResolver.unregisterContentObserver(it) }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                debouncedRefresh()
            }

            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                debouncedRefresh()
            }
        }

        contactObserver = observer

        try {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true, // notifyForDescendants
                observer
            )
            Log.d(TAG, "ContentObserver registered for ContactsContract changes")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot register ContentObserver without READ_CONTACTS permission", e)
        }
    }

    /**
     * Debounced contact cache refresh (500ms).
     * Coalesces rapid contact change notifications into a single reload.
     */
    private fun debouncedRefresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500L)
            appContext?.let { ctx ->
                withContext(Dispatchers.IO) {
                    loadAllContacts(ctx)
                }
                _cacheVersion.value++
            }
        }
    }
}
