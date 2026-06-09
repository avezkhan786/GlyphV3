package com.glyph.glyph_v3.ui.settings.chat

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.databinding.ActivityWallpaperPreviewBinding
import com.glyph.glyph_v3.utils.ChatWallpaperManager
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.launch

class WallpaperPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWallpaperPreviewBinding
    
    private var wallpaperPath: String? = null
    private var themeFolderId: String? = null
    private var currentDimming: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityWallpaperPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wallpaperPath = intent.getStringExtra(EXTRA_WALLPAPER_PATH)
        themeFolderId = intent.getStringExtra(EXTRA_THEME_FOLDER_ID)
        currentDimming = intent.getFloatExtra(EXTRA_INITIAL_DIMMING, 0f)

        setupUI()
        loadWallpaper()
    }

    private fun setupUI() {
        // Toolbar
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        // Slider
        binding.brightnessSlider.value = currentDimming
        updateDimming(currentDimming)
        
        binding.brightnessSlider.addOnChangeListener { _, value, _ ->
            currentDimming = value
            updateDimming(value)
        }

        // Reset brightness on Sun icon click
        binding.iconSun.setOnClickListener {
            binding.brightnessSlider.value = 0f
        }

        // Set Button
        binding.btnSetWallpaper.setOnClickListener {
            saveAndFinish()
        }
    }

    private fun loadWallpaper() {
        if (wallpaperPath == null) return

        if (wallpaperPath!!.startsWith("content://") || wallpaperPath!!.startsWith("file://")) {
            Glide.with(this)
                .load(Uri.parse(wallpaperPath))
                .centerCrop()
                .into(binding.previewImage)
        } else {
            // Asset path
            // If themeFolderId is provided, construct full path if needed
            // The adapter passes just the filename usually, but let's see what we pass.
            // We will pass the FULL asset path or just filename. 
            // Let's assume we pass what ChatWallpaperManager expects or can handle.
            
            val uri = if (themeFolderId != null) {
                val folder = when(themeFolderId) {
                    "dark" -> ChatWallpaperManager.DARK
                    "pastel" -> ChatWallpaperManager.PASTEL
                    else -> ChatWallpaperManager.LIGHT
                }
                // If path doesn't contain slash, prepend folder
                val fullPath = if (wallpaperPath!!.contains("/")) wallpaperPath!! else "${folder.assetDir}/$wallpaperPath"
                ChatWallpaperManager.assetUri(fullPath)
            } else {
                ChatWallpaperManager.assetUri(wallpaperPath!!)
            }

            Glide.with(this)
                .load(uri)
                .centerCrop()
                .into(binding.previewImage)
        }
    }

    private fun updateDimming(value: Float) {
        val alpha = (value * 255).toInt()
        binding.previewOverlay.setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }

    private fun saveAndFinish() {
        if (themeFolderId == null || wallpaperPath == null) return

        val folder = when(themeFolderId) {
            "dark" -> ChatWallpaperManager.DARK
            "pastel" -> ChatWallpaperManager.PASTEL
            else -> ChatWallpaperManager.LIGHT
        }

        lifecycleScope.launch {
            ChatWallpaperManager.setSelectedWallpaper(this@WallpaperPreviewActivity, folder, wallpaperPath!!)
            ChatWallpaperManager.setWallpaperDimming(this@WallpaperPreviewActivity, folder, currentDimming)
            
            android.widget.Toast.makeText(this@WallpaperPreviewActivity, "Wallpaper set", android.widget.Toast.LENGTH_SHORT).show()
            
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {
        const val EXTRA_WALLPAPER_PATH = "wallpaper_path"
        const val EXTRA_THEME_FOLDER_ID = "theme_folder_id"
        const val EXTRA_INITIAL_DIMMING = "initial_dimming"
    }
}
