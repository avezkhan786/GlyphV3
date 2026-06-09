package com.glyph.glyph_v3.ui.walkietalkie

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.glyph.glyph_v3.data.service.WalkieTalkieManager
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.chat.walkietalkie.WalkieTalkieOverlay
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.utils.CallLockScreenHelper
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Standalone full-screen walkie-talkie session activity.
 * Shows the PTT overlay UI independently of ChatActivity. Works on lock screen.
 *
 * Mirrors ActiveCallActivity: opaque, shows when locked, turns screen on.
 * When the session ends (IDLE), finishes and optionally opens ChatActivity.
 */
class WalkieTalkieActiveActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WTActive"
        const val EXTRA_PEER_ID = "wt_peer_id"
        const val EXTRA_PEER_NAME = "wt_peer_name"
        const val EXTRA_SESSION_ID = "wt_session_id"

        fun createIntent(
            context: Context,
            sessionId: String,
            peerId: String,
            peerName: String
        ): Intent = Intent(context, WalkieTalkieActiveActivity::class.java).apply {
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_PEER_NAME, peerName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private lateinit var wtManager: WalkieTalkieManager
    private var peerId: String = ""
    private var peerName: String = ""
    private var sessionId: String = ""
    private var topSafeAreaInsetPx by mutableIntStateOf(0)
    private var bottomSafeAreaInsetPx by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureLockScreenPresentation()
        configureEdgeToEdge()
        setupWindowInsets()

        readExtras(intent)
        wtManager = WalkieTalkieManager.getInstance(applicationContext)

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                val wtState by wtManager.state.collectAsState()
                val displayName = peerName.ifEmpty { "User" }

                WalkieTalkieOverlay(
                    state = wtState,
                    peerName = displayName,
                    topSafeAreaInsetPx = topSafeAreaInsetPx,
                    bottomSafeAreaInsetPx = bottomSafeAreaInsetPx,
                    onPttPress = { wtManager.pressPtt() },
                    onPttRelease = { wtManager.releasePtt() },
                    onDisconnect = {
                        wtManager.disconnect()
                        Toast.makeText(this@WalkieTalkieActiveActivity, "Walkie-Talkie ended", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        observeSessionEnd()
        observeFloorDenied()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
        CallLockScreenHelper.pulseScreenWake(this, "$TAG:NewIntentWake")
    }

    private fun readExtras(intent: Intent?) {
        peerId = intent?.getStringExtra(EXTRA_PEER_ID).orEmpty()
        peerName = intent?.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
    }

    private fun configureLockScreenPresentation() {
        CallLockScreenHelper.prepareActivityWindow(this)
        CallLockScreenHelper.pulseScreenWake(this, "$TAG:ActiveWake", durationMs = 3_000L)
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _: View, insets: WindowInsetsCompat ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topSafeAreaInsetPx = bars.top
            bottomSafeAreaInsetPx = bars.bottom
            insets
        }
        ViewCompat.requestApplyInsets(window.decorView)
    }

    private fun observeSessionEnd() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wtManager.state.collectLatest { state ->
                    when (state) {
                        WalkieTalkieManager.State.IDLE -> {
                            finishAndRemoveTask()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun observeFloorDenied() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                wtManager.floorDenied.collect {
                    Toast.makeText(
                        this@WalkieTalkieActiveActivity,
                        "Other user is speaking",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Open ChatActivity for this peer when user navigates away from the active screen.
     * Called via back press or when device is unlocked and user wants the full chat.
     */
    fun openChatForPeer() {
        if (peerId.isEmpty()) return
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val chatId = if (myUid < peerId) "${myUid}_${peerId}" else "${peerId}_${myUid}"

        val chatIntent = ChatActivity.newIntent(
            context = applicationContext,
            chatId = chatId,
            otherUserId = peerId,
            otherUsername = peerName
        )
        chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(chatIntent)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use OnBackPressedCallback", ReplaceWith("onBackPressedDispatcher"))
    override fun onBackPressed() {
        // Don't disconnect on back — go to chat instead
        openChatForPeer()
        finish()
        overridePendingTransition(0, 0)
    }
}
