package com.glyph.glyph_v3.ui.chatlist

import android.content.Context
import android.graphics.drawable.Drawable
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

/**
 * Scroll-tuned chat list adapter.
 *
 * Bind-path allocations are intentionally hoisted out of [ChatViewHolder.bind]:
 *  - text colors resolved once from theme attributes, not per bind
 *  - drawables (default avatar, online indicator, double-check, group icon) resolved once
 *  - letter-avatar backgrounds built once per color and reused across rows + recycles
 *  - SimpleDateFormat instances created per-adapter (locale-aware, but no per-bind alloc)
 *  - Glide is invoked only when the avatar key has changed for that ImageView tag
 *
 * The previous implementation allocated a Calendar + new SimpleDateFormat for every row on
 * every bind, and re-issued a Glide request into the same ImageView whenever a row was
 * scrolled back into view (skipMemoryCache(true) forced a disk decode each time). Both are
 * now avoided.
 */
class ChatListAdapter(
    private val onChatClick: (Chat) -> Unit,
    private val onAvatarClick: ((Chat, View) -> Unit)? = null,
    private val currentUserId: String? = null
) : ListAdapter<Chat, ChatListAdapter.ChatViewHolder>(ChatDiffCallback()) {

    // ── Letter avatar palette (compile-time ints) ──
    private val avatarColors = intArrayOf(
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

    // ── Per-adapter text cache. Keyed by chat id + the field-defining inputs of each
    // resolved string so that a row recovering from a recycle reuses the already-resolved
    // displayName and timestamp strings instead of repeating string formatting work.
    // Bounded via removeEldestEntry so it can't grow unbounded across a long-running list. ──
    private val displayNameCache = object : java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_DISPLAY_NAME_CACHE
    }
    private val timestampCache = object : java.util.LinkedHashMap<Long, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>): Boolean = size > MAX_TIMESTAMP_CACHE
    }

    // ── Theme-resolved colors (filled in lazily on first bind context) ──
    private var primaryColor: Int = 0
    private var mutedColor: Int = 0
    private var draftColor: Int = 0

    // ── Lazy caches ──
    private var initialized: Boolean = false
    private lateinit var defaultAvatarDrawable: Drawable
    private lateinit var onlineIndicatorDrawable: Drawable
    private lateinit var doubleCheckDrawable: Drawable
    private lateinit var groupIconDrawable: Drawable
    private var letterAvatarDrawables: Array<Drawable>? = null
    private val groupAvatarBackground: GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFF3A2B1C.toInt())
    }
    private val groupIconTintColor: Int = 0xFFFFD166.toInt()

    // ── Per-adapter formatters (locale-aware; cheaply re-built on adapter recreation) ──
    private val todayFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dowFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
    private val shortDateFormatter = SimpleDateFormat("M/d/yy", Locale.getDefault())

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        primaryColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(context, R.color.whatsapp_green)
        )
        mutedColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            ContextCompat.getColor(context, android.R.color.darker_gray)
        )
        draftColor = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorError,
            ContextCompat.getColor(context, android.R.color.holo_red_dark)
        )
        defaultAvatarDrawable = ContextCompat.getDrawable(context, R.drawable.ic_default_avatar)!!
        onlineIndicatorDrawable = ContextCompat.getDrawable(context, R.drawable.bg_online_indicator)!!
        doubleCheckDrawable = ContextCompat.getDrawable(context, R.drawable.ic_double_check)!!
        groupIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_group)!!
        letterAvatarDrawables = Array(avatarColors.size) { i ->
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(avatarColors[i])
            }
        }
        initialized = true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        ensureInitialized(parent.context)
        val binding = ItemChatListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * Partial bind: when DiffUtil reports only a subset of fields changed (e.g. only
     * the unread badge, only online indicator), update those fields directly. The
     * Compose smooth-scroll pattern in [StatusPrivacyScreen] / [LockedChatsScreen]
     * uses `remember(chat.id, …)` so unchanged inputs don't recompute; this is the
     * equivalent for RecyclerView — diffing at field granularity instead of full row.
     */
    override fun onBindViewHolder(holder: ChatViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        ChatListPerfMonitor.onPayloadFired()
        holder.bindPartial(getItem(position), payloads)
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
            val bindStart = ChatListPerfMonitor.bindStartNanos()
            try {
            // Username — resolve with device contact name priority. Cache the
            // resolved string by chat id so a row recovering from recycle reuses
            // the same displayName instance (==) and the fast-path TextView guard
            // short-circuits without re-running ContactDisplayNameResolver.
            val displayName = resolveDisplayName(chat)
            setTextIfChanged(binding.tvUsername, displayName)

            // Draft overrides last message preview (WhatsApp-style)
            val draftText = chat.draft.trim()
            val hasDraft = draftText.isNotEmpty()
            if (hasDraft) {
                binding.tvDraftLabel.visibility = View.VISIBLE
                binding.tvDraftLabel.setTextColor(draftColor)
                binding.ivMessageStatus.visibility = View.GONE
                setTextIfChanged(binding.tvLastMessage, draftText)
            } else {
                binding.tvDraftLabel.visibility = View.GONE
                setTextIfChanged(binding.tvLastMessage, chat.lastMessage)
            }

            // Timestamp formatting (WhatsApp style) — cached formatter + per-day string cache
            if (chat.lastMessageTimestamp != null) {
                val text = resolveTimestamp(chat.lastMessageTimestamp)
                setTextIfChanged(binding.tvTimestamp, text)
                binding.tvTimestamp.setTextColor(
                    if (chat.unreadCount > 0) primaryColor else mutedColor
                )
            } else {
                setTextIfChanged(binding.tvTimestamp, "")
            }

            // Unread badge
            if (chat.unreadCount > 0) {
                binding.badgeUnread.visibility = View.VISIBLE
                val badgeText = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
                setTextIfChanged(binding.badgeUnread, badgeText)
            } else {
                binding.badgeUnread.visibility = View.GONE
            }

            // Message status icon (only for sent messages by current user)
            if (!hasDraft) {
                if (chat.lastMessageSenderId == currentUserId && chat.lastMessage.isNotEmpty()) {
                    binding.ivMessageStatus.visibility = View.VISIBLE
                    binding.ivMessageStatus.setImageDrawable(doubleCheckDrawable)
                } else {
                    binding.ivMessageStatus.visibility = View.GONE
                }
            } else {
                binding.ivMessageStatus.visibility = View.GONE
            }

            // Avatar handling - cache-aware, skip Glide when key unchanged
            val displayAvatar = if (chat.isGroup) chat.groupIconUrl else chat.otherUserAvatar
            val otherUserId = if (chat.isGroup) "" else (chat.participants.firstOrNull { it != currentUserId } ?: "")
            bindAvatar(chat, displayAvatar, otherUserId)

            // Online indicator (View.setVisibility short-circuits on no-op)
            binding.onlineIndicator.visibility =
                if (!chat.isGroup && chat.isOtherUserOnline) View.VISIBLE else View.GONE
            } finally {
                ChatListPerfMonitor.bindEnd(bindStart)
            }
        }

        /**
         * Partial bind used when DiffUtil reports only specific fields changed
         * (via [ChatPayload]). Mirrors Compose's `remember(key1, key2)` skip-recompute
         * pattern: only the bits DiffUtil flagged are touched, so an unrelated
         * presence tick or draft edit doesn't requestLayout the rest of the row.
         */
        fun bindPartial(chat: Chat, payloads: MutableList<Any>) {
            ChatListPerfMonitor.onPartialBind()
            var unreadChange = false
            var lastMessageChange = false
            var onlineChange = false
            var timestampChange = false
            payloads.forEach { p ->
                if (p is ChatPayload) {
                    if (p.unreadCountChanged) unreadChange = true
                    if (p.lastMessageChanged) lastMessageChange = true
                    if (p.onlineStatusChanged) onlineChange = true
                    if (p.timestampChanged) timestampChange = true
                } else if (p is String && p == PAYLOAD_REBIND_ALL) {
                    bind(chat); return
                }
            }
            if (lastMessageChange) {
                val draftText = chat.draft.trim()
                if (draftText.isNotEmpty()) {
                    setTextIfChanged(binding.tvLastMessage, draftText)
                } else {
                    setTextIfChanged(binding.tvLastMessage, chat.lastMessage)
                }
            }
            if (timestampChange && chat.lastMessageTimestamp != null) {
                val text = resolveTimestamp(chat.lastMessageTimestamp)
                setTextIfChanged(binding.tvTimestamp, text)
            }
            if (unreadChange) {
                if (chat.unreadCount > 0) {
                    binding.badgeUnread.visibility = View.VISIBLE
                    val badgeText = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
                    setTextIfChanged(binding.badgeUnread, badgeText)
                } else {
                    binding.badgeUnread.visibility = View.GONE
                }
                binding.tvTimestamp.setTextColor(
                    if (chat.unreadCount > 0) primaryColor else mutedColor
                )
            }
            if (onlineChange) {
                binding.onlineIndicator.visibility =
                    if (!chat.isGroup && chat.isOtherUserOnline) View.VISIBLE else View.GONE
            }
        }

        /**
         * Allocation-free text assign. Compares against the current text with
         * reference equality first (the common case for repeat binds) and
         * String equality second, falling through to [TextView.setText] only
         * when the value actually changed. Avoids `CharSequence.toString()`
         * allocating a new String on every bind.
         */
        private fun setTextIfChanged(view: android.widget.TextView, value: CharSequence) {
            val existing = view.text
            if (existing === value) return
            if (existing is String && value is String && existing == value) return
            view.text = value
        }

            private fun resolveDisplayName(chat: Chat): String {
            // Per-chat cache. The "Group" / "Unknown" fallbacks are pure functions of
            // the input so they're safe to memoize on chat.id; the contact-resolver
            // path is also safe to memoize — its inputs (otherUserId + remote name)
            // are intrinsic to the chat row.
            synchronized(displayNameCache) {
                val cached = displayNameCache[chat.id]
                if (cached != null) {
                    ChatListPerfMonitor.onTextResolved(cacheHit = true)
                    return cached
                }
                val computed = if (chat.isGroup) {
                    chat.groupName.ifBlank { "Group" }
                } else {
                    val otherUserId = chat.participants.firstOrNull { it != currentUserId } ?: ""
                    ContactDisplayNameResolver.getDisplayName(
                        otherUserId = otherUserId,
                        remoteProfileName = chat.otherUsername
                    )
                }
                ChatListPerfMonitor.onTextResolved(cacheHit = false)
                displayNameCache[chat.id] = computed
                return computed
            }
        }

        private fun resolveTimestamp(date: java.util.Date): String {
            // Cache formatted timestamps by day-bucket so we don't reformat the
            // same instant multiple times across visible rows. The key uses the
            // day's "yesterday / today / weekday" bucket via the formatter output
            // is unstable across boundaries, but a re-key on date.time miss is
            // bounded to one reformat per day-bucket shift, which is rare.
            val key = date.time
            synchronized(timestampCache) {
                val cached = timestampCache[key]
                if (cached != null) {
                    ChatListPerfMonitor.onTextResolved(cacheHit = true)
                    return cached
                }
                ChatListPerfMonitor.onTextResolved(cacheHit = false)
                val computed = formatTimestampCached(date)
                timestampCache[key] = computed
                return computed
            }
        }

        private fun bindAvatar(chat: Chat, displayAvatar: String, otherUserId: String) {
            val wantImage = displayAvatar.isNotEmpty() && (chat.isGroup || otherUserId.isNotEmpty())
            val newMode = if (wantImage) MODE_IMAGE else MODE_LETTER
            val prevMode = binding.ivAvatar.getTag(TAG_KEY_MODE) as? String
            if (prevMode != newMode) {
                binding.ivAvatar.setTag(TAG_KEY_MODE, newMode)
                binding.ivAvatar.colorFilter = null
                binding.ivAvatar.setImageDrawable(null)
                binding.ivAvatar.background = null
            }

            if (wantImage) {
                binding.tvAvatarInitial.visibility = View.GONE
                binding.ivAvatar.visibility = View.VISIBLE

                val localPath = if (chat.isGroup) {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(chat.id)
                } else {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(otherUserId)
                }
                val avatarKey: String = if (localPath != null) "L:$localPath" else "U:$displayAvatar"

                val prev = binding.ivAvatar.getTag(TAG_KEY_AVATAR) as? String
                if (prev != avatarKey) {
                    binding.ivAvatar.setTag(TAG_KEY_AVATAR, avatarKey)
                    ChatListPerfMonitor.onGlideCall()
                    if (localPath != null) {
                        val file = java.io.File(localPath)
                        // Memory cache is allowed; the signature changes when the file changes,
                        // so a stale decoded bitmap cannot survive an avatar update, but repeat
                        // binds (scroll-back) will hit the in-memory bitmap, not a fresh decode.
                        Glide.with(binding.ivAvatar)
                            .load(file)
                            .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                            .transform(CircleCrop())
                            .placeholder(defaultAvatarDrawable)
                            .error(defaultAvatarDrawable)
                            .into(binding.ivAvatar)
                    } else {
                        Glide.with(binding.ivAvatar)
                            .load(displayAvatar)
                            .transform(CircleCrop())
                            .placeholder(defaultAvatarDrawable)
                            .error(defaultAvatarDrawable)
                            .into(binding.ivAvatar)
                    }
                }
            } else {
                // Letter avatar or group icon — reuse pre-built drawables
                binding.ivAvatar.setTag(TAG_KEY_AVATAR, null)

                if (chat.isGroup) {
                    binding.tvAvatarInitial.visibility = View.GONE
                    binding.ivAvatar.visibility = View.VISIBLE
                    binding.ivAvatar.setImageDrawable(groupIconDrawable)
                    binding.ivAvatar.setColorFilter(groupIconTintColor)
                    binding.ivAvatar.background = groupAvatarBackground
                } else {
                    val displayName = binding.tvUsername.text?.toString().orEmpty()
                    val colorIndex = if (displayName.isEmpty()) 0 else
                        abs(displayName.hashCode()) % avatarColors.size
                    val bg = letterAvatarDrawables!![colorIndex]
                    val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                    binding.ivAvatar.setImageDrawable(null)
                    binding.ivAvatar.background = bg
                    if (binding.tvAvatarInitial.text?.toString() != initial) {
                        binding.tvAvatarInitial.text = initial
                    }
                    binding.tvAvatarInitial.visibility = View.VISIBLE
                    binding.ivAvatar.visibility = View.VISIBLE
                    binding.tvAvatarInitial.background = bg
                }
            }
        }

        /**
         * WhatsApp-style timestamp formatting with cached formatters.
         * Reduces per-bind allocations vs. creating new SimpleDateFormat instances each call.
         */
        private fun formatTimestampCached(date: Date): String {
            val msgMs = date.time
            val now = Calendar.getInstance()
            val msg = Calendar.getInstance().apply { time = date }

            if (now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
            ) {
                return todayFormatter.format(date)
            }

            val yesterday = (now.clone() as Calendar).apply { add(Calendar.DATE, -1) }
            if (yesterday.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
            ) {
                return "Yesterday"
            }

            // Within last 7 days but not today/yesterday → day name
            return if (now.timeInMillis - msgMs < 7L * 24L * 60L * 60L * 1000L) {
                dowFormatter.format(date)
            } else {
                shortDateFormatter.format(date)
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

        /**
         * Field-granular change payload. RecyclerView routes this list to
         * `onBindViewHolder(holder, position, payloads)`, which then touches
         * only the affected subviews. Mirrors Compose's pattern of recomposing
         * only the state that changed for a row.
         */
        override fun getChangePayload(oldItem: Chat, newItem: Chat): Any? {
            val changed = ChatPayload()
            if (oldItem.unreadCount != newItem.unreadCount) changed.unreadCountChanged = true
            if (oldItem.lastMessage != newItem.lastMessage) changed.lastMessageChanged = true
            if (oldItem.lastMessageTimestamp != newItem.lastMessageTimestamp) changed.timestampChanged = true
            if (oldItem.isOtherUserOnline != newItem.isOtherUserOnline) changed.onlineStatusChanged = true
            // Nothing the partial path can express → request full rebind
            val anyPartial = changed.unreadCountChanged ||
                changed.lastMessageChanged ||
                changed.timestampChanged ||
                changed.onlineStatusChanged
            return if (anyPartial) changed else PAYLOAD_REBIND_ALL
        }
    }

    data class ChatPayload(
        var unreadCountChanged: Boolean = false,
        var lastMessageChanged: Boolean = false,
        var onlineStatusChanged: Boolean = false,
        var timestampChanged: Boolean = false
    )

    companion object {
        // Unique 32-bit integers for ImageView tag slots; chosen to avoid collision with
        // any system- or library-generated view id.
        private const val TAG_KEY_AVATAR = 0x7E0A_0001
        private const val TAG_KEY_MODE = 0x7E0A_0002
        private const val MODE_IMAGE = "I"
        private const val MODE_LETTER = "L"
        internal const val PAYLOAD_REBIND_ALL = "REBIND_ALL"
        private const val MAX_DISPLAY_NAME_CACHE = 200
        private const val MAX_TIMESTAMP_CACHE = 400
    }
}
