package com.glyph.glyph_v3.ui.chatlist

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.databinding.ItemChatListBinding
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ChatListAdapter(
    private val onChatClick: (Chat) -> Unit,
    private val onAvatarClick: ((Chat, View) -> Unit)? = null,
    private val currentUserId: String? = null
) : ListAdapter<Chat, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {

    // Predefined avatar background colors for letter avatars
    private val avatarColors = listOf(
        0xFF25D366.toInt(), // WhatsApp green
        0xFF128C7E.toInt(), // Teal
        0xFF075E54.toInt(), // Dark teal
        0xFF34B7F1.toInt(), // Light blue
        0xFF00A884.toInt(), // Green
        0xFFD4AC0D.toInt(), // Gold
        0xFFE74C3C.toInt(), // Red
        0xFF9B59B6.toInt(), // Purple
        0xFF3498DB.toInt(), // Blue
        0xFFE67E22.toInt()  // Orange
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChatViewHolder(private val binding: ItemChatListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChatClick(getItem(position))
                }
            }
            
            // Avatar click listener for profile preview
            binding.avatarContainer.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onAvatarClick?.invoke(getItem(position), binding.ivAvatar)
                }
            }
        }

        fun bind(chat: Chat) {
            val context = binding.root.context

            // Username — resolve with device contact name priority
            val displayName = if (chat.isGroup) {
                chat.groupName.ifBlank { "Group" }
            } else {
                val otherUserId = chat.participants.firstOrNull { it != currentUserId } ?: ""
                ContactDisplayNameResolver.getDisplayName(
                    otherUserId = otherUserId,
                    remoteProfileName = chat.otherUsername
                )
            }
            val displayAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar
            binding.tvUsername.text = displayName
            
            // Draft overrides last message preview (WhatsApp-style)
            val draftText = chat.draft.trim()
            val hasDraft = draftText.isNotEmpty()
            if (hasDraft) {
                val draftColor = MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorError,
                    ContextCompat.getColor(context, android.R.color.holo_red_dark)
                )
                binding.tvDraftLabel.visibility = View.VISIBLE
                binding.tvDraftLabel.setTextColor(draftColor)
                binding.ivMessageStatus.visibility = View.GONE
                binding.tvLastMessage.text = draftText
            } else {
                binding.tvDraftLabel.visibility = View.GONE
                binding.tvLastMessage.text = chat.lastMessage
            }
            
            // Timestamp formatting (WhatsApp style)
            if (chat.lastMessageTimestamp != null) {
                binding.tvTimestamp.text = formatTimestampWhatsApp(chat.lastMessageTimestamp)
                // Green timestamp if unread
                if (chat.unreadCount > 0) {
                    val primary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, ContextCompat.getColor(context, R.color.whatsapp_green))
                    binding.tvTimestamp.setTextColor(primary)
                } else {
                    val muted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, ContextCompat.getColor(context, android.R.color.darker_gray))
                    binding.tvTimestamp.setTextColor(muted)
                }
            } else {
                binding.tvTimestamp.text = ""
            }

            // Unread badge
            if (chat.unreadCount > 0) {
                binding.badgeUnread.visibility = View.VISIBLE
                binding.badgeUnread.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
            } else {
                binding.badgeUnread.visibility = View.GONE
            }

            // Message status icon (only for sent messages by current user)
            val isOwnMessage = chat.lastMessageSenderId == currentUserId
            if (!hasDraft) {
                if (isOwnMessage && chat.lastMessage.isNotEmpty()) {
                    binding.ivMessageStatus.visibility = View.VISIBLE
                    // For now, show double check (delivered) - can be enhanced with actual status
                    binding.ivMessageStatus.setImageResource(R.drawable.ic_double_check)
                } else {
                    binding.ivMessageStatus.visibility = View.GONE
                }
            } else {
                binding.ivMessageStatus.visibility = View.GONE
            }

            // Avatar handling - INSTANT LOAD from local cache
            val otherUserId = if (chat.isGroup) "" else (chat.participants.firstOrNull { it != currentUserId } ?: "")
            if (displayAvatar.isNotEmpty() && (chat.isGroup || otherUserId.isNotEmpty())) {
                // Try to load from local cache first (instant, synchronous)
                val localAvatarPath = if (chat.isGroup) {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(chat.id)
                } else {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(otherUserId)
                }
                
                if (localAvatarPath != null) {
                    // Load from local storage - INSTANT, no network delay
                    // Use signature() with file timestamp to force Glide to reload when file changes
                    val file = java.io.File(localAvatarPath)
                    binding.ivAvatar.visibility = View.VISIBLE
                    binding.tvAvatarInitial.visibility = View.GONE
                    Glide.with(context)
                        .load(file)
                        .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                        .skipMemoryCache(true)  // Force re-decode from file
                        .transform(CircleCrop())
                        .placeholder(R.drawable.ic_default_avatar)
                        .error(R.drawable.ic_default_avatar)
                        .into(binding.ivAvatar)
                } else {
                    // Fallback to URL (first time load)
                    binding.ivAvatar.visibility = View.VISIBLE
                    binding.tvAvatarInitial.visibility = View.GONE
                    Glide.with(context)
                        .load(displayAvatar)
                        .transform(CircleCrop())
                        .placeholder(R.drawable.ic_default_avatar)
                        .error(R.drawable.ic_default_avatar)
                        .into(binding.ivAvatar)
                }
            } else {
                // Show letter avatar
                val bgColor = if (chat.isGroup) {
                    android.graphics.Color.parseColor("#3A2B1C")
                } else {
                    val colorIndex = abs(displayName.hashCode()) % avatarColors.size
                    avatarColors[colorIndex]
                }
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(bgColor)
                }
                if (chat.isGroup) {
                    binding.ivAvatar.visibility = View.VISIBLE
                    binding.tvAvatarInitial.visibility = View.GONE
                    binding.ivAvatar.setImageResource(R.drawable.ic_group)
                    binding.ivAvatar.setColorFilter(android.graphics.Color.parseColor("#FFD166"))
                    binding.ivAvatar.background = bgDrawable
                } else {
                    val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    binding.tvAvatarInitial.text = initial
                    binding.tvAvatarInitial.visibility = View.VISIBLE
                    binding.tvAvatarInitial.background = bgDrawable
                    binding.ivAvatar.setImageDrawable(null)
                    binding.ivAvatar.background = bgDrawable
                }
            }
            
            // Online indicator
            binding.onlineIndicator.visibility = if (!chat.isGroup && chat.isOtherUserOnline) View.VISIBLE else View.GONE
        }

        /**
         * WhatsApp-style timestamp formatting:
         * - Today: Show time (10:45 AM)
         * - Yesterday: "Yesterday"
         * - This week: Day name (Monday)
         * - Older: Date (12/15/24)
         */
        private fun formatTimestampWhatsApp(date: Date): String {
            val now = Calendar.getInstance()
            val messageTime = Calendar.getInstance().apply { time = date }
            
            val isToday = now.get(Calendar.DATE) == messageTime.get(Calendar.DATE) &&
                    now.get(Calendar.MONTH) == messageTime.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)
            
            val isYesterday = run {
                val yesterday = Calendar.getInstance().apply { add(Calendar.DATE, -1) }
                yesterday.get(Calendar.DATE) == messageTime.get(Calendar.DATE) &&
                        yesterday.get(Calendar.MONTH) == messageTime.get(Calendar.MONTH) &&
                        yesterday.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)
            }
            
            val isThisWeek = run {
                val weekAgo = Calendar.getInstance().apply { add(Calendar.DATE, -7) }
                messageTime.after(weekAgo) && !isToday && !isYesterday
            }
            
            return when {
                isToday -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
                isYesterday -> "Yesterday"
                isThisWeek -> SimpleDateFormat("EEEE", Locale.getDefault()).format(date)
                else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(date)
            }
        }
    }

    class ChatDiffCallback : DiffUtil.ItemCallback<Chat>() {
        override fun areItemsTheSame(oldItem: Chat, newItem: Chat): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Chat, newItem: Chat): Boolean {
            return oldItem == newItem
        }
    }
}
