package com.glyph.glyph_v3.ui.chat.overlay

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.chat.state.RecorderState
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * VoiceRecorderOverlay - Floating overlay for voice recording
 * 
 * Architecture:
 * - Rendered as a fullscreen overlay ABOVE the chat content
 * - Never inline with chat input (no recomposition of input area)
 * - Self-contained amplitude visualization
 * - Handles slide-to-cancel and lock gestures
 * 
 * CRITICAL: High-frequency amplitude updates (10Hz) are isolated here
 * and never propagate to ChatInputShell or message list
 */
@Composable
fun VoiceRecorderOverlay(
    recorderState: RecorderState,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording by recorderState.isRecording
    val isLocked by recorderState.isLocked
    
    AnimatedVisibility(
        visible = isRecording,
        enter = fadeIn(animationSpec = tween(200)) + 
                slideInVertically(initialOffsetY = { it / 2 }, animationSpec = spring(stiffness = Spring.StiffnessLow)),
        exit = fadeOut(animationSpec = tween(150)) + 
               slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)) // Darker background for better focus
        ) {
            if (isLocked) {
                // Locked recording mode - centered controls
                LockedRecordingUI(
                    recorderState = recorderState,
                    onSend = onSend,
                    onCancel = onCancel
                )
            } else {
                // Hold-to-record mode - slide controls
                HoldRecordingUI(
                    recorderState = recorderState,
                    onCancel = onCancel,
                    onLock = { recorderState.lockRecording() }
                )
            }
        }
    }
}

/**
 * LockedRecordingUI - Full overlay UI when recording is locked
 * Shows waveform, duration, and send/cancel controls
 */
@Composable
private fun LockedRecordingUI(
    recorderState: RecorderState,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val view = LocalView.current
    val duration by recorderState.duration
    val amplitude by recorderState.amplitude
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Recording Label
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.Red, CircleShape)
            )
            Text(
                text = "Recording...",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Large waveform visualization
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            glyphTheme.backgroundElevated.copy(alpha = 0.9f),
                            glyphTheme.backgroundElevated.copy(alpha = 0.7f)
                        )
                    )
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            LargeWaveformVisualization(amplitude = amplitude)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Duration display
        Text(
            text = duration,
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
                fontSize = 48.sp // Larger font
            ),
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(64.dp))
        
        // Control buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cancel button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                        onCancel()
                    },
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = "Cancel",
                            tint = Color.Red.copy(alpha = 0.9f),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Cancel", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
            }
            
            // Send button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        onSend()
                    },
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = glyphTheme.sendButtonBackground,
                    shadowElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_send_custom),
                            contentDescription = "Send",
                            tint = glyphTheme.sendButtonIcon,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Send", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

/**
 * HoldRecordingUI - Slide-to-cancel and lock UI during hold-to-record
 */
@Composable
private fun HoldRecordingUI(
    recorderState: RecorderState,
    onCancel: () -> Unit,
    onLock: () -> Unit
) {
    val duration by recorderState.duration
    val amplitude by recorderState.amplitude
    
    // Use offsets from state (driven by Native touch listener for Press-and-Hold)
    val horizontalOffset by recorderState.offsetX
    val verticalOffset by recorderState.offsetY
    
    val cancelThreshold = 180f
    val lockThreshold = -140f
    
    // Derive visual states from offsets
    val isCancelling = horizontalOffset < -cancelThreshold * 0.7f
    val isLocking = verticalOffset < lockThreshold * 0.7f
    
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Lock indicator (top center)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-150).dp)
                .graphicsLayer {
                    val lockProgress = (-verticalOffset / lockThreshold.unaryMinus()).coerceIn(0f, 1f)
                    scaleX = 0.8f + (lockProgress * 0.3f)
                    scaleY = 0.8f + (lockProgress * 0.3f)
                    alpha = 0.4f + (lockProgress * 0.6f)
                    translationY = (1.0f - lockProgress) * 50f
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = if (isLocking) glyphTheme.actionPrimary else Color.White.copy(alpha = 0.15f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_lock),
                        contentDescription = "Lock",
                        tint = if (isLocking) Color.White else Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_expand_less),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
        
        // Recording indicator (center) - Moved dynamically
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(horizontalOffset.roundToInt(), verticalOffset.roundToInt()) }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated recording dot with ripple
                val pulseAnim = rememberInfiniteTransition(label = "Pulse")
                val pulseScale by pulseAnim.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "PulseScale"
                )
                
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                                alpha = 1f - ((pulseScale - 1f) * 2f)
                            }
                            .background(
                                Color.Red.copy(alpha = 0.5f),
                                CircleShape
                            )
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .shadow(8.dp, CircleShape)
                            .background(Color.Red, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                         Icon(
                            painter = painterResource(id = R.drawable.ic_mic_custom),
                            contentDescription = "Mic",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Duration with styling
                Text(
                    text = duration,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Mini waveform
                MiniWaveform(amplitude = amplitude)
            }
        }
        
        // Cancel hint (left side)
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
                .graphicsLayer {
                    val cancelProgress = (-horizontalOffset / cancelThreshold).coerceIn(0f, 1f)
                    alpha = (0.5f + (cancelProgress * 0.5f))
                    scaleX = 0.9f + (cancelProgress * 0.2f)
                    scaleY = 0.9f + (cancelProgress * 0.2f)
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isCancelling) Color.Red else Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                         Icon(
                            painter = painterResource(id = R.drawable.ic_delete), // Using delete icon for cancel hint
                            contentDescription = null,
                            tint = if (isCancelling) Color.White else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                Icon(
                     painter = painterResource(id = R.drawable.ic_arrow_back), // Arrow showing direction
                     contentDescription = null,
                     tint = Color.White.copy(alpha = 0.6f),
                     modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * LargeWaveformVisualization - Full-screen waveform for locked mode
 */
@Composable
private fun LargeWaveformVisualization(amplitude: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center // Centered
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "LargeWaveform")
        val barCount = 34
        
        // Mirror effect for better visualization
        val normalizedAmp = (amplitude / 32767f).coerceIn(0f, 1f)
        
        repeat(barCount) { index ->
            val distanceFromCenter = kotlin.math.abs(index - (barCount / 2))
            val scaleFactor = 1f - (distanceFromCenter / (barCount / 2f))
            
            val phase by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 300 + (index * 15),
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 50)
                ),
                label = "Bar$index"
            )
            
            // Dynamic height based on amplitude + animation + position
            val activeHeight = 0.1f + (phase * 0.9f * normalizedAmp * scaleFactor.coerceAtLeast(0.3f))
            // Always show at least some height
            val finalHeight = activeHeight.coerceAtLeast(0.05f)
            
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .width(4.dp)
                    .fillMaxHeight(finalHeight)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                glyphTheme.actionPrimary.copy(alpha = 0.6f),
                                glyphTheme.actionPrimary,
                                glyphTheme.actionPrimary.copy(alpha = 0.6f)
                            )
                        ), 
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/**
 * MiniWaveform - Compact waveform for hold-to-record mode
 */
@Composable
private fun MiniWaveform(amplitude: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "MiniWave")
        val normalizedAmp = (amplitude / 32767f).coerceIn(0f, 1f)

        repeat(5) { index ->
            val phase by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 200 + (index * 100),
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 100)
                ),
                label = "MiniBar$index"
            )
            
            val height = 8.dp + (24.dp * phase * normalizedAmp)
            
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(2.dp))
            )
        }
    }
}
