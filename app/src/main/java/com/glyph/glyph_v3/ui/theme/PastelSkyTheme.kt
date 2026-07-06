package com.glyph.glyph_v3.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * ✨ Pastel-Sky Premium Theme ✨
 * 
 * A premium, gradient-first theme that feels:
 * - Premium and luxurious
 * - Calm & uplifting 
 * - Soft, pastel, and elegant (not childish)
 * - Designed for long chat usage
 * 
 * Color Philosophy:
 * - Subtle, premium gradients (never loud)
 * - No harsh whites or blues
 * - Barely noticeable transitions
 * - AMOLED-optimized (tinted whites, soft elevation)
 */
@Immutable
object PastelSkyThemeTokens : GlyphThemeTokens {
    
    // ========== CORE COLOR PALETTE ==========
    
    private val SkyBlue = Color(0xFFCFE9F3)        // Sky blue - primary accent
    private val LavenderMist = Color(0xFFE6DDF2)   // Lavender mist - soft purple tint
    private val MintCloud = Color(0xFFDFF2EA)      // Mint cloud - calming green
    private val PeachHaze = Color(0xFFF6E2D6)      // Peach haze - warm accent
    private val CreamWhite = Color(0xFFFBFAF8)     // Cream white - AMOLED-safe white
    private val CloudGray = Color(0xFFE8EDF2)      // Cloud gray - soft neutral
    
    // ========== VIBRANT ACCENTS (for icons & highlights) ==========
    private val VibrantPurple = Color(0xFF9B7EDE)  // Dreamy purple for icons
    private val VibrantTeal = Color(0xFF4DD4D4)    // Lively teal for accents
    private val DeepTeal = Color(0xFF0097A7)       // Darker teal for status readability
    private val VibrantPink = Color(0xFFFF9ECD)    // Playful pink for selection
    private val DeepIndigo = Color(0xFF5B4B8A)     // Rich indigo for text
    
    // ========== GRADIENTS (Premium & Subtle) ==========
    
    /**
     * Primary App Background Gradient (Top → Bottom)
     * Very subtle, moves slowly, barely noticeable
     */
    private fun primaryGradientBrush(): Brush = Brush.verticalGradient(
        colors = listOf(
            SkyBlue,
            LavenderMist,
            CreamWhite
        )
    )
    
