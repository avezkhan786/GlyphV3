package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.utils.ThemeManager

/**
 * Hosts the WhatsApp-style Media, Docs & Links viewer for a given chat.
 * Launched from ContactInfoScreen when the user taps the "Media, links, and docs" row.
 */
class MediaDocsLinksActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CONTACT_NAME = "extra_contact_name"
        private const val EXTRA_CHAT_ID      = "extra_chat_id"

        fun newIntent(
            context: Context,
            contactName: String,
            chatId: String
        ): Intent = Intent(context, MediaDocsLinksActivity::class.java).apply {
            putExtra(EXTRA_CONTACT_NAME, contactName)
            putExtra(EXTRA_CHAT_ID, chatId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Contact"
        val chatId      = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                MediaDocsLinksScreen(
                    contactName = contactName,
                    chatId = chatId,
                    onBackClick = { finish() }
                )
            }
        }
    }
}
