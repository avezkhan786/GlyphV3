package com.glyph.glyph_v3

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import java.io.File

class LeanLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("LeanLauncher", "Lean launcher first frame")
        val snap = File(cacheDir, "chat_list_snapshot.json")
        if (snap.exists()) Log.d("LeanLauncher", "Snapshot present, size=" + snap.length())

        // Show chat list instantly using same binding; chat list quality preserved
        setContentView(R.layout.lean_launcher)

        // Instant hand-off: start MainActivity and sync service immediately
        // Chat list renders from snapshot in MainActivity; no intermediate delay
        startService(Intent(this, BackgroundSyncService::class.java))
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }
}
