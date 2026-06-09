package com.glyph.glyph_v3.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import java.security.MessageDigest

class SecuritySettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

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
            title = "Security"
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

        addSectionHeader("App lock")

        lifecycleScope.launch {
            val lockEnabled = SettingsDataStore.appLockEnabledFlow(this@SecuritySettingsActivity).first()
            val lockType = SettingsDataStore.appLockTypeFlow(this@SecuritySettingsActivity).first()
            val timeout = SettingsDataStore.appLockTimeoutFlow(this@SecuritySettingsActivity).first()
            val biometricAvailable = isBiometricAvailable()

            addToggleItem(
                "App lock",
                if (lockEnabled) "Enabled · ${lockTypeDisplayName(lockType)}" else "Disabled",
                R.drawable.ic_lock,
                lockEnabled
            ) { enabled ->
                if (enabled) {
                    if (biometricAvailable) {
                        showLockTypeDialog()
                    } else {
                        showPinSetupDialog()
                    }
                } else {
                    lifecycleScope.launch {
                        SettingsDataStore.setAppLockEnabled(this@SecuritySettingsActivity, false)
                        buildUI()
                    }
                }
            }

            if (lockEnabled) {
                addSettingItem(
                    "Lock type",
                    lockTypeDisplayName(lockType),
                    R.drawable.ic_lock
                ) {
                    showLockTypeDialog()
                }

                addSettingItem(
                    "Auto-lock timeout",
                    timeoutDisplayName(timeout),
                    R.drawable.ic_clock
                ) {
                    showTimeoutPicker()
                }

                if (lockType == "pin") {
                    addSettingItem(
                        "Change PIN",
                        "Set a new PIN code",
                        R.drawable.ic_edit
                    ) {
                        showPinSetupDialog()
                    }
                }
            }

            // ── Information ─────────────────────────────────────────────
            addSectionHeader("Information")

            val dp = resources.displayMetrics.density
            container.addView(TextView(this@SecuritySettingsActivity).apply {
                text = "When app lock is enabled, you'll need to authenticate to open Glyph after the timeout period. " +
                        "Notifications will still be shown but message content will be hidden."
                textSize = 13f
                setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
                setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
                setLineSpacing(4 * dp, 1f)
            })
        }
    }

    private fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(this)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showLockTypeDialog() {
        val options = mutableListOf("PIN code")
        val values = mutableListOf("pin")

        if (isBiometricAvailable()) {
            options.add(0, "Biometric (Fingerprint / Face)")
            values.add(0, "biometric")
        }

        AlertDialog.Builder(this)
            .setTitle("Lock type")
            .setItems(options.toTypedArray()) { _, which ->
                val selected = values[which]
                if (selected == "biometric") {
                    authenticateWithBiometric {
                        lifecycleScope.launch {
                            SettingsDataStore.setAppLockType(this@SecuritySettingsActivity, "biometric")
                            SettingsDataStore.setAppLockEnabled(this@SecuritySettingsActivity, true)
                            buildUI()
                        }
                    }
                } else {
                    showPinSetupDialog()
                }
            }
            .show()
    }

    private fun authenticateWithBiometric(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@SecuritySettingsActivity, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationFailed() {
                Toast.makeText(this@SecuritySettingsActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable Biometric Lock")
            .setSubtitle("Verify your identity to enable biometric app lock")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun showPinSetupDialog() {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }

        val pinInput = EditText(this).apply {
            hint = "Enter 4-6 digit PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
        }
        layout.addView(pinInput)

        val confirmInput = EditText(this).apply {
            hint = "Confirm PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            maxLines = 1
            setPadding(paddingLeft, (12 * dp).toInt(), paddingRight, paddingBottom)
        }
        layout.addView(confirmInput)

        AlertDialog.Builder(this)
            .setTitle("Set PIN")
            .setView(layout)
            .setPositiveButton("Set") { _, _ ->
                val pin = pinInput.text.toString()
                val confirm = confirmInput.text.toString()

                when {
                    pin.length < 4 || pin.length > 6 -> {
                        Toast.makeText(this, "PIN must be 4-6 digits", Toast.LENGTH_SHORT).show()
                    }
                    pin != confirm -> {
                        Toast.makeText(this, "PINs don't match", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        lifecycleScope.launch {
                            val hash = hashPin(pin)
                            SettingsDataStore.setAppLockPinHash(this@SecuritySettingsActivity, hash)
                            SettingsDataStore.setAppLockType(this@SecuritySettingsActivity, "pin")
                            SettingsDataStore.setAppLockEnabled(this@SecuritySettingsActivity, true)
                            Toast.makeText(this@SecuritySettingsActivity, "PIN set successfully", Toast.LENGTH_SHORT).show()
                            buildUI()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTimeoutPicker() {
        val options = arrayOf("Immediately", "After 1 minute", "After 5 minutes", "After 15 minutes", "After 30 minutes")
        val values = intArrayOf(0, 1, 5, 15, 30)

        AlertDialog.Builder(this)
            .setTitle("Auto-lock after")
            .setItems(options) { _, which ->
                lifecycleScope.launch {
                    SettingsDataStore.setAppLockTimeout(this@SecuritySettingsActivity, values[which])
                    buildUI()
                }
            }
            .show()
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun lockTypeDisplayName(type: String): String = when (type) {
        "biometric" -> "Biometric"
        "pin" -> "PIN code"
        else -> "Biometric"
    }

    private fun timeoutDisplayName(minutes: Int): String = when (minutes) {
        0 -> "Immediately"
        1 -> "After 1 minute"
        else -> "After $minutes minutes"
    }

    // ── UI helpers ──────────────────────────────────────────────────

    private fun addSectionHeader(title: String) {
        val dp = resources.displayMetrics.density
        container.addView(TextView(this).apply {
            text = title; textSize = 13f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding((20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
    }

    private fun addSettingItem(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit) {
        val itemView = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        itemView.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
        itemView.findViewById<TextView>(R.id.settingTitle).text = title
        itemView.findViewById<TextView>(R.id.settingSubtitle).text = subtitle
        itemView.setOnClickListener { onClick() }
        container.addView(itemView)
    }

    private fun addToggleItem(title: String, subtitle: String, iconRes: Int, defaultValue: Boolean, onToggle: (Boolean) -> Unit) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            setBackgroundResource(resolveSelectableBackground())
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() }
            setImageResource(iconRes); imageTintList = android.content.res.ColorStateList.valueOf(resolveThemeColor(R.attr.glyphIconSecondary))
        })
        val tc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        tc.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(resolveThemeColor(R.attr.glyphTextPrimary)); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        tc.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(resolveThemeColor(R.attr.glyphTextSecondary)); setPadding(0, (2 * dp).toInt(), 0, 0) })
        row.addView(tc)
        row.addView(createSettingsModuleSwitch(this, defaultValue, onToggle))
        container.addView(row)
    }

    private fun resolveSelectableBackground(): Int {
        val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); return tv.resourceId
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue(); theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }
}
