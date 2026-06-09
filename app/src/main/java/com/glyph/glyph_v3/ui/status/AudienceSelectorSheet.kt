package com.glyph.glyph_v3.ui.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.data.models.StatusPrivacyMode
import com.glyph.glyph_v3.ui.theme.glyphTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudienceSelectorSheet(
    currentMode: StatusPrivacyMode,
    excludedCount: Int,
    includedCount: Int,
    onModeSelected: (StatusPrivacyMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = glyphTheme.backgroundElevated,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .padding(0.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = glyphTheme.borderSecondary
                ) {}
            }
        }
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "Who can see my status",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = glyphTheme.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            AudienceOption(
                icon = Icons.Default.People,
                title = "My contacts",
                subtitle = "Share with all contacts",
                isSelected = currentMode == StatusPrivacyMode.MY_CONTACTS,
                onClick = { onModeSelected(StatusPrivacyMode.MY_CONTACTS) }
            )

            AudienceOption(
                icon = Icons.Default.PersonOff,
                title = "My contacts except...",
                subtitle = if (excludedCount > 0) "$excludedCount excluded" else "Choose contacts to hide from",
                isSelected = currentMode == StatusPrivacyMode.MY_CONTACTS_EXCEPT,
                onClick = { onModeSelected(StatusPrivacyMode.MY_CONTACTS_EXCEPT) }
            )

            AudienceOption(
                icon = Icons.Default.Lock,
                title = "Only share with...",
                subtitle = if (includedCount > 0) "$includedCount included" else "Choose specific contacts",
                isSelected = currentMode == StatusPrivacyMode.ONLY_SHARE_WITH,
                onClick = { onModeSelected(StatusPrivacyMode.ONLY_SHARE_WITH) }
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Changes apply to future statuses",
                fontSize = 12.sp,
                color = glyphTheme.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }
}

@Composable
private fun AudienceOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isSelected) glyphTheme.actionPrimary else glyphTheme.iconSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = glyphTheme.textPrimary
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                color = glyphTheme.textSecondary
            )
        }
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = glyphTheme.actionPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** Returns a friendly label for the audience chip based on privacy mode. */
fun audienceLabelFor(mode: StatusPrivacyMode, excludedCount: Int = 0, includedCount: Int = 0): String {
    return when (mode) {
        StatusPrivacyMode.MY_CONTACTS -> "Status (Contacts)"
        StatusPrivacyMode.MY_CONTACTS_EXCEPT -> "Contacts except $excludedCount"
        StatusPrivacyMode.ONLY_SHARE_WITH -> "Only $includedCount contacts"
    }
}
