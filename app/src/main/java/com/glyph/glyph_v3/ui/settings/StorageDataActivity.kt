package com.glyph.glyph_v3.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.preferences.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class StorageDataActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val toolbar = androidx.appcompat.widget.Toolbar(this).apply {
            title = "Storage and data"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(scrollView)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 32)
        }
        scrollView.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        buildUI()
    }

    private fun buildUI() {
        container.removeAllViews()
        val dp = resources.displayMetrics.density

        // ── Storage Usage ───────────────────────────────────────────
        addSectionHeader("Storage usage")
        addStorageOverview(dp)

        // ── Media management ────────────────────────────────────────
        addSectionHeader("Manage storage")

        addSettingItem("Images", calculateMediaSize("images"), R.drawable.ic_photo) {
            showClearMediaDialog("images")
        }
        addDivider()
        addSettingItem("Videos", calculateMediaSize("videos"), R.drawable.ic_videocam) {
            showClearMediaDialog("videos")
        }
        addDivider()
        addSettingItem("Audio", calculateMediaSize("audio"), R.drawable.ic_mic) {
            showClearMediaDialog("audio")
        }
        addDivider()
        addSettingItem("Documents", calculateMediaSize("documents"), R.drawable.ic_attachment_document) {
            showClearMediaDialog("documents")
        }
        addDivider()
        addSettingItem("Clear all cached media", "Free up space by clearing cached files", R.drawable.ic_delete) {
            showClearAllCacheDialog()
        }

        // ── Auto-download ───────────────────────────────────────────
        addSectionHeader("Auto-download media")

        lifecycleScope.launch {
            val wifi = SettingsDataStore.autoDownloadWifiFlow(this@StorageDataActivity).first()
            val mobile = SettingsDataStore.autoDownloadMobileFlow(this@StorageDataActivity).first()
            val roaming = SettingsDataStore.autoDownloadRoamingFlow(this@StorageDataActivity).first()

            addToggleItem("When connected to Wi-Fi", "Auto-download media on Wi-Fi", R.drawable.ic_download, wifi) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setAutoDownloadWifi(this@StorageDataActivity, enabled) }
            }
            addDivider()
            addToggleItem("When using mobile data", "Auto-download media on mobile data", R.drawable.ic_download, mobile) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setAutoDownloadMobile(this@StorageDataActivity, enabled) }
            }
            addDivider()
            addToggleItem("When roaming", "Auto-download media while roaming", R.drawable.ic_download, roaming) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setAutoDownloadRoaming(this@StorageDataActivity, enabled) }
            }
        }
    }

    private fun addStorageOverview(dp: Float) {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            }
            setCardBackgroundColor(resolveThemeColor(R.attr.glyphCardBackground))
            radius = 12 * dp
            cardElevation = 2 * dp
        }

        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }
        card.addView(cardContent)

        // Calculate storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.blockSizeLong * stat.blockCountLong
        val availableBytes = stat.blockSizeLong * stat.availableBlocksLong
        val usedBytes = totalBytes - availableBytes
        val appCacheBytes = cacheDir.walkBottomUp().sumOf { it.length() }

        val totalStr = formatSize(totalBytes)
        val usedStr = formatSize(usedBytes)
        val availableStr = formatSize(availableBytes)
        val appCacheStr = formatSize(appCacheBytes)

        cardContent.addView(TextView(this).apply {
            text = "Device Storage"
            textSize = 16f
            setTextColor(resolveThemeColor(R.attr.glyphTextPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (8 * dp).toInt()
            ).apply { topMargin = (12 * dp).toInt(); bottomMargin = (12 * dp).toInt() }
            max = 100
            progress = ((usedBytes.toDouble() / totalBytes) * 100).toInt()
        }
        cardContent.addView(progressBar)

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        infoRow.addView(TextView(this).apply {
            text = "$usedStr used of $totalStr"
            textSize = 13f
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        infoRow.addView(TextView(this).apply {
            text = "$availableStr free"
            textSize = 13f
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
        })
        cardContent.addView(infoRow)

        cardContent.addView(TextView(this).apply {
            text = "Glyph app cache: $appCacheStr"
            textSize = 12f
            setTextColor(resolveThemeColor(R.attr.glyphTextTertiary))
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })

        container.addView(card)
    }

    private fun calculateMediaSize(type: String): String {
        val mediaDir = File(filesDir, "media/$type")
        if (!mediaDir.exists()) return "0 B"
        val size = mediaDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return formatSize(size)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    private fun showClearMediaDialog(type: String) {
        AlertDialog.Builder(this)
            .setTitle("Clear $type")
            .setMessage("This will delete all cached $type files. Downloaded files in your device gallery will not be affected.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val dir = File(filesDir, "media/$type")
                        if (dir.exists()) dir.deleteRecursively()

                        // Also clear cache subdirectory
                        val cacheSubDir = File(cacheDir, type)
                        if (cacheSubDir.exists()) cacheSubDir.deleteRecursively()
                    }
                    Toast.makeText(this@StorageDataActivity, "${type.replaceFirstChar { it.uppercase() }} cache cleared", Toast.LENGTH_SHORT).show()
                    buildUI() // Refresh
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearAllCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear all cached media")
            .setMessage("This will clear all cached images, videos, audio, and documents. This cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val mediaDir = File(filesDir, "media")
                        if (mediaDir.exists()) mediaDir.deleteRecursively()

                        // Clear Glide cache
                        com.bumptech.glide.Glide.get(this@StorageDataActivity).clearDiskCache()

                        // Clear general cache
                        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    }
                    Toast.makeText(this@StorageDataActivity, "All cached media cleared", Toast.LENGTH_SHORT).show()
                    buildUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── UI helpers ──────────────────────────────────────────────────

    private fun addSectionHeader(title: String) {
        val dp = resources.displayMetrics.density
        container.addView(TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
    }

    private fun addSettingItem(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit) {
        val itemView = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        itemView.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
        itemView.findViewById<TextView>(R.id.settingTitle).text = title
        itemView.findViewById<TextView>(R.id.settingSubtitle).text = subtitle
        itemView.setOnClickListener { onClick() }
        container.addView(itemView)
    }

    private fun addToggleItem(title: String, subtitle: String, iconRes: Int, defaultValue: Boolean, onToggle: (Boolean) -> Unit) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            setBackgroundResource(resolveSelectableBackground())
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() }
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(resolveThemeColor(R.attr.glyphIconSecondary))
        })
        val tc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tc.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(resolveThemeColor(R.attr.glyphTextPrimary)); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        tc.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(resolveThemeColor(R.attr.glyphTextSecondary)); setPadding(0, (2 * dp).toInt(), 0, 0) })
        row.addView(tc)
        row.addView(createSettingsModuleSwitch(this, defaultValue, onToggle))
        container.addView(row)
    }

    private fun addDivider() {
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = (72 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(resolveThemeColor(R.attr.glyphDivider))
        })
    }

    private fun resolveSelectableBackground(): Int {
        val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); return tv.resourceId
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue(); theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }
}
