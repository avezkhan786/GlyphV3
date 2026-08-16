package com.glyph.glyph_v3.ui.chatlist

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.Chat
import com.glyph.glyph_v3.ui.aiagent.AiAgentConstants
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

internal fun Context.resolveColor(attr: Int): Int {
    val typedValue = TypedValue()
    theme.resolveAttribute(attr, typedValue, true)
    return typedValue.data
}

// ─── View Type Constants ─────────────────────────────────────────────────────

internal const val VIEW_TYPE_ARCHIVED_BANNER = 0
internal const val VIEW_TYPE_AI_AGENT = 1
internal const val VIEW_TYPE_CHAT = 2
internal const val VIEW_TYPE_PLACEHOLDER = 3
internal const val VIEW_TYPE_EMPTY = 4

// ─── Payload Change Types ────────────────────────────────────────────────────
// These mirror the DiffUtil payloads used by the Compose implementation — each
// payload targets a single visual region so onBindViewHolder runs only the
// bind logic for the changed field, not the whole row.

internal sealed class ChatListPayload {
    object UnreadCount : ChatListPayload()
    object LastMessage : ChatListPayload()
    object Timestamp : ChatListPayload()
    object MessageStatus : ChatListPayload()
    object TypingState : ChatListPayload()
    object Presence : ChatListPayload()
    object GroupOnlineCount : ChatListPayload()
    object Draft : ChatListPayload()
    object Selection : ChatListPayload()
    object Avatar : ChatListPayload()
    object DisplayName : ChatListPayload()
    object StatusRing : ChatListPayload()
}

// ─── List Item Model ─────────────────────────────────────────────────────────

/**
 * Sealed list item model for the chat list RecyclerView.
 * Each variant carries exactly the data needed by its ViewHolder.
 */
internal sealed class ChatListScreenItem {

    /** The pinned "Glyph AI" entry at the top of the chat list. */
    data class AiAgent(
        val key: String = AiAgentConstants.AI_AGENT_CHAT_ID,
        override val stableKey: String = key
    ) : ChatListScreenItem()

    /** Loading shimmer placeholder shown while chats load. */
    data class Placeholder(
        val key: String,
        override val stableKey: String = key
    ) : ChatListScreenItem()

    /** Banner shown at the top of the archived chats screen. */
    data class ArchivedBanner(
        val key: String = "archived_banner",
        override val stableKey: String = key
    ) : ChatListScreenItem()

    /** Empty state shown when there are no chats. */
    data class Empty(
        val key: String = "empty_state",
        override val stableKey: String = key
    ) : ChatListScreenItem()

    /** A regular chat row. */
    data class Chat(
        val chat: com.glyph.glyph_v3.data.models.Chat,
        val displayName: String,
        val otherUserId: String,
        val statusRingState: ChatStatusRingState,
        val isSelected: Boolean,
        val isInSelectionMode: Boolean,
        val avatarUrl: String,
        val isGroupChat: Boolean,
        val avatarLocalPath: String?,
        val initialLetter: String,
        val avatarBgColor: Int,
        val isOfficial: Boolean,
        val avatarCacheKey: String,
        val avatarStateVersion: Long,
        override val stableKey: String = chat.id
    ) : ChatListScreenItem()

    /** Unique key used by ListAdapter for stable item identity. */
    abstract val stableKey: String
}

// ─── DiffUtil ───────────────────────────────────────────────────────────────

internal object ChatListDiffCallback : DiffUtil.ItemCallback<ChatListScreenItem>() {

