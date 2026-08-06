package com.glyph.glyph_v3.ui.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.ui.auth.components.AuthScaffold
import com.glyph.glyph_v3.ui.auth.components.AvatarPicker
import com.glyph.glyph_v3.ui.auth.components.GlyphButton
import com.glyph.glyph_v3.ui.auth.components.LoadingOverlay
import com.glyph.glyph_v3.ui.base.ThemedActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Screen 4: Profile Setup (redesigned).
 *
 * Replaces the old ViewBinding-based [com.glyph.glyph_v3.ui.login.SetupProfileActivity].
 * Uses the same [FirebaseRepository.saveUserProfile] call path with unchanged logic.
 */
class SetupProfileActivity : ThemedActivity() {

    private val repository = FirebaseRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val phoneNumber = intent.getStringExtra("phone_number")
        if (phoneNumber == null) {
            Toast.makeText(this, "Error: Phone number not provided.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContent {
            GlyphThemeProvider {
                SetupProfileScreen(
                    phoneNumber = phoneNumber,
                    repository = repository,
                    onNavigateToMain = { navigateToMain() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun navigateToMain() {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            action = intent.action
            type = intent.type
            clipData = intent.clipData
            putExtras(intent)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(mainIntent)
        finish()
    }
}

@Composable
private fun SetupProfileScreen(
    phoneNumber: String,
    repository: FirebaseRepository,
    onNavigateToMain: () -> Unit,
    onBack: () -> Unit
) {
    val theme = glyphTheme
    val scrollState = rememberScrollState()

    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            imageUri = uri
        }
    }

    val isNameValid = displayName.trim().length >= 2

    AuthScaffold(
        showBackButton = true,
        onBackClick = onBack
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Avatar picker
            AvatarPicker(
                imageUri = imageUri,
                onPickImage = { imagePickerLauncher.launch("image/*") },
                size = 120
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Add a profile photo",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textSecondary
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Display name
            OutlinedTextField(
                value = displayName,
                onValueChange = {
                    displayName = it
                    nameError = null
                },
                label = { Text("Display Name") },
                supportingText = nameError?.let { error ->
                    { Text(error, color = theme.actionError) }
                },
                trailingIcon = {
                    Text(
                        text = "${displayName.trim().length}/30",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.textTertiary
                    )
                },
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.borderFocus,
                    unfocusedBorderColor = theme.borderInput,
                    focusedContainerColor = theme.surfaceInput,
                    unfocusedContainerColor = theme.surfaceInput,
                    cursorColor = theme.cursorColor,
                    focusedTextColor = theme.textPrimary,
                    unfocusedTextColor = theme.textPrimary,
                    focusedLabelColor = theme.borderFocus,
                    unfocusedLabelColor = theme.textSecondary,
                    errorBorderColor = theme.actionError,
                    errorLabelColor = theme.actionError
                ),
                isError = nameError != null,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bio (optional)
            OutlinedTextField(
                value = bio,
                onValueChange = { if (it.length <= 150) bio = it },
                label = { Text("Bio (optional)") },
                supportingText = {
                    Text(
                        text = "${bio.length}/150",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.textTertiary
                    )
                },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = theme.borderFocus,
                    unfocusedBorderColor = theme.borderInput,
                    focusedContainerColor = theme.surfaceInput,
                    unfocusedContainerColor = theme.surfaceInput,
                    cursorColor = theme.cursorColor,
                    focusedTextColor = theme.textPrimary,
                    unfocusedTextColor = theme.textPrimary,
                    focusedLabelColor = theme.borderFocus,
                    unfocusedLabelColor = theme.textSecondary
                )
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Continue button
            GlyphButton(
                text = "Continue",
                onClick = {
                    val name = displayName.trim()
                    if (name.length < 2) {
                        nameError = "Name must be at least 2 characters"
                        return@GlyphButton
                    }
                    isLoading = true
                    val finalBio = bio.trim().ifEmpty { "Hey there! I am using Glyph." }
                    repository.saveUserProfile(
                        username = name,
                        phone = phoneNumber,
                        bio = finalBio,
                        imageUri = imageUri,
                        onSuccess = {
                            Handler(Looper.getMainLooper()).post {
                                isLoading = false
                                onNavigateToMain()
                            }
                        },
                        onFailure = { e ->
                            Handler(Looper.getMainLooper()).post {
                                isLoading = false
                                nameError = "Failed to save profile: ${e.message}"
                            }
                        }
                    )
                },
                enabled = isNameValid && !isLoading,
                loading = isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            )
        }
    }

    // Loading overlay
    LoadingOverlay(
        visible = isLoading,
        message = "Creating your profile..."
    )
}
