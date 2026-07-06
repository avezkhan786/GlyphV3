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
    
    // ========== TELEGRAM-SPECIFIC TOKENS ==========
    override val telegramBubbleOutgoing = Color(0xFF005C4B) // Dark green for dark mode
    override val telegramBubbleIncoming = Color(0xFF1F2C34) // Dark gray for incoming
    override val telegramBubbleOutgoingText = Color(0xFFFFFFFF) // White text on outgoing
    override val telegramBubbleIncomingText = Color(0xFFE6EEF1) // Light text on incoming
    override val telegramTimestamp = Color(0x80FFFFFF) // 50% alpha white
    override val telegramDateHeaderBackground = Color(0x331F2C34) // Semi-transparent dark
    override val telegramDateHeaderText = Color(0xFFD8FDD2) // Light greenish white
    override val telegramGlassOverlay = Color(0x4D000000) // 30% alpha black for dark mode
    override val telegramInputBackground = Color(0x800B1014) // Semi-transparent dark surface
    override val telegramAppBarBackground = Color(0x8D0B1014) // Semi-transparent dark surface

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
object DeepDarkThemeTokens : GlyphThemeTokens {
    private val DeepDarkSurface = Color(0xFF0A0A0A)
    private val DeepDarkBackground = Color(0xFF000000)

    // Inherit all from DarkThemeTokens
    private val base = DarkThemeTokens

    // ========== SURFACES & BACKGROUNDS ==========
    override val backgroundPrimary = DeepDarkBackground
    override val backgroundSecondary = DeepDarkSurface
    override val backgroundElevated = DeepDarkSurface
    override val backgroundTinted = Color(0xFF0D1418)
    override val backgroundWarm = Color(0xFF12100C)
    override val surfaceChat = DeepDarkBackground
    override val surfaceHeader = DeepDarkSurface
    override val surfaceNavigation = DeepDarkSurface
    override val surfaceInput = DeepDarkSurface
    override val surfaceOverlay = Color.Black.copy(alpha = 0.85f)

    // ========== NAVIGATION ==========
    override val navIconActive = base.navIconActive
    override val navIconInactive = base.navIconInactive
    override val navTextActive = base.navTextActive
    override val navTextInactive = base.navTextInactive
    override val navActiveIndicator = base.navActiveIndicator
    override val backgroundGradient = null

    // ========== TEXT & TYPOGRAPHY ==========
    override val textPrimary = Color(0xFFE6EEF1)
    override val textSecondary = Color(0xFF9EA7AC)
    override val textTertiary = Color(0xFF6E7580)
    override val textInverse = Color(0xFF1F2937)
    override val textLink = base.textLink
    override val textMention = base.textMention
    override val textPlaceholder = Color(0xFF6E7580)

    // ========== ACTIONS & INTERACTIVITY ==========
    override val actionPrimary = base.actionPrimary
    override val actionSecondary = DeepDarkSurface
    override val actionDestructive = base.actionDestructive
    override val actionSuccess = base.actionSuccess
    override val actionWarning = base.actionWarning
    override val actionError = base.actionError
    override val actionPressed = Color.White.copy(alpha = 0.08f)
    override val actionHover = Color.White.copy(alpha = 0.03f)
    override val actionRipple = base.actionRipple

    // ========== CHAT BUBBLES ==========
    override val bubbleOutgoingBackground = Color(0xFF004D3D)
    override val bubbleOutgoingText = Color(0xFFFFFFFF)
    override val bubbleIncomingBackground = DeepDarkSurface
    override val bubbleIncomingText = Color(0xFFE6EEF1)
    override val bubbleTimestamp = Color(0x80FFFFFF)
    override val bubbleBorder = Color(0x1AFFFFFF)

