package com.glyph.glyph_v3.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.glyph.glyph_v3.data.service.WalkieTalkieManager
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.utils.ThemeManager

class WalkieTalkieSettingsActivity : ComponentActivity() {

    private lateinit var viewModel: WalkieTalkieSettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[WalkieTalkieSettingsViewModel::class.java]
        viewModel.load()

        setContent {
            GlyphThemeProvider {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state.savedSuccessfully) {
                    if (state.savedSuccessfully) {
                        Toast.makeText(
                            this@WalkieTalkieSettingsActivity,
                            "Walkie-talkie settings saved",
                            Toast.LENGTH_SHORT
                        ).show()

                        if (!state.autoAcceptEnabled) {
                            WalkieTalkieManager.getInstance(applicationContext).disconnect()
                        }

                        finish()
                    }
                }

                LaunchedEffect(state.error) {
                    state.error?.let { error ->
                        Toast.makeText(this@WalkieTalkieSettingsActivity, error, Toast.LENGTH_SHORT).show()
                        viewModel.clearError()
                    }
                }

                WalkieTalkieSettingsScreen(
                    state = state,
                    onToggleAutoAccept = viewModel::setAutoAcceptEnabled,
                    onScopeChanged = viewModel::setScope,
                    onToggleUser = viewModel::toggleAllowedUser,
                    onSearchChanged = viewModel::updateSearch,
                    onSave = viewModel::save,
                    onBack = { finish() }
                )
            }
        }
    }
}