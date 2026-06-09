package com.glyph.glyph_v3.ui.share

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.repo.FirebaseRepository
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.databinding.ActivityUserListBinding
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.login.LoginActivity
import com.glyph.glyph_v3.ui.users.ContactListItem
import com.glyph.glyph_v3.utils.PhoneNumberUtil
import com.glyph.glyph_v3.utils.ThemeManager
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles Android share sheet entries for ACTION_SEND and ACTION_SEND_MULTIPLE.
 *
 * Flow: system share sheet → this activity (contact picker) → [ChatActivity] with
 * shared content pre-loaded, ready to send.
 */
class ShareTargetActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserListBinding
    private val repository = FirebaseRepository()
    private lateinit var messageRepository: RealtimeMessageRepository
    private var allContacts: List<ContactListItem> = emptyList()
    private lateinit var adapter: ShareTargetAdapter
    private val selectedUsers = LinkedHashMap<String, User>()
    private val selectedRecipientLabels = LinkedHashMap<String, String>()
    private var isSendingShare = false
    private var sharedLinkUrl: String? = null
    private var sharedLinkPreview: LinkPreviewData? = null
    private var isLoadingLinkPreview = false
    private var isPreviewDismissed = false

    // Shared content extracted from the incoming intent
    private var sharedText: String? = null
    private var sharedUris: ArrayList<String> = arrayListOf()
    private var sharedMimeType: String = "*/*"

    data class DeviceContact(val name: String, val phoneNumbers: List<String>, val contactId: Long)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            loadAndDisplayContacts()
        } else {
            Toast.makeText(this, getString(R.string.contacts_permission_required), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        if (repository.currentUserId == null) {
            routeToLogin()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityUserListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getDatabase(this)
        messageRepository = RealtimeMessageRepository(
            db.messageDao(),
            db.chatDao(),
            db.deletedMessageDao(),
            applicationContext
        )

        // Extract shared content before any UI setup
        extractSharedContent()

        // Apply themed background for Pastel-Sky
        val currentTheme = ThemeManager.getCurrentTheme(this)
        if (currentTheme == ThemeManager.THEME_PASTEL_SKY) {
            binding.root.background = ContextCompat.getDrawable(this, R.drawable.bg_pastel_gradient)
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.share_send_to_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        applyHeaderTheming()

        // Push the toolbar below the status bar
        binding.toolbar.updatePadding(top = 0)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
        binding.appBarLayout.requestApplyInsets()

        setupRecyclerView()
        setupSearchView()
        setupShareFooter()
        sharedLinkUrl = LinkPreviewResolver.extractFirstUrl(sharedText)
        checkPermissionsAndLoad()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return false
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share_confirm -> {
                handleShareConfirmation()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ─── Shared content extraction ───────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun extractSharedContent() {
        sharedUris.clear()
        sharedMimeType = intent.type ?: "*/*"
        sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeUnless { it.isEmpty() }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                extractSingleStreamUri(intent)?.let { addSharedUri(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                extractMultipleStreamUris(intent).forEach { addSharedUri(it) }
            }
        }

        if (sharedUris.isEmpty()) {
            val clipData = intent.clipData
            for (index in 0 until (clipData?.itemCount ?: 0)) {
                clipData?.getItemAt(index)?.uri?.let { addSharedUri(it) }
            }
        }

        if (sharedMimeType == "*/*" && sharedUris.size == 1) {
            sharedMimeType = contentResolver.getType(Uri.parse(sharedUris.first())) ?: sharedMimeType
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSingleStreamUri(sourceIntent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sourceIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            sourceIntent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractMultipleStreamUris(sourceIntent: Intent): List<Uri> {
        val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sourceIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            sourceIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return uris.orEmpty()
    }

    private fun addSharedUri(uri: Uri) {
        val uriString = uri.toString()
        if (uriString.isNotEmpty() && !sharedUris.contains(uriString)) {
            sharedUris.add(uriString)
        }
    }

    private fun routeToLogin() {
        val loginIntent = Intent(this, LoginActivity::class.java).apply {
            action = intent.action
            type = intent.type
            clipData = intent.clipData
            putExtras(intent)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(loginIntent)
        finish()
    }

    // ─── UI helpers ──────────────────────────────────────────────────────────

    private fun applyHeaderTheming() {
        val iconColor = resolveColorAttr(R.attr.glyphToolbarIcon)
        binding.toolbar.navigationIcon?.setTint(iconColor)

        val searchSrcText = binding.searchView
            .findViewById<android.widget.TextView>(androidx.appcompat.R.id.search_src_text)
        val textColor = resolveColorAttr(R.attr.glyphTextPrimary)
        val hintColor = resolveColorAttr(R.attr.glyphTextHint)
        searchSrcText?.setTextColor(textColor)
        searchSrcText?.setHintTextColor(hintColor)

        binding.searchView.queryHint = getString(R.string.search_contacts_placeholder)
        binding.searchView.setIconifiedByDefault(false)

        val plate = binding.searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)
        plate?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val searchIcon = binding.searchView
            .findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_mag_icon)
        val closeIcon = binding.searchView
            .findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_close_btn)
        val iconSecondaryColor = resolveColorAttr(R.attr.glyphIconSecondary)
        searchIcon?.setColorFilter(iconSecondaryColor)
        closeIcon?.setColorFilter(iconSecondaryColor)

        val offsetPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics
        ).toInt()
        searchIcon?.translationX = 0f
        searchSrcText?.setPaddingRelative(
            offsetPx,
            searchSrcText.paddingTop,
            searchSrcText.paddingEnd,
            searchSrcText.paddingBottom
        )
    }

    private fun resolveColorAttr(attrRes: Int): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(attrRes, outValue, true)
        return if (outValue.resourceId != 0) {
            ContextCompat.getColor(this, outValue.resourceId)
        } else {
            outValue.data
        }
    }

    // ─── RecyclerView & Search ────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = ShareTargetAdapter(
            contacts = emptyList(),
            isSelected = { item -> item.registeredUser?.id?.let(selectedUsers::containsKey) == true },
            onUserClick = { item -> toggleRecipientSelection(item) }
        )
        binding.recyclerViewUsers.adapter = adapter
        binding.recyclerViewUsers.layoutManager = LinearLayoutManager(this)
    }

    private fun toggleRecipientSelection(item: ContactListItem) {
        val user = item.registeredUser
        if (!item.isRegistered || user == null) {
            Toast.makeText(
                this,
                getString(R.string.share_registered_only_hint),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (selectedUsers.containsKey(user.id)) {
            selectedUsers.remove(user.id)
            selectedRecipientLabels.remove(user.id)
        } else {
            selectedUsers[user.id] = user
            selectedRecipientLabels[user.id] = item.name
        }
        adapter.refreshSelection()
        updateSelectionTitle()
        updateShareFooter()
        if (selectedUsers.isNotEmpty()) {
            resolveSharedLinkPreviewIfNeeded()
        }
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filterContacts(newText.orEmpty())
                return true
            }
        })
    }

    private fun filterContacts(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            adapter.updateList(allContacts)
            return
        }
        val normalizedQuery = PhoneNumberUtil.normalizeToLast10Digits(q)
        val filtered = allContacts.filter { item ->
            val nameMatch = item.name.contains(q, ignoreCase = true)
            val phoneNorm = PhoneNumberUtil.normalizeToLast10Digits(item.phoneNumber)
            val phoneMatch = normalizedQuery.isNotEmpty() && phoneNorm.contains(normalizedQuery)
            nameMatch || phoneMatch
        }
        adapter.updateList(filtered)
    }

    private fun updateSelectionTitle() {
        supportActionBar?.title = if (selectedUsers.isEmpty()) {
            getString(R.string.share_send_to_title)
        } else {
            getString(R.string.share_selection_count, selectedUsers.size)
        }
    }

    private fun setupShareFooter() {
        binding.btnShareSend.setOnClickListener { handleShareConfirmation() }
        binding.btnDismissSharePreview.setOnClickListener {
            isPreviewDismissed = true
            updateShareFooter()
        }
        updateShareFooter()
    }

    private fun updateShareFooter() {
        val hasRecipients = selectedUsers.isNotEmpty()
        binding.shareBottomBar.visibility = if (hasRecipients) View.VISIBLE else View.GONE
        binding.btnShareSend.visibility = if (hasRecipients) View.VISIBLE else View.GONE
        binding.btnShareSend.isEnabled = hasRecipients && !isSendingShare
        binding.btnShareSend.alpha = if (binding.btnShareSend.isEnabled) 1f else 0.42f
        binding.tvSelectedRecipients.text = if (hasRecipients) {
            selectedRecipientLabels.values.joinToString(", ")
        } else {
            getString(R.string.share_recipients_placeholder)
        }

        val showPreview = hasRecipients && !isPreviewDismissed && !sharedLinkUrl.isNullOrBlank()
        binding.sharePreviewCard.visibility = if (showPreview) View.VISIBLE else View.GONE
        if (!showPreview) {
            Glide.with(this).clear(binding.ivSharePreviewThumbnail)
            binding.ivSharePreviewThumbnail.setImageDrawable(null)
            return
        }

        val preview = sharedLinkPreview
        val fallback = LinkPreviewResolver.fallback(sharedLinkUrl!!)
        binding.tvSharePreviewTitle.text = when {
            isLoadingLinkPreview && preview == null -> getString(R.string.share_preview_loading)
            !preview?.title.isNullOrBlank() -> preview?.title
            else -> fallback.title
        }
        binding.tvSharePreviewDomain.text = preview?.domain ?: fallback.domain
        binding.tvSharePreviewUrl.visibility = View.GONE

        val thumbnailUrl = preview?.thumbnailUrl
        if (!thumbnailUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(thumbnailUrl)
                .centerCrop()
                .into(binding.ivSharePreviewThumbnail)
        } else {
            Glide.with(this).clear(binding.ivSharePreviewThumbnail)
            binding.ivSharePreviewThumbnail.setImageDrawable(null)
        }
    }

    private fun resolveSharedLinkPreviewIfNeeded() {
        val url = sharedLinkUrl ?: return
        if (isPreviewDismissed || sharedLinkPreview != null || isLoadingLinkPreview) return

        isLoadingLinkPreview = true
        updateShareFooter()
        lifecycleScope.launch {
            sharedLinkPreview = runCatching { LinkPreviewResolver.resolve(url) }.getOrNull()
            isLoadingLinkPreview = false
            updateShareFooter()
        }
    }

    private fun updateShareActionState(menuItem: MenuItem?) {
        menuItem ?: return
        menuItem.isVisible = true
        menuItem.isEnabled = selectedUsers.isNotEmpty() && !isSendingShare
        menuItem.icon?.alpha = if (menuItem.isEnabled) 255 else 100
    }

    // ─── Permissions & data loading ──────────────────────────────────────────

    private fun checkPermissionsAndLoad() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            loadAndDisplayContacts()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    private fun loadAndDisplayContacts() {
        binding.progressBar.visibility = View.VISIBLE

        repository.getAllUsers(onSuccess = { firestoreUsers ->
            val registeredByNormalized = firestoreUsers
                .filter { it.phoneNumber.isNotBlank() }
                .associateBy { PhoneNumberUtil.normalizeToLast10Digits(it.phoneNumber) }

            val deviceContacts = getDeviceContacts()
            val contacts = mutableListOf<ContactListItem>()
            val seenUids = mutableSetOf<String>()
            val seenPhones = mutableSetOf<String>()

            for (contact in deviceContacts) {
                // Check ALL phone numbers for this contact
                var registeredUser: com.glyph.glyph_v3.data.models.User? = null
                var primaryPhone = contact.phoneNumbers.firstOrNull() ?: ""

                for (phone in contact.phoneNumbers) {
                    val normalized = PhoneNumberUtil.normalizeToLast10Digits(phone)
                    val user = if (normalized.isNotEmpty()) registeredByNormalized[normalized] else null
                    if (user != null) {
                        registeredUser = user
                        primaryPhone = phone
                        break
                    }
                }

                // Deduplicate: registered by Firebase UID, unregistered by normalized phone
                val added = if (registeredUser != null) {
                    seenUids.add(registeredUser.id)
                } else {
                    val norm = PhoneNumberUtil.normalizeToLast10Digits(primaryPhone)
                    if (norm.isNotEmpty()) seenPhones.add(norm) else true
                }
                if (!added) continue

                contacts.add(
                    ContactListItem(
                        name = contact.name,
                        phoneNumber = primaryPhone,
                        isRegistered = registeredUser != null,
                        registeredUser = registeredUser
                    )
                )
            }

            // Registered contacts first, then alphabetical
            contacts.sortWith(
                compareByDescending<ContactListItem> { it.isRegistered }.thenBy { it.name }
            )
            allContacts = contacts

            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                adapter.updateList(allContacts)
            }
        })
    }

    @SuppressLint("Range")
    private fun getDeviceContacts(): List<DeviceContact> {
        val idToEntry = linkedMapOf<Long, Pair<String, MutableList<String>>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )
        cursor?.use {
            while (it.moveToNext()) {
                val contactId = it.getLong(
                    it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                )
                val name = it.getString(
                    it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                ) ?: continue
                val phone = it.getString(
                    it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                ) ?: continue
                val entry = idToEntry.getOrPut(contactId) { Pair(name, mutableListOf()) }
                entry.second.add(phone)
            }
        }
        return idToEntry.map { (id, pair) ->
            DeviceContact(name = pair.first, phoneNumbers = pair.second.distinct(), contactId = id)
        }
    }

    // ─── Navigation to ChatActivity ──────────────────────────────────────────

    private fun openChatWithSharedContent(user: User) {
        openChatWithSharedContent(user, includeSharedPayload = true)
    }

    private fun openChatWithSharedContent(user: User, includeSharedPayload: Boolean = true) {
        if (includeSharedPayload && sharedUris.isNotEmpty()) {
            lifecycleScope.launch {
                val preparedUris = stageSharedUrisForDelivery(sharedUris)
                startChatWithPreparedShare(user, includeSharedPayload, preparedUris)
            }
            return
        }

        startChatWithPreparedShare(user, includeSharedPayload, sharedUris)
        }

    private fun startChatWithPreparedShare(
        user: User,
        includeSharedPayload: Boolean,
        preparedUris: ArrayList<String>
    ) {
        val currentUserId = repository.currentUserId ?: return
        val otherUserId = user.id
        val chatId = if (currentUserId < otherUserId) {
            "${currentUserId}_${otherUserId}"
        } else {
            "${otherUserId}_${currentUserId}"
        }

        val chatIntent = ChatActivity.newIntent(
            context = this,
            chatId = chatId,
            otherUserId = otherUserId,
            otherUsername = user.username,
            otherUserAvatar = user.profileImageUrl
        ).apply {
            if (includeSharedPayload && !sharedText.isNullOrEmpty()) {
                putExtra(ChatActivity.EXTRA_SHARED_TEXT, sharedText)
            }
            effectiveLinkPreview()?.let { preview ->
                if (includeSharedPayload) {
                    putExtra(ChatActivity.EXTRA_SHARED_LINK_PREVIEW_TITLE, preview.title)
                    putExtra(ChatActivity.EXTRA_SHARED_LINK_PREVIEW_DOMAIN, preview.domain)
                    putExtra(ChatActivity.EXTRA_SHARED_LINK_PREVIEW_THUMBNAIL_URL, preview.thumbnailUrl)
                }
            }
            if (includeSharedPayload && preparedUris.isNotEmpty()) {
                putStringArrayListExtra(ChatActivity.EXTRA_SHARED_URIS, preparedUris)
                putExtra(ChatActivity.EXTRA_SHARED_MIME_TYPE, sharedMimeType)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(chatIntent)
        finish()
    }

    private fun handleShareConfirmation() {
        if (selectedUsers.isEmpty()) {
            Toast.makeText(this, getString(R.string.share_select_recipient_hint), Toast.LENGTH_SHORT).show()
            return
        }
        if (isSendingShare) return

        val recipients = selectedUsers.values.toList()
        if (recipients.size == 1) {
            openChatWithSharedContent(recipients.first())
            return
        }

        isSendingShare = true
        binding.progressBar.visibility = View.VISIBLE
        updateShareFooter()

        lifecycleScope.launch {
            val successfulRecipients = recipients.filter { user ->
                runCatching {
                    sendSharedContentToUser(user)
                }.isSuccess
            }

            isSendingShare = false
            binding.progressBar.visibility = View.GONE
            updateShareFooter()

            if (successfulRecipients.isEmpty()) {
                Toast.makeText(this@ShareTargetActivity, getString(R.string.share_sending_failed), Toast.LENGTH_LONG).show()
                return@launch
            }

            Toast.makeText(
                this@ShareTargetActivity,
                getString(R.string.share_sent_to_multiple, successfulRecipients.size),
                Toast.LENGTH_SHORT
            ).show()
            openChatWithSharedContent(successfulRecipients.first(), includeSharedPayload = false)
        }
    }

    private suspend fun sendSharedContentToUser(user: User) {
        val target = buildChatTarget(user)
        val caption = sharedText.orEmpty()
        val preview = effectiveLinkPreview()

        if (sharedUris.isEmpty()) {
            val text = sharedText.orEmpty()
            if (text.isNotBlank()) {
                messageRepository.sendMessage(
                    chatId = target.chatId,
                    text = text,
                    otherUserId = target.otherUserId,
                    otherUsername = target.otherUsername,
                    otherUserAvatar = target.otherUserAvatar,
                    previewThumbnailUrl = preview?.thumbnailUrl,
                    previewTitle = preview?.title,
                    previewDomain = preview?.domain
                )
            }
            return
        }

        sharedUris.forEach { uriString ->
            val uri = Uri.parse(uriString)
            val mimeType = resolveSharedMimeType(uri)
            val preparedUri = materializeSharedUriForDelivery(uri, mimeType)
            when {
                mimeType.startsWith("image/") -> {
                    messageRepository.sendImageMessage(
                        chatId = target.chatId,
                        imageUri = preparedUri,
                        otherUserId = target.otherUserId,
                        otherUsername = target.otherUsername,
                        otherUserAvatar = target.otherUserAvatar,
                        caption = caption
                    )
                }
                mimeType.startsWith("video/") -> {
                    messageRepository.sendVideoMessage(
                        chatId = target.chatId,
                        videoUri = preparedUri,
                        otherUserId = target.otherUserId,
                        otherUsername = target.otherUsername,
                        otherUserAvatar = target.otherUserAvatar,
                        caption = caption
                    )
                }
                else -> {
                    messageRepository.sendDocumentMessage(
                        chatId = target.chatId,
                        documentUri = preparedUri,
                        otherUserId = target.otherUserId,
                        otherUsername = target.otherUsername,
                        otherUserAvatar = target.otherUserAvatar,
                        caption = caption
                    )
                }
            }
        }
    }

    private fun resolveSharedMimeType(uri: Uri): String {
        return contentResolver.getType(uri)
            ?: if (sharedMimeType == "*/*" && sharedUris.size > 1) "application/*" else sharedMimeType
    }

    private suspend fun stageSharedUrisForDelivery(uris: List<String>): ArrayList<String> {
        val preparedUris = ArrayList<String>(uris.size)
        uris.forEach { uriString ->
            val uri = Uri.parse(uriString)
            val mimeType = resolveSharedMimeType(uri)
            preparedUris.add(materializeSharedUriForDelivery(uri, mimeType).toString())
        }
        return preparedUris
    }

    private suspend fun materializeSharedUriForDelivery(uri: Uri, mimeType: String): Uri {
        if (uri.scheme == "file" || uri.authority == "$packageName.fileprovider") {
            return uri
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val cacheDir = File(cacheDir, "shared-imports").apply { mkdirs() }
                val extension = resolveSharedExtension(uri, mimeType)
                val tempFile = File(cacheDir, "shared_${UUID.randomUUID()}.$extension")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                } ?: return@runCatching uri

                FileProvider.getUriForFile(
                    this@ShareTargetActivity,
                    "$packageName.fileprovider",
                    tempFile
                )
            }.getOrElse {
                uri
            }
        }
    }

    private fun resolveSharedExtension(uri: Uri, mimeType: String): String {
        val fromName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.substringAfterLast('.', missingDelimiterValue = "")
                } else {
                    null
                }
            }
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        if (fromName != null) return fromName

        val normalizedMime = mimeType.takeIf { it.contains('/') && !it.endsWith("/*") }
            ?: contentResolver.getType(uri)
        return MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(normalizedMime)
            ?.takeIf { it.isNotBlank() }
            ?: "bin"
    }

    private data class ChatTarget(
        val chatId: String,
        val otherUserId: String,
        val otherUsername: String,
        val otherUserAvatar: String
    )

    private fun buildChatTarget(user: User): ChatTarget {
        val currentUserId = repository.currentUserId ?: throw IllegalStateException("User not logged in")
        val otherUserId = user.id
        val chatId = if (currentUserId < otherUserId) {
            "${currentUserId}_${otherUserId}"
        } else {
            "${otherUserId}_${currentUserId}"
        }
        return ChatTarget(
            chatId = chatId,
            otherUserId = otherUserId,
            otherUsername = user.username,
            otherUserAvatar = user.profileImageUrl
        )
    }

    private fun effectiveLinkPreview(): LinkPreviewData? {
        if (isPreviewDismissed || sharedLinkUrl.isNullOrBlank()) return null
        return sharedLinkPreview ?: LinkPreviewResolver.fallback(sharedLinkUrl!!)
    }
}
