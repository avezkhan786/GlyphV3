package com.glyph.glyph_v3.ui.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.preferences.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    // Which ringtone picker is currently open
    private var pendingRingtoneTarget: RingtoneTarget? = null

    private enum class RingtoneTarget { MESSAGE, GROUP, CALL }

    private lateinit var ringtonePickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        ringtonePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val target = pendingRingtoneTarget ?: return@registerForActivityResult
            pendingRingtoneTarget = null

            val uri: Uri? = result.data
                ?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            val uriString = uri?.toString() ?: "None"

            lifecycleScope.launch {
                when (target) {
                    RingtoneTarget.MESSAGE -> SettingsDataStore.setNotificationSound(this@NotificationSettingsActivity, uriString)
                    RingtoneTarget.GROUP -> SettingsDataStore.setGroupNotificationSound(this@NotificationSettingsActivity, uriString)
                    RingtoneTarget.CALL -> SettingsDataStore.setCallRingtone(this@NotificationSettingsActivity, uriString)
                }
                // Rebuild UI to show new title
                buildUI()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        val toolbar = androidx.appcompat.widget.Toolbar(this).apply {
            title = "Notifications"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(scrollView)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 32)
        }
        scrollView.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        buildUI()
    }

    private fun buildUI() {
        container.removeAllViews()

        // ── Message Notifications ───────────────────────────────────
        addSectionHeader("Message notifications")

        lifecycleScope.launch {
            val notifEnabled = SettingsDataStore.notificationsEnabledFlow(this@NotificationSettingsActivity).first()
            val vibrate = SettingsDataStore.notificationVibrateFlow(this@NotificationSettingsActivity).first()
            val popup = SettingsDataStore.notificationPopupFlow(this@NotificationSettingsActivity).first()
            val sound = SettingsDataStore.notificationSoundFlow(this@NotificationSettingsActivity).first()

            addToggleItem(
                "Message notifications",
                "Show notifications for new messages",
                R.drawable.ic_notifications,
                notifEnabled
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setNotificationsEnabled(this@NotificationSettingsActivity, enabled) }
            }

            addDivider()

            addSettingItem(
                "Notification tone",
                ringtoneDisplayName(sound, RingtoneManager.TYPE_NOTIFICATION),
                R.drawable.ic_volume_up
            ) {
                openRingtonePicker(RingtoneTarget.MESSAGE, RingtoneManager.TYPE_NOTIFICATION, sound)
            }

            addDivider()

            addToggleItem(
                "Vibrate",
                "Vibrate when a new message arrives",
                R.drawable.ic_phone_portrait,
                vibrate
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setNotificationVibrate(this@NotificationSettingsActivity, enabled) }
            }

            addDivider()

            addToggleItem(
                "Popup notification",
                "Show popup when a new message arrives",
                R.drawable.ic_chat,
                popup
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setNotificationPopup(this@NotificationSettingsActivity, enabled) }
            }

            // ── Group Notifications ─────────────────────────────────────
            addSectionHeader("Group notifications")

            val groupEnabled = SettingsDataStore.groupNotificationsEnabledFlow(this@NotificationSettingsActivity).first()
            val groupVibrate = SettingsDataStore.groupNotificationVibrateFlow(this@NotificationSettingsActivity).first()
            val groupSound = SettingsDataStore.groupNotificationSoundFlow(this@NotificationSettingsActivity).first()

            addToggleItem(
                "Group notifications",
                "Show notifications for group messages",
                R.drawable.ic_group,
                groupEnabled
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setGroupNotificationsEnabled(this@NotificationSettingsActivity, enabled) }
            }

            addDivider()

            addSettingItem(
                "Group tone",
                ringtoneDisplayName(groupSound, RingtoneManager.TYPE_NOTIFICATION),
                R.drawable.ic_volume_up
            ) {
                openRingtonePicker(RingtoneTarget.GROUP, RingtoneManager.TYPE_NOTIFICATION, groupSound)
            }

            addDivider()

            addToggleItem(
                "Group vibrate",
                "Vibrate for group messages",
                R.drawable.ic_phone_portrait,
                groupVibrate
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setGroupNotificationVibrate(this@NotificationSettingsActivity, enabled) }
            }

            // ── Call Notifications ──────────────────────────────────────
            addSectionHeader("Calls")

            val callVibrate = SettingsDataStore.callVibrateFlow(this@NotificationSettingsActivity).first()
            val callRingtone = SettingsDataStore.callRingtoneFlow(this@NotificationSettingsActivity).first()

            addSettingItem(
                "Ringtone",
                ringtoneDisplayName(callRingtone, RingtoneManager.TYPE_RINGTONE),
                R.drawable.ic_call
            ) {
                openRingtonePicker(RingtoneTarget.CALL, RingtoneManager.TYPE_RINGTONE, callRingtone)
            }

            addDivider()

            addToggleItem(
                "Vibrate",
                "Vibrate for incoming calls",
                R.drawable.ic_phone_portrait,
                callVibrate
            ) { enabled ->
                lifecycleScope.launch { SettingsDataStore.setCallVibrate(this@NotificationSettingsActivity, enabled) }
            }

            // ── System notification settings ────────────────────────────
            addSectionHeader("System")

            addSettingItem(
                "Notification channels",
                "Manage notification channels in system settings",
                R.drawable.ic_settings
            ) {
                openSystemNotificationSettings()
            }
        }
    }

    private fun openSystemNotificationSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    // ── UI helper methods ───────────────────────────────────────────

    private fun addSectionHeader(title: String) {
        val header = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            val dp = resources.displayMetrics.density
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(header)
    }

    private fun addSettingItem(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit) {
        val itemView = LayoutInflater.from(this)
            .inflate(R.layout.item_setting, container, false)
        itemView.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
        itemView.findViewById<TextView>(R.id.settingTitle).text = title
        itemView.findViewById<TextView>(R.id.settingSubtitle).text = subtitle
        itemView.setOnClickListener { onClick() }
        container.addView(itemView)
    }

    private fun addToggleItem(
        title: String,
        subtitle: String,
        iconRes: Int,
        defaultValue: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            setBackgroundResource(resolveSelectableBackground())
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply {
                marginEnd = (14 * dp).toInt()
            }
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(resolveThemeColor(R.attr.glyphIconSecondary))
        }
        row.addView(icon)

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textContainer.addView(TextView(this).apply {
            text = title; textSize = 16f
            setTextColor(resolveThemeColor(R.attr.glyphTextPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        textContainer.addView(TextView(this).apply {
            text = subtitle; textSize = 13f
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(textContainer)

        row.addView(createSettingsModuleSwitch(this, defaultValue, onToggle))
        container.addView(row)
    }

    private fun addDivider() {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = (72 * resources.displayMetrics.density).toInt() }
            setBackgroundColor(resolveThemeColor(R.attr.glyphDivider))
        }
        container.addView(divider)
    }

    private fun resolveSelectableBackground(): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return typedValue.resourceId
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) ContextCompat.getColor(this, typedValue.resourceId) else typedValue.data
    }

    private fun openRingtonePicker(target: RingtoneTarget, type: Int, currentValue: String) {
        pendingRingtoneTarget = target
        val existingUri: Uri? = when (currentValue) {
            "Default" -> RingtoneManager.getDefaultUri(
                if (type == RingtoneManager.TYPE_RINGTONE) RingtoneManager.TYPE_RINGTONE
                else RingtoneManager.TYPE_NOTIFICATION
            )
            "None" -> null
            else -> runCatching { Uri.parse(currentValue) }.getOrNull()
        }
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, type)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(type)
            )
            if (type == RingtoneManager.TYPE_RINGTONE) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select ringtone")
            } else {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select notification tone")
            }
        }
        ringtonePickerLauncher.launch(intent)
    }

    private fun ringtoneDisplayName(storedValue: String, type: Int): String {
        return when (storedValue) {
            "Default" -> "Default"
            "None" -> "Silent"
            else -> {
                try {
                    val uri = Uri.parse(storedValue)
                    val ringtone = RingtoneManager.getRingtone(this, uri)
                    ringtone?.getTitle(this) ?: "Default"
                } catch (_: Exception) {
                    "Default"
                }
            }
        }
    }
}
