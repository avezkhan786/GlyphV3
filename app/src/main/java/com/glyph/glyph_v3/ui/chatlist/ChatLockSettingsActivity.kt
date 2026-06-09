package com.glyph.glyph_v3.ui.chatlist

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.ui.settings.GlyphSettingsSwitch
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.launch

class ChatLockSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                ChatLockSettingsScreen(
                    onBackClick = { finish() },
                    onSecretCodeClick = { isSet ->
                        if (!isSet) {
                            startActivity(Intent(this, HideLockedChatsActivity::class.java))
                        } else {
                            startActivity(Intent(this, HideLockedChatsActivity::class.java))
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatLockSettingsScreen(
    onBackClick: () -> Unit,
    onSecretCodeClick: (isSet: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val surfaceBg = glyphTheme.backgroundPrimary
    val textPrimary = glyphTheme.textPrimary
    val textSecondary = glyphTheme.textSecondary
    val dividerColor = glyphTheme.bubbleBorder

    var hideLockedChats by remember {
        mutableStateOf(ChatSettingsDataStore.isHideLockedChatsEnabled(context))
    }
    var isSecretCodeSet by remember {
        mutableStateOf(ChatSettingsDataStore.isSecretCodeSet(context))
    }

    // Refresh on resume
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hideLockedChats = ChatSettingsDataStore.isHideLockedChatsEnabled(context)
                isSecretCodeSet = ChatSettingsDataStore.isSecretCodeSet(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Snackbar host
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        containerColor = surfaceBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Chat lock settings",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "Back",
                            tint = glyphTheme.iconPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceBg,
                    titleContentColor = textPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

            // Hide locked chats toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (isSecretCodeSet) {
                            hideLockedChats = !hideLockedChats
                            scope.launch {
                                ChatSettingsDataStore.setHideLockedChatsEnabled(context, hideLockedChats)
                            }
                        } else {
                            onSecretCodeClick(false)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hide locked chats",
                        color = textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Locked chats won't show on your chat list. To see them, enter your secret code into the search bar in your Chats tab.",
                        color = textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                GlyphSettingsSwitch(
                    checked = hideLockedChats,
                    onCheckedChange = { newVal ->
                        if (isSecretCodeSet) {
                            hideLockedChats = newVal
                            scope.launch {
                                ChatSettingsDataStore.setHideLockedChatsEnabled(context, newVal)
                            }
                        } else {
                            onSecretCodeClick(false)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secret code row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSecretCodeClick(isSecretCodeSet) }
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Secret code",
                        color = textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Unlock your chats with a secret code instead of your device's passcode. You can also open them on some linked devices.",
                        color = textSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (isSecretCodeSet) "On" else "Off",
                    color = textSecondary,
                    fontSize = 16.sp
                )
            }
        }
    }
}