    override fun areItemsTheSame(oldItem: ChatListScreenItem, newItem: ChatListScreenItem): Boolean {
        return when {
            oldItem is ChatListScreenItem.Chat && newItem is ChatListScreenItem.Chat ->
                oldItem.chat.id == newItem.chat.id
            oldItem is ChatListScreenItem.AiAgent && newItem is ChatListScreenItem.AiAgent ->
                oldItem.key == newItem.key
            oldItem is ChatListScreenItem.Placeholder && newItem is ChatListScreenItem.Placeholder ->
                oldItem.key == newItem.key
            oldItem is ChatListScreenItem.ArchivedBanner && newItem is ChatListScreenItem.ArchivedBanner ->
                oldItem.key == newItem.key
            oldItem is ChatListScreenItem.Empty && newItem is ChatListScreenItem.Empty ->
                oldItem.key == newItem.key
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: ChatListScreenItem, newItem: ChatListScreenItem): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: ChatListScreenItem, newItem: ChatListScreenItem): Any? {
        // Only Chat items support payloads — all other types do full rebind
        if (oldItem !is ChatListScreenItem.Chat || newItem !is ChatListScreenItem.Chat) return null

        val oldChat = oldItem.chat
        val newChat = newItem.chat
        val payloads = mutableListOf<ChatListPayload>()

        if (oldItem.isSelected != newItem.isSelected) {
            payloads.add(ChatListPayload.Selection)
        }
        // displayName, initialLetter, and avatarBgColor are all derived from
        // the contact resolution result — they change together. A dedicated
        // payload avoids a full bind() (which calls resetViewProperties() →
        // hides all views → visible flicker) when only the display name changes.
        if (oldItem.displayName != newItem.displayName) {
            payloads.add(ChatListPayload.DisplayName)
        }
        if (oldItem.statusRingState != newItem.statusRingState) {
            payloads.add(ChatListPayload.StatusRing)
        }
        if (oldChat.unreadCount != newChat.unreadCount) {
            payloads.add(ChatListPayload.UnreadCount)
        }
        if (oldChat.lastMessage != newChat.lastMessage) {
            payloads.add(ChatListPayload.LastMessage)
        }
        if (oldChat.lastMessageTimestamp != newChat.lastMessageTimestamp) {
            payloads.add(ChatListPayload.Timestamp)
        }
        if (oldChat.lastMessageStatus != newChat.lastMessageStatus ||
            oldChat.lastMessageSenderId != newChat.lastMessageSenderId) {
            payloads.add(ChatListPayload.MessageStatus)
        }
        if (oldChat.isOtherUserTyping != newChat.isOtherUserTyping ||
            oldChat.typingText != newChat.typingText) {
            payloads.add(ChatListPayload.TypingState)
        }
        if (oldChat.isOtherUserOnline != newChat.isOtherUserOnline ||
            oldChat.isOtherUserInChat != newChat.isOtherUserInChat) {
            payloads.add(ChatListPayload.Presence)
        }
        if (oldChat.groupOnlineCount != newChat.groupOnlineCount) {
            payloads.add(ChatListPayload.GroupOnlineCount)
        }
        if (newChat.draft != oldChat.draft) {
            payloads.add(ChatListPayload.Draft)
        }
        // Detect avatar changes by BOTH version AND localPath. The version alone
        // is insufficient: when peek() transitions from a null fallback (version=0,
        // localPath=null) to the in-memory state after observe() (version=0,
        // localPath="/path"), the version is unchanged but localPath changed.
        // Without checking localPath, getChangePayload would return null → full bind()
        // → resetViewProperties() → visible full-row flicker.
        if (oldItem.avatarStateVersion != newItem.avatarStateVersion ||
            oldItem.avatarLocalPath != newItem.avatarLocalPath) {
            payloads.add(ChatListPayload.Avatar)
        }

        return if (payloads.isEmpty()) null else payloads
    }
}

// ─── Adapter ─────────────────────────────────────────────────────────────────

/**
 * RecyclerView adapter for the chat list screen, hosted inside ChatListScreen
 * via AndroidView. Mirrors the Compose ChatRow / LazyColumn exactly but uses
 * RecyclerView's ViewHolder recycling and DiffUtil payloads for sub-millisecond
 * partial updates during fling.
 *
 * The adapter delegates animations (status ring, presence pulse, typing dots,
 * unread badge scale) to [ScrollSuspensionCoordinator] which mirrors Compose's
 * LocalListScrolling — when the list is flinging, infinite animations are paused
 * to free UI-thread budget for newly-bound rows.
 */
