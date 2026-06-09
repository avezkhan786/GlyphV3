package com.glyph.glyph_v3.ui.status

import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.chat.picker.EmojiPickerPanel
import com.glyph.glyph_v3.ui.chat.picker.KeyboardHeightProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.roundToInt

/**
 * Font preset with a serializable ID string for Firestore persistence.
 * The [id] is stored in the Status document and resolved back to styling on display.
 */
data class FontPreset(
    val id: String,
    val label: String,
    val fontFamily: FontFamily,
    val fontWeight: FontWeight = FontWeight.Normal,
    val fontStyle: FontStyle = FontStyle.Normal
)

/** All available font presets — shared with StatusViewerScreen via [resolveFontPreset]. */
val fontPresets = listOf(
    FontPreset("default",    "Aa", FontFamily.Default,   FontWeight.Normal),
    FontPreset("serif",      "Aa", FontFamily.Serif,     FontWeight.Normal),
    FontPreset("cursive",    "Aa", FontFamily.Cursive,   FontWeight.Normal, FontStyle.Italic),
    FontPreset("bold_sans",  "Aa", FontFamily.SansSerif, FontWeight.Bold),
    FontPreset("mono",       "Aa", FontFamily.Monospace, FontWeight.Medium),
    FontPreset("heavy",      "Aa", FontFamily.Serif,     FontWeight.Black),
    FontPreset("light",      "Aa", FontFamily.SansSerif, FontWeight.Light),
    FontPreset("thin_serif", "Aa", FontFamily.Serif,     FontWeight.Thin),
    FontPreset("condensed",  "Aa", FontFamily.SansSerif, FontWeight.ExtraBold),
    FontPreset("elegant",    "Aa", FontFamily.Serif,     FontWeight.SemiBold, FontStyle.Italic),
    FontPreset("casual",     "Aa", FontFamily.Cursive,   FontWeight.Bold),
    FontPreset("minimal",    "Aa", FontFamily.Default,   FontWeight.Thin),
)

/** Resolve a fontStyle ID (from Firestore) back to its [FontPreset]. Falls back to default. */
fun resolveFontPreset(fontStyleId: String): FontPreset {
    return fontPresets.firstOrNull { it.id == fontStyleId } ?: fontPresets[0]
}

