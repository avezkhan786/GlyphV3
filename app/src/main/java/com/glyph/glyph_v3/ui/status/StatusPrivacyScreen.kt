package com.glyph.glyph_v3.ui.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.StatusPrivacyMode
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.ui.theme.GlyphThemeTokens
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusPrivacyScreen(
    state: StatusPrivacyUiState,
    onModeChange: (StatusPrivacyMode) -> Unit,
    onToggleExcluded: (String) -> Unit,
    onToggleIncluded: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    val theme = glyphTheme
    val context = LocalContext.current
    val currentTheme = ThemeManager.getCurrentTheme(context)
    val colors = remember(currentTheme, theme) {
        statusPrivacyColors(currentTheme, theme)
    }

    val backgroundModifier = if (theme.gradientPrimary != null) {
        Modifier.background(theme.gradientPrimary!!)
    } else {
        Modifier.background(colors.screenBackground)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(backgroundModifier)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Status Privacy",
                            color = colors.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.title)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = if (theme.gradientPrimary != null) Color.Transparent else colors.topBarBackground,
                        titleContentColor = colors.title,
                        navigationIconContentColor = colors.title,
                        actionIconContentColor = colors.accent
                    ),
                    actions = {
                        IconButton(
                            onClick = onSave,
                            enabled = !state.isSaving
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.accent
                                )
                            } else {
                                Icon(Icons.Default.Check, "Save", tint = colors.accent)
                            }
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
                    CircularProgressIndicator(color = colors.accent)
                }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StatusPrivacyHeroCard(colors = colors)
                }

                item {
                    SectionLabel(
                        text = "Audience",
                        colors = colors
                    )
                }

                item {
                    PrivacyOptionCard(
                        title = "My contacts",
                        subtitle = "All your contacts can see your status updates.",
                        description = "Best when you want sharing to feel effortless.",
                        isSelected = state.mode == StatusPrivacyMode.MY_CONTACTS,
                        colors = colors,
                        onClick = { onModeChange(StatusPrivacyMode.MY_CONTACTS) }
                    )
                }

                item {
                    PrivacyOptionCard(
                        title = "My contacts except...",
                        subtitle = if (state.mode == StatusPrivacyMode.MY_CONTACTS_EXCEPT && state.excludedContacts.isNotEmpty()) {
                            "${state.excludedContacts.size} contact${if (state.excludedContacts.size > 1) "s" else ""} excluded"
                        } else {
                            "All your contacts except the people you choose."
                        },
                        description = "Use this for a mostly-open list with a few exceptions.",
                        isSelected = state.mode == StatusPrivacyMode.MY_CONTACTS_EXCEPT,
                        colors = colors,
                        onClick = { onModeChange(StatusPrivacyMode.MY_CONTACTS_EXCEPT) }
                    )
                }

                item {
                    PrivacyOptionCard(
                        title = "Only share with...",
                        subtitle = if (state.mode == StatusPrivacyMode.ONLY_SHARE_WITH && state.includedContacts.isNotEmpty()) {
                            "${state.includedContacts.size} contact${if (state.includedContacts.size > 1) "s" else ""} selected"
                        } else {
                            "Only the contacts you choose can view your status."
                        },
                        description = "Ideal for close friends, family, or small groups.",
                        isSelected = state.mode == StatusPrivacyMode.ONLY_SHARE_WITH,
                        colors = colors,
                        onClick = { onModeChange(StatusPrivacyMode.ONLY_SHARE_WITH) }
                    )
                }

                if (state.mode == StatusPrivacyMode.MY_CONTACTS_EXCEPT || state.mode == StatusPrivacyMode.ONLY_SHARE_WITH) {
                    item {
                        SectionLabel(
                            text = if (state.mode == StatusPrivacyMode.MY_CONTACTS_EXCEPT) {
                                "Contacts to hide from"
                            } else {
                                "Contacts who can see your updates"
                            },
                            colors = colors
                        )
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                            shape = RoundedCornerShape(24.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorder)
                        ) {
                            Text(
                                text = if (state.mode == StatusPrivacyMode.MY_CONTACTS_EXCEPT) {
                                    "Choose the contacts that should not see your status. Everyone else in your contacts list will still see it."
                                } else {
                                    "Choose the specific contacts that can see your status. Nobody else will be included."
                                },
                                color = colors.description,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                    }

                    if (state.allContacts.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
                                shape = RoundedCornerShape(24.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorder)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No contacts found",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.subtitle
                                    )
                                }
                            }
                        }
                    } else {
                        items(state.allContacts, key = { it.id }) { contact ->
                            val isChecked = if (state.mode == StatusPrivacyMode.MY_CONTACTS_EXCEPT) {
                                contact.id in state.excludedContacts
                            } else {
                                contact.id in state.includedContacts
                            }

                            ContactCheckRow(
                                user = contact,
                                isChecked = isChecked,
                                colors = colors,
                                onClick = {
                                    if (state.mode == StatusPrivacyMode.MY_CONTACTS_EXCEPT) {
                                        onToggleExcluded(contact.id)
                                    } else {
                                        onToggleIncluded(contact.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Immutable
private data class StatusPrivacyColors(
    val screenBackground: Color,
    val topBarBackground: Color,
    val cardBackground: Color,
    val cardBorder: Color,
    val selectedCardBackground: Color,
    val selectedCardBorder: Color,
    val title: Color,
    val subtitle: Color,
    val description: Color,
    val sectionLabel: Color,
    val accent: Color,
    val onAccent: Color,
    val controlInactive: Color,
    val avatarBackground: Color
)

private fun statusPrivacyColors(
    currentTheme: String,
    theme: GlyphThemeTokens
): StatusPrivacyColors {
    val accent = when (currentTheme) {
        ThemeManager.THEME_PASTEL_SKY -> theme.iconPrimary
        else -> theme.borderFocus
    }
    val cardBackground = when (currentTheme) {
        ThemeManager.THEME_PASTEL_SKY -> lerp(theme.backgroundElevated, Color.White, 0.18f)
        else -> theme.backgroundElevated
    }

    return StatusPrivacyColors(
        screenBackground = theme.backgroundPrimary,
        topBarBackground = theme.surfaceHeader,
        cardBackground = cardBackground,
        cardBorder = theme.borderPrimary.copy(alpha = 0.72f),
        selectedCardBackground = lerp(cardBackground, accent, 0.12f),
        selectedCardBorder = accent.copy(alpha = 0.9f),
        title = theme.textPrimary,
        subtitle = theme.textSecondary,
        description = theme.textTertiary,
        sectionLabel = accent,
        accent = accent,
        onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White,
        controlInactive = theme.textTertiary,
        avatarBackground = lerp(cardBackground, theme.backgroundSecondary, 0.4f)
    )
}

@Composable
private fun StatusPrivacyHeroCard(colors: StatusPrivacyColors) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Who can see my status updates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.title
            )
            Text(
                text = "Your current privacy mode is applied to every new update. Adjust it here without affecting performance or share flow.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.description
            )
        }
    }
}

@Composable
private fun SectionLabel(
    text: String,
    colors: StatusPrivacyColors
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = colors.sectionLabel,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun PrivacyOptionCard(
    title: String,
    subtitle: String,
    description: String,
    isSelected: Boolean,
    colors: StatusPrivacyColors,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) colors.selectedCardBackground else colors.cardBackground
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) colors.selectedCardBorder else colors.cardBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = colors.accent,
                    unselectedColor = colors.controlInactive
                )
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.title
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) colors.title else colors.subtitle,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.description,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (isSelected) {
                Text(
                    text = "Selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ContactCheckRow(
    user: User,
    isChecked: Boolean,
    colors: StatusPrivacyColors,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isChecked) colors.selectedCardBackground else colors.cardBackground
        ),
        border = androidx.compose.foundation.BorderStroke(
            if (isChecked) 1.5.dp else 1.dp,
            if (isChecked) colors.selectedCardBorder else colors.cardBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isChecked) 3.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.avatarBackground),
                contentAlignment = Alignment.Center
            )
            {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(user.profileImageUrl.ifEmpty { null })
                        .placeholder(R.drawable.ic_default_avatar)
                        .error(R.drawable.ic_default_avatar)
                        .build(),
                    contentDescription = "Avatar",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ContactDisplayNameResolver.getDisplayName(
                        otherUserId = user.id,
                        remoteProfileName = user.username,
                        remotePhoneNumber = user.phoneNumber
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = user.bio.ifEmpty { user.phoneNumber },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.accent,
                    uncheckedColor = colors.controlInactive,
                    checkmarkColor = colors.onAccent
                )
            )
        }
    }
}