internal class ChatListScreenAdapter(
    private val currentUserId: String?,
    private val groupSenderNamesByUserId: Map<String, String>,
    private val blockedUserIds: Set<String>,
    private val onClick: (Chat) -> Unit,
    private val onLongClick: (Chat) -> Unit,
    private val onAvatarClick: (Chat, Rect) -> Unit,
    private val onAiAgentClick: () -> Unit,
    private val selectionBackgroundColor: Int,
    private val scrollSuspensionCoordinator: ScrollSuspensionCoordinator
) : ListAdapter<ChatListScreenItem, RecyclerView.ViewHolder>(ChatListDiffCallback) {

    // ── Synchronous list mode ────────────────────────────────────────────────────
    // ListAdapter.submitList() routes through AsyncListDiffer, which runs DiffUtil
    // on a background thread and posts results back to the main thread. That
    // background-thread hop + main-thread post adds a one-frame (~16ms) delay that
    // Compose's LazyColumn does not have — LazyColumn computes its diff synchronously
    // inside the composition, so items render on the very same frame the state
    // changes. This delay is what users perceive as "Glyph Official chat displays
    // after a slight delay" on cold start: the cache seed emits, recomposition
    // happens, submitList dispatches into AsyncListDiffer, one frame passes before
    // the diff result lands on the main thread.
    //
    // submitListSync() below calculates DiffUtil on the calling thread (main) and
    // dispatches results synchronously — matching Compose's behaviour with zero
    // extra frames. For a chat list (< ~100 items) the CPU cost of a synchronous
    // DiffUtil on the main thread is measured in microseconds, far below the 16ms
    // frame budget.
    private var mSyncList: List<ChatListScreenItem> = emptyList()
    private var useSyncMode = false

    /** Equivalent to [submitList] but computes the diff synchronously on the current thread. */
    fun submitListSync(newList: List<ChatListScreenItem>?) {
        val list = newList ?: emptyList()
        val oldList = if (useSyncMode) mSyncList else currentList
        if (list == oldList) return // same reference — nothing to do

        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList?.size ?: 0
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                ChatListDiffCallback.areItemsTheSame(oldList!![oldPos], list[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                ChatListDiffCallback.areContentsTheSame(oldList!![oldPos], list[newPos])
            override fun getChangePayload(oldPos: Int, newPos: Int): Any? =
                ChatListDiffCallback.getChangePayload(oldList!![oldPos], list[newPos])
        })

        // Update the internal list BEFORE dispatch so onBindViewHolder (invoked
        // synchronously by notifyItemRangeInserted etc.) sees the new data.
        // This mirrors AsyncListDiffer's behaviour, which sets mList before
        // dispatching results on the main thread.
        mSyncList = list
        useSyncMode = true
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemCount(): Int {
        return if (useSyncMode) mSyncList.size else super.getItemCount()
    }

    @Suppress("RedundantOverride")
    override fun getItem(position: Int): ChatListScreenItem {
        return if (useSyncMode) mSyncList[position] else super.getItem(position)
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChatListScreenItem.ArchivedBanner -> VIEW_TYPE_ARCHIVED_BANNER
            is ChatListScreenItem.AiAgent -> VIEW_TYPE_AI_AGENT
            is ChatListScreenItem.Chat -> VIEW_TYPE_CHAT
            is ChatListScreenItem.Placeholder -> VIEW_TYPE_PLACEHOLDER
            is ChatListScreenItem.Empty -> VIEW_TYPE_EMPTY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ARCHIVED_BANNER ->
                ArchivedBannerViewHolder(
                    inflater.inflate(R.layout.item_chat_list_screen_archived_banner, parent, false)
                )
            VIEW_TYPE_AI_AGENT ->
                AiAgentViewHolder(
                    inflater.inflate(R.layout.item_chat_list_screen_ai_agent, parent, false),
                    onAiAgentClick
                )
            VIEW_TYPE_PLACEHOLDER ->
                PlaceholderViewHolder(
                    inflater.inflate(R.layout.item_chat_list_screen_placeholder, parent, false)
                )
            VIEW_TYPE_EMPTY ->
                EmptyViewHolder(
                    inflater.inflate(R.layout.item_chat_list_screen_empty, parent, false)
                )
            else -> ChatRowViewHolder(
                inflater.inflate(R.layout.item_chat_list_screen, parent, false),
                scrollSuspensionCoordinator,
                selectionBackgroundColor,
                blockedUserIds,
                currentUserId,
                groupSenderNamesByUserId,
                onClick,
                onLongClick,
                onAvatarClick
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ChatRowViewHolder -> holder.bind(item as ChatListScreenItem.Chat)
            is AiAgentViewHolder -> holder.bind()
            // PlaceholderViewHolder shimmer is started in onViewAttachedToWindow
            is EmptyViewHolder -> holder.bind()
            is ArchivedBannerViewHolder -> holder.bind()
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder !is ChatRowViewHolder) {
            // Non-chat types always do full bind
            onBindViewHolder(holder, position)
            return
        }
        val item = getItem(position) as ChatListScreenItem.Chat

        if (payloads.isEmpty()) {
            holder.bind(item)
        } else {
            holder.bindPayloads(item, payloads.filterIsInstance<ChatListPayload>())
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is ChatRowViewHolder) {
            holder.registerScrollListener(scrollSuspensionCoordinator)
        } else if (holder is PlaceholderViewHolder) {
            holder.bind()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is ChatRowViewHolder) {
            holder.unregisterScrollListener()
        } else if (holder is PlaceholderViewHolder) {
            holder.unbind()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is PlaceholderViewHolder) {
            holder.unbind()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        scrollSuspensionCoordinator.attach(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scrollSuspensionCoordinator.detach()
    }
}

private const val TAG_KEY_AVATAR = 0x7E0A_0001

// ─── ViewHolder: Archived Banner ───────────────────────────────────────────

internal class ArchivedBannerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    fun bind() {
        // Static banner — no dynamic content
    }
}

// ─── ViewHolder: AI Agent Row ────────────────────────────────────────────────

internal class AiAgentViewHolder(
    itemView: View,
    private val onAiAgentClick: () -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val avatarImage: ImageView = itemView.findViewById(R.id.ivAvatar)
    private val agentName: TextView = itemView.findViewById(R.id.tvAgentName)
    private val agentDescription: TextView = itemView.findViewById(R.id.tvAgentDescription)
    private val timestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
    private val sparkleIcon: ImageView = itemView.findViewById(R.id.ivArrow)

    init {
        itemView.setOnClickListener { onAiAgentClick() }
    }

    fun bind() {
        agentName.text = AiAgentConstants.AI_AGENT_USERNAME
        agentDescription.text = AiAgentConstants.AI_AGENT_LAST_MESSAGE
    }
}

// ─── ViewHolder: Placeholder (Shimmer) ───────────────────────────────────────

internal class PlaceholderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val avatarPlaceholder: View = itemView.findViewById(R.id.vAvatarPlaceholder)
    private val usernamePlaceholder: View = itemView.findViewById(R.id.vUsernamePlaceholder)
    private val timestampPlaceholder: View = itemView.findViewById(R.id.vTimestampPlaceholder)
    private val messagePlaceholder: View = itemView.findViewById(R.id.vMessagePlaceholder)

    private val handler = Handler(Looper.getMainLooper())
    private val shimmerRunnable = object : Runnable {
        override fun run() {
            val fraction = (System.currentTimeMillis() % 1400) / 1400f
            val alpha = 0.13f + (0.07f - 0.13f) * fraction
            avatarPlaceholder.alpha = alpha.coerceIn(0.07f, 0.13f)
            usernamePlaceholder.alpha = alpha.coerceIn(0.07f, 0.13f)
            timestampPlaceholder.alpha = alpha.coerceIn(0.07f, 0.13f)
            messagePlaceholder.alpha = alpha.coerceIn(0.07f, 0.13f)
            handler.postDelayed(this, 30)
        }
    }

    fun bind() {
        handler.post(shimmerRunnable)
    }

    fun unbind() {
        handler.removeCallbacks(shimmerRunnable)
    }
}

// ─── ViewHolder: Empty State ─────────────────────────────────────────────────

internal class EmptyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    private val titleText: TextView = itemView.findViewById(R.id.tvEmptyTitle)
    private val subtitleText: TextView = itemView.findViewById(R.id.tvEmptySubtitle)

    fun bind() {
        titleText.setText(R.string.no_chats_yet)
        subtitleText.setText(R.string.start_new_conversation)
    }
}

