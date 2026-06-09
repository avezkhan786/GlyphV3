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
import com.glyph.glyph_v3.data.service.LiveAudioSharingManager
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.utils.ThemeManager

/**
 * Settings → Privacy → Live Audio Sharing
 *
 * Full control over mic sharing permissions, audience, and active sessions.
 */
class LiveAudioSettingsActivity : ComponentActivity() {

    private lateinit var viewModel: LiveAudioSettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = ViewModelProvider(this)[LiveAudioSettingsViewModel::class.java]
        viewModel.load()

        setContent {
            GlyphThemeProvider {
                val state by viewModel.state.collectAsState()

                LaunchedEffect(state.savedSuccessfully) {
                    if (state.savedSuccessfully) {
                        Toast.makeText(
                            this@LiveAudioSettingsActivity,
                            "Live audio settings saved",
                            Toast.LENGTH_SHORT
                        ).show()

                        // If sharing was disabled, shut down any active sessions
                        if (!state.shareMicEnabled) {
                            LiveAudioSharingManager
                                .getInstance(this@LiveAudioSettingsActivity)
                                .shutdown()
                        }

                        finish()
                    }
                }

                LaunchedEffect(state.error) {
                    state.error?.let {
                        Toast.makeText(
                            this@LiveAudioSettingsActivity, it, Toast.LENGTH_SHORT
                        ).show()
                        viewModel.clearError()
                    }
                }

                LiveAudioSettingsScreen(
                    state = state,
                    onToggleShareMic = viewModel::toggleShareMic,
                    onSetAudience = viewModel::setAudience,
                    onToggleUser = viewModel::toggleUser,
                    onSearchChanged = viewModel::updateSearch,
                    onStopSharing = {
                        viewModel.stopSharingNow()
                        LiveAudioSharingManager
                            .getInstance(this@LiveAudioSettingsActivity)
                            .stopBroadcasting()
                    },
                    onSave = viewModel::save,
                    onBack = { finish() }
                )
            }
        }
    }
}
