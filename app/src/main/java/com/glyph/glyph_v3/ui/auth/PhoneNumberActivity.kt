package com.glyph.glyph_v3.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.auth.components.AuthScaffold
import com.glyph.glyph_v3.ui.auth.components.ConfirmationDialog
import com.glyph.glyph_v3.ui.auth.components.CountryPickerSheet
import com.glyph.glyph_v3.ui.auth.components.GlyphButton
import com.glyph.glyph_v3.ui.auth.components.LoadingOverlay
import com.glyph.glyph_v3.ui.auth.components.PhoneInputField
import com.glyph.glyph_v3.ui.auth.data.CountryData
import com.glyph.glyph_v3.ui.auth.models.Country
import com.glyph.glyph_v3.ui.base.ThemedActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import java.util.Locale

/**
 * Screen 2: Phone Number Input.
 *
 * Country selector with bottom sheet, phone number input with dial code prefix,
 * and a bottom-right floating Next button. On valid number, shows a confirmation
 * dialog then starts Firebase phone verification.
 */
class PhoneNumberActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val defaultIso = detectSimCountry()
        setContent {
            GlyphThemeProvider {
                PhoneNumberScreen(
                    defaultCountry = CountryData.detectDefault(defaultIso),
                    onNavigateToOtp = { phoneNumber, dialCode, national ->
                        AuthFlowSession.phoneNumber = phoneNumber
                        AuthFlowSession.dialCode = dialCode
                        AuthFlowSession.nationalDigits = national
                        startActivity(Intent(this, OtpVerificationActivity::class.java))
                        AuthAnimationUtils.forward(this)
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun detectSimCountry(): String? {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.simCountryIso?.uppercase()?.takeIf { it.length == 2 }
                ?: Locale.getDefault().country.uppercase().takeIf { it.length == 2 }
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
private fun PhoneNumberScreen(
    defaultCountry: Country,
    onNavigateToOtp: (phoneNumber: String, dialCode: String, national: String) -> Unit,
    onBack: () -> Unit
) {
    val theme = glyphTheme
    var selectedCountry by remember { mutableStateOf(defaultCountry) }
    var phoneValue by remember { mutableStateOf(TextFieldValue("")) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showConfirmation by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // Check validity
    val nationalDigits = phoneValue.text.filter { it.isDigit() }
    val isValid = nationalDigits.length in 7..12

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
                text = "Enter your phone number",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            Text(
                text = "We'll verify your phone number using SMS.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.textSecondary,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Country selector row
            CountrySelectorRow(
                country = selectedCountry,
                onClick = { showCountryPicker = true }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Phone number input
            PhoneInputField(
                dialCode = selectedCountry.callingCode,
                value = phoneValue,
                onValueChange = {
                    phoneValue = it
                    errorMessage = null
                },
                error = errorMessage != null,
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

            Spacer(modifier = Modifier.weight(0.3f))

            // Privacy note
            Text(
                text = "Carrier charges may apply.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Floating Next button (bottom right)
            GlyphButton(
                text = "",
                icon = Icons.Default.ArrowForward,
                onClick = {
                    if (isValid) {
                        showConfirmation = true
                    }
                },
                enabled = isValid,
                fullWidth = false,
                circular = true,
                modifier = Modifier
                    .align(Alignment.End)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            )
        }
    }

    // Country picker bottom sheet
    if (showCountryPicker) {
        CountryPickerSheet(
            selectedCountry = selectedCountry,
            onCountrySelected = { country ->
                selectedCountry = country
                showCountryPicker = false
            },
            onDismiss = { showCountryPicker = false }
        )
    }

    // Confirmation dialog
    if (showConfirmation) {
        val formattedPhone = "+${selectedCountry.callingCode} ${phoneValue.text}"
        ConfirmationDialog(
            title = "Confirm your number",
            message = "Is this the correct number?\n\n$formattedPhone\n\nWe'll send an SMS to verify your phone number. Carrier charges may apply.",
            confirmText = "Continue",
            dismissText = "Edit",
            onConfirm = {
                showConfirmation = false
                isLoading = true
                val e164 = "+${selectedCountry.callingCode}$nationalDigits"
                PhoneAuthCoordinator.startVerification(
                    activity = context as android.app.Activity,
                    phoneNumber = e164,
                    onCodeSent = { verificationId, token ->
                        isLoading = false
                        AuthFlowSession.verificationId = verificationId
                        AuthFlowSession.resendToken = token
                        onNavigateToOtp(e164, selectedCountry.callingCode, nationalDigits)
                    },
                    onFailed = { e ->
                        isLoading = false
                        errorMessage = "Verification failed: ${e.localizedMessage ?: "Please try again."}"
                    },
                    onAutoCompleted = { /* handled by coordinator, will route */ }
                )
            },
            onDismiss = { showConfirmation = false }
        )
    }

    // Loading overlay
    LoadingOverlay(
        visible = isLoading,
        message = "Sending verification code..."
    )
}

@Composable
private fun CountrySelectorRow(
    country: Country,
    onClick: () -> Unit
) {
    val theme = glyphTheme
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.surfaceInput, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .border(1.dp, theme.borderInput, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = country.flagEmoji,
            fontSize = 22.sp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = country.name,
            style = MaterialTheme.typography.bodyLarge,
            color = theme.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "+${country.callingCode}",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.textSecondary,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "▾",
            fontSize = 14.sp,
            color = theme.textTertiary
        )
    }
}
