package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.utils.ThemeManager

/**
 * Contact Info screen – WhatsApp-style profile detail page for chat contacts.
 * Launched from ChatActivity when the user taps the contact name or avatar.
 */
class ContactInfoActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_CONTACT_NAME = "extra_contact_name"
        private const val EXTRA_CONTACT_PHONE = "extra_contact_phone"
        private const val EXTRA_CONTACT_AVATAR = "extra_contact_avatar"
        private const val EXTRA_CONTACT_USER_ID = "extra_contact_user_id"
        private const val EXTRA_CHAT_ID = "extra_chat_id"
        private const val EXTRA_LAST_SEEN = "extra_last_seen"

        fun newIntent(
            context: Context,
            contactName: String,
            contactPhone: String,
            contactAvatar: String,
            contactUserId: String,
            chatId: String,
            lastSeen: String = ""
        ): Intent {
            return Intent(context, ContactInfoActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_CONTACT_PHONE, contactPhone)
                putExtra(EXTRA_CONTACT_AVATAR, contactAvatar)
                putExtra(EXTRA_CONTACT_USER_ID, contactUserId)
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_LAST_SEEN, lastSeen)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Unknown"
        val contactPhone = intent.getStringExtra(EXTRA_CONTACT_PHONE) ?: ""
        val contactAvatar = intent.getStringExtra(EXTRA_CONTACT_AVATAR) ?: ""
        val contactUserId = intent.getStringExtra(EXTRA_CONTACT_USER_ID) ?: ""
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""
        val lastSeen = intent.getStringExtra(EXTRA_LAST_SEEN) ?: ""

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                ContactInfoScreen(
                    contactName = contactName,
                    contactPhone = contactPhone,
                    contactAvatar = contactAvatar,
                    contactUserId = contactUserId,
                    chatId = chatId,
                    lastSeen = lastSeen,
                    onBackClick = { finish() }
                )
            }
        }
    }
}
