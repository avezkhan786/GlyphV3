package com.glyph.glyph_v3.ui.status

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.activity.OnBackPressedCallback
import androidx.viewpager2.widget.ViewPager2
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.StatusType
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.models.UserStatusGroup
import com.glyph.glyph_v3.data.models.ViewerInfo
import com.glyph.glyph_v3.data.repo.StatusRepository
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider

class StatusFragment : Fragment() {

    companion object {
        private const val STATUS_TAB_INDEX = 1
    }

    private lateinit var viewModel: StatusViewModel
    private var currentScreen by mutableStateOf<StatusScreen>(StatusScreen.List)
    private var viewersStatusId by mutableStateOf<String?>(null)
    private var viewers by mutableStateOf<List<ViewerInfo>>(emptyList())
    private var pagerCallback: ViewPager2.OnPageChangeCallback? = null

    private val viewerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                viewersStatusId != null -> closeViewersSheet()
                isStatusViewerScreen(currentScreen) -> closeStatusViewer()
            }
        }
    }

    /** Multi-select media picker (images and videos) */
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            pendingMediaUris = uris
        }
    }

    /** Holds selected URIs until the Compose screen reads them */
    private var pendingMediaUris by mutableStateOf<List<Uri>>(emptyList())

    private fun isStatusViewerScreen(screen: StatusScreen): Boolean =
        screen is StatusScreen.ViewMine || screen is StatusScreen.ViewContact

    private fun closeViewersSheet() {
        viewersStatusId = null
        viewers = emptyList()
    }

    private fun closeStatusViewer() {
        val wasViewerVisible = isStatusViewerScreen(currentScreen)
        closeViewersSheet()
        if (wasViewerVisible) {
            currentScreen = StatusScreen.List
            setFullScreen(false)
        }
    }

    /**
     * Open a portal official message (Phase 18 F4). If a deep link is present and
     * is an http(s)/app-scheme URI, launch it; otherwise surface the body text.
     */
    private fun shouldHideHostChrome(screen: StatusScreen): Boolean = when (screen) {
        StatusScreen.CreateText,
        StatusScreen.VoiceStatus,
        is StatusScreen.MediaPreview,
        is StatusScreen.CollagePreview,
        StatusScreen.ViewMine,
        is StatusScreen.ViewContact -> true
        else -> false
    }

    /** Hide bottom nav and remove padding for immersive creation screens. */
    private fun setFullScreen(fullScreen: Boolean) {
        val act = activity ?: return
        val bottomNav = act.findViewById<View>(R.id.bottom_navigation) ?: return
        val viewPager = act.findViewById<ViewPager2>(R.id.main_view_pager) ?: return
        if (fullScreen) {
            bottomNav.visibility = View.GONE
            viewPager.setPadding(0, 0, 0, 0)
        } else {
            bottomNav.visibility = View.VISIBLE
            // Directly restore ViewPager padding after the bottom nav is laid out,
            // instead of relying on a layout listener that may not fire reliably.
            bottomNav.post {
                if (!isAdded || activity == null) return@post
                val gapPx = (13 * act.resources.displayMetrics.density).toInt()
                val navHeight = bottomNav.height
                if (navHeight > 0) {
                    viewPager.setPadding(0, 0, 0, navHeight + gapPx)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[StatusViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                GlyphThemeProvider {
                    val uiState by viewModel.uiState.collectAsState()
                    val privacyState by viewModel.privacyState.collectAsState()
                    val pendingOpenRequest by StatusNavigationBus.pendingRequest.collectAsState()

                    // Audience selector sheet
                    var showAudienceSelector by remember { mutableStateOf(false) }

                    // Audience label derived from privacy state
                    val audienceLabel = audienceLabelFor(
                        privacyState.mode,
                        privacyState.excludedContacts.size,
                        privacyState.includedContacts.size
                    )

                    // When media URIs are picked, navigate to preview
                    if (pendingMediaUris.isNotEmpty() && currentScreen is StatusScreen.List) {
                        val uris = pendingMediaUris.toList()
                        pendingMediaUris = emptyList()
                        // camera picker uses "image/*", so all pending URIs are images
                        currentScreen = StatusScreen.MediaPreview(
                            uris.map { MediaItem(it, StatusType.IMAGE) }
                        )
                    }

                    // Manage full-screen immersive mode for creation screens
                    val shouldHideHostChrome = shouldHideHostChrome(currentScreen)
                    LaunchedEffect(shouldHideHostChrome) {
                        setFullScreen(shouldHideHostChrome)
                    }

                    LaunchedEffect(currentScreen, viewersStatusId) {
                        viewerBackCallback.isEnabled =
                            viewersStatusId != null ||
                                currentScreen is StatusScreen.ViewMine ||
                                currentScreen is StatusScreen.ViewContact
                    }

                    LaunchedEffect(pendingOpenRequest, uiState.contactStatusGroups) {
                        val request = pendingOpenRequest ?: return@LaunchedEffect
                        val matchingGroup = uiState.contactStatusGroups.firstOrNull {
                            it.userId == request.userId
                        } ?: return@LaunchedEffect
                        closeViewersSheet()
                        currentScreen = StatusScreen.ViewContact(matchingGroup)
                        StatusNavigationBus.clear(request)
                    }

                    Box {
                        when (val screen = currentScreen) {
                            is StatusScreen.List,
                            is StatusScreen.AddStatus,
                            is StatusScreen.LayoutPicker -> {
                                StatusListScreen(
                                    uiState = uiState,
                                    onMyStatusClick = {
                                        if (uiState.myStatuses.isNotEmpty()) {
                                            currentScreen = StatusScreen.ViewMine
                                        }
                                    },
                                    onAddTextStatus = {
                                        currentScreen = StatusScreen.CreateText
                                    },
                                    onAddMediaStatus = {
                                        currentScreen = StatusScreen.AddStatus
                                    },
                                    onContactStatusClick = { group ->
                                        currentScreen = StatusScreen.ViewContact(group)
                                    },
                                    onOfficialStatusClick = { group ->
                                        currentScreen = StatusScreen.ViewContact(group)
                                    }
                                )

                                uiState.error?.let { error ->
                                    Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                                    viewModel.clearError()
                                }
                            }

                            is StatusScreen.CreateText -> {
                                TextStatusScreen(
                                    isUploading = uiState.isUploading,
                                    onPost = { text, bgColor, fontStyle ->
                                        viewModel.uploadTextStatus(text, bgColor, fontStyle)
                                        currentScreen = StatusScreen.List
                                    },
                                    onClose = { currentScreen = StatusScreen.List },
                                    onAudienceClick = { showAudienceSelector = true },
                                    audienceLabel = audienceLabel,
                                    onPickerMediaReady = { mediaItem ->
                                        currentScreen = StatusScreen.MediaPreview(items = listOf(mediaItem))
                                    }
                                )
                            }

                            is StatusScreen.VoiceStatus -> {
                                VoiceStatusScreen(
                                    isUploading = uiState.isUploading,
                                    onPost = { audioFile ->
                                        viewModel.uploadVoiceStatus(audioFile)
                                        currentScreen = StatusScreen.List
                                    },
                                    onClose = { currentScreen = StatusScreen.AddStatus },
                                    onAudienceClick = { showAudienceSelector = true },
                                    audienceLabel = audienceLabel
                                )
                            }

                            is StatusScreen.MediaPreview -> {
                                MediaPreviewScreen(
                                    mediaItems = screen.items,
                                    isUploading = uiState.isUploading,
                                    uploadProgress = uiState.uploadProgress,
                                    uploadStage = uiState.uploadStage,
                                    uploadIndex = uiState.uploadIndex,
                                    uploadTotal = uiState.uploadTotal,
                                    onSend = { items ->
                                        viewModel.uploadMultipleMediaStatuses(items)
                                        currentScreen = StatusScreen.List
                                    },
                                    onClose = { currentScreen = StatusScreen.List },
                                    openPickerOnStart = screen.openPickerOnStart
                                )
                            }

                            is StatusScreen.CollagePreview -> {
                                Unit
                            }

                            is StatusScreen.ViewMine -> {
                                StatusViewerScreen(
                                    statuses = uiState.myStatuses,
                                    ownerName = uiState.myUsername,
                                    ownerAvatarUrl = uiState.myAvatarUrl,
                                    isMine = true,
                                    onViewStatus = {},
                                    onDeleteStatus = { statusId ->
                                        viewModel.deleteStatus(statusId)
                                        if (uiState.myStatuses.size <= 1) {
                                            currentScreen = StatusScreen.List
                                        }
                                    },
                                    onClose = { closeStatusViewer() },
                                    onBack = { closeStatusViewer() },
                                    onViewersClick = { statusId ->
                                        viewersStatusId = statusId
                                    },
                                    isViewersSheetOpen = viewersStatusId != null
                                )
                            }

                            is StatusScreen.ViewContact -> {
                                val isOfficial = screen.group.isOfficial
                                StatusViewerScreen(
                                    statuses = screen.group.statuses,
                                    ownerName = screen.group.username,
                                    ownerAvatarUrl = screen.group.profileImageUrl,
                                    isMine = false,
                                    // Official content is read-only: never write back
                                    // view/reply/like state to the portal-owned doc.
                                    onViewStatus = { if (!isOfficial) viewModel.markViewed(it) },
                                    onDeleteStatus = {},
                                    onClose = { closeStatusViewer() },
                                    onBack = { closeStatusViewer() },
                                    onReply = { status, replyText ->
                                        if (!isOfficial) viewModel.sendStatusReply(status, replyText)
                                    },
                                    onLikeStatus = { status ->
                                        if (!isOfficial) viewModel.sendStatusLike(status)
                                    },
                                    isReplying = uiState.isUploading || uiState.isReplying, // covers both status post and reply
                                    replyStatusId = uiState.replyStatusId,
                                    isViewersSheetOpen = viewersStatusId != null
                                )
                            }
                        }

                        if (currentScreen is StatusScreen.AddStatus) {
                            AddStatusSheet(
                                onDismiss = { currentScreen = StatusScreen.List },
                                onTextStatus = {
                                    currentScreen = StatusScreen.CreateText
                                },
                                onVoiceStatus = {
                                    currentScreen = StatusScreen.VoiceStatus
                                },
                                onLayoutStatus = { _ ->
                                    currentScreen = StatusScreen.LayoutPicker
                                },
                                onMediaSelected = { galleryItems ->
                                    currentScreen = StatusScreen.MediaPreview(
                                        galleryItems.map { item ->
                                            MediaItem(
                                                uri  = item.uri,
                                                // Use the authoritative MediaStore MIME type.
                                                // isVideo is derived from mimeType so both are consistent.
                                                type = if (item.mimeType.startsWith("video/"))
                                                           StatusType.VIDEO
                                                       else
                                                           StatusType.IMAGE
                                            )
                                        }
                                    )
                                },
                                onCameraClick = {
                                    pickMediaLauncher.launch("image/*")
                                    currentScreen = StatusScreen.List
                                },
                                onGifStatus = {
                                    currentScreen = StatusScreen.MediaPreview(
                                        items = emptyList(),
                                        openPickerOnStart = true
                                    )
                                }
                            )
                        }

                        if (currentScreen is StatusScreen.LayoutPicker) {
                            LayoutPickerScreen(
                                onDone = { selectedUris ->
                                    currentScreen = StatusScreen.CollagePreview(selectedUris)
                                },
                                onClose = { currentScreen = StatusScreen.AddStatus }
                            )
                        }

                        (currentScreen as? StatusScreen.CollagePreview)?.let { screen ->
                            LayoutStatusEditorScreen(
                                imageUris = screen.uris,
                                isUploading = uiState.isUploading,
                                onSend = { renderedUri, caption ->
                                    viewModel.uploadMediaStatus(renderedUri, StatusType.IMAGE, caption)
                                    currentScreen = StatusScreen.List
                                },
                                onBack = { currentScreen = StatusScreen.LayoutPicker }
                            )
                        }

                    }

                    // Audience selector bottom sheet
                    if (showAudienceSelector) {
                        AudienceSelectorSheet(
                            currentMode = privacyState.mode,
                            excludedCount = privacyState.excludedContacts.size,
                            includedCount = privacyState.includedContacts.size,
                            onModeSelected = { mode ->
                                viewModel.updatePrivacyMode(mode)
                                viewModel.savePrivacySettings()
                                showAudienceSelector = false
                            },
                            onDismiss = { showAudienceSelector = false }
                        )
                    }

                    // Viewers bottom sheet
                    viewersStatusId?.let { statusId ->
                        val expectedViewerCount = remember(statusId, uiState.myStatuses) {
                            uiState.myStatuses.firstOrNull { it.id == statusId }?.viewerIds?.size ?: 0
                        }
                        StatusViewersSheet(
                            viewers = viewers,
                            expectedCount = expectedViewerCount,
                            onDismiss = { viewersStatusId = null }
                        )

                        // Load viewers
                        androidx.compose.runtime.LaunchedEffect(statusId) {
                            StatusRepository.getStatusViewers(statusId).collect { result ->
                                viewers = result
                            }
                        }
                    }

                    // Load privacy settings on first composition
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        viewModel.loadPrivacySettings()
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, viewerBackCallback)

        activity?.findViewById<ViewPager2>(R.id.main_view_pager)?.let { pager ->
            val callback = object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    if (position != STATUS_TAB_INDEX) {
                        closeStatusViewer()
                    }
                }
            }
            pager.registerOnPageChangeCallback(callback)
            pagerCallback = callback
        }
    }

    override fun onDestroyView() {
        activity?.findViewById<ViewPager2>(R.id.main_view_pager)?.let { pager ->
            pagerCallback?.let(pager::unregisterOnPageChangeCallback)
        }
        pagerCallback = null
        super.onDestroyView()
        // Restore bottom nav when fragment is removed
        setFullScreen(false)
    }
}

private sealed class StatusScreen {
    data object List : StatusScreen()
    data object CreateText : StatusScreen()
    data object AddStatus : StatusScreen()
    data object LayoutPicker : StatusScreen()
    data class CollagePreview(val uris: kotlin.collections.List<Uri>) : StatusScreen()
    data object VoiceStatus : StatusScreen()
    data class MediaPreview(val items: kotlin.collections.List<MediaItem>, val openPickerOnStart: Boolean = false) : StatusScreen()
    data object ViewMine : StatusScreen()
    data class ViewContact(val group: UserStatusGroup) : StatusScreen()
}
