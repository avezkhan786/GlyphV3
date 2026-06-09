package com.glyph.glyph_v3.ui.aiagent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.text.Editable
import com.bumptech.glide.Glide
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.repo.AiAgentRepository
import com.glyph.glyph_v3.data.repo.AiMode
import com.glyph.glyph_v3.databinding.ActivityAiAgentBinding
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.chat.picker.EmojiPickerPanel
import com.glyph.glyph_v3.ui.chat.picker.KeyboardHeightProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Activity for the Global AI Agent — a dedicated AI chat accessible from the chat list.
 *
 * Features:
 * • Three modes via chip tabs: Chat / Search / App Help
 * • RecyclerView with [AiAgentAdapter]
 * • Voice input via Android TTS recognition
 * • Search consent bottom sheet
 * • Copy + TTS for AI responses
 *
 * Architecture mirrors [ChatActivity] in a simplified form:
 * ViewBinding + manual ViewModel factory + lifecycleScope collection.
 */
class AiAgentActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AiAgentActivity"
        private const val PREFS_NAME = "ai_agent_prefs"

        fun newIntent(context: Context): Intent {
            return Intent(context, AiAgentActivity::class.java)
        }
    }

    private lateinit var binding: ActivityAiAgentBinding
    private lateinit var viewModel: AiAgentViewModel
    private lateinit var adapter: AiAgentAdapter

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Sound effects
    private lateinit var soundPool: SoundPool
    private var soundSentId: Int = 0
    private var soundReceivedId: Int = 0
    private var lastMessageCount = 0
    private var isFirstLoad = true

    // Emoji/Attachment/Keyboard
    private lateinit var keyboardHeightProvider: KeyboardHeightProvider
    private var isPickerMode: Boolean = false
    private var isKeyboardAnimating: Boolean = false
    private var isAtBottom: Boolean = true
    private var suppressScrollFabUntilAtBottom: Boolean = false
    private var scrollToBottomRequestToken: Long = 0L
    private var cameraPhotoUri: Uri? = null

    // Gallery launcher
    private val pickMultipleMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            handleMultipleMediaUris(uris)
        }
    }

    // Camera photo capture
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraPhotoUri != null) {
            sendImage(cameraPhotoUri!!)
        }
    }

    // Camera permission request
    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchInAppCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // In-app camera launcher
    private val inAppCameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uriString = data?.getStringExtra("RESULT_MEDIA_URI")
            if (uriString != null) {
                sendImage(Uri.parse(uriString))
            }
        }
    }

    // Document picker
    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            sendDocument(uri)
        }
    }

    // ─── Lifecycle ───────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply theme before super
        com.glyph.glyph_v3.utils.ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityAiAgentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyboardHeightProvider = KeyboardHeightProvider(this)
        setupWindowInsets()
        setupWallpaper()

        initViewModel()
        initRecyclerView()
        initInputBar()
        initToolbar()
        initModeChips()
        initTts()
        initSoundPool()
        initShortcuts()
        observeState()
        observeEvents()
        
        // Initial hint size setup
        updateInputHint(AiMode.CHAT)

        // Handle back press to clear selection/picker
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val panel = binding.emojiPickerPanel
                
                // Priority 1: If expanded picker with keyboard visible → hide keyboard only
                if (panel.isPickerVisible && panel.panelMode == EmojiPickerPanel.PanelMode.EXPANDED) {
                    val insets = ViewCompat.getRootWindowInsets(binding.root)
                    val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    
                    if (imeBottom > 0) {
                        // Keyboard is visible → hide it, keep panel expanded
                        panel.clearAllSearchFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.hideSoftInputFromWindow(panel.windowToken, 0)
                    } else {
                        // Keyboard hidden → close panel and restore keyboard
                        hideEmojiPicker()
                        binding.etMessage.requestFocus()
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(binding.etMessage, InputMethodManager.SHOW_IMPLICIT)
                    }
                } else if (panel.isPickerVisible) {
                    // Priority 2: Compact picker visible → hide it
                    hideEmojiPicker()
                } else if (binding.layoutAttachmentMenu.root.isVisible) {
                    toggleAttachmentMenu()
                } else {
                    // Priority 3: Default system back
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Deferred setup
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            setupAttachmentMenu()
            setupAttachmentMenuOverlay()
            setupEmojiPicker()
        }
    }

    private fun setupWallpaper() {
        val assetPath = "file:///android_asset/ai_agent/ai_agent_bg.jpeg"
        Glide.with(this)
            .load(assetPath)
            .centerCrop()
            .into(binding.ivWallpaper)

        // Adjust dimming overlay opacity to suit the active theme.
        // Dark theme: more contrast boost; Light/Pastel: lighter overlay so the
        // themed toolbar and bubbles remain the primary visual anchors.
        val overlayAlpha = when (com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this)) {
            com.glyph.glyph_v3.utils.ThemeManager.THEME_DARK   -> 0.35f
            com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY -> 0.15f
            else -> 0.20f  // Light and System themes
        }
        binding.wallpaperDimmingOverlay.alpha = overlayAlpha
    }

    private fun setupAttachmentMenuOverlay() {
        val overlay = binding.attachmentMenuOverlay
        val input = binding.inputBar

        val applyPadding = {
            overlay.setPadding(
                overlay.paddingLeft,
                overlay.paddingTop,
                overlay.paddingRight,
                input.height
            )
        }

        input.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyPadding()
        }

        input.post {
            applyPadding()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        soundPool.release()
        super.onDestroy()
    }

    // ─── Init ────────────────────────────────────────────

    private fun initViewModel() {
        val repository = AiAgentRepository.getInstance(this)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val factory = AiAgentViewModelFactory(repository, prefs)
        viewModel = ViewModelProvider(this, factory)[AiAgentViewModel::class.java]
    }

    private fun initRecyclerView() {
        adapter = AiAgentAdapter(
            onCopyClick = { text -> copyToClipboard(text) },
            onTtsClick = { text -> speakText(text) },
            onSourceClick = { source -> viewModel.navigateToSourceChat(source) }
        )

        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        binding.rvMessages.apply {
            this.layoutManager = layoutManager
            this.adapter = this@AiAgentActivity.adapter
            itemAnimator = null // Disable animations for snappier updates

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (suppressScrollFabUntilAtBottom) return
                    isAtBottom = isRecyclerAtBottom()
                }
            })
        }
    }

    private fun isRecyclerAtBottom(): Boolean {
        val rv = binding.rvMessages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return true
        val count = adapter.itemCount
        if (count == 0) return true
        val lastVisible = lm.findLastVisibleItemPosition()
        return lastVisible >= count - 2
    }

    private fun initInputBar() {
        // ── Send/mic button logic ─────────────────────────────────────────────
        val container = binding.btnSendContainer
        val ivSend    = binding.ivSendIcon
        val ivMic     = binding.ivMicIcon

        // Tapping the container: send when typing, voice otherwise
        container.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text)
                soundPool.play(soundSentId, 1f, 1f, 0, 0, 1f)
                binding.etMessage.text?.clear()
                isAtBottom = true
                requestScrollToBottomAfterNextPreDraw()
                hideKeyboard()
            } else {
                Toast.makeText(this, "Voice input coming soon", Toast.LENGTH_SHORT).show()
            }
        }

        // ── TextWatcher — animates mic ↔ send like ChatScreen ─────────────────
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            private var wasTyping = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val isTyping = !s.isNullOrBlank()
                if (isTyping == wasTyping) return
                wasTyping = isTyping
                if (isTyping) {
                    // Mic shrinks out, Send grows in
                    ivMic.animate()
                        .scaleX(0f).scaleY(0f).alpha(0f)
                        .setDuration(150).setInterpolator(android.view.animation.AccelerateInterpolator()).start()
                    ivSend.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(220).setStartDelay(80)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
                } else {
                    // Send shrinks out, Mic grows in
                    ivSend.animate()
                        .scaleX(0f).scaleY(0f).alpha(0f)
                        .setDuration(120).setInterpolator(android.view.animation.AccelerateInterpolator()).start()
                    ivMic.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(200).setStartDelay(60)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.5f)).start()
                }
            }
        })

        // Send on IME action
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text)
                binding.etMessage.text?.clear()
                isAtBottom = true
                requestScrollToBottomAfterNextPreDraw()
                hideKeyboard()
                true
            } else false
        }

        // ── Other pill buttons ────────────────────────────────────────────────
        binding.btnAttach.setOnClickListener {
            toggleAttachmentMenu()
        }
        binding.btnEmoji.setOnClickListener {
            toggleEmojiPicker()
        }
        binding.btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchInAppCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun initToolbar() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.ai_agent_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.clearHistory() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun initModeChips() {
        binding.chipChat.setOnClickListener {
            viewModel.switchMode(AiMode.CHAT)
            updateInputHint(AiMode.CHAT)
        }
        binding.chipSearch.setOnClickListener {
            viewModel.switchMode(AiMode.SEARCH)
            updateInputHint(AiMode.SEARCH)
        }
        binding.chipApp.setOnClickListener {
            viewModel.switchMode(AiMode.APP)
            updateInputHint(AiMode.APP)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()
        soundSentId = soundPool.load(this, R.raw.message_sent, 1)
        soundReceivedId = soundPool.load(this, R.raw.message_received, 1)
    }

    private fun initShortcuts() {
        // Each entry: Triple(displayText, iconResId, shortcutType?)
        // shortcutType != null → weekly insight shortcut (auto-routes to Search)
        // shortcutType == null → regular free-text message sent in current mode
        val shortcuts = listOf(
            // ── Smart weekly-insight shortcuts (auto-switch to Search) ──────
            Triple(
                "Summarize my chats this week",
                R.drawable.ic_chats,
                "weekly_summary"
            ),
            Triple(
                "Documents sent and received this week",
                R.drawable.ic_attachment_document,
                "weekly_documents"
            ),
            Triple(
                "Media sent and received this week",
                R.drawable.ic_gallery,
                "weekly_media"
            ),
            // ── General AI chat shortcuts ───────────────────────────────────
            Triple("I want to write a cover letter",  R.drawable.ic_attachment_document, null),
            Triple("I want help with a project for work", R.drawable.ic_chat, null),
            Triple("I want to hear a story",          R.drawable.ic_chat, null),
            Triple("I want something new to read",    R.drawable.ic_chat, null),
            Triple("I need help with finding a job",  R.drawable.ic_chat, null),
            Triple("I want to hear a joke",           R.drawable.ic_chat, null),
            Triple("I want to plan a wedding",        R.drawable.ic_chat, null),
            Triple("I want to feel less stressed",    R.drawable.ic_chat, null),
            Triple("I want to write an email",        R.drawable.ic_attachment_document, null),
        )

        val container = binding.shortcutsContainer
        container.removeAllViews()

        for ((text, iconRes, shortcutType) in shortcuts) {
            val view = layoutInflater.inflate(R.layout.item_ai_shortcut, container, false)
            val tvText = view.findViewById<android.widget.TextView>(R.id.tvShortcutText)
            val ivIcon = view.findViewById<android.widget.ImageView>(R.id.ivShortcutIcon)

            tvText.text = text
            ivIcon.setImageResource(iconRes)

            view.setOnClickListener {
                if (shortcutType != null) {
                    // Weekly insight: auto-switch to Search, send via dedicated handler
                    viewModel.sendShortcutQuery(shortcutType, text)
                    soundPool.play(soundSentId, 1f, 1f, 0, 0, 1f)
                    updateInputHint(AiMode.SEARCH)
                } else {
                    // Regular shortcut: send as a chat message in the current mode
                    viewModel.sendMessage(text)
                    soundPool.play(soundSentId, 1f, 1f, 0, 0, 1f)
                }
                isAtBottom = true
                requestScrollToBottomAfterNextPreDraw()
            }

            container.addView(view)
        }
    }

    // ─── UI Helpers ──────────────────────────────────────

    private fun setupWindowInsets() {
        // Handle smooth keyboard animation
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        isKeyboardAnimating = true
                    }
                    super.onPrepare(animation)
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: List<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
                    val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                    
                    val panel = binding.emojiPickerPanel
                    if (panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                        panel.expandForSearch(imeInsets.bottom)
                        return insets
                    }
                    
                    updateContentBottomPadding(lastIme = imeInsets.bottom, systemBottom = navBars.bottom)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        isKeyboardAnimating = false
                        val insets = ViewCompat.getRootWindowInsets(binding.root)
                        val imeBottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                        val panel = binding.emojiPickerPanel
                        val inputHasFocus = binding.etMessage.hasFocus()

                        if (imeBottom > 0 && isPickerMode && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                            panel.expandForSearch(imeBottom)
                        } else if (imeBottom > 0 && isPickerMode && !inputHasFocus) {
                            panel.expandForSearch(imeBottom)
                        } else if (imeBottom > 0 && isPickerMode && inputHasFocus) {
                            hideEmojiPicker()
                        } else if (imeBottom == 0 && panel.panelMode != EmojiPickerPanel.PanelMode.COMPACT) {
                            panel.expandForSearch(0)
                        }
                    }
                }
            }
        )

        // Handle system bars and IME insets statically
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Apply status bar padding to AppBarLayout
            binding.appBarLayout.setPadding(0, systemBars.top, 0, 0)

            // Specifically dispatch to Compose stubs
            ViewCompat.onApplyWindowInsets(binding.voiceRecorderStub, insets)

            if (!isKeyboardAnimating) {
                updateContentBottomPadding(lastIme = ime.bottom, systemBottom = navBars.bottom)
            }

            insets
        }
    }

    private fun updateContentBottomPadding(lastIme: Int, systemBottom: Int) {
        val gapPx = (3 * resources.displayMetrics.density).toInt()
        
        if (!::keyboardHeightProvider.isInitialized) {
            val targetPadding = maxOf(lastIme, systemBottom) + gapPx
            binding.inputBar.setPadding(
                binding.inputBar.paddingLeft,
                binding.inputBar.paddingTop,
                binding.inputBar.paddingRight,
                targetPadding
            )
            return
        }

        val pickerHeight = keyboardHeightProvider.getKeyboardHeight() + systemBottom
        val effectiveIme = if (isPickerMode) {
            maxOf(lastIme, pickerHeight)
        } else {
            lastIme
        }

        val targetPadding = maxOf(effectiveIme, systemBottom) + gapPx
        
        if (binding.inputBar.paddingBottom != targetPadding) {
            val wasAtBottom = isAtBottom
            binding.inputBar.setPadding(
                binding.inputBar.paddingLeft,
                binding.inputBar.paddingTop,
                binding.inputBar.paddingRight,
                targetPadding
            )
            if (wasAtBottom) {
                requestScrollToBottomAfterNextPreDraw()
            }
        }
    }

    private fun requestScrollToBottomAfterNextPreDraw() {
        val rv = binding.rvMessages
        if (adapter.itemCount <= 0) {
            isAtBottom = true
            suppressScrollFabUntilAtBottom = false
            return
        }

        suppressScrollFabUntilAtBottom = true
        val token = ++scrollToBottomRequestToken

        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (token != scrollToBottomRequestToken) {
                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }

                val countNow = adapter.itemCount
                if (countNow <= 0) {
                    rv.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }

                rv.viewTreeObserver.removeOnPreDrawListener(this)
                val target = countNow - 1
                rv.scrollToPosition(target)

                rv.post {
                    if (token != scrollToBottomRequestToken) return@post
                    ensureLastItemFullyVisibleAboveBottomUi(target)
                    suppressScrollFabUntilAtBottom = false
                    isAtBottom = true
                }
                return true
            }
        }

        rv.viewTreeObserver.addOnPreDrawListener(listener)
        rv.requestLayout()
        rv.invalidate()
    }

    private fun ensureLastItemFullyVisibleAboveBottomUi(lastAdapterPosition: Int) {
        val rv = binding.rvMessages
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val lastView = lm.findViewByPosition(lastAdapterPosition) ?: return

        val rvLoc = IntArray(2)
        rv.getLocationOnScreen(rvLoc)
        val rvTopOnScreen = rvLoc[1]

        val blockingTopOnScreen = computeBottomBlockingTopOnScreen()
        val safeBottomInRv = (blockingTopOnScreen - rvTopOnScreen).coerceIn(0, rv.height)

        val targetBottom = (safeBottomInRv - rv.paddingBottom).coerceAtLeast(0)
        val delta = lastView.bottom - targetBottom
        if (delta > 0) {
            rv.scrollBy(0, delta)
        }
    }

    private fun computeBottomBlockingTopOnScreen(): Int {
        val candidates = ArrayList<View>(3)
        candidates.add(binding.inputBar)

        if (binding.layoutAttachmentMenu.root.visibility == View.VISIBLE) {
            candidates.add(binding.layoutAttachmentMenu.root)
        }
        
        val panel = binding.emojiPickerPanel
        if (panel.visibility == View.VISIBLE && panel.isPickerVisible) {
            candidates.add(panel)
        }

        var minTop = Int.MAX_VALUE
        val tmp = IntArray(2)
        for (v in candidates) {
            v.getLocationOnScreen(tmp)
            val top = tmp[1]
            if (top < minTop) minTop = top
        }
        return minTop
    }

    private fun setupEmojiPicker() {
        val panel = binding.emojiPickerPanel
        panel.attachToActivity(this)
        panel.setPickerHeight(keyboardHeightProvider.getKeyboardHeight(), getNavBarHeight())

        keyboardHeightProvider.onHeightChanged = { heightPx ->
            panel.setPickerHeight(heightPx, getNavBarHeight())
        }
        keyboardHeightProvider.start()

        panel.onSearchFocusChanged = { hasFocus ->
            if (hasFocus && panel.isPickerVisible) {
                binding.etMessage.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(panel, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        panel.onModeChanged = { mode ->
            when (mode) {
                EmojiPickerPanel.PanelMode.EXPANDED -> binding.inputBar.visibility = View.GONE
                EmojiPickerPanel.PanelMode.HALF -> binding.inputBar.visibility = View.GONE
                EmojiPickerPanel.PanelMode.COMPACT -> {
                    binding.inputBar.visibility = View.VISIBLE
                    updateContentBottomPadding(lastIme = 0, systemBottom = getNavBarHeight())
                }
            }
        }

        panel.onDragClose = {
            panel.clearAllSearchFocus()
            isPickerMode = false
            binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
            panel.collapseToCompact()
            panel.hide()
            binding.inputBar.visibility = View.VISIBLE
            binding.etMessage.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etMessage, InputMethodManager.SHOW_IMPLICIT)
        }

        panel.onCollapseToCompactRequested = {
            collapseExpandedSearch()
        }

        panel.onSystemEmojiSelected = { emoji ->
            val start = binding.etMessage.selectionStart
            val end = binding.etMessage.selectionEnd
            val text = binding.etMessage.text
            if (text != null) {
                text.replace(start, end, emoji)
            } else {
                binding.etMessage.setText(emoji)
                binding.etMessage.setSelection(emoji.length)
            }
        }
        
        // klipy selection logic would go here if supported
    }

    private fun toggleEmojiPicker() {
        val panel = binding.emojiPickerPanel
        if (panel.panelMode == EmojiPickerPanel.PanelMode.EXPANDED) {
            collapseExpandedSearch()
            return
        }
        if (isPickerMode) {
            binding.etMessage.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.etMessage, InputMethodManager.SHOW_IMPLICIT)
        } else {
            showEmojiPicker()
        }
    }

    private fun showEmojiPicker() {
        val currentIme = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.ime())
            ?.bottom ?: 0
        val wasKeyboardVisible = currentIme > 0 || keyboardHeightProvider.isKeyboardVisible
        isPickerMode = true
        binding.btnEmoji.setImageResource(R.drawable.ic_keyboard)
        
        if (!wasKeyboardVisible) {
            TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)
        }

        updateContentBottomPadding(lastIme = 0, systemBottom = getNavBarHeight())
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)

        binding.emojiPickerPanel.postDelayed({
            if (!isFinishing && !isDestroyed) {
                val navBar = getNavBarHeight()
                binding.emojiPickerPanel.setPickerHeight(keyboardHeightProvider.getKeyboardHeight(), navBar)
                binding.emojiPickerPanel.show(animate = !wasKeyboardVisible)
                requestScrollToBottomAfterNextPreDraw()
                updateContentBottomPadding(lastIme = 0, systemBottom = navBar)
            }
        }, 150)
    }

    private fun hideEmojiPicker() {
        val insets = ViewCompat.getRootWindowInsets(binding.root)
        val currentIme = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        
        if (currentIme == 0) {
            TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)
        }

        isPickerMode = false
        binding.emojiPickerPanel.hide()
        binding.btnEmoji.setImageResource(R.drawable.ic_emoji)
        binding.inputBar.visibility = View.VISIBLE
        updateContentBottomPadding(lastIme = currentIme, systemBottom = getNavBarHeight())
    }

    private fun collapseExpandedSearch() {
        val panel = binding.emojiPickerPanel
        if (panel.panelMode != EmojiPickerPanel.PanelMode.EXPANDED) return
        panel.clearAllSearchFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(panel.windowToken, 0)
        panel.collapseToCompact()
    }

    private fun getNavBarHeight(): Int {
        val insets = ViewCompat.getRootWindowInsets(binding.root) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
    }

    private fun setupAttachmentMenu() {
        with(binding.layoutAttachmentMenu) {
            optionGallery.setOnClickListener {
                hideAttachmentMenu { pickMultipleMediaLauncher.launch("*/*") }
            }
            optionCamera.setOnClickListener {
                hideAttachmentMenu {
                    if (ContextCompat.checkSelfPermission(this@AiAgentActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchInAppCamera()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }
            optionDocument.setOnClickListener {
                hideAttachmentMenu { pickDocumentLauncher.launch("*/*") }
            }
            optionAudio.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@AiAgentActivity, "Audio sharing coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionLocation.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@AiAgentActivity, "Location sharing coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionContact.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@AiAgentActivity, "Contact sharing coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionPoll.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@AiAgentActivity, "Polls coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionPayment.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@AiAgentActivity, "Payments coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionEvent.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@AiAgentActivity, "Events coming soon", Toast.LENGTH_SHORT).show()
                }
            }
            optionAiImages.setOnClickListener {
                hideAttachmentMenu {
                    Toast.makeText(this@AiAgentActivity, "AI Image generation coming soon", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleAttachmentMenu() {
        if (binding.layoutAttachmentMenu.root.visibility == View.VISIBLE) {
            hideAttachmentMenu()
        } else {
            showAttachmentMenu()
        }
    }

    private fun showAttachmentMenu() {
        WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
        binding.layoutAttachmentMenu.root.visibility = View.VISIBLE
        requestScrollToBottomAfterNextPreDraw()
        animateAttachmentMenuIn()
    }

    private fun hideAttachmentMenu(onComplete: (() -> Unit)? = null) {
        animateAttachmentMenuOut {
            binding.layoutAttachmentMenu.root.visibility = View.GONE
            onComplete?.invoke()
        }
    }

    private fun animateAttachmentMenuIn() {
        val menuBinding = binding.layoutAttachmentMenu
        val gridItems = listOf(
            menuBinding.optionGallery, menuBinding.optionCamera, menuBinding.optionDocument,
            menuBinding.optionAudio, menuBinding.optionLocation, menuBinding.optionContact,
            menuBinding.optionPoll, menuBinding.optionPayment, menuBinding.optionEvent, menuBinding.optionAiImages
        )
        gridItems.forEach { item ->
            item.alpha = 0f
            item.scaleX = 0.8f
            item.scaleY = 0.8f
        }
        gridItems.forEachIndexed { index, item ->
            item.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250)
                .setStartDelay(index * 20L)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun animateAttachmentMenuOut(onComplete: () -> Unit) {
        val menuBinding = binding.layoutAttachmentMenu
        menuBinding.root.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(150)
            .withEndAction {
                menuBinding.root.alpha = 1f
                menuBinding.root.scaleX = 1f
                menuBinding.root.scaleY = 1f
                onComplete()
            }
            .start()
    }

    private fun launchInAppCamera() {
        val intent = Intent(this, com.glyph.glyph_v3.ui.camera.CameraActivity::class.java)
        inAppCameraLauncher.launch(intent)
    }

    private fun handleMultipleMediaUris(uris: List<Uri>) {
        // AI component doesn't support media yet, show placeholder
        Toast.makeText(this, "AI Agent: Media support coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun sendImage(uri: Uri) {
        Toast.makeText(this, "AI Agent: Image support coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun sendDocument(uri: Uri) {
        Toast.makeText(this, "AI Agent: Document support coming soon", Toast.LENGTH_SHORT).show()
    }

    // ─── Observers ───────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // Update messages list
                    val currentMessages = state.messages
                    
                    if (isFirstLoad) {
                        lastMessageCount = currentMessages.size
                        isFirstLoad = false
                    } else if (currentMessages.size > lastMessageCount) {
                        val lastMsg = currentMessages.lastOrNull()
                        if (lastMsg?.role == "model") {
                            soundPool.play(soundReceivedId, 1f, 1f, 0, 0, 1f)
                        }
                    }
                    lastMessageCount = currentMessages.size

                    adapter.submitList(currentMessages) {
                        // Scroll to bottom after list update
                        if (currentMessages.isNotEmpty()) {
                            binding.rvMessages.scrollToPosition(currentMessages.size - 1)
                        }
                    }

                    // Empty state visibility
                    binding.emptyStateContainer.visibility =
                        if (currentMessages.isEmpty()) View.VISIBLE else View.GONE

                    // Loading indicator
                    binding.loadingIndicator.visibility =
                        if (state.isLoading) View.VISIBLE else View.GONE

                    // Mode chips sync
                    syncModeChips(state.currentMode)

                    // Consent bottom-sheet
                    if (state.showSearchConsent) {
                        showSearchConsentSheet()
                    }

                    // Empty state
                    // Optionally show a welcome message when there are no messages
                }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is AiAgentEvent.ShowError -> {
                            Toast.makeText(this@AiAgentActivity, event.message, Toast.LENGTH_SHORT).show()
                        }
                        is AiAgentEvent.NavigateToChat -> {
                            navigateToChat(event.chatId, event.otherUserId, event.otherUsername)
                        }
                        is AiAgentEvent.ScrollToBottom -> {
                            isAtBottom = true
                            requestScrollToBottomAfterNextPreDraw()
                        }
                        is AiAgentEvent.HistoryCleared -> {
                            lastMessageCount = 0
                            Toast.makeText(
                                this@AiAgentActivity,
                                R.string.ai_agent_history_cleared,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is AiAgentEvent.ModeAutoSwitched -> {
                            // Sync the chip UI and input hint to the auto-routed mode
                            syncModeChips(event.newMode)
                            updateInputHint(event.newMode)
                        }
                    }
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────

    private fun syncModeChips(mode: AiMode) {
        val chipId = when (mode) {
            AiMode.CHAT -> R.id.chipChat
            AiMode.SEARCH -> R.id.chipSearch
            AiMode.APP -> R.id.chipApp
        }
        val chip = findViewById<Chip>(chipId)
        if (chip != null && !chip.isChecked) {
            chip.isChecked = true
        }
    }

    private fun updateInputHint(mode: AiMode) {
        val hintStr = when (mode) {
            AiMode.CHAT -> getString(R.string.ai_agent_input_hint)
            AiMode.SEARCH -> "Search your chats…"
            AiMode.APP -> "Ask about app features…"
        }
        val spannableHint = SpannableString(hintStr)
        spannableHint.setSpan(
            AbsoluteSizeSpan(14, true), // 14sp for hint regardless of EditText textSize
            0,
            hintStr.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.etMessage.hint = spannableHint
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Response", text))
        Toast.makeText(this, R.string.ai_agent_copied, Toast.LENGTH_SHORT).show()
    }

    private fun speakText(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_response")
        } else {
            Toast.makeText(this, "Text-to-speech not ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSearchConsentSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_search_consent, null)

        view.findViewById<View>(R.id.btnAllow)?.setOnClickListener {
            viewModel.grantSearchConsent()
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnDeny)?.setOnClickListener {
            viewModel.dismissSearchConsent()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.setOnDismissListener { viewModel.dismissSearchConsent() }
        dialog.show()
    }

    private fun navigateToChat(chatId: String, otherUserId: String, otherUsername: String) {
        val intent = ChatActivity.newIntent(this, chatId, otherUserId, otherUsername)
        startActivity(intent)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }
}
