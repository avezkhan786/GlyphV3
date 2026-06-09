package com.glyph.glyph_v3.ui.settings.chat

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.glyph.glyph_v3.databinding.ActivityChatSettingsBinding
import com.glyph.glyph_v3.ui.settings.createSettingsModuleSwitch
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ChatSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatSettingsBinding
    private var setupJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityChatSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top)
            insets
        }

        binding.toolbar.title = "Chats"

        // Apply pastel gradient for Pastel-Sky
        val currentTheme = ThemeManager.getCurrentTheme(this)
        if (currentTheme == ThemeManager.THEME_PASTEL_SKY) {
            binding.root.background = ContextCompat.getDrawable(this, R.drawable.bg_pastel_gradient)
            
            // Fix Toolbar for Pastel-Sky
            binding.toolbar.setBackgroundColor(Color.TRANSPARENT)
            val textColor = ContextCompat.getColor(this, R.color.pastel_text_primary)
            binding.toolbar.setTitleTextColor(textColor)
            binding.toolbar.navigationIcon?.setTint(textColor)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        setupItems()
    }

    override fun onResume() {
        super.onResume()
        // Refresh subtitles that may depend on theme/wallpaper.
        setupItems()
    }

    private fun setupItems() {
        setupJob?.cancel()
        binding.settingsContainer.removeAllViews()

        // ── Display section ─────────────────────────────────────────
        addSectionHeader("Display")

        // Wallpaper
        addSettingItem(
            getString(R.string.chat_settings_wallpapers_title),
            getString(R.string.chat_settings_wallpapers_subtitle),
            R.drawable.ic_chats
        ) {
            startActivity(Intent(this, WallpaperSelectionActivity::class.java))
        }

        // Font size + chat behavior loaded async from DataStore
        setupJob = lifecycleScope.launch {
            val fontSize = SettingsDataStore.fontSizeFlow(this@ChatSettingsActivity).first()
            val enterToSend = SettingsDataStore.enterToSendFlow(this@ChatSettingsActivity).first()
            val mediaVisibility = SettingsDataStore.mediaVisibilityFlow(this@ChatSettingsActivity).first()

            addSettingItem(
                "Font size",
                fontSizeDisplayName(fontSize),
                R.drawable.ic_language
            ) {
                showFontSizePicker()
            }

            // ── Chat behavior ───────────────────────────────────────
            addSectionHeader("Chat behavior")

            addToggleItem(
                "Enter is send",
                "Press Enter to send messages instead of adding a new line",
                R.drawable.ic_send,
                enterToSend
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setEnterToSend(this@ChatSettingsActivity, enabled) }
            }

            addToggleItem(
                "Media visibility",
                "Show newly downloaded media in your phone's gallery",
                R.drawable.ic_media_visibility,
                mediaVisibility
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setMediaVisibility(this@ChatSettingsActivity, enabled) }
            }
        }
    }

    private fun showFontSizePicker() {
        val options = arrayOf("Small", "Medium", "Large")
        AlertDialog.Builder(this)
            .setTitle("Font size")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    SettingsDataStore.setFontSize(this@ChatSettingsActivity, which)
                    setupItems()
                }
            }
            .show()
    }

    private fun fontSizeDisplayName(size: Int): String = when (size) {
        0 -> "Small"
        1 -> "Medium"
        2 -> "Large"
        else -> "Medium"
    }

    // ── UI Helpers ──────────────────────────────────────────────────

    private fun addSectionHeader(title: String) {
        val dp = resources.displayMetrics.density
        binding.settingsContainer.addView(TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
    }

    private fun addSettingItem(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit) {
        val itemView = LayoutInflater.from(this)
            .inflate(R.layout.item_setting, binding.settingsContainer, false)
        itemView.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
        itemView.findViewById<TextView>(R.id.settingTitle).text = title
        itemView.findViewById<TextView>(R.id.settingSubtitle).text = subtitle
        itemView.setOnClickListener { onClick() }
        binding.settingsContainer.addView(itemView)
    }

    private fun addToggleItem(
        title: String,
        subtitle: String,
        iconRes: Int,
        defaultValue: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
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
        tc.addView(TextView(this).apply {
            text = title; textSize = 16f
            setTextColor(resolveThemeColor(R.attr.glyphTextPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        tc.addView(TextView(this).apply {
            text = subtitle; textSize = 13f
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(tc)
        row.addView(createSettingsModuleSwitch(this, defaultValue, onToggle))
        binding.settingsContainer.addView(row)
    }

    private fun resolveSelectableBackground(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }
}
