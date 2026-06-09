package com.glyph.glyph_v3.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glyph.glyph_v3.data.repo.WalkieTalkieAutoAcceptSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WalkieTalkieAutoAcceptActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISABLE_AUTO_ACCEPT = "com.glyph.glyph_v3.ACTION_DISABLE_WT_AUTO_ACCEPT"
        private const val TAG = "WTAutoAcceptAction"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISABLE_AUTO_ACCEPT) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                WalkieTalkieAutoAcceptSettingsRepository().setEnabled(context, false)
                WalkieTalkieManager.getInstance(context.applicationContext).disconnect()
            } catch (error: Exception) {
                Log.w(TAG, "Failed to disable WT auto-accept from notification", error)
            }
        }
    }
}