    // ========== ICONS & INDICATORS ==========
    override val iconPrimary = base.iconPrimary
    override val iconSecondary = Color(0xFF9EA7AC)
    override val iconTertiary = Color(0xFF6E7580)
    override val indicatorOnline = base.indicatorOnline
    override val indicatorTyping = base.indicatorTyping
    override val indicatorUnreadBackground = base.indicatorUnreadBackground
    override val indicatorUnreadText = base.indicatorUnreadText
    override val indicatorMessageStatus = base.indicatorMessageStatus

    // ========== BORDERS & DIVIDERS ==========
    override val borderPrimary = Color(0x1A2A3036)
    override val borderSecondary = Color(0x0D2A3036)
    override val borderInput = DeepDarkSurface
    override val borderFocus = base.borderFocus
    override val divider = Color(0x0D2A3036)

    // ========== SPECIAL COMPONENTS ==========
    override val dateHeaderBackground = Color(0xFF141A20)
    override val dateHeaderText = Color(0xFF9EA7AC)
    override val sendButtonBackground = base.sendButtonBackground
    override val sendButtonIcon = base.sendButtonIcon
    override val attachmentIcon = Color.White
    override val emojiIcon = Color.White
    override val aiIcon = Color.White
    override val headerIcon = Color(0xFF9EA7AC)
    override val cursorColor = base.cursorColor
    override val selectionBackground = Color(0x4025D366)
    override val selectionOverlay = Color(0x2025D366)
    override val avatarPlaceholder = Color(0xFF1A2024)
    override val imagePlaceholder = DeepDarkSurface

    // ========== GRADIENTS ==========
    override val gradientPrimary = null
    override val gradientHeader = null
    override val gradientInput = base.gradientInput
    override val gradientAIComposer = base.gradientAIComposer
    override val gradientBubbleOutgoing = null
    override val gradientBubbleIncoming = null
    override val gradientSendButton = null

    // ========== TELEGRAM-SPECIFIC TOKENS ==========
    override val telegramBubbleOutgoing = Color(0xFF004D3D) // Darker green for OLED
    override val telegramBubbleIncoming = DeepDarkSurface // Pure black/dark gray
    override val telegramBubbleOutgoingText = Color(0xFFFFFFFF) // White
    override val telegramBubbleIncomingText = Color(0xFFE6EEF1) // Light text
    override val telegramTimestamp = Color(0x80FFFFFF) // 50% alpha white
    override val telegramDateHeaderBackground = Color(0x4D141A20) // Semi-transparent dark
    override val telegramDateHeaderText = Color(0xFF9EA7AC) // Light gray
    override val telegramGlassOverlay = Color(0x66000000) // 40% alpha black
    override val telegramInputBackground = Color(0x8D0A0A0A) // Semi-transparent black
    override val telegramAppBarBackground = Color(0x8D0A0A0A) // Semi-transparent black

    // ========== ELEVATION & SHADOWS ==========
    override val elevationLow = base.elevationLow
    override val elevationMedium = base.elevationMedium
    override val elevationHigh = base.elevationHigh

    // ========== CORNER RADIUS ==========
    override val cornerRadiusSmall = base.cornerRadiusSmall
    override val cornerRadiusMedium = base.cornerRadiusMedium
    override val cornerRadiusLarge = base.cornerRadiusLarge
    override val cornerRadiusCircular = base.cornerRadiusCircular

    // ========== SPACING ==========
    override val spacingXs = base.spacingXs
    override val spacingSmall = base.spacingSmall
    override val spacingMedium = base.spacingMedium
    override val spacingLarge = base.spacingLarge
    override val spacingXl = base.spacingXl

    // ========== ANIMATION DURATIONS ==========
    override val animationDurationFast = base.animationDurationFast
    override val animationDurationMedium = base.animationDurationMedium
    override val animationDurationSlow = base.animationDurationSlow

    // ========== THEME METADATA ==========
    override val themeName = "Deep Dark"
    override val isDark = true
    override val isPremium = false
    override val hasGradients = false
}
