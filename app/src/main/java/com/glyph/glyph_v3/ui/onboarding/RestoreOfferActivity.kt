package com.glyph.glyph_v3.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.MainActivity
import com.glyph.glyph_v3.data.auth.GoogleSignInRepository
import com.glyph.glyph_v3.data.backup.BackupImporter
import com.glyph.glyph_v3.data.backup.BackupPreferences
import com.glyph.glyph_v3.data.backup.DriveRepository
import com.glyph.glyph_v3.data.backup.RestoreWorker
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Shown during first launch after reinstall when a Google Drive backup
 * is detected. Offers the user the option to restore or skip.
 */
class RestoreOfferActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GlyphThemeProvider {
                RestoreOfferScreen(
                    onRestore = { restoreAndContinue() },
                    onSkip = { skipToMain() }
                )
            }
        }
    }

    @Composable
    private fun RestoreOfferScreen(
        onRestore: () -> Unit,
        onSkip: () -> Unit
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var isLoading by remember { mutableStateOf(true) }
        var backupInfo by remember { mutableStateOf<BackupImporter.ValidationResult?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val bg = glyphTheme.backgroundPrimary
        val textPrimary = glyphTheme.textPrimary
        val textSecondary = glyphTheme.textSecondary
        val accent = glyphTheme.actionPrimary

        LaunchedEffect(Unit) {
            try {
                val googleRepo = GoogleSignInRepository.getInstance(context)
                val account = googleRepo.silentSignIn()
                if (account != null) {
                    val credential = googleRepo.getDriveCredential(account)
                    val driveRepo = DriveRepository.getInstance(context)
                    driveRepo.init(account, credential)
                    val backups = driveRepo.listBackups()
                    if (backups.isNotEmpty()) {
                        val latest = backups.first()
                        val tempDir = File(cacheDir, "restore_check_${UUID.randomUUID()}")
                        tempDir.mkdirs()
                        val downloadFile = File(tempDir, "check_download.bin")
                        driveRepo.downloadBackup(latest.fileId, downloadFile)

                        val keyManager = com.glyph.glyph_v3.data.backup.BackupKeyManager.getInstance(context)
                        val decrypted = try {
                            keyManager.decrypt(downloadFile.readBytes())
                        } catch (_: Exception) { null }

                        if (decrypted != null) {
                            val extractDir = File(tempDir, "extracted")
                            extractDir.mkdirs()
                            val decompressed = java.io.ByteArrayInputStream(decrypted).use { input ->
                                java.util.zip.GZIPInputStream(input).use { gzip -> gzip.readBytes() }
                            }
                            extractManifest(decompressed, extractDir)
                            val importer = BackupImporter.getInstance(context)
                            backupInfo = importer.validateBackup(extractDir)
                        }
                        tempDir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message
            }
            isLoading = false
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = bg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(color = accent)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Checking for backup...", color = textSecondary)
                    }
                    backupInfo?.isValid == true -> {
                        Text(
                            "Backup Found!",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "A backup from ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(backupInfo!!.backupTime))} was found in your Google Drive.",
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${backupInfo!!.messageCount} messages • ${backupInfo!!.chatCount} chats",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textSecondary
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = onRestore,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) { Text("Restore") }
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(onClick = onSkip) { Text("Skip", color = accent) }
                    }
                    else -> {
                        Text(
                            "No Backup Found",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            errorMessage ?: "No backup was found in your Google Drive. You can start fresh or try again.",
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = onSkip,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) { Text("Continue") }
                    }
                }
            }
        }
    }

    private fun restoreAndContinue() {
        lifecycleScope.launch(Dispatchers.IO) {
            BackupPreferences.markRestoreOfferSeen(this@RestoreOfferActivity)
        }
        RestoreWorker.enqueueRestore(this)
        Toast.makeText(this, "Restoring your chats...", Toast.LENGTH_LONG).show()
        goToMain()
    }

    private fun skipToMain() {
        lifecycleScope.launch(Dispatchers.IO) {
            BackupPreferences.markRestoreOfferSeen(this@RestoreOfferActivity)
        }
        goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun extractManifest(archiveData: ByteArray, outputDir: File) {
        var offset = 0
        val fileCount = ((archiveData[offset].toInt() and 0xFF) shl 24) or
            ((archiveData[offset + 1].toInt() and 0xFF) shl 16) or
            ((archiveData[offset + 2].toInt() and 0xFF) shl 8) or
            (archiveData[offset + 3].toInt() and 0xFF)
        offset += 4

        for (i in 0 until fileCount.coerceAtMost(10)) {
            val nameLen = ((archiveData[offset].toInt() and 0xFF) shl 24) or
                ((archiveData[offset + 1].toInt() and 0xFF) shl 16) or
                ((archiveData[offset + 2].toInt() and 0xFF) shl 8) or
                (archiveData[offset + 3].toInt() and 0xFF)
            offset += 4
            val name = String(archiveData, offset, nameLen, Charsets.UTF_8)
            offset += nameLen
            var fileSize = 0L
            for (j in 0..7) {
                fileSize = (fileSize shl 8) or (archiveData[offset + j].toLong() and 0xFF)
            }
            offset += 8
            val fileData = archiveData.copyOfRange(offset, offset + fileSize.toInt())
            offset += fileSize.toInt()
            val outFile = File(outputDir, name)
            outFile.parentFile?.mkdirs()
            outFile.writeBytes(fileData)
        }
    }
}
