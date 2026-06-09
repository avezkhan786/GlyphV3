package com.glyph.glyph_v3.data.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.glyph.glyph_v3.data.repo.PresenceManager
import com.google.firebase.auth.FirebaseAuth

/**
 * Monitor network connectivity using modern NetworkCallback API.
 * 
 * Replaces depreciated BroadcastReceiver approach.
 * Ensures:
 * - Presence updates when network returns
 * - Incoming message sync restarts on reconnection
 */
class NetworkConnectivityMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    // Track if we are currently online to avoid redundant "onAvailable" logic
    private var isOnline = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (!isOnline) {
                isOnline = true
                handleReconnection()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            // Check if there are other networks
            if (activeNetworkAvailable()) {
            } else {
                isOnline = false
            }
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
             val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                               networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
             if (hasInternet && !isOnline) {
                 isOnline = true
                 handleReconnection()
             }
        }
    }

    fun startMonitoring() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
            
            // Initial check
            isOnline = activeNetworkAvailable()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }
    
    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Already unregistered or failed
        }
    }

    private fun handleReconnection() {
        if (FirebaseAuth.getInstance().currentUser != null) {
            try {
                // COLD-START FIX: Reduced delay from 1000ms to 200ms.
                // The old 1-second delay was unnecessarily long and directly
                // added to the perceived cold-start delay. 200ms is enough for
                // the network stack to stabilize while keeping response snappy.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    // CRITICAL FIX: Only go online if the app is actually visible to the user.
                    // If this is a background wake-up (e.g. FCM), do NOT set presence to online.
                    if (AppVisibilityTracker.isAppVisible) {
                        PresenceManager.goOnline()
                    } else {
                    }
                    
                    // Force restart of message sync to catch any pending messages
                    val app = context.applicationContext as? com.glyph.glyph_v3.GlyphApplication
                    app?.ensureSharedRepositoryStartup(reason = "network_reconnect", warmStartupChats = false)
                    val repo = app?.repository ?: app?.getOrCreateRealtimeRepository()
                    repo?.restartIncomingMessageSync()
                    repo?.restartGlobalDeliveryReceiptSync()

                    // Clean up any stale walkie-talkie sessions that arrived while offline
                    WalkieTalkieManager.getInstance(context).cleanupStaleSessionsOnReconnect()
                }, 200) 
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update presence on network change", e)
            }
        }
    }
    
    private fun activeNetworkAvailable(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && 
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

