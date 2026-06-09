package com.glyph.glyph_v3.ui.chat.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.chat.translation.TranslationLanguage
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * State for the voice recording options sheet.
 */
sealed class VoiceOptionState {
    /** Sheet is hidden */
    data object Hidden : VoiceOptionState()
    
    /** Showing the two options: Send Voice Note / Convert to Text */
    data class ShowingOptions(
        val durationText: String,
        val selectedLanguage: TranslationLanguage = TranslationLanguage.DEFAULT
    ) : VoiceOptionState()
    
    /** Processing speech-to-text */
    data object Processing : VoiceOptionState()
    
    /** STT completed - showing result preview */
    data class ResultPreview(
        val recognizedText: String,
        val translatedText: String?,
        val targetLanguage: String?,
        val selectedLanguage: TranslationLanguage = TranslationLanguage.DEFAULT
    ) : VoiceOptionState()
    
    /** Error occurred */
    data class Error(val message: String) : VoiceOptionState()
}

/**
 * Full-screen overlay that shows after voice recording completes.
 * Presents two options: Send as Voice Note or Convert to Text & Translate.
 *
 * Architecture:
 * - Rendered as overlay above chat content (same pattern as VoiceRecorderOverlay)
 * - Self-contained state management
 * - Smooth animations for transitions between states
 */
@Composable
fun VoiceRecordingOptionsSheet(
    state: VoiceOptionState,
    onSendVoiceNote: () -> Unit,
    onConvertToText: () -> Unit,
    onLanguageChanged: (TranslationLanguage) -> Unit,
    onRetranslate: (TranslationLanguage) -> Unit,
    onUseTranslatedText: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state != VoiceOptionState.Hidden,
        enter = fadeIn(animationSpec = tween(250)) +
                slideInVertically(initialOffsetY = { it / 3 }, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(animationSpec = tween(200)) +
               slideOutVertically(targetOffsetY = { it / 3 }),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { /* Consume clicks on background */ },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = glyphTheme.backgroundElevated
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                when (val currentState = state) {
                    is VoiceOptionState.ShowingOptions -> OptionsContent(
                        durationText = currentState.durationText,
                        selectedLanguage = currentState.selectedLanguage,
                        onSendVoiceNote = onSendVoiceNote,
                        onConvertToText = onConvertToText,
                        onLanguageChanged = onLanguageChanged,
                        onDismiss = onDismiss
                    )
                    is VoiceOptionState.Processing -> ProcessingContent()
                    is VoiceOptionState.ResultPreview -> ResultContent(
                        recognizedText = currentState.recognizedText,
                        translatedText = currentState.translatedText,
                        targetLanguage = currentState.targetLanguage,
                        selectedLanguage = currentState.selectedLanguage,
                        onUseText = { text -> onUseTranslatedText(text) },
                        onRetranslate = onRetranslate,
                        onDismiss = onDismiss
                    )
                    is VoiceOptionState.Error -> ErrorContent(
                        message = currentState.message,
                        onRetry = onConvertToText,
                        onSendVoiceNote = onSendVoiceNote,
                        onDismiss = onDismiss
                    )
                    else -> { /* Hidden - should not be rendered */ }
                }
            }
        }
    }
}

/**
 * Options content: Send Voice Note or Convert to Text & Translate
 */
