package com.glyph.glyph_v3.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.PrivacySettingsRepository
import com.glyph.glyph_v3.ui.status.StatusPrivacyActivity
import com.glyph.glyph_v3.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrivacySettingsActivity : AppCompatActivity() {

    private data class VisibilityOptionSpec(
        val label: String,
        val value: String,
        val description: String
    )

    private lateinit var scrollView: ScrollView
    private lateinit var container: LinearLayout
    private var lastSeenSubtitle: TextView? = null
    private var onlineSubtitle: TextView? = null
    private var profilePhotoSubtitle: TextView? = null
    private var aboutSubtitle: TextView? = null
    private var readReceiptsSwitch: SettingsModuleSwitchView? = null
    private var currentSettings: PrivacySettingsRepository.PrivacySettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

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

        val toolbar = Toolbar(this).apply {
            title = "Privacy"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { onSupportNavigateUp() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(scrollView)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 32)
        }
        scrollView.addView(container, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        PrivacySettingsRepository.getCachedPrivacySettings()?.let { cachedSettings ->
            renderPrivacySettings(cachedSettings)
        }
        syncPrivacySettingsSilently()
    }

    private fun buildSettingsUI(settings: PrivacySettingsRepository.PrivacySettings) {
        container.removeAllViews()

        addSectionHeader(container, "Who can see my personal info")

        val lastSeenItem = addSettingItem(
            container,
            "Last seen",
            visibilityDisplayName(settings.lastSeenVisibility),
            R.drawable.ic_clock
        ) {
            showVisibilityPicker("Last seen", "lastSeen")
        }
        lastSeenSubtitle = lastSeenItem.findViewById(R.id.settingSubtitle)

        val onlineItem = addSettingItem(
            container,
            "Online",
            visibilityDisplayName(settings.onlineVisibility),
            R.drawable.ic_online_status
        ) {
            showVisibilityPicker("Online", "online")
        }
        onlineSubtitle = onlineItem.findViewById(R.id.settingSubtitle)

        val profilePhotoItem = addSettingItem(
            container,
            "Profile photo",
            visibilityDisplayName(settings.profilePhotoVisibility),
            R.drawable.ic_person
        ) {
            showVisibilityPicker("Profile photo", "profilePhoto")
        }
        profilePhotoSubtitle = profilePhotoItem.findViewById(R.id.settingSubtitle)

        val aboutItem = addSettingItem(
            container,
            "About",
            visibilityDisplayName(settings.aboutVisibility),
            R.drawable.ic_info
        ) {
            showVisibilityPicker("About", "about")
        }
        aboutSubtitle = aboutItem.findViewById(R.id.settingSubtitle)

        val readReceiptsView = addToggleItem(
            container,
            "Read receipts",
            "If turned off, you won't send or receive read receipts",
            R.drawable.ic_double_check_blue,
            settings.readReceipts
        ) { enabled ->
            val previousSettings = currentSettings ?: PrivacySettingsRepository.PrivacySettings()
            renderPrivacySettings(previousSettings.copy(readReceipts = enabled))
            lifecycleScope.launch {
                try {
                    PrivacySettingsRepository.updateReadReceipts(enabled)
                } catch (e: Exception) {
                    renderPrivacySettings(previousSettings)
                    Toast.makeText(this@PrivacySettingsActivity, "Failed to update", Toast.LENGTH_SHORT).show()
                }
            }
        }
        readReceiptsSwitch = readReceiptsView.findViewWithTag("toggle_switch")

        addSectionHeader(container, "Privacy controls")

        addSettingItem(
            container,
            "Blocked contacts",
            "Manage blocked contacts",
            R.drawable.ic_block_user
        ) {
            startActivity(Intent(this, BlockedContactsActivity::class.java))
        }

        addSettingItem(
            container,
            "Status privacy",
            "Control who can see your status updates",
            R.drawable.ic_status
        ) {
            startActivity(Intent(this, StatusPrivacyActivity::class.java))
        }

        addSectionHeader(container, "Communication controls")

        addSettingItem(
            container,
            "Walkie-Talkie",
            "Auto-accept and hands-free listening controls",
            R.drawable.ic_walkie_talkie
        ) {
            startActivity(Intent(this, WalkieTalkieSettingsActivity::class.java))
        }

        addSettingItem(
            container,
            "Live Audio Sharing",
            "Control who can listen to your live audio",
            R.drawable.ic_live_audio
        ) {
            startActivity(Intent(this, LiveAudioSettingsActivity::class.java))
        }

        applySettings(settings)
    }

    private fun renderPrivacySettings(settings: PrivacySettingsRepository.PrivacySettings) {
        if (container.childCount == 0) {
            buildSettingsUI(settings)
            return
        }
        applySettings(settings)
    }

    private fun applySettings(settings: PrivacySettingsRepository.PrivacySettings) {
        currentSettings = settings
        lastSeenSubtitle?.text = visibilityDisplayName(settings.lastSeenVisibility)
        onlineSubtitle?.text = visibilityDisplayName(settings.onlineVisibility)
        profilePhotoSubtitle?.text = visibilityDisplayName(settings.profilePhotoVisibility)
        aboutSubtitle?.text = visibilityDisplayName(settings.aboutVisibility)
        readReceiptsSwitch?.isChecked = settings.readReceipts
    }

    private fun syncPrivacySettingsSilently() {
        lifecycleScope.launch {
            val settings = withContext(Dispatchers.IO) {
                PrivacySettingsRepository.getPrivacySettings()
            }
            if (currentSettings == null || currentSettings != settings) {
                renderPrivacySettings(settings)
            }
        }
    }

    private fun showVisibilityPicker(title: String, field: String) {
        val previousSettings = currentSettings ?: return
        val currentValue = when (field) {
            "lastSeen" -> previousSettings.lastSeenVisibility
            "online" -> previousSettings.onlineVisibility
            "profilePhoto" -> previousSettings.profilePhotoVisibility
            "about" -> previousSettings.aboutVisibility
            else -> return
        }
        val dialogView = layoutInflater.inflate(R.layout.dialog_privacy_visibility_picker, null)
        dialogView.findViewById<ImageView>(R.id.image_privacy_dialog_icon)?.setImageResource(
            when (field) {
                "lastSeen" -> R.drawable.ic_clock
                "online" -> R.drawable.ic_online_status
                "profilePhoto" -> R.drawable.ic_person
                "about" -> R.drawable.ic_info
                else -> R.drawable.ic_lock
            }
        )
        dialogView.findViewById<TextView>(R.id.text_privacy_dialog_title)?.text = title
        dialogView.findViewById<TextView>(R.id.text_privacy_dialog_message)?.text =
            privacyDialogMessage(title)
        dialogView.findViewById<MaterialCardView>(R.id.privacy_dialog_card)
            ?.setCardBackgroundColor(dialogSurfaceColor())

        val optionsContainer = dialogView.findViewById<LinearLayout>(R.id.privacy_options_container)
        val optionViews = mutableListOf<Pair<MaterialCardView, RadioButton>>()
        var pendingSelection = currentValue

        visibilityOptions().forEachIndexed { index, option ->
            val optionView = createVisibilityOptionView(
                container = optionsContainer,
                option = option,
                selected = option.value == currentValue
            ) { selectedValue ->
                pendingSelection = selectedValue
                optionViews.forEachIndexed { optionIndex, (card, radio) ->
                    val isSelected = visibilityOptions()[optionIndex].value == selectedValue
                    radio.isChecked = isSelected
                    styleVisibilityOption(card, isSelected)
                }
            }
            optionViews += optionView
        }

        val dialog = MaterialAlertDialogBuilder(this, R.style.GlyphConfirmationDialog)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        configureDialogWindow(dialog)

        dialogView.findViewById<MaterialButton>(R.id.button_privacy_dialog_cancel)
            ?.setOnClickListener { dialog.dismiss() }

        dialogView.findViewById<MaterialButton>(R.id.button_privacy_dialog_save)
            ?.setOnClickListener {
                if (pendingSelection == currentValue) {
                    dialog.dismiss()
                    return@setOnClickListener
                }

                val updatedSettings = when (field) {
                    "lastSeen" -> previousSettings.copy(lastSeenVisibility = pendingSelection)
                    "online" -> previousSettings.copy(onlineVisibility = pendingSelection)
                    "profilePhoto" -> previousSettings.copy(profilePhotoVisibility = pendingSelection)
                    "about" -> previousSettings.copy(aboutVisibility = pendingSelection)
                    else -> previousSettings
                }

                dialog.dismiss()
                renderPrivacySettings(updatedSettings)

                lifecycleScope.launch {
                    try {
                        when (field) {
                            "lastSeen" -> PrivacySettingsRepository.updateLastSeenVisibility(pendingSelection)
                            "online" -> PrivacySettingsRepository.updateOnlineVisibility(pendingSelection)
                            "profilePhoto" -> PrivacySettingsRepository.updateProfilePhotoVisibility(pendingSelection)
                            "about" -> PrivacySettingsRepository.updateAboutVisibility(pendingSelection)
                        }
                    } catch (e: Exception) {
                        renderPrivacySettings(previousSettings)
                        Toast.makeText(this@PrivacySettingsActivity, "Failed to update $title", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        dialog.show()
    }

    private fun visibilityOptions(): List<VisibilityOptionSpec> = listOf(
        VisibilityOptionSpec("Everyone", "everyone", "Anyone on Glyph can see this information."),
        VisibilityOptionSpec("My contacts", "contacts", "Only people saved in your contacts can see this."),
        VisibilityOptionSpec("Nobody", "nobody", "This information stays hidden from everyone else.")
    )

    private fun privacyDialogMessage(title: String): String = when (title) {
        "Last seen" -> "Choose who can see when you were last active."
        "Online" -> "Control who can tell when you're currently online."
        "Profile photo" -> "Decide who can view your profile picture."
        "About" -> "Choose who can read your about information."
        else -> "Choose who can see this information."
    }

    private fun createVisibilityOptionView(
        container: LinearLayout?,
        option: VisibilityOptionSpec,
        selected: Boolean,
        onSelected: (String) -> Unit
    ): Pair<MaterialCardView, RadioButton> {
        val density = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            radius = 20f * density
            cardElevation = 0f
            useCompatPadding = false
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (16 * density).toInt())
        }

        val radioButton = RadioButton(this).apply {
            isChecked = selected
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(
                resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
            )
        }
        row.addView(radioButton)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
        }

        val titleView = TextView(this).apply {
            text = option.label
            setTextColor(resolveThemeColor(R.attr.glyphTextPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        textColumn.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = option.description
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.08f)
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        textColumn.addView(subtitleView)

        row.addView(textColumn)
        card.addView(row)

        styleVisibilityOption(card, selected)

        val clickListener = View.OnClickListener { onSelected(option.value) }
        card.setOnClickListener(clickListener)
        row.setOnClickListener(clickListener)
        container?.addView(card)
        return card to radioButton
    }

    private fun styleVisibilityOption(card: MaterialCardView, selected: Boolean) {
        val surface = dialogSurfaceColor()
        val accent = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
        card.setCardBackgroundColor(surface)
        card.strokeWidth = 0
    }

    private fun dialogSurfaceColor(): Int {
        return if (ThemeManager.isDarkMode(this)) {
            ContextCompat.getColor(this, R.color.ai_dark_surface)
        } else {
            resolveThemeColor(R.attr.glyphDialogBackground)
        }
    }

    private fun configureDialogWindow(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            attributes = attributes.apply {
                windowAnimations = R.style.GlyphDialogWindowAnimation
            }
        }
    }

    private fun visibilityDisplayName(value: String): String = when (value) {
        "everyone" -> "Everyone"
        "contacts" -> "My contacts"
        "nobody" -> "Nobody"
        else -> "Everyone"
    }

    private fun addSectionHeader(container: LinearLayout, title: String) {
        val header = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt()
            )
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        container.addView(header)
    }

    private fun addSettingItem(
        container: LinearLayout,
        title: String,
        subtitle: String,
        iconRes: Int,
        onClick: () -> Unit
    ): View {
        val itemView = LayoutInflater.from(this)
            .inflate(R.layout.item_setting, container, false)

        itemView.findViewById<ImageView>(R.id.settingIcon).setImageResource(iconRes)
        itemView.findViewById<TextView>(R.id.settingTitle).text = title
        itemView.findViewById<TextView>(R.id.settingSubtitle).text = subtitle
        itemView.setOnClickListener { onClick() }
        container.addView(itemView)
        return itemView
    }

    private fun addToggleItem(
        container: LinearLayout,
        title: String,
        subtitle: String,
        iconRes: Int,
        defaultValue: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            setBackgroundResource(android.R.attr.selectableItemBackground.let {
                val typedValue = TypedValue()
                context.theme.resolveAttribute(it, typedValue, true)
                typedValue.resourceId
            })
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

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(resolveThemeColor(R.attr.glyphTextPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        textContainer.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        textContainer.addView(subtitleView)
        row.addView(textContainer)

        val switch = createSettingsModuleSwitch(this, defaultValue, onToggle).apply {
            tag = "toggle_switch"
        }
        row.addView(switch)

        container.addView(row)
        return row
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