// ─── ViewHolder: Chat Row ────────────────────────────────────────────────────

internal class ChatRowViewHolder(
    itemView: View,
    private val scrollSuspensionCoordinator: ScrollSuspensionCoordinator,
    private val selectionBackgroundColor: Int,
    private val blockedUserIds: Set<String>,
    private val currentUserId: String?,
    private val groupSenderNamesByUserId: Map<String, String>,
    private val onClick: (Chat) -> Unit,
    private val onLongClick: (Chat) -> Unit,
    private val onAvatarClick: (Chat, Rect) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    // ── View references ──
    private val avatarContainer: FrameLayout = itemView.findViewById(R.id.avatarContainer)
    private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAvatar)
    private val tvAvatarInitial: TextView = itemView.findViewById(R.id.tvAvatarInitial)
    private val vStatusRing: View = itemView.findViewById(R.id.vStatusRing)
    private val vOnlineIndicator: View = itemView.findViewById(R.id.vOnlineIndicator)
    private val tvGroupOnlineCount: TextView = itemView.findViewById(R.id.tvGroupOnlineCount)
    private val ivSelectionCheck: ImageView = itemView.findViewById(R.id.ivSelectionCheck)
    private val tvUsername: TextView = itemView.findViewById(R.id.tvUsername)
    private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
    private val ivMessageStatus: ImageView = itemView.findViewById(R.id.ivMessageStatus)
    private val tvDraftLabel: TextView = itemView.findViewById(R.id.tvDraftLabel)
    private val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
    private val llTypingIndicator: LinearLayout = itemView.findViewById(R.id.llTypingIndicator)
    private val tvTypingLabel: TextView = itemView.findViewById(R.id.tvTypingLabel)
    private val llTypingDots: LinearLayout = itemView.findViewById(R.id.llTypingDots)
    private val badgeUnread: TextView = itemView.findViewById(R.id.badgeUnread)
    private val divider: View = itemView.findViewById(R.id.divider)

    // ── Animation state (kept as fields, updated lazily) ──
    private val ringAnimator: ValueAnimator by lazy {
        ValueAnimator.ofFloat(0.92f, 1f).apply {
            duration = 220
            interpolator = OvershootInterpolator()
            addUpdateListener { vStatusRing.scaleX = it.animatedValue as Float; vStatusRing.scaleY = it.animatedValue as Float }
        }
    }
    private val presenceAnimator: ValueAnimator by lazy {
        ValueAnimator.ofFloat(1f, 1.2f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { v -> vOnlineIndicator.scaleX = v.animatedValue as Float; vOnlineIndicator.scaleY = v.animatedValue as Float }
        }
    }
    private val typingAnimator: ValueAnimator by lazy {
        ValueAnimator.ofInt(0, 2).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { updateTypingDots(it.animatedValue as Int) }
        }
    }

    // ── Cached formatters (mirrors Compose's hoisted SimpleDateFormat reuse) ──
    private var currentItem: ChatListScreenItem.Chat? = null
    private var avatarClickBounds: Rect? = null

    // ── Color cache (resolved from theme on first bind) ──
    private var unreadBadgeColor: Int = 0
    private var unreadBadgeTextColor: Int = 0
    private var onlineIndicatorColor: Int = 0
    private var actionPrimaryColor: Int = 0
    private var textPrimaryColor: Int = 0
    private var textSecondaryColor: Int = 0
    private var textTertiaryColor: Int = 0
    private var errorColor: Int = 0
    private var selectionBgColor: Int = 0
    private var colorsResolved = false

    private fun resolveThemeColors() {
        if (colorsResolved) return
        val ctx = itemView.context
        unreadBadgeColor = ctx.resolveColor(R.attr.glyphUnreadBadge)
        unreadBadgeTextColor = 0  // Resolved from drawable (bg_whatsapp_unread_badge)
        onlineIndicatorColor = ctx.resolveColor(R.attr.glyphOnlineIndicator)
        // Message status icon uses textSecondary (or glyphPrimary for READ) — resolved in bindMessageStatus
        actionPrimaryColor = ctx.resolveColor(R.attr.glyphPrimary)
        textPrimaryColor = ctx.resolveColor(R.attr.glyphTextPrimary)
        textSecondaryColor = ctx.resolveColor(R.attr.glyphTextSecondary)
        textTertiaryColor = ctx.resolveColor(R.attr.glyphTextTertiary)
        errorColor = ctx.resolveColor(R.attr.glyphError)
        selectionBgColor = this.selectionBackgroundColor
        colorsResolved = true
    }

    private fun resetViewProperties() {
        vStatusRing.visibility = View.GONE
        vOnlineIndicator.visibility = View.GONE
        tvGroupOnlineCount.visibility = View.GONE
        ivSelectionCheck.visibility = View.GONE
        ivMessageStatus.visibility = View.GONE
        tvDraftLabel.visibility = View.GONE
        llTypingIndicator.visibility = View.GONE
        badgeUnread.visibility = View.GONE
    }

    // ─── Full bind ───
    fun bind(item: ChatListScreenItem.Chat) {
        currentItem = item
        resolveThemeColors()
        resetViewProperties()

        // ── Selection background ──
        itemView.setBackgroundColor(
            if (item.isSelected && item.isInSelectionMode) selectionBgColor else 0
        )

        // ── Selection check ──
        if (item.isSelected) {
            ivSelectionCheck.visibility = View.VISIBLE
        }

        // ── Username ──
        tvUsername.text = item.displayName
        tvUsername.setTextColor(textPrimaryColor)

        // ── Timestamp ──
        val timestampText = item.chat.lastMessageTimestamp?.let { formatTimestampWhatsApp(it) }.orEmpty()
        if (timestampText.isNotEmpty()) {
            tvTimestamp.text = timestampText
            tvTimestamp.setTextColor(textTertiaryColor)
            tvTimestamp.visibility = View.VISIBLE
        } else {
            tvTimestamp.visibility = View.GONE
        }

        // ── Avatar ──
        bindAvatar(item)

        // ── Status ring (UNSEEN) ──
        if (!item.isGroupChat && item.statusRingState == ChatStatusRingState.UNSEEN) {
            vStatusRing.visibility = View.VISIBLE
            ringAnimator.start()
        }

        // ── Online indicator / group badge ──
        val chat = item.chat
        if (!item.isGroupChat) {
            if (chat.isOtherUserOnline || chat.isOtherUserInChat) {
                vOnlineIndicator.visibility = View.VISIBLE
                vOnlineIndicator.setBackgroundResource(R.drawable.bg_online_indicator)
                startPresencePulse()
            }
        } else if (chat.groupOnlineCount > 0) {
            tvGroupOnlineCount.visibility = View.VISIBLE
            tvGroupOnlineCount.text = if (chat.groupOnlineCount > 99) "99+" else chat.groupOnlineCount.toString()
        }

        // ── Last message / draft / typing ──
        val draftText = com.glyph.glyph_v3.data.service.DraftMessageStore.getDraft(chat.id).trim()
        val hasDraft = draftText.isNotEmpty()

        when {
            chat.isOtherUserTyping -> {
                bindTypingIndicator(chat.typingText)
            }
            hasDraft -> {
                bindDraft(draftText)
            }
            else -> {
                bindLastMessage(chat)
            }
        }

        // ── Unread badge ──
        if (chat.unreadCount > 0) {
            badgeUnread.visibility = View.VISIBLE
            badgeUnread.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
            badgeUnread.setBackgroundResource(R.drawable.bg_whatsapp_unread_badge)
            badgeUnread.setTextColor(0xFFFFFFFF.toInt()) // WhatsApp unread badge text is white
            animateUnreadBadge()
        }

        // ── Click listeners ──
        setClickListeners(item)
    }

    private fun bindAvatar(item: ChatListScreenItem.Chat) {
        val chat = item.chat

        if (item.isOfficial) {
            // Official chat — show Glyph brand avatar (launcher icon clipped to circle)
            ivAvatar.setImageResource(R.drawable.ic_brand_official)
            ivAvatar.setTag(TAG_KEY_AVATAR, null)
            ivAvatar.visibility = View.VISIBLE
            tvAvatarInitial.visibility = View.GONE
            vStatusRing.visibility = View.GONE // Official chats never show status ring
            tvGroupOnlineCount.visibility = View.GONE
            vOnlineIndicator.visibility = View.GONE
            // Scale the official avatar slightly to match Compose's LOGO_SCALE=1.45f
            ivAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
            return
        }

        val avatarBgColor = item.avatarBgColor
        tvAvatarInitial.setBackgroundColor(avatarBgColor)
        tvAvatarInitial.text = item.initialLetter
        tvAvatarInitial.setTextColor(0xFFFFFFFF.toInt())

        val localPath = item.avatarLocalPath
        val visibleUrl = item.avatarUrl

        if (localPath != null) {
            // Local file — load with tag-skip pattern (mirror legacy ChatListAdapter)
            val avatarKey = "L:$localPath"
            val prevKey = ivAvatar.getTag(TAG_KEY_AVATAR) as? String
            if (prevKey != avatarKey) {
                ivAvatar.setTag(TAG_KEY_AVATAR, avatarKey)
                ivAvatar.visibility = View.VISIBLE
                tvAvatarInitial.visibility = View.GONE
                Glide.with(itemView)
                    .load(File(localPath))
                    .circleCrop()
                    .override(46, 46)
                    .signature(ObjectKey(localPath))
                    .into(ivAvatar)
            }
        } else if (visibleUrl.isNotBlank()) {
            // Remote URL — load with tag-skip pattern
            val avatarKey = "U:$visibleUrl"
            val prevKey = ivAvatar.getTag(TAG_KEY_AVATAR) as? String
            if (prevKey != avatarKey) {
                ivAvatar.setTag(TAG_KEY_AVATAR, avatarKey)
                ivAvatar.visibility = View.VISIBLE
                tvAvatarInitial.visibility = View.GONE
                Glide.with(itemView)
                    .load(visibleUrl)
                    .circleCrop()
                    .override(46, 46)
                    .error(R.drawable.ic_default_avatar)
                    .into(ivAvatar)
            }
        } else {
            // No image — show initial letter
            ivAvatar.setTag(TAG_KEY_AVATAR, null)
            ivAvatar.setImageDrawable(null)
            ivAvatar.visibility = View.GONE
            tvAvatarInitial.visibility = View.VISIBLE
        }
    }

    private fun bindTypingIndicator(typingText: String) {
        llTypingIndicator.visibility = View.VISIBLE
        tvLastMessage.visibility = View.GONE
        tvDraftLabel.visibility = View.GONE
        ivMessageStatus.visibility = View.GONE

        val displayLabel = typingText.trim().ifBlank { "typing..." }.removeSuffix("...").trimEnd()
        tvTypingLabel.text = displayLabel
        tvTypingLabel.setTextColor(actionPrimaryColor)
        startTypingAnimation()
    }

    private fun bindDraft(draft: String) {
        llTypingIndicator.visibility = View.GONE
        tvLastMessage.visibility = View.VISIBLE
        tvDraftLabel.visibility = View.VISIBLE
        ivMessageStatus.visibility = View.GONE

        tvDraftLabel.text = "Draft: "
        tvDraftLabel.setTextColor(errorColor)
        tvDraftLabel.setTypeface(null, android.graphics.Typeface.BOLD)

        tvLastMessage.text = draft
        tvLastMessage.setTextColor(textSecondaryColor)
    }

    private fun bindLastMessage(chat: Chat) {
        llTypingIndicator.visibility = View.GONE
        tvDraftLabel.visibility = View.GONE
        tvLastMessage.visibility = View.VISIBLE

        val subtitle = buildChatListSubtitle(chat, currentUserId, groupSenderNamesByUserId)
        tvLastMessage.text = subtitle
        tvLastMessage.setTextColor(textSecondaryColor)

        // Message status icon — only shown for own messages
        val isOwnMessage = chat.lastMessageSenderId == currentUserId
        val isOwnReactionSummary = isOwnMessage && chat.lastMessage.startsWith("You reacted ")
        if (isOwnMessage && chat.lastMessage.isNotEmpty() && !isOwnReactionSummary) {
            val statusIconRes = when (chat.lastMessageStatus) {
                "SENDING" -> R.drawable.ic_clock
                "SENT" -> R.drawable.ic_check
                "DELIVERED" -> R.drawable.ic_double_check
                "READ" -> R.drawable.ic_double_check_blue
                "FAILED" -> R.drawable.ic_error_outline
                else -> R.drawable.ic_check
            }
            ivMessageStatus.setImageResource(statusIconRes)
            val statusTint = if (chat.lastMessageStatus == "READ") {
                itemView.context.resolveColor(R.attr.glyphPrimary)
            } else {
                textSecondaryColor
            }
            ivMessageStatus.setColorFilter(statusTint)
            ivMessageStatus.visibility = View.VISIBLE
        } else {
            ivMessageStatus.visibility = View.GONE
        }
    }

    private fun setClickListeners(item: ChatListScreenItem.Chat) {
        val chat = item.chat

        itemView.setOnClickListener {
            if (item.isInSelectionMode) {
                onLongClick(chat)
            } else {
                com.glyph.glyph_v3.util.ChatOpenTrace.start(
                    chatId = chat.id,
                    source = "chat_list_screen_tap",
                    details = "unread=${chat.unreadCount}"
                )
                onClick(chat)
            }
        }

        itemView.setOnLongClickListener {
            onLongClick(chat)
            true
        }

        avatarContainer.setOnClickListener {
            if (avatarClickBounds != null) {
                onAvatarClick(chat, avatarClickBounds!!)
            }
        }

        // Compute avatar bounds for click target
        avatarContainer.post {
            val loc = IntArray(2)
            avatarContainer.getLocationOnScreen(loc)
            avatarClickBounds = Rect(
                loc[0], loc[1],
                loc[0] + avatarContainer.width,
                loc[1] + avatarContainer.height
            )
        }
    }

    // ─── Payload binders (partial updates) ───
    fun bindPayloads(item: ChatListScreenItem.Chat, payloads: List<ChatListPayload>) {
        currentItem = item
        val ctx = itemView.context
        resolveThemeColors()

        for (payload in payloads) {
            when (payload) {
                is ChatListPayload.Selection -> bindSelection(item.isSelected)
                is ChatListPayload.UnreadCount -> bindUnreadCount(item.chat.unreadCount)
                is ChatListPayload.LastMessage -> bindLastMessageOrDraft(item)
                is ChatListPayload.Timestamp -> bindTimestamp(item.chat.lastMessageTimestamp)
                is ChatListPayload.MessageStatus -> bindMessageStatus(item)
                is ChatListPayload.TypingState -> bindTypingState(item)
                is ChatListPayload.Presence -> bindPresence(item)
                is ChatListPayload.GroupOnlineCount -> bindGroupOnlineCount(item.chat.groupOnlineCount)
                is ChatListPayload.Draft -> bindDraftOnly(item)
                is ChatListPayload.Avatar -> bindAvatar(item)
                is ChatListPayload.DisplayName -> bindDisplayName(item)
                is ChatListPayload.StatusRing -> bindStatusRing(item)
            }
        }
    }

    private fun bindSelection(isSelected: Boolean) {
        itemView.setBackgroundColor(
            if (isSelected) selectionBgColor else 0
        )
        ivSelectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
    }

    private fun bindUnreadCount(count: Int) {
        if (count > 0) {
            badgeUnread.visibility = View.VISIBLE
            badgeUnread.text = if (count > 99) "99+" else count.toString()
            animateUnreadBadge()
        } else {
            badgeUnread.visibility = View.GONE
        }
    }

    private fun bindLastMessageOrDraft(item: ChatListScreenItem.Chat) {
        val chat = item.chat
        val draftText = com.glyph.glyph_v3.data.service.DraftMessageStore.getDraft(chat.id).trim()
        val hasDraft = draftText.isNotEmpty()

        when {
            chat.isOtherUserTyping -> bindTypingIndicator(chat.typingText)
            hasDraft -> bindDraft(draftText)
            else -> {
                val subtitle = buildChatListSubtitle(chat, currentUserId, groupSenderNamesByUserId)
                tvLastMessage.text = subtitle
                tvLastMessage.setTextColor(textSecondaryColor)
                tvLastMessage.visibility = View.VISIBLE
                tvDraftLabel.visibility = View.GONE
                llTypingIndicator.visibility = View.GONE
                // Reset message status icon
                val isOwnMessage = chat.lastMessageSenderId == currentUserId
                if (isOwnMessage && chat.lastMessage.isNotEmpty()) {
                    bindMessageStatus(item)
                } else {
                    ivMessageStatus.visibility = View.GONE
                }
            }
        }
    }

    private fun bindTimestamp(timestamp: Date?) {
        val text = timestamp?.let { formatTimestampWhatsApp(it) }.orEmpty()
        if (text.isNotEmpty()) {
            tvTimestamp.text = text
            tvTimestamp.setTextColor(textTertiaryColor)
            tvTimestamp.visibility = View.VISIBLE
        } else {
            tvTimestamp.visibility = View.GONE
        }
    }

    private fun bindMessageStatus(item: ChatListScreenItem.Chat) {
        val chat = item.chat
        val isOwnMessage = chat.lastMessageSenderId == currentUserId
        if (!isOwnMessage || chat.lastMessage.isEmpty()) {
            ivMessageStatus.visibility = View.GONE
            return
        }
        val statusIconRes = when (chat.lastMessageStatus) {
            "SENDING" -> R.drawable.ic_clock
            "SENT" -> R.drawable.ic_check
            "DELIVERED" -> R.drawable.ic_double_check
            "READ" -> R.drawable.ic_double_check_blue
            "FAILED" -> R.drawable.ic_error_outline
            else -> R.drawable.ic_check
        }
        ivMessageStatus.setImageResource(statusIconRes)
        val statusTint = if (chat.lastMessageStatus == "READ") {
            itemView.context.resolveColor(R.attr.glyphPrimary)
        } else {
            textSecondaryColor
        }
        ivMessageStatus.setColorFilter(statusTint)
        ivMessageStatus.visibility = View.VISIBLE
    }

    private fun bindTypingState(item: ChatListScreenItem.Chat) {
        val chat = item.chat
        if (chat.isOtherUserTyping) {
            bindTypingIndicator(chat.typingText)
        } else {
            llTypingIndicator.visibility = View.GONE
            bindLastMessageOrDraft(item)
        }
    }

    private fun bindPresence(item: ChatListScreenItem.Chat) {
        val chat = item.chat
        stopPresencePulse()
        if (!item.isGroupChat && (chat.isOtherUserOnline || chat.isOtherUserInChat)) {
            vOnlineIndicator.visibility = View.VISIBLE
            startPresencePulse()
        } else {
            vOnlineIndicator.visibility = View.GONE
            stopPresencePulse()
        }
    }

    private fun bindGroupOnlineCount(count: Int) {
        if (count > 0) {
            tvGroupOnlineCount.visibility = View.VISIBLE
            tvGroupOnlineCount.text = if (count > 99) "99+" else count.toString()
        } else {
            tvGroupOnlineCount.visibility = View.GONE
        }
    }

    private fun bindDraftOnly(item: ChatListScreenItem.Chat) {
        val chat = item.chat
        val draftText = com.glyph.glyph_v3.data.service.DraftMessageStore.getDraft(chat.id).trim()
        if (draftText.isNotEmpty()) {
            bindDraft(draftText)
        }
    }

    // ─── Partial-bind helpers for fields that don't have their own payload yet ──

    private fun bindDisplayName(item: ChatListScreenItem.Chat) {
        tvUsername.text = item.displayName
        tvUsername.setTextColor(textPrimaryColor)
        tvAvatarInitial.setBackgroundColor(item.avatarBgColor)
        tvAvatarInitial.text = item.initialLetter
    }

    private fun bindStatusRing(item: ChatListScreenItem.Chat) {
        if (!item.isGroupChat && item.statusRingState == ChatStatusRingState.UNSEEN) {
            vStatusRing.visibility = View.VISIBLE
            ringAnimator.start()
        } else {
            vStatusRing.visibility = View.GONE
        }
    }

    // ─── Animations ───

    private fun animateUnreadBadge() {
        badgeUnread.scaleX = 0f
        badgeUnread.scaleY = 0f
        badgeUnread.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun startPresencePulse() {
        val isScrolling = scrollSuspensionCoordinator.isScrolling.get()
        if (isScrolling) {
            // Don't animate during scroll — freeze at rest
            vOnlineIndicator.scaleX = 1f
            vOnlineIndicator.scaleY = 1f
            return
        }
        if (!presenceAnimator.isRunning) {
            presenceAnimator.start()
        }
    }

    private fun stopPresencePulse() {
        presenceAnimator.cancel()
        vOnlineIndicator.scaleX = 1f
        vOnlineIndicator.scaleY = 1f
    }

    private fun startTypingAnimation() {
        val isScrolling = scrollSuspensionCoordinator.isScrolling.get()
        if (isScrolling) {
            // Show static dots while scrolling — no animation
            setTypingDotOffsets(0)
            return
        }
        if (!typingAnimator.isRunning) {
            typingAnimator.start()
        }
    }

    private fun setTypingDotOffsets(phase: Int) {
        for (i in 0 until llTypingDots.childCount) {
            val dot = llTypingDots.getChildAt(i) as TextView
            val dotPhase = (phase + i / 3f) % 1f
            val offsetY = if (dotPhase < 0.5f) -4f * (dotPhase / 0.5f) else -4f * ((1f - dotPhase) / 0.5f)
            dot.translationY = offsetY
        }
    }

    private fun updateTypingDots(phase: Int) {
        setTypingDotOffsets(phase)
    }

    // ─── Scroll suspension ───

    fun registerScrollListener(coordinator: ScrollSuspensionCoordinator) {
        coordinator.listeners.add(scrollSuspensionListener)
    }

    fun unregisterScrollListener() {
        scrollSuspensionCoordinator.listeners.remove(scrollSuspensionListener)
    }

    private val scrollSuspensionListener = object : ScrollSuspensionCoordinator.ScrollStateListener {
        override fun onScrollStateChanged(scrolling: Boolean) {
            if (scrolling) {
                // Pause all infinite animations on this row
                presenceAnimator.pause()
                typingAnimator.pause()
            } else {
                // Resume if the row should be active
                val item = currentItem ?: return
                val chat = item.chat
                if (!item.isGroupChat && (chat.isOtherUserOnline || chat.isOtherUserInChat)) {
                    presenceAnimator.resume()
                }
                if (chat.isOtherUserTyping) {
                    typingAnimator.resume()
                }
            }
        }
    }
}

