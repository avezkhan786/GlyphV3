package com.glyph.glyph_v3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.app.Activity
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.glyph.glyph_v3.ui.auth.WelcomeActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        GlyphApplication.splashShown = true
        super.onCreate(savedInstanceState)

        // Fire-and-forget health check
        FirebaseFirestore.getInstance().collection("_health_check_").document("doc").get()
            .addOnSuccessListener { }
            .addOnFailureListener { e ->
                Log.e("FirestoreHealthCheck", "CRITICAL FAILURE: The app cannot connect to the Firestore Database.", e)
            }

        continueToApp()
    }

    @Suppress("DEPRECATION")
    private fun continueToApp() {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser

        if (currentUser != null) {
            // Route immediately to MainActivity. Token refresh for Firestore
            // (PERMISSION_DENIED protection) runs non-blocking in
            // GlyphApplication.onCreate() and MainActivity.ensureAuthenticated().
            //
            // The Google Drive backup/restore offer check is handled by
            // MainActivity.checkForBackupRestore() AFTER its first frame renders,
            // so SplashActivity's branded window background (centered app icon)
            // is never visible as an extra intermediate frame between the system
            // splash and the chat list. See docs/splash-activity-logo-hold.md.
            goToMain()
        } else {
            startActivity(Intent(this, WelcomeActivity::class.java))
            overrideTransition()
            finish()
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
