package com.glyph.glyph_v3.ui.auth.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.auth.AuthAnimationUtils
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Premium rounded primary button used throughout the auth flow.
 *
 * Supports:
 * - Press scale animation (96%)
 * - Disabled state (grey background)
 * - Inline loading state (replaces text with spinner)
 * - Full-width or fixed-width layouts
 * - Icon-only circular variant
 *
 * @param text Button label (hidden when [loading] is true).
 * @param onClick Callback invoked when the button is tapped.
 * @param enabled When false, the button is visually dimmed and non-interactive.
 * @param loading When true, shows a [CircularProgressIndicator] instead of the label.
 * @param modifier Modifier applied to the outer button.
 * @param icon Optional leading icon.
 * @param fullWidth When true, the button fills available width.
 * @param circular When true, renders a circular button (icon-only) with fixed size.
 */
@Composable
fun GlyphButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fullWidth: Boolean = true,
    circular: Boolean = false,
    height: Dp = 56.dp,
    cornerRadius: Dp = 28.dp
) {
    val theme = glyphTheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) AuthAnimationUtils.PRESS_SCALE_TARGET else 1f,
        animationSpec = AuthAnimationUtils.pressSpring,
        label = "btnScale"
    )

    val elevation by animateFloatAsState(
        targetValue = if (isPressed) 8f else 2f,
        animationSpec = AuthAnimationUtils.pressSpring,
        label = "btnElevation"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> theme.actionSecondary.copy(alpha = 0.4f)
            loading -> theme.actionPrimary.copy(alpha = 0.8f)
            else -> theme.actionPrimary
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "btnBg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> theme.textTertiary
            else -> if (theme.isDark) theme.textInverse else theme.textPrimary
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "btnContent"
    )

    val shape = if (circular) CircleShape else RoundedCornerShape(cornerRadius)

    Button(
        onClick = onClick,
        modifier = modifier
            .then(
                if (circular) Modifier.size(height)
                else if (fullWidth) Modifier.fillMaxWidth().height(height)
                else Modifier.height(height)
            )
            .scale(scale)
            .shadow(elevation.dp, shape),
        enabled = enabled && !loading,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = theme.actionSecondary.copy(alpha = 0.3f),
            disabledContentColor = theme.textTertiary
        ),
        interactionSource = interactionSource
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = contentColor,
                strokeWidth = 2.5.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    if (text.isNotEmpty()) {
                        Box(modifier = Modifier.width(8.dp))
                    }
                }
                if (text.isNotEmpty()) {
                    Text(
                        text = text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
