package com.glyph.glyph_v3.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.chatWallpaperDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_wallpaper_prefs")

/**
 * Stores per-theme chat wallpaper selections.
 *
 * Values are stored as asset-relative paths like: "dark_mode/snow_flakes.jpg".
 */
object ChatWallpaperStore {

    private fun keyForThemeFolder(themeFolderId: String) = stringPreferencesKey("wallpaper_$themeFolderId")
    private fun keyForDimming(themeFolderId: String) = androidx.datastore.preferences.core.floatPreferencesKey("dimming_$themeFolderId")

    fun selectedWallpaperPathFlow(context: Context, themeFolderId: String): Flow<String?> {
        val key = keyForThemeFolder(themeFolderId)
        return context.chatWallpaperDataStore.data.map { prefs -> prefs[key] }
    }

    fun wallpaperDimmingFlow(context: Context, themeFolderId: String): Flow<Float> {
        val key = keyForDimming(themeFolderId)
        return context.chatWallpaperDataStore.data.map { prefs -> prefs[key] ?: 0f }
    }

    suspend fun setSelectedWallpaperPath(
        context: Context,
        themeFolderId: String,
        assetRelativePath: String
    ) {
        val key = keyForThemeFolder(themeFolderId)
        context.chatWallpaperDataStore.edit { prefs ->
            prefs[key] = assetRelativePath
        }
    }

    suspend fun setWallpaperDimming(
        context: Context,
        themeFolderId: String,
        dimming: Float
    ) {
        val key = keyForDimming(themeFolderId)
        context.chatWallpaperDataStore.edit { prefs ->
            prefs[key] = dimming
        }
    }

    suspend fun clearSelectedWallpaper(context: Context, themeFolderId: String) {
        val key = keyForThemeFolder(themeFolderId)
        context.chatWallpaperDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }
}
