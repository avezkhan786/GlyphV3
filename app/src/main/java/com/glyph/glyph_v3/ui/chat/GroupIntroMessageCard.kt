package com.glyph.glyph_v3.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.glyph.glyph_v3.R

private val IntroCardBackground = Color(0xFF111B21)
private val IntroCardBorder = Color(0xFF334047)
private val IntroCardPrimary = Color(0xFFF7F8F8)
private val IntroCardSecondary = Color(0xFF8E9AA3)
private val IntroCardIconBackground = Color(0xFF4A3828)
private val IntroCardIconTint = Color(0xFFF6CF74)
private val IntroEncryptionBackground = Color(0xFF182229)
private val IntroEncryptionTint = Color(0xFFE9C986)

@Composable
fun GroupIntroMessageCard(
    groupName: String,
    groupAvatarUrl: String,
    description: String,
    memberCount: Int,
    onDescriptionClick: () -> Unit,
    onAddMembersClick: () -> Unit,
    onInviteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IntroEncryptionBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lock),
                contentDescription = null,
                tint = IntroEncryptionTint,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Messages and calls are end-to-end encrypted. Only people in this chat can read, listen to, or share them. Learn more",
                color = IntroEncryptionTint,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(IntroCardBackground)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(IntroCardIconBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_group),
                        contentDescription = null,
                        tint = IntroCardIconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    if (groupAvatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = groupAvatarUrl,
                            contentDescription = groupName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "You created this group",
                    color = IntroCardPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Group · $memberCount members",
                    color = IntroCardSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = description.ifBlank { "Add description..." },
                    color = if (description.isBlank()) IntroCardSecondary else IntroCardPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (description.isBlank()) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable(onClick = onDescriptionClick)
                )
                Spacer(modifier = Modifier.height(12.dp))
                GroupIntroActionRow(
                    icon = R.drawable.ic_person_add,
                    label = "Add members",
                    onClick = onAddMembersClick
                )
                Spacer(modifier = Modifier.height(8.dp))
                GroupIntroActionRow(
                    icon = R.drawable.ic_link,
                    label = "Invite via link or QR code",
                    onClick = onInviteClick
                )
            }
        }
    }
}

@Composable
private fun GroupIntroActionRow(
    icon: Int,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .border(1.25.dp, IntroCardBorder, RoundedCornerShape(27.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = IntroCardPrimary,
            modifier = Modifier.size(21.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = IntroCardPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
