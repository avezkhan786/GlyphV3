package com.glyph.glyph_v3.ui.settings

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.utils.ThemeManager
import com.google.android.material.card.MaterialCardView

class ThemeSelectionActivity : AppCompatActivity() {

    private var currentTheme = ThemeManager.THEME_SYSTEM

    private lateinit var cardLightMode: MaterialCardView
    private lateinit var cardDarkMode: MaterialCardView
    private lateinit var cardPastelMode: MaterialCardView
    private lateinit var cardSystemMode: MaterialCardView
    
    private lateinit var badgeLightMode: FrameLayout
    private lateinit var badgeDarkMode: FrameLayout
    private lateinit var badgePastelMode: FrameLayout
    private lateinit var badgeSystemMode: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply current theme before super.onCreate
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContentView(R.layout.activity_theme_selection)

        val rootLayout = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        // Setup toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Select Theme"
        toolbar.setNavigationOnClickListener { finish() }

        currentTheme = ThemeManager.getCurrentTheme(this)

        // Apply pastel gradient for Pastel-Sky
        if (currentTheme == ThemeManager.THEME_PASTEL_SKY) {
            // Set background on the root LinearLayout
            rootLayout.background = ContextCompat.getDrawable(this, R.drawable.bg_pastel_gradient)
            
            // Fix Toolbar for Pastel-Sky
            toolbar.setBackgroundColor(Color.TRANSPARENT)
            val textColor = ContextCompat.getColor(this, R.color.pastel_text_primary)
            toolbar.setTitleTextColor(textColor)
            toolbar.navigationIcon?.setTint(textColor)
            
            // Ensure status bar is transparent
            window.statusBarColor = Color.TRANSPARENT
        }

        initViews()
        updateSelection()
        setupClickListeners()
    }

    private fun initViews() {
        cardLightMode = findViewById(R.id.cardLightMode)
        cardDarkMode = findViewById(R.id.cardDarkMode)
        cardPastelMode = findViewById(R.id.cardPastelMode)
        cardSystemMode = findViewById(R.id.cardSystemMode)
        
        badgeLightMode = findViewById(R.id.badgeLightMode)
        badgeDarkMode = findViewById(R.id.badgeDarkMode)
        badgePastelMode = findViewById(R.id.badgePastelMode)
        badgeSystemMode = findViewById(R.id.badgeSystemMode)
    }

    private fun setupClickListeners() {
        cardLightMode.setOnClickListener {
            selectTheme(ThemeManager.THEME_LIGHT)
        }

        cardDarkMode.setOnClickListener {
            selectTheme(ThemeManager.THEME_DARK)
        }

        cardPastelMode.setOnClickListener {
            selectTheme(ThemeManager.THEME_PASTEL_SKY)
        }

        cardSystemMode.setOnClickListener {
            selectTheme(ThemeManager.THEME_SYSTEM)
        }
    }

    private fun selectTheme(theme: String) {
        currentTheme = theme
        
        // Update UI first
        updateSelection()
        
        // Use ThemeManager.setTheme which saves, applies night mode, and recreates
        ThemeManager.setTheme(this, theme)
    }
    private fun updateSelection() {
        // Reset all cards
        resetCard(cardLightMode, badgeLightMode)
        resetCard(cardDarkMode, badgeDarkMode)
        resetCard(cardPastelMode, badgePastelMode)
        resetCard(cardSystemMode, badgeSystemMode)

        // Highlight selected card
        when (currentTheme) {
            ThemeManager.THEME_LIGHT -> activateCard(cardLightMode, badgeLightMode)
            ThemeManager.THEME_DARK -> activateCard(cardDarkMode, badgeDarkMode)
            ThemeManager.THEME_PASTEL_SKY -> activateCard(cardPastelMode, badgePastelMode)
            ThemeManager.THEME_SYSTEM -> activateCard(cardSystemMode, badgeSystemMode)
        }
    }

    private fun resetCard(card: MaterialCardView, badge: FrameLayout) {
        card.strokeColor = 0xFFE5E7EB.toInt()
        card.strokeWidth = resources.getDimensionPixelSize(R.dimen.card_stroke_width_default)
        badge.visibility = View.GONE
    }

    private fun activateCard(card: MaterialCardView, badge: FrameLayout) {
        card.strokeColor = ContextCompat.getColor(this, R.color.glyph_primary)
        card.strokeWidth = resources.getDimensionPixelSize(R.dimen.card_stroke_width_active)
        badge.visibility = View.VISIBLE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
