package com.glyph.glyph_v3.ui.chat

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.data.models.OFFICIAL_USER_ID
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager

/**
 * Read-only conversation footer banner for official / broadcast channels.
 *
 * A fixed banner pinned to the bottom of a read-only conversation (official
 * announcements, company accounts, system accounts, read-only channels and
 * broadcast channels). It communicates to the user — WhatsApp style — that this
 * is a one-directional conversation: only Glyph can send messages.
 *
 * Properties
 * - Full width, ~56dp tall, pinned above the navigation gesture area.
 * - Background matches the top app bar ([com.glyph.glyph_v3.ui.theme.GlyphThemeTokens.surfaceHeader]).
 * - 1dp top divider using [ColorScheme.outlineVariant].
 * - Centered text: "Only Glyph can send messages" (Roboto Medium, 16sp).
 * - Fade-in + 8dp slide-up on open; smooth fade-out on leave (Material motion).
 * - Automatic Dynamic Material You (Android 12+), dark/light, and custom-theme support.
 * - Respects navigation/gesture insets and never overlaps the message list.
 *
 * Reusability
 * - Drive visibility through [OfficialConversationFooterState] which exposes the
 *   imperative [OfficialConversationFooterState.show], [OfficialConversationFooterState.hide]
 *   and [OfficialConversationFooterState.setConversation] API requested by the design.
 * - Any read-only conversation (including a [Chat] via [Chat.toReadOnlyConversation])
 *   can reuse this single component.
 */

/** Default dimensions / durations for the footer — no magic numbers elsewhere. */
private object OfficialFooterDefaults {
    val Height = 56.dp
    val DividerThickness = 1.dp
    val SlideDistance = 8.dp
    val TextSize = 16.sp
    val AnimationDurationMillis = 150
    val ContentHorizontalPadding = 16.dp
    val MotionEasing: Easing = FastOutSlowInEasing
    const val LightenRatio = 0.06f
    const val DividerRatio = 0.05f
    const val ContentDescription =
        "Read only conversation. Only Glyph can send messages."
}

/**
 * Categories of conversation the footer can represent. Every entry declares whether
 * it is read-only so visibility can be derived from [ReadOnlyConversation.type].
 */
enum class ConversationType(val isReadOnly: Boolean) {
    OFFICIAL_ANNOUNCEMENT(true),
    OFFICIAL_COMPANY(true),
    SYSTEM(true),
    READ_ONLY_CHANNEL(true),
    BROADCAST_CHANNEL(true),
    NORMAL(false),
    GROUP(false)
}

/** Lightweight, reusable description of a conversation that owns the footer's visibility. */
@Immutable
data class ReadOnlyConversation(
    val id: String,
    val type: ConversationType,
    /** Defaults to the read-only nature of [type]; may be forced for ad-hoc cases. */
    val isReadOnly: Boolean = type.isReadOnly
)

/**
 * State holder for [OfficialConversationFooter], exposing the imperative API while
 * remaining Compose-idiomatic (the composable observes [isVisible]).
 */
class OfficialConversationFooterState(initial: ReadOnlyConversation? = null) {
    var currentConversation by mutableStateOf<ReadOnlyConversation?>(initial)
        private set
    var isVisible by mutableStateOf(initial?.isReadOnly == true)
        internal set

    /** Reveal the banner. */
    fun show() {
        isVisible = true
    }

    /** Hide the banner (e.g. when a composer appears and would overlap it). */
    fun hide() {
        isVisible = false
    }

    /**
     * Bind a conversation. Visibility is automatically driven by the conversation's
     * read-only status: `conversation.isReadOnly` (or `type == OFFICIAL*`).
     */
    fun setConversation(conversation: ReadOnlyConversation) {
        this.currentConversation = conversation
        this.isVisible = conversation.isReadOnly
    }
}

/** Remember a [OfficialConversationFooterState], optionally seeded with a conversation. */
@Composable
fun rememberOfficialConversationFooterState(
    initial: ReadOnlyConversation? = null
): OfficialConversationFooterState = remember { OfficialConversationFooterState(initial) }

/**
 * Map a real [Chat] into a [ReadOnlyConversation] so the footer can be reused across the
 * app's conversation list. Official chats (company announcements) are read-only.
 */
fun Chat.toReadOnlyConversation(): ReadOnlyConversation =
    ReadOnlyConversation(
        id = id.ifBlank { OFFICIAL_USER_ID },
        type = if (isOfficial) ConversationType.OFFICIAL_COMPANY else ConversationType.NORMAL
    )

