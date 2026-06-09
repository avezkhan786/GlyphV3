package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.utils.PhoneNumberUtil

class ContactViewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CONTACT_NAME = "contact_name"
        private const val EXTRA_CONTACT_PHONE = "contact_phone"
        private const val EXTRA_REGISTERED_USER_ID = "registered_user_id"
        private const val EXTRA_REGISTERED_AVATAR_URL = "registered_avatar_url"
        private const val EXTRA_REGISTERED_USERNAME = "registered_username"
        private const val EXTRA_CURRENT_USER_ID = "current_user_id"
        private const val EXTRA_CURRENT_USER_PHONE = "current_user_phone"

        /** Avatar background colors cycled by first char of contact name */
        private val AVATAR_COLORS = intArrayOf(
            0xFF00897B.toInt(), // teal
            0xFF1565C0.toInt(), // blue
            0xFF6A1B9A.toInt(), // purple
            0xFFAD1457.toInt(), // pink
            0xFFE65100.toInt(), // deep orange
            0xFF2E7D32.toInt(), // green
            0xFF4527A0.toInt(), // deep purple
            0xFF00838F.toInt(), // cyan
        )

        fun newIntent(
            context: Context,
            contactName: String,
            contactPhone: String,
            registeredUserId: String? = null,
            registeredAvatarUrl: String? = null,
            registeredUsername: String? = null,
            currentUserId: String? = null,
            currentUserPhone: String? = null
        ): Intent {
            return Intent(context, ContactViewActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_CONTACT_PHONE, contactPhone)
                putExtra(EXTRA_REGISTERED_USER_ID, registeredUserId)
                putExtra(EXTRA_REGISTERED_AVATAR_URL, registeredAvatarUrl)
                putExtra(EXTRA_REGISTERED_USERNAME, registeredUsername)
                putExtra(EXTRA_CURRENT_USER_ID, currentUserId)
                putExtra(EXTRA_CURRENT_USER_PHONE, currentUserPhone)
            }
        }

        fun avatarColorForName(name: String): Int {
            if (name.isBlank()) return AVATAR_COLORS[0]
            val idx = (name.first().uppercaseChar().code) % AVATAR_COLORS.size
            return AVATAR_COLORS[idx]
        }

        fun initialsFor(name: String): String {
            if (name.isBlank()) return "?"
            val parts = name.trim().split("\\s+".toRegex())
            return if (parts.size >= 2) {
                "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            } else {
                parts[0].take(2).uppercase()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_contact_view)

        // Match status bar color and toolbar background to glyphToolbarBackground
        val tvBg = TypedValue()
        theme.resolveAttribute(R.attr.glyphToolbarBackground, tvBg, true)
        window.statusBarColor = tvBg.data

        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Contact"
        val contactPhone = intent.getStringExtra(EXTRA_CONTACT_PHONE) ?: ""
        val registeredUserId = intent.getStringExtra(EXTRA_REGISTERED_USER_ID)
        val registeredAvatarUrl = intent.getStringExtra(EXTRA_REGISTERED_AVATAR_URL)
        val registeredUsername = intent.getStringExtra(EXTRA_REGISTERED_USERNAME)
        val currentUserId = intent.getStringExtra(EXTRA_CURRENT_USER_ID)
        val currentUserPhone = intent.getStringExtra(EXTRA_CURRENT_USER_PHONE)

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        // Force theme-aware tint for the navigation icon (back arrow)
        val toolbarIconColor = resolveColorAttr(R.attr.glyphToolbarIcon)
        toolbar.navigationIcon?.setTint(toolbarIconColor)

        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = "View contact"

        // Handle insets for AppBarLayout to prevent overlap with status bar
        val appBarLayout = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = statusBars.top)
            insets
        }

        // Large avatar
        val viewAvatarBgLarge = findViewById<View>(R.id.viewAvatarBgLarge)
        val tvInitialsLarge = findViewById<TextView>(R.id.tvAvatarInitialsLarge)
        val ivAvatarLarge = findViewById<ShapeableImageView>(R.id.ivAvatarLarge)
        val tvContactNameLarge = findViewById<TextView>(R.id.tvContactNameLarge)
        val llOnGlyphBadgeLarge = findViewById<LinearLayout>(R.id.llOnGlyphBadgeLarge)
        val tvBadgeLabel = findViewById<TextView>(R.id.tvBadgeLabel)
        val tvContactPhone = findViewById<TextView>(R.id.tvContactPhone)
        val tvGlyphUsername = findViewById<TextView?>(R.id.tvGlyphUsername)
        val llMessageOnGlyph = findViewById<View>(R.id.llMessageOnGlyph)
        val btnCallPhone = findViewById<FrameLayout>(R.id.btnCallPhone)
        val btnSmsPhone = findViewById<FrameLayout>(R.id.btnSmsPhone)

        // Check if this is the user's own contact
        val normalizedContact = PhoneNumberUtil.normalizeToLast10Digits(contactPhone)
        val normalizedMyPhone = if (!currentUserPhone.isNullOrEmpty()) PhoneNumberUtil.normalizeToLast10Digits(currentUserPhone) else null
        val isMe = (registeredUserId != null && registeredUserId == currentUserId) ||
                   (normalizedContact.isNotEmpty() && normalizedContact == normalizedMyPhone)

        // Set initials + color
        val initials = initialsFor(contactName)
        val avatarColor = avatarColorForName(contactName)
        viewAvatarBgLarge.backgroundTintList = android.content.res.ColorStateList.valueOf(avatarColor)
        tvInitialsLarge.text = initials
        tvContactNameLarge.text = if (isMe) "$contactName (You)" else contactName
        tvContactPhone.text = contactPhone

        // Load real avatar if registered and has photo
        if (!registeredAvatarUrl.isNullOrBlank()) {
            ivAvatarLarge.visibility = View.VISIBLE
            tvInitialsLarge.visibility = View.INVISIBLE
            Glide.with(this)
                .load(registeredAvatarUrl)
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .circleCrop()
                .into(ivAvatarLarge)
        } else {
            ivAvatarLarge.visibility = View.GONE
            tvInitialsLarge.visibility = View.VISIBLE
        }

        // Show "on Glyph" badge if registered OR if it's me (since I'm on Glyph)
        if (!registeredUserId.isNullOrBlank() || isMe) {
            llOnGlyphBadgeLarge.visibility = View.VISIBLE
            if (isMe) {
                tvBadgeLabel.text = "This is you"
            }
        }

        // Action buttons gating for own contact
        if (isMe) {
            llMessageOnGlyph.visibility = View.GONE
            btnCallPhone.visibility = View.GONE
            btnSmsPhone.visibility = View.GONE
        } else {
            // Show "Message on Glyph" section if registered
            if (!registeredUserId.isNullOrBlank() && !currentUserId.isNullOrBlank()) {
                llMessageOnGlyph.visibility = View.VISIBLE
                tvGlyphUsername?.text = if (!registeredUsername.isNullOrBlank()) "@$registeredUsername" else contactName
                llMessageOnGlyph.setOnClickListener {
                    openChatIfRegistered(
                        currentUserId,
                        registeredUserId,
                        contactName,
                        registeredAvatarUrl,
                        registeredUsername
                    )
                }
            } else {
                llMessageOnGlyph.visibility = View.GONE
            }

            // Call button
            btnCallPhone.setOnClickListener {
                if (contactPhone.isNotBlank()) {
                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$contactPhone"))
                    startActivity(dialIntent)
                }
            }

            // SMS button
            btnSmsPhone.setOnClickListener {
                if (!registeredUserId.isNullOrBlank() && !currentUserId.isNullOrBlank()) {
                    openChatIfRegistered(currentUserId, registeredUserId, contactName, registeredAvatarUrl, registeredUsername)
                } else if (contactPhone.isNotBlank()) {
                    showInviteDialog(contactName, contactPhone)
                }
            }
        }
    }

    private fun openChatIfRegistered(
        currentUserId: String,
        registeredUserId: String,
        contactName: String,
        registeredAvatarUrl: String?,
        registeredUsername: String?
    ) {
        val chatId = if (currentUserId < registeredUserId) {
            "${currentUserId}_$registeredUserId"
        } else {
            "${registeredUserId}_$currentUserId"
        }
        val chatIntent = ChatActivity.newIntent(
            this,
            chatId,
            registeredUserId,
            registeredUsername ?: contactName,
            registeredAvatarUrl ?: ""
        )
        startActivity(chatIntent)
        finish()
    }

    private fun showInviteDialog(name: String, phoneNumber: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_invite, null)

        val titleView = dialogView.findViewById<TextView>(R.id.invite_title)
        val subtitleView = dialogView.findViewById<TextView>(R.id.invite_subtitle)
        val previewView = dialogView.findViewById<android.widget.EditText>(R.id.invite_message_preview)
        val btnSend = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_send_sms)
        val btnShare = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_share)

        titleView.text = getString(R.string.invite_dialog_title, name)
        subtitleView.text = getString(R.string.invite_dialog_subtitle)

        val playStoreUrl = "https://play.google.com/store/apps/details?id=${packageName}"
        val message = getString(R.string.invite_message, playStoreUrl)
        previewView.setText(message)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton(getString(R.string.action_not_now), null)
            .create()

        dialog.setCanceledOnTouchOutside(true)

        btnSend.setOnClickListener {
            val finalMessage = previewView.text.toString()
            dialog.dismiss()
            sendSmsInvite(phoneNumber, finalMessage)
        }

        btnShare.setOnClickListener {
            val finalMessage = previewView.text.toString()
            dialog.dismiss()
            shareInviteText(phoneNumber, finalMessage)
        }

        dialog.show()

        val neg = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
        neg?.setTextColor(resolveColorAttr(R.attr.glyphTextPrimary))
    }

    private fun sendSmsInvite(phoneNumber: String, message: String) {
        val smsUri = Uri.parse("smsto:${Uri.encode(phoneNumber)}")
        val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", message)
        }

        val pm = packageManager
        val resolved = pm.queryIntentActivities(smsIntent, 0)
        if (resolved.isNotEmpty()) {
            val preferred = resolved.find { it.activityInfo.packageName == "com.google.android.apps.messaging" }?.activityInfo?.packageName
            if (preferred != null) smsIntent.setPackage(preferred)
            try {
                startActivity(smsIntent)
                finish()
            } catch (e: Exception) {
                shareInviteText(phoneNumber, message)
            }
        } else {
            shareInviteText(phoneNumber, message)
        }
    }

    private fun shareInviteText(phoneNumber: String, message: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, message)
            type = "text/plain"
        }

        val chooser = Intent.createChooser(sendIntent, getString(R.string.invite_share))
        if (sendIntent.resolveActivity(packageManager) != null) {
            startActivity(chooser)
            finish()
        }
    }

    private fun resolveColorAttr(attrName: Int): Int {
        val typedValue = android.util.TypedValue()
        val theme = theme
        theme.resolveAttribute(attrName, typedValue, true)
        return typedValue.data
    }
}
