package com.glyph.glyph_v3.ui.settings

import android.graphics.Color
import android.os.Bundle
import android.util.Log
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.glyph.glyph_v3.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class HelpSupportActivity : AppCompatActivity() {

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
            title = "Help"
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

        // ── FAQ ─────────────────────────────────────────────────────
        addSectionHeader("Frequently Asked Questions")

        val faqs = listOf(
            "How do I change my profile picture?" to "Go to Settings, tap on your profile photo, then choose 'Take Photo' or 'Choose from Gallery'.",
            "How do I block someone?" to "Open the chat with that person, tap the three dots menu > Contact Info > Block. You can also go to Settings > Privacy > Blocked Contacts.",
            "Can I see who viewed my status?" to "Yes! When viewing your own status, swipe up to see the list of people who have seen it.",
            "How do I change the chat wallpaper?" to "Go to Settings > Chats > Wallpapers. You can choose different wallpapers for each theme.",
            "How do I enable dark mode?" to "Go to Settings > Theme and select 'Dark Mode' or 'Match System' to follow your device theme.",
            "What is Walkie-Talkie?" to "Walkie-Talkie lets you have a push-to-talk style conversation with contacts. You can configure auto-accept in Settings > Privacy > Walkie-Talkie.",
            "How do I use Live Audio Sharing?" to "During a chat, tap the Live Audio icon to share audio in real-time with your contact.",
            "How do read receipts work?" to "Blue double-check marks indicate that your message has been read. You can disable read receipts in Settings > Privacy.",
            "How do I delete messages?" to "Long-press a message to select it, then tap the delete icon. You can choose to delete for everyone within a time window.",
            "How do I back up my chats?" to "Chat backup options are available in Settings > Chats. Messages are synced via Firebase in real-time."
        )

        faqs.forEach { (question, answer) ->
            addFaqItem(question, answer)
            addDivider()
        }

        // ── Support ─────────────────────────────────────────────────
        addSectionHeader("Support")

        addSettingItem(
            "Report a bug",
            "Submit a bug report to help us improve",
            R.drawable.ic_report
        ) {
            showBugReportDialog()
        }

        addDivider()

        addSettingItem(
            "Contact support",
            "Get help from our support team",
            R.drawable.ic_help
        ) {
            showContactSupportDialog()
        }

        addDivider()

        addSettingItem(
            "App info",
            "Glyph v${com.glyph.glyph_v3.BuildConfig.VERSION_NAME}",
            R.drawable.ic_info
        ) {
            showAppInfoDialog()
        }
    }

    private fun addFaqItem(question: String, answer: String) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
            setBackgroundResource(resolveSelectableBackground())
            setOnClickListener {
                // Toggle answer visibility
                val answerView = getChildAt(1) as? TextView ?: return@setOnClickListener
                answerView.visibility = if (answerView.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }

        row.addView(TextView(this).apply {
            text = question
            textSize = 15f
            setTextColor(resolveThemeColor(R.attr.glyphTextPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        row.addView(TextView(this).apply {
            text = answer
            textSize = 14f
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
            setPadding(0, (8 * dp).toInt(), 0, 0)
            visibility = View.GONE
            setLineSpacing(3 * dp, 1f)
        })

        container.addView(row)
    }

    private fun showBugReportDialog() {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }

        val titleInput = EditText(this).apply {
            hint = "Brief description of the issue"
            maxLines = 1
        }
        layout.addView(titleInput)

        val detailInput = EditText(this).apply {
            hint = "Steps to reproduce the bug..."
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP
            setPadding(paddingLeft, (12 * dp).toInt(), paddingRight, paddingBottom)
        }
        layout.addView(detailInput)

        AlertDialog.Builder(this)
            .setTitle("Report a Bug")
            .setView(layout)
            .setPositiveButton("Submit") { _, _ ->
                val title = titleInput.text.toString().trim()
                val details = detailInput.text.toString().trim()

                if (title.isEmpty()) {
                    Toast.makeText(this, "Please enter a brief description", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                submitBugReport(title, details)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitBugReport(title: String, details: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val report = hashMapOf(
            "userId" to uid,
            "title" to title,
            "details" to details,
            "timestamp" to System.currentTimeMillis(),
            "appVersion" to com.glyph.glyph_v3.BuildConfig.VERSION_NAME,
            "deviceModel" to android.os.Build.MODEL,
            "androidVersion" to android.os.Build.VERSION.SDK_INT,
            "status" to "open"
        )

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FirebaseFirestore.getInstance()
                        .collection("bug_reports")
                        .add(report)
                        .await()
                }
                Toast.makeText(this@HelpSupportActivity, "Bug report submitted. Thank you!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("HelpSupport", "Failed to submit bug report", e)
                Toast.makeText(this@HelpSupportActivity, "Failed to submit report. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showContactSupportDialog() {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }

        val messageInput = EditText(this).apply {
            hint = "Describe your issue or question..."
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP
        }
        layout.addView(messageInput)

        AlertDialog.Builder(this)
            .setTitle("Contact Support")
            .setView(layout)
            .setPositiveButton("Send") { _, _ ->
                val message = messageInput.text.toString().trim()
                if (message.isEmpty()) {
                    Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                submitSupportRequest(message)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitSupportRequest(message: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val request = hashMapOf(
            "userId" to uid,
            "message" to message,
            "timestamp" to System.currentTimeMillis(),
            "appVersion" to com.glyph.glyph_v3.BuildConfig.VERSION_NAME,
            "status" to "pending"
        )

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FirebaseFirestore.getInstance()
                        .collection("support_requests")
                        .add(request)
                        .await()
                }
                Toast.makeText(this@HelpSupportActivity, "Support request sent. We'll get back to you soon!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("HelpSupport", "Failed to submit support request", e)
                Toast.makeText(this@HelpSupportActivity, "Failed to send request. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAppInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Glyph")
            .setMessage(
                "Glyph v${com.glyph.glyph_v3.BuildConfig.VERSION_NAME}\n\n" +
                "A modern messaging app built with Firebase.\n\n" +
                "Device: ${android.os.Build.MODEL}\n" +
                "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n\n" +
                "Built with precision by Avez Khan"
            )
            .setPositiveButton("OK", null)
            .show()
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

    private fun addDivider() {
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = (72 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(resolveThemeColor(R.attr.glyphDivider))
        })
    }

    private fun resolveSelectableBackground(): Int {
        val tv = TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); return tv.resourceId
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue(); theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }
}
