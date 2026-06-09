package com.glyph.glyph_v3.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieSettingsScreen(
    state: WalkieTalkieSettingsState,
    onToggleAutoAccept: (Boolean) -> Unit,
    onScopeChanged: (WalkieTalkieAutoAcceptScope) -> Unit,
    onToggleUser: (String) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val filteredContacts by remember(state.contacts, state.searchQuery) {
        derivedStateOf {
            if (state.searchQuery.isBlank()) {
                state.contacts
            } else {
                state.contacts.filter {
                    it.username.contains(state.searchQuery, ignoreCase = true) ||
                        it.phoneNumber.contains(state.searchQuery, ignoreCase = true)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Walkie-Talkie") },
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
            item {
                WalkieTalkieAutoAcceptToggle(
                    enabled = state.autoAcceptEnabled,
                    onToggle = onToggleAutoAccept
                )
            }

            item {
                AnimatedVisibility(
                    visible = state.autoAcceptEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    AutoAcceptScopeSection(
                        scope = state.scope,
                        onScopeChanged = onScopeChanged
                    )
                }
            }

            if (state.autoAcceptEnabled && state.scope == WalkieTalkieAutoAcceptScope.SELECTED_USERS) {
                item {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = onSearchChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        placeholder = { Text("Search contacts…") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (filteredContacts.isEmpty()) {
                    item {
                        Text(
                            text = "No eligible contacts found.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    items(
                        items = filteredContacts,
                        key = { it.id },
                        contentType = { "contact" }
                    ) { user ->
                        AllowedUserRow(
                            user = user,
                            isSelected = user.id in state.allowedUserIds,
                            onClick = { onToggleUser(user.id) }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                WalkieTalkiePrivacyNotes()
            }
        }
    }
}

@Composable
private fun WalkieTalkieAutoAcceptToggle(
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
                painter = painterResource(R.drawable.ic_walkie_talkie),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-Accept Incoming WT Calls",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    text = if (enabled) {
                        "Incoming walkie-talkie calls connect hands-free"
                    } else {
                        "Manual accept is required for incoming WT calls"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AutoAcceptScopeSection(
    scope: WalkieTalkieAutoAcceptScope,
    onScopeChanged: (WalkieTalkieAutoAcceptScope) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Who can auto-connect to this device",
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        listOf(
            WalkieTalkieAutoAcceptScope.CONTACTS to "My contacts",
            WalkieTalkieAutoAcceptScope.SELECTED_USERS to "Only selected contacts"
        ).forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onScopeChanged(value) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = scope == value, onClick = { onScopeChanged(value) })
                Spacer(modifier = Modifier.size(12.dp))
                Column {
                    Text(text = label, fontSize = 15.sp)
                    Text(
                        text = if (value == WalkieTalkieAutoAcceptScope.CONTACTS) {
                            "Any existing contact can start hands-free listening"
                        } else {
                            "Only contacts you explicitly allow can auto-connect"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AllowedUserRow(
    user: User,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.username,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (user.phoneNumber.isNotBlank()) {
                Text(
                    text = user.phoneNumber,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Checkbox(checked = isSelected, onCheckedChange = { onClick() })
    }
}

@Composable
private fun WalkieTalkiePrivacyNotes() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Privacy",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            WalkieTalkiePrivacyNote("Auto-accept is off by default for privacy")
            Spacer(modifier = Modifier.height(4.dp))
            WalkieTalkiePrivacyNote("A foreground notification remains visible during hands-free listening")
            Spacer(modifier = Modifier.height(4.dp))
            WalkieTalkiePrivacyNote("You can end the session or disable auto-accept from the active notification")
        }
    }
}

@Composable
private fun WalkieTalkiePrivacyNote(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(text = "• ", color = MaterialTheme.colorScheme.primary)
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
}