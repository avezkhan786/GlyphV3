package com.glyph.glyph_v3.ui.settings.chat

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.databinding.ActivityWallpaperSelectionBinding
import com.glyph.glyph_v3.utils.ChatWallpaperManager
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.launch

class WallpaperSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWallpaperSelectionBinding

    private val themeFolder by lazy { ChatWallpaperManager.getEffectiveThemeFolder(this) }

    private var initialSelection: String? = null
    private var pendingSelection: String? = null
    private var pendingDimming: Float = 0f

    private var adapter: WallpaperAdapter? = null

    private val previewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // Wallpaper set successfully.
            // We do NOT finish here, so the user stays on the selection screen.
            // Optionally refresh the grid if needed (though selection state might not be visible in grid yet).
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            // Take persistable URI permission for long-term access
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Not a persistable URI, but we'll try to use it anyway
            }
            launchPreview(it.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge for transparent status bar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        binding = ActivityWallpaperSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Setup Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Wallpaper"
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Apply pastel gradient for Pastel-Sky
        val currentTheme = ThemeManager.getCurrentTheme(this)
        if (currentTheme == ThemeManager.THEME_PASTEL_SKY) {
            binding.root.background = ContextCompat.getDrawable(this, R.drawable.bg_pastel_gradient)
            binding.toolbar.setBackgroundColor(Color.TRANSPARENT)
            val textColor = ContextCompat.getColor(this, R.color.pastel_text_primary)
            binding.toolbar.setTitleTextColor(textColor)
            binding.toolbar.navigationIcon?.setTint(textColor)
        }

        // Handle insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        // 2. Load Initial State
        lifecycleScope.launch {
            val fullPath = ChatWallpaperManager.getSelectedWallpaperPathOrNull(this@WallpaperSelectionActivity, themeFolder)
            // If it's an asset path, we need to strip the folder prefix to match the adapter items
            initialSelection = if (fullPath != null && !fullPath.startsWith("content://") && fullPath.contains("/")) {
                fullPath.substringAfterLast("/")
            } else {
                fullPath
            }
            
            pendingSelection = initialSelection
            pendingDimming = ChatWallpaperManager.getWallpaperDimming(this@WallpaperSelectionActivity, themeFolder)

            setupRecyclerView()
        }
    }

    private fun setupRecyclerView() {
        val wallpapers = ChatWallpaperManager.listWallpapersForFolder(this, themeFolder)
        
        adapter = WallpaperAdapter(
            wallpapers = wallpapers,
            currentSelection = pendingSelection,
            onWallpaperSelected = { wallpaperName ->
                launchPreview(wallpaperName)
            },
            onGalleryClicked = {
                galleryLauncher.launch(arrayOf("image/*"))
            }
        )

        val spanCount = calculateSpanCount()
        binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.recyclerView.addItemDecoration(GridSpacingItemDecoration(spanCount, resources.getDimensionPixelSize(R.dimen.wallpaper_grid_spacing), includeEdge = true))
        binding.recyclerView.adapter = adapter
    }

    private fun launchPreview(wallpaperPath: String) {
        val intent = Intent(this, WallpaperPreviewActivity::class.java).apply {
            putExtra(WallpaperPreviewActivity.EXTRA_WALLPAPER_PATH, wallpaperPath)
            putExtra(WallpaperPreviewActivity.EXTRA_THEME_FOLDER_ID, themeFolder.id)
            putExtra(WallpaperPreviewActivity.EXTRA_INITIAL_DIMMING, pendingDimming)
        }
        previewLauncher.launch(intent)
    }

    private fun calculateSpanCount(): Int {
        val displayMetrics = resources.displayMetrics
        val dpWidth = displayMetrics.widthPixels / displayMetrics.density
        return (dpWidth / 110).toInt().coerceAtLeast(3)
    }
}

class GridSpacingItemDecoration(private val spanCount: Int, private val spacing: Int, private val includeEdge: Boolean) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        val column = position % spanCount

        if (includeEdge) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount

            if (position < spanCount) {
                outRect.top = spacing
            }
            outRect.bottom = spacing
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) {
                outRect.top = spacing
            }
        }
    }
}
