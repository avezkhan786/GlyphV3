package com.glyph.glyph_v3.startup

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import androidx.lifecycle.ProcessLifecycleOwner
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.util.StartupTrace
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.cache.MessagePreviewCacheManager
import com.glyph.glyph_v3.utils.MessageCacheManager
import com.glyph.glyph_v3.data.cache.StatusCacheManager
import com.glyph.glyph_v3.data.cache.StatusCacheCleanupWorker
import com.glyph.glyph_v3.data.cache.StatusThumbnailCache
import com.glyph.glyph_v3.data.repo.PrivacySettingsRepository
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.data.service.AppVisibilityTracker
import com.glyph.glyph_v3.data.service.CallForegroundService
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.service.NetworkConnectivityMonitor
import kotlinx.coroutines.launch

/**
 * Services initializer.
 *
 * Initializes background services, cache managers, and utility classes.
 * These are non-critical for app startup and can run in parallel with UI rendering.
 *
 * Dependencies: PresenceInitializer (runs after Firebase and presence are ready)
 */
class ServicesInitializer : Initializer<Unit> {

    companion object {
        private const val TAG = "ServicesInitializer"
    }

    override fun create(context: Context) {
        try {
            StartupTrace.logStage("services_init_start")

            // Initialize cache managers (lightweight operations)
            PrivacySettingsRepository.init(context)
            AvatarVisibilityRepository.init(context)
            AvatarCacheManager.init(context)
            MessagePreviewCacheManager.init(context)
            MessageCacheManager.init(context)
            StatusCacheManager.init(context)
            StatusThumbnailCache.init(context)

            // Initialize contact name resolver
            ContactDisplayNameResolver.init(context)

            // Initialize app visibility tracker (requires Application context)
            try {
                val appContext = context.applicationContext as Application
                AppVisibilityTracker.init(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize AppVisibilityTracker: ${e.message}")
            }

            // Schedule periodic cleanup of expired status cache files
            StatusCacheCleanupWorker.schedule(context)

            // Initialize block list listeners
            BlockRepository.startListening()

            // Initialize network monitor
            val networkMonitor = NetworkConnectivityMonitor(context)
            networkMonitor.startMonitoring()

            // Ensure call notification channels
            val app = context.applicationContext as? GlyphApplication
            app?.appScope?.launch {
                runCatching {
                    CallForegroundService.ensureNotificationChannels(context)
                }.onFailure { error ->
                    Log.w(TAG, "Failed to warm call notification channels", error)
                }
            }

            // Restore background sharing services
            restoreBackgroundSharingServices(context)

            // Initialize Google Sign-In for backup/restore (fire-and-forget)
            app?.appScope?.launch {
                runCatching {
                    com.glyph.glyph_v3.data.auth.GoogleSignInRepository.getInstance(context)
                        .silentSignIn()
                }.onFailure { e ->
                    Log.w(TAG, "Google Sign-In silent attempt failed (expected if no Google account)", e)
                }
            }

            StartupTrace.logStage("services_init_complete")
            Log.d(TAG, "Services initialization complete")
        } catch (e: Exception) {
            Log.e(TAG, "Services initialization failed", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        // Services depend on Presence being initialized first
        return listOf(PresenceInitializer::class.java)
    }

    /**
     * Restore persisted background live-sharing services after process death.
     */
    private fun restoreBackgroundSharingServices(context: Context) {
        runCatching {
            val activeSharing = com.glyph.glyph_v3.data.service.ActiveSharingRegistry
            activeSharing.init(context)
            when {
                activeSharing.hasAnyLiveTargets() -> {
                    com.glyph.glyph_v3.data.service.LocationUpdateService.startLive(context, 1L)
                }
                activeSharing.hasAnyTargets() -> {
                    com.glyph.glyph_v3.data.service.LocationUpdateService.startPassive(context)
                }
            }
            com.glyph.glyph_v3.data.service.MapCameraShareForegroundService.ensureRunning(context)
        }.onFailure { e ->
            Log.w(TAG, "Failed to restore background sharing services", e)
        }
    }
}
