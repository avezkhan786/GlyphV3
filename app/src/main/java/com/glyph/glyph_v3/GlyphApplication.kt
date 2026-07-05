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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        /**
         * True after Coil image loader has been initialized.
         * Coil is only needed for Compose UI, so we defer initialization until
         * the first Compose screen is displayed.
         */
        @Volatile
        private var isCoilInitialized = false
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

    // MEMORY LEAK FIX: Single application-scoped CoroutineScope replaces all
    // ad-hoc CoroutineScope(Dispatchers.X).launch {} call sites. Those created
    // new un-cancellable scopes every call — each captured `this` (Application)
    // and ran until completion, counting as a distinct allocation in the profiler
    // and a "leak" when the scope outlived the logical operation.
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            // ═══════════════════════════════════════════════════════════════════════
            // STARTUP OPTIMIZATION: Minimal Application.onCreate() using androidx.startup
            //
            // All heavy initialization has been moved to androidx.startup Initializers:
            // - FirebaseInitializer: RTDB persistence + connection warming
            // - PresenceInitializer: Presence tracking + auth listeners
            // - ThemeInitializer: Theme manager initialization
            // - ServicesInitializer: Cache managers, notifications, etc.
            //
            // This reduces Application.onCreate() blocking time from ~500-1000ms to <50ms.
            // ═══════════════════════════════════════════════════════════════════════

            StartupTrace.logStage("app_onCreate_start")

            // Initialize Firebase Firestore cache configuration
            // (This must happen before any Firestore usage)
            configureFirestoreCache()

            // Initialize shared data layer prewarming (lightweight)
            prewarmSharedDataLayerAsync(reason = "app_onCreate_early")

            // Schedule Firebase foreground warmup
            scheduleFirebaseForegroundWarmup(reason = "app_onCreate", force = true)

            // Preload chat wallpaper in background
            appScope.launch {
                runCatching {
                    com.glyph.glyph_v3.utils.ChatWallpaperManager.preload(this@GlyphApplication)
                }.onFailure { e ->
                    Log.w(TAG, "Failed to preload chat wallpaper", e)
                }
            }

            // Observe app lifecycle for foreground/background transitions
            ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())

            StartupTrace.logStage("app_onCreate_end")

        } catch (e: Exception) {
            Log.e(TAG, "Error during application initialization", e)
        }
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

        appScope.launch {
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

        appScope.launch {
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
                    appScope.launch {
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

        appScope.launch {
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

    /**
     * STARTUP OPTIMIZATION: Lazy Coil initialization for Compose UI.
     * Coil is only used in Compose screens, so we defer initialization until
     * the first Compose screen is displayed. This saves 100-200ms during cold start.
     *
     * Thread-safe: Uses double-checked locking to ensure Coil is only initialized once.
     * Can be called from any thread - will return immediately if already initialized.
     */
    fun ensureCoilInitialized() {
        if (isCoilInitialized) return

        synchronized(this) {
            if (isCoilInitialized) return

            try {
                StartupTrace.logStage("coil_init_start")

                // Initialize optimized Coil ImageLoader with custom configuration
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
                isCoilInitialized = true
                StartupTrace.logStage("coil_init_complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Coil image loader", e)
            }
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
                appScope.launch(Dispatchers.IO) {
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
