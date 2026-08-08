package com.glyph.glyph_v3.ui.auth

import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.auth.components.AuthScaffold
import com.glyph.glyph_v3.ui.auth.components.GlyphButton
import com.glyph.glyph_v3.ui.auth.components.LoadingOverlay
import com.glyph.glyph_v3.ui.auth.components.OtpInputField
import com.glyph.glyph_v3.ui.base.ThemedActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.delay

/**
 * Screen 3: OTP Verification.
 *
 * Displays 6 OTP input boxes, a countdown timer with resend, and a verify button.
 * On successful verification, shows an animated checkmark then routes the user.
 */
class OtpVerificationActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val phoneNumber = AuthFlowSession.phoneNumber ?: ""
        val dialCode = AuthFlowSession.dialCode ?: ""
        val national = AuthFlowSession.nationalDigits ?: ""

        if (phoneNumber.isEmpty()) {
            finish()
            return
        }

        setContent {
            GlyphThemeProvider {
                OtpVerificationScreen(
                    phoneNumber = phoneNumber,
                    dialCode = dialCode,
                    nationalDigits = national,
                    onBack = {
                        finish()
                        AuthAnimationUtils.back(this)
                    },
                    onChangeNumber = {
                        finish()
                        AuthAnimationUtils.back(this)
                    }
                )
            }
        }
    }
}

