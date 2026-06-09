package com.glyph.glyph_v3.ui.chatlist

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.launch

class CreateSecretCodeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                CreateSecretCodeFlow(
                    onBackClick = { finish() },
                    onComplete = {
                        Toast.makeText(
                            this,
                            "Locked chats are hidden and your secret code is set.",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSecretCodeFlow(
    onBackClick: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Two-step flow: 0 = Create, 1 = Confirm
    var step by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var confirmCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val surfaceBg = glyphTheme.backgroundPrimary
    val textPrimary = glyphTheme.textPrimary
    val textSecondary = glyphTheme.textSecondary
    val accentGreen = Color(0xFF00A884)
    val dividerColor = glyphTheme.bubbleBorder

    val title = if (step == 0) "Create secret code" else "Confirm secret code"
    val buttonText = if (step == 0) "Next" else "Done"
    val currentValue = if (step == 0) code else confirmCode
    val isButtonEnabled = currentValue.isNotEmpty()

    Scaffold(
        containerColor = surfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step == 1) {
                            step = 0
                            confirmCode = ""
                            error = null
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "Back",
                            tint = glyphTheme.iconPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceBg,
                    titleContentColor = textPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

            Spacer(modifier = Modifier.height(24.dp))

            // Description
            Text(
                text = "A secret code lets you find locked chats in the search bar and open them on some linked devices.",
                color = textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Input field
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current

            OutlinedTextField(
                value = currentValue,
                onValueChange = { newVal ->
                    error = null
                    if (step == 0) code = newVal else confirmCode = newVal
                },
                label = { Text("Secret code") },
                placeholder = null,
                singleLine = true,
                isError = error != null,
                supportingText = {
                    if (error != null) {
                        Text(text = error!!, color = MaterialTheme.colorScheme.error)
                    } else if (step == 0) {
                        Text(
                            text = "Use a word or emoji, but make it memorable.",
                            color = textSecondary
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentGreen,
                    unfocusedBorderColor = glyphTheme.bubbleBorder,
                    focusedLabelColor = accentGreen,
                    unfocusedLabelColor = textSecondary,
                    cursorColor = accentGreen,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    keyboardController?.hide()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .focusRequester(focusRequester)
            )

            LaunchedEffect(step) {
                focusRequester.requestFocus()
            }

            Spacer(modifier = Modifier.weight(1f))

            // Action button
            Button(
                onClick = {
                    if (step == 0) {
                        if (code.isEmpty()) {
                            error = "Please enter a secret code"
                        } else {
                            step = 1
                        }
                    } else {
                        if (confirmCode != code) {
                            error = "Codes don't match. Try again."
                            confirmCode = ""
                        } else {
                            scope.launch {
                                ChatSettingsDataStore.setSecretCode(context, code)
                                onComplete()
                            }
                        }
                    }
                },
                enabled = isButtonEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentGreen,
                    disabledContainerColor = Color(0xFF1F2C34)
                )
            ) {
                Text(
                    text = buttonText,
                    color = if (isButtonEnabled) Color.White else textSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