private val statusColors = listOf(
    0xFF1B5E20.toInt(), 0xFF0D47A1.toInt(), 0xFFBF360C.toInt(),
    0xFF4A148C.toInt(), 0xFF880E4F.toInt(), 0xFF01579B.toInt(),
    0xFF33691E.toInt(), 0xFFE65100.toInt(), 0xFF1A237E.toInt(),
    0xFF263238.toInt(), 0xFF004D40.toInt(), 0xFF3E2723.toInt(),
    0xFF4E6E58.toInt(), 0xFF4F6D7A.toInt(), 0xFFA0522D.toInt(),
    0xFFB5838D.toInt(), 0xFF6B7B8D.toInt(), 0xFF2C3E50.toInt(),
    0xFF4A7C59.toInt(), 0xFFBC6C25.toInt(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextStatusScreen(
    isUploading: Boolean,
    onPost: (text: String, backgroundColor: Int, fontStyle: String) -> Unit,
    onClose: () -> Unit,
    onAudienceClick: (() -> Unit)? = null,
    audienceLabel: String = "Status (Contacts)",
    onPickerMediaReady: ((MediaItem) -> Unit)? = null
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var selectedColorIndex by remember { mutableIntStateOf(0) }
    var selectedFontIndex by remember { mutableIntStateOf(0) }
    var showColorPicker by remember { mutableStateOf(false) }
    var configSaved by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var isPickerVisible by remember { mutableStateOf(false) }
    var pickerSearchFocused by remember { mutableStateOf(false) }
    var emojiPickerPanel by remember { mutableStateOf<EmojiPickerPanel?>(null) }
    var isKeyboardAnimating by remember { mutableStateOf(false) }
    var animatedImeBottomPx by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val hostActivity = remember(context) { context.findFragmentActivity() }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val fallbackKeyboardHeightPx = remember(density) { with(density) { 270.dp.roundToPx() } }
    val inputGapPx = with(density) { 2.dp.roundToPx() }
    var kbdHeightPx by remember { mutableIntStateOf(fallbackKeyboardHeightPx) }
    // When true, picker stays visible until keyboard animation starts then hides simultaneously
    var pendingHidePicker by remember { mutableStateOf(false) }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)

    val backgroundColor = statusColors[selectedColorIndex]
    val font = fontPresets[selectedFontIndex]
    val hasContent by remember { derivedStateOf { textFieldValue.text.isNotBlank() } }

    // Theme-aware overlay color for all floating controls (chip, Aa, palette, emoji button).
    // Dark theme → dark charcoal grey, opaque enough to read on any status color.
    // Light theme → frosted white consistent with lighter palettes.
    // Color is a value type — no remember needed here.
    val controlsOverlay = if (glyphTheme.isDark) Color(0xFF1A1A1A).copy(alpha = 0.90f)
        else Color.White.copy(alpha = 0.30f)
    val controlsOverlayActive = if (glyphTheme.isDark) Color(0xFF2E2E2E).copy(alpha = 0.96f)
        else Color.White.copy(alpha = 0.48f)

    // Accurate keyboard height from the same provider used by ChatActivity / StatusViewerScreen
    DisposableEffect(hostActivity) {
        if (hostActivity != null) {
            val kbp = KeyboardHeightProvider(hostActivity)
            kbdHeightPx = kbp.getKeyboardHeight()
            kbp.onHeightChanged = { kbdHeightPx = it }
            kbp.start()
            onDispose { kbp.stop() }
        } else {
            onDispose { }
        }
    }

    // Smooth IME animation tracking — keeps the picker / column in sync during keyboard open/close
    DisposableEffect(view, emojiPickerPanel) {
        val callback = object : WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
        ) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                    isKeyboardAnimating = true
                }
                super.onPrepare(animation)
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: List<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat {
                val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                animatedImeBottomPx = imeInsets.bottom
                val panel = emojiPickerPanel
                if (panel != null && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                    panel.expandForSearch(imeInsets.bottom)
                }
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                super.onEnd(animation)
                if ((animation.typeMask and WindowInsetsCompat.Type.ime()) == 0) return
                isKeyboardAnimating = false
                val rootInsets = ViewCompat.getRootWindowInsets(view)
                val imeBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                animatedImeBottomPx = imeBottom

                // Seamless picker→keyboard: keyboard is now fully visible.
                // Hide the picker here (it's behind the keyboard, user won't see the slide-out).
                // This mirrors the ChatActivity.onEnd → hideEmojiPicker() pattern.
                if (pendingHidePicker) {
                    isPickerVisible = false
                    pendingHidePicker = false
                    return
                }

                val panel = emojiPickerPanel
                if (panel != null) {
                    if (imeBottom > 0 && isPickerVisible &&
                        panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT
                    ) {
                        panel.expandForSearch(imeBottom)
                    } else if (imeBottom == 0 &&
                        panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT
                    ) {
                        panel.expandForSearch(0)
                    }
                }
            }
        }
        ViewCompat.setWindowInsetsAnimationCallback(view, callback)
        onDispose { ViewCompat.setWindowInsetsAnimationCallback(view, null) }
    }

    // Effective bottom offset: keeps the Column at the same height whether keyboard or picker is up.
    // While pendingHidePicker is true the picker is still physically present — use its height as
    // the floor so the Column doesn't move during the keyboard-rise animation.
    val pickerHeightPx = kbdHeightPx + navBottomPx
    val activeImePx = if (isKeyboardAnimating) animatedImeBottomPx else imeBottomPx
    val treatPickerActive = isPickerVisible || pendingHidePicker
    val effectiveImePx = if (treatPickerActive) maxOf(activeImePx, pickerHeightPx) else activeImePx
    val rawEffectiveBottomPx = maxOf(effectiveImePx, navBottomPx) + inputGapPx
    val targetEffectiveBottomDp = with(density) { rawEffectiveBottomPx.toDp() }
    val effectiveBottomDp by animateDpAsState(
        targetValue = targetEffectiveBottomDp,
        animationSpec = spring(
            stiffness = Spring.StiffnessMedium,
            dampingRatio = Spring.DampingRatioNoBouncy
        ),
        label = "kbdPadding"
    )

    // Back handler: dismiss picker first, then show discard dialog if there's content
    BackHandler(enabled = true) {
        if (isPickerVisible) {
            isPickerVisible = false
        } else if (hasContent) {
            showDiscardDialog = true
        } else {
            onClose()
        }
    }

    // Discard confirmation dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard text?") },
            text = { Text("Your status text will be lost.") },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onClose()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            containerColor = glyphTheme.backgroundElevated,
            titleContentColor = glyphTheme.textPrimary,
            textContentColor = glyphTheme.textSecondary
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(backgroundColor))
            .statusBarsPadding()
    ) {
        // ── Content column: shrinks above keyboard / picker ──
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = effectiveBottomDp)
        ) {
            // ── Top bar: Done (left) | Font + Color buttons (right) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Done chip — theme-aware frosted pill
                Surface(
                    onClick = { configSaved = true },
                    shape = RoundedCornerShape(20.dp),
                    color = if (configSaved) controlsOverlayActive else controlsOverlay,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (configSaved) {
                            Icon(
                                Icons.Default.Check, "Saved",
                                tint = Color.White, modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = if (configSaved) "Saved" else "Done",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // Font style button (Aa) — active when font strip is visible
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (!showColorPicker) controlsOverlayActive
                            else controlsOverlay
                        )
                        .clickable { showColorPicker = false; configSaved = false },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Aa",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(Modifier.width(10.dp))

                // Background color picker button — active when color strip is visible
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (showColorPicker) controlsOverlayActive
                            else controlsOverlay
                        )
                        .clickable { showColorPicker = true; configSaved = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Palette, "Background color",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // ── Centered text input in expanding space ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                TextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        configSaved = false
                        // Dismiss picker when user starts typing
                        if (isPickerVisible) isPickerVisible = false
                    },
                    placeholder = {
                        Text(
                            "Type a status",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 24.sp,
                            fontWeight = font.fontWeight,
                            fontFamily = font.fontFamily,
                            fontStyle = font.fontStyle,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = font.fontWeight,
                        fontFamily = font.fontFamily,
                        fontStyle = font.fontStyle,
                        textAlign = TextAlign.Center
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused && isPickerVisible) {
                                isPickerVisible = false
                            }
                        },
                    maxLines = 8
                )
            }

            // ── Color / font strip ──
            if (showColorPicker) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(statusColors.indices.toList()) { index ->
                        val color = statusColors[index]
                        val isSelected = index == selectedColorIndex
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 38.dp else 32.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color.White.copy(alpha = 0.4f) else Color.Transparent)
                                .padding(if (isSelected) 3.dp else 0.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .clickable {
                                    selectedColorIndex = index
                                    configSaved = false
                                }
                        )
                    }
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(fontPresets.indices.toList()) { index ->
                        val preset = fontPresets[index]
                        val isSelected = index == selectedFontIndex
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color.White else Color.White.copy(alpha = 0.15f))
                                .clickable {
                                    selectedFontIndex = index
                                    configSaved = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset.label,
                                fontFamily = preset.fontFamily,
                                fontWeight = preset.fontWeight,
                                fontStyle = preset.fontStyle,
                                fontSize = 15.sp,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }
            }

            // ── Audience | spacer | emoji + send ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Audience chip — theme-aware
                Surface(
                    onClick = { onAudienceClick?.invoke() },
                    shape = RoundedCornerShape(20.dp),
                    color = controlsOverlay,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("◎", fontSize = 14.sp, color = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = audienceLabel,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Emoji picker button — plain Box (no FAB elevation/tonal artifacts)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (isPickerVisible) controlsOverlayActive else controlsOverlay
                        )
                        .clickable {
                            if (isPickerVisible) {
                                // Don't hide picker yet — show keyboard first so it renders
                                // behind the picker, then dismiss picker via pendingHidePicker
                                pickerSearchFocused = false
                                pendingHidePicker = true
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            } else {
                                focusRequester.freeFocus()
                                keyboardController?.hide()
                                isPickerVisible = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (isPickerVisible) R.drawable.ic_keyboard else R.drawable.ic_emoji
                        ),
                        contentDescription = if (isPickerVisible) "Keyboard" else "Emoji picker",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Send FAB — accent color
                FloatingActionButton(
                    onClick = {
                        if (textFieldValue.text.isNotBlank() && !isUploading) {
                            onPost(textFieldValue.text.trim(), backgroundColor, font.id)
                        }
                    },
                    containerColor = glyphTheme.actionPrimary,
                    modifier = Modifier.size(52.dp)
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = glyphTheme.textInverse,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = glyphTheme.textInverse)
                    }
                }
            }
        } // end content Column

        // ── Emoji picker overlay (rendered last = highest z-order) ──
        if (hostActivity != null) {
            AndroidView(
                factory = { viewContext ->
                    android.widget.FrameLayout(viewContext).apply {
                        clipChildren = false
                        clipToPadding = false
                        val panel = EmojiPickerPanel(viewContext).also { p ->
                            emojiPickerPanel = p
                            p.attachToActivity(hostActivity)
                            p.onSystemEmojiSelected = { emoji ->
                                val current = textFieldValue
                                val start = current.selection.start.coerceAtLeast(0)
                                val end = current.selection.end.coerceAtLeast(0)
                                val newText = current.text.replaceRange(start, end, emoji)
                                textFieldValue = TextFieldValue(
                                    text = newText,
                                    selection = TextRange(start + emoji.length)
                                )
                                configSaved = false
                            }
                            p.onEmojiSelected = { item ->
                                scope.launch {
                                    val downloaded = addRemotePickerItem(item, context, onStart = {}, onFinish = {})
                                    if (downloaded != null) {
                                        isPickerVisible = false
                                        onPickerMediaReady?.invoke(downloaded)
                                    }
                                }
                            }
                            p.onGifSelected = { item ->
                                scope.launch {
                                    val downloaded = addRemotePickerItem(item, context, onStart = {}, onFinish = {})
                                    if (downloaded != null) {
                                        isPickerVisible = false
                                        onPickerMediaReady?.invoke(downloaded)
                                    }
                                }
                            }
                            p.onStickerSelected = { item ->
                                scope.launch {
                                    val downloaded = addRemotePickerItem(item, context, onStart = {}, onFinish = {})
                                    if (downloaded != null) {
                                        isPickerVisible = false
                                        onPickerMediaReady?.invoke(downloaded)
                                    }
                                }
                            }
                            p.onMemeSelected = { item ->
                                scope.launch {
                                    val downloaded = addRemotePickerItem(item, context, onStart = {}, onFinish = {})
                                    if (downloaded != null) {
                                        isPickerVisible = false
                                        onPickerMediaReady?.invoke(downloaded)
                                    }
                                }
                            }
                            p.onSearchFocusChanged = { hasFocus ->
                                pickerSearchFocused = hasFocus
                            }
                            p.onDragClose = {
                                isPickerVisible = false
                                pickerSearchFocused = false
                            }
                        }
                        addView(
                            panel,
                            android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.BOTTOM
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.BottomCenter),
                update = { host ->
                    val panel = emojiPickerPanel ?: return@AndroidView
                    val compactHeightPx = kbdHeightPx.coerceAtLeast(fallbackKeyboardHeightPx)
                    panel.setPickerHeight(compactHeightPx, navBottomPx)
                    val panelLp = panel.layoutParams as? android.widget.FrameLayout.LayoutParams
                        ?: android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM
                        )
                    if (panelLp.gravity != Gravity.BOTTOM) {
                        panelLp.gravity = Gravity.BOTTOM
                        panel.layoutParams = panelLp
                    }
                    host.clipChildren = false
                    host.clipToPadding = false
                    if (isPickerVisible) {
                        if (!panel.isPickerVisible) panel.show(animate = false)
                        if (pickerSearchFocused && imeBottomPx > 0) {
                            panel.expandForSearch(imeBottomPx)
                        } else {
                            panel.collapseToCompact()
                        }
                    } else if (panel.isPickerVisible) {
                        panel.clearAllSearchFocus()
                        panel.hide()
                    }
                }
            )
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
