package com.glyph.glyph_v3.ui.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.service.CallNotificationHelper
import com.glyph.glyph_v3.data.webrtc.GroupCallManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Group video/voice call activity with dynamic grid layout.
 * Displays participants in a responsive grid (1×1, 1×2, 2×2, 2×3, 2×4)
 * with real-time video streams, mute/video indicators, and call controls.
 */
class GroupCallActivity : AppCompatActivity() {

    companion object {
        const val TAG = "GroupCallActivity"
        const val EXTRA_GROUP_CALL_ID = "group_call_id"
        const val EXTRA_CALL_TYPE = "call_type"
        const val EXTRA_IS_JOINING = "is_joining"

        // For starting a new group call
        const val EXTRA_PARTICIPANT_IDS = "participant_ids"
        const val EXTRA_PARTICIPANT_NAMES = "participant_names"
        const val EXTRA_PARTICIPANT_AVATARS = "participant_avatars"

        fun createIntent(
            context: Context,
            groupCallId: String,
            callType: CallType,
            isJoining: Boolean = false
        ): Intent = Intent(context, GroupCallActivity::class.java).apply {
            putExtra(EXTRA_GROUP_CALL_ID, groupCallId)
            putExtra(EXTRA_CALL_TYPE, callType.name)
            putExtra(EXTRA_IS_JOINING, isJoining)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        fun createIntentForNewCall(
            context: Context,
            callType: CallType,
            participantIds: ArrayList<String>,
            participantNames: ArrayList<String>,
            participantAvatars: ArrayList<String>
        ): Intent = Intent(context, GroupCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_TYPE, callType.name)
            putExtra(EXTRA_IS_JOINING, false)
            putStringArrayListExtra(EXTRA_PARTICIPANT_IDS, participantIds)
            putStringArrayListExtra(EXTRA_PARTICIPANT_NAMES, participantNames)
            putStringArrayListExtra(EXTRA_PARTICIPANT_AVATARS, participantAvatars)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }

    private var groupCallId: String = ""
    private var callType = CallType.VIDEO
    private var isJoining = false
    private var tileManager: GroupCallVideoTileManager? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startOrJoinCall()
        } else {
            Log.w(TAG, "Permissions denied")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Suppress enter animation when migrating from 1:1 call for seamless feel
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        readIntent(intent)
        setContentView(R.layout.activity_group_call)
        setupUI()
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        tileManager?.releaseAll()
        super.onDestroy()
    }

    private fun readIntent(intent: Intent) {
        groupCallId = intent.getStringExtra(EXTRA_GROUP_CALL_ID) ?: ""
        callType = try {
            CallType.valueOf(intent.getStringExtra(EXTRA_CALL_TYPE) ?: "VIDEO")
        } catch (_: Exception) { CallType.VIDEO }
        isJoining = intent.getBooleanExtra(EXTRA_IS_JOINING, false)
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (callType == CallType.VIDEO) {
            perms.add(Manifest.permission.CAMERA)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startOrJoinCall()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startOrJoinCall() {
        // Cancel the incoming group call notification (if we opened from one)
        CallNotificationHelper.cancelGroupCallNotification(this)

        // If GroupCallManager is already running (migrated from 1:1 call), skip initialization
        if (GroupCallManager.isInGroupCall.value) {
            return
        }

        if (isJoining && groupCallId.isNotEmpty()) {
            GroupCallManager.joinGroupCall(this, groupCallId, callType)
        } else {
            // Starting a new group call
            val ids = intent.getStringArrayListExtra(EXTRA_PARTICIPANT_IDS) ?: arrayListOf()
            val names = intent.getStringArrayListExtra(EXTRA_PARTICIPANT_NAMES) ?: arrayListOf()
            val avatars = intent.getStringArrayListExtra(EXTRA_PARTICIPANT_AVATARS) ?: arrayListOf()

            val nameMap = mutableMapOf<String, String>()
            val avatarMap = mutableMapOf<String, String>()
            for (i in ids.indices) {
                nameMap[ids[i]] = names.getOrElse(i) { "" }
                avatarMap[ids[i]] = avatars.getOrElse(i) { "" }
            }

            GroupCallManager.startGroupCall(this, callType, ids, nameMap, avatarMap)
        }
    }

    private fun setupUI() {
        val videoGrid = findViewById<AdaptiveVideoGridLayout>(R.id.videoGrid)
        val tvTimer = findViewById<TextView>(R.id.tvGroupCallTimer)
        val tvParticipantCount = findViewById<TextView>(R.id.tvGroupCallParticipantCount)
        val btnEndCall = findViewById<FrameLayout>(R.id.btnEndCallGroup)
        val btnMute = findViewById<FrameLayout>(R.id.btnMuteGroup)
        val btnCamera = findViewById<FrameLayout>(R.id.btnCameraGroup)
        val btnSwitchCamera = findViewById<FrameLayout>(R.id.btnSwitchCameraGroup)
        val btnSpeaker = findViewById<FrameLayout>(R.id.btnSpeakerGroup)
        val btnMinimize = findViewById<FrameLayout>(R.id.btnMinimizeGroup)
        val btnAddParticipant = findViewById<FrameLayout>(R.id.btnAddParticipantGroup)
        val ivMute = findViewById<ImageView>(R.id.ivMuteIconGroup)
        val ivCamera = findViewById<ImageView>(R.id.ivCameraIconGroup)
        val ivSpeaker = findViewById<ImageView>(R.id.ivSpeakerIconGroup)

        // WindowInsets — pass to grid so it can inset tiles under the bars
        val topBar = findViewById<View>(R.id.topBarGroupCall)
        val bottomBar = findViewById<View>(R.id.bottomControlBarGroup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootGroupCall)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.updatePadding(top = bars.top)
            bottomBar.updatePadding(bottom = bars.bottom)
            // Let the grid know about vertical insets so tiles
            // don't hide behind controls
            videoGrid.topInset = bars.top + topBar.measuredHeight
            videoGrid.bottomInset = bars.bottom + bottomBar.measuredHeight
            insets
        }

        // After first layout, refine insets with actual measured sizes
        videoGrid.post {
            videoGrid.topInset = topBar.height
            videoGrid.bottomInset = bottomBar.height
        }

        // Create tile manager
        tileManager = GroupCallVideoTileManager(this, videoGrid)

        // Observe participant UI states — adaptive grid, no scrolling
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupCallManager.participantUiStates.collectLatest { states ->
                    tileManager?.updateParticipants(states)
                    tvParticipantCount.text = "${states.size} participant${if (states.size != 1) "s" else ""}"
                }
            }
        }