@Composable
private fun OtpVerificationScreen(
    phoneNumber: String,
    dialCode: String,
    nationalDigits: String,
    onBack: () -> Unit,
    onChangeNumber: () -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current
    var otpCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableIntStateOf(AuthAnimationUtils.OTP_COUNTDOWN_SECONDS) }
    var canResend by remember { mutableStateOf(false) }
    var otpError by remember { mutableStateOf(false) }

    // Countdown timer
    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
        canResend = true
    }

    // Route after success — navigation is now handled by PhoneAuthCoordinator.performSignInAndRoute
    // in the token refresh callback, which ensures the auth token is fresh before
    // MainActivity sets up Firestore listeners. This LaunchedEffect only triggers
    // the success animation; the actual navigation happens in the coordinator.
    LaunchedEffect(showSuccess) {
        if (showSuccess) {
            // Success animation plays here; navigation occurs in coordinator after token refresh
        }
    }

    val maskedPhone = if (nationalDigits.length >= 6) {
        "+$dialCode ${nationalDigits.take(2)}*** ${nationalDigits.takeLast(2)}"
    } else {
        "+$dialCode $nationalDigits"
    }

    AuthScaffold(
        showBackButton = true,
        onBackClick = onBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = "Verify your phone number",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle with masked phone
            Text(
                text = "Enter the 6-digit code sent via SMS to $maskedPhone.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.textSecondary,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(40.dp))

            // OTP Input
            OtpInputField(
                length = 6,
                onCodeComplete = { code ->
                    otpCode = code
                },
                onCodeChanged = { code ->
                    otpCode = code
                    otpError = false
                    errorMessage = null
                },
                error = otpError,
                modifier = Modifier.fillMaxWidth()
            )

            // Error text
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.actionError,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Countdown / Resend
            if (canResend) {
                TextButton(
                    onClick = {
                        canResend = false
                        countdown = AuthAnimationUtils.OTP_COUNTDOWN_SECONDS
                        val token = AuthFlowSession.resendToken
                        if (token != null) {
                            PhoneAuthCoordinator.resendCode(
                                activity = context as android.app.Activity,
                                phoneNumber = phoneNumber,
                                token = token,
                                onCodeSent = { vid, newToken ->
                                    AuthFlowSession.verificationId = vid
                                    AuthFlowSession.resendToken = newToken
                                },
                                onFailed = { e ->
                                    errorMessage = e.localizedMessage ?: "Failed to resend code"
                                }
                            )
                        }
                    },
                    enabled = canResend
                ) {
                    Text(
                        text = "Resend Code",
                        color = if (canResend) theme.textLink else theme.textTertiary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                }
            } else {
                Text(
                    text = "Resend code in ${"%02d".format(countdown / 60)}:${"%02d".format(countdown % 60)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.textTertiary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Change phone number
            TextButton(onClick = onChangeNumber) {
                Text(
                    text = "Change phone number",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Success animation or Verify button
            if (showSuccess) {
                AnimatedCheckmark(
                    modifier = Modifier
                        .size(64.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                )
            } else {
                GlyphButton(
                    text = "Verify",
                    onClick = {
                        val vid = AuthFlowSession.verificationId
                        if (vid != null && otpCode.length == 6) {
                            // Hide keyboard
                            val imm = context.getSystemService(InputMethodManager::class.java)
                            imm?.hideSoftInputFromWindow(
                                (context as android.app.Activity).window?.decorView?.windowToken,
                                0
                            )
                            isVerifying = true
                            errorMessage = null
                            PhoneAuthCoordinator.verifyCode(
                                activity = context as android.app.Activity,
                                verificationId = vid,
                                code = otpCode,
                                onSuccess = {
                                    isVerifying = false
                                    showSuccess = true
                                },
                                onFailed = { e ->
                                    isVerifying = false
                                    otpError = true
                                    errorMessage = e.localizedMessage ?: "Invalid code. Please try again."
                                }
                            )
                        }
                    },
                    enabled = otpCode.length == 6 && !isVerifying,
                    loading = isVerifying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                )
            }
        }
    }

    // Loading overlay during resend
    LoadingOverlay(
        visible = isLoading,
        message = "Verifying..."
    )
}

/**
 * Animated checkmark composable — circle scales in, then stroke draws.
 * Pure Canvas animation, no Lottie dependency.
 */
@Composable
private fun AnimatedCheckmark(modifier: Modifier = Modifier) {
    val theme = glyphTheme
    val circleProgress = remember { Animatable(0f) }
    val checkProgress = remember { Animatable(0f) }
    var showCheck by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        circleProgress.animateTo(
            1f,
            tween(AuthAnimationUtils.CHECKMARK_CIRCLE_MS)
        )
        showCheck = true
        checkProgress.animateTo(
            1f,
            tween(AuthAnimationUtils.CHECKMARK_STROKE_MS)
        )
    }

    Canvas(modifier = modifier) {
        val canvasSize = size.minDimension
        val strokeWidth = canvasSize * 0.08f
        val circleRadius = (canvasSize - strokeWidth) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Background circle
        drawCircle(
            color = theme.actionSuccess.copy(alpha = 0.15f),
            radius = circleRadius,
            center = center
        )

        // Animated circle arc
        drawArc(
            color = theme.actionSuccess,
            startAngle = -90f,
            sweepAngle = 360f * circleProgress.value,
            useCenter = false,
            topLeft = Offset(
                center.x - circleRadius,
                center.y - circleRadius
            ),
            size = Size(circleRadius * 2f, circleRadius * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // Animated checkmark path
        if (showCheck) {
            val checkLength = checkProgress.value
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(
                    center.x - circleRadius * 0.35f,
                    center.y + circleRadius * 0.05f
                )
                lineTo(
                    center.x - circleRadius * 0.05f,
                    center.y + circleRadius * 0.35f
                )
                lineTo(
                    center.x + circleRadius * 0.45f,
                    center.y - circleRadius * 0.2f
                )
            }

            // Draw partial checkmark based on progress
            val pm = androidx.compose.ui.graphics.PathMeasure().apply {
                setPath(path, false)
            }
            val stopLength = pm.length * checkLength

            val partialPath = androidx.compose.ui.graphics.Path()
            pm.getSegment(0f, stopLength, partialPath, true)

            drawPath(
                path = partialPath,
                color = theme.actionSuccess,
                style = Stroke(width = strokeWidth * 1.2f, cap = StrokeCap.Round)
            )
        }
    }
}
