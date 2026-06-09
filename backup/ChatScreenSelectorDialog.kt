package com.glyph.glyph_v3.ui.chat

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.glyph.glyph_v3.ui.theme.glyphTheme

/**
 * Dialog to select between XML ChatActivity, original Compose ChatScreen, and optimized ChatScreenV2
 * 
 * This allows A/B/C comparison of performance between the three implementations
 */
@Composable
fun ChatScreenSelectorDialog(
    onDismiss: () -> Unit,
    onSelectXml: () -> Unit = {},
    onSelectOriginal: () -> Unit,
    onSelectOptimized: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        // Animation state
        var animateIn by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { animateIn = true }
        
        val alpha by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0f,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            label = "SelectorDialogAlpha"
        )
        
        val scale by animateFloatAsState(
            targetValue = if (animateIn) 1f else 0.92f,
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            label = "SelectorDialogScale"
        )
        
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .graphicsLayer {
                    this.alpha = alpha
                    this.scaleX = scale
                    this.scaleY = scale
                },
            shape = RoundedCornerShape(24.dp),
            color = glyphTheme.backgroundElevated,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Choose Chat Implementation",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = glyphTheme.textPrimary,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Subtitle
                Text(
                    text = "Compare performance between XML, Compose, and Optimized Compose versions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = glyphTheme.textSecondary,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // XML Screen Option
                ScreenOptionCard(
                    title = "XML Chat",
                    description = "Traditional XML/RecyclerView implementation - Best initial scroll performance",
                    badge = "XML",
                    badgeColor = androidx.compose.ui.graphics.Color(0xFF9C27B0),
                    onClick = {
                        onSelectXml()
                        onDismiss()
                    }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Original Screen Option
                ScreenOptionCard(
                    title = "Original Chat",
                    description = "Current production implementation with all features",
                    badge = "CURRENT",
                    badgeColor = glyphTheme.bubbleIncomingBackground,
                    onClick = {
                        onSelectOriginal()
                        onDismiss()
                    }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Optimized Screen Option
                ScreenOptionCard(
                    title = "Optimized Chat V2",
                    description = "Brand new implementation with extreme performance optimizations",
                    badge = "NEW",
                    badgeColor = glyphTheme.actionPrimary,
                    onClick = {
                        onSelectOptimized()
                        onDismiss()
                    }
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Info text
                Text(
                    text = "All versions have identical features. XML is most stable, Compose V1 uses standard patterns, V2 is highly optimized for performance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = glyphTheme.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Dismiss button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(
                        text = "Cancel",
                        color = glyphTheme.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenOptionCard(
    title: String,
    description: String,
    badge: String,
    badgeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = glyphTheme.surfaceInput,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = glyphTheme.textPrimary
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = glyphTheme.textSecondary
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Arrow indicator
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = com.glyph.glyph_v3.R.drawable.ic_forward),
                contentDescription = null,
                tint = glyphTheme.textTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
