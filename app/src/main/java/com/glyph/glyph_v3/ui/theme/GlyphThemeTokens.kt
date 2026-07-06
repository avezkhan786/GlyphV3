package com.glyph.glyph_v3.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ✨ Glyph Semantic Theme Tokens ✨
 * 
 * Single Source of Truth for all visual styling across the app.
 * All UI components must use these semantic tokens instead of hardcoded values.
 * 
 * Architecture Principles:
 * - Semantic naming (what it represents, not what it looks like)
 * - Complete coverage (every UI element has a token)
 * - Runtime switchable (all themes implement this interface)
 * - Type-safe (Kotlin sealed classes & data classes)
 */

/**
 * Core theme specification that all themes must implement.
 * This ensures every theme provides complete visual coverage.
 */
@Immutable
interface GlyphThemeTokens {

    companion object {
        /** Persistent green color used for unread indicators & status across ALL themes */
        val PremiumGreen = Color(0xFF1DB954) // Exact DarkPrimary green used for unread badges
    }
    
    // ========== SURFACES & BACKGROUNDS ==========
    
    /** Root application background (main activity container) */
    val backgroundPrimary: Color
    
    /** Secondary background for cards, list items */
    val backgroundSecondary: Color
    
    /** Elevated surfaces (dialogs, bottom sheets, popovers) */
    val backgroundElevated: Color
    
    /** Tinted background for special sections */
    val backgroundTinted: Color
    
    /** Warm-toned background variant */
    val backgroundWarm: Color
    
    /** Surface for chat messages container */
    val surfaceChat: Color
    
    /** Surface for headers and top bars */
    val surfaceHeader: Color
    
    /** Surface for bottom navigation */
    val surfaceNavigation: Color
    
    /** Surface for input fields */
    val surfaceInput: Color
    
    /** Surface for overlays and scrims */
    val surfaceOverlay: Color
    
    // ========== NAVIGATION ==========
    
    /** Bottom navigation active icon color */
    val navIconActive: Color
    
    /** Bottom navigation inactive icon color */
    val navIconInactive: Color
    
    /** Bottom navigation active label text color */
    val navTextActive: Color
    
    /** Bottom navigation inactive label text color */
    val navTextInactive: Color
    
    /** Bottom navigation selection indicator (pill) color */
    val navActiveIndicator: Color
    
    /** Default background gradient when no wallpaper is set */
    val backgroundGradient: Brush?
    
    // ========== TEXT & TYPOGRAPHY ==========
    
    /** Primary text (headings, main content) */
    val textPrimary: Color
    
    /** Secondary text (timestamps, metadata) */
    val textSecondary: Color
    
    /** Tertiary text (hints, disabled states) */
    val textTertiary: Color
    
    /** Inverse text (for dark backgrounds) */
    val textInverse: Color
    
    /** Link text */
    val textLink: Color
    
    /** @mention text */
    val textMention: Color
    
    /** Placeholder text in inputs */
    val textPlaceholder: Color
    
    // ========== ACTIONS & INTERACTIVITY ==========
    
    /** Primary action color (main buttons, FABs) */
    val actionPrimary: Color
    
    /** Secondary action color (secondary buttons) */
    val actionSecondary: Color
    
    /** Destructive actions (delete, remove) */
    val actionDestructive: Color
    
    /** Success state */
    val actionSuccess: Color
    
    /** Warning state */
    val actionWarning: Color
    
    /** Error state */
    val actionError: Color
    
    /** Pressed/active state overlay */
    val actionPressed: Color
    
    /** Hover state overlay */
    val actionHover: Color
    
    /** Ripple effect color */
    val actionRipple: Color
    
    // ========== CHAT BUBBLES ==========
    
    /** Outgoing message bubble background */
    val bubbleOutgoingBackground: Color
    
    /** Outgoing message bubble text */
    val bubbleOutgoingText: Color
    
    /** Incoming message bubble background */
    val bubbleIncomingBackground: Color
    
    /** Incoming message bubble text */
    val bubbleIncomingText: Color
    
    /** Message timestamp text */
    val bubbleTimestamp: Color
    
    /** Message bubble border (if applicable) */
    val bubbleBorder: Color
    
    // ========== ICONS & INDICATORS ==========
    
    /** Primary icon color */
    val iconPrimary: Color
    
    /** Secondary icon color (less prominent) */
    val iconSecondary: Color
    
    /** Tertiary icon color (subtle) */
    val iconTertiary: Color
    
    /** Online presence indicator */
    val indicatorOnline: Color
    
    /** Typing indicator */
    val indicatorTyping: Color
    
    /** Unread badge background */
    val indicatorUnreadBackground: Color
    
    /** Unread badge text */
    val indicatorUnreadText: Color
    
    /** Message status icon (sent, delivered, read) */
    val indicatorMessageStatus: Color
    
    // ========== BORDERS & DIVIDERS ==========
    
    /** Primary border color */
    val borderPrimary: Color
    
    /** Secondary border color (subtle) */
    val borderSecondary: Color
    
    /** Input field border */
    val borderInput: Color
    
    /** Focus/active border */
    val borderFocus: Color
    
    /** Divider line */
    val divider: Color
    
    // ========== SPECIAL COMPONENTS ==========
    
    /** Date header background */
    val dateHeaderBackground: Color
    
    /** Date header text */
    val dateHeaderText: Color
    
    /** Send button background */
    val sendButtonBackground: Color
    
