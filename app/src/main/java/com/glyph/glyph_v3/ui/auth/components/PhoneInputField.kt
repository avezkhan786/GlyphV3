package com.glyph.glyph_v3.ui.auth.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.auth.AuthAnimationUtils
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Phone number input field with country code prefix and vertical divider.
 *
 * Displays the dial code (e.g. +91) as a non-editable label, a vertical divider,
 * and a large [BasicTextField] for national digits. The container border animates
 * on focus, and the field auto-formats digits for readability.
 *
 * @param dialCode The country calling code prefix (e.g. "91").
 * @param value The current [TextFieldValue] holding the national digits.
 * @param onValueChange Called when the user types or edits digits.
 * @param error When true, displays error styling.
 * @param modifier Modifier for the outer container.
 */
@Composable
fun PhoneInputField(
    dialCode: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    error: Boolean = false,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = when {
            error -> theme.actionError
            isFocused -> theme.borderFocus
            else -> theme.borderInput
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "phoneBorder"
    )

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 1.dp,
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "phoneBorderWidth"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.surfaceInput, RoundedCornerShape(14.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Country code prefix
        Text(
            text = "+$dialCode",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) theme.textPrimary else theme.textSecondary,
            fontSize = 22.sp
        )

        // Vertical divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(32.dp)
                .padding(vertical = 2.dp)
                .background(theme.divider)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // National digits input
        BasicTextField(
            value = value,
            onValueChange = { newValue ->
                // Allow only digits, auto-format with spaces every 5
                val digits = newValue.text.filter { it.isDigit() }
                val formatted = formatNationalDigits(digits)
                onValueChange(
                    TextFieldValue(
                        text = formatted,
                        selection = TextRange(formatted.length)
                    )
                )
            },
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .onFocusChanged { state -> isFocused = state.isFocused },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = TextStyle(
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
                color = theme.textPrimary,
                textAlign = TextAlign.Start
            ),
            cursorBrush = SolidColor(theme.cursorColor),
            singleLine = true,
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.text.isEmpty() && !isFocused) {
                        Text(
                            text = "Phone number",
                            style = MaterialTheme.typography.bodyLarge,
                            color = theme.textPlaceholder,
                            fontSize = 22.sp
                        )
                    }
                    innerTextField()
                }
            }
        )
    }
}

/**
 * Inserts a space every 5 digits for readability.
 * "9876543210" → "98765 43210"
 */
private fun formatNationalDigits(digits: String): String {
    if (digits.length <= 5) return digits
    return buildString {
        digits.forEachIndexed { index, c ->
            append(c)
            if ((index + 1) % 5 == 0 && index != digits.lastIndex) {
                append(' ')
            }
        }
    }
}
