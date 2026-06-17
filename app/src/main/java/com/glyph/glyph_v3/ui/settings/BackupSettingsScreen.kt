package com.glyph.glyph_v3.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.data.auth.GoogleSignInRepository
import com.glyph.glyph_v3.data.backup.BackupPreferences
import com.glyph.glyph_v3.data.backup.BackupProgressTracker
import com.glyph.glyph_v3.data.backup.BackupWorker
import com.glyph.glyph_v3.data.backup.RestoreWorker
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val googleRepo = remember { GoogleSignInRepository.getInstance(context) }
    val account by googleRepo.signedInAccount.collectAsState()

    // ── Preferences ────────────────────────────────────────────
    val backupEnabled by BackupPreferences.backupEnabledFlow(context).collectAsState(false)
    val backupFrequency by BackupPreferences.backupFrequencyFlow(context).collectAsState(BackupPreferences.BackupFrequency.WEEKLY)
    val networkPolicy by BackupPreferences.networkPolicyFlow(context).collectAsState(BackupPreferences.NetworkPolicy.WIFI_ONLY)
    val includeVideos by BackupPreferences.includeVideosFlow(context).collectAsState(true)
    val lastBackupTime by BackupPreferences.lastBackupTimeFlow(context).collectAsState(0L)
    val lastBackupSize by BackupPreferences.lastBackupSizeFlow(context).collectAsState(0L)

    // ── Real-time progress ──────────────────────────────────────
    val progressState by BackupProgressTracker.progressState(context).collectAsState(
        BackupProgressTracker.BackupProgressState()
    )

    // On screen open: clear any stale "running" state from a crashed/killed previous backup
    LaunchedEffect(Unit) {
        BackupProgressTracker.clearStaleIfNeeded(context)
    }

    // Debug log every progress change
    LaunchedEffect(progressState) {
        android.util.Log.d("BackupSettingsScreen",
            "progressState changed: isRunning=${progressState.isRunning} " +
            "stage=${progressState.stage} pct=${(progressState.progressPct*100).toInt()}% " +
            "result=${progressState.resultState}")
    }

    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showFrequencyDropdown by remember { mutableStateOf(false) }
    var showNetworkDropdown by remember { mutableStateOf(false) }
    var showSuccessAnimation by remember { mutableStateOf(false) }

    // Show success animation briefly on completion
    LaunchedEffect(progressState.resultState) {
        if (progressState.resultState == "success" && progressState.operationType == "backup") {
            showSuccessAnimation = true
            delay(4000)
            showSuccessAnimation = false
            scope.launch { BackupProgressTracker.clear(context) }
        }
    }

    // ── Theme ───────────────────────────────────────────────────
    val bg = glyphTheme.backgroundPrimary
    val cardBg = glyphTheme.backgroundSecondary
    val textPrimary = glyphTheme.textPrimary
    val textSecondary = glyphTheme.textSecondary
    val accent = glyphTheme.actionPrimary
    val divider = glyphTheme.bubbleBorder
    val error = glyphTheme.actionError

    // Google Sign-In launcher
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            scope.launch {
                val signedIn = googleRepo.handleSignInResult(result.data)
                if (signedIn != null) {
                    BackupPreferences.setGoogleAccountEmail(context, signedIn.email)
                    Toast.makeText(context, "Google account linked!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", color = textPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = textPrimary)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // ══════════════════════════════════════════════════════
            // LIVE PROGRESS CARD  (visible while backup/restore runs)
            // ══════════════════════════════════════════════════════
            AnimatedVisibility(
                visible = progressState.isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        // Stage header
                        Text(
                            progressState.stageDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textPrimary
                        )
                        Spacer(Modifier.height(12.dp))

                        // Progress bar
                        LinearProgressIndicator(
                            progress = { progressState.progressPct },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = accent,
                            trackColor = glyphTheme.backgroundTinted,
                        )
                        Spacer(Modifier.height(8.dp))

                        // Percentage + size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${(progressState.progressPct * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = accent
                            )
                            if (progressState.backupSizeBytes > 0L) {
                                Text(
                                    "${progressState.formattedUploaded} / ${progressState.formattedBackupSize}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondary
                                )
                            }
                        }

                        // Metrics row
                        if (progressState.messagesProcessed > 0L || progressState.uploadSpeedBps > 0L) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (progressState.messagesProcessed > 0L) {
                                    MetricChip(
                                        icon = Icons.Filled.Chat,
                                        value = "${progressState.messagesProcessed}/${progressState.totalMessages}",
                                        label = "messages"
                                    )
                                }
                                if (progressState.uploadSpeedBps > 0L) {
                                    MetricChip(
                                        icon = Icons.Filled.Speed,
                                        value = progressState.formattedUploadSpeed,
                                        label = "upload"
                                    )
                                }
                                val eta = progressState.estimatedTimeRemainingSeconds
                                if (eta > 0L) {
                                    MetricChip(
                                        icon = Icons.Filled.Timer,
                                        value = formatDuration(eta),
                                        label = "remaining"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            // SUCCESS / FAILURE BANNER  (shown briefly after completion)
            // ══════════════════════════════════════════════════════
            AnimatedVisibility(
                visible = showSuccessAnimation && progressState.resultState == "success",
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1DB954).copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Backup completed successfully",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1DB954)
                            )
                            Text(
                                "${progressState.formattedBackupSize} • ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondary
                            )
                            Text(
                                "Account: ${progressState.googleAccount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = textSecondary
                            )
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            // FAILURE BANNER
            // ══════════════════════════════════════════════════════
            AnimatedVisibility(
                visible = progressState.resultState == "failure" && !progressState.isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = error.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Error, null, tint = error, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Backup failed", fontWeight = FontWeight.SemiBold, color = error)
                            if (progressState.errorMessage != null) {
                                Text(
                                    progressState.errorMessage!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = textSecondary
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = {
                                scope.launch { BackupProgressTracker.clear(context) }
                            }) { Text("Dismiss", color = accent) }
                        }
                    }
                }
            }

            // ══════════════════════════════════════════════════════
            // GOOGLE ACCOUNT
            // ══════════════════════════════════════════════════════
            SectionHeader("Google Account", accent)
            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = MaterialTheme.shapes.medium
            ) {
                if (account != null) {
                    ListItem(
                        headlineContent = { Text(account!!.email ?: "", color = textPrimary) },
                        supportingContent = { Text("Backup linked to this account", color = textSecondary) },
                        leadingContent = { Icon(Icons.Filled.CheckCircle, null, tint = accent) },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch {
                                    googleRepo.signOut()
                                    BackupPreferences.setGoogleAccountEmail(context, null)
                                    BackupPreferences.setBackupEnabled(context, false)
                                    BackupWorker.cancel(context)
                                }
                            }) { Text("Disconnect", color = accent) }
                        }
                    )
                } else {
                    ListItem(
                        headlineContent = { Text("Not signed in", color = textPrimary) },
                        supportingContent = { Text("Sign in with your Google account", color = textSecondary) },
                        leadingContent = { Icon(Icons.Filled.Warning, null, tint = error) },
                        trailingContent = {
                            Button(
                                onClick = { signInLauncher.launch(googleRepo.getSignInIntent()) },
                                colors = ButtonDefaults.buttonColors(containerColor = accent)
                            ) { Text("Sign In") }
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ══════════════════════════════════════════════════════
            // LAST BACKUP STATUS
            // ══════════════════════════════════════════════════════
            SectionHeader("Last Backup", accent)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = MaterialTheme.shapes.medium
            ) {
                if (lastBackupTime > 0L) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val df = SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.getDefault())
                        Text(df.format(Date(lastBackupTime)), color = textPrimary, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Size: ${BackupProgressTracker.BackupProgressState.formatBytes(lastBackupSize)}",
                            style = MaterialTheme.typography.bodySmall, color = textSecondary
                        )
                        if (backupEnabled) {
                            val nextMs = lastBackupTime + backupFrequency.durationMinutes * 60_000L
                            Text(
                                "Next: ${df.format(Date(nextMs))}",
                                style = MaterialTheme.typography.bodySmall, color = textSecondary
                            )
                        }
                    }
                } else {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No backup yet", color = textSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (progressState.isRunning && progressState.operationType == "backup") {
                    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Text(
                            "Backup in progress…",
                            color = accent,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ══════════════════════════════════════════════════════
            // BACKUP SETTINGS
            // ══════════════════════════════════════════════════════
            SectionHeader("Backup Settings", accent)
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = MaterialTheme.shapes.medium
            ) {
                ListItem(
                    headlineContent = { Text("Backup enabled", color = textPrimary) },
                    trailingContent = {
                        Switch(
                            checked = backupEnabled,
                            enabled = account != null && !progressState.isRunning,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    BackupPreferences.setBackupEnabled(context, enabled)
                                    if (enabled) BackupWorker.schedule(context, backupFrequency, networkPolicy)
                                    else BackupWorker.cancel(context)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = accent)
                        )
                    }
                )
                HorizontalDivider(color = divider)

                ListItem(
                    headlineContent = { Text("Frequency", color = textPrimary) },
                    supportingContent = { Text(backupFrequency.displayName, color = textSecondary) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { showFrequencyDropdown = true }) {
                                Icon(Icons.Filled.ArrowDropDown, "Select", tint = textSecondary)
                            }
                            DropdownMenu(expanded = showFrequencyDropdown, onDismissRequest = { showFrequencyDropdown = false }) {
                                BackupPreferences.BackupFrequency.entries.forEach { freq ->
                                    DropdownMenuItem(text = { Text(freq.displayName) }, onClick = {
                                        scope.launch {
                                            BackupPreferences.setBackupFrequency(context, freq)
                                            if (backupEnabled) BackupWorker.schedule(context, freq, networkPolicy)
                                        }
                                        showFrequencyDropdown = false
                                    })
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(color = divider)

                ListItem(
                    headlineContent = { Text("Network", color = textPrimary) },
                    supportingContent = { Text(networkPolicy.displayName, color = textSecondary) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { showNetworkDropdown = true }) {
                                Icon(Icons.Filled.ArrowDropDown, "Select", tint = textSecondary)
                            }
                            DropdownMenu(expanded = showNetworkDropdown, onDismissRequest = { showNetworkDropdown = false }) {
                                BackupPreferences.NetworkPolicy.entries.forEach { policy ->
                                    DropdownMenuItem(text = { Text(policy.displayName) }, onClick = {
                                        scope.launch {
                                            BackupPreferences.setNetworkPolicy(context, policy)
                                            if (backupEnabled) BackupWorker.schedule(context, backupFrequency, policy)
                                        }
                                        showNetworkDropdown = false
                                    })
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(color = divider)

                ListItem(
                    headlineContent = { Text("Include videos", color = textPrimary) },
                    supportingContent = { Text("Videos may increase backup size", color = textSecondary) },
                    trailingContent = {
                        Switch(
                            checked = includeVideos,
                            onCheckedChange = { scope.launch { BackupPreferences.setIncludeVideos(context, it) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = accent)
                        )
                    }
                )
            }

            Spacer(Modifier.height(24.dp))

            // ══════════════════════════════════════════════════════
            // ACTION BUTTONS
            // ══════════════════════════════════════════════════════
            if (progressState.isRunning && progressState.operationType == "backup") {
                // Show stop button when backup is running
                Button(
                    onClick = {
                        BackupWorker.cancelAll(context)
                        Toast.makeText(context, "Backup cancelled", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = error)
                ) {
                    Icon(Icons.Filled.Stop, null, tint = glyphTheme.textInverse)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop Backup", color = glyphTheme.textInverse, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = {
                        if (account == null) {
                            Toast.makeText(context, "Sign in with Google first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        BackupWorker.enqueueManualBackup(context)
                        Toast.makeText(context, "Backup started", Toast.LENGTH_SHORT).show()
                    },
                    enabled = account != null,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Icon(Icons.Filled.CloudUpload, null, tint = glyphTheme.textInverse)
                    Spacer(Modifier.width(8.dp))
                    Text("Back Up Now", color = glyphTheme.textInverse, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    if (account == null) {
                        Toast.makeText(context, "Sign in with Google first", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    if (progressState.isRunning) return@OutlinedButton
                    showRestoreConfirm = true
                },
                enabled = account != null && !progressState.isRunning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = Brush.horizontalGradient(listOf(accent, accent))
                )
            ) {
                Icon(Icons.Filled.CloudDownload, null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (progressState.isRunning && progressState.operationType == "restore") "Restoring…"
                    else "Restore from Backup",
                    fontWeight = FontWeight.SemiBold
                )
            }

        }
    }

    // ── Restore confirmation dialog ─────────────────────────
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            containerColor = glyphTheme.backgroundElevated,
            titleContentColor = textPrimary,
            textContentColor = textPrimary,
            title = { Text("Restore Backup") },
            text = {
                Text("This will restore your chat history, media, and settings from the latest Google Drive backup. Current data will NOT be deleted — it will be merged. Continue?")
            },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirm = false
                    RestoreWorker.enqueueRestore(context)
                    Toast.makeText(context, "Restore started", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel", color = accent) }
            }
        )
    }
}

// ── Small reusable composables ─────────────────────────────────

@Composable
private fun SectionHeader(title: String, accent: Color) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = accent,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun MetricChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    val textSecondary = glyphTheme.textSecondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Column {
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = textSecondary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = textSecondary.copy(alpha = 0.7f), fontSize = 9.sp)
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds < 60) return "${totalSeconds}s"
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "${mins}m ${secs}s"
}

