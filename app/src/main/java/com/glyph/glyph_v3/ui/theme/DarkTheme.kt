package com.glyph.glyph_v3.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 🌙 Dark Theme - True Dark Mode Implementation
 * 
 * Complete implementation of GlyphThemeTokens for dark mode.
 * Optimized for OLED with deep blacks and subtle highlights.
 */
@Immutable
object DarkThemeTokens : GlyphThemeTokens {
    
    // Base color palette
    private val DarkBackground = Color(0xFF01090C)
    private val DarkSurface = Color(0xFF0B1014)
    private val DarkToolbar = Color(0xFF1F2C34)
    private val DarkPrimary = Color(0xFF1DB954)
    private val DarkOnPrimary = Color(0xFF000000)
    private val DarkText = Color(0xFFE6EEF1)
    private val DarkTextSecondary = Color(0xFF9EA7AC)
    private val DarkTextTertiary = Color(0xFF8E8E93)
    private val DarkBorder = Color(0xFF1A2328) // Subtle dark blue-gray border
    private val DarkSurfaceVariant = Color(0xFF0D2422)
    
    // Modern AI Composer colors — Deep Indigo/Space theme
    private val AIGradientStart = Color(0xFF1A1625) // Richer Deep Indigo
    private val AIGradientEnd = Color(0xFF0B0D12)   // Midnight Grey
    private val AIAccentPurple = Color(0xFFA78BFA) // Vibrant Lavender
    private val AIAccentBlue = Color(0xFF60A5FA)   // Vibrant Sky Blue
    
    // ========== SURFACES & BACKGROUNDS ==========
    override val backgroundPrimary = DarkBackground
    override val backgroundSecondary = DarkSurface
    override val backgroundElevated = Color(0xFF141B24)
    override val backgroundTinted = Color(0xFF1A2D2D)
    override val backgroundWarm = Color(0xFF2A2520)
    override val surfaceChat = DarkBackground
    override val surfaceHeader = Color(0xFF0B1014) // matches dark_surface in colors.xml
    override val surfaceNavigation = DarkToolbar
    override val surfaceInput = DarkSurface
    override val surfaceOverlay = Color.Black.copy(alpha = 0.8f)
    
    // ========== NAVIGATION ==========
    override val navIconActive = Color(0xFFD8FDD2)
    override val navIconInactive = Color.White
    override val navTextActive = Color.White
    override val navTextInactive = Color.White
    override val navActiveIndicator = Color(0xFF103629)
    
    override val backgroundGradient: Brush? = null
    
    // ========== TEXT & TYPOGRAPHY ==========
    override val textPrimary = DarkText
    override val textSecondary = DarkTextSecondary
    override val textTertiary = DarkTextTertiary
    override val textInverse = Color(0xFF1F2937)
    override val textLink = Color(0xFF0EA5E9)
    override val textMention = Color(0xFFB39DDB)
    override val textPlaceholder = DarkTextTertiary
    
    // ========== ACTIONS & INTERACTIVITY ==========
    override val actionPrimary = DarkPrimary
    override val actionSecondary = DarkSurface
    override val actionDestructive = Color(0xFFDC2626)
    override val actionSuccess = Color(0xFF25D366)
    override val actionWarning = Color(0xFFF59E0B)
    override val actionError = Color(0xFFDC2626)
    override val actionPressed = Color.White.copy(alpha = 0.1f)
    override val actionHover = Color.White.copy(alpha = 0.05f)
    override val actionRipple = Color(0x2025D366)
    
    // ========== CHAT BUBBLES ==========
    override val bubbleOutgoingBackground = Color(0xFF005C4B)
    override val bubbleOutgoingText = DarkText
    override val bubbleIncomingBackground = DarkToolbar
    override val bubbleIncomingText = DarkText
    override val bubbleTimestamp = DarkTextSecondary
    override val bubbleBorder = DarkBorder.copy(alpha = 0.6f) // Subtle, barely visible
    
    // ========== ICONS & INDICATORS ==========
    override val iconPrimary = DarkText
    override val iconSecondary = DarkTextSecondary
    override val iconTertiary = DarkTextTertiary
    override val indicatorOnline = GlyphThemeTokens.PremiumGreen
    override val indicatorTyping = Color(0xFF0EA5E9)
    override val indicatorUnreadBackground = GlyphThemeTokens.PremiumGreen
    override val indicatorUnreadText = DarkOnPrimary
    override val indicatorMessageStatus = Color(0xFF4FC3F7)
    
    // ========== BORDERS & DIVIDERS ==========
    override val borderPrimary = DarkBorder
    override val borderSecondary = DarkBorder
    override val borderInput = DarkSurfaceVariant
    override val borderFocus = DarkPrimary
    override val divider = DarkBorder
    
    // ========== SPECIAL COMPONENTS ==========
    override val dateHeaderBackground = Color(0xFF182229)
    override val dateHeaderText = DarkTextSecondary
    override val sendButtonBackground = DarkPrimary
    override val sendButtonIcon = DarkSurface
    override val attachmentIcon = Color.White
    override val emojiIcon = Color.White
    override val aiIcon = Color.White
    override val headerIcon = DarkText
    override val cursorColor = DarkPrimary
    override val selectionBackground = Color(0x4025D366)
    override val selectionOverlay = Color(0x2025D366)
    override val avatarPlaceholder = Color(0xFF2D3B41)
    override val imagePlaceholder = Color(0xFF2D2D2D)
    
    // ========== GRADIENTS ==========
    override val gradientPrimary: Brush? = null
    override val gradientHeader: Brush? = null
    override val gradientInput: Brush = Brush.verticalGradient(
        colors = listOf(AIGradientStart, AIGradientEnd),
        startY = 0f,
        endY = Float.POSITIVE_INFINITY
    )
    override val gradientAIComposer: Brush = gradientInput
    override val gradientBubbleOutgoing: Brush? = null
    override val gradientBubbleIncoming: Brush? = null
    override val gradientSendButton: Brush? = null
    
    // ========== ELEVATION & SHADOWS ==========
    override val elevationLow = 2.dp
    override val elevationMedium = 4.dp
    override val elevationHigh = 8.dp
    
    // ========== CORNER RADIUS ==========
    override val cornerRadiusSmall = 8.dp
    override val cornerRadiusMedium = 16.dp
    override val cornerRadiusLarge = 24.dp
    override val cornerRadiusCircular = 9999.dp
    
    // ========== SPACING ==========
    override val spacingXs = 4.dp
    override val spacingSmall = 8.dp
    override val spacingMedium = 16.dp
    override val spacingLarge = 24.dp
    override val spacingXl = 32.dp
    
    // ========== ANIMATION DURATIONS ==========
    override val animationDurationFast = 200
    override val animationDurationMedium = 300
    override val animationDurationSlow = 400
    
    // ========== THEME METADATA ==========
    override val themeName = "Dark"
    override val isDark = true
    override val isPremium = false
    override val hasGradients = false
}

/**
 * 🌑 Deep Dark Theme - OLED Optimized Variant
 * 
 * Specifically designed for AI Agent and Chat screens with pure black/deep gray backgrounds.
 */
@Immutable
object DeepDarkThemeTokens : GlyphThemeTokens by DarkThemeTokens {
    private val DeepDarkSurface = Color(0xFF0A0A0A)
    
    override val surfaceHeader = DeepDarkSurface
    override val surfaceInput = DeepDarkSurface
    override val backgroundSecondary = DeepDarkSurface
    
    override val themeName = "Deep Dark"
}