    /** Send button icon */
    val sendButtonIcon: Color
    
    /** Attachment button icon */
    val attachmentIcon: Color
    
    /** Emoji picker icon */
    val emojiIcon: Color
    
    /** AI Composer icon */
    val aiIcon: Color
    
    /** Header icons (back, call, menu) */
    val headerIcon: Color
    
    /** Cursor color in inputs */
    val cursorColor: Color
    
    /** Selection highlight */
    val selectionBackground: Color
    
    /** Selection overlay */
    val selectionOverlay: Color
    
    /** Avatar placeholder */
    val avatarPlaceholder: Color
    
    /** Image/media placeholder */
    val imagePlaceholder: Color
    
    // ========== GRADIENTS (Optional) ==========
    
    /** Primary gradient for backgrounds (null if solid color) */
    val gradientPrimary: Brush?
    
    /** Header gradient (null if solid color) */
    val gradientHeader: Brush?
    
    /** Input field gradient (null if solid color) */
    val gradientInput: Brush?
    
    /** Outgoing bubble gradient (null if solid color) */
    val gradientBubbleOutgoing: Brush?

    /** Incoming bubble gradient (null if solid color) */
    val gradientBubbleIncoming: Brush?
    
    /** AI Composer sheet gradient (null if solid color) */
    val gradientAIComposer: Brush?
    
    /** Send button gradient (null if solid color) */
    val gradientSendButton: Brush?

    // ========== TELEGRAM-SPECIFIC TOKENS ==========

    /** Telegram-style outgoing bubble (green: #A9BE5F or gradient) */
    val telegramBubbleOutgoing: Color

    /** Telegram-style incoming bubble (white: #FFFFFF or light gray: #F1F1F1) */
    val telegramBubbleIncoming: Color

    /** Telegram outgoing bubble text (white) */
    val telegramBubbleOutgoingText: Color

    /** Telegram incoming bubble text (black) */
    val telegramBubbleIncomingText: Color

    /** Telegram timestamp color (semi-transparent) */
    val telegramTimestamp: Color

    /** Telegram date header background (semi-transparent) */
    val telegramDateHeaderBackground: Color

    /** Telegram date header text (white) */
    val telegramDateHeaderText: Color

    /** Telegram glass overlay for blur effects (semi-transparent white) */
    val telegramGlassOverlay: Color

    /** Telegram input background with glass effect */
    val telegramInputBackground: Color

    /** Telegram app bar background with glass effect */
    val telegramAppBarBackground: Color

    // ========== ELEVATION & SHADOWS ==========
    
    /** Subtle elevation (cards) */
    val elevationLow: Dp
    
    /** Medium elevation (dialogs) */
    val elevationMedium: Dp
    
    /** High elevation (modal overlays) */
    val elevationHigh: Dp
    
    // ========== CORNER RADIUS ==========
    
    /** Small corner radius (chips, small buttons) */
    val cornerRadiusSmall: Dp
    
    /** Medium corner radius (cards, bubbles) */
    val cornerRadiusMedium: Dp
    
    /** Large corner radius (bottom sheets, large cards) */
    val cornerRadiusLarge: Dp
    
    /** Circular corner radius */
    val cornerRadiusCircular: Dp
    
    // ========== SPACING (Semantic) ==========
    
    /** Extra small spacing (tight elements) */
    val spacingXs: Dp
    
    /** Small spacing (close elements) */
    val spacingSmall: Dp
    
    /** Medium spacing (default) */
    val spacingMedium: Dp
    
    /** Large spacing (sections) */
    val spacingLarge: Dp
    
    /** Extra large spacing (major sections) */
    val spacingXl: Dp
    
    // ========== ANIMATION DURATIONS ==========
    
    /** Quick animations (micro-interactions) */
    val animationDurationFast: Int
    
    /** Standard animations (most UI transitions) */
    val animationDurationMedium: Int
    
    /** Slow animations (page transitions) */
    val animationDurationSlow: Int
    
    // ========== THEME METADATA ==========
    
    /** Theme display name */
    val themeName: String
    
    /** Is this a dark theme? */
    val isDark: Boolean
    
    /** Is this a premium theme? */
    val isPremium: Boolean
    
    /** Supports gradients? */
    val hasGradients: Boolean
}

/**
 * CompositionLocal for accessing theme tokens throughout the app.
 * Never access this directly - use LocalGlyphTheme.current instead.
 */
val LocalGlyphTheme = staticCompositionLocalOf<GlyphThemeTokens> {
    error("No GlyphTheme provided! Wrap your content with GlyphTheme { }")
}

/**
 * Provides Glyph theme tokens to the composition tree.
 * This is the single entry point for theming in Compose.
 * 
 * Usage:
 * ```
 * GlyphTheme(tokens = LightThemeTokens) {
 *     // Your UI here - all components have access to theme
 * }
 * ```
 */
@Composable
fun GlyphTheme(
    tokens: GlyphThemeTokens,
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlyphTheme provides tokens,
        content = content
    )
}

/**
 * Extension property to access theme tokens easily in any @Composable.
 * 
 * Usage:
 * ```
 * @Composable
 * fun MyComponent() {
 *     val theme = glyphTheme
 *     Text(
 *         text = "Hello",
 *         color = theme.textPrimary
 *     )
 * }
 * ```
 */
val glyphTheme: GlyphThemeTokens
    @Composable
    get() = LocalGlyphTheme.current
