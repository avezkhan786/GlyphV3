package com.glyph.glyph_v3.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Debug utilities for troubleshooting translation and network issues.
 * These can be called from the UI during development/testing.
 */
object DebugUtils {
    
    private const val TAG = "DebugUtils"
    
    /**
     * Run network diagnostics and show results via Toast.
     * Call this from the UI when having translation issues.
     */
    fun runNetworkDiagnostics(context: Context, scope: CoroutineScope) {
        Toast.makeText(context, "Running network diagnostics... (check logs)", Toast.LENGTH_SHORT).show()
        
        scope.launch {
            try {
                // Run full diagnostics
                NetworkDiagnostic.runDiagnostics(context)
                
                // Quick Firebase test
                val firebaseOk = NetworkDiagnostic.testFirebaseConnectivity(context)
                val message = if (firebaseOk) {
                    "✓ Firebase connectivity: OK"
                } else {
                    "✗ Firebase connectivity: FAILED"
                }
                
                // Show result on UI thread
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Diagnostics failed", e)
                Toast.makeText(context, "Diagnostics failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * Test the translation service directly with a simple message.
     * Useful for debugging the entire translation pipeline.
     */
    fun testTranslationService(context: Context, scope: CoroutineScope) {
        Toast.makeText(context, "Testing translation service...", Toast.LENGTH_SHORT).show()
        
        // This would require access to TranslationRepository 
        // For now, just tell user to check the translation button
        Toast.makeText(context, "Try translating a message and check logs", Toast.LENGTH_LONG).show()
    }
}

/**
 * Add this method to ChatActivity for easy debugging.
 * You can call it from a debug button or when long-pressing something.
 * 
 * Example usage in ChatActivity:
 * 
 * // Add this method:
 * private fun debugNetworkIssues() {
 *     DebugUtils.runNetworkDiagnostics(this, lifecycleScope)
 * }
 * 
 * // Call it when debugging:
 * // debugNetworkIssues()
 */