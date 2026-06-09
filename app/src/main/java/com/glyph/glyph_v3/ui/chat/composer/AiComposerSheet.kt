package com.glyph.glyph_v3.ui.chat.composer

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.*
import com.glyph.glyph_v3.ui.chat.translation.TranslationLanguage
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

/**
 * AI Composer Bottom Sheet — Full composable for the AI message enhancement feature.
 *
 * Renders as a modal bottom sheet overlay on top of the chat.
 * Contains: action picker, language selector, tone selector, loading, preview, error states.
 *
 * Performance: Only renders when visible. Uses AnimatedContent for state transitions.
 */
@Composable
fun AiComposerSheet(
    uiState: AiComposerUiState,
    currentText: String,
    onActionSelected: (AiAction) -> Unit,
    onLanguageSelected: (TranslationLanguage) -> Unit,
    onToneSelected: (ToneOption) -> Unit,
    onReplaceText: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val isVisible = uiState !is AiComposerUiState.Hidden

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200)) + slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(tween(150)) + slideOutVertically(
            targetOffsetY = { it / 3 },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clickable(enabled = false) {}, // Block click-through
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                val sheetBackground = if (glyphTheme.gradientAIComposer != null) {
                    glyphTheme.gradientAIComposer!!
                } else {
                    Brush.verticalGradient(
                        colors = listOf(glyphTheme.backgroundElevated, glyphTheme.backgroundElevated)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(sheetBackground)
                        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .border(
                            width = 1.2.dp,
                            color = if (glyphTheme.isDark) Color.White.copy(alpha = 0.2f) 
                                   else Color.Black.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                        )
                ) {
                    AnimatedContent(
                        targetState = uiState,
                        transitionSpec = {
                            (fadeIn(tween(200)) + slideInHorizontally { it / 4 })
                                .togetherWith(fadeOut(tween(150)) + slideOutHorizontally { -it / 4 })
                        },
                        label = "AiComposerContent"
                    ) { state ->
                        when (state) {
                            is AiComposerUiState.Hidden -> {
                                // Should not render, but safety fallback
                                Spacer(modifier = Modifier.height(1.dp))
                            }
                            is AiComposerUiState.ActionPicker -> {
                                ActionPickerContent(
                                    currentText = currentText,
                                    onActionSelected = onActionSelected,
                                    onDismiss = onDismiss
                                )
                            }
                            is AiComposerUiState.LanguageSelector -> {
                                LanguageSelectorContent(
                                    selectedLanguage = state.currentLanguage,
                                    onLanguageSelected = onLanguageSelected,
                                    onBack = onBack
                                )
                            }
                            is AiComposerUiState.ToneSelector -> {
                                ToneSelectorContent(
                                    onToneSelected = onToneSelected,
                                    onBack = onBack
                                )
                            }
                            is AiComposerUiState.Loading -> {
                                LoadingContent(
                                    action = state.action,
                                    onCancel = onBack
                                )
                            }
                            is AiComposerUiState.Preview -> {
                                PreviewContent(
                                    action = state.action,
                                    originalText = state.originalText,
                                    enhancedText = state.enhancedText,
                                    language = state.language,
                                    tone = state.tone,
                                    onReplace = { onReplaceText(state.enhancedText) },
                                    onBack = onBack,
                                    onDismiss = onDismiss
                                )
                            }
                            is AiComposerUiState.Error -> {
                                ErrorContent(
                                    message = state.message,
                                    onRetry = onRetry,
                                    onBack = onBack
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Sheet Header ─────────────────────────────────────────

@Composable
private fun SheetHeader(
    title: String,
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack && onBack != null) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = Color.Transparent,
                contentColor = glyphTheme.iconPrimary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        // AI sparkle emoji + title
        Text(
            text = "✨ $title",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                color = glyphTheme.textPrimary,
                letterSpacing = 0.3.sp
            ),
            modifier = Modifier.weight(1f)
        )

        if (onClose != null) {
            Surface(
                onClick = onClose,
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = if (glyphTheme.isDark) Color.White.copy(alpha = 0.15f) 
                        else Color.Black.copy(alpha = 0.1f),
                contentColor = glyphTheme.textPrimary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ─── Action Picker ────────────────────────────────────────

@Composable
private fun ActionPickerContent(
    currentText: String,
    onActionSelected: (AiAction) -> Unit,
    onDismiss: () -> Unit
) {
    // Wave animation state for each element
    val headerAlpha = remember { Animatable(0f) }
    val headerTranslationY = remember { Animatable(20f) }
    
    val previewAlpha = remember { Animatable(0f) }
    val previewTranslationY = remember { Animatable(30f) }
    val previewScale = remember { Animatable(0.9f) }
    
    // Card animations (4 cards with staggered timing)
    val card1Alpha = remember { Animatable(0f) }
    val card1TranslationY = remember { Animatable(40f) }
    val card1Scale = remember { Animatable(0.92f) }
    
    val card2Alpha = remember { Animatable(0f) }
    val card2TranslationY = remember { Animatable(40f) }
    val card2Scale = remember { Animatable(0.92f) }
    
    val card3Alpha = remember { Animatable(0f) }
    val card3TranslationY = remember { Animatable(40f) }
    val card3Scale = remember { Animatable(0.92f) }
    
    val card4Alpha = remember { Animatable(0f) }
    val card4TranslationY = remember { Animatable(40f) }
    val card4Scale = remember { Animatable(0.92f) }

    // Trigger wave animation on composition
    LaunchedEffect(Unit) {
        val animationSpec = tween<Float>(
            durationMillis = 400,
            easing = FastOutSlowInEasing
        )
        val scaleSpec = tween<Float>(
            durationMillis = 450,
            easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f) // Subtle bounce
        )
        
        // Animate all elements with wave pattern - fire and forget async animations
        // Header (immediate)
        async { headerAlpha.animateTo(1f, tween(300)) }
        async { headerTranslationY.animateTo(0f, animationSpec) }
        
        // Message preview (50ms delay)
        if (currentText.isNotBlank()) {
            delay(50)
            async { previewAlpha.animateTo(1f, animationSpec) }
            async { previewTranslationY.animateTo(0f, animationSpec) }
            async { previewScale.animateTo(1f, scaleSpec) }
        }
        
        // Cards with diagonal wave pattern
        delay(if (currentText.isNotBlank()) 100 else 80)
        
        // Card 1 - top left
        async { card1Alpha.animateTo(1f, animationSpec) }
        async { card1TranslationY.animateTo(0f, animationSpec) }
        async { card1Scale.animateTo(1f, scaleSpec) }
        
        delay(60)
        
        // Card 2 - top right
        async { card2Alpha.animateTo(1f, animationSpec) }
        async { card2TranslationY.animateTo(0f, animationSpec) }
        async { card2Scale.animateTo(1f, scaleSpec) }
        
        delay(60)
        
        // Card 3 - bottom left
        async { card3Alpha.animateTo(1f, animationSpec) }
        async { card3TranslationY.animateTo(0f, animationSpec) }
        async { card3Scale.animateTo(1f, scaleSpec) }
        
        delay(60)
        
        // Card 4 - bottom right
        async { card4Alpha.animateTo(1f, animationSpec) }
        async { card4TranslationY.animateTo(0f, animationSpec) }
        async { card4Scale.animateTo(1f, scaleSpec) }
    }

    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        Box(
            modifier = Modifier.graphicsLayer {
                alpha = headerAlpha.value
                translationY = headerTranslationY.value
            }
        ) {
            SheetHeader(title = "AI Composer", onClose = onDismiss)
        }

        // Show message preview
        if (currentText.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .graphicsLayer {
                        alpha = previewAlpha.value
                        translationY = previewTranslationY.value
                        scaleX = previewScale.value
                        scaleY = previewScale.value
                    },
                shape = RoundedCornerShape(12.dp),
                color = glyphTheme.backgroundSecondary.copy(alpha = 0.8f),
                border = androidx.compose.foundation.BorderStroke(
                    1.2.dp, 
                    glyphTheme.borderSecondary.copy(alpha = 0.4f)
                )
            ) {
                Text(
                    text = currentText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = glyphTheme.textPrimary
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Action buttons grid (2x2)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionCard(
                    action = AiAction.ENHANCE,
                    accentColor = Color(0xFFA78BFA), // Vibrant Lavender
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = card1Alpha.value
                            translationY = card1TranslationY.value
                            scaleX = card1Scale.value
                            scaleY = card1Scale.value
                        },
                    onClick = { onActionSelected(AiAction.ENHANCE) }
                )
                ActionCard(
                    action = AiAction.GRAMMAR,
                    accentColor = Color(0xFF34D399), // Vibrant Mint
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = card2Alpha.value
                            translationY = card2TranslationY.value
                            scaleX = card2Scale.value
                            scaleY = card2Scale.value
                        },
                    onClick = { onActionSelected(AiAction.GRAMMAR) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionCard(
                    action = AiAction.TRANSLATE,
                    accentColor = Color(0xFF60A5FA), // Vibrant Sky
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = card3Alpha.value
                            translationY = card3TranslationY.value
                            scaleX = card3Scale.value
                            scaleY = card3Scale.value
                        },
                    onClick = { onActionSelected(AiAction.TRANSLATE) }
                )
                ActionCard(
                    action = AiAction.TONE,
                    accentColor = Color(0xFFFBBF24), // Vibrant Amber
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = card4Alpha.value
                            translationY = card4TranslationY.value
                            scaleX = card4Scale.value
                            scaleY = card4Scale.value
                        },
                    onClick = { onActionSelected(AiAction.TONE) }
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    action: AiAction,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = glyphTheme.isDark
    
    Surface(
        onClick = onClick,
        modifier = modifier.height(118.dp),
        shape = RoundedCornerShape(20.dp),
        color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.6f),
        border = BorderStroke(
            width = 1.2.dp,
            color = accentColor.copy(alpha = if (isDark) 0.5f else 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon wrapper with more visible glow
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accentColor.copy(alpha = if (isDark) 0.18f else 0.22f), CircleShape)
                    .border(1.2.dp, accentColor.copy(alpha = if (isDark) 0.3f else 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = action.icon,
                    fontSize = 20.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = action.displayName,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = glyphTheme.textPrimary,
                    letterSpacing = 0.1.sp
                )
            )
            
            Text(
                text = action.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = glyphTheme.textSecondary.copy(alpha = if (isDark) 0.9f else 0.85f),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─── Language Selector ────────────────────────────────────

@Composable
private fun LanguageSelectorContent(
    selectedLanguage: TranslationLanguage,
    onLanguageSelected: (TranslationLanguage) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val allLanguages = remember { TranslationLanguage.entries.toList() }
    val filteredLanguages = remember(searchQuery) {
        if (searchQuery.isBlank()) allLanguages
        else allLanguages.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.nativeName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 450.dp)
    ) {
        SheetHeader(title = "Translate To", showBack = true, onBack = onBack)

        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    "Search language...",
                    fontSize = 14.sp,
                    color = glyphTheme.textPlaceholder
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = glyphTheme.borderInput,
                unfocusedBorderColor = glyphTheme.borderInput.copy(alpha = 0.5f),
                cursorColor = glyphTheme.cursorColor,
                focusedTextColor = glyphTheme.textPrimary,
                unfocusedTextColor = glyphTheme.textPrimary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            items(filteredLanguages) { language ->
                LanguageItem(
                    language = language,
                    isSelected = language == selectedLanguage,
                    onClick = { onLanguageSelected(language) }
                )
            }
        }
    }
}

@Composable
private fun LanguageItem(
    language: TranslationLanguage,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) Color(0xFF8B5CF6).copy(alpha = 0.12f) else Color.Transparent,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            1.dp,
            Color(0xFF8B5CF6).copy(alpha = 0.3f)
        ) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = language.flag, fontSize = 20.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = language.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = glyphTheme.textPrimary
                    )
                )
                if (language.nativeName != language.displayName) {
                    Text(
                        text = language.nativeName,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = glyphTheme.textSecondary
                        )
                    )
                }
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color(0xFF8B5CF6).copy(alpha = 0.2f), CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.Center)
                            .background(Color(0xFF8B5CF6), CircleShape)
                    )
                }
            }
        }
    }
}

