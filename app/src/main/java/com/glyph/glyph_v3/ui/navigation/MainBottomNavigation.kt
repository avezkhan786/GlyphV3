package com.glyph.glyph_v3.ui.navigation

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.theme.glyphTheme

@Immutable
data class BottomNavItem(
    val id: Int,
    @DrawableRes val iconRes: Int,
    val label: String
)

@Composable
fun GlyphBottomNavigationBar(
    items: List<BottomNavItem>,
    selectedId: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(context)
    val isPastelSky = currentTheme == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY

    // Make bottom nav transparent for Pastel-Sky to show gradient, solid for others
    val backgroundColor = if (isPastelSky) Color.Transparent else glyphTheme.surfaceNavigation

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp) // Increased height for better touch targets and visuals
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val selected = item.id == selectedId

                // Use semantic theme tokens for coloring to ensure consistency across screens
                val iconColor = if (selected) {
                    glyphTheme.navIconActive
                } else {
                    glyphTheme.navIconInactive
                }

                val textColor = if (selected) {
                    glyphTheme.navTextActive
                } else {
                    glyphTheme.navTextInactive
                }

                val indicatorColor = if (selected) {
                    glyphTheme.navActiveIndicator
                } else {
                    Color.Transparent
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelected(item.id) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(indicatorColor) // Pill background
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(id = item.iconRes),
                            contentDescription = item.label,
                            tint = iconColor, // Apply dynamic color
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.label,
                            color = textColor, // Apply dynamic color
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
