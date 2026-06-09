package com.glyph.glyph_v3

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.app.Activity
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.glyph.glyph_v3.ui.login.LoginActivity
import com.glyph.glyph_v3.utils.ThemeManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = applicationContext as GlyphApplication
        if (!GlyphApplication.splashShown) {
            // Cold start: apply the branded window background BEFORE super.onCreate so the
            // window is created with the splash drawable. ThemeManager runs after.
            setTheme(R.style.Theme_GlyphV3_SplashBranded)
            GlyphApplication.splashShown = true
        }
        // Warm restart: manifest theme is already transparent — no setTheme needed.
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        // Fire-and-forget health check (do NOT block UI startup)
        FirebaseFirestore.getInstance().collection("_health_check_").document("doc").get()
            .addOnSuccessListener {
            }
            .addOnFailureListener { e ->
                Log.e("FirestoreHealthCheck", "CRITICAL FAILURE: The app cannot connect to the Firestore Database. Please check your Firebase Console.", e)
            }

        // Proceed immediately to app routing
        continueToApp()
    }

    @Suppress("DEPRECATION")
    private fun continueToApp() {
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        
        
        if (currentUser != null) {
            // Go to Main immediately; profile validation happens asynchronously in MainActivity
            val mainIntent = Intent(this, MainActivity::class.java)
            intent.extras?.let { mainIntent.putExtras(it) }
            startActivity(mainIntent)
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                overridePendingTransition(0, 0)
            }
            finish()
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                overridePendingTransition(0, 0)
            }
            finish()
        }
    }
}
