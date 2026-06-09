package com.glyph.glyph_v3.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.cache.UserProfileCache
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.PrivacySettingsRepository
import com.glyph.glyph_v3.databinding.FragmentSettingsNewBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

private data class SettingItem(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val onClick: () -> Unit
)

class SettingsFragment : Fragment() {

    companion object {
        private const val PROFILE_REFRESH_INTERVAL_MS = 60_000L
    }

    private var _binding: FragmentSettingsNewBinding? = null
    private val binding get() = _binding!!
    private val repository = FirebaseRepository()
    
    private var userName = "Your Name"
    private var about = "Hey there! I am using Glyph."
    private var userId: String? = null
    private var authUserId: String? = null
    private var avatarUrl: String? = null
    private var avatarFullUrl: String? = null
    private var isLoading = false
    private var isUploading = false
    private var renderedTheme: String? = null
    private var lastProfileLoadAt = 0L
    private var lastRenderedAvatarKey: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleAvatarUpload(it) }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            capturedImageUri?.let { handleAvatarUpload(it) }
        }
    }

    private var capturedImageUri: Uri? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsNewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        loadUserData(forceRefresh = false)
        warmPrivacySettingsCache()
        setupClickListeners()
        setupSettingsItems()
        setupVersionInfo()
        applyThemeBackground(view)
    }

    override fun onResume() {
        super.onResume()
        // Restore camera badge visibility in case it was hidden for a transition
        _binding?.cameraBadge?.visibility = View.VISIBLE
        loadUserData(forceRefresh = false)
        warmPrivacySettingsCache()

        if (renderedTheme != com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(requireContext())) {
            setupSettingsItems()
            applyThemeBackground(requireView())
        }
    }

    private fun setupClickListeners() {
        // Avatar container click
        binding.avatarContainer.setOnClickListener {
            if (!isUploading) {
                openAvatarPreview()
            }
        }

        // Edit name
        binding.nameLabelRow.setOnClickListener {
            if (!isUploading) {
                showEditDialog("name")
            }
        }

        // Edit about
        binding.aboutCard.setOnClickListener {
            if (!isUploading) {
                showEditDialog("about")
            }
        }

        // Refresh avatar
        binding.refreshAction.setOnClickListener {
            if (!isUploading) {
                refreshAvatar()
            }
        }

    }

    private fun warmPrivacySettingsCache() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            PrivacySettingsRepository.warmCacheIfNeeded()
        }
    }

    private fun setupSettingsItems() {
        val context = requireContext()
        val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(context)
        renderedTheme = currentTheme

        val settingsItems = listOf(
            SettingItem(
                "Account",
                "Security notifications, change number",
                R.drawable.ic_account
            ) {
                startActivity(Intent(requireContext(), AccountSettingsActivity::class.java))
            },
            SettingItem(
                "Privacy",
                "Block contacts, last seen, read receipts",
                R.drawable.ic_privacy
            ) {
                startActivity(android.content.Intent(requireContext(), PrivacySettingsActivity::class.java))
            },
            SettingItem(
                "Security",
                "App lock, biometric authentication",
                R.drawable.ic_lock
            ) {
                startActivity(Intent(requireContext(), SecuritySettingsActivity::class.java))
            },
            SettingItem(
                "Theme",
                com.glyph.glyph_v3.utils.ThemeManager.getThemeDisplayName(
                    currentTheme
                ),
                R.drawable.ic_theme
            ) {
                val intent = Intent(requireContext(), ThemeSelectionActivity::class.java)
                startActivity(intent)
            },
            SettingItem(
                "Chats",
                "Wallpapers, font size, enter-to-send",
                R.drawable.ic_chats
            ) {
                val intent = Intent(requireContext(), com.glyph.glyph_v3.ui.settings.chat.ChatSettingsActivity::class.java)
                startActivity(intent)
            },
            SettingItem(
                "Notifications",
                "Message, group & call tones",
                R.drawable.ic_notifications
            ) {
                startActivity(Intent(requireContext(), NotificationSettingsActivity::class.java))
            },
            SettingItem(
                "Storage and data",
                "Network usage, auto-download",
                R.drawable.ic_storage
            ) {
                startActivity(Intent(requireContext(), StorageDataActivity::class.java))
            },
            SettingItem(
                "Help",
                "FAQ, contact us, report a bug",
                R.drawable.ic_help
            ) {
                startActivity(Intent(requireContext(), HelpSupportActivity::class.java))
            }
        )

        binding.settingsContainer.removeAllViews()
        
        settingsItems.forEach { item ->
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setting, binding.settingsContainer, false)
            
            itemView.findViewById<ImageView>(R.id.settingIcon).setImageResource(item.iconRes)
            itemView.findViewById<TextView>(R.id.settingTitle).text = item.title
            itemView.findViewById<TextView>(R.id.settingSubtitle).text = item.subtitle
            
            itemView.setOnClickListener { item.onClick() }
            
            binding.settingsContainer.addView(itemView)
        }
    }

    private fun applyThemeBackground(root: View) {
        val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(requireContext())
        root.background = if (currentTheme == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY) {
            ContextCompat.getDrawable(requireContext(), R.drawable.bg_pastel_gradient)
        } else {
            android.graphics.drawable.ColorDrawable(resolveThemeColor(android.R.attr.colorBackground))
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun setupVersionInfo() {
        binding.tvVersion.text = "Glyph v${com.glyph.glyph_v3.BuildConfig.VERSION_NAME}"
    }

    private fun loadUserData(forceRefresh: Boolean) {
        if (isLoading) return

        val currentUser = Firebase.auth.currentUser
        authUserId = currentUser?.uid
        userId = currentUser?.phoneNumber ?: authUserId
        val now = System.currentTimeMillis()

        val cachedProfile = UserProfileCache.get(requireContext(), authUserId)
        if (cachedProfile != null) {
            applyCachedProfile(cachedProfile)
            if (!forceRefresh && now - lastProfileLoadAt < PROFILE_REFRESH_INTERVAL_MS) {
                return
            }
        }

        isLoading = true
        _binding?.syncStatus?.visibility = View.VISIBLE

        repository.getUser { user ->
            val binding = _binding
            if (binding != null && isAdded && user != null) {
                userName = user.username.ifEmpty { "Your Name" }
                about = user.bio.ifEmpty { "Hey there! I am using Glyph." }
                avatarUrl = user.profileImageUrl
                avatarFullUrl = user.profileImageFullUrl

                binding.tvDisplayName.text = userName
                binding.tvAbout.text = about
                binding.tvPhoneId.text = "Phone: ${userId ?: "Unknown"}"

                renderProfileAvatar(force = false)

                authUserId?.let { uid ->
                    UserProfileCache.save(
                        requireContext(),
                        UserProfileCache.CachedUserProfile(
                            userId = uid,
                            phone = currentUser?.phoneNumber,
                            username = userName,
                            bio = about,
                            avatarUrl = avatarUrl,
                            avatarFullUrl = user.profileImageFullUrl
                        )
                    )

                    if (!avatarUrl.isNullOrEmpty()) {
                        val appContext = requireContext().applicationContext
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val updated = AvatarCacheManager.updateAvatarIfNeeded(
                                uid,
                                avatarUrl,
                                appContext
                            )
                            if (updated && isAdded) {
                                withContext(Dispatchers.Main) {
                                    renderProfileAvatar(force = true)
                                }
                            }
                        }
                    }
                }
            }

            lastProfileLoadAt = System.currentTimeMillis()
            isLoading = false
            binding?.syncStatus?.visibility = View.GONE
        }
    }

    private fun applyCachedProfile(profile: UserProfileCache.CachedUserProfile) {
        val binding = _binding ?: return
        binding.syncStatus.visibility = View.GONE
        userName = if (profile.username.isNotBlank()) profile.username else "Your Name"
        about = if (profile.bio.isNotBlank()) profile.bio else "Hey there! I am using Glyph."
        avatarUrl = profile.avatarUrl
        avatarFullUrl = profile.avatarFullUrl

        binding.tvDisplayName.text = userName
        binding.tvAbout.text = about
        binding.tvPhoneId.text = "Phone: ${profile.phone ?: "Unknown"}"

        val localPath = profile.userId.let { AvatarCacheManager.getLocalAvatarPath(it) }
        renderProfileAvatar(localAvatarPath = localPath, remoteUrl = profile.avatarUrl, force = false)
    }

    private fun renderProfileAvatar(
        localAvatarPath: String? = authUserId?.let { AvatarCacheManager.getLocalAvatarPath(it) },
        remoteUrl: String? = avatarUrl,
        force: Boolean
    ) {
        val binding = _binding ?: return
        val avatarFile = localAvatarPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.length() > 0L }
        val resolvedRemoteUrl = remoteUrl?.takeIf { it.isNotBlank() }
        val cacheKey = when {
            avatarFile != null -> {
                AvatarCacheManager.buildAvatarCacheKey(
                    userId = authUserId.orEmpty(),
                    localAvatarPath = avatarFile.absolutePath,
                    avatarUrl = resolvedRemoteUrl
                ) ?: "settings-avatar:file:${avatarFile.lastModified()}"
            }
            resolvedRemoteUrl != null -> {
                AvatarCacheManager.buildAvatarCacheKey(
                    userId = authUserId.orEmpty(),
                    localAvatarPath = null,
                    avatarUrl = resolvedRemoteUrl
                ) ?: "settings-avatar:url:$resolvedRemoteUrl"
            }
            else -> "settings-avatar:default"
        }

        if (!force && lastRenderedAvatarKey == cacheKey && binding.ivProfile.drawable != null) {
            return
        }

        lastRenderedAvatarKey = cacheKey

        when {
            avatarFile != null -> {
                Glide.with(this)
                    .load(avatarFile)
                    .signature(ObjectKey(cacheKey))
                    .dontAnimate()
                    .placeholder(binding.ivProfile.drawable)
                    .error(R.drawable.ic_default_avatar)
                    .circleCrop()
                    .into(binding.ivProfile)
            }
            resolvedRemoteUrl != null -> {
                Glide.with(this)
                    .load(resolvedRemoteUrl)
                    .signature(ObjectKey(cacheKey))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .dontAnimate()
                    .placeholder(binding.ivProfile.drawable)
                    .error(R.drawable.ic_default_avatar)
                    .circleCrop()
                    .into(binding.ivProfile)
            }
            else -> {
                binding.ivProfile.setImageResource(R.drawable.ic_default_avatar)
            }
        }
    }

    private fun openAvatarPreview() {
        // Compute the avatar's exact position on screen so AvatarPreviewActivity
        // can animate from that exact rect — no shared-element API needed.
        val loc = IntArray(2)
        binding.ivProfile.getLocationOnScreen(loc)
        val srcLeft   = loc[0]
        val srcTop    = loc[1]
        val srcWidth  = binding.ivProfile.width
        val srcHeight = binding.ivProfile.height

        val intent = Intent(requireContext(), AvatarPreviewActivity::class.java).apply {
            putExtra("EXTRA_AVATAR_URL", avatarUrl)
            putExtra("EXTRA_AVATAR_FULL_URL", avatarFullUrl)
            putExtra("EXTRA_USER_ID", authUserId ?: userId)
            putExtra("EXTRA_SRC_LEFT",   srcLeft)
            putExtra("EXTRA_SRC_TOP",    srcTop)
            putExtra("EXTRA_SRC_WIDTH",  srcWidth)
            putExtra("EXTRA_SRC_HEIGHT", srcHeight)
        }

        // Hide the camera badge so it doesn't bleed through the transparent window
        binding.cameraBadge.visibility = View.INVISIBLE
        startActivity(intent)
        // Suppress the default Activity transition — we run our own
        requireActivity().overridePendingTransition(0, 0)
    }

    private fun showAvatarOptionsDialog() {
        val options = if (avatarUrl.isNullOrEmpty()) {
            arrayOf("Take Photo", "Choose from Gallery")
        } else {
            arrayOf("Take Photo", "Choose from Gallery", "Remove Avatar", "Refresh Avatar")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Profile Picture")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> takePhoto()
                    1 -> pickImage()
                    2 -> if (!avatarUrl.isNullOrEmpty()) removeAvatar()
                    3 -> if (!avatarUrl.isNullOrEmpty()) refreshAvatar()
                }
            }
            .show()
    }

    private fun pickImage() {
        pickImageLauncher.launch("image/*")
    }

    private fun takePhoto() {
        // Implementation for taking photo
        Toast.makeText(requireContext(), "Camera feature coming soon!", Toast.LENGTH_SHORT).show()
    }

    private fun handleAvatarUpload(uri: Uri) {
        isUploading = true
        binding.uploadOverlay.visibility = View.VISIBLE
        binding.cameraBadge.visibility = View.GONE
        binding.uploadProgressText.text = "0%"
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val downloadUrl = withContext(Dispatchers.IO) {
                    repository.uploadProfileImage(uri) { progress ->
                        CoroutineScope(Dispatchers.Main).launch {
                            binding.uploadProgressText.text = "${progress.toInt()}%"
                        }
                    }
                }
                
                avatarUrl = downloadUrl
                avatarFullUrl = downloadUrl
                lastRenderedAvatarKey = null
                renderProfileAvatar(localAvatarPath = null, remoteUrl = downloadUrl, force = true)

                UserProfileCache.update(
                    requireContext(),
                    authUserId,
                    avatarUrl = downloadUrl
                )
                
                // Cache avatar immediately for instant loading across the app
                authUserId?.let { id ->
                    withContext(Dispatchers.IO) {
                        AvatarCacheManager.cacheAvatar(
                            id,
                            downloadUrl,
                            requireContext()
                        )
                    }
                }
                
                Toast.makeText(
                    requireContext(),
                    "Profile picture updated successfully!",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to upload avatar: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isUploading = false
                binding.uploadOverlay.visibility = View.GONE
                binding.cameraBadge.visibility = View.VISIBLE
            }
        }
    }

    private fun removeAvatar() {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Profile Picture")
            .setMessage("Are you sure you want to remove your profile picture?")
            .setPositiveButton("Remove") { _, _ ->
                isUploading = true
                binding.uploadOverlay.visibility = View.VISIBLE
                binding.cameraBadge.visibility = View.GONE
                
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        withContext(Dispatchers.IO) {
                            repository.removeProfileImage()
                            
                            // Clear cached avatar
                            authUserId?.let { id ->
                                com.glyph.glyph_v3.data.cache.AvatarCacheManager.clearAvatarCache(id)
                            }
                        }
                        
                        avatarUrl = null
                        avatarFullUrl = null
                        lastRenderedAvatarKey = null
                        binding.ivProfile.setImageResource(R.drawable.ic_default_avatar)

                        UserProfileCache.update(
                            requireContext(),
                            authUserId,
                            avatarUrl = ""
                        )
                        
                        Toast.makeText(
                            requireContext(),
                            "Profile picture removed successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Failed to remove avatar: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        isUploading = false
                        binding.uploadOverlay.visibility = View.GONE
                        binding.cameraBadge.visibility = View.VISIBLE
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshAvatar() {
        isUploading = true
        binding.uploadOverlay.visibility = View.VISIBLE
        binding.cameraBadge.visibility = View.GONE
        lastRenderedAvatarKey = null
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                loadUserData(forceRefresh = true)
                Toast.makeText(
                    requireContext(),
                    "Avatar refreshed from server!",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isUploading = false
                binding.uploadOverlay.visibility = View.GONE
                binding.cameraBadge.visibility = View.VISIBLE
            }
        }
    }

    private fun showEditDialog(field: String) {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                navigationBarColor = resolveThemeColor(R.attr.glyphSettingsHeroBackground)
                navigationBarDividerColor = resolveThemeColor(R.attr.glyphSettingsHeroBackground)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    isNavigationBarContrastEnforced = false
                }
            }
        }
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_edit_profile, null)
        val baseBottomPadding = sheetView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheetView) { view, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = baseBottomPadding + bottomInset)
            insets
        }
        
        val isName = field == "name"
        val maxLength = if (isName) 50 else 140
        val minLength = if (isName) 2 else 1
        
        val titleView = sheetView.findViewById<TextView>(R.id.editModalTitle)
        val inputView = sheetView.findViewById<EditText>(R.id.editModalInput)
        val errorView = sheetView.findViewById<TextView>(R.id.editModalError)
        val descriptionView = sheetView.findViewById<TextView>(R.id.editModalDescription)
        val counterView = sheetView.findViewById<TextView>(R.id.editModalCounter)
        val cancelBtn = sheetView.findViewById<View>(R.id.btnCancel)
        val saveBtn = sheetView.findViewById<View>(R.id.btnSave)
        
        titleView.text = if (isName) "Edit Display Name" else "Edit About"
        inputView.setText(if (isName) userName else about)
        inputView.hint = if (isName) "Set a display name" else "Tell people about yourself"
        descriptionView.text = if (isName) {
            "This name appears across your Glyph profile."
        } else {
            "Your contacts will see this status under your name."
        }
        
        if (!isName) {
            inputView.maxLines = 4
            inputView.minLines = 1
            inputView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            inputView.isSingleLine = false
            inputView.setHorizontallyScrolling(false)
            inputView.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        
        fun updateCounter() {
            val length = inputView.text?.length ?: 0
            counterView.text = "$length/$maxLength"
        }
        
        updateCounter()
        
        inputView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                errorView.visibility = View.GONE
                updateCounter()
            }
        })
        
        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }
        
        saveBtn.setOnClickListener {
            val trimmed = inputView.text.toString().trim()
            
            when {
                trimmed.length < minLength -> {
                    errorView.text = if (isName) {
                        "Display name must be at least $minLength characters."
                    } else {
                        "About cannot be empty."
                    }
                    errorView.visibility = View.VISIBLE
                }
                trimmed.length > maxLength -> {
                    errorView.text = if (isName) {
                        "Display name must be $maxLength characters or fewer."
                    } else {
                        "About must be $maxLength characters or fewer."
                    }
                    errorView.visibility = View.VISIBLE
                }
                else -> {
                    saveProfileField(field, trimmed)
                    dialog.dismiss()
                }
            }
        }
        
        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun saveProfileField(field: String, value: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    if (field == "name") {
                        repository.updateUserProfile(username = value)
                        
                        // Update Firebase Auth display name
                        val currentUser = Firebase.auth.currentUser
                        currentUser?.updateProfile(
                            userProfileChangeRequest {
                                displayName = value
                            }
                        )?.await()
                    } else {
                        repository.updateUserProfile(bio = value)
                    }
                }
                
                if (field == "name") {
                    userName = value
                    binding.tvDisplayName.text = value
                    UserProfileCache.update(
                        requireContext(),
                        authUserId,
                        username = value
                    )
                } else {
                    about = value
                    binding.tvAbout.text = value
                    UserProfileCache.update(
                        requireContext(),
                        authUserId,
                        bio = value
                    )
                }
                
                Toast.makeText(
                    requireContext(),
                    "Profile updated successfully!",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to update profile: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
