package com.glyph.glyph_v3.data.cache

import android.content.Context
import android.util.Log
import com.glyph.glyph_v3.data.repo.BlockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single global source-of-truth for user avatars.
 *
 * Every screen that displays a user avatar reads from this manager instead of
 * querying [AvatarCacheManager] or Glide independently.  This ensures:
 * - One download per user (no duplicate fetches)
 * - Instant propagation: when a block is lifted, a single refresh updates every
 *   visible avatar everywhere (chat list, chat header, contact info, forward).
 * - Pre-rendering: the local path is available synchronously from the first read.
 */
object AvatarStateManager {
    private const val TAG = "AvatarStateManager"

    data class AvatarState(
        val localPath: String?,
        val remoteUrl: String,
        val isDownloaded: Boolean,
        val version: Long  // increments on every download, forces UI recomposition
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = mutableMapOf<String, MutableStateFlow<AvatarState>>()
    private val lock = Any()
    private var previouslyBlocked: Set<String> = emptySet()

    /** Observe block-status changes: when a user is unblocked, force-refresh. */
    init {
        scope.launch {
            BlockRepository.myBlockedUsers.collect { blockedSet ->
                // Detect users that were blocked and now are not (just unblocked).
                val newlyUnblocked = previouslyBlocked - blockedSet
                previouslyBlocked = blockedSet
                // Re-download avatars for any user who left the blocked set and
                // whom we know about (has a remote URL on record).
                for (userId in newlyUnblocked) {
                    val url = synchronized(lock) { states[userId]?.value?.remoteUrl } ?: ""
                    if (url.isNotBlank()) {
                        refresh(userId, url)
                    }
                }
            }
        }
    }

    /**
     * Get or create the avatar state for [userId].  Call once per screen
     * (or once per Composable) to observe the latest state.
     */
    fun observe(userId: String, remoteUrl: String): StateFlow<AvatarState> {
        synchronized(lock) {
            val existing = states[userId]
            if (existing != null) {
                // Update remote URL if it changed (e.g. user changed photo).
                if (existing.value.remoteUrl != remoteUrl && remoteUrl.isNotBlank()) {
                    refresh(userId, remoteUrl)
                }
                return existing.asStateFlow()
            }
            // First access: check disk and build initial state.
            val localPath = AvatarCacheManager.getLocalAvatarPath(userId)
            val flow = MutableStateFlow(AvatarState(
                localPath = localPath,
                remoteUrl = remoteUrl,
                isDownloaded = localPath != null,
                version = 0L
            ))
            states[userId] = flow
            // If not on disk and we have a URL, download now.
            if (localPath == null && remoteUrl.isNotBlank()) {
                refresh(userId, remoteUrl)
            }
            return flow.asStateFlow()
        }
    }

    /**
     * Synchronously get the current state without creating a subscription.
     * Use for one-shot reads (e.g. share intent, notification).
     */
    fun peek(userId: String): AvatarState? = synchronized(lock) {
        states[userId]?.value
    }

    /**
     * Force re-download the avatar for [userId] and update the state.
     * Safe to call from any thread.
     */
    fun refresh(userId: String, remoteUrl: String) {
        if (remoteUrl.isBlank()) {
            Log.d(TAG, "refresh: SKIPPED (blank url) for $userId")
            return
        }
        scope.launch {
            try {
                // Force-download via cacheAvatar (not updateAvatarIfNeeded, which
                // may skip based on stale metadata).  This guarantees the file is
                // re-created after a block cleared it.
                val downloaded = withContext(Dispatchers.IO) {
                    AvatarCacheManager.cacheAvatar(userId, remoteUrl, appContext!!)
                }
                val localPath = AvatarCacheManager.getLocalAvatarPath(userId)
                Log.d(TAG, "refresh: userId=$userId downloaded=$downloaded localPath=$localPath url=${remoteUrl.take(70)}")
                synchronized(lock) {
                    val flow = states[userId]
                    if (flow != null) {
                        val old = flow.value
                        flow.value = old.copy(
                            localPath = localPath,
                            isDownloaded = downloaded,
                            version = old.version + 1  // bump regardless, forces recomposition
                        )
                    } else {
                        states[userId] = MutableStateFlow(AvatarState(
                            localPath = localPath,
                            remoteUrl = remoteUrl,
                            isDownloaded = downloaded,
                            version = 1L
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh avatar for $userId", e)
            }
        }
    }

    /**
     * Called on block.  Does NOT delete the local avatar file — it is simply
     * hidden by the block-status gate (`canShowAvatar = !isBlocked`) in every
     * screen.  Deleting it forced an unreliable Firebase re-download on unblock
     * (which fails for stale/expired URLs), leaving the avatar permanently
     * missing.  Keeping the file makes unblock instant and 100% reliable.
     */
    fun invalidate(userId: String) {
        synchronized(lock) {
            val flow = states[userId]
            if (flow != null) {
                val old = flow.value
                flow.value = old.copy(version = old.version + 1)
            }
        }
    }

    /** Must be called once at app start (from [com.glyph.glyph_v3.GlyphApplication]). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @Volatile
    private var appContext: Context? = null
}
