package com.glyph.glyph_v3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.app.Activity
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.glyph.glyph_v3.data.auth.GoogleSignInRepository
import com.glyph.glyph_v3.data.backup.BackupPreferences
import com.glyph.glyph_v3.data.backup.DriveRepository
import com.glyph.glyph_v3.ui.login.LoginActivity
import com.glyph.glyph_v3.ui.onboarding.RestoreOfferActivity
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.util.StartupTrace
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.logStage("splash_onCreate_start")

        val app = applicationContext as GlyphApplication
        if (!GlyphApplication.splashShown) {
            setTheme(R.style.Theme_GlyphV3_SplashBranded)
            GlyphApplication.splashShown = true
        }
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        StartupTrace.logStage("splash_onCreate_end")

        // OPTIMIZATION: Navigate immediately, defer health check to background
        // This removes 50-200ms from splash time on cold starts
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseFirestore.getInstance().collection("_health_check_").document("doc").get()
                    .addOnSuccessListener { }
                    .addOnFailureListener { e ->
                        Log.e("FirestoreHealthCheck", "CRITICAL FAILURE: The app cannot connect to the Firestore Database.", e)
                    }
            } catch (e: Exception) {
                Log.w("SplashActivity", "Health check deferred", e)
            }
        }

        continueToApp()
    }

    @Suppress("DEPRECATION")
    private fun continueToApp() {
        StartupTrace.logStage("splash_continueToApp_start")

        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser != null) {
            StartupTrace.logStage("splash_auth_verified", "uid=${currentUser.uid.take(8)}")
            checkForBackupAndRoute()
        } else {
            StartupTrace.logStage("splash_no_auth routing_to_login")
            startActivity(Intent(this, LoginActivity::class.java))
            overrideTransition()
            finish()
        }
    }

    private fun checkForBackupAndRoute() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!BackupPreferences.shouldShowRestoreOffer(this@SplashActivity)) {
                    goToMain(); return@launch
                }

                // OPTIMIZATION: Add 3-second timeout to backup check
                // If it takes longer, proceed to main and skip restore offer this time
                val hasBackups = withTimeoutOrNull(3000L) {
                    val googleRepo = GoogleSignInRepository.getInstance(this@SplashActivity)
                    val account = googleRepo.silentSignIn()
                    if (account != null) {
                        val credential = googleRepo.getDriveCredential(account)
                        val driveRepo = DriveRepository.getInstance(this@SplashActivity)
                        driveRepo.init(account, credential)
                        val backups = driveRepo.listBackups()
                        backups.isNotEmpty()
                    } else {
                        false
                    }
                } ?: false

                if (hasBackups) {
                    val restoreIntent = Intent(this@SplashActivity, RestoreOfferActivity::class.java)
                    startActivity(restoreIntent)
                    overrideTransition()
                    finish()
                    return@launch
                }
            } catch (_: Exception) {
                // Silently ignore errors - user can still access backup from settings
            }
            goToMain()
        }
    }

    private fun goToMain() {
        val mainIntent = Intent(this@SplashActivity, MainActivity::class.java)
        intent.extras?.let { mainIntent.putExtras(it) }
        startActivity(mainIntent)
        overrideTransition()
        finish()
    }

    private fun overrideTransition() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
