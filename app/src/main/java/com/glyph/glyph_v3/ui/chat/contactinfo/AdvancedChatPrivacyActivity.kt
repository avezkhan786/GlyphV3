package com.glyph.glyph_v3.ui.chat.contactinfo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.ui.settings.GlyphSettingsSwitch
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────
// WhatsApp-style Advanced Chat Privacy screen
// ──────────────────────────────────────────────────────

private val WaDarkBg = Color(0xFF0B141A)
private val WaDarkSurface = Color(0xFF111B21)
private val WaDivider = Color(0xFF222D34)
private val WaGreen = Color(0xFF00A884)
private val WaTextPrimary = Color(0xFFE9EDEF)
private val WaTextSecondary = Color(0xFF8696A0)
private val WaGreenBanner = Color(0xFF103629)

class AdvancedChatPrivacyActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CHAT_ID = "extra_chat_id"

        fun newIntent(context: Context, chatId: String): Intent {
            return Intent(context, AdvancedChatPrivacyActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                AdvancedChatPrivacyScreen(
                    chatId = chatId,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedChatPrivacyScreen(
    chatId: String,
    onBackClick: () -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val surfaceBg = if (theme.isDark) WaDarkBg else theme.backgroundPrimary
    val dividerColor = if (theme.isDark) WaDivider else theme.divider
    val textPrimary = if (theme.isDark) WaTextPrimary else theme.textPrimary
    val textSecondary = if (theme.isDark) WaTextSecondary else theme.textSecondary
    val accentGreen = if (theme.isDark) WaGreen else theme.actionPrimary
    val bannerBg = if (theme.isDark) WaGreenBanner else accentGreen.copy(alpha = 0.12f)

    var privacyEnabled by remember {
        mutableStateOf(ChatSettingsDataStore.isAdvancedPrivacyEnabled(context, chatId))
    }

    Scaffold(
        containerColor = surfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Advanced chat privacy",
                        color = textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceBg
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .background(surfaceBg)
        ) {
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

            // ── Informational Banner ────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bannerBg)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info),
                        contentDescription = null,
                        tint = accentGreen,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = buildAnnotatedString {
                            append("All chats are private by default. ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("This is true whether you turn this setting on or not.")
                            }
                            append(" Learn more")
                        },
                        color = textPrimary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Illustration ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Document icon
                    Box(
                        modifier = Modifier
                            .size(width = 56.dp, height = 72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (theme.isDark) Color(0xFF1F2C33) else Color(0xFFE8E8E8)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.Start,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            repeat(3) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(textSecondary.copy(alpha = 0.3f))
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    // Lock icon
                    Box(
                        modifier = Modifier
                            .size(width = 52.dp, height = 64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentGreen.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chat_lock),
                            contentDescription = null,
                            tint = accentGreen,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // ── Description ─────────────────────────────────────────
            Text(
                text = "Limit how messages and media from this chat can be shared outside of Glyph",
                color = textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "If you turn this on, people in this chat:",
                color = textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Restriction items ───────────────────────────────────
            RestrictionItem(
                icon = R.drawable.ic_media_visibility,
                text = "Can't save media to their device gallery automatically",
                textPrimary = textPrimary,
                iconTint = textSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            RestrictionItem(
                icon = R.drawable.ic_translate_glyph,
                text = "Can't ask AI questions, or to create images or summaries in this chat",
                textPrimary = textPrimary,
                iconTint = textSecondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            RestrictionItem(
                icon = R.drawable.ic_storage,
                text = "Can't export the chat",
                textPrimary = textPrimary,
                iconTint = textSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

            // ── Toggle Row ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Advanced chat privacy",
                    color = textPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                GlyphSettingsSwitch(
                    checked = privacyEnabled,
                    onCheckedChange = { newVal ->
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        privacyEnabled = newVal
                        scope.launch {
                            ChatSettingsDataStore.setAdvancedPrivacyEnabled(context, chatId, newVal)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RestrictionItem(
    icon: Int,
    text: String,
    textPrimary: Color,
    iconTint: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            color = textPrimary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