    /**
     * Header/Top Bar Gradient (Top → Bottom)
     * Floating, airy feel for top app bar
     */
    private fun headerGradientBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF90CAF9), // Vibrant Sky Blue
            Color(0xFFB2F2BB)  // Vibrant Mint
        )
    )
    
    /**
     * Input Field Gradient (Left → Right)
     */
    private fun inputGradientBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF90CAF9).copy(alpha = 0.4f), // Vibrant Sky Blue (Softened)
            Color(0xFFB2F2BB).copy(alpha = 0.4f)  // Vibrant Mint (Softened)
        )
    )

    /**
     * AI Composer Sheet Gradient (Opaque) - Rich Sky-Dream
     */
    private fun aiComposerGradientBrush(): Brush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFBAE6FD), // Sky Blue (Rich)
            Color(0xFFDDD6FE), // Lavender Mist (Rich)
            Color(0xFFF9FAFB)  // Near White base
        )
    )
    
    /**
     * Outgoing Bubble Gradient (Left → Right)
     * Cloud-like, soft, never sharp
     */
    private fun outgoingBubbleGradientBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFB2F2BB), // Vibrant Mint
            Color(0xFF90CAF9)  // Vibrant Sky Blue
        )
    )

    /**
     * Incoming Bubble Gradient (Left → Right)
     * Soft lavender to cream transition
     */
    private fun incomingBubbleGradientBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFD1C4E9), // Vibrant Lavender
            Color(0xFFFCE4EC)  // Vibrant Soft Pink
        )
    )
    
    /**
     * Send Button Gradient (Left → Right)
     */
    private fun sendButtonGradientBrush(): Brush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF90CAF9), // Vibrant Sky Blue
            Color(0xFFB2F2BB)  // Vibrant Mint
        )
    )
    
    // ========== SURFACES & BACKGROUNDS ==========
    override val backgroundPrimary = CreamWhite
    override val backgroundSecondary = Color(0xFFD8C8E8) // Slightly darker Lavender for better card contrast
    override val backgroundElevated = CloudGray
    override val backgroundTinted = Color(0xFFF0F7FF)
    override val backgroundWarm = PeachHaze
    override val surfaceChat = CreamWhite
    override val surfaceHeader = CreamWhite
    override val surfaceNavigation = CreamWhite
    override val surfaceInput = CreamWhite
    override val surfaceOverlay = Color.Black.copy(alpha = 0.32f)
    
    // ========== NAVIGATION ==========
    override val navIconActive = VibrantPurple
    override val navIconInactive = VibrantPurple
    override val navTextActive = VibrantPurple
    override val navTextInactive = VibrantPurple
    override val navActiveIndicator = Color(0xFFC4B5ED)
    
    override val backgroundGradient = primaryGradientBrush()
    
    // ========== TEXT & TYPOGRAPHY ==========
    override val textPrimary = Color(0xFF2D2445)    // Much darker indigo for better readability
    override val textSecondary = Color(0xFF4A3D6D)  // Darker purple-gray
    override val textTertiary = Color(0xFF757E8A)
    override val textInverse = Color.White
    override val textLink = Color(0xFF5B8FA8)
    override val textMention = Color(0xFF8B9DC3)
    override val textPlaceholder = Color(0xFF8A92A0)
    
    // ========== ACTIONS & INTERACTIVITY ==========
    override val actionPrimary = MintCloud
    override val actionSecondary = CloudGray
    override val actionDestructive = Color(0xFFD32F2F)
    override val actionSuccess = Color(0xFF6B9B7A)
    override val actionWarning = Color(0xFFF2C94C)
    override val actionError = Color(0xFFD32F2F)
    override val actionPressed = MintCloud.copy(alpha = 0.8f)
    override val actionHover = MintCloud.copy(alpha = 0.06f)
    override val actionRipple = MintCloud.copy(alpha = 0.12f)
    
    // ========== CHAT BUBBLES ==========
    override val bubbleOutgoingBackground = MintCloud
    override val bubbleOutgoingText = Color(0xFF243338)
    override val bubbleIncomingBackground = CreamWhite
    override val bubbleIncomingText = Color(0xFF2E2E2E)
    override val bubbleTimestamp = Color(0xFF6A7280)
    override val bubbleBorder = CloudGray.copy(alpha = 0.40f) // Soft cloud-gray, matches palette
    
    // ========== ICONS & INDICATORS ==========
    override val iconPrimary = VibrantPurple         // Vibrant purple for icons
    override val iconSecondary = VibrantTeal          // Lively teal for secondary icons
    override val iconTertiary = Color(0xFFA8A0C8)     // Soft lavender
    override val indicatorOnline = GlyphThemeTokens.PremiumGreen
    override val indicatorTyping = VibrantTeal
    override val indicatorUnreadBackground = GlyphThemeTokens.PremiumGreen
    override val indicatorUnreadText = Color.Black
    // Use the same message-status indicator color as Light/Dark themes for consistency
    override val indicatorMessageStatus = Color(0xFF4FC3F7)
    
    // ========== BORDERS & DIVIDERS ==========
    override val borderPrimary = CloudGray
    override val borderSecondary = CloudGray.copy(alpha = 0.6f)
    override val borderInput = CloudGray
    override val borderFocus = Color(0xFF5B8FA8)
    override val divider = CloudGray.copy(alpha = 0.25f)
    
    // ========== SPECIAL COMPONENTS ==========
    override val dateHeaderBackground = LavenderMist
    override val dateHeaderText = VibrantPurple
    override val sendButtonBackground = VibrantPurple
    override val sendButtonIcon = Color(0xFF360F5A)
    // Use neutral gray for icons to match Light/Dark theme consistency
    override val attachmentIcon = Color(0xFF5B5B5B)  // Neutral gray, matches input area aesthetic
    override val emojiIcon = Color(0xFF5B5B5B)       // Neutral gray
    override val aiIcon = Color(0xFF6C63FF)
    override val headerIcon = Color(0xFF5B5B5B)      // Neutral gray, consistent across themes
    override val cursorColor = DeepIndigo
    override val selectionBackground = Color(0x405B8FA8)
    override val selectionOverlay = Color(0x205B8FA8)
    override val avatarPlaceholder = CloudGray
    override val imagePlaceholder = CreamWhite
    
    // ========== GRADIENTS ==========
    override val gradientPrimary = primaryGradientBrush()
    override val gradientHeader = headerGradientBrush()
    override val gradientInput = inputGradientBrush()
    override val gradientAIComposer = aiComposerGradientBrush()
    override val gradientBubbleOutgoing = outgoingBubbleGradientBrush()
    override val gradientBubbleIncoming = incomingBubbleGradientBrush()
    override val gradientSendButton = sendButtonGradientBrush()

    // ========== TELEGRAM-SPECIFIC TOKENS ==========
    override val telegramBubbleOutgoing = MintCloud // Pastel mint green for outgoing
    override val telegramBubbleIncoming = CreamWhite // Cream white for incoming
    override val telegramBubbleOutgoingText = Color(0xFF243338) // Dark text on mint
    override val telegramBubbleIncomingText = Color(0xFF2D2445) // Dark indigo text on white
    override val telegramTimestamp = Color(0x804A3D6D) // Semi-transparent purple-gray
    override val telegramDateHeaderBackground = Color(0x66E6DDF2) // Semi-transparent lavender
    override val telegramDateHeaderText = VibrantPurple // Vibrant purple text
    override val telegramGlassOverlay = Color(0x66FFFFFF) // 40% alpha white
    override val telegramInputBackground = Color(0x80FBFAF8) // Semi-transparent cream
    override val telegramAppBarBackground = Color(0xB3FBFAF8) // 70% alpha cream

    // ========== ELEVATION & SHADOWS ==========
    override val elevationLow = 0.5.dp
    override val elevationMedium = 2.dp
    override val elevationHigh = 4.dp
    
    // ========== CORNER RADIUS ==========
    override val cornerRadiusSmall = 8.dp
    override val cornerRadiusMedium = 22.dp
    override val cornerRadiusLarge = 28.dp
    override val cornerRadiusCircular = 9999.dp
    
    // ========== SPACING ==========
    override val spacingXs = 4.dp
    override val spacingSmall = 8.dp
    override val spacingMedium = 16.dp
    override val spacingLarge = 24.dp
    override val spacingXl = 32.dp
    
    // ========== ANIMATION DURATIONS ==========
    override val animationDurationFast = 400
    override val animationDurationMedium = 600
    override val animationDurationSlow = 800
    
    // ========== THEME METADATA ==========
    override val themeName = "Pastel-Sky"
    override val isDark = false
    override val isPremium = true
    override val hasGradients = true
}