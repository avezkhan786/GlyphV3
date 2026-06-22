package com.glyph.glyph_v3

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.data.auth.GoogleSignInRepository
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.data.cache.StatusCacheCleanupWorker
import com.glyph.glyph_v3.data.cache.StatusCacheManager
import com.glyph.glyph_v3.data.cache.StatusThumbnailCache
import com.glyph.glyph_v3.data.repo.CallSignalingRepository
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.repo.PrivacySettingsRepository
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.repo.GroupChatRepository
import com.glyph.glyph_v3.ui.chatlist.ChatListViewModel
import com.glyph.glyph_v3.data.service.AppVisibilityTracker
import com.glyph.glyph_v3.data.service.ActiveSharingRegistry
import com.glyph.glyph_v3.data.service.LocationUpdateService
import com.glyph.glyph_v3.data.service.CallForegroundService
import com.glyph.glyph_v3.data.service.MapCameraShareForegroundService
import com.glyph.glyph_v3.data.service.NetworkConnectivityMonitor
import com.glyph.glyph_v3.data.service.WalkieTalkieManager
import com.glyph.glyph_v3.ui.chat.ChatOpenPrefetcher
import com.glyph.glyph_v3.util.StartupTrace
import com.glyph.glyph_v3.utils.MessageCacheManager
import com.glyph.glyph_v3.utils.ThemeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.appcheck.FirebaseAppCheck
// DebugAppCheckProviderFactory is only available in debug builds
// Import conditionally via fully qualified name in debug block below
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import coil.ImageLoader
import coil.Coil
import coil.util.DebugLogger
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


/**
 * Application class for Glyph.
 * Handles app-wide initialization and lifecycle events for presence tracking.
 */
class GlyphApplication : Application() {

    companion object {
        private const val TAG = "GlyphApplication"
        private const val AUTH_PREFS_NAME = "auth_session_state"
        private const val KEY_LAST_AUTH_UID = "last_auth_uid"

        private const val WAKEUP_CHANNEL_ID = "glyph_wakeup_channel"
        private const val STARTUP_CHAT_PREFETCH_LIMIT = 4

        /**
         * True after the first Activity creation in this process.
         * Used by SplashActivity to apply the branded window background only on a cold start.
         */
        @JvmField
        var splashShown: Boolean = false
    }

    private var incomingSyncRef: DatabaseReference? = null
    @Volatile
    private var appDatabase: AppDatabase? = null
    var repository: RealtimeMessageRepository? = null
        private set
    var groupChatRepository: com.glyph.glyph_v3.data.repo.GroupChatRepository? = null
        private set
    private var lastFirebaseForegroundWarmupAtMs: Long = 0L
    private val sharedDataLayerPrewarmInFlight = AtomicBoolean(false)
    private val sharedRepositoryStartupInFlight = AtomicBoolean(false)
    private val sharedRepositoryStartupComplete = AtomicBoolean(false)
        
    private lateinit var networkMonitor: NetworkConnectivityMonitor

    override fun onCreate() {
        super.onCreate()
        StartupTrace.logStage("app_onCreate_start")

        // ── Firebase DefaultRunLoop crash guard ──────────────────────────────────
        // Firebase RTDB uses an internal ScheduledThreadPoolExecutor ("DefaultRunLoop").
        // If that pool is ever shut down (via goOffline/internal reset) while an old
        // TubeSockReader WebSocket thread is still alive, the thread's onError() handler
        // tries to schedule a reconnect task onto the terminated pool and throws a
        // RejectedExecutionException that is NOT caught inside the Firebase SDK — it
        // propagates as an uncaught exception and kills the process.
        //
        // Fix: intercept that specific exception on Firebase-owned threads, trigger a
        // goOnline() to re-establish the connection, and swallow the crash.
        // All other exceptions are forwarded to Android's default crash handler.
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val isFirebaseRtdbThread = thread.name.let { name ->
                name.startsWith("TubeSockReader") ||
                name.startsWith("FirebaseDatabaseWorker") ||
                name.startsWith("Firebase")
            }
            val isRejectedFromTerminatedPool = throwable is java.util.concurrent.RejectedExecutionException &&
                throwable.message?.contains("Terminated") == true
            if (isFirebaseRtdbThread && isRejectedFromTerminatedPool) {
                Log.w(TAG, "Suppressed Firebase RTDB DefaultRunLoop RejectedExecutionException " +
                    "on thread '${thread.name}' — triggering goOnline() recovery", throwable)
                // Attempt to revive the RTDB connection on the next available main-thread slot.
                try { FirebaseDatabase.getInstance().goOnline() } catch (_: Exception) {}
                return@setDefaultUncaughtExceptionHandler
            }
            systemHandler?.uncaughtException(thread, throwable)
        }
        // ────────────────────────────────────────────────────────────────────────

