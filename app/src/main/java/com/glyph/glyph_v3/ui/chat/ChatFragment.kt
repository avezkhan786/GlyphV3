package com.glyph.glyph_v3.ui.chat

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.databinding.FragmentChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var chatAdapter: ChatAdapter
    private lateinit var repository: RealtimeMessageRepository
    private val firebaseRepository = FirebaseRepository()
    private val selectionManager = SelectionManager()
    private var chatId: String? = null
    private var otherUserId: String? = null
    private var otherUsername: String = ""
    private var otherUserAvatar: String = ""

    // ── Cached computation state ──────────────────────────────────────────────
    // Avoids re-allocating the date formatter, ZoneId, and List on every emission.
    private val zoneId: ZoneId by lazy(LazyThreadSafetyMode.NONE) { ZoneId.systemDefault() }
    private val dateFormatter: SimpleDateFormat by lazy(LazyThreadSafetyMode.NONE) {
        SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
    }
    // Track last known message set to skip redundant processMessagesWithHeaders
    private var lastKnownProcessedMessageIds: Set<String> = emptySet()
    private var cachedHeaderProcessedItems: List<ChatListItem> = emptyList()

    companion object {
        private const val ARG_CHAT_ID = "chat_id"
        private const val ARG_OTHER_USER_ID = "other_user_id"
        private const val ARG_OTHER_USERNAME = "other_username"
        private const val ARG_OTHER_USER_AVATAR = "other_user_avatar"

        // ── RecyclerView tuning constants ──────────────────────────────────
        // Off-screen view cache: small enough that pool refill is immediate
        // during flings, large enough to cover tiny scroll reversals.
        private const val OFFSCREEN_VIEW_CACHE_SIZE = 4
        // Prefetch items ahead of the scroll direction to avoid pool misses.
        private const val INITIAL_PREFETCH_ITEM_COUNT = 10
        // Pre-inflate only the most critical types before first frame (4 text bubbles).
        // The full warm (STAGE1-3) runs asynchronously after the first frame.
        private val CRITICAL_WARM_TYPES = intArrayOf(1, 2, 1, 2)

        fun newInstance(chatId: String, otherUserId: String, otherUsername: String = "", otherUserAvatar: String = ""): ChatFragment {
            val fragment = ChatFragment()
            val args = Bundle()
            args.putString(ARG_CHAT_ID, chatId)
            args.putString(ARG_OTHER_USER_ID, otherUserId)
            args.putString(ARG_OTHER_USERNAME, otherUsername)
            args.putString(ARG_OTHER_USER_AVATAR, otherUserAvatar)
            fragment.arguments = args
            return fragment
        }

        // Backward-compatible factory
        fun newInstance(chatId: String): ChatFragment {
            return newInstance(chatId, "", "", "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            chatId = it.getString(ARG_CHAT_ID)
            otherUserId = it.getString(ARG_OTHER_USER_ID)
            otherUsername = it.getString(ARG_OTHER_USERNAME) ?: ""
            otherUserAvatar = it.getString(ARG_OTHER_USER_AVATAR) ?: ""
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize repository
        val db = AppDatabase.getDatabase(requireContext())
        repository = RealtimeMessageRepository(db.messageDao(), db.chatDao(), db.deletedMessageDao(), requireContext())

        setupHeader()
        setupRecyclerView()

        // If we don't have user info, fetch it
        if (otherUsername.isEmpty() && !otherUserId.isNullOrEmpty()) {
            fetchOtherUserInfo()
        } else {
            updateHeaderInfo()
            ensureLocalChatExists()
        }

        loadMessages()

        binding.btnSend.setOnClickListener {
            sendMessage()
        }
    }

    private fun setupHeader() {
        // Back button
        binding.btnBack.setOnClickListener {
            activity?.findViewById<View>(R.id.bottom_navigation)?.visibility = View.VISIBLE
            parentFragmentManager.popBackStack()
        }

        // Header action buttons
        binding.btnVideoCall.setOnClickListener {
            initiateCall(com.glyph.glyph_v3.data.models.CallType.VIDEO)
        }

        binding.btnVoiceCall.setOnClickListener {
            initiateCall(com.glyph.glyph_v3.data.models.CallType.VOICE)
        }

        binding.btnLightning.setOnClickListener {
            Toast.makeText(requireContext(), "Quick actions coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnMenu.setOnClickListener {
            Toast.makeText(requireContext(), "Menu coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHeaderInfo() {
        if (!isAdded) return

        // Set user name
        binding.tvUserName.text = otherUsername.ifEmpty { "Unknown" }

        // Set last seen status (placeholder for now)
        binding.tvLastSeen.text = "online"

        // Load profile picture - try local cache first for instant display
        if (otherUserAvatar.isNotEmpty() && !otherUserId.isNullOrEmpty()) {
            val localAvatarPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(otherUserId!!)

            if (localAvatarPath != null) {
                // Load from local storage - INSTANT
                // Use signature() with file timestamp to force Glide to reload when file changes
                val file = java.io.File(localAvatarPath)
                Glide.with(this)
                    .load(file)
                    .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                    .skipMemoryCache(true)  // Force re-decode from file
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .into(binding.ivProfilePicture)
            } else {
                // Fallback to URL (first time load)
                Glide.with(this)
                    .load(otherUserAvatar)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_default_avatar)
                    .error(R.drawable.ic_default_avatar)
                    .into(binding.ivProfilePicture)

                // Cache in background for next time
                viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheAvatar(
                        otherUserId!!,
                        otherUserAvatar,
                        requireContext()
                    )
                }
            }
        } else {
            binding.ivProfilePicture.setImageResource(R.drawable.ic_default_avatar)
        }
    }

    private fun fetchOtherUserInfo() {
        val otherId = otherUserId ?: return
        firebaseRepository.getUser(otherId) { user ->
            if (user != null) {
                otherUsername = user.username
                otherUserAvatar = user.profileImageUrl
                // Update header with fetched info
                activity?.runOnUiThread {
                    updateHeaderInfo()
                }
            }
            ensureLocalChatExists()
        }
    }

    private fun ensureLocalChatExists() {
        val id = chatId ?: return
        val otherId = otherUserId ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            repository.getOrCreateLocalChat(id, otherId, otherUsername.ifEmpty { "Unknown" }, otherUserAvatar)
            repository.clearUnreadCount(id)
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(selectionManager).apply {
            setHasStableIds(true)
        }

        binding.recyclerViewMessages.apply {
            // ── Core RecyclerView optimizations ──────────────────────────────
            // Fixed size: RecyclerView can skip measuring its own dimensions when content changes
            setHasFixedSize(true)

            // No item animator: avoid costly add/remove/move animations during chat updates
            itemAnimator = null

            // Small off-screen bound-view cache. During fast flings, positions rarely repeat,
            // so a small cache (4 views) covers tiny scroll reversals without delaying the
            // return of views to the shared RecycledViewPool for true reuse at the leading edge.
            // 4 is the Telegram/WhatsApp sweet spot — enough for jitter, small enough that
            // the pool refills immediately.
            setItemViewCacheSize(OFFSCREEN_VIEW_CACHE_SIZE)

            // Disable expensive nested scrolling path — chat list doesn't need it
            isNestedScrollingEnabled = false

            // Only show overscroll effect when content actually exceeds viewport
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            // ── LayoutManager with prefetch ──────────────────────────────────
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
                // Prefetch views ahead of scroll direction to avoid pool misses during fling
                isItemPrefetchEnabled = true
                // Number of items to prefetch when the adapter is first set
                initialPrefetchItemCount = INITIAL_PREFETCH_ITEM_COUNT
            }

            // ── RecycledViewPool configuration ───────────────────────────────
            // Raised per-type caps so fast flings recycle existing bubbles instead of
            // inflating new ones mid-frame. Text bubbles dominate; media/collage/docs
            // get proportionally sized caps.
            recycledViewPool.apply {
                setMaxRecycledViews(1, 30)   // incoming text
                setMaxRecycledViews(2, 30)   // outgoing text
                setMaxRecycledViews(3, 16)   // incoming image/media
                setMaxRecycledViews(4, 16)   // outgoing image/media
                setMaxRecycledViews(7, 6)    // incoming audio
                setMaxRecycledViews(8, 6)    // outgoing audio
                setMaxRecycledViews(9, 8)    // incoming collage/media group
                setMaxRecycledViews(10, 8)   // outgoing collage/media group
                setMaxRecycledViews(11, 4)   // incoming contact
                setMaxRecycledViews(12, 4)   // outgoing contact
                setMaxRecycledViews(13, 6)   // date headers
                setMaxRecycledViews(17, 4)   // incoming video note
                setMaxRecycledViews(18, 4)   // outgoing video note
                setMaxRecycledViews(19, 6)   // incoming document
                setMaxRecycledViews(20, 6)   // outgoing document
            }

            adapter = chatAdapter

            // ── Seed critical ViewHolder types synchronously ───────────────────
            // Only 4 text bubbles (2 incoming + 2 outgoing) — cheap enough to run
            // before the first frame (~30ms total). The full pool warm continues
            // asynchronously after the first layout pass.
            post {
                if (!isAdded) return@post
                preInflateCriticalViewHolders()
                // Defer full pool warm to after the first frame is drawn
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main.immediate) {
                    warmRecycledViewPoolAsync()
                }
            }
        }
    }

    /**
     * Synchronously seed the RecycledViewPool with 4 critical text ViewHolders.
     * Cheap (~30ms for 4 inflations) — covers the first handful of bubbles so the
     * very first fling never pays XML inflation cost mid-frame.
     */
    private fun preInflateCriticalViewHolders() {
        if (!isAdded) return
        val pool = binding.recyclerViewMessages.recycledViewPool
        val rv = binding.recyclerViewMessages
        for (viewType in CRITICAL_WARM_TYPES) {
            if (!isAdded) return
            try {
                pool.putRecycledView(chatAdapter.createViewHolder(rv, viewType))
            } catch (_: Exception) {
                // Non-critical — pool seeding is best-effort
            }
        }
    }

    /**
     * Asynchronously pre-inflate common ViewHolder types into the recycled-view pool.
     * Text bubbles (incoming + outgoing) dominate most chats, so we seed 10 of each.
     * Runs on the main thread but yields between inflations to let frames render.
     *
     * This replaces the old synchronous 20-VH inflation that blocked the first frame
     * for 200-300ms. With yield(), frames can interleave, keeping the animation
     * responsive.
     */
    private suspend fun warmRecycledViewPoolAsync() {
        val rv = binding.recyclerViewMessages
        val pool = rv.recycledViewPool
        // View types for the most common bubble types: 1=incoming text, 2=outgoing text
        val warmTypes = intArrayOf(1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1, 2)
        for (viewType in warmTypes) {
            if (!isAdded) return
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                pool.putRecycledView(chatAdapter.createViewHolder(rv, viewType))
            } catch (_: Exception) {
                // Non-critical — pool warming is best-effort
            }
            // Yield to let frames render between inflations
            yield()
        }
    }

    private fun loadMessages() {
        val id = chatId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getMessages(id).collect { messages ->
                val listItems = processMessagesWithHeaders(messages)

                // ── Text layout precomputation on background thread ─────────
                // Telegram's MessageObject precomputes StaticLayout heights before
                // messages reach the adapter. We replicate this via TextLayoutPrecomputer.
                // Without it, every TextView.setText() during bind triggers an internal
                // StaticLayout creation costing 5-15ms per bubble — the #1 cause of
                // first-open stutter and frame drops during fast scrolling.
                val precomputedItems = if (TextLayoutPrecomputer.isReady()) {
                    withContext(Dispatchers.Default) {
                        TextLayoutPrecomputer.precomputeForItems(listItems)
                    }
                } else {
                    listItems
                }

                val wasAtBottom = isRecyclerNearBottom()
                chatAdapter.submitList(precomputedItems) {
                    if (precomputedItems.isNotEmpty() && wasAtBottom) {
                        binding.recyclerViewMessages.post {
                            binding.recyclerViewMessages.scrollToPosition(precomputedItems.size - 1)
                        }
                    }
                }
            }
        }
    }

    /**
     * Process messages into ChatListItems with date headers.
     *
     * Optimizations over the original:
     * - Caches last known message IDs to skip re-processing when the message set is unchanged.
     * - Uses lazy-initialized ZoneId/SimpleDateFormat to avoid per-call allocation.
     * - Pre-allocates the result list with a capacity hint.
     */
    private fun processMessagesWithHeaders(messages: List<Message>): List<ChatListItem> {
        if (messages.isEmpty()) return emptyList()

        // Fast-path: if the message set hasn't changed, return the cached result.
        // This is common when the live flow re-emits the same data (e.g., after a
        // configuration change or another collector restarting).
        val currentIds = messages.mapTo(mutableSetOf()) { it.id }
        if (currentIds == lastKnownProcessedMessageIds && cachedHeaderProcessedItems.isNotEmpty()) {
            return cachedHeaderProcessedItems
        }

        // Pre-allocate with capacity hint: each message → 1 item + occasional header.
        // Headers appear roughly every 20-50 messages in an active conversation.
        val estimatedSize = messages.size + (messages.size / 20).coerceAtLeast(1)
        val result = ArrayList<ChatListItem>(estimatedSize)

        var lastDate: LocalDate? = null
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)

        var currentHeaderText = ""
        messages.forEach { message ->
            val messageDate = Instant.ofEpochMilli(message.timestamp).atZone(zoneId).toLocalDate()
            if (messageDate != lastDate) {
                currentHeaderText = when (messageDate) {
                    today -> "Today"
                    yesterday -> "Yesterday"
                    else -> dateFormatter.format(Date(message.timestamp))
                }
                result.add(ChatListItem.DateHeader(currentHeaderText))
                lastDate = messageDate
            }
            result.add(ChatListItem.MessageItem(message, dateString = currentHeaderText))
        }

        // Cache for next emission
        lastKnownProcessedMessageIds = currentIds
        cachedHeaderProcessedItems = result

        return result
    }

    private fun initiateCall(callType: com.glyph.glyph_v3.data.models.CallType) {
        val receiverId = otherUserId
        if (receiverId.isNullOrEmpty()) {
            android.widget.Toast.makeText(requireContext(), "Cannot call – user info unavailable", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (com.glyph.glyph_v3.data.repo.BlockRepository.getBlockStatus(receiverId).isBlocked) {
            android.widget.Toast.makeText(
                requireContext(),
                "Voice and video calls are unavailable with ${otherUsername.ifEmpty { "this contact" }}.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val callManager = com.glyph.glyph_v3.data.webrtc.CallManager
        callManager.startOutgoingCall(
            context = requireContext(),
            receiverId = receiverId,
            receiverName = otherUsername,
            receiverAvatar = otherUserAvatar,
            callType = callType
        )
        val callData = callManager.callData.value ?: return
        startActivity(
            com.glyph.glyph_v3.ui.calls.ActiveCallActivity.createIntent(
                context = requireContext(),
                callId = callData.callId,
                callType = callType,
                contactName = otherUsername,
                contactAvatar = otherUserAvatar
            )
        )
    }

    private fun sendMessage() {
        val text = binding.etMessageInput.text.toString().trim()
        val id = chatId ?: return
        val otherId = otherUserId ?: return

        if (text.isNotEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch {
                repository.sendMessage(id, text, otherId, otherUsername.ifEmpty { "Unknown" }, otherUserAvatar)
            }
            binding.etMessageInput.text.clear()
            binding.recyclerViewMessages.post {
                if (chatAdapter.itemCount > 0) {
                    binding.recyclerViewMessages.scrollToPosition(chatAdapter.itemCount - 1)
                }
            }
        }
    }

    private fun isRecyclerNearBottom(): Boolean {
        val rv = binding.recyclerViewMessages

        // If not laid out or no items, we are effectively "at bottom" for UI purposes.
        if (rv.width == 0 || rv.height == 0 || chatAdapter.itemCount == 0) return true

        val verticalOffset = rv.computeVerticalScrollOffset()
        val verticalExtent = rv.computeVerticalScrollExtent()
        val verticalRange = rv.computeVerticalScrollRange()

        // Dist from bottom in pixels
        val distanceFromBottom = verticalRange - verticalExtent - verticalOffset

        // If the entire list fits on screen, we are always at bottom.
        if (verticalRange <= verticalExtent) return true

        val density = resources.displayMetrics.density
        val thresholdPx = (50 * density).toInt()

        return distanceFromBottom < thresholdPx
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // MEMORY LEAK FIX: Clear cached data so the Fragment doesn't hold stale
        // references through its fields after the view is destroyed.
        lastKnownProcessedMessageIds = emptySet()
        cachedHeaderProcessedItems = emptyList()
        _binding = null
    }
}