// ─── Tone Selector ────────────────────────────────────────

@Composable
private fun ToneSelectorContent(
    onToneSelected: (ToneOption) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        SheetHeader(title = "Choose Tone", showBack = true, onBack = onBack)

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ToneOption.entries.forEach { tone ->
                ToneCard(
                    tone = tone,
                    onClick = { onToneSelected(tone) }
                )
            }
        }
    }
}

@Composable
private fun ToneCard(
    tone: ToneOption,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = tone.emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = tone.displayName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = glyphTheme.textPrimary
                )
            )
        }
    }
}

// ─── Loading ──────────────────────────────────────────────

@Composable
private fun LoadingContent(
    action: AiAction,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SheetHeader(title = action.displayName, showBack = true, onBack = onCancel)

        Spacer(modifier = Modifier.height(4.dp))

        // Lottie Robot Animation
        val composition by rememberLottieComposition(LottieCompositionSpec.Asset("robot.lottie"))
        val progress by animateLottieCompositionAsState(
            composition = composition,
            iterations = LottieConstants.IterateForever
        )

        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = Modifier.size(240.dp)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Glyph AI is working on your message...",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = glyphTheme.textSecondary
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        LinearProgressIndicator(
            modifier = Modifier
                .width(140.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Color(0xFFA78BFA),
            trackColor = Color.White.copy(alpha = 0.1f)
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─── Preview ──────────────────────────────────────────────

@Composable
private fun PreviewContent(
    action: AiAction,
    originalText: String,
    enhancedText: String,
    language: TranslationLanguage?,
    tone: ToneOption?,
    onReplace: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val subtitle = when {
        language != null -> "${action.displayName} → ${language.flag} ${language.displayName}"
        tone != null -> "${action.displayName} → ${tone.emoji} ${tone.displayName}"
        else -> action.displayName
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        SheetHeader(title = subtitle, showBack = true, onBack = onBack, onClose = onDismiss)

        // Original text
        TextComparisonCard(
            label = "ORIGINAL",
            text = originalText,
            color = if (glyphTheme.isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.04f),
            borderColor = glyphTheme.borderSecondary.copy(alpha = 0.3f)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Enhanced text
        TextComparisonCard(
            label = "ENHANCED",
            text = enhancedText,
            color = Color(0xFFA78BFA).copy(alpha = if (glyphTheme.isDark) 0.18f else 0.14f),
            borderColor = Color(0xFFA78BFA).copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Cancel
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = glyphTheme.textSecondary
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, glyphTheme.borderInput)
            ) {
                Text("Cancel")
            }

            // Replace
            Button(
                onClick = onReplace,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFA78BFA),
                    contentColor = Color.White
                )
            ) {
                Text("Replace", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TextComparisonCard(
    label: String,
    text: String,
    color: Color,
    borderColor: Color? = null
) {
    val isDark = glyphTheme.isDark
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                color = if (label == "ENHANCED") Color(0xFFA78BFA) else glyphTheme.textSecondary,
                letterSpacing = 1.2.sp
            ),
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (borderColor != null) Modifier.border(
                        BorderStroke(1.5.dp, borderColor),
                        RoundedCornerShape(18.dp)
                    ) else Modifier
                ),
            shape = RoundedCornerShape(18.dp),
            color = color
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = glyphTheme.textPrimary,
                    lineHeight = 22.sp,
                    fontWeight = if (label == "ENHANCED") FontWeight.Medium else FontWeight.Normal
                ),
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

// ─── Error ────────────────────────────────────────────────

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SheetHeader(title = "Error", showBack = true, onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "😔", fontSize = 36.sp)

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = glyphTheme.textSecondary,
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFA78BFA),
                contentColor = Color.White
            )
        ) {
            Text("Try Again", fontWeight = FontWeight.SemiBold)
        }
    }
}
