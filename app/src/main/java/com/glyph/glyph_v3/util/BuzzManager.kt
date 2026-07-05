package com.glyph.glyph_v3.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.glyph.glyph_v3.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Manages the "Buzz" feature (Yahoo Messenger style).
 * Handles sending, receiving, rate limiting, and audio/vibration effects.
 */
object BuzzManager {

    private const val TAG = "BuzzManager"
    private const val COOLDOWN_MS = 5000L // 5 seconds cooldown
    private const val PREFS_NAME = "buzz_prefs"
    private const val KEY_MUTED_CHATS = "muted_chats"
    
    // Rate Limiting: Map of ChatID to last buzz timestamp (Sender side)
    private val lastBuzzMap = mutableMapOf<String, Long>()
    
    // Mute settings: Set of ChatIDs that are muted
    private var mutedChats: MutableSet<String>? = null

    // SoundPool for low-latency playback
    private var soundPool: SoundPool? = null
    private var buzzSoundId: Int = 0
    private var isSoundLoaded = false
    private var lastReceivedTime = 0L

    // MEMORY LEAK FIX: Single shared scope for buzz operations instead of
    // creating new CoroutineScope(Dispatchers.X) on every call.
    private val buzzScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    interface BuzzUiListener {
        fun onBuzzReceived(buzzChatId: String, senderName: String)
    }

    private var uiListener: BuzzUiListener? = null

    fun setUiListener(listener: BuzzUiListener?) {
        this.uiListener = listener
    }

    fun primeTransport() {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return
        buzzScope.launch {
            runCatching { currentUser.getIdToken(false).await() }
                .onFailure { error -> Log.w(TAG, "Buzz auth prime failed", error) }
        }
    }
    
    // ============================================================================================
    // Preference Logic
    // ============================================================================================
    
