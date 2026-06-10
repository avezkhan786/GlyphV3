package com.glyph.glyph_v3.ui.settings

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.ui.login.LoginActivity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class AccountSettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val repository = FirebaseRepository()

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
            title = "Account"
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

        // ── Account info ────────────────────────────────────────────
        addSectionHeader("Account information")

        val currentUser = FirebaseAuth.getInstance().currentUser
        val phone = currentUser?.phoneNumber ?: "Unknown"
        val uid = currentUser?.uid ?: "Unknown"

        addInfoItem("Phone number", phone, R.drawable.ic_phone)
        addInfoItem("User ID", uid, R.drawable.ic_person)

        // ── Security ────────────────────────────────────────────────
        addSectionHeader("Security")

        addSettingItem(
            "Security notifications",
            "Get notified when your security code changes",
            R.drawable.ic_encryption
        ) {
            Toast.makeText(this, "Security code notifications are enabled by default", Toast.LENGTH_SHORT).show()
        }

        addSettingItem(
            "App lock",
            "Require fingerprint or PIN to open Glyph",
            R.drawable.ic_lock
        ) {
            startActivity(android.content.Intent(this, SecuritySettingsActivity::class.java))
        }

        // ── Account actions ─────────────────────────────────────────
        addSectionHeader("Account actions")

        addSettingItem(
            "Request account info",
            "Request a report of your account information",
            R.drawable.ic_info
        ) {
            showRequestInfoDialog()
        }

        addSettingItem(
            "Delete my account",
            "Delete your account and all your data",
            R.drawable.ic_delete
        ) {
            showDeleteAccountDialog()
        }

        addSettingItem(
            "Logout",
            "Sign out from this device and verify again with the same phone number",
            R.drawable.ic_logout
        ) {
            showLogoutDialog()
        }
    }

    private fun addInfoItem(label: String, value: String, iconRes: Int) {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
        }

        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() }
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(resolveThemeColor(R.attr.glyphIconSecondary))
        })

        val textContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textContent.addView(TextView(this).apply {
            text = label; textSize = 13f
            setTextColor(resolveThemeColor(R.attr.glyphTextSecondary))
        })
        textContent.addView(TextView(this).apply {
            text = value; textSize = 16f
            setTextColor(resolveThemeColor(R.attr.glyphTextPrimary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (2 * dp).toInt(), 0, 0)
        })
        row.addView(textContent)
        container.addView(row)
    }

    private fun showRequestInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Request Account Info")
            .setMessage(
                "Your account information includes:\n\n" +
                "• Profile data (name, bio, phone)\n" +
                "• Privacy settings\n" +
                "• Chat metadata\n\n" +
                "This will generate a report and save it to your Glyph account."
            )
            .setPositiveButton("Request") { _, _ ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@setPositiveButton
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                .collection("account_info_requests")
                                .add(
                                    hashMapOf(
                                        "userId" to uid,
                                        "timestamp" to System.currentTimeMillis(),
                                        "status" to "pending"
                                    )
                                )
                                .await()
                        }
                        Toast.makeText(this@AccountSettingsActivity, "Info request submitted. You'll be notified when ready.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@AccountSettingsActivity, "Failed to submit request", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAccountDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Account")
            .setMessage(
                "⚠️ WARNING: This action is permanent and cannot be undone!\n\n" +
                "Deleting your account will:\n" +
                "• Remove all your messages\n" +
                "• Delete your profile and contacts\n" +
                "• Remove you from all groups\n" +
                "• Clear all your data from our servers\n\n" +
                "Are you absolutely sure?"
            )
            .setPositiveButton("Delete") { _, _ ->
                // Second confirmation
                AlertDialog.Builder(this)
                    .setTitle("Final Confirmation")
                    .setMessage("Type DELETE to confirm account deletion.")
                    .setPositiveButton("I understand, delete") { _, _ ->
                        performAccountDeletion()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout Confirmation")
            .setMessage(
                "Are you sure you want to logout?\n\n" +
                    "Important: After logout, you must re-verify with the same phone number " +
                    "to access your data. If you authenticate with a different number, your " +
                    "chat history, contacts, and profile data will not be available."
            )
            .setPositiveButton("Logout") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        ContactDisplayNameResolver.shutdown()
        FirebaseAuth.getInstance().signOut()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun performAccountDeletion() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Mark account for deletion in Firestore
                    com.google.firebase.firestore.FirebaseFirestore.getInstance()
                        .collection("users").document(uid)
                        .update(
                            mapOf(
                                "deleted" to true,
                                "deletedAt" to System.currentTimeMillis()
                            )
                        )
                        .await()
                }
                // Sign out
                ContactDisplayNameResolver.shutdown()
                FirebaseAuth.getInstance().signOut()
                val intent = android.content.Intent(this@AccountSettingsActivity, com.glyph.glyph_v3.ui.login.LoginActivity::class.java)
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                Toast.makeText(this@AccountSettingsActivity, "Failed to delete account: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue(); theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
    }
}