        try {
            // ============================================================
            // COLD-START FIX: Enable RTDB disk persistence BEFORE the very
            // first RTDB operation. This lets the SDK persist the auth token
            // and in-flight writes to disk so that:
            //   (a) cold starts don't need a full TLS + auth re-handshake, and
            //   (b) outgoing writes queued while offline survive process restart.
            // Must be called exactly once, before ANY FirebaseDatabase usage.
            // ============================================================
            try {
                FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            } catch (_: Exception) {
                // Thrown only if called after the first RTDB operation (e.g. on
                // hot reload in dev builds). Safe to ignore in production.
            }

            // ============================================================
            // COLD-START FIX: Warm Firebase RTDB connection IMMEDIATELY.
            // Firebase RTDB uses a persistent WebSocket. On cold start (or
            // after long idle), establishing this connection takes 3-15 s
            // (TLS handshake + auth). By calling goOnline() here — before
            // any UI work — the connection starts in parallel with image
            // loader setup, theme init, etc., so it is ready (or nearly
            // ready) by the time Activities need presence / messages.
            // ============================================================
            warmFirebaseConnection()
            PresenceManager.initContext(this)
            // Debug AppCheck is only available in debug builds
            // Commented out for release builds - not needed for production
            // if (BuildConfig.DEBUG) {
            //     FirebaseAppCheck.getInstance()
            //         .installAppCheckProviderFactory(
            //             com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory.getInstance()
            //         )
            // }
            initializePresence()
            prewarmSharedDataLayerAsync(reason = "app_onCreate_early")
            scheduleFirebaseForegroundWarmup(reason = "app_onCreate", force = true)
            configureFirestoreCache()

            // CRITICAL: Initialize optimized Coil ImageLoader FIRST
            // This prevents cold start jank by pre-configuring image loading
            initializeImageLoader()

            // Configure Glide (used for chat RecyclerView) with expanded 512MB disk cache
            // so decoded resources persist across cold starts and force-stops
            Glide.init(
                this,
                com.bumptech.glide.GlideBuilder()
                    .setDiskCache(
                        com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory(
                            this, 512L * 1024L * 1024L
                        )
                    )
            )
            
            createNotificationChannels()
            MainScope().launch(Dispatchers.IO) {
                runCatching {
                    CallForegroundService.ensureNotificationChannels(this@GlyphApplication)
                }.onFailure { error ->
                    Log.w(TAG, "Failed to warm call notification channels", error)
                }
            }

            // Initialize theme settings globally (sets AppCompatDelegate night mode)
            ThemeManager.init(this)
            PrivacySettingsRepository.init(this)
            AvatarVisibilityRepository.init(this)
            
            // Initialize avatar cache manager for instant profile picture loading
            AvatarCacheManager.init(this)
            MessagePreviewCacheManager.init(this)
            MessageCacheManager.init(this)
            StatusCacheManager.init(this)
            StatusThumbnailCache.init(this)
            completeSharedRepositoryStartupAsync(reason = "app_onCreate", warmStartupChats = true)

            // Schedule periodic cleanup of expired status cache files
            StatusCacheCleanupWorker.schedule(this)

            // Preload chat wallpaper off the main thread so chat open can apply an
            // already decoded bitmap instead of waiting for the first Glide decode.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                com.glyph.glyph_v3.utils.ChatWallpaperManager.preload(this@GlyphApplication)
            }

            // Initialize contact name resolver for WhatsApp-style display names
            ContactDisplayNameResolver.init(this)

            // Track whether the app has a visible Activity (used for notification alerting behavior)
            AppVisibilityTracker.init(this)

            // Restore persisted background live-sharing services after process death.
            restoreBackgroundSharingServices()
            