// ─── Scroll Suspension Coordinator (mirrors Compose LocalListScrolling) ──────

/**
 * Mirrors Compose's [LocalListScrolling] — provides a boolean signal that
 * infinite animations (presence pulse, typing dots) read to suspend themselves
 * during fling, freeing UI-thread budget for newly-bound rows.
 */
internal class ScrollSuspensionCoordinator {

    /**
     * AtomicReference-like boolean: true while the RecyclerView is actively
     * scrolling/flinging. Read by ViewHolders to decide whether to run
     * infinite animations.
     */
    val isScrolling = AtomicBoolean(false)

    /** Listeners registered by attached ChatRowViewHolders. */
    val listeners = mutableListOf<ScrollStateListener>()

    private var recyclerView: RecyclerView? = null
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            val scrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING ||
                    newState == RecyclerView.SCROLL_STATE_SETTLING
            isScrolling.set(scrolling)
            listeners.forEach { it.onScrollStateChanged(scrolling) }
        }

        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            // While actively scrolling, ensure the flag stays true
            if (!isScrolling.get()) {
                isScrolling.set(true)
                listeners.forEach { it.onScrollStateChanged(true) }
            }
        }
    }

    fun attach(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
    }

    fun detach() {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = null
    }

    interface ScrollStateListener {
        fun onScrollStateChanged(scrolling: Boolean)
    }
}

