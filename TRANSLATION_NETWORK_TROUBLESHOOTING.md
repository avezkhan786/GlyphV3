# Translation Network Issues - Troubleshooting Guide

## Problem
Translation feature shows "DNS resolution failed" or network errors. Logs show:
```
Unable to resolve host firestore.googleapis.com
android_getaddrinfo failed: EAI_NODATA (No address associated with hostname)
```

## Root Cause
The device cannot connect to Google Firebase services due to:
1. **No internet connection**
2. **DNS blocking/filtering** (corporate networks, parental controls)
3. **Firewall blocking Google services**
4. **VPN issues**
5. **Restricted network (captive portal)**

## Immediate Diagnostics

### 1. Quick Network Test
Add this method to `ChatActivity.kt` to test network connectivity:

```kotlin
// Add to ChatActivity.kt imports:
import com.glyph.glyph_v3.utils.DebugUtils

// Add this method to ChatActivity:
private fun testNetworkConnectivity() {
    DebugUtils.runNetworkDiagnostics(this, lifecycleScope)
}

// Call it from somewhere (e.g., onResume temporarily):
// testNetworkConnectivity()
```

### 2. Check Logs
When translation fails, check logs (Logcat) for:
- `NetworkDiagnostic` tag - shows detailed connectivity tests
- `TranslationMgr` tag - shows translation errors with context
- `TranslationRepo` tag - shows network errors and DNS failures

### 3. Manual Network Tests
Test these URLs in a browser on the same device/network:
- https://firestore.googleapis.com
- https://firebase.googleapis.com  
- https://google.com

If any fail to load, it's a network/DNS issue, not an app issue.

## Solutions

### For Users:
1. **Switch Networks**: Try mobile data instead of WiFi (or vice versa)
2. **Disable VPN**: Turn off any VPN temporarily to test
3. **Check DNS**: Try switching DNS to 8.8.8.8 or 1.1.1.1
4. **Corporate Networks**: Ask IT to whitelist Firebase/Google domains
5. **Restart Network**: Toggle airplane mode or restart device

### For Developers:
1. **Add Fallbacks**: The app already caches translations locally
2. **Better Error Messages**: Implemented with specific DNS/network error types
3. **Offline Mode**: App should still work with cached translations

## Firebase Domains to Whitelist
If on corporate/restricted network, ask IT to allow:
- firestore.googleapis.com
- firebase.googleapis.com
- functions.googleapi.com
- texttospeech.googleapis.com
- aiplatform.googleapis.com

## Code Changes Made
✅ Enhanced error handling with specific DNS resolution error detection
✅ Added network connectivity checks before API calls
✅ Added diagnostic logging to identify network issues
✅ Better user-facing error messages distinguishing network vs server errors
✅ Network diagnostic utility for troubleshooting

## Test Plan
1. Install updated APK
2. Try translation on WiFi and mobile data
3. Check logs for diagnostic information if issues persist
4. Use the debug network test function to verify connectivity