package com.glyph.glyph_v3.ui.settings

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

private val SETTINGS_SWITCH_CHECKED_THUMB = Color(0xFF0B1014)
private val SETTINGS_SWITCH_UNCHECKED_THUMB = Color(0xFF6E767D)
private val SETTINGS_SWITCH_CHECKED_TRACK = Color(0xFF21C063)
private val SETTINGS_SWITCH_UNCHECKED_TRACK = Color(0xFF0B1014)
private val SETTINGS_SWITCH_UNCHECKED_BORDER = Color(0xFF5B636A)

@Composable
fun GlyphSettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Switch(
        modifier = modifier,
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = SETTINGS_SWITCH_CHECKED_THUMB,
            checkedTrackColor = SETTINGS_SWITCH_CHECKED_TRACK,
            uncheckedThumbColor = SETTINGS_SWITCH_UNCHECKED_THUMB,
            uncheckedTrackColor = SETTINGS_SWITCH_UNCHECKED_TRACK,
            uncheckedBorderColor = SETTINGS_SWITCH_UNCHECKED_BORDER,
            checkedBorderColor = Color.Transparent
        )
    )
}

class SettingsModuleSwitchView(context: Context) : FrameLayout(context) {
    private var checkedState by mutableStateOf(false)
    private var onCheckedChange: ((Boolean) -> Unit)? = null

    var isChecked: Boolean
        get() = checkedState
        set(value) {
            setCheckedInternal(value, fromUser = false)
        }

    private fun setCheckedInternal(value: Boolean, fromUser: Boolean) {
        if (checkedState == value) return
        checkedState = value
        if (fromUser) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onCheckedChange?.invoke(value)
        }
    }

    init {
        val switchSpacingPx = (context.resources.displayMetrics.density * 20).toInt()
        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = switchSpacingPx
        }

        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    GlyphSettingsSwitch(
                        checked = checkedState,
                        onCheckedChange = { setCheckedInternal(it, fromUser = true) }
                    )
                }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
    }

    fun setOnCheckedChangeListener(listener: ((Boolean) -> Unit)?) {
        onCheckedChange = listener
    }
}

fun createSettingsModuleSwitch(
    context: Context,
    defaultValue: Boolean,
    onToggle: (Boolean) -> Unit
): SettingsModuleSwitchView {
    return SettingsModuleSwitchView(context).apply {
        isChecked = defaultValue
        setOnCheckedChangeListener(onToggle)
    }
}