// ─── Timestamp Formatting (mirrors Compose formatTimestampWhatsApp) ──────────

private const val TIMESTAMP_CACHE_CAPACITY = 256
private val timestampStringCache = HashMap<Long, String>(TIMESTAMP_CACHE_CAPACITY)
private val todayFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dowFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
private val shortDateFormatter = SimpleDateFormat("M/d/yy", Locale.getDefault())

internal fun formatTimestampWhatsApp(date: Date): String {
    val cached = timestampStringCache[date.time]
    if (cached != null) return cached

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

    val formatted = when {
        isToday -> todayFormatter.format(date)
        isYesterday -> "Yesterday"
        isThisWeek -> dowFormatter.format(date)
        else -> shortDateFormatter.format(date)
    }

    if (timestampStringCache.size >= TIMESTAMP_CACHE_CAPACITY) {
        val earliest = timestampStringCache.keys.iterator().next()
        timestampStringCache.remove(earliest)
    }
    timestampStringCache[date.time] = formatted
    return formatted
}

// ─── Letter Avatar Colors (mirrors Compose letterAvatarColors) ───────────────

internal val letterAvatarColors: List<Int> = listOf(
    0xFF25D366.toInt(),
    0xFF128C7E.toInt(),
    0xFF075E54.toInt(),
    0xFF34B7F1.toInt(),
    0xFF00A884.toInt(),
    0xFFD4AC0D.toInt(),
    0xFFE74C3C.toInt(),
    0xFF9B59B6.toInt(),
    0xFF3498DB.toInt(),
    0xFFE67E22.toInt()
)

// ─── Compose functions reused in the adapter (pure text logic) ────────────────

// NOTE: buildChatListSubtitle is defined in ChatListScreen.kt and marked internal.
// Since this is in the same package, it's accessible directly.
// No additional import needed.