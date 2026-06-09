package com.glyph.glyph_v3.ui.chat.contactinfo

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.ui.settings.GlyphSettingsSwitch
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────
// WhatsApp-style per-chat Notifications screen
// ──────────────────────────────────────────────────────

private val WaDarkBg = Color(0xFF0B141A)
private val WaDarkSurface = Color(0xFF111B21)
private val WaDivider = Color(0xFF222D34)
private val WaGreen = Color(0xFF00A884)
private val WaTextPrimary = Color(0xFFE9EDEF)
private val WaTextSecondary = Color(0xFF8696A0)

class ChatNotificationsActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CHAT_ID = "extra_chat_id"
        private const val EXTRA_CONTACT_NAME = "extra_contact_name"

        fun newIntent(context: Context, chatId: String, contactName: String): Intent {
            return Intent(context, ChatNotificationsActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
        }
    }

    // Ringtone picker results
    private var onNotificationTonePicked: ((String) -> Unit)? = null
    private var onCallRingtonePicked: ((String) -> Unit)? = null

    private val notificationToneLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val name = if (uri != null) {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Default"
        } else "Silent"
        onNotificationTonePicked?.invoke(name)
    }

    private val callRingtoneLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val name = if (uri != null) {
            RingtoneManager.getRingtone(this, uri)?.getTitle(this) ?: "Default"
        } else "Silent"
        onCallRingtonePicked?.invoke(name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                ChatNotificationsScreen(
                    chatId = chatId,
                    onBackClick = { finish() },
                    onPickNotificationTone = { callback ->
                        onNotificationTonePicked = callback
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Notification tone")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        }
                        notificationToneLauncher.launch(intent)
                    },
                    onPickCallRingtone = { callback ->
                        onCallRingtonePicked = callback
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Ringtone")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        }
                        callRingtoneLauncher.launch(intent)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatNotificationsScreen(
    chatId: String,
    onBackClick: () -> Unit,
    onPickNotificationTone: ((String) -> Unit) -> Unit,
    onPickCallRingtone: ((String) -> Unit) -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    val surfaceBg = if (theme.isDark) WaDarkBg else theme.backgroundPrimary
    val sectionBg = if (theme.isDark) WaDarkSurface else theme.backgroundElevated
    val dividerColor = if (theme.isDark) WaDivider else theme.divider
    val textPrimary = if (theme.isDark) WaTextPrimary else theme.textPrimary
    val textSecondary = if (theme.isDark) WaTextSecondary else theme.textSecondary
    val accentGreen = if (theme.isDark) WaGreen else theme.actionPrimary

    // ── Read all current values synchronously (instant render) ──
    var msgMuted by remember { mutableStateOf(ChatSettingsDataStore.isMessagesMuted(context, chatId)) }
    var notifTone by remember { mutableStateOf(ChatSettingsDataStore.getNotificationTone(context, chatId)) }
    var msgVibrate by remember { mutableStateOf(ChatSettingsDataStore.getMessageVibrate(context, chatId)) }
    var callsMuted by remember { mutableStateOf(ChatSettingsDataStore.isCallsMuted(context, chatId)) }
    var callRingtone by remember { mutableStateOf(ChatSettingsDataStore.getCallRingtone(context, chatId)) }
    var callVibrate by remember { mutableStateOf(ChatSettingsDataStore.getCallVibrate(context, chatId)) }
    var statusMuted by remember { mutableStateOf(ChatSettingsDataStore.isStatusMuted(context, chatId)) }

    // Vibrate picker dialog state
    var showMsgVibratePicker by remember { mutableStateOf(false) }
    var showCallVibratePicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = surfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Notifications",
                        color = textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Normal
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = com.glyph.glyph_v3.R.drawable.ic_arrow_back),
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
                .background(surfaceBg)
        ) {
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

            // ── Message Section ─────────────────────────────────────
            SectionHeader("Message", accentGreen)

            // Mute toggle
            NotifToggleRow(
                title = "Mute",
                checked = msgMuted,
                textPrimary = textPrimary,
                onCheckedChange = { newVal ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    msgMuted = newVal
                    scope.launch { ChatSettingsDataStore.setMessagesMuted(context, chatId, newVal) }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Notification tone
            NotifClickRow(
                title = "Notification tone",
                subtitle = notifTone,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                onClick = {
                    onPickNotificationTone { picked ->
                        notifTone = picked
                        scope.launch { ChatSettingsDataStore.setNotificationTone(context, chatId, picked) }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Vibrate
            NotifClickRow(
                title = "Vibrate",
                subtitle = msgVibrate,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                onClick = { showMsgVibratePicker = true }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Advanced settings placeholder
            NotifClickRow(
                title = "Advanced settings",
                subtitle = null,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                onClick = { /* Advanced notification settings */ }
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

            // ── Call Section ────────────────────────────────────────
            SectionHeader("Call", accentGreen)

            // Ringtone
            NotifClickRow(
                title = "Ringtone",
                subtitle = callRingtone,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                onClick = {
                    onPickCallRingtone { picked ->
                        callRingtone = picked
                        scope.launch { ChatSettingsDataStore.setCallRingtone(context, chatId, picked) }
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Vibrate
            NotifClickRow(
                title = "Vibrate",
                subtitle = callVibrate,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                onClick = { showCallVibratePicker = true }
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)

            // ── Status Section ──────────────────────────────────────
            SectionHeader("Status", accentGreen)

            NotifToggleRow(
                title = "Mute",
                checked = statusMuted,
                textPrimary = textPrimary,
                onCheckedChange = { newVal ->
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    statusMuted = newVal
                    scope.launch { ChatSettingsDataStore.setStatusMuted(context, chatId, newVal) }
                }
            )
        }
    }

    // ── Vibrate picker dialogs ──────────────────────────────────────
    if (showMsgVibratePicker) {
        VibratePickerDialog(
            currentMode = msgVibrate,
            textPrimary = textPrimary,
            accentGreen = accentGreen,
            surfaceBg = if (theme.isDark) WaDarkSurface else theme.backgroundElevated,
            onDismiss = { showMsgVibratePicker = false },
            onSelect = { mode ->
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                msgVibrate = mode
                showMsgVibratePicker = false
                scope.launch { ChatSettingsDataStore.setMessageVibrate(context, chatId, mode) }
            }
        )
    }

    if (showCallVibratePicker) {
        VibratePickerDialog(
            currentMode = callVibrate,
            textPrimary = textPrimary,
            accentGreen = accentGreen,
            surfaceBg = if (theme.isDark) WaDarkSurface else theme.backgroundElevated,
            onDismiss = { showCallVibratePicker = false },
            onSelect = { mode ->
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                callVibrate = mode
                showCallVibratePicker = false
                scope.launch { ChatSettingsDataStore.setCallVibrate(context, chatId, mode) }
            }
        )
    }
}

// ──────────────────────────────────────────────────────
// Reusable composables for the Notifications screen
// ──────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, color: Color) {
    Text(
        text = title,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
    )
}

@Composable
private fun NotifToggleRow(
    title: String,
    checked: Boolean,
    textPrimary: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = textPrimary,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        GlyphSettingsSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun NotifClickRow(
    title: String,
    subtitle: String?,
    textPrimary: Color,
    textSecondary: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            color = textPrimary,
            fontSize = 16.sp
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = textSecondary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun VibratePickerDialog(
    currentMode: String,
    textPrimary: Color,
    accentGreen: Color,
    surfaceBg: Color,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val modes = listOf(
        ChatSettingsDataStore.VIBRATE_DEFAULT,
        ChatSettingsDataStore.VIBRATE_OFF,
        ChatSettingsDataStore.VIBRATE_SHORT,
        ChatSettingsDataStore.VIBRATE_LONG
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surfaceBg,
        title = {
            Text("Vibrate", color = textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        },
        text = {
            Column {
                modes.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onSelect(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = accentGreen,
                                unselectedColor = textPrimary.copy(alpha = 0.5f)
                            )
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = mode,
                            color = textPrimary,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = accentGreen)
            }
        }
    )
}
