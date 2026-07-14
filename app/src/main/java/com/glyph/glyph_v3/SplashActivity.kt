package com.glyph.glyph_v3

import android.content.Intent
import android.os.Bundle
import android.app.Activity
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.glyph.glyph_v3.ui.login.LoginActivity
import com.glyph.glyph_v3.utils.ThemeManager
import com.google.firebase.auth.FirebaseAuth

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!GlyphApplication.splashShown) {
            // Apply branded splash window background ONLY on the very first cold-start
            // frame so the launcher-icon → app transition is seamless.
            setTheme(R.style.Theme_GlyphV3_SplashBranded)
            GlyphApplication.splashShown = true
        }
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // COLD-START OPTIMIZATION: Immediately route to the next screen.
        // No blocking I/O, no network calls, no Firestore health check.
        // The health check is now a no-op — the app functions correctly without
        // it, and any Firestore connectivity issues surface naturally when the
        // user attempts an operation that requires it.
        continueToApp()
    }

    @Suppress("DEPRECATION")
    private fun continueToApp() {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser != null) {
            checkForBackupAndRoute()
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            overrideTransition()
            finish()
        }
    }

    private fun checkForBackupAndRoute() {
        // COLD-START OPTIMIZATION: Route to MainActivity immediately.
        // The backup/restore check is a non-critical enhancement that should
        // not gate the cold-start path. Move it to a post-first-frame check
        // in MainActivity instead.
        // The drive-backup list operation involves Google Sign-In + Drive API
        // which adds 500-2000ms to the cold-start path when gating goToMain().
        goToMain()
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