            // Initialize Google Sign-In for backup/restore (fire-and-forget, best-effort)
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    GoogleSignInRepository.getInstance(this@GlyphApplication).silentSignIn()
                }.onFailure { e ->
                    Log.w(TAG, "Google Sign-In silent attempt failed (expected if no Google account)", e)
                }
            }

            // Initialize block list listeners (must be after auth is available)
            com.glyph.glyph_v3.data.repo.BlockRepository.startListening()
            
            // Initialize Network Monitor
            networkMonitor = NetworkConnectivityMonitor(this)
            networkMonitor.startMonitoring()
            
            // Observe app lifecycle for foreground/background transitions
            ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())
        } catch (e: Exception) {
            Log.e(TAG, "Error during application initialization", e)
        }
        StartupTrace.logStage("app_onCreate_end")
    }

    fun getOrCreateAppDatabase(): AppDatabase {
        appDatabase?.let { return it }
        return synchronized(this) {
            appDatabase ?: AppDatabase.getDatabase(this).also { db ->
                appDatabase = db
            }
        }
    }

    fun getOrCreateRealtimeRepository(): RealtimeMessageRepository {
        repository?.let { return it }
        return synchronized(this) {
            repository ?: run {
                val db = getOrCreateAppDatabase()
                RealtimeMessageRepository(
                    db.messageDao(),
                    db.chatDao(),
                    db.deletedMessageDao(),
                    this
                ).also { repo ->
                    repository = repo
                }
            }
        }
    }

    /**
     * Singleton accessor for the group chat repository. Strictly additive to
     * [RealtimeMessageRepository] — the 1:1 stack does not depend on it.
     */
    fun getOrCreateGroupChatRepository(): com.glyph.glyph_v3.data.repo.GroupChatRepository {
        groupChatRepository?.let { return it }
        return synchronized(this) {
            groupChatRepository ?: run {
                val db = getOrCreateAppDatabase()
                com.glyph.glyph_v3.data.repo.GroupChatRepository(
                    db.messageDao(),
                    db.chatDao(),
                    this
                ).also { repo -> groupChatRepository = repo }
            }
        }
    }

    fun ensureSharedRepositoryStartup(reason: String, warmStartupChats: Boolean = false) {
        completeSharedRepositoryStartupAsync(reason = reason, warmStartupChats = warmStartupChats)
    }

    private fun prewarmSharedDataLayerAsync(reason: String) {
        if (appDatabase != null && repository != null) {
            return
        }
        if (!sharedDataLayerPrewarmInFlight.compareAndSet(false, true)) {
            return
        }

        MainScope().launch(Dispatchers.IO) {
            try {
                getOrCreateAppDatabase()
                getOrCreateRealtimeRepository()
                StartupTrace.logStage("repository_prewarm_ready", "reason=$reason")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prewarm shared data layer", e)
            } finally {
                sharedDataLayerPrewarmInFlight.set(false)
            }
        }
    }

    private fun completeSharedRepositoryStartupAsync(reason: String, warmStartupChats: Boolean) {
        if (sharedRepositoryStartupComplete.get()) {
            return
        }
        if (!sharedRepositoryStartupInFlight.compareAndSet(false, true)) {
            return
        }

        MainScope().launch(Dispatchers.IO) {
            try {
                val db = getOrCreateAppDatabase()
                val repo = getOrCreateRealtimeRepository()
                StartupTrace.logStage("repository_ready", "reason=$reason")
                startIncomingSyncIfLoggedIn()
                repo.startGlobalDeliveryReceiptSync(forceRestart = true)
                repo.startGroupMetadataSync(forceRestart = true)
                PrivacySettingsRepository.warmCacheIfNeeded()
                if (warmStartupChats) {
                    warmStartupChatSnapshots(db, repo)
                }
                sharedRepositoryStartupComplete.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete shared repository startup", e)
            } finally {
                sharedRepositoryStartupInFlight.set(false)
            }
        }
    }

    private fun restoreBackgroundSharingServices() {
        runCatching {
            ActiveSharingRegistry.init(this)
            when {
                ActiveSharingRegistry.hasAnyLiveTargets() -> LocationUpdateService.startLive(this, 1L)
                ActiveSharingRegistry.hasAnyTargets() -> LocationUpdateService.startPassive(this)
            }
            MapCameraShareForegroundService.ensureRunning(this)
        }.onFailure { e ->
            Log.w(TAG, "Failed to restore background sharing services", e)
        }
    }

    private suspend fun warmStartupChatSnapshots(
        db: AppDatabase,
        repo: RealtimeMessageRepository
    ) {
        runCatching {
            val topChats = db.chatDao().getTopActiveChats(STARTUP_CHAT_PREFETCH_LIMIT)
            val topChatIds = topChats
                .map { it.id }
                .filter { it.isNotBlank() }
            if (topChatIds.isEmpty()) return

            // Pre-seed the ChatListViewModel process-level cache so the first
            // ViewModel starts with isInitialLoading=false and chats visible,
            // eliminating the shimmer-placeholder step on cold start.
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
            val uiChats: List<Chat> = topChats.map { local ->
                val effectiveIsGroup = local.isGroup || GroupChatRepository.isGroupChatId(local.id)
                Chat(
                    id = local.id,
                    participants = if (effectiveIsGroup) {
                        GroupChatRepository.decodeStringList(local.participantsJson)
                    } else {
                        listOf(currentUid, local.otherUserId)
                    },
                    lastMessage = local.lastMessage,
                    lastMessageTimestamp = if (local.lastMessageTimestamp > 0) java.util.Date(local.lastMessageTimestamp) else null,
                    lastMessageSenderId = local.lastMessageSenderId,
                    lastMessageStatus = local.lastMessageStatus,
                    unreadCount = local.unreadCount,
                    otherUsername = local.otherUsername,
                    otherUserAvatar = local.otherUserAvatar,
                    isGroup = effectiveIsGroup,
                    groupName = local.groupName,
                    groupIconUrl = local.groupIconUrl,
                    groupDescription = local.groupDescription,
                    createdBy = local.createdBy,
                    createdAt = local.createdAt
                )
            }
            ChatListViewModel.prewarmCache("main", uiChats)

            val avatarsToWarm = topChats.mapNotNull { chat ->
                val avatarUrl = chat.otherUserAvatar.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                chat.otherUserId to avatarUrl
            }

            StartupTrace.logStage(
                "startup_chat_prefetch_scheduled",
                "count=${topChatIds.size} avatars=${avatarsToWarm.size}"
            )

            if (avatarsToWarm.isNotEmpty()) {
                val visibleAvatarsToWarm = avatarsToWarm.filter { (userId, _) ->
                    AvatarVisibilityRepository.getCachedProfilePhotoVisibility(userId)?.isVisible == true
                }
                if (visibleAvatarsToWarm.isNotEmpty()) {
                    AvatarCacheManager.preloadAvatars(visibleAvatarsToWarm, this@GlyphApplication)
                }

                avatarsToWarm.forEach { (userId, _) ->
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        runCatching { AvatarVisibilityRepository.refreshProfilePhotoVisibility(userId) }
                    }
                }
            }

            ChatOpenPrefetcher.warmChatsAsync(
                context = this@GlyphApplication,
                repository = repo,
                chatIds = topChatIds,
                source = "app_cold_start"
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to warm startup chat snapshots", e)
        }
    }

    /**
     * COLD-START FIX: Proactively warm the Firebase Realtime Database connection
     * and refresh the auth token so that presence, messages, and delivery receipts
     * are ready by the time the first Activity appears.
     *
     * Without this, the RTDB WebSocket is lazily created on the first database
     * read/write, adding 5-15 seconds of latency on cold start or after long idle.
     *
     * What this does:
     * 1. Forces RTDB to open its WebSocket NOW (goOnline is a no-cost call if
     *    already connected; on cold start it begins the TLS + auth handshake).
     * 2. Proactively refreshes the Firebase Auth ID token. After long idle the
     *    token may be expired; refreshing it here prevents RTDB operations from
     *    blocking on a token refresh later.
     * 3. Pre-syncs the presence root so the local cache is warm before any
     *    Activity subscribes to presence.
     */
    private fun warmFirebaseConnection() {
        try {
            val rtdb = FirebaseDatabase.getInstance()
            StartupTrace.logStage("rtdb_warmup_start")

            // 1. Force the WebSocket open immediately
            rtdb.goOnline()

            // 2. Pre-warm presence path cache so first reads don't wait for server
            rtdb.getReference("presence").keepSynced(true)
            rtdb.getReference("walkieTalkieSessions").keepSynced(true)

            // 3. Refresh auth token proactively (runs async, won't block main thread)
            FirebaseAuth.getInstance().currentUser?.getIdToken(false)
                ?.addOnSuccessListener {
                }
                ?.addOnFailureListener { e ->
                    Log.w(TAG, "Proactive token refresh failed (will retry on demand)", e)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Error warming Firebase connection", e)
        }
    }

    private fun configureFirestoreCache() {
        try {
            val firestore = FirebaseFirestore.getInstance()
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(
                    PersistentCacheSettings
                        .newBuilder()
                        .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                        .build()
                )
                .build()
            firestore.firestoreSettings = settings
            StartupTrace.logStage("firestore_cache_configured")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure Firestore cache", e)
        }
    }

    private fun scheduleFirebaseForegroundWarmup(reason: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val hasPreviousWarmup = lastFirebaseForegroundWarmupAtMs > 0L
        val idleGapMs = if (hasPreviousWarmup) {
            now - lastFirebaseForegroundWarmupAtMs
        } else {
            0L
        }
        if (!force && idleGapMs < 10_000L) {
            return
        }
        lastFirebaseForegroundWarmupAtMs = now

        MainScope().launch(Dispatchers.IO) {
            try {
                val auth = FirebaseAuth.getInstance()
                val user = auth.currentUser

                StartupTrace.logStage(
                    "firebase_transport_warmup_start",
                    "reason=$reason uid=${user?.uid ?: "none"}"
                )

                val rtdb = FirebaseDatabase.getInstance()
                val shouldRestartRealtimeListeners =
                    force || (hasPreviousWarmup && (!PresenceManager.isConnected.value || idleGapMs > 60_000L))
                val shouldForceTokenRefresh = force || (hasPreviousWarmup && idleGapMs > 60_000L)
                // NOTE: goOffline/goOnline cycling was removed. Calling goOffline() then goOnline()
                // causes Firebase to reissue all registered listens, which can trigger
                // "listen() called twice for same QuerySpec" if any removeEventListener round-trip
                // is still in flight. goOnline() alone is sufficient to wake a dormant connection.
                rtdb.goOnline()
                // Note: .info/connected is an internal Firebase path that does not support keepSynced()
                rtdb.getReference("presence").keepSynced(true)
                rtdb.getReference("pending_messages").keepSynced(true)
                PresenceManager.primeTransport(reason, shouldForceTokenRefresh)
                WalkieTalkieManager.getInstance(this@GlyphApplication)
                    .primeTransport(reason, shouldForceTokenRefresh)

                if (user != null) {
                    CallSignalingRepository.primeTransport(reason)
                    startIncomingSyncIfLoggedIn(forceRestart = shouldRestartRealtimeListeners)
                    repository?.primeRealtimeTransportForForeground(reason)
                    repository?.restartGlobalDeliveryReceiptSync()

                    runCatching {
                        user.getIdToken(shouldForceTokenRefresh).await()
                        StartupTrace.logStage(
                            "firebase_transport_warmup_auth_ready",
                            "reason=$reason uid=${user.uid} hardReconnect=$shouldRestartRealtimeListeners forceTokenRefresh=$shouldForceTokenRefresh"
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Foreground Firebase auth warm-up failed", error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to warm Firebase transport for foreground", e)
            }
        }
    }

    private fun persistLastKnownAuthUid(userId: String?) {
        val prefs = getSharedPreferences(AUTH_PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            if (userId.isNullOrBlank()) {
                remove(KEY_LAST_AUTH_UID)
            } else {
                putString(KEY_LAST_AUTH_UID, userId)
            }
        }.apply()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return

        // Wakeup channel: used only for pre-wake pings from FCM.
        // Must be IMPORTANCE_MIN and silent so it doesn't alert the user.
        val wakeupChannel = NotificationChannel(
            WAKEUP_CHANNEL_ID,
            "Background Wakeup",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Internal channel used to wake device for chat delivery"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
        }
        notificationManager.createNotificationChannel(wakeupChannel)

        // Live Audio Sharing channel
        val liveAudioChannel = NotificationChannel(
            "glyph_live_audio_sharing",
            "Live Audio Sharing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when you are sharing live audio"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(liveAudioChannel)

        // Status upload progress channel
        val uploadChannel = NotificationChannel(
            "glyph_status_upload",
            "Status Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows upload progress for status updates"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(uploadChannel)

        // Status update notifications channel (user opt-in per contact)
        com.glyph.glyph_v3.data.service.StatusUpdateNotificationHelper.ensureChannel(this)
    }
    
    /**
     * Initialize optimized Coil ImageLoader for best performance.
     * CRITICAL optimizations:
     * - Hardware bitmaps for GPU rendering (reduces CPU load)
     * - Increased memory cache for smooth scrolling
     * - Optimized disk cache
     * - Connection pooling for faster network loads
     */
    private fun initializeImageLoader() {
        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.35) // Increased from 25% to 35% - prevent eviction during scroll
                    .strongReferencesEnabled(true) // Keep strong references to prevent GC
                    .weakReferencesEnabled(false) // Disable weak refs - only strong refs for stability
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(500L * 1024 * 1024) // Increased from 250MB to 500MB
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
                    .build()
            }
            // Video frame decoding (first frame as thumbnail) + animated GIF/WebP decoding
            .components {
                add(VideoFrameDecoder.Factory())
                add(ImageDecoderDecoder.Factory())
            }
            // CRITICAL: Enable hardware bitmaps for GPU rendering
            .allowHardware(true)
            .crossfade(false) // Disable crossfade globally for performance
            .respectCacheHeaders(false) // Ignore server cache headers for better caching
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
            
        Coil.setImageLoader(imageLoader)
    }

    private fun initializePresence() {
        try {
            // Only initialize if user is logged in
            if (FirebaseAuth.getInstance().currentUser != null) {
                persistLastKnownAuthUid(FirebaseAuth.getInstance().currentUser?.uid)
                PresenceManager.initialize()
            }
            
            // Listen for auth state changes
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                try {
                    if (auth.currentUser != null) {
                        StartupTrace.logStage("auth_state_ready", "uid=${auth.currentUser?.uid}")
                        persistLastKnownAuthUid(auth.currentUser?.uid)
                        PresenceManager.initialize()
                        scheduleFirebaseForegroundWarmup(reason = "auth_state_ready", force = true)
                        if (AppVisibilityTracker.isAppVisible) {
                            PresenceManager.goOnline()
                        }
                        startIncomingSyncIfLoggedIn(forceRestart = true)
                        repository?.restartGlobalDeliveryReceiptSync()
                    } else {
                        StartupTrace.logStage("auth_state_cleared")
                        persistLastKnownAuthUid(null)
                        PresenceManager.cleanup()
                        repository?.stopIncomingMessageSync()
                        repository?.stopGlobalDeliveryReceiptSync()
                        incomingSyncRef = null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in auth state listener", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing presence", e)
        }
    }

    /**
     * Lifecycle observer to handle app foreground/background transitions.
     * 
     * CRITICAL: ProcessLifecycleOwner.onStart() triggers even when FCM wakes the app
     * for background notification processing, causing false "online" status.
     * 
     * SOLUTION: Only go online when there's an actual visible activity (PresenceManager
     * tracks this internally). The goOnline() call is now handled by individual activities
     * when they become visible, not by process lifecycle.
     */
    private inner class AppLifecycleObserver : DefaultLifecycleObserver {
        
        override fun onStart(owner: LifecycleOwner) {
            // App process started - start message sync but DON'T go online
            // (Activities will handle presence when actually visible)
            try {
                if (FirebaseAuth.getInstance().currentUser != null) {
                    ensureSharedRepositoryStartup(reason = "process_onStart", warmStartupChats = false)
                    scheduleFirebaseForegroundWarmup(reason = "process_onStart")
                    // Only start message sync, NOT presence
                    startIncomingSyncIfLoggedIn(forceRestart = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onStart", e)
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            // App went to background - go offline
            try {
                if (FirebaseAuth.getInstance().currentUser != null) {
                    PresenceManager.goOffline()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onStop", e)
            }
        }
    }

    private fun startIncomingSyncIfLoggedIn(forceRestart: Boolean = false) {
        try {
            val authUser = FirebaseAuth.getInstance().currentUser ?: return
            if (!forceRestart && incomingSyncRef != null) return // already running
            val repo = repository ?: run {
                completeSharedRepositoryStartupAsync(
                    reason = "incoming_sync_missing_repo",
                    warmStartupChats = false
                )
                return
            }
            incomingSyncRef = if (forceRestart) {
                repo.restartIncomingMessageSync()
            } else {
                repo.startIncomingMessageSync()
            }
            if (incomingSyncRef != null) {
                StartupTrace.logStage("incoming_sync_started", "uid=${authUser.uid}")
                repo.primeRealtimeTransportForForeground("incoming_sync_start")
            } else {
                // Keep a light retry loop for transient Firebase auth/readiness races.
                MainScope().launch(Dispatchers.IO) {
                    repeat(6) {
                        kotlinx.coroutines.delay(500)
                        if (!forceRestart && incomingSyncRef != null) return@launch // another path started it
                        val liveRepo = repository ?: return@repeat
                        val ref = if (forceRestart) {
                            liveRepo.restartIncomingMessageSync()
                        } else {
                            liveRepo.startIncomingMessageSync()
                        }
                        if (ref != null) {
                            incomingSyncRef = ref
                            StartupTrace.logStage("incoming_sync_started_deferred", "uid=${authUser.uid}")
                            liveRepo.primeRealtimeTransportForForeground("incoming_sync_start_deferred")
                            return@launch
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start incoming sync", e)
        }
    }
}
