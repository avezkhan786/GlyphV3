package com.glyph.glyph_v3.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glyph.glyph_v3.R

/**
 * "Glyph Official" avatar — the app launcher icon
 * ([R.drawable.ic_brand_official]), clipped to a circle and slightly
 * zoomed so the center logo fills the badge. Shared by the chat-list
 * row ([com.glyph.glyph_v3.ui.chatlist.ChatListScreen]),
 * [OfficialChatActivity], and the status screen.
 */
private const val LOGO_SCALE = 1.25f

@Composable
fun OfficialGlyphAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    Image(
        painter = painterResource(R.drawable.ic_brand_official),
        contentDescription = "Glyph Official",
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .graphicsLayer {
                scaleX = LOGO_SCALE
                scaleY = LOGO_SCALE
            },
        contentScale = ContentScale.Crop
    )
}
