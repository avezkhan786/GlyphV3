package com.glyph.glyph_v3.ui.calls

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteCallTarget(
    val userId: String,
    val displayName: String,
    val avatarUrl: String
)

object CallFavoritesStore {
    private const val PREFS_NAME = "call_favorites"
    private const val KEY_PREFIX = "favorites_"
    private const val FIELD_USER_ID = "userId"
    private const val FIELD_DISPLAY_NAME = "displayName"
    private const val FIELD_AVATAR_URL = "avatarUrl"

    private val favoritesFlow = MutableStateFlow<List<FavoriteCallTarget>>(emptyList())
    private var loadedUserId: String? = null

    fun observe(context: Context): StateFlow<List<FavoriteCallTarget>> {
        ensureLoaded(context.applicationContext)
        return favoritesFlow.asStateFlow()
    }

    fun add(context: Context, target: FavoriteCallTarget) {
        if (target.userId.isBlank()) return
        ensureLoaded(context.applicationContext)

        val current = favoritesFlow.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.userId == target.userId }
        if (existingIndex >= 0) {
            current[existingIndex] = current[existingIndex].copy(
                displayName = target.displayName.ifBlank { current[existingIndex].displayName },
                avatarUrl = target.avatarUrl.ifBlank { current[existingIndex].avatarUrl }
            )
        } else {
            current += target
        }
        updateAndPersist(context.applicationContext, current)
    }

    fun addAll(context: Context, targets: List<FavoriteCallTarget>) {
        if (targets.isEmpty()) return
        ensureLoaded(context.applicationContext)

        val current = favoritesFlow.value.toMutableList()
        targets.forEach { target ->
            if (target.userId.isBlank()) return@forEach
            val existingIndex = current.indexOfFirst { it.userId == target.userId }
            if (existingIndex >= 0) {
                current[existingIndex] = current[existingIndex].copy(
                    displayName = target.displayName.ifBlank { current[existingIndex].displayName },
                    avatarUrl = target.avatarUrl.ifBlank { current[existingIndex].avatarUrl }
                )
            } else {
                current += target
            }
        }
        updateAndPersist(context.applicationContext, current)
    }

    fun remove(context: Context, userId: String) {
        if (userId.isBlank()) return
        ensureLoaded(context.applicationContext)
        updateAndPersist(
            context.applicationContext,
            favoritesFlow.value.filterNot { it.userId == userId }
        )
    }

    fun isFavorite(context: Context, userId: String): Boolean {
        if (userId.isBlank()) return false
        ensureLoaded(context.applicationContext)
        return favoritesFlow.value.any { it.userId == userId }
    }

    fun reorder(context: Context, orderedUserIds: List<String>) {
        ensureLoaded(context.applicationContext)
        if (orderedUserIds.isEmpty()) {
            updateAndPersist(context.applicationContext, favoritesFlow.value)
            return
        }

        val currentById = favoritesFlow.value.associateBy { it.userId }
        val reordered = buildList {
            orderedUserIds.forEach { userId ->
                currentById[userId]?.let(::add)
            }
            favoritesFlow.value.forEach { target ->
                if (target.userId !in orderedUserIds) add(target)
            }
        }
        updateAndPersist(context.applicationContext, reordered)
    }

    private fun ensureLoaded(context: Context) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (loadedUserId == currentUserId) return

        loadedUserId = currentUserId
        favoritesFlow.value = if (currentUserId.isBlank()) {
            emptyList()
        } else {
            readFavorites(context, currentUserId)
        }
    }

    private fun updateAndPersist(context: Context, favorites: List<FavoriteCallTarget>) {
        favoritesFlow.value = favorites

        val userId = loadedUserId.orEmpty()
        if (userId.isBlank()) return

        val json = JSONArray().apply {
            favorites.forEach { target ->
                put(
                    JSONObject()
                        .put(FIELD_USER_ID, target.userId)
                        .put(FIELD_DISPLAY_NAME, target.displayName)
                        .put(FIELD_AVATAR_URL, target.avatarUrl)
                )
            }
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + userId, json.toString())
            .apply()
    }

    private fun readFavorites(context: Context, userId: String): List<FavoriteCallTarget> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + userId, null)
            .orEmpty()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val jsonArray = JSONArray(raw)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val id = item.optString(FIELD_USER_ID).orEmpty()
                    if (id.isBlank()) continue
                    add(
                        FavoriteCallTarget(
                            userId = id,
                            displayName = item.optString(FIELD_DISPLAY_NAME).orEmpty(),
                            avatarUrl = item.optString(FIELD_AVATAR_URL).orEmpty()
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }
}