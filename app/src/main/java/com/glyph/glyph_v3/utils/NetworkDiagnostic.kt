package com.glyph.glyph_v3.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.Socket
import java.net.URL

/**
 * Network diagnostic utility to help troubleshoot connectivity issues.
 * Specifically for debugging Firebase/Google services connectivity problems.
 */
object NetworkDiagnostic {
    
    private const val TAG = "NetworkDiagnostic"
    
    /**
     * Run comprehensive network diagnostics and log results.
     * Call this when translation fails or before testing.
     */
    suspend fun runDiagnostics(context: Context) = withContext(Dispatchers.IO) {
        
        // 1. Check basic connectivity
        checkBasicConnectivity(context)
        
        // 2. Check DNS resolution
        checkDnsResolution()
        
        // 3. Check Firebase/Google service connectivity
        checkGoogleServices()
        
        // 4. Check network type and features
        checkNetworkFeatures(context)
        
    }
    
    private fun checkBasicConnectivity(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val caps = cm.getNetworkCapabilities(network)
            
            
            if (caps != null) {
            } else {
                Log.w(TAG, "No network capabilities available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Basic connectivity check failed", e)
        }
    }
    
    private suspend fun checkDnsResolution() = withContext(Dispatchers.IO) {
        val hosts = listOf(
            "firestore.googleapis.com",
            "firebase.googleapis.com", 
            "functions.googleapi.com",
            "google.com",
            "8.8.8.8"  // Google DNS
        )
        
        for (host in hosts) {
            try {
                val addr = InetAddress.getByName(host)
            } catch (e: Exception) {
                Log.w(TAG, "✗ $host -> FAILED: ${e.message}")
            }
        }
    }
    
    private suspend fun checkGoogleServices() = withContext(Dispatchers.IO) {
        val services = mapOf(
            "Firestore" to "firestore.googleapis.com:443",
            "Firebase Functions" to "firebase.googleapis.com:443", 
            "Cloud TTS" to "texttospeech.googleapis.com:443",
            "Vertex AI" to "aiplatform.googleapis.com:443"
        )
        
        for ((service, endpoint) in services) {
            try {
                val (host, port) = endpoint.split(":")
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port.toInt()), 5000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "✗ $service ($endpoint) - FAILED: ${e.message}")
            }
        }
    }
    
    private fun checkNetworkFeatures(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            // Check network status
            val activeNetwork = cm.activeNetwork
            val networkCapabilities = cm.getNetworkCapabilities(activeNetwork)
            
            // Check for metered connection
            
            // Check for data saver mode (API 24+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Network features check failed", e)
        }
    }
    
    /**
     * Quick connectivity test specifically for Firebase. 
     * Returns true if likely to work, false if network issues detected.
     */
    suspend fun testFirebaseConnectivity(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Basic connectivity
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return@withContext false
            val caps = cm.getNetworkCapabilities(network) ?: return@withContext false
            
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Log.w(TAG, "No validated internet connection")
                return@withContext false
            }
            
            // 2. DNS resolution for Firebase
            InetAddress.getByName("firestore.googleapis.com")
            
            // 3. Port connectivity
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("firestore.googleapis.com", 443), 3000)
            }
            
            true
        } catch (e: Exception) {
            Log.w(TAG, "Firebase connectivity test: FAIL - ${e.message}")
            false
        }
    }
}