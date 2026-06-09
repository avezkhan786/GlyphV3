package com.glyph.glyph_v3.ui.chat.contactinfo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────
// WhatsApp-style Disappearing Messages screen
// ──────────────────────────────────────────────────────

private val WaDarkBg = Color(0xFF0B141A)
private val WaDarkSurface = Color(0xFF111B21)
private val WaDivider = Color(0xFF222D34)
private val WaGreen = Color(0xFF00A884)
private val WaTextPrimary = Color(0xFFE9EDEF)
private val WaTextSecondary = Color(0xFF8696A0)
private val WaGreenBanner = Color(0xFF103629)

class DisappearingMessagesActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CHAT_ID = "extra_chat_id"
        private const val EXTRA_CONTACT_NAME = "extra_contact_name"

        fun newIntent(context: Context, chatId: String, contactName: String): Intent {
            return Intent(context, DisappearingMessagesActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                DisappearingMessagesScreen(
                    chatId = chatId,
                    contactName = contactName,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisappearingMessagesScreen(
    chatId: String,
    contactName: String,
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

    var selectedTimer by remember {
        mutableStateOf(ChatSettingsDataStore.getDisappearingTimer(context, chatId))
    }

    Scaffold(
        containerColor = surfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Disappearing messages",
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

            // ── Illustration Area ───────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (theme.isDark) Color(0xFF0E2620) else accentGreen.copy(alpha = 0.08f))
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                // Stylized disappearing messages illustration
                Box(
                    modifier = Modifier.size(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Large circle
                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .clip(CircleShape)
                            .background(accentGreen.copy(alpha = 0.3f))
                    )
                    // Smaller accent circle
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .offset(x = 20.dp, y = (-15).dp)
                            .clip(CircleShape)
                            .background(accentGreen.copy(alpha = 0.15f))
                    )
                    // Timer icon in center
                    Icon(
                        painter = painterResource(id = R.drawable.ic_disappearing),
                        contentDescription = null,
                        tint = accentGreen,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Description text ────────────────────────────────────
            Text(
                text = "Make messages in this chat disappear",
                color = textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "For more privacy and storage, new messages will disappear from this chat for everyone after the selected duration except when kept. Anyone in the chat can change this setting. Learn more",
                color = textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

            // ── Message Timer Section ───────────────────────────────
            Text(
                text = "Message timer",
                color = textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 12.dp)
            )

            val timerOptions = listOf(
                ChatSettingsDataStore.DISAPPEARING_24H to "24 hours",
                ChatSettingsDataStore.DISAPPEARING_7D to "7 days",
                ChatSettingsDataStore.DISAPPEARING_90D to "90 days",
                ChatSettingsDataStore.DISAPPEARING_OFF to "Off"
            )

            timerOptions.forEach { (value, label) ->
                TimerOptionRow(
                    label = label,
                    isSelected = selectedTimer == value,
                    accentGreen = accentGreen,
                    textPrimary = textPrimary,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        selectedTimer = value
                        scope.launch {
                            ChatSettingsDataStore.setDisappearingTimer(context, chatId, value)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
            Spacer(modifier = Modifier.height(16.dp))

            // ── Default Timer Suggestion ────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { /* Open default timer settings */ }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_disappearing),
                    contentDescription = null,
                    tint = textSecondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Try a default message timer",
                        color = textPrimary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Start your new chats with disappearing messages",
                        color = textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TimerOptionRow(
    label: String,
    isSelected: Boolean,
    accentGreen: Color,
    textPrimary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = accentGreen,
                unselectedColor = textPrimary.copy(alpha = 0.4f)
            )
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = textPrimary,
            fontSize = 16.sp
        )
    }
}
