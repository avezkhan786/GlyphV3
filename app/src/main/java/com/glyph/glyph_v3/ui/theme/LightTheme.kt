package com.glyph.glyph_v3.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 🌞 Premium Light Theme - Warm Neutral Beige Palette
 * 
 * Complete implementation of GlyphThemeTokens for consistent light mode.
 * Designed for long reading sessions with reduced eye strain.
 */
@Immutable
object LightThemeTokens : GlyphThemeTokens {
    
    // Base color palette
    private val Beige1 = Color(0xFFD6CCC2)
    private val Beige2 = Color(0xFFDED6CE)
    private val Beige3 = Color(0xFFE5DED8)
    private val Beige4 = Color(0xFFEEEAE6)
    private val Beige5 = Color(0xFFF5EBE0)
    private val Beige6 = Color(0xFFE3D5CA)
    
    // Modern AI Composer colors — Luminous Lavender theme
    private val AIGradientStart = Color(0xFFF8F7FF) // Luminous White with Violet touch
    private val AIGradientEnd = Color(0xFFEEE9FF)   // Richer Lavender tint
    private val AIAccentIndigo = Color(0xFF6366F1) // Modern Indigo
    private val AIAccentPurple = Color(0xFFA855F7) // Modern Purple
    
    // ========== SURFACES & BACKGROUNDS ==========
    override val backgroundPrimary = Beige4
    override val backgroundSecondary = Beige4
    override val backgroundElevated = Beige3
    override val backgroundTinted = Color(0xFFF0F7FF)
    override val backgroundWarm = Color(0xFFFFF8F0)
    override val surfaceChat = Beige5
    override val surfaceHeader = Beige4
    override val surfaceNavigation = Beige4
    override val surfaceInput = Color(0xFFF5F5F5) // Light gray for input
    override val surfaceOverlay = Color.Black.copy(alpha = 0.32f)
    
    // ========== NAVIGATION ==========
    override val navIconActive = Color(0xFF006A6A)
    override val navIconInactive = Color(0xFF6B6B6B)
    override val navTextActive = Color(0xFF006A6A)
    override val navTextInactive = Color(0xFF6B6B6B)
    override val navActiveIndicator = Color(0xFFD6CCC2)
    
    override val backgroundGradient: Brush? = null
    
    // ========== TEXT & TYPOGRAPHY ==========
    override val textPrimary = Color(0xFF2B2B2B)
    override val textSecondary = Color(0xFF6B6B6B)
    override val textTertiary = Color(0xFF9A9A9A)
    override val textInverse = Color.White
    override val textLink = Color(0xFF006A6A)
    override val textMention = Color(0xFF7C4DFF)
    override val textPlaceholder = Color(0xFF8A8A8A)
    
    // ========== ACTIONS & INTERACTIVITY ==========
    override val actionPrimary = Beige1
    override val actionSecondary = Beige3
    override val actionDestructive = Color(0xFFD32F2F)
    override val actionSuccess = Color(0xFF6B8E6B)
    override val actionWarning = Color(0xFFF2C94C)
    override val actionError = Color(0xFFD32F2F)
    override val actionPressed = Beige6
    override val actionHover = Beige1.copy(alpha = 0.04f)
    override val actionRipple = Beige1.copy(alpha = 0.1f)
    
    // ========== CHAT BUBBLES ==========
    override val bubbleOutgoingBackground = Color(0xFFd0f0e0)
    override val bubbleOutgoingText = Color(0xFF000000)
    override val bubbleIncomingBackground = Color(0xFFebe6e1)
    override val bubbleIncomingText = Color(0xFF000000)
    override val bubbleTimestamp = Color(0xFF6B6B6B)
    override val bubbleBorder = Beige1.copy(alpha = 0.35f) // More subtle, softer border
    
    // ========== ICONS & INDICATORS ==========
    override val iconPrimary = Color(0xFF3A3A3A)
    override val iconSecondary = Color(0xFF5A5A5A)
    override val iconTertiary = Color(0xFF8A8A8A)
    override val indicatorOnline = GlyphThemeTokens.PremiumGreen
    override val indicatorTyping = Color(0xFF006A6A)
    override val indicatorUnreadBackground = GlyphThemeTokens.PremiumGreen
    override val indicatorUnreadText = Color.Black
    override val indicatorMessageStatus = Color(0xFF4FC3F7)
    
    // ========== BORDERS & DIVIDERS ==========
    override val borderPrimary = Beige1
    override val borderSecondary = Beige3
    override val borderInput = Color(0xFFF1F3F4)
    override val borderFocus = Color(0xFF006A6A)
    override val divider = Beige1
    
    // ========== SPECIAL COMPONENTS ==========
    override val dateHeaderBackground = Beige2
    override val dateHeaderText = Color(0xFF4A4A4A)
    override val sendButtonBackground = Color(0xFFFBFBFB) // Same as top bar
    override val sendButtonIcon = Color(0xFF6B6B6B) // Same as header icons
    override val attachmentIcon = Color(0xFF6B6B6B)
    override val emojiIcon = Color(0xFF6B6B6B)
    override val aiIcon = Color(0xFF6C63FF)
    override val headerIcon = Color(0xFF6B6B6B)
    override val cursorColor = Color(0xFF360F5A)
    override val selectionBackground = Color(0x400EA5E9)
    override val selectionOverlay = Color(0x200EA5E9)
    override val avatarPlaceholder = Color(0xFFDFE6E9)
    override val imagePlaceholder = Color(0xFFF1F5F9)
    
    // ========== GRADIENTS ==========
    override val gradientPrimary: Brush? = null
    override val gradientHeader: Brush? = null
    override val gradientInput: Brush? = null
    override val gradientBubbleOutgoing: Brush? = null
    override val gradientBubbleIncoming: Brush? = null
    override val gradientAIComposer: Brush? = Brush.verticalGradient(
        colors = listOf(AIGradientStart, AIGradientEnd)
    )
    override val gradientSendButton: Brush? = null

    // ========== TELEGRAM-SPECIFIC TOKENS ==========
    override val telegramBubbleOutgoing = Color(0xFFE1F2C3) // Telegram green (#A9BE5F adjusted for light mode)
    override val telegramBubbleIncoming = Color(0xFFFFFFFF) // Pure white
    override val telegramBubbleOutgoingText = Color(0xFF000000) // Black text on green
    override val telegramBubbleIncomingText = Color(0xFF000000) // Black text on white
    override val telegramTimestamp = Color(0x80000000) // 50% alpha black
    override val telegramDateHeaderBackground = Color(0x33FFFFFF) // Semi-transparent white
    override val telegramDateHeaderText = Color(0xFF000000) // Black text
    override val telegramGlassOverlay = Color(0x66FFFFFF) // 40% alpha white
    override val telegramInputBackground = Color(0x80FFFFFF) // Semi-transparent white
    override val telegramAppBarBackground = Color(0xB3FFFFFF) // 70% alpha white

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
    override val themeName = "Light"
    override val isDark = false
    override val isPremium = false
    override val hasGradients = false
}