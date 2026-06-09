package com.glyph.glyph_v3.ui.chat.input

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.ui.chat.state.RecorderState
import com.glyph.glyph_v3.ui.chat.state.ReplyEditState
import com.glyph.glyph_v3.ui.chat.state.TextInputState
import com.glyph.glyph_v3.ui.chat.state.UIVisibilityState
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.util.AudioRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ChatInputShell - Slot-based composition for chat input
 * 
 * Architecture:
 * ```
 * ChatInputShell (stable, never recomposes from internal state)
 *  ├── PreviewSlot (edit / reply previews - low frequency)
 *  ├── InputSlot (text field only - high frequency, isolated)
 *  ├── ActionSlot (send / mic button - medium frequency)
 *  └── OverlaySlot (voice recorder, emoji picker - conditionally rendered)
 * ```
 * 
 * CRITICAL Rules:
 * 1. Shell NEVER reads high-frequency state directly
 * 2. Each slot is independently recomposable
 * 3. Typing NEVER causes PreviewSlot or ActionSlot to recompose
 * 4. Voice recording UI is a floating overlay, not inline
 */
@Composable
fun ChatInputShell(
    textInputState: TextInputState,
    replyEditState: ReplyEditState,
    recorderState: RecorderState,
    uiVisibilityState: UIVisibilityState,
    otherUsername: String,
    onSendMessage: (String) -> Unit,
    onSendVoice: (java.io.File, Long) -> Unit,
    onTyping: (String) -> Unit,
    onAttachClick: () -> Unit,
    onCameraClick: () -> Unit,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    
    // Audio recorder - lazy initialization
    val audioRecorder = remember { AudioRecorder(context) }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle permission result if needed
    }
    
    // Stabilized callbacks - prevents lambda allocation on each composition
    val stableOnSend = remember(onSendMessage) { 
        { text: String -> 
            if (text.isNotBlank()) {
                onSendMessage(text)
            }
        } 
    }
    
    val stableOnTyping = remember(onTyping) { onTyping }
    
    // Recording timer effect - isolated to recorderState changes only
    LaunchedEffect(recorderState.isRecording.value) {
        if (recorderState.isRecording.value) {
            val startTime = System.currentTimeMillis()
            while (recorderState.isRecording.value) {
                val elapsed = System.currentTimeMillis() - startTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 1000) / 60
                recorderState.updateDuration(String.format("%d:%02d", minutes, seconds))
                recorderState.updateAmplitude(audioRecorder.getAmplitude())
                delay(100)
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            // Main Input Container (Pill)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(26.dp),
                color = glyphTheme.surfaceInput,
                border = androidx.compose.foundation.BorderStroke(1.dp, glyphTheme.borderInput),
                shadowElevation = 0.dp
            ) {
                Box(
                    modifier = Modifier.then(
                        if (glyphTheme.gradientInput != null) 
                            Modifier.background(glyphTheme.gradientInput!!) 
                        else Modifier
                    )
                ) {
                    Column {
                        // PreviewSlot - Only recomposes when reply/edit changes
                        PreviewSlot(
                            replyEditState = replyEditState,
                            otherUsername = otherUsername,
                            onCancelReply = onCancelReply,
                            onCancelEdit = onCancelEdit
                        )
                        
                        // InputSlot Container - Contains text field and inline icons
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) {
                            // Main input row - visible when not recording
                            val isRecording by recorderState.isRecording
                            
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .graphicsLayer {
                                        alpha = if (isRecording) 0f else 1f
                                    }
                            ) {
                                // Attach Button
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .pointerInput(Unit) {
                                            detectTapGestures { onAttachClick() }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_add_2),
                                        contentDescription = "Attach",
                                        tint = glyphTheme.attachmentIcon,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                                
                                // InputSlot - High-frequency, isolated
                                InputSlot(
                                    textInputState = textInputState,
                                    onTyping = stableOnTyping,
                                    modifier = Modifier
                                        .weight(1f)
                                        .align(Alignment.CenterVertically)
                                )
                                
                                // Right Icons Slot
                                RightIconsSlot(
                                    textInputState = textInputState,
                                    onCameraClick = onCameraClick,
                                    onEmojiClick = { /* TODO: Emoji picker */ }
                                )
                            }
                            
                            // Voice Recording Inline UI (when recording)
                            InlineRecordingSlot(
                                recorderState = recorderState,
                                onCancel = {
                                    audioRecorder.cancel()
                                    recorderState.cancelRecording()
                                },
                                onSend = {
                                    val duration = audioRecorder.stop()
                                    recorderState.stopRecording()?.let { file ->
                                        onSendVoice(file, duration)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // ActionSlot - Send/Mic Button
            ActionSlot(
                textInputState = textInputState,
                recorderState = recorderState,
                onSend = {
                    val text = textInputState.text.value
                    if (text.isNotBlank()) {
                        stableOnSend(text)
                        textInputState.clear()
                        replyEditState.clearAll()
                    }
                },
                onMicDown = {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    
                    if (hasPermission) {
                        val file = java.io.File(
                            context.cacheDir, 
                            "voice_${System.currentTimeMillis()}.m4a"
                        )
                        audioRecorder.start(file)
                        recorderState.startRecording(file)
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onMicUp = {
                    if (recorderState.isRecording.value && !recorderState.isLocked.value) {
                        val duration = audioRecorder.stop()
                        recorderState.stopRecording()?.let { file ->
                            onSendVoice(file, duration)
                        }
                    }
                },
                onMicLocked = {
                    recorderState.lockRecording()
                }
            )
        }
    }
}

/**
 * PreviewSlot - Handles reply and edit previews
 * Only recomposes when replyEditState changes
 */
@Composable
private fun PreviewSlot(
    replyEditState: ReplyEditState,
    otherUsername: String,
    onCancelReply: () -> Unit,
    onCancelEdit: () -> Unit
) {
    val editingMessage by replyEditState.editingMessage
    val replyToMessage by replyEditState.replyToMessage
    
    // Track closing animation states
    var isClosingEdit by remember { mutableStateOf(false) }
    var isClosingReply by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Reset closing flags when state changes
    LaunchedEffect(editingMessage) {
        if (editingMessage == null) isClosingEdit = false
    }
    LaunchedEffect(replyToMessage) {
        if (replyToMessage == null) isClosingReply = false
    }
    
    // Edit Preview (takes priority)
    AnimatedVisibility(
        visible = editingMessage != null && !isClosingEdit,
        enter = slideInVertically(
            initialOffsetY = { it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(animationSpec = tween(250)),
        exit = slideOutVertically(
            targetOffsetY = { it },
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(300, easing = LinearOutSlowInEasing))
    ) {
        editingMessage?.let { message ->
            EditPreviewContent(
                message = message,
                onCancel = {
                    isClosingEdit = true
                    scope.launch {
                        delay(350)
                        onCancelEdit()
                    }
                }
            )
        }
    }
    
    // Reply Preview (only when not editing)
    AnimatedVisibility(
        visible = replyToMessage != null && !isClosingReply && editingMessage == null,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(animationSpec = tween(300)),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(500, easing = LinearOutSlowInEasing))
    ) {
        replyToMessage?.let { message ->
            ReplyPreviewContent(
                message = message,
                username = otherUsername,
                onCancel = {
                    isClosingReply = true
                    scope.launch {
                        delay(550)
                        onCancelReply()
                    }
                }
            )
        }
    }
}

/**
 * InputSlot - Text input field only
 * CRITICAL: This is the high-frequency component - must be ultra-lightweight
 */
@Composable
private fun InputSlot(
    textInputState: TextInputState,
    onTyping: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val text by textInputState.text
    
    androidx.compose.foundation.text.BasicTextField(
        value = text,
        onValueChange = { newText ->
            textInputState.updateText(newText)
            onTyping(newText)
        },
        modifier = modifier
            .heightIn(min = 24.dp, max = 100.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            fontSize = 18.sp,
            color = glyphTheme.textPrimary
        ),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
        ),
        cursorBrush = SolidColor(glyphTheme.cursorColor),
        decorationBox = { innerTextField ->
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                if (text.isEmpty()) {
                    Text(
                        "Message",
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 18.sp,
                            color = glyphTheme.textPlaceholder
                        )
                    )
                }
                innerTextField()
            }
        }
    )
}

/**
 * RightIconsSlot - Camera and emoji icons with animated visibility
 * Uses derivedStateOf to only recompose when hasText changes
 */
@Composable
private fun RightIconsSlot(
    textInputState: TextInputState,
    onCameraClick: () -> Unit,
    onEmojiClick: () -> Unit
) {
    // Use derivedStateOf to minimize recomposition - only recomposes when hasText changes
    val isTyping by textInputState.hasText
    
    Box(
        modifier = Modifier.wrapContentWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        val transition = updateTransition(targetState = isTyping, label = "InputIcons")
        
        val cameraScale by transition.animateFloat(
            label = "CameraScale",
            transitionSpec = { tween(durationMillis = 200, easing = FastOutSlowInEasing) }
        ) { typing -> if (typing) 0f else 1f }
        
        val cameraAlpha by transition.animateFloat(
            label = "CameraAlpha",
            transitionSpec = { tween(durationMillis = 150) }
        ) { typing -> if (typing) 0f else 1f }
        
        val emojiTranslationX by transition.animateDp(
            label = "EmojiTranslation",
            transitionSpec = { tween(durationMillis = 200, easing = FastOutSlowInEasing) }
        ) { typing -> if (typing) 40.dp else 0.dp }
        
        // Camera Button
        if (cameraAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        scaleX = cameraScale
                        scaleY = cameraScale
                        alpha = cameraAlpha
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { onCameraClick() }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_camera),
                    contentDescription = "Camera",
                    tint = glyphTheme.attachmentIcon,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        
        // Emoji Button
        Box(
            modifier = Modifier
                .padding(end = 52.dp)
                .size(52.dp)
                .graphicsLayer { translationX = emojiTranslationX.toPx() }
                .pointerInput(Unit) {
                    detectTapGestures { onEmojiClick() }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_emoji),
                contentDescription = "Emoji",
                tint = glyphTheme.emojiIcon,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * InlineRecordingSlot - Recording UI shown inside the input pill
 * Only visible during active recording
 */
@Composable
private fun InlineRecordingSlot(
    recorderState: RecorderState,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val isRecording by recorderState.isRecording
    
    AnimatedVisibility(
        visible = isRecording,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
    ) {
        RecordingInlineUI(
            recorderState = recorderState,
            onCancel = onCancel,
            onSend = onSend
        )
    }
}

/**
 * ActionSlot - Send/Mic button with gesture handling
 * Uses derivedStateOf for efficient recomposition
 */
@Composable
private fun ActionSlot(
    textInputState: TextInputState,
    recorderState: RecorderState,
    onSend: () -> Unit,
    onMicDown: () -> Unit,
    onMicUp: () -> Unit,
    onMicLocked: () -> Unit
) {
    val view = LocalView.current
    val hasText by textInputState.hasText
    val isRecording by recorderState.isRecording
    
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(
                brush = glyphTheme.gradientSendButton ?: SolidColor(glyphTheme.sendButtonBackground),
                shape = CircleShape
            )
            .pointerInput(hasText) {
                if (hasText) {
                    detectTapGestures(onTap = { 
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSend() 
                    })
                } else {
                    detectTapGestures(
                        onPress = {
                            onMicDown()
                            val startTime = System.currentTimeMillis()
                            val released = tryAwaitRelease()
                            if (released) {
                                if (System.currentTimeMillis() - startTime < 500) {
                                    onMicLocked()
                                } else {
                                    onMicUp()
                                }
                            } else {
                                onMicUp()
                            }
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val sendScale by animateFloatAsState(
            targetValue = if (hasText) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "SendIconScale"
        )
        val micScale by animateFloatAsState(
            targetValue = if (hasText) 0f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "MicIconScale"
        )
        
        // Send Icon
        Icon(
            painter = painterResource(id = R.drawable.ic_send_custom),
            contentDescription = "Send",
            modifier = Modifier
                .padding(start = 2.dp)
                .size(24.dp)
                .graphicsLayer {
                    scaleX = sendScale
                    scaleY = sendScale
                    alpha = sendScale.coerceIn(0f, 1f)
                },
            tint = glyphTheme.sendButtonIcon
        )
        
        // Mic Icon
        Icon(
            painter = painterResource(id = R.drawable.ic_mic_custom),
            contentDescription = "Mic",
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = micScale
                    scaleY = micScale
                    alpha = micScale.coerceIn(0f, 1f)
                },
            tint = glyphTheme.sendButtonIcon
        )
    }
}

/**
 * Recording UI shown inline within the pill
 */
@Composable
private fun RecordingInlineUI(
    recorderState: RecorderState,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    val view = LocalView.current
    val isLocked by recorderState.isLocked
    val duration by recorderState.duration
    val amplitude by recorderState.amplitude
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(glyphTheme.backgroundElevated)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLocked) {
            // Delete button
            IconButton(onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                onCancel()
            }) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_delete),
                    contentDescription = "Delete",
                    tint = androidx.compose.ui.graphics.Color.Red
                )
            }
        } else {
            Spacer(modifier = Modifier.width(16.dp))
            // Blinking dot
            val infiniteTransition = rememberInfiniteTransition(label = "RecDot")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "RecDotAlpha"
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        androidx.compose.ui.graphics.Color.Red.copy(alpha = alpha),
                        CircleShape
                    )
            )
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Duration
        Text(
            text = duration,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = glyphTheme.textPrimary
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // Waveform
        Box(modifier = Modifier.weight(1f)) {
            WaveformAnimation(amplitude = amplitude)
        }
        
        if (isLocked) {
            // Send button
            IconButton(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onSend()
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(glyphTheme.sendButtonBackground, CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_send_custom),
                    contentDescription = "Send",
                    tint = glyphTheme.sendButtonIcon
                )
            }
        } else {
            Text(
                text = "Slide left to cancel",
                style = MaterialTheme.typography.bodySmall,
                color = glyphTheme.textSecondary,
                modifier = Modifier.padding(end = 16.dp)
            )
        }
    }
}

/**
 * Waveform animation during recording
 */
@Composable
private fun WaveformAnimation(amplitude: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "Waveform")
        repeat(20) { index ->
            val heightScale by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 300 + (index * 50),
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 100)
                ),
                label = "Bar$index"
            )
            
            val effectiveHeight = if (amplitude > 0) {
                val normAmp = (amplitude / 32767f).coerceIn(0.1f, 1f)
                heightScale * normAmp
            } else {
                heightScale * 0.5f
            }
            
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp * effectiveHeight)
                    .background(glyphTheme.textSecondary, CircleShape)
            )
        }
    }
}

// Helper extension for sp
private val Int.sp: androidx.compose.ui.unit.TextUnit
    get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

private val Float.sp: androidx.compose.ui.unit.TextUnit
    get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
