package com.glyph.glyph_v3.ui.calls

import android.graphics.Color
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.firebase.auth.FirebaseAuth
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.webrtc.CallManager
import com.glyph.glyph_v3.ui.chat.ChatActivity

/**
 * Unanswered Call screen — shown when an outgoing call is not answered.
 * Matches WhatsApp's "No answer" screen pixel-precisely.
 * Offers: Cancel | Record voice message | Call again.
 */
class UnansweredCallActivity : AppCompatActivity() {

    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { finishWithSlide() }

    companion object {
        const val EXTRA_CONTACT_NAME   = "contact_name"
        const val EXTRA_CONTACT_AVATAR = "contact_avatar"
        const val EXTRA_RECEIVER_ID    = "receiver_id"
        const val EXTRA_CALL_TYPE      = "call_type"
        const val EXTRA_CALL_REASON    = "call_reason"

        fun createIntent(
            context: Context,
            contactName: String,
            contactAvatar: String,
            receiverId: String,
            callType: CallType,
            callReason: String = "No answer"
        ): Intent = Intent(context, UnansweredCallActivity::class.java).apply {
            putExtra(EXTRA_CONTACT_NAME, contactName)
            putExtra(EXTRA_CONTACT_AVATAR, contactAvatar)
            putExtra(EXTRA_RECEIVER_ID, receiverId)
            putExtra(EXTRA_CALL_TYPE, callType.name)
            putExtra(EXTRA_CALL_REASON, callReason)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge so the background fills behind the status/nav bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_unanswered_call)

        val contactName   = intent.getStringExtra(EXTRA_CONTACT_NAME)   ?: ""
        val contactAvatar = intent.getStringExtra(EXTRA_CONTACT_AVATAR) ?: ""
        val receiverId    = intent.getStringExtra(EXTRA_RECEIVER_ID)     ?: ""
        val callType = try {
            CallType.valueOf(intent.getStringExtra(EXTRA_CALL_TYPE) ?: "VOICE")
        } catch (_: Exception) { CallType.VOICE }

        val callReason = intent.getStringExtra(EXTRA_CALL_REASON) ?: "No answer"

        val tvName    = findViewById<TextView>(R.id.tvUnansweredName)
        val tvStatus  = findViewById<TextView>(R.id.tvUnansweredStatus)
        val ivAvatar  = findViewById<ImageView>(R.id.ivUnansweredAvatar)
        val btnCancel    = findViewById<FrameLayout>(R.id.btnUnansweredCancel)
        val btnRecord    = findViewById<FrameLayout>(R.id.btnUnansweredRecord)
        val btnCallAgain = findViewById<FrameLayout>(R.id.btnUnansweredCallAgain)

        tvName.text = contactName
        tvStatus.text = callReason

        if (contactAvatar.isNotEmpty()) {
            Glide.with(this)
                .load(contactAvatar)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .into(ivAvatar)
        }

        // ── Apply WindowInsets for safe-area padding ──────────────────
        val contentRoot   = findViewById<android.view.View>(R.id.unansweredContentRoot)
        val bottomWrapper = findViewById<android.view.View>(R.id.unansweredBottomWrapper)

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Top padding ensures content clears the status bar / notch
            contentRoot.updatePadding(top = bars.top)
            // Bottom padding ensures buttons clear the gesture nav bar
            bottomWrapper.updatePadding(bottom = bars.bottom + dpToPx(24))
            insets
        }

        // Auto-close after 10 seconds
        autoDismissHandler.postDelayed(autoDismissRunnable, 10_000L)

        // ── Button handlers ───────────────────────────────────────────
        btnCancel.setOnClickListener { finishWithSlide() }

        btnRecord.setOnClickListener {
            // Build the chat ID the same way the rest of the app does
            val myUid = FirebaseAuth.getInstance().currentUser?.uid
            if (myUid != null && receiverId.isNotEmpty()) {
                val chatId = if (myUid < receiverId) "${myUid}_${receiverId}" else "${receiverId}_${myUid}"
                startActivity(
                    ChatActivity.newIntent(
                        context       = this,
                        chatId        = chatId,
                        otherUserId   = receiverId,
                        otherUsername = contactName,
                        otherUserAvatar = contactAvatar,
                        startRecording = true
                    )
                )
            }
            finishWithSlide()
        }

        btnCallAgain.setOnClickListener {
            if (receiverId.isNotEmpty()) {
                CallManager.startOutgoingCall(
                    context        = this,
                    receiverId     = receiverId,
                    receiverName   = contactName,
                    receiverAvatar = contactAvatar,
                    callType       = callType
                )
                // CallManager.callData is populated synchronously inside startOutgoingCall
                val callData = CallManager.callData.value
                if (callData != null) {
                    startActivity(
                        ActiveCallActivity.createIntent(
                            context       = this,
                            callId        = callData.callId,
                            callType      = callType,
                            contactName   = contactName,
                            contactAvatar = contactAvatar
                        )
                    )
                }
            }
            finishWithSlide()
        }
    }

    override fun onDestroy() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        super.onDestroy()
    }

    private fun finishWithSlide() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.slide_in_left, R.anim.slide_out_right)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