/**
 * Build a Material 3 [ColorScheme] for the footer.
 *
 * - On Android 12+ with a system dark/light theme, uses Dynamic Material You colors.
 * - Otherwise derives the M3 roles from the active Glyph theme tokens so custom themes
 *   (e.g. Pastel-Sky) and light/dark still adapt automatically.
 */
@Composable
private fun rememberFooterColorScheme(): ColorScheme {
    val context = LocalContext.current
    val tokens = glyphTheme
    val isDark = tokens.isDark
    val useDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ThemeManager.getCurrentTheme(context) != ThemeManager.THEME_PASTEL_SKY

    return remember(useDynamic, isDark, tokens.themeName) {
        if (useDynamic) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (isDark) {
            darkColorScheme(
                primary = tokens.actionPrimary,
                onPrimary = tokens.textInverse,
                background = tokens.backgroundPrimary,
                surface = tokens.backgroundElevated,
                onSurface = tokens.textPrimary,
                onSurfaceVariant = tokens.textSecondary,
                surfaceContainer = tokens.backgroundPrimary.lighten(OfficialFooterDefaults.LightenRatio),
                outline = tokens.borderPrimary,
                outlineVariant = tokens.divider
            )
        } else {
            lightColorScheme(
                primary = tokens.actionPrimary,
                onPrimary = tokens.textPrimary,
                background = tokens.backgroundPrimary,
                surface = tokens.backgroundElevated,
                onSurface = tokens.textPrimary,
                onSurfaceVariant = tokens.textSecondary,
                surfaceContainer = tokens.backgroundPrimary.lighten(OfficialFooterDefaults.LightenRatio),
                outline = tokens.borderPrimary,
                outlineVariant = tokens.divider
            )
        }
    }
}

/** Blend [this] color toward white by [ratio] — used to derive a slightly lighter surface. */
private fun Color.lighten(ratio: Float): Color {
    val white = Color.White
    return Color(
        red = red + (white.red - red) * ratio,
        green = green + (white.green - green) * ratio,
        blue = blue + (white.blue - blue) * ratio,
        alpha = alpha
    )
}

/** Blend [this] color toward black by [ratio] — used for a hairline divider tinted from the background. */
private fun Color.darken(ratio: Float): Color {
    val black = Color.Black
    return Color(
        red = red + (black.red - red) * ratio,
        green = green + (black.green - green) * ratio,
        blue = blue + (black.blue - blue) * ratio,
        alpha = alpha
    )
}

/**
 * The footer banner composable. Pin it at the bottom of the screen (below the scrolling
 * message list); the [OfficialConversationFooterState] controls its visibility.
 */
@Composable
fun OfficialConversationFooter(
    state: OfficialConversationFooterState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scheme = rememberFooterColorScheme()
    val slideDistancePx = with(density) { OfficialFooterDefaults.SlideDistance.roundToPx() }
    val duration = OfficialFooterDefaults.AnimationDurationMillis
    val easing = OfficialFooterDefaults.MotionEasing

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(tween(duration, easing = easing)) +
            slideInVertically(tween(duration, easing = easing)) { slideDistancePx },
        exit = fadeOut(tween(duration, easing = easing)) +
            slideOutVertically(tween(duration, easing = easing)) { -slideDistancePx }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                // Match the top app bar's surface so the banner reads as its visual sibling.
                .background(glyphTheme.surfaceHeader)
        ) {
            // Thin 1dp top divider, tinted just slightly off the banner background so it
            // reads as a subtle hairline rather than a hard line.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(OfficialFooterDefaults.DividerThickness)
                    .background(glyphTheme.surfaceHeader.darken(OfficialFooterDefaults.DividerRatio))
            )
            // Text band kept in the safe area above the navigation/gesture bar while the
            // outer background stays full-bleed so the color continues beneath the system UI.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .heightIn(min = OfficialFooterDefaults.Height - OfficialFooterDefaults.DividerThickness)
                    .semantics(mergeDescendants = true) {
                        contentDescription = OfficialFooterDefaults.ContentDescription
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Only Glyph can send messages",
                    color = scheme.onSurfaceVariant,
                    fontSize = OfficialFooterDefaults.TextSize,
                    fontWeight = FontWeight.Medium, // Roboto Medium
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        horizontal = OfficialFooterDefaults.ContentHorizontalPadding
                    )
                )
            }
        }
    }
}