        // Timer
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupCallManager.callDurationSeconds.collectLatest { seconds ->
                    tvTimer.text = formatDuration(seconds)
                }
            }
        }

        // Mic button state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupCallManager.isMicMuted.collectLatest { muted ->
                    btnMute.setBackgroundResource(
                        if (muted) R.drawable.bg_call_control_active else R.drawable.bg_call_control_button
                    )
                    if (muted) {
                        ivMute.setImageResource(R.drawable.ic_mic_off)
                        ivMute.setColorFilter(0xFFFF0000.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        ivMute.setImageResource(R.drawable.ic_mic_custom)
                        ivMute.setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                }
            }
        }

        // Camera button state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupCallManager.isVideoEnabled.collectLatest { enabled ->
                    btnCamera.setBackgroundResource(
                        if (!enabled) R.drawable.bg_call_control_active else R.drawable.bg_call_control_button
                    )
                    if (!enabled) {
                        ivCamera.setImageResource(R.drawable.ic_videocam_off)
                        ivCamera.setColorFilter(0xFFFF0000.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    } else {
                        ivCamera.setImageResource(R.drawable.ic_videocam)
                        ivCamera.setColorFilter(0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                }
            }
        }

        // Speaker button state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupCallManager.isSpeakerOn.collectLatest { speaker ->
                    btnSpeaker.setBackgroundResource(
                        if (speaker) R.drawable.bg_call_control_active else R.drawable.bg_call_control_button
                    )
                    ivSpeaker.setColorFilter(
                        if (speaker) 0xFF333333.toInt() else 0xFFFFFFFF.toInt()
                    )
                }
            }
        }

        // Observe call ended
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GroupCallManager.isInGroupCall.collectLatest { inCall ->
                    if (!inCall && !isFinishing) {
                        finish()
                    }
                }
            }
        }

        // Button actions
        btnEndCall.setOnClickListener {
            GroupCallManager.leaveGroupCall(this)
            finish()
        }
        btnMute.setOnClickListener { GroupCallManager.toggleMicrophone() }
        btnCamera.setOnClickListener { GroupCallManager.toggleVideo() }
        btnSwitchCamera.setOnClickListener { GroupCallManager.switchCamera() }
        btnSpeaker.setOnClickListener { GroupCallManager.toggleSpeaker() }
        btnMinimize.setOnClickListener { moveTaskToBack(true) }
        btnAddParticipant.setOnClickListener { showAddParticipantPicker() }
    }

    private fun showAddParticipantPicker() {
        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return
        val existingIds = GroupCallManager.participants.value.map { it.userId }

        val bottomSheet = AddParticipantBottomSheet.newInstance(
            excludeUserIds = existingIds + myUid
        )
        bottomSheet.onUserSelected = { user ->
            GroupCallManager.addParticipant(
                this,
                user.id,
                user.username,
                user.profileImageUrl
            )
            android.widget.Toast.makeText(this, "Added ${user.username}", android.widget.Toast.LENGTH_SHORT).show()
        }
        bottomSheet.show(supportFragmentManager, AddParticipantBottomSheet.TAG)
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m >= 60) {
            val h = m / 60
            String.format("%d:%02d:%02d", h, m % 60, s)
        } else {
            String.format("%d:%02d", m, s)
        }
    }
}
