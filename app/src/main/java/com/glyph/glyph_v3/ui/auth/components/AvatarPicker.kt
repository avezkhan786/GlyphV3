package com.glyph.glyph_v3.ui.auth.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Circular avatar picker with a camera icon overlay.
 *
 * Displays the selected image URI via Coil [AsyncImage], or a default avatar
 * placeholder. A floating camera badge sits at the bottom-end of the circle.
 * Tapping either the avatar or the badge triggers [onPickImage].
 *
 * @param imageUri The currently selected image URI, or null for default.
 * @param onPickImage Called when the user taps to change the photo.
 * @param size The diameter of the avatar circle.
 * @param modifier Modifier for the outer container.
 */
@Composable
fun AvatarPicker(
    imageUri: Uri?,
    onPickImage: () -> Unit,
    size: Int = 140,
    modifier: Modifier = Modifier
) {
    val theme = glyphTheme
    val sizeDp = size.dp
    val badgeSizeDp = 44.dp

    Box(
        modifier = modifier.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        // Main circular avatar
        AsyncImage(
            model = imageUri,
            contentDescription = "Profile photo",
            modifier = Modifier
                .size(sizeDp)
                .clip(CircleShape)
                .clickable(onClick = onPickImage),
            contentScale = ContentScale.Crop
        )
        // Placeholder fallback is handled by the default avatar drawn as background
        if (imageUri == null) {
            Box(
                modifier = Modifier
                    .size(sizeDp)
                    .clip(CircleShape)
                    .background(theme.avatarPlaceholder)
                    .clickable(onClick = onPickImage),
                contentAlignment = Alignment.Center
            ) {
                // Use the default avatar icon
                AsyncImage(
                    model = R.drawable.ic_default_avatar,
                    contentDescription = "Default avatar",
                    modifier = Modifier.size(sizeDp * 0.85f),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Camera badge — bottom-end overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size(badgeSizeDp)
                .clip(CircleShape)
                .background(theme.backgroundElevated)
                .border(2.dp, theme.borderPrimary, CircleShape)
                .clickable(onClick = onPickImage),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = "Change photo",
                tint = theme.iconPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
