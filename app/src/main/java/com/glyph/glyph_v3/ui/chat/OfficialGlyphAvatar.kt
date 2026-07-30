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
 *
 * @param modifier The modifier that controls the avatar size — caller MUST
 *   include [Modifier.size] (e.g. `Modifier.size(46.dp)`) for proper sizing.
 * @param size Convenience parameter; when provided, applies [Modifier.size] to
 *   the modifier chain. When null (default), the caller's modifier is used as-is.
 */
private const val LOGO_SCALE = 1.45f

@Composable
fun OfficialGlyphAvatar(
    modifier: Modifier = Modifier,
    size: Dp? = null
) {
    val finalModifier = if (size != null) modifier.size(size) else modifier
    Image(
        painter = painterResource(R.drawable.ic_brand_official),
        contentDescription = "Glyph Official",
        modifier = finalModifier
            .clip(CircleShape)
            .graphicsLayer {
                scaleX = LOGO_SCALE
                scaleY = LOGO_SCALE
            },
        contentScale = ContentScale.Crop
    )
}
