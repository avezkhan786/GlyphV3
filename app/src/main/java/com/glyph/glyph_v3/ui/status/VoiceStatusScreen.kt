package com.glyph.glyph_v3.ui.status

import android.Manifest
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.util.AudioRecorder
import kotlinx.coroutines.delay
import java.io.File

private enum class VoiceState { IDLE, RECORDING, RECORDED, PLAYING }

@Composable
fun VoiceStatusScreen(
    isUploading: Boolean,
    onPost: (File) -> Unit,
    onClose: () -> Unit,
    onAudienceClick: (() -> Unit)? = null,
    audienceLabel: String = "Status (Contacts)"
) {
    val context = LocalContext.current
    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var hasPermission by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var playbackSeconds by remember { mutableIntStateOf(0) }
    var totalDurationSeconds by remember { mutableIntStateOf(0) }
    val amplitudes = remember { mutableStateListOf<Float>() }

    val audioRecorder = remember { AudioRecorder(context) }
    val outputFile = remember { File(context.cacheDir, "voice_status_${System.currentTimeMillis()}.m4a") }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val postSubmitted = remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Recording timer + amplitude sampling
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceState.RECORDING) {
            recordingSeconds = 0
            amplitudes.clear()
            while (voiceState == VoiceState.RECORDING) {
                delay(100)
                val amp = audioRecorder.getAmplitude()
                val normalized = (amp / 32768f).coerceIn(0.05f, 1f)
                amplitudes.add(normalized)
                if (amplitudes.size > 200) amplitudes.removeAt(0)
                // Increment seconds
                if (amplitudes.size % 10 == 0) {
                    recordingSeconds = amplitudes.size / 10
                }
            }
        }
    }

    // Playback timer
    LaunchedEffect(voiceState) {
        if (voiceState == VoiceState.PLAYING) {
            playbackSeconds = 0
            while (voiceState == VoiceState.PLAYING) {
                delay(1000)
                playbackSeconds++
                if (playbackSeconds >= totalDurationSeconds) {
                    voiceState = VoiceState.RECORDED
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            // Only cancel (and delete the temp file) if the recording was NOT submitted.
            // If the user tapped Send, the file must survive until the upload finishes.
            if (!postSubmitted.value) {
                audioRecorder.cancel()
            }
        }
    }

    fun startRecording() {
        if (audioRecorder.start(outputFile)) {
            voiceState = VoiceState.RECORDING
        }
    }

    fun stopRecording() {
        val durationMs = audioRecorder.stop()
        totalDurationSeconds = (durationMs / 1000).toInt().coerceAtLeast(1)
        voiceState = VoiceState.RECORDED
    }

    fun playRecording() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(outputFile.absolutePath)
            prepare()
            start()
            setOnCompletionListener { voiceState = VoiceState.RECORDED }
        }
        voiceState = VoiceState.PLAYING
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        voiceState = VoiceState.RECORDED
    }

    fun deleteRecording() {
        mediaPlayer?.release()
        mediaPlayer = null
        outputFile.delete()
        amplitudes.clear()
        recordingSeconds = 0
        voiceState = VoiceState.IDLE
    }

    fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    val pulsateAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulsateAnim.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ), label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(glyphTheme.backgroundPrimary)
            .systemBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (voiceState == VoiceState.RECORDING) audioRecorder.cancel()
                mediaPlayer?.release()
                onClose()
            }) {
                Icon(Icons.Default.Close, "Close", tint = glyphTheme.iconPrimary)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Voice status",
                color = glyphTheme.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        // Center content
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Waveform visualization
            if (amplitudes.isNotEmpty()) {
                val waveColor = if (voiceState == VoiceState.RECORDING)
                    glyphTheme.actionPrimary else glyphTheme.textSecondary
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .padding(horizontal = 32.dp)
                ) {
                    val barWidth = 3.dp.toPx()
                    val gap = 2.dp.toPx()
                    val totalWidth = size.width
                    val maxBars = (totalWidth / (barWidth + gap)).toInt()
                    val displayAmps = amplitudes.takeLast(maxBars)
                    val centerY = size.height / 2

                    displayAmps.forEachIndexed { i, amp ->
                        val barHeight = amp * (size.height * 0.8f)
                        val x = i * (barWidth + gap)
                        drawLine(
                            color = waveColor,
                            start = Offset(x + barWidth / 2, centerY - barHeight / 2),
                            end = Offset(x + barWidth / 2, centerY + barHeight / 2),
                            strokeWidth = barWidth,
                            cap = StrokeCap.Round
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Timer
            Text(
                text = when (voiceState) {
                    VoiceState.IDLE -> "0:00"
                    VoiceState.RECORDING -> formatTime(recordingSeconds)
                    VoiceState.RECORDED -> formatTime(totalDurationSeconds)
                    VoiceState.PLAYING -> formatTime(playbackSeconds)
                },
                fontSize = 36.sp,
                fontWeight = FontWeight.Light,
                color = glyphTheme.textPrimary
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = when (voiceState) {
                    VoiceState.IDLE -> "Tap to record"
                    VoiceState.RECORDING -> "Recording..."
                    VoiceState.RECORDED -> "Tap play to preview"
                    VoiceState.PLAYING -> "Playing..."
                },
                fontSize = 14.sp,
                color = glyphTheme.textSecondary
            )

            Spacer(Modifier.height(40.dp))

            // Main action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (voiceState) {
                    VoiceState.IDLE -> {
                        FloatingActionButton(
                            onClick = { if (hasPermission) startRecording() },
                            containerColor = glyphTheme.actionPrimary,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                Icons.Default.Mic, "Record",
                                tint = glyphTheme.textInverse,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    VoiceState.RECORDING -> {
                        FloatingActionButton(
                            onClick = { stopRecording() },
                            containerColor = Color(0xFFE53935),
                            modifier = Modifier
                                .size(72.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                }
                        ) {
                            Icon(
                                Icons.Default.Stop, "Stop",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    VoiceState.RECORDED, VoiceState.PLAYING -> {
                        // Delete
                        FloatingActionButton(
                            onClick = { deleteRecording() },
                            containerColor = glyphTheme.backgroundElevated,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete, "Delete",
                                tint = Color(0xFFE53935), modifier = Modifier.size(24.dp)
                            )
                        }
                        // Play/Pause
                        FloatingActionButton(
                            onClick = {
                                if (voiceState == VoiceState.PLAYING) pausePlayback()
                                else playRecording()
                            },
                            containerColor = glyphTheme.actionPrimary,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Icon(
                                if (voiceState == VoiceState.PLAYING) Icons.Default.Pause
                                else Icons.Default.PlayArrow,
                                "Play/Pause",
                                tint = glyphTheme.textInverse,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom bar: audience + send
        if (voiceState == VoiceState.RECORDED || voiceState == VoiceState.PLAYING) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { onAudienceClick?.invoke() },
                    shape = RoundedCornerShape(20.dp),
                    color = glyphTheme.backgroundElevated,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("◎", fontSize = 14.sp, color = glyphTheme.textSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            audienceLabel,
                            color = glyphTheme.textSecondary,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                FloatingActionButton(
                    onClick = {
                        if (!isUploading && outputFile.exists()) {
                            postSubmitted.value = true
                            onPost(outputFile)
                        }
                    },
                    containerColor = glyphTheme.actionPrimary,
                    modifier = Modifier.size(52.dp)
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = glyphTheme.textInverse,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send, "Send",
                            tint = glyphTheme.textInverse
                        )
                    }
                }
            }
        }
    }
}