    private fun ensurePrefsLoaded(context: Context) {
        if (mutedChats == null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_MUTED_CHATS, emptySet()) ?: emptySet()
            // Create a copy to ensure mutability
            mutedChats = HashSet(set)
        }
    }
    
    fun isBuzzMuted(context: Context, chatId: String): Boolean {
        ensurePrefsLoaded(context)
        return mutedChats?.contains(chatId) == true
    }
    
    fun setBuzzMuted(context: Context, chatId: String, muted: Boolean) {
        ensurePrefsLoaded(context)
        
        val changed = if (muted) {
            mutedChats?.add(chatId) == true
        } else {
            mutedChats?.remove(chatId) == true
        }
        
        if (changed) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_MUTED_CHATS, mutedChats).apply()
        }
    }

    // ============================================================================================
    // Sender Logic
    // ============================================================================================

    fun canBuzz(chatId: String): Boolean {
        val lastTime = lastBuzzMap[chatId] ?: 0L
        val now = System.currentTimeMillis()
        return now - lastTime >= COOLDOWN_MS
    }

    fun sendBuzz(chatId: String, recipientId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!canBuzz(chatId)) {
            onError("Buzz cooling down. Wait a moment.")
            return
        }

        if (com.glyph.glyph_v3.data.repo.BlockRepository.getBlockStatus(recipientId).isBlocked) {
            onError("Buzz is unavailable for this contact.")
            return
        }

        // Validate parameters
        if (chatId.isBlank() || recipientId.isBlank()) {
            Log.e(TAG, "Invalid parameters: chatId=$chatId, recipientId=$recipientId")
            onError("Invalid chat or recipient")
            return
        }

        // Check if user is authenticated
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.e(TAG, "Cannot send buzz: User not authenticated")
            onError("Please sign in to send buzz")
            return
        }

        lastBuzzMap[chatId] = System.currentTimeMillis()

        buzzScope.launch {
            try {
                // Warm auth without forcing a token refresh on every buzz tap.
                currentUser.getIdToken(false).await()
                
                // Call Firebase Cloud Function
                val functions = FirebaseFunctions.getInstance()
                val data = hashMapOf(
                    "chatId" to chatId,
                    "recipientId" to recipientId
                )
                
                val result = functions.getHttpsCallable("sendBuzz")
                    .call(data)
                    .await()
                
                // Check the result status
                val resultData = result.data as? Map<*, *>
                val status = resultData?.get("status") as? String
                
                
                when (status) {
                    "sent" -> {
                        launch(Dispatchers.Main) { onSuccess() }
                    }
                    "user_not_found" -> {
                        Log.w(TAG, "Recipient user not found")
                        launch(Dispatchers.Main) { onError("Recipient not found") }
                    }
                    "no_token" -> {
                        Log.w(TAG, "Recipient has no FCM token")
                        launch(Dispatchers.Main) { onError("Recipient is offline or has no notification token") }
                    }
                    else -> {
                        Log.w(TAG, "Unknown status from buzz function: $status")
                        launch(Dispatchers.Main) { onSuccess() } // Treat as success if no error
                    }
                }
            } catch (e: FirebaseFunctionsException) {
                // Handle Firebase Functions specific errors
                Log.e(TAG, "Firebase Functions error: code=${e.code}, message=${e.message}, details=${e.details}", e)
                
                val errorMessage = when (e.code) {
                    FirebaseFunctionsException.Code.UNAUTHENTICATED -> 
                        "Authentication failed. Please sign in again."
                    FirebaseFunctionsException.Code.PERMISSION_DENIED -> 
                        "Permission denied. Contact support."
                    FirebaseFunctionsException.Code.INVALID_ARGUMENT -> 
                        "Invalid request. Please try again."
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED, 
                    FirebaseFunctionsException.Code.UNAVAILABLE -> 
                        "Server timeout. Check your connection."
                    FirebaseFunctionsException.Code.INTERNAL -> {
                        // Log the details for debugging
                        Log.e(TAG, "Internal server error details: ${e.details}")
                        "Server error. The buzz feature may be temporarily unavailable."
                    }
                    else -> "Failed to send buzz: ${e.message}"
                }
                
                launch(Dispatchers.Main) { onError(errorMessage) }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error sending buzz: ${e.javaClass.simpleName}", e)
                
                // Provide more helpful error messages
                val errorMessage = when {
                    e.message?.contains("timeout", ignoreCase = true) == true -> {
                        "Connection timeout. Check your internet."
                    }
                    e.message?.contains("network", ignoreCase = true) == true -> {
                        "Network error. Please try again."
                    }
                    else -> "Failed to send buzz. Please try again."
                }
                
                launch(Dispatchers.Main) { onError(errorMessage) }
            }
        }
    }

    // ============================================================================================
    // Receiver Logic
    // ============================================================================================

    /**
     * Handles an incoming Buzz event.
     * Should be called from the UI (if valid) or Background Service.
     * Returns true if UI handled it, false otherwise (so service can show notification).
     */
    @Synchronized
    fun onBuzzReceived(context: Context, buzzChatId: String, senderName: String): Boolean {
        // Block guard: if the sender of this chat is blocked, suppress entirely.
        val chatOtherUserId = try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                com.glyph.glyph_v3.data.local.AppDatabase.getDatabase(context).chatDao().getChatById(buzzChatId)?.otherUserId
            }
        } catch (_: Exception) { null }
        if (!chatOtherUserId.isNullOrEmpty() && com.glyph.glyph_v3.data.repo.BlockRepository.getBlockStatus(chatOtherUserId).isBlocked) {
            return true
        }

        // Mute Check
        if (isBuzzMuted(context, buzzChatId)) {
            return true // Suppress logic, return true so notification is also suppressed
        }
        
        val now = System.currentTimeMillis()
        if (now - lastReceivedTime < 300) return true // Ignore extreme spam (bouncing)
        lastReceivedTime = now


        // 1. Play Sound
        playSound(context)

        // 2. Vibrate
        vibrate(context)

        // 3. Trigger UI effects if listener attached
        val listener = uiListener
        if (listener != null) {
            mainScope.launch {
                listener.onBuzzReceived(buzzChatId, senderName)
            }
            return true
        }
        return false
    }

    @Synchronized
    private fun playSound(context: Context) {
        if (soundPool == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(5)
                .setAudioAttributes(audioAttributes)
                .build()

            soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                if (status == 0) {
                    isSoundLoaded = true
                    buzzSoundId = sampleId
                    soundPool?.play(buzzSoundId, 1f, 1f, 1, 0, 1f)
                }
            }
            
            try {
                buzzSoundId = soundPool?.load(context, R.raw.chat_buzz, 1) ?: 0
            } catch (e: Exception) {
                Log.e(TAG, "Error loading buzz sound", e)
            }
        } else {
            if (isSoundLoaded && buzzSoundId != 0) {
                val result = soundPool?.play(buzzSoundId, 1f, 1f, 1, 0, 1f) ?: 0
            } else {
                Log.w(TAG, "SoundPool not ready yet: loaded=$isSoundLoaded, id=$buzzSoundId")
            }
        }
    }

    private fun vibrate(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Short, sharp vibration
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(500)
            }
        }
    }

    fun cleanup() {
        soundPool?.release()
        soundPool = null
        uiListener = null
        isSoundLoaded = false
        buzzSoundId = 0
        // Cancel coroutine scopes to clean up any pending operations
        buzzScope.cancel()
        mainScope.cancel()
    }
}
