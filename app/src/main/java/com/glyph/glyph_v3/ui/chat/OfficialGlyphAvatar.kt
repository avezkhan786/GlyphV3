package com.glyph.glyph_v3.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glyph.glyph_v3.R

/**
 * "Glyph Official" avatar — the brand app-icon mark
 * ([R.drawable.ic_brand_official]: indigo `#0E0254` field with the gradient
 * "loop" glyph), cropped to a circle so it reads as the recognizable app mark.
 * Shared by the chat-list row ([com.glyph.glyph_v3.ui.chatlist.ChatListScreen])
 * and [OfficialChatActivity] so it is identical on every surface.
 *
 * The SVG source (`app/inspiration/new_app_icon/brand_new_icon.svg`) also carries
 * placeholder `<text>` ("COMPANY NAME" / "SLOGAN HERE") which a VectorDrawable
 * cannot render and which is illegible at avatar scale, so it is omitted here.
 * The drawable is drawn in its own colors with NO tint at `contentScale`
 * of the container, so the brand mark sits inset inside the circle (a thin
 * indigo margin) and never overflows the badge. The Box clips it to a circle.
 */
private val brandFieldBackground = Color(0xFF0E0254)

@Composable
fun OfficialGlyphAvatar(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    contentScale: Float = 0.9f
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(brandFieldBackground),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_brand_official),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(size * contentScale)
        )
    }
}
