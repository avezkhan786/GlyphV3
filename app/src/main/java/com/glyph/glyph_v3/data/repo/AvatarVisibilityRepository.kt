package com.glyph.glyph_v3.data.repo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class ProfilePhotoVisibilityState(
    val isResolved: Boolean = false,
    val isVisible: Boolean = false
)

object AvatarVisibilityRepository {

    private const val TAG = "AvatarVisibilityRepo"
    private const val PREFS_NAME = "avatar_visibility_cache"
    private const val KEY_RESOLVED_PREFIX = "resolved:"
    private const val KEY_VISIBLE_PREFIX = "visible:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visibilityStates = ConcurrentHashMap<String, MutableStateFlow<ProfilePhotoVisibilityState>>()
    private val observationJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun currentViewerId(): String? = FirebaseAuth.getInstance().currentUser?.uid

    private fun stateKey(ownerUserId: String): String = "${currentViewerId().orEmpty()}|$ownerUserId"

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun observeProfilePhotoVisibility(userId: String): StateFlow<ProfilePhotoVisibilityState> {
        if (userId.isBlank()) {
            return MutableStateFlow(ProfilePhotoVisibilityState(isResolved = true, isVisible = false)).asStateFlow()
        }

        val viewerId = currentViewerId()
        val key = stateKey(userId)
        val stateFlow = visibilityStates.getOrPut(key) {
            MutableStateFlow(initialState(ownerUserId = userId, viewerUserId = viewerId))
        }

        ensureObservation(
            ownerUserId = userId,
            viewerUserId = viewerId,
            key = key,
            stateFlow = stateFlow
        )

        return stateFlow.asStateFlow()
    }

    fun getCachedProfilePhotoVisibility(userId: String): ProfilePhotoVisibilityState? {
        if (userId.isBlank()) return null
        val viewerId = currentViewerId()
        val key = stateKey(userId)
        return visibilityStates.getOrPut(key) {
            MutableStateFlow(initialState(ownerUserId = userId, viewerUserId = viewerId))
        }.value
    }

    suspend fun refreshProfilePhotoVisibility(userId: String): ProfilePhotoVisibilityState {
        if (userId.isBlank()) {
            return ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
        }

        val viewerId = currentViewerId()
        val key = stateKey(userId)
        val stateFlow = visibilityStates.getOrPut(key) {
            MutableStateFlow(initialState(ownerUserId = userId, viewerUserId = viewerId))
        }

        val resolved = resolveProfilePhotoVisibility(ownerUserId = userId, viewerUserId = viewerId)
        stateFlow.value = resolved
        persistVisibilityState(key, resolved)
        if (!resolved.isVisible) {
            AvatarCacheManager.clearAvatarCache(userId)
        }
        ensureObservation(userId, viewerId, key, stateFlow)
        return resolved
    }

    private fun initialState(ownerUserId: String, viewerUserId: String?): ProfilePhotoVisibilityState {
        if (ownerUserId.isNotBlank() && ownerUserId == viewerUserId) {
            return ProfilePhotoVisibilityState(isResolved = true, isVisible = true)
        }

        resolveCachedProfilePhotoVisibility(ownerUserId, viewerUserId)?.let { return it }
        readPersistedVisibilityState(stateKey(ownerUserId))?.let { return it }
        return ProfilePhotoVisibilityState()
    }

    private fun resolveCachedProfilePhotoVisibility(
        ownerUserId: String,
        viewerUserId: String?
    ): ProfilePhotoVisibilityState? {
        if (ownerUserId.isBlank()) {
            return ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
        }

        val viewerId = viewerUserId ?: return ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
        if (BlockRepository.isInteractionBlocked(ownerUserId)) {
            return ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
        }

        val cachedSettings = PrivacySettingsRepository.getCachedPrivacySettingsForUser(ownerUserId) ?: return null
        val cachedVisibility = PrivacySettingsRepository.canViewerSeeCached(
            ownerUserId = ownerUserId,
            viewerUid = viewerId,
            visibility = cachedSettings.profilePhotoVisibility
        ) ?: return null

        return ProfilePhotoVisibilityState(isResolved = true, isVisible = cachedVisibility)
    }

