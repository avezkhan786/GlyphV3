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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.Message
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.databinding.FragmentChatBinding
import kotlinx.coroutines.launch
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

    companion object {
        private const val ARG_CHAT_ID = "chat_id"
        private const val ARG_OTHER_USER_ID = "other_user_id"
        private const val ARG_OTHER_USERNAME = "other_username"
        private const val ARG_OTHER_USER_AVATAR = "other_user_avatar"

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
        chatAdapter = ChatAdapter(selectionManager)
        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun loadMessages() {
        val id = chatId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getMessages(id).collect { messages ->
                val listItems = processMessagesWithHeaders(messages)
                val wasAtBottom = isRecyclerNearBottom()
                chatAdapter.submitList(listItems) {
                    if (listItems.isNotEmpty() && wasAtBottom) {
                        binding.recyclerViewMessages.post {
                            binding.recyclerViewMessages.scrollToPosition(listItems.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun processMessagesWithHeaders(messages: List<Message>): List<ChatListItem> {
        val result = mutableListOf<ChatListItem>()
        var lastDate: LocalDate? = null
        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val yesterday = today.minusDays(1)
        val dateFormatter = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())

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
        _binding = null
    }
}
