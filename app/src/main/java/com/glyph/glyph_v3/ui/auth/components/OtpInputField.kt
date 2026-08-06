package com.glyph.glyph_v3.ui.auth.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.auth.AuthAnimationUtils
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.delay

/**
 * Premium 6-digit OTP input component.
 *
 * Renders 6 individually styled boxes with auto-advance, backspace navigation,
 * paste support, and digit-entry animations. Uses a single hidden [BasicTextField]
 * for keyboard/paste/selection management, with visible boxes mirroring the text.
 */
@Composable
fun OtpInputField(
    length: Int = 6,
    onCodeComplete: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    error: Boolean = false,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    val focusRequesters = remember { List(length) { FocusRequester() } }
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var focusedIndex by remember { mutableStateOf(0) }
    val shakeOffset = remember { Animatable(0f) }

    // Trigger shake on error
    LaunchedEffect(error) {
        if (error) {
            repeat(4) {
                shakeOffset.animateTo(
                    AuthAnimationUtils.SHAKE_OFFSET_DP.value,
                    tween(AuthAnimationUtils.SHAKE_HALF_CYCLE_MS)
                )
                shakeOffset.animateTo(
                    -AuthAnimationUtils.SHAKE_OFFSET_DP.value,
                    tween(AuthAnimationUtils.SHAKE_HALF_CYCLE_MS)
                )
            }
            shakeOffset.animateTo(0f, tween(AuthAnimationUtils.SHAKE_HALF_CYCLE_MS))
        }
    }

    // Focus the current index
    LaunchedEffect(focusedIndex) {
        delay(50)
        if (focusedIndex in 0 until length) {
            focusRequesters[focusedIndex].requestFocus()
        }
    }

    val code = textFieldValue.text.take(length)

    // Notify callers
    LaunchedEffect(code) {
        onCodeChanged(code)
        if (code.length == length) {
            onCodeComplete(code)
        }
    }

    // Clipboard paste / auto-fill support
    val clipboardManager = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        val clipText = clipboardManager.getText()?.text?.filter { it.isDigit() }
        if (!clipText.isNullOrBlank() && clipText.length == length) {
            textFieldValue = TextFieldValue(clipText, TextRange(clipText.length))
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        // Hidden text field that captures all keyboard input
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val digits = newValue.text.filter { it.isDigit() }
                val truncated = digits.take(length)
                textFieldValue = TextFieldValue(
                    text = truncated,
                    selection = TextRange(truncated.length)
                )
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequesters.first()),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = Color.Transparent,
                fontSize = 1.sp
            ),
            cursorBrush = SolidColor(Color.Transparent),
            singleLine = true
        )

        // Visible OTP boxes
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shakeOffset.value },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (index in 0 until length) {
                val digit = code.getOrNull(index)
                val isFocused = index == focusedIndex
                val isFilled = digit != null

                OtpBox(
                    digit = digit,
                    isFocused = isFocused,
                    isFilled = isFilled,
                    isError = error,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequesters[index])
                        .onFocusChanged { state ->
                            if (state.isFocused) focusedIndex = index
                        },
                    onClick = {
                        focusedIndex = index
                        focusRequesters[index].requestFocus()
                    }
                )
            }
        }
    }
}

@Composable
private fun OtpBox(
    digit: Char?,
    isFocused: Boolean,
    isFilled: Boolean,
    isError: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val theme = glyphTheme

    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> theme.actionError
            isFocused -> theme.borderFocus
            isFilled -> theme.borderPrimary
            else -> theme.borderInput
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "otpBorder"
    )

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 1.5.dp,
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "otpBorderWidth"
    )

    val bgColor by animateColorAsState(
        targetValue = when {
            isFocused -> theme.surfaceInput
            isFilled -> theme.backgroundElevated
            else -> theme.backgroundPrimary
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "otpBg"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isError -> theme.actionError
            isFilled -> theme.textPrimary
            else -> theme.textSecondary
        },
        animationSpec = tween(AuthAnimationUtils.BORDER_TRANSITION_MS),
        label = "otpText"
    )

    Box(
        modifier = modifier
            .height(56.dp)
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = digit,
            transitionSpec = {
                (scaleIn(animationSpec = tween(AuthAnimationUtils.OTP_DIGIT_ENTRY_MS)) +
                    fadeIn(animationSpec = tween(AuthAnimationUtils.OTP_DIGIT_ENTRY_MS)))
                    .togetherWith(
                        androidx.compose.animation.scaleOut(animationSpec = tween(100)) +
                            androidx.compose.animation.fadeOut(animationSpec = tween(100))
                    )
            },
            label = "otpDigit"
        ) { targetDigit ->
            if (targetDigit != null) {
                Text(
                    text = targetDigit.toString(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    textAlign = TextAlign.Center
                )
            } else if (isFocused) {
                // Blinking cursor
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(24.dp)
                        .background(theme.cursorColor, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}