@Composable
private fun OptionsContent(
    durationText: String,
    selectedLanguage: TranslationLanguage,
    onSendVoiceNote: () -> Unit,
    onConvertToText: () -> Unit,
    onLanguageChanged: (TranslationLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Voice Recording",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = glyphTheme.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Duration
        Text(
            text = "Duration: $durationText",
            style = MaterialTheme.typography.bodyMedium,
            color = glyphTheme.textSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Language selector
        LanguageSelectorRow(
            selectedLanguage = selectedLanguage,
            expanded = showLanguagePicker,
            onToggle = { showLanguagePicker = !showLanguagePicker },
            onLanguageSelected = { lang ->
                onLanguageChanged(lang)
                showLanguagePicker = false
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Option 1: Send as Voice Note
        OptionButton(
            icon = Icons.Default.Mic,
            title = "Send as Voice Note",
            subtitle = "Send the recording as an audio message",
            accentColor = glyphTheme.actionPrimary,
            onClick = onSendVoiceNote
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Option 2: Convert to Text & Translate
        OptionButton(
            icon = Icons.Default.Translate,
            title = "Convert to Text & Translate",
            subtitle = "Transcribe speech and translate to ${selectedLanguage.displayName}",
            accentColor = Color(0xFF7C4DFF), // Purple accent for translation
            onClick = onConvertToText
        )
    }
}

/**
 * Single option button with icon, title, and subtitle
 */
@Composable
private fun OptionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = accentColor.copy(alpha = 0.1f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Icon circle
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(accentColor, accentColor.copy(alpha = 0.7f))
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = glyphTheme.textPrimary
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = glyphTheme.textSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * Processing state: Loading spinner with message
 */
@Composable
private fun ProcessingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Animated dots indicator
        val infiniteTransition = rememberInfiniteTransition(label = "processing")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            color = Color(0xFF7C4DFF),
            strokeWidth = 4.dp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Converting speech to text...",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = glyphTheme.textPrimary.copy(alpha = alpha)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This may take a few seconds",
            style = MaterialTheme.typography.bodySmall,
            color = glyphTheme.textSecondary
        )
    }
}

/**
 * Result preview: Shows recognized and translated text
 */
@Composable
private fun ResultContent(
    recognizedText: String,
    translatedText: String?,
    targetLanguage: String?,
    selectedLanguage: TranslationLanguage,
    onUseText: (String) -> Unit,
    onRetranslate: (TranslationLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    var showLanguagePicker by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Speech Recognized",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = glyphTheme.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Language selector — allows changing language and re-translating
        LanguageSelectorRow(
            selectedLanguage = selectedLanguage,
            expanded = showLanguagePicker,
            onToggle = { showLanguagePicker = !showLanguagePicker },
            onLanguageSelected = { lang ->
                showLanguagePicker = false
                onRetranslate(lang)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Recognized text
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = glyphTheme.surfaceInput.copy(alpha = 0.5f)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Original",
                    style = MaterialTheme.typography.labelSmall,
                    color = glyphTheme.textSecondary,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = recognizedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = glyphTheme.textPrimary
                )
            }
        }

        // Translated text (if available)
        if (!translatedText.isNullOrBlank() && translatedText != recognizedText) {
            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF7C4DFF).copy(alpha = 0.08f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Translated${if (targetLanguage != null) " ($targetLanguage)" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF7C4DFF),
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = translatedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = glyphTheme.textPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Use original text
            OutlinedButton(
                onClick = { onUseText(recognizedText) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = glyphTheme.textPrimary
                )
            ) {
                Text("Use Original", fontSize = 13.sp)
            }

            // Use translated text (primary action)
            if (!translatedText.isNullOrBlank() && translatedText != recognizedText) {
                Button(
                    onClick = { onUseText(translatedText) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C4DFF)
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Use Translation", fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Error content: Shows error with retry and fallback options
 */
@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onSendVoiceNote: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Conversion Failed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = glyphTheme.textPrimary
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Error message
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = glyphTheme.textPrimary,
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Send as voice note (fallback)
            OutlinedButton(
                onClick = onSendVoiceNote,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Send Voice", fontSize = 13.sp)
            }

            // Retry
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7C4DFF)
                )
            ) {
                Text("Try Again", fontSize = 13.sp)
            }
        }
    }
}

// ─── Language Selector Components ─────────────────────────

/**
 * Compact language selector row with expandable picker.
 * Shows current language as a chip; tapping expands to a searchable grid.
 */
@Composable
private fun LanguageSelectorRow(
    selectedLanguage: TranslationLanguage,
    expanded: Boolean,
    onToggle: () -> Unit,
    onLanguageSelected: (TranslationLanguage) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Language chip
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF7C4DFF).copy(alpha = 0.08f),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = Color(0xFF7C4DFF),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Translate to:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = glyphTheme.textSecondary
                )
                Text(
                    text = "${selectedLanguage.flag} ${selectedLanguage.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7C4DFF),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Change language",
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Expandable language picker
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
        ) {
            LanguagePickerGrid(
                selectedLanguage = selectedLanguage,
                onLanguageSelected = onLanguageSelected
            )
        }
    }
}

/**
 * Searchable language grid inside the options sheet.
 */
@Composable
private fun LanguagePickerGrid(
    selectedLanguage: TranslationLanguage,
    onLanguageSelected: (TranslationLanguage) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val allLanguages = remember { TranslationLanguage.entries.toList() }
    val filteredLanguages = remember(searchQuery) {
        if (searchQuery.isBlank()) allLanguages
        else allLanguages.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.nativeName.contains(searchQuery, ignoreCase = true) ||
            it.code.equals(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    "Search languages...",
                    style = MaterialTheme.typography.bodySmall,
                    color = glyphTheme.textSecondary
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = glyphTheme.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF7C4DFF),
                unfocusedBorderColor = glyphTheme.textSecondary.copy(alpha = 0.3f),
                cursorColor = Color(0xFF7C4DFF)
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Language list (scrollable, max height)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
        ) {
            filteredLanguages.forEach { lang ->
                val isSelected = lang == selectedLanguage
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onLanguageSelected(lang) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0xFF7C4DFF).copy(alpha = 0.12f) else Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = lang.flag,
                            fontSize = 18.sp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) Color(0xFF7C4DFF) else glyphTheme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = lang.nativeName,
                                style = MaterialTheme.typography.labelSmall,
                                color = glyphTheme.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color(0xFF7C4DFF),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            
            if (filteredLanguages.isEmpty()) {
                Text(
                    text = "No languages found",
                    style = MaterialTheme.typography.bodySmall,
                    color = glyphTheme.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
