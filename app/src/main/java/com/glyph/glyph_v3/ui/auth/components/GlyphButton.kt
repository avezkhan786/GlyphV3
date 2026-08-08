package com.glyph.glyph_v3.ui.auth.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
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
 * @param iconSize Size of the icon when [icon] is provided. Defaults to 20.dp.
 * @param height Button height. Defaults to 56.dp.
 * @param cornerRadius Corner radius for non-circular buttons. Defaults to 28.dp.
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
    iconSize: Dp = 20.dp,
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
            !enabled -> if (theme.isDark) Color(0xFF353535) else Color(0xFFCCCCCC)
            loading -> theme.actionPrimary.copy(alpha = 0.8f)
            else -> theme.actionPrimary
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "btnBg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> if (theme.isDark) Color(0xFFCCCCCC) else Color(0xFF666666)
            else -> if (theme.isDark) theme.textInverse else theme.textPrimary
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "btnContent"
    )

    val shape = if (circular) CircleShape else RoundedCornerShape(cornerRadius)

    // For circular icon-only buttons, use a custom clickable Box to have full control over icon size
    if (circular && text.isEmpty() && icon != null) {
        val boxModifier = modifier
            .size(height)
            .scale(scale)
            .shadow(elevation.dp, shape)
            .background(containerColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClickLabel = null,
                role = null,
                onClick = onClick
            )

        Box(
            modifier = boxModifier,
            contentAlignment = Alignment.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = contentColor,
                    strokeWidth = 2.5.dp
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = contentColor
                )
            }
        }
    } else {
        val horizontalPadding = if (circular) 0.dp else 24.dp

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
                disabledContainerColor = if (theme.isDark) Color(0xFF353535) else Color(0xFFCCCCCC),
                disabledContentColor = if (theme.isDark) Color(0xFFCCCCCC) else Color(0xFF666666)
            ),
            interactionSource = interactionSource
        ) {
            val horizontalPadding = if (circular) 0.dp else 24.dp

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = contentColor,
                    strokeWidth = 2.5.dp
                )
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = horizontalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = contentColor
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
}
