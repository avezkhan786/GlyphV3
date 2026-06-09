package com.glyph.glyph_v3.ui.status

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.utils.ThemeManager

class StatusPrivacyActivity : ComponentActivity() {

    private lateinit var viewModel: StatusViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[StatusViewModel::class.java]
        viewModel.loadPrivacySettings()

        setContent {
            GlyphThemeProvider {
                val state by viewModel.privacyState.collectAsState()

                LaunchedEffect(state.savedSuccessfully) {
                    if (state.savedSuccessfully) {
                        Toast.makeText(this@StatusPrivacyActivity, "Privacy settings saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }

                StatusPrivacyScreen(
                    state = state,
                    onModeChange = viewModel::updatePrivacyMode,
                    onToggleExcluded = viewModel::toggleExcludedContact,
                    onToggleIncluded = viewModel::toggleIncludedContact,
                    onSave = viewModel::savePrivacySettings,
                    onBack = { finish() }
                )
            }
        }
    }
}