    private fun readPersistedVisibilityState(key: String): ProfilePhotoVisibilityState? {
        val prefs = prefs() ?: return null
        if (!prefs.contains(KEY_RESOLVED_PREFIX + key)) return null
        val isResolved = prefs.getBoolean(KEY_RESOLVED_PREFIX + key, false)
        val isVisible = prefs.getBoolean(KEY_VISIBLE_PREFIX + key, false)
        return ProfilePhotoVisibilityState(isResolved = isResolved, isVisible = isVisible)
    }

    private fun persistVisibilityState(key: String, state: ProfilePhotoVisibilityState) {
        prefs()?.edit()
            ?.putBoolean(KEY_RESOLVED_PREFIX + key, state.isResolved)
            ?.putBoolean(KEY_VISIBLE_PREFIX + key, state.isVisible)
            ?.apply()
    }

    private fun ensureObservation(
        ownerUserId: String,
        viewerUserId: String?,
        key: String,
        stateFlow: MutableStateFlow<ProfilePhotoVisibilityState>
    ) {
        val existing = observationJobs[key]
        if (existing?.isActive == true) return

        observationJobs[key] = scope.launch {
            runCatching {
                stateFlow.value = resolveProfilePhotoVisibility(ownerUserId, viewerUserId)
            }.onFailure { error ->
                Log.w(TAG, "Failed to resolve initial profile photo visibility for $ownerUserId", error)
            }

            combine(
                PrivacySettingsRepository.privacySettingsFlowForUser(ownerUserId),
                combine(BlockRepository.myBlockedUsers, BlockRepository.blockedByUsers) { myBlockedUsers, blockedByUsers ->
                    ownerUserId in myBlockedUsers || ownerUserId in blockedByUsers
                }
            ) { settings, isBlocked ->
                settings to isBlocked
            }.collect { (settings, isBlocked) ->
                val nextState = runCatching {
                    resolveProfilePhotoVisibility(ownerUserId, viewerUserId, settings, isBlocked)
                }.getOrElse { error ->
                    Log.w(TAG, "Failed to resolve profile photo visibility update for $ownerUserId", error)
                    ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
                }

                if (nextState != stateFlow.value) {
                    stateFlow.value = nextState
                    persistVisibilityState(key, nextState)
                    if (!nextState.isVisible) {
                        AvatarCacheManager.clearAvatarCache(ownerUserId)
                    }
                }
            }
        }
    }

    private suspend fun resolveProfilePhotoVisibility(
        ownerUserId: String,
        viewerUserId: String?,
        settings: PrivacySettingsRepository.PrivacySettings? = null,
        isBlockedOverride: Boolean? = null
    ): ProfilePhotoVisibilityState {
        if (ownerUserId.isBlank()) {
            return ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
        }
        if (ownerUserId == viewerUserId) {
            return ProfilePhotoVisibilityState(isResolved = true, isVisible = true)
        }

        val viewerId = viewerUserId ?: return ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
        val isBlocked = isBlockedOverride ?: BlockRepository.isInteractionBlocked(ownerUserId)
        if (isBlocked) {
            return ProfilePhotoVisibilityState(isResolved = true, isVisible = false)
        }

        val resolvedSettings = settings ?: PrivacySettingsRepository.getPrivacySettingsForUser(ownerUserId)
        val isVisible = PrivacySettingsRepository.canViewerSee(
            ownerUserId = ownerUserId,
            viewerUid = viewerId,
            visibility = resolvedSettings.profilePhotoVisibility
        )

        return ProfilePhotoVisibilityState(isResolved = true, isVisible = isVisible)
    }
}