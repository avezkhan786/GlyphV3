package com.glyph.glyph_v3.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.Context
import android.net.Uri
import android.util.Log
import com.glyph.glyph_v3.data.preferences.ChatWallpaperStore
import kotlinx.coroutines.flow.first
import com.bumptech.glide.Glide
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ChatWallpaperManager {

    data class ThemeFolder(
        /** Stable id used for persistence keys. */
        val id: String,
        /** Assets directory that contains wallpapers for this theme. */
        val assetDir: String
    )

    val LIGHT = ThemeFolder(id = "light", assetDir = "light_mode")
    val DARK = ThemeFolder(id = "dark", assetDir = "dark_mode")
    val PASTEL = ThemeFolder(id = "pastel", assetDir = "pastel_sky")

    private var cachedWallpaperPath: String? = null
    private var cachedWallpaperDimming: Float = 0f
    private var isPreloaded = false
    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedWallpaperBitmap: Bitmap? = null

    @Volatile
    private var cachedWallpaperBitmapPath: String? = null

    fun getCachedWallpaperPath(): String? = cachedWallpaperPath
    fun getCachedWallpaperDimming(): Float = cachedWallpaperDimming

    fun getCachedWallpaperDrawable(resources: Resources, expectedPath: String? = cachedWallpaperPath): Drawable? {
        val bitmap = cachedWallpaperBitmap ?: return null
        if (expectedPath != null && cachedWallpaperBitmapPath != expectedPath) return null
        return BitmapDrawable(resources, bitmap)
    }

    fun warmCurrentWallpaperAsync(context: Context) {
        preloadScope.launch {
            preload(context.applicationContext)
        }
    }

    /**
     * Preloads the current wallpaper path and dimming into memory.
     * Also triggers Glide to preload the image into memory/disk cache.
     */
    suspend fun preload(context: Context) {
        val appContext = context.applicationContext
        val folder = getEffectiveThemeFolder(appContext)
        cachedWallpaperPath = resolveWallpaperToApply(appContext, folder)
        cachedWallpaperDimming = getWallpaperDimming(appContext, folder)
        isPreloaded = true

        val cachedPath = cachedWallpaperPath
        if (cachedPath.isNullOrBlank()) {
            cachedWallpaperBitmap = null
            cachedWallpaperBitmapPath = null
            return
        }

        // Preload into Glide for ChatActivity (XML) and decode a screen-sized bitmap
        // so ChatActivity can apply it immediately without waiting for first-frame decode.
        try {
            cacheWallpaperBitmap(appContext, cachedPath)
            val uri = assetUri(cachedPath)
            Glide.with(appContext)
                .load(uri)
                .preload()

            // Preload into Coil for ChatComposeActivity (Compose)
            val request = ImageRequest.Builder(appContext)
                .data(uri)
                .build()
            appContext.imageLoader.enqueue(request)
        } catch (error: Exception) {
            Log.w("ChatWallpaper", "Failed to preload wallpaper bitmap for $cachedPath", error)
            cachedWallpaperBitmap = null
            cachedWallpaperBitmapPath = null
        }
    }

    private suspend fun cacheWallpaperBitmap(context: Context, path: String) {
        withContext(Dispatchers.IO) {
            val uri = assetUri(path)
            val width = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
            val height = context.resources.displayMetrics.heightPixels.coerceAtLeast(1)
            val target = Glide.with(context)
                .asBitmap()
                .load(uri)
                .centerCrop()
                .submit(width, height)

            try {
                val decodedBitmap = target.get()
                val safeConfig = decodedBitmap.config ?: Bitmap.Config.ARGB_8888
                cachedWallpaperBitmap = decodedBitmap.copy(safeConfig, false)
                cachedWallpaperBitmapPath = path
            } finally {
                Glide.with(context).clear(target)
            }
        }
    }

    fun getEffectiveThemeFolder(context: Context): ThemeFolder {
        return when (ThemeManager.getCurrentTheme(context)) {
            ThemeManager.THEME_DARK -> DARK
            ThemeManager.THEME_PASTEL_SKY -> PASTEL
            ThemeManager.THEME_SYSTEM -> if (ThemeManager.isDarkMode(context)) DARK else LIGHT
            else -> LIGHT
        }
    }

    fun listWallpapersForFolder(context: Context, folder: ThemeFolder): List<String> {
        val files = context.assets.list(folder.assetDir)?.toList().orEmpty()
        val imageFiles = files
            .filter { name ->
                val lower = name.lowercase()
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
            }

        // Keep "default" first if present, then alphabetical.
        val (defaults, rest) = imageFiles.partition { it.lowercase().startsWith("default") }
        return defaults.sorted() + rest.sorted()
    }

    suspend fun getSelectedWallpaperPathOrNull(context: Context, folder: ThemeFolder): String? {
        return ChatWallpaperStore
            .selectedWallpaperPathFlow(context, folder.id)
            .first()
    }

    suspend fun getWallpaperDimming(context: Context, folder: ThemeFolder): Float {
        return ChatWallpaperStore
            .wallpaperDimmingFlow(context, folder.id)
            .first()
    }

    suspend fun setSelectedWallpaper(context: Context, folder: ThemeFolder, fileName: String) {
        // If it's a content URI, store it as is. Otherwise, prepend asset dir if not already present.
        val path = if (fileName.startsWith("content://") || fileName.startsWith("file://")) {
            fileName
        } else if (fileName.contains("/")) {
             fileName // Already has path
        } else {
            "${folder.assetDir}/$fileName"
        }
        ChatWallpaperStore.setSelectedWallpaperPath(context, folder.id, path)
        
        // Update cache if this is the current theme
        if (folder == getEffectiveThemeFolder(context)) {
            cachedWallpaperPath = path
            warmCurrentWallpaperAsync(context.applicationContext)
        }
    }

    suspend fun setWallpaperDimming(context: Context, folder: ThemeFolder, dimming: Float) {
        ChatWallpaperStore.setWallpaperDimming(context, folder.id, dimming)
        
        // Update cache if this is the current theme
        if (folder == getEffectiveThemeFolder(context)) {
            cachedWallpaperDimming = dimming
        }
    }

    suspend fun resolveWallpaperToApply(context: Context, folder: ThemeFolder): String? {
        val selectedPath = getSelectedWallpaperPathOrNull(context, folder)
        
        // If it's a custom URI, return it directly
        if (!selectedPath.isNullOrBlank() && (selectedPath.startsWith("content://") || selectedPath.startsWith("file://"))) {
            return selectedPath
        }

        val availableFileNames = listWallpapersForFolder(context, folder)
        if (availableFileNames.isEmpty()) return null

        if (!selectedPath.isNullOrBlank()) {
            val selectedFileName = selectedPath.removePrefix(folder.assetDir + "/")
            if (availableFileNames.contains(selectedFileName)) {
                return "${folder.assetDir}/$selectedFileName"
            }
        }

        // Fallback to default if present, else first available.
        val fallbackFileName = availableFileNames.first()
        return "${folder.assetDir}/$fallbackFileName"
    }

    fun assetUri(path: String): Uri {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            return Uri.parse(path)
        }
        // Encode spaces and other special characters, but keep '/' separators.
        val encoded = Uri.encode(path, "/")
        return Uri.parse("file:///android_asset/$encoded")
    }
}
