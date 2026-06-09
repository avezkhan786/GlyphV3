package com.glyph.glyph_v3.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.LiveAudioAudience
import com.glyph.glyph_v3.data.models.User

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveAudioSettingsScreen(
    state: LiveAudioSettingsState,
    onToggleShareMic: (Boolean) -> Unit,
    onSetAudience: (LiveAudioAudience) -> Unit,
    onToggleUser: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onStopSharing: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val filteredUsers by remember(state.allUsers, state.searchQuery) {
        derivedStateOf {
            if (state.searchQuery.isBlank()) {
                state.allUsers
            } else {
                state.allUsers.filter {
                    it.username.contains(state.searchQuery, ignoreCase = true) ||
                        it.phoneNumber.contains(state.searchQuery)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live Audio Sharing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onSave) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ── Master Toggle ────────────────────────────────────
            item {
                MasterToggleSection(
                    enabled = state.shareMicEnabled,
                    onToggle = onToggleShareMic
                )
            }

            // ── Active Session Indicator ─────────────────────────
            if (state.isStreaming) {
                item {
                    ActiveSessionCard(
                        listenerCount = state.listenerCount,
                        onStopSharing = onStopSharing
                    )
                }
            }

            // ── Audience Control ─────────────────────────────────
            item {
                AnimatedVisibility(
                    visible = state.shareMicEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    AudienceSection(
                        audience = state.audience,
                        onSetAudience = onSetAudience
                    )
                }
            }

            // ── User Selection ───────────────────────────────────
            if (state.shareMicEnabled && state.audience == LiveAudioAudience.SELECTED_USERS) {
                item {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        placeholder = { Text("Search users…") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (filteredUsers.isEmpty()) {
                    item {
                        Text(
                            text = "No users found.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(
                        items = filteredUsers,
                        key = { it.id },
                        contentType = { "user" }
                    ) { user ->
                        UserSelectionRow(
                            user = user,
                            isSelected = user.id in state.allowedUserIds,
                            onClick = { onToggleUser(user.id) }
                        )
                    }
                }
            }

            // ── Privacy Notes ────────────────────────────────────
            item {
                Spacer(Modifier.height(24.dp))
                PrivacyNotesSection()
            }
        }
    }
}

@Composable
private fun MasterToggleSection(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_live_audio),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Enable Live Audio Sharing",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    if (enabled) "Others can request to listen"
                    else "Sharing is disabled",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun ActiveSessionCard(
    listenerCount: Int,
    onStopSharing: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Currently streaming to $listenerCount user(s)",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onStopSharing) {
                Text(
                    "Stop Now",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun AudienceSection(
    audience: LiveAudioAudience,
    onSetAudience: (LiveAudioAudience) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Who can listen to my live audio",
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val options = listOf(
            LiveAudioAudience.EVERYONE to "Everyone",
            LiveAudioAudience.CONTACTS to "My Contacts",
            LiveAudioAudience.SELECTED_USERS to "Only Selected Users"
        )

        options.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSetAudience(value) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = audience == value,
                    onClick = { onSetAudience(value) }
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, fontSize = 15.sp)
                    if (value == LiveAudioAudience.SELECTED_USERS) {
                        Text(
                            "Recommended for privacy",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserSelectionRow(
    user: User,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(user.profileImageUrl)
                .build(),
            contentDescription = user.username,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.ic_default_avatar),
            error = painterResource(R.drawable.ic_default_avatar)
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                user.username,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (user.phoneNumber.isNotBlank()) {
                Text(
                    user.phoneNumber,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() }
        )
    }
}

@Composable
private fun PrivacyNotesSection() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Privacy",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            PrivacyNote("Your microphone is only active when someone is listening")
            Spacer(Modifier.height(4.dp))
            PrivacyNote("A notification will be shown when audio is being shared")
            Spacer(Modifier.height(4.dp))
            PrivacyNote("You can stop sharing at any time from the notification or this screen")
        }
    }
}

@Composable
private fun PrivacyNote(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "•",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp, top = 1.dp)
        )
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
