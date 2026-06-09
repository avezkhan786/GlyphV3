package com.glyph.glyph_v3.ui.profile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.databinding.DialogProfilePreviewBinding
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.data.repo.AvatarVisibilityRepository
import com.glyph.glyph_v3.data.webrtc.CallManager
import com.glyph.glyph_v3.ui.calls.ActiveCallActivity
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.chat.ContactInfoActivity
import com.glyph.glyph_v3.ui.groups.GroupInfoActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfilePreviewDialog : DialogFragment() {

    private var _binding: DialogProfilePreviewBinding? = null
    private val binding get() = _binding!!

    private var userId: String = ""
    private var userName: String = ""
    private var userAvatar: String = ""
    private var chatId: String = ""
    private var canShowAvatar: Boolean = false
    
    // Animation parameters
    private var startX: Int = 0
    private var startY: Int = 0
    private var startWidth: Int = 0
    private var startHeight: Int = 0

    companion object {
        private const val ARG_USER_ID = "user_id"
        private const val ARG_USER_NAME = "user_name"
        private const val ARG_USER_AVATAR = "user_avatar"
        private const val ARG_CHAT_ID = "chat_id"
        private const val ARG_START_X = "start_x"
        private const val ARG_START_Y = "start_y"
        private const val ARG_START_WIDTH = "start_width"
        private const val ARG_START_HEIGHT = "start_height"

        fun newInstance(
            userId: String,
            userName: String,
            userAvatar: String,
            chatId: String,
            startX: Int,
            startY: Int,
            startWidth: Int,
            startHeight: Int
        ): ProfilePreviewDialog {
            return ProfilePreviewDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_USER_ID, userId)
                    putString(ARG_USER_NAME, userName)
                    putString(ARG_USER_AVATAR, userAvatar)
                    putString(ARG_CHAT_ID, chatId)
                    putInt(ARG_START_X, startX)
                    putInt(ARG_START_Y, startY)
                    putInt(ARG_START_WIDTH, startWidth)
                    putInt(ARG_START_HEIGHT, startHeight)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.ProfilePreviewDialogTheme)
        
        arguments?.let {
            userId = it.getString(ARG_USER_ID, "")
            userName = it.getString(ARG_USER_NAME, "")
            userAvatar = it.getString(ARG_USER_AVATAR, "")
            chatId = it.getString(ARG_CHAT_ID, "")
            startX = it.getInt(ARG_START_X, 0)
            startY = it.getInt(ARG_START_Y, 0)
            startWidth = it.getInt(ARG_START_WIDTH, 0)
            startHeight = it.getInt(ARG_START_HEIGHT, 0)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogProfilePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            setupUI()
            setupClickListeners()
            
            // Wait for layout to calculate animation
            binding.cardPreview.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    binding.cardPreview.viewTreeObserver.removeOnPreDrawListener(this)
                    animateIn()
                    return true
                }
            })
        } catch (e: Exception) {
            // If anything fails, just dismiss the dialog gracefully
            dismissAllowingStateLoss()
        }
    }

    private fun setupUI() {
        binding.tvUserName.text = userName
        binding.ivProfilePreview.setImageResource(R.drawable.ic_default_avatar)

        if (userId.isBlank()) {
            renderGroupAvatarPreview()
            return
        }

        lifecycleScope.launch {
            AvatarVisibilityRepository.observeProfilePhotoVisibility(userId).collectLatest { state ->
                if (!isAdded) return@collectLatest
                renderAvatar(state.isVisible)
            }
        }
    }

    private fun renderAvatar(canShow: Boolean) {
        canShowAvatar = canShow
        if (!canShow || userAvatar.isEmpty() || userId.isEmpty()) {
            Glide.with(this).clear(binding.ivProfilePreview)
            binding.ivProfilePreview.setImageResource(R.drawable.ic_default_avatar)
            return
        }

        val localAvatarPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(userId)

        if (localAvatarPath != null) {
            val file = java.io.File(localAvatarPath)
            Glide.with(this)
                .load(file)
                .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                .skipMemoryCache(true)
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(binding.ivProfilePreview)
            checkForAvatarUpdate()
        } else {
            Glide.with(this)
                .load(userAvatar)
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(binding.ivProfilePreview)

            lifecycleScope.launch(Dispatchers.IO) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheAvatar(
                    userId,
                    userAvatar,
                    requireContext()
                )
            }
        }
    }

    private fun renderGroupAvatarPreview() {
        canShowAvatar = userAvatar.isNotBlank()
        if (!canShowAvatar) {
            Glide.with(this).clear(binding.ivProfilePreview)
            binding.ivProfilePreview.setImageResource(R.drawable.ic_default_avatar)
            return
        }

        val localAvatarPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(chatId)
        if (localAvatarPath != null) {
            val file = java.io.File(localAvatarPath)
            Glide.with(this)
                .load(file)
                .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                .skipMemoryCache(true)
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(binding.ivProfilePreview)
        } else {
            val request = ImageRequest.Builder(requireContext())
                .data(userAvatar)
                .size(Size.ORIGINAL)
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .target(binding.ivProfilePreview)
                .build()
            requireContext().imageLoader.enqueue(request)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val updated = if (localAvatarPath == null) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.cacheGroupAvatar(
                    chatId,
                    userAvatar,
                    requireContext()
                )
            } else {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.updateGroupAvatarIfNeeded(
                    chatId,
                    userAvatar,
                    requireContext()
                )
            }

            if (updated && isAdded) {
                withContext(Dispatchers.Main) {
                    val refreshedPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalGroupAvatarPath(chatId)
                    val refreshedFile = refreshedPath?.let { java.io.File(it) }
                    if (refreshedFile != null && refreshedFile.exists()) {
                        Glide.with(this@ProfilePreviewDialog)
                            .load(refreshedFile)
                            .signature(com.bumptech.glide.signature.ObjectKey(refreshedFile.lastModified()))
                            .skipMemoryCache(true)
                            .placeholder(R.drawable.ic_default_avatar)
                            .error(R.drawable.ic_default_avatar)
                            .into(binding.ivProfilePreview)
                    }
                }
            }
        }
    }
    
    /**
     * Check for avatar updates when user taps to enlarge the avatar.
     * If a new avatar is found, download it and automatically update the UI.
     */
    private fun checkForAvatarUpdate() {
        if (userId.isEmpty() || userAvatar.isEmpty()) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val updated = com.glyph.glyph_v3.data.cache.AvatarCacheManager.updateAvatarIfNeeded(
                    userId,
                    userAvatar,
                    requireContext()
                )

                if (updated) {
                    // Reload the avatar on the main thread
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        reloadAvatarImage()
                    }
                } else {
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfilePreviewDialog", "Error checking for avatar update", e)
            }
        }
    }
    
    /**
     * Reload avatar image from cache after a new avatar has been downloaded.
     */
    private fun reloadAvatarImage() {
        val localPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(userId)
        
        if (localPath != null) {
            val file = java.io.File(localPath)
            Glide.with(this)
                .load(file)
                .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                .skipMemoryCache(true)  // Force Glide to re-decode from file
                .placeholder(R.drawable.ic_default_avatar)
                .error(R.drawable.ic_default_avatar)
                .into(binding.ivProfilePreview)
        } else {
            android.util.Log.w("ProfilePreviewDialog", "Cannot reload avatar: no cached file for user $userId")
        }
    }

    private fun setupClickListeners() {
        // Tap on background to dismiss
        binding.root.setOnClickListener {
            animateOut { dismiss() }
        }

        // Tap on preview card - prevent dismiss
        binding.cardPreview.setOnClickListener {
            // Do nothing - prevents click through to root
        }

        // Tap on image to open full screen
        binding.ivProfilePreview.setOnClickListener {
            if (canShowAvatar) {
                openFullScreenImage()
            }
        }

        // Chat button
        binding.btnChat.setOnClickListener {
            animateOut {
                dismiss()
                navigateToChat()
            }
        }

        // Voice call button
        binding.btnVoiceCall.setOnClickListener {
            animateOut {
                dismiss()
                initiateCall(CallType.VOICE)
            }
        }

        // Video call button
        binding.btnVideoCall.setOnClickListener {
            animateOut {
                dismiss()
                initiateCall(CallType.VIDEO)
            }
        }

        // Info button
        binding.btnInfo.setOnClickListener {
            animateOut {
                dismiss()
                openInfo()
            }
        }
    }

    private fun animateIn() {
        if (_binding == null || !isAdded) return
        
        val card = binding.cardPreview
        val root = binding.root
        
        // Validate that we have valid dimensions
        if (card.width <= 0 || root.width <= 0 || startWidth <= 0) {
            // Skip animation, just show the dialog
            card.alpha = 1f
            binding.overlayBg.alpha = 1f
            return
        }
        
        try {
            // Use root view center as the reference point since the card is centered in the root
            // This avoids issues where card.getLocationOnScreen might return 0 or incorrect values during initial layout
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            
            // Calculate the center of the card (which is centered in the root)
            val cardCenterX = rootLocation[0] + root.width / 2f
            val cardCenterY = rootLocation[1] + root.height / 2f
            
            // Calculate the center of the source avatar
            val avatarCenterX = startX + startWidth / 2f
            val avatarCenterY = startY + startHeight / 2f
            
            // Calculate translation needed to move card center to avatar center
            val translateX = avatarCenterX - cardCenterX
            val translateY = avatarCenterY - cardCenterY
            
            // Calculate scale (avatar size / image size)
            // We want the image part of the card (ivProfilePreview) to match the avatar size
            // The image is 280dp, startWidth is usually ~48dp
            val imageWidth = binding.ivProfilePreview.width
            val scale = startWidth.toFloat() / imageWidth.coerceAtLeast(1).toFloat()
            
            // Store original radius and set initial state
            val finalRadius = card.radius
            val initialRadius = card.width / 2f
            
            // Set pivot to center for scale animation
            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f
            
            // Set initial state: shifted to source, scaled down, circular, with secondary elements hidden
            card.translationX = translateX
            card.translationY = translateY
            card.scaleX = scale
            card.scaleY = scale
            card.alpha = 0f
            card.radius = initialRadius
            
            binding.layoutHeader.alpha = 0f
            binding.layoutButtons.alpha = 0f
            binding.overlayBg.alpha = 0f

            // Animate to final state
            val cardAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(card, View.SCALE_X, scale, 1f),
                    ObjectAnimator.ofFloat(card, View.SCALE_Y, scale, 1f),
                    ObjectAnimator.ofFloat(card, View.TRANSLATION_X, translateX, 0f),
                    ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, translateY, 0f),
                    ObjectAnimator.ofFloat(card, "radius", initialRadius, finalRadius)
                )
                duration = 300
                interpolator = DecelerateInterpolator()
            }

            val componentsAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.layoutHeader, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(binding.layoutButtons, View.ALPHA, 0f, 1f)
                )
                duration = 200
                startDelay = 100
            }

            val bgAnimator = ObjectAnimator.ofFloat(binding.overlayBg, View.ALPHA, 0f, 1f).apply {
                duration = 250
            }

            AnimatorSet().apply {
                playTogether(cardAnimator, componentsAnimator, bgAnimator)
                start()
            }
        } catch (e: Exception) {
            // If animation fails, just show the dialog without animation
            card.alpha = 1f
            binding.overlayBg.alpha = 1f
        }
    }

    private fun animateOut(onEnd: () -> Unit) {
        if (_binding == null || !isAdded) {
            onEnd()
            return
        }
        
        try {
            val card = binding.cardPreview
            val root = binding.root
            
            // Calculate current centers again (in case of any layout changes, though unlikely)
            val rootLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            
            val cardCenterX = rootLocation[0] + root.width / 2f
            val cardCenterY = rootLocation[1] + root.height / 2f
            
            val avatarCenterX = startX + startWidth / 2f
            val avatarCenterY = startY + startHeight / 2f
            
            val translateX = avatarCenterX - cardCenterX
            val translateY = avatarCenterY - cardCenterY
            
            val imageWidth = binding.ivProfilePreview.width
            val scale = startWidth.toFloat() / imageWidth.coerceAtLeast(1).toFloat()
            val initialRadius = card.width / 2f
            val currentRadius = card.radius

            card.pivotX = card.width / 2f
            card.pivotY = card.height / 2f

            val cardAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(card, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(card, View.SCALE_X, 1f, scale),
                    ObjectAnimator.ofFloat(card, View.SCALE_Y, 1f, scale),
                    ObjectAnimator.ofFloat(card, View.TRANSLATION_X, 0f, translateX),
                    ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, 0f, translateY),
                    ObjectAnimator.ofFloat(card, "radius", currentRadius, initialRadius)
                )
                duration = 200
                interpolator = DecelerateInterpolator()
            }

            val componentsAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.layoutHeader, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(binding.layoutButtons, View.ALPHA, 1f, 0f)
                )
                duration = 100
            }

            val bgAnimator = ObjectAnimator.ofFloat(binding.overlayBg, View.ALPHA, 1f, 0f).apply {
                duration = 200
            }

            AnimatorSet().apply {
                playTogether(cardAnimator, componentsAnimator, bgAnimator)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onEnd()
                    }
                })
                start()
            }
        } catch (e: Exception) {
            // If animation fails, just execute the callback
            onEnd()
        }
    }

    private fun openFullScreenImage() {
        if (userAvatar.isNotEmpty()) {
            val ctx = context ?: return
            
            // Prefer local cached avatar for fullscreen (ensures latest image + avoids stale URL caches)
            val localAvatarPath = if (userId.isNotEmpty()) {
                com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(userId)
            } else {
                null
            }

            val intent = if (!localAvatarPath.isNullOrEmpty()) {
                val file = java.io.File(localAvatarPath)
                FullScreenImageActivity.newIntent(
                    context = ctx,
                    imageUrl = userAvatar,
                    userName = userName,
                    localImagePath = localAvatarPath,
                    localImageLastModified = file.lastModified()
                )
            } else {
                FullScreenImageActivity.newIntent(
                    ctx,
                    userAvatar,
                    userName
                )
            }

            startActivity(intent)
        }
    }

    private fun initiateCall(callType: CallType) {
        val ctx = context ?: return
        if (userId.isBlank()) {
            // Group calls not yet supported
            android.widget.Toast.makeText(ctx, "Group calls are not yet supported", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        CallManager.startOutgoingCall(
            context = ctx,
            receiverId = userId,
            receiverName = userName,
            receiverAvatar = userAvatar,
            callType = callType
        )
        val callData = CallManager.callData.value ?: return
        startActivity(
            ActiveCallActivity.createIntent(
                context = ctx,
                callId = callData.callId,
                callType = callType,
                contactName = userName,
                contactAvatar = userAvatar
            )
        )
    }

    private fun openInfo() {
        val ctx = context ?: return
        if (userId.isBlank()) {
            // Group chat — open GroupInfoActivity
            startActivity(
                GroupInfoActivity.newIntent(
                    context = ctx,
                    chatId = chatId
                )
            )
        } else {
            // 1:1 chat — open ContactInfoActivity
            startActivity(
                ContactInfoActivity.newIntent(
                    context = ctx,
                    contactName = userName,
                    contactPhone = "",
                    contactAvatar = userAvatar,
                    contactUserId = userId,
                    chatId = chatId
                )
            )
        }
    }

    private fun navigateToChat() {
        val ctx = context ?: return
        val intent = ChatActivity.newIntent(
            ctx,
            chatId,
            userId,
            userName,
            userAvatar
        )
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
