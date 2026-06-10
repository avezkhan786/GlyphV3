package com.glyph.glyph_v3.ui.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.BlockRepository
import com.glyph.glyph_v3.data.repo.BlockedUserInfo
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Privacy > Blocked Contacts screen.
 * Shows a list of all users the current user has blocked, with the option to unblock each.
 */
class BlockedContactsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var adapter: BlockedContactsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple layout built in code — can be replaced with XML layout later
        val root = androidx.constraintlayout.widget.ConstraintLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground))
        }

        // Toolbar
        val toolbar = androidx.appcompat.widget.Toolbar(this).apply {
            id = View.generateViewId()
            title = "Blocked Contacts"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        })

        // Empty state text
        emptyView = TextView(this).apply {
            id = View.generateViewId()
            text = "No blocked contacts"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(emptyView, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            0, 0
        ).apply {
            topToBottom = toolbar.id
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        })

        // RecyclerView
        recyclerView = RecyclerView(this).apply {
            id = View.generateViewId()
            layoutManager = LinearLayoutManager(this@BlockedContactsActivity)
        }
        root.addView(recyclerView, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            0, 0
        ).apply {
            topToBottom = toolbar.id
            startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        })

        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = BlockedContactsAdapter { user ->
            showUnblockDialog(user)
        }
        recyclerView.adapter = adapter

        loadBlockedContacts()
    }

    private fun loadBlockedContacts() {
        lifecycleScope.launch {
            try {
                val blockedUsers = withContext(Dispatchers.IO) {
                    BlockRepository.getBlockedUsersWithProfiles()
                }
                if (blockedUsers.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.submitList(blockedUsers)
                }
            } catch (e: Exception) {
                Log.e("BlockedContacts", "Error loading blocked contacts", e)
                Toast.makeText(this@BlockedContactsActivity, "Error loading blocked contacts", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUnblockDialog(user: BlockedUserInfo) {
        val displayName = ContactDisplayNameResolver.getDisplayName(
            otherUserId = user.userId,
            remoteProfileName = user.username,
            remotePhoneNumber = user.phoneNumber
        )
        AlertDialog.Builder(this)
            .setTitle("Unblock $displayName?")
            .setMessage("This contact will be able to send you messages and see your online status again.")
            .setPositiveButton("Unblock") { _, _ ->
                lifecycleScope.launch {
                    try {
                        BlockRepository.unblockUser(user.userId)
                        Toast.makeText(
                            this@BlockedContactsActivity,
                            "$displayName unblocked",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadBlockedContacts() // Refresh list
                    } catch (e: Exception) {
                        Log.e("BlockedContacts", "Error unblocking user", e)
                        Toast.makeText(this@BlockedContactsActivity, "Failed to unblock. Try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }
}

private class BlockedContactsAdapter(
    private val onUnblockClick: (BlockedUserInfo) -> Unit
) : ListAdapter<BlockedUserInfo, BlockedContactsAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<BlockedUserInfo>() {
        override fun areItemsTheSame(a: BlockedUserInfo, b: BlockedUserInfo) = a.userId == b.userId
        override fun areContentsTheSame(a: BlockedUserInfo, b: BlockedUserInfo) = a == b
    }
) {
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val avatar: ImageView = itemView.findViewById(android.R.id.icon)
        val name: TextView = itemView.findViewById(android.R.id.text1)
        val phone: TextView = itemView.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context

        val row = androidx.constraintlayout.widget.ConstraintLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 20, 32, 20)
        }

        val avatarView = ImageView(context).apply {
            id = android.R.id.icon
            val size = (48 * context.resources.displayMetrics.density).toInt()
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(size, size).apply {
                topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        row.addView(avatarView)

        val nameView = TextView(context).apply {
            id = android.R.id.text1
            textSize = 16f
            setPadding((12 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
        }
        row.addView(nameView, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = avatarView.id
            startToEnd = avatarView.id
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        })

        val phoneView = TextView(context).apply {
            id = android.R.id.text2
            textSize = 13f
            setPadding((12 * context.resources.displayMetrics.density).toInt(), 4, 0, 0)
        }
        row.addView(phoneView, androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topToBottom = nameView.id
            startToEnd = avatarView.id
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = avatarView.id
        })

        return ViewHolder(row)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = getItem(position)
        holder.name.text = ContactDisplayNameResolver.getDisplayName(
            otherUserId = user.userId,
            remoteProfileName = user.username,
            remotePhoneNumber = user.phoneNumber
        )
        holder.phone.text = user.phoneNumber

        if (user.profileImageUrl.isNotEmpty()) {
            Glide.with(holder.avatar)
                .load(user.profileImageUrl)
                .circleCrop()
                .placeholder(com.glyph.glyph_v3.R.drawable.ic_default_avatar)
                .into(holder.avatar)
        } else {
            Glide.with(holder.avatar)
                .load(com.glyph.glyph_v3.R.drawable.ic_default_avatar)
                .circleCrop()
                .into(holder.avatar)
        }

        holder.itemView.setOnClickListener { onUnblockClick(user) }
    }
}
