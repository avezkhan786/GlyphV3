package com.glyph.glyph_v3.ui.groups

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yalantis.ucrop.UCrop
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.data.preferences.ChatSettingsDataStore
import com.glyph.glyph_v3.ui.chat.contactinfo.AdvancedChatPrivacyActivity
import com.glyph.glyph_v3.ui.chat.contactinfo.ChatNotificationsActivity
import com.glyph.glyph_v3.ui.chat.contactinfo.DisappearingMessagesActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.ui.chat.picker.EmojiPickerPanel
import com.glyph.glyph_v3.ui.chat.picker.KeyboardHeightProvider
import com.glyph.glyph_v3.utils.ThemeManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GroupWaBackground = Color(0xFF0B1014)
private val GroupWaSurface = Color(0xFF111B21)
private val GroupWaSurfaceAlt = Color(0xFF1F2C33)
private val GroupWaDivider = Color(0xFF202C33)
private val GroupWaTextPrimary = Color(0xFFE9EDEF)
private val GroupWaTextSecondary = Color(0xFF8696A0)
private val GroupWaGreen = Color(0xFF25D366)
private val GroupWaDanger = Color(0xFFFF5C6C)
private val GroupWaTileBorder = Color(0xFF233138)
private val GroupWaAdminBackground = Color(0xFF143127)
private val GroupWaAdminText = Color(0xFF8DE39C)
private val GroupWaIconBackground = Color(0xFF4A3828)
private val GroupWaIconTint = Color(0xFFF6CF74)

class GroupInfoActivity : AppCompatActivity() {

    // ── Activity-level pick→crop launchers (mirrors CreateGroupActivity pattern) ──

    private val cropIconLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val cropped = UCrop.getOutput(result.data!!)
            if (cropped != null) viewModel.changeIcon(cropped)
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(this, "Crop failed: ${UCrop.getError(result.data!!)?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickIconLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { startGroupIconCrop(it) } }

    private var tempCameraUri: Uri? = null
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success && tempCameraUri != null) startGroupIconCrop(tempCameraUri!!) }

    private fun startGroupIconCamera() {
        try {
            val photoFile = File(cacheDir, "group_icon_camera_${System.currentTimeMillis()}.jpg")
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", photoFile)
            tempCameraUri = photoUri
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGroupIconCrop(source: Uri) {
        val dest = Uri.fromFile(File(cacheDir, "group_icon_crop_${System.currentTimeMillis()}.jpg"))
        val options = UCrop.Options().apply {
            setCircleDimmedLayer(true)
            setShowCropGrid(false)
            setCompressionQuality(90)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(true)
            setToolbarColor(ContextCompat.getColor(this@GroupInfoActivity, R.color.black))
            setStatusBarColor(ContextCompat.getColor(this@GroupInfoActivity, R.color.black))
            setToolbarWidgetColor(ContextCompat.getColor(this@GroupInfoActivity, R.color.white))
        }
        UCrop.of(source, dest)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(1080, 1080)
            .withOptions(options)
            .getIntent(this)
            .also { cropIconLauncher.launch(it) }
    }

    private val viewModel: GroupInfoViewModel by viewModels {
        val app = applicationContext as GlyphApplication
        val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""
        GroupInfoViewModelFactory(
            application = app,
            chatId = chatId,
            groupChatRepository = app.getOrCreateGroupChatRepository()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val navBarColorValue = TypedValue()
        theme.resolveAttribute(R.attr.glyphBackground, navBarColorValue, true)
        window.navigationBarColor = navBarColorValue.data
        // Edge-to-edge: decor extends behind nav bar so EmojiPickerPanel can
        // be positioned at the very bottom with its own bottom-inset padding.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Let Compose handle IME via imePadding(); stop the system from
        // auto-resizing the window (which would conflict with imePadding).
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContent {
            GlyphThemeProvider {
                GroupInfoScreen(
                    chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: "",
                    viewModel = viewModel,
                    onClose = { finish() },
                    openAddMembersInitially = intent.getBooleanExtra(EXTRA_OPEN_ADD_MEMBERS, false),
                    onTakeGroupIconPhoto = { startGroupIconCamera() },
                    onPickGroupIconFromGallery = { pickIconLauncher.launch("image/*") },
                    onRemoveGroupIcon = { viewModel.removeIcon() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_OPEN_ADD_MEMBERS = "extra_open_add_members"

        fun newIntent(
            context: android.content.Context,
            chatId: String,
            openAddMembers: Boolean = false
        ): Intent {
            return Intent(context, GroupInfoActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_OPEN_ADD_MEMBERS, openAddMembers)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupInfoScreen(
    chatId: String,
    viewModel: GroupInfoViewModel,
    onClose: () -> Unit,
    openAddMembersInitially: Boolean,
    onTakeGroupIconPhoto: () -> Unit = {},
    onPickGroupIconFromGallery: () -> Unit = {},
    onRemoveGroupIcon: () -> Unit = {}
) {
    // Theme-aware color aliases — shadow the file-level hardcoded constants in this composable.
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    @Suppress("LocalVariableName")
    val GroupWaDivider = glyphTheme.divider

    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var renameDialogOpen by remember { mutableStateOf(false) }
    var descriptionEditorOpen by remember { mutableStateOf(false) }
    var addMembersOpen by remember { mutableStateOf(openAddMembersInitially) }
    var groupPermissionsOpen by remember { mutableStateOf(false) }
    var leaveConfirmOpen by remember { mutableStateOf(false) }
    var encryptionDialogOpen by remember { mutableStateOf(false) }
    var actionMember by remember { mutableStateOf<GroupInfoViewModel.MemberRow?>(null) }
    var showIconOptionsSheet by remember { mutableStateOf(false) }

    var messagesMuted by remember(chatId) {
        mutableStateOf(ChatSettingsDataStore.isMessagesMuted(context, chatId))
    }
    var disappearingTimer by remember(chatId) {
        mutableStateOf(ChatSettingsDataStore.getDisappearingTimer(context, chatId))
    }
    var chatLockEnabled by remember(chatId) {
        mutableStateOf(ChatSettingsDataStore.isChatLocked(context, chatId))
    }
    var advancedPrivacyEnabled by remember(chatId) {
        mutableStateOf(ChatSettingsDataStore.isAdvancedPrivacyEnabled(context, chatId))
    }
    var translateEnabled by remember(chatId) {
        mutableStateOf(ChatSettingsDataStore.isTranslateEnabled(context, chatId))
    }
    var mediaVisible by remember(chatId) {
        mutableStateOf(ChatSettingsDataStore.isMediaVisible(context, chatId))
    }
    var groupPermissions by remember(chatId) {
        mutableStateOf(ChatSettingsDataStore.getGroupPermissions(context, chatId))
    }

    fun refreshPersistedSettings() {
        messagesMuted = ChatSettingsDataStore.isMessagesMuted(context, chatId)
        disappearingTimer = ChatSettingsDataStore.getDisappearingTimer(context, chatId)
        chatLockEnabled = ChatSettingsDataStore.isChatLocked(context, chatId)
        advancedPrivacyEnabled = ChatSettingsDataStore.isAdvancedPrivacyEnabled(context, chatId)
        translateEnabled = ChatSettingsDataStore.isTranslateEnabled(context, chatId)
        mediaVisible = ChatSettingsDataStore.isMediaVisible(context, chatId)
        groupPermissions = ChatSettingsDataStore.getGroupPermissions(context, chatId)
    }

    DisposableEffect(lifecycleOwner, chatId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPersistedSettings()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.consumeError()
            }
        }
    }
    LaunchedEffect(state.infoMessage) {
        state.infoMessage?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.consumeInfo()
            }
        }
    }
    LaunchedEffect(state.didLeave) {
        if (state.didLeave) onClose()
    }

    BackHandler {
        when {
            descriptionEditorOpen -> descriptionEditorOpen = false
            addMembersOpen -> addMembersOpen = false
            groupPermissionsOpen -> groupPermissionsOpen = false
            else -> onClose()
        }
    }

    if (descriptionEditorOpen) {
        GroupDescriptionEditorScreen(
            initialDescription = state.groupDescription,
            onBack = { descriptionEditorOpen = false },
            onCancel = { descriptionEditorOpen = false },
            onConfirm = { description ->
                viewModel.updateDescription(description)
                descriptionEditorOpen = false
            }
        )
        return
    }

    if (addMembersOpen) {
        AddMembersScreen(
            groupName = state.groupName.ifBlank { "Group" },
            membersCanAddOthers = groupPermissions.addMembers,
            availableUsers = state.availableUsers,
            onBack = { addMembersOpen = false },
            onAddMembers = { selectedIds ->
                viewModel.addMembers(selectedIds)
                addMembersOpen = false
            },
            onInviteClick = {
                scope.launch { snackbarHostState.showSnackbar("Invite links are not available yet") }
            }
        )
        return
    }

    if (groupPermissionsOpen) {
        GroupPermissionsEditorScreen(
            groupName = state.groupName.ifBlank { "Group" },
            permissions = groupPermissions,
            canEdit = state.isCurrentUserAdmin,
            onBack = { groupPermissionsOpen = false },
            onPermissionsChange = { updated ->
                groupPermissions = updated
                scope.launch {
                    ChatSettingsDataStore.setGroupPermissions(context, chatId, updated)
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = GroupWaBackground,
        topBar = {
            GroupInfoTopBar(
                onBack = onClose,
                onQrClick = {
                    scope.launch { snackbarHostState.showSnackbar("QR code sharing is not available yet") }
                },
                onMenuClick = {
                    scope.launch { snackbarHostState.showSnackbar("More actions are not available yet") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val scrollState = rememberScrollState()

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = glyphTheme.actionPrimary)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp)
        ) {
            // XML-like linear layout: one continuous scroll surface, no lazy item boundaries.
            Column {
                val creatorName = remember(state.members, state.createdBy) {
                    state.members.firstOrNull { it.user.id == state.createdBy }?.let { row ->
                        if (row.isSelf) "You" else ContactDisplayNameResolver.getDisplayName(
                            otherUserId = row.user.id,
                            remoteProfileName = row.user.username,
                            remotePhoneNumber = row.user.phoneNumber,
                            fallback = "Someone"
                        )
                    } ?: "You"
                }
                val createdMeta = remember(creatorName, state.createdAt) {
                    buildGroupCreatedMeta(creatorName, state.createdAt)
                }
                GroupInfoHeroSection(
                    iconUrl = state.groupIconUrl,
                    name = state.groupName,
                    memberCount = state.members.size,
                    isAdmin = state.isCurrentUserAdmin,
                    onChangeIcon = { if (state.isCurrentUserAdmin) showIconOptionsSheet = true },
                    onRename = { renameDialogOpen = true },
                    onAudioClick = {
                        scope.launch { snackbarHostState.showSnackbar("Group audio calls are not available yet") }
                    },
                    onVideoClick = {
                        scope.launch { snackbarHostState.showSnackbar("Group video calls are not available yet") }
                    },
                    onAddClick = { addMembersOpen = true },
                    onSearchClick = {
                        scope.launch { snackbarHostState.showSnackbar("Member search is not available yet") }
                    }
                )
                GroupInfoDescriptionBlock(
                    description = state.groupDescription,
                    createdMeta = createdMeta,
                    isAdmin = state.isCurrentUserAdmin,
                    isMember = state.isCurrentUserMember,
                    onEdit = { descriptionEditorOpen = true },
                    onNonAdminTap = {
                        scope.launch {
                            snackbarHostState.showSnackbar("Only group admins can edit the description")
                        }
                    }
                )
                HorizontalDivider(color = GroupWaDivider, modifier = Modifier.padding(top = 10.dp))

                    GroupInfoSettingRow(
                        icon = R.drawable.ic_notifications,
                        title = "Notifications",
                        subtitle = if (messagesMuted) "Muted" else "All",
                        onClick = {
                            context.startActivity(
                                ChatNotificationsActivity.newIntent(
                                    context,
                                    chatId,
                                    state.groupName.ifBlank { "Group" }
                                )
                            )
                        }
                    )
                    GroupInfoSettingRow(
                        icon = R.drawable.ic_media_visibility,
                        title = "Media visibility",
                        checked = mediaVisible,
                        onCheckedChange = { enabled ->
                            mediaVisible = enabled
                            scope.launch {
                                ChatSettingsDataStore.setMediaVisible(context, chatId, enabled)
                            }
                        }
                    )
                    GroupInfoSettingRow(
                        icon = R.drawable.ic_encryption,
                        title = "Encryption",
                        subtitle = "Messages and calls are end-to-end encrypted. Tap to learn more.",
                        onClick = {
                            encryptionDialogOpen = true
                        }
                    )
                    GroupInfoSettingRow(
                        icon = R.drawable.ic_disappearing,
                        title = "Disappearing messages",
                        subtitle = ChatSettingsDataStore.disappearingTimerLabel(disappearingTimer),
                        onClick = {
                            context.startActivity(
                                DisappearingMessagesActivity.newIntent(
                                    context,
                                    chatId,
                                    state.groupName.ifBlank { "Group" }
                                )
                            )
                        }
                    )
                    GroupInfoSettingRow(
                        icon = R.drawable.ic_chat_lock,
                        title = "Chat lock",
                        subtitle = "Lock and hide this chat on this device.",
                        checked = chatLockEnabled,
                        onCheckedChange = { enabled ->
                            chatLockEnabled = enabled
                            scope.launch {
                                ChatSettingsDataStore.updateLockedSet(context, chatId, enabled)
                            }
                        }
                    )
                    GroupInfoSettingRow(
                        icon = R.drawable.ic_advanced_privacy,
                        title = "Advanced chat privacy",
                        subtitle = if (advancedPrivacyEnabled) "On" else "Off",
                        onClick = {
                            context.startActivity(AdvancedChatPrivacyActivity.newIntent(context, chatId))
                        }
                    )
                    GroupInfoSettingRow(
                        icon = R.drawable.ic_settings,
                        title = "Group permissions",
                        subtitle = ChatSettingsDataStore.groupPermissionsSummary(groupPermissions),
                        onClick = {
                            groupPermissionsOpen = true
                        }
                    )
                    GroupInfoSettingRow(
                        icon = R.drawable.ic_translate_glyph,
                        title = "Translate messages",
                        checked = translateEnabled,
                        onCheckedChange = { enabled ->
                            translateEnabled = enabled
                            scope.launch {
                                ChatSettingsDataStore.setTranslateEnabled(context, chatId, enabled)
                            }
                        }
                    )
                    GroupCommunityRow(
                        onClick = {
                            scope.launch { snackbarHostState.showSnackbar("Communities are not available yet") }
                        }
                    )

                    GroupMembersHeader(memberCount = state.members.size)

                    if (state.isCurrentUserAdmin) {
                        GroupInfoActionRow(
                            icon = R.drawable.ic_person_add,
                            label = "Add members",
                            onClick = { addMembersOpen = true }
                        )
                        GroupInfoActionRow(
                            icon = R.drawable.ic_link,
                            label = "Invite via link or QR code",
                            onClick = {
                                scope.launch { snackbarHostState.showSnackbar("Invite links are not available yet") }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
            }

            state.members.forEach { row ->
                MemberRowWithStatus(
                    row = row,
                    onlineStatusFlow = viewModel.memberOnlineStatus,
                    showMenu = state.isCurrentUserAdmin && !row.isSelf,
                    onMenuClick = { actionMember = row }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            GroupInfoBottomActionRow(
                icon = R.drawable.ic_favourite,
                label = "Add to Favourites",
                onClick = {
                    scope.launch { snackbarHostState.showSnackbar("Favourites are not available yet") }
                }
            )
            GroupInfoBottomActionRow(
                icon = R.drawable.ic_list,
                label = "Add to list",
                onClick = {
                    scope.launch { snackbarHostState.showSnackbar("Lists are not available yet") }
                }
            )
            GroupInfoBottomActionRow(
                icon = R.drawable.ic_clear_chat,
                label = "Clear chat",
                onClick = {
                    scope.launch { snackbarHostState.showSnackbar("Clear chat is not available yet") }
                }
            )
            if (state.isCurrentUserMember) {
                GroupInfoBottomActionRow(
                    icon = R.drawable.ic_logout,
                    label = "Exit group",
                    destructive = true,
                    onClick = { leaveConfirmOpen = true }
                )
            }
            GroupInfoBottomActionRow(
                icon = R.drawable.ic_report,
                label = "Report group",
                destructive = true,
                onClick = {
                    scope.launch { snackbarHostState.showSnackbar("Report group is not available yet") }
                }
            )
        }
    }

    // -- Dialogs --
    if (renameDialogOpen) {
        TextInputDialog(
            title = "Rename group",
            initialValue = state.groupName,
            confirmLabel = "Save",
            onDismiss = { renameDialogOpen = false },
            onConfirm = { newName ->
                if (newName.isNotBlank()) viewModel.renameGroup(newName)
                renameDialogOpen = false
            }
        )
    }
    if (leaveConfirmOpen) {
        AlertDialog(
            onDismissRequest = { leaveConfirmOpen = false },
            containerColor = glyphTheme.backgroundElevated,
            titleContentColor = glyphTheme.textPrimary,
            textContentColor = glyphTheme.textSecondary,
            title = { Text("Exit group?") },
            text = { Text("You'll stop receiving messages from this group.") },
            confirmButton = {
                TextButton(onClick = {
                    leaveConfirmOpen = false
                    viewModel.leaveGroup()
                }) {
                    Text("Exit", color = glyphTheme.actionDestructive)
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveConfirmOpen = false }) {
                    Text("Cancel", color = glyphTheme.textPrimary)
                }
            }
        )
    }
    if (encryptionDialogOpen) {
        AlertDialog(
            onDismissRequest = { encryptionDialogOpen = false },
            containerColor = glyphTheme.backgroundElevated,
            titleContentColor = glyphTheme.textPrimary,
            textContentColor = glyphTheme.textSecondary,
            title = { Text("End-to-end encryption") },
            text = {
                Text(
                    "Messages and calls in this group stay end-to-end encrypted. Only group participants can read message content and listen to calls."
                )
            },
            confirmButton = {
                TextButton(onClick = { encryptionDialogOpen = false }) {
                    Text("OK", color = glyphTheme.actionPrimary)
                }
            }
        )
    }
    actionMember?.let { row ->
        MemberActionsDialog(
            row = row,
            onDismiss = { actionMember = null },
            onPromote = {
                viewModel.promote(row.user.id)
                actionMember = null
            },
            onDemote = {
                viewModel.demote(row.user.id)
                actionMember = null
            },
            onRemove = {
                viewModel.removeMember(row.user.id)
                actionMember = null
            }
        )
    }

    if (showIconOptionsSheet) {
        GroupIconOptionsSheet(
            hasExistingIcon = state.groupIconUrl.isNotBlank(),
            onDismiss = { showIconOptionsSheet = false },
            onTakePhoto = {
                showIconOptionsSheet = false
                onTakeGroupIconPhoto()
            },
            onChooseFromGallery = {
                showIconOptionsSheet = false
                onPickGroupIconFromGallery()
            },
            onRemove = {
                showIconOptionsSheet = false
                onRemoveGroupIcon()
            }
        )
    }
}

private fun buildGroupCreatedMeta(createdByName: String, createdAt: Long): String {
    if (createdAt <= 0L) return "Created by $createdByName"
    val dateLabel = if (android.text.format.DateUtils.isToday(createdAt)) {
        "today"
    } else {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(createdAt))
    }
    val timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault())
        .format(Date(createdAt))
        .lowercase(Locale.getDefault())
    return "Created by $createdByName, $dateLabel at $timeLabel"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupIconOptionsSheet(
    hasExistingIcon: Boolean,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChooseFromGallery: () -> Unit,
    onRemove: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    @Suppress("LocalVariableName")
    val GroupWaSurface = glyphTheme.backgroundSecondary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    @Suppress("LocalVariableName")
    val GroupWaDanger = glyphTheme.actionDestructive

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GroupWaSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            // Handle bar
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GroupWaTextSecondary.copy(alpha = 0.3f))
            )
            // Title
            Text(
                text = "Profile Picture",
                color = GroupWaTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            HorizontalDivider(color = glyphTheme.divider)
            // Take Photo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTakePhoto() }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_camera),
                    contentDescription = null,
                    tint = GroupWaTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("Take Photo", color = GroupWaTextPrimary, fontSize = 16.sp)
            }
            // Choose From Gallery
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChooseFromGallery() }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_gallery),
                    contentDescription = null,
                    tint = GroupWaTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text("Choose From Gallery", color = GroupWaTextPrimary, fontSize = 16.sp)
            }
            // Remove Profile Picture (only if icon exists)
            if (hasExistingIcon) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRemove() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        tint = GroupWaDanger,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Remove Profile Picture", color = GroupWaDanger, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun GroupInfoTopBar(
    onBack: () -> Unit,
    onQrClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(GroupWaBackground)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "Back",
                tint = GroupWaTextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onQrClick) {
            Icon(
                painter = painterResource(R.drawable.ic_dialpad),
                contentDescription = "QR code",
                tint = GroupWaTextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        IconButton(onClick = onMenuClick) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = "More",
                tint = GroupWaTextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun GroupInfoHeroSection(
    iconUrl: String,
    name: String,
    memberCount: Int,
    isAdmin: Boolean,
    onChangeIcon: () -> Unit,
    onRename: () -> Unit,
    onAudioClick: () -> Unit,
    onVideoClick: () -> Unit,
    onAddClick: () -> Unit,
    onSearchClick: () -> Unit
) {
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GroupWaBackground)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Wrap in an outer Box so we can overlay the camera badge badge for admins
        Box(
            modifier = Modifier.size(112.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            // Group icon circle
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(GroupWaIconBackground)
                    .clickable(enabled = isAdmin) { onChangeIcon() },
                contentAlignment = Alignment.Center
            ) {
                if (iconUrl.isNotBlank()) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(iconUrl)
                            .memoryCacheKey("group_icon:${iconUrl}")
                            .diskCacheKey(iconUrl)
                            .crossfade(false)
                            .build(),
                        contentDescription = "Group icon",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_group),
                        contentDescription = null,
                        tint = GroupWaIconTint,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }
            // Camera badge — only visible to admins
            if (isAdmin) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(GroupWaGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = "Change group icon",
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = name.ifBlank { "Group" },
            color = GroupWaTextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable(enabled = isAdmin) { onRename() }
                .padding(horizontal = 12.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Group · ",
                color = GroupWaTextSecondary,
                fontSize = 14.sp
            )
            Text(
                text = "$memberCount members",
                color = GroupWaGreen,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GroupQuickActionTile(
                icon = R.drawable.ic_call,
                label = "Audio",
                onClick = onAudioClick,
                modifier = Modifier.weight(1f)
            )
            GroupQuickActionTile(
                icon = R.drawable.ic_video_call,
                label = "Video",
                onClick = onVideoClick,
                modifier = Modifier.weight(1f)
            )
            GroupQuickActionTile(
                icon = R.drawable.ic_person_add,
                label = "Add",
                onClick = onAddClick,
                modifier = Modifier.weight(1f)
            )
            GroupQuickActionTile(
                icon = R.drawable.ic_search,
                label = "Search",
                onClick = onSearchClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun GroupQuickActionTile(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTileBorder = glyphTheme.borderPrimary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, GroupWaTileBorder, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = GroupWaGreen,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = label,
            color = GroupWaTextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun GroupInfoDescriptionBlock(
    description: String,
    createdMeta: String,
    isAdmin: Boolean,
    isMember: Boolean,
    onEdit: () -> Unit,
    onNonAdminTap: () -> Unit
) {
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isMember) { if (isAdmin) onEdit() else onNonAdminTap() }
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = description.ifBlank {
                    if (isAdmin) "Add group description" else "No description"
                },
                color = if (description.isNotBlank() || isAdmin) GroupWaGreen else GroupWaTextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = createdMeta,
                color = GroupWaTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
        if (isAdmin) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = "Edit description",
                tint = GroupWaTextSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun GroupInfoSettingRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    checked: Boolean? = null,
    onClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                when {
                    checked != null && onCheckedChange != null -> onCheckedChange(!checked)
                    onClick != null -> onClick()
                }
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = GroupWaTextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = GroupWaTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = GroupWaTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
        if (checked != null) {
            GroupInfoToggle(checked = checked)
        }
    }
}

@Composable
private fun GroupInfoToggle(checked: Boolean) {
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    val trackColor = if (checked) GroupWaGreen else Color(0xFF4A545B)
    val thumbOffset = if (checked) 22.dp else 0.dp
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(trackColor)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(GroupWaBackground)
        )
    }
}

    @Composable
    private fun GroupPermissionsEditorScreen(
        groupName: String,
        permissions: ChatSettingsDataStore.GroupPermissions,
        canEdit: Boolean,
        onBack: () -> Unit,
        onPermissionsChange: (ChatSettingsDataStore.GroupPermissions) -> Unit
    ) {
        @Suppress("LocalVariableName")
        val GroupWaBackground = glyphTheme.backgroundPrimary
        @Suppress("LocalVariableName")
        val GroupWaTextPrimary = glyphTheme.textPrimary
        @Suppress("LocalVariableName")
        val GroupWaTextSecondary = glyphTheme.textSecondary
        @Suppress("LocalVariableName")
        val GroupWaDivider = glyphTheme.divider
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GroupWaBackground)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        tint = GroupWaTextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Group permissions", color = GroupWaTextPrimary, fontSize = 18.sp)
            }
            HorizontalDivider(color = GroupWaDivider)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp)
            ) {
                item {
                    Text("Members can:", color = GroupWaTextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    GroupPermissionToggleRow(
                        icon = R.drawable.ic_edit,
                        title = "Edit group settings",
                        subtitle = "This includes the name, icon, description and disappearing messages timer.",
                        checked = permissions.editSettings,
                        enabled = canEdit,
                        onCheckedChange = {
                            onPermissionsChange(permissions.copy(editSettings = it))
                        }
                    )
                    GroupPermissionToggleRow(
                        icon = R.drawable.ic_chat,
                        title = "Send new messages",
                        checked = permissions.sendMessages,
                        enabled = canEdit,
                        onCheckedChange = {
                            onPermissionsChange(permissions.copy(sendMessages = it))
                        }
                    )
                    GroupPermissionToggleRow(
                        icon = R.drawable.ic_person_add,
                        title = "Add other members",
                        checked = permissions.addMembers,
                        enabled = canEdit,
                        onCheckedChange = {
                            onPermissionsChange(permissions.copy(addMembers = it))
                        }
                    )
                    GroupPermissionToggleRow(
                        icon = R.drawable.ic_link,
                        title = "Invite via link or QR code",
                        checked = permissions.inviteViaLink,
                        enabled = canEdit,
                        onCheckedChange = {
                            onPermissionsChange(permissions.copy(inviteViaLink = it))
                        }
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Text("Admins can:", color = GroupWaTextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(14.dp))
                    GroupPermissionToggleRow(
                        icon = R.drawable.ic_person_add,
                        title = "Approve new members",
                        subtitle = "When turned on, admins must approve anyone who wants to join the group.",
                        checked = permissions.approveMembers,
                        enabled = canEdit,
                        onCheckedChange = {
                            onPermissionsChange(permissions.copy(approveMembers = it))
                        }
                    )
                    if (!canEdit) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Only group admins can change these permissions.",
                            color = GroupWaTextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(groupName, color = Color.Transparent, fontSize = 1.sp)
                }
            }
        }
    }

    @Composable
    private fun GroupPermissionToggleRow(
        icon: Int,
        title: String,
        subtitle: String? = null,
        checked: Boolean,
        enabled: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        @Suppress("LocalVariableName")
        val GroupWaTextPrimary = glyphTheme.textPrimary
        @Suppress("LocalVariableName")
        val GroupWaTextSecondary = glyphTheme.textSecondary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = GroupWaTextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (enabled) GroupWaTextPrimary else GroupWaTextSecondary,
                    fontSize = 16.sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = GroupWaTextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
            GroupInfoToggle(checked = checked)
        }
    }

@Composable
private fun GroupCommunityRow(onClick: () -> Unit) {
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GroupWaGreen),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_group),
                contentDescription = null,
                tint = GroupWaBackground,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Add group to a community",
                color = GroupWaTextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Bring members together in topic-based groups.",
                color = GroupWaTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun GroupMembersHeader(memberCount: Int) {
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$memberCount members",
            color = GroupWaTextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = GroupWaTextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun GroupInfoBottomActionRow(
    icon: Int,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    @Suppress("LocalVariableName")
    val GroupWaDanger = glyphTheme.actionDestructive
    val tint = if (destructive) GroupWaDanger else GroupWaTextSecondary
    val textColor = if (destructive) GroupWaDanger else GroupWaTextPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(18.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun GroupHeader(
    iconUrl: String,
    name: String,
    memberCount: Int,
    isAdmin: Boolean,
    onChangeIcon: () -> Unit,
    onRename: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(glyphTheme.backgroundSecondary)
                .clickable(enabled = isAdmin) { onChangeIcon() },
            contentAlignment = Alignment.Center
        ) {
            if (iconUrl.isNotBlank()) {
                AsyncImage(
                    model = iconUrl,
                    contentDescription = "Group icon",
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Text(
                    text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "G",
                    color = glyphTheme.textPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = isAdmin) { onRename() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = name.ifBlank { "Group" },
                color = glyphTheme.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "Group · $memberCount members",
            color = glyphTheme.textSecondary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        text = label,
        color = glyphTheme.textSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun DescriptionRow(description: String, canEdit: Boolean, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canEdit) { onEdit() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = description.ifBlank { if (canEdit) "Add a description" else "No description" },
            color = if (description.isBlank()) glyphTheme.textSecondary else glyphTheme.textPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun GroupInfoActionRow(icon: Int, label: String, onClick: () -> Unit) {
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(GroupWaGreen),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = GroupWaBackground,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(18.dp))
        Text(label, color = GroupWaTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GroupDescriptionEditorScreen(
    initialDescription: String,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    @Suppress("LocalVariableName")
    val GroupWaDivider = glyphTheme.divider
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary

    var description by remember(initialDescription) { mutableStateOf(initialDescription) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    // Track raw inset values updated every frame during keyboard animation
    var lastNavBarHeightPx by remember { mutableStateOf(0) }
    var lastImeBottomPx by remember { mutableStateOf(0) }
    // True while we've hidden the emoji picker but keyboard hasn't fully appeared yet.
    // Keeps the bottom space alive so content doesn't collapse during the handoff.
    var isTransitioningToKeyboard by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val context = LocalContext.current
    val activity = context as AppCompatActivity
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current

    // KeyboardHeightProvider: measures and persists real keyboard height (nav bar excluded)
    val keyboardHeightProvider = remember { KeyboardHeightProvider(activity) }

    // Manage EmojiPickerPanel lifecycle on the window decor view
    val emojiPanel = remember {
        EmojiPickerPanel(context).also { panel ->
            panel.attachToActivity(activity)
            panel.visibility = android.view.View.GONE
        }
    }
    val decorView = remember(activity) {
        activity.window.decorView as FrameLayout
    }
    DisposableEffect(Unit) {
        val lp = FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        decorView.addView(emojiPanel, lp)
        emojiPanel.onSystemEmojiSelected = { emoji -> description += emoji }
        onDispose {
            emojiPanel.onSystemEmojiSelected = null
            if (emojiPanel.isPickerVisible) emojiPanel.hide()
            decorView.post { decorView.removeView(emojiPanel) }
        }
    }

    DisposableEffect(Unit) {
        keyboardHeightProvider.onKeyboardVisibilityChanged = { isVisible ->
            if (isVisible) {
                // Keyboard is now active: finish emoji -> keyboard handoff.
                if (showEmojiPicker) {
                    showEmojiPicker = false
                    if (emojiPanel.isPickerVisible) emojiPanel.hide()
                }
                isTransitioningToKeyboard = false
            } else {
                // Keyboard hidden while emoji mode is active is expected during
                // keyboard -> emoji handoff; do not close emoji here.
                if (!showEmojiPicker && emojiPanel.isPickerVisible) {
                    emojiPanel.hide()
                }
            }
        }
        keyboardHeightProvider.start()
        onDispose {
            keyboardHeightProvider.onKeyboardVisibilityChanged = null
            keyboardHeightProvider.stop()
        }
    }

    // Track raw inset values every frame during keyboard animation
    DisposableEffect(view) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastNavBarHeightPx = navBottom
            lastImeBottomPx = imeBottom
            val kbVisible = (imeBottom - navBottom) > 100
            if (kbVisible) {
                // Keyboard is fully up — complete handoff and hide emoji panel.
                isTransitioningToKeyboard = false
                if (showEmojiPicker) showEmojiPicker = false
            }
            insets
        }
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(view, null) }
    }

    // Back press: if emoji picker open → switch to keyboard first
    BackHandler(enabled = showEmojiPicker) {
        showEmojiPicker = false
        isTransitioningToKeyboard = false
        emojiPanel.hide()
        softwareKeyboardController?.hide()
    }

    // Single source of truth for bottom space.
    // Emoji-target keeps its height alive via isTransitioningToKeyboard until the keyboard
    // is confirmed fully up, so the layout never collapses during the handoff.
    val emojiTargetPx = if (showEmojiPicker || isTransitioningToKeyboard)
        keyboardHeightProvider.getKeyboardHeight() + lastNavBarHeightPx
    else 0
    // maxOf ensures: during emoji→keyboard transition lastImeBottomPx grows to meet
    // emojiTargetPx; during keyboard→emoji transition emojiTargetPx immediately covers
    // the outgoing keyboard height. In both directions the content never shrinks early.
    val bottomSpaceDp = with(density) { maxOf(emojiTargetPx, lastImeBottomPx).toDp() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GroupWaBackground)
            .padding(bottom = bottomSpaceDp)  // single controlled source — no imePadding()
    ) {
        // ── Top app bar (Back + Title + Save) ──────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    tint = GroupWaTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = "Group description",
                color = GroupWaTextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )
            TextButton(
                onClick = { onConfirm(description.trim()) },
                enabled = description.trim() != initialDescription.trim()
            ) {
                Text(
                    text = "Save",
                    color = if (description.trim() != initialDescription.trim()) GroupWaGreen
                            else GroupWaTextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        HorizontalDivider(color = GroupWaDivider)

        // ── Description input field ────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .border(1.dp, GroupWaDivider, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (description.isBlank()) {
                    Text(
                        "Add group description",
                        color = GroupWaTextSecondary,
                        fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
                BasicTextField(
                    value = description,
                    onValueChange = { if (it.length <= 500) description = it },
                    cursorBrush = SolidColor(GroupWaGreen),
                    textStyle = TextStyle(
                        color = GroupWaTextPrimary,
                        fontSize = 16.sp,
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            IconButton(
                onClick = {
                    if (showEmojiPicker) {
                        // Toggle emoji -> keyboard. Keep emoji visible until keyboard
                        // is confirmed up to avoid a visual gap.
                        isTransitioningToKeyboard = true
                        focusRequester.requestFocus()
                        softwareKeyboardController?.show()
                    } else {
                        // Toggle keyboard -> emoji. Show emoji first, then hide keyboard
                        // so one input is always present during the handoff.
                        val kbHeight = keyboardHeightProvider.getKeyboardHeight()
                        val navBar = lastNavBarHeightPx
                        emojiPanel.setPickerHeight(kbHeight, navBar)
                        emojiPanel.show(animate = false)
                        showEmojiPicker = true
                        softwareKeyboardController?.hide()
                    }
                },
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_emoji),
                    contentDescription = "Open emoji picker",
                    tint = if (showEmojiPicker) GroupWaGreen else GroupWaTextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // ── Character count + hint ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Visible to all group members",
                color = GroupWaTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${description.length}/500",
                color = GroupWaTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMembersScreen(
    groupName: String,
    membersCanAddOthers: Boolean,
    availableUsers: List<User>,
    onBack: () -> Unit,
    onAddMembers: (List<String>) -> Unit,
    onInviteClick: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    @Suppress("LocalVariableName")
    val GroupWaBackground = glyphTheme.backgroundPrimary
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    @Suppress("LocalVariableName")
    val GroupWaDivider = glyphTheme.divider
    val filteredUsers = remember(query, availableUsers) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) availableUsers else availableUsers.filter { user ->
            user.username.contains(trimmedQuery, ignoreCase = true) ||
                user.phoneNumber.contains(trimmedQuery, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = GroupWaBackground,
        floatingActionButton = {
            if (selectedIds.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { onAddMembers(selectedIds.toList()) },
                    containerColor = GroupWaGreen,
                    contentColor = Color.Black,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = "Add")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(GroupWaBackground)
        ) {
            AddMembersSearchBar(
                query = query,
                onQueryChange = { query = it },
                onBack = onBack
            )
            Text(
                text = if (membersCanAddOthers) {
                    "All members are able to add others to this group. Edit group permissions."
                } else {
                    "Only admins are able to add others to this group. Edit group permissions."
                },
                color = GroupWaTextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
            )
            HorizontalDivider(color = GroupWaDivider, modifier = Modifier.padding(horizontal = 24.dp))
            GroupInfoActionRow(icon = R.drawable.ic_person_add, label = "New contact", onClick = {})
            GroupInfoActionRow(icon = R.drawable.ic_link, label = "Invite via link or QR code", onClick = onInviteClick)
            Text(
                text = if (groupName.isBlank()) "Frequently contacted" else "Add members to $groupName",
                color = GroupWaTextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(filteredUsers, key = { it.id }) { user ->
                    val selected = user.id in selectedIds
                    AddMemberContactRow(
                        user = user,
                        selected = selected,
                        onClick = {
                            selectedIds = if (selected) selectedIds - user.id else selectedIds + user.id
                        }
                    )
                }
                if (filteredUsers.isEmpty()) {
                    item {
                        Text(
                            text = if (query.isBlank()) "No contacts available" else "No matches",
                            color = GroupWaTextSecondary,
                            textAlign = TextAlign.Center,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 42.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMembersSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit
) {
    @Suppress("LocalVariableName")
    val GroupWaSurface = glyphTheme.backgroundSecondary
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(GroupWaSurface)
            .padding(start = 8.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = "Back",
                tint = GroupWaTextPrimary,
                modifier = Modifier.size(30.dp)
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (query.isBlank()) {
                Text("Search name or number...", color = GroupWaTextSecondary, fontSize = 16.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(GroupWaGreen),
                textStyle = TextStyle(color = GroupWaTextPrimary, fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_dialpad),
            contentDescription = null,
            tint = GroupWaTextSecondary,
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun AddMemberContactRow(user: User, selected: Boolean, onClick: () -> Unit) {
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user = user, size = 48.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = ContactDisplayNameResolver.getDisplayName(
                otherUserId = user.id,
                remoteProfileName = user.username,
                remotePhoneNumber = user.phoneNumber
            ),
            color = GroupWaTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (selected) GroupWaGreen else Color.Transparent)
                .border(2.dp, if (selected) GroupWaGreen else GroupWaTextSecondary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun MemberRowItem(
    row: GroupInfoViewModel.MemberRow,
    isOnline: Boolean = false,
    showMenu: Boolean,
    onMenuClick: () -> Unit
) {
    @Suppress("LocalVariableName")
    val GroupWaGreen = glyphTheme.actionPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextPrimary = glyphTheme.textPrimary
    @Suppress("LocalVariableName")
    val GroupWaTextSecondary = glyphTheme.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = showMenu) { onMenuClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user = row.user, size = 46.dp)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (row.isSelf) "You" else ContactDisplayNameResolver.getDisplayName(
                    otherUserId = row.user.id,
                    remoteProfileName = row.user.username,
                    remotePhoneNumber = row.user.phoneNumber
                ),
                color = GroupWaTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val secondaryText = when {
                row.isSelf -> "Add member tag"
                row.user.phoneNumber.isNotBlank() -> row.user.phoneNumber
                else -> ""
            }
            if (secondaryText.isNotBlank()) {
                Text(
                    text = secondaryText,
                    color = if (row.isSelf) GroupWaGreen else GroupWaTextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isOnline && !row.isSelf) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GroupWaAdminBackground)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Online",
                    color = GroupWaGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            if (row.isAdmin) Spacer(modifier = Modifier.width(6.dp))
        }
        if (row.isAdmin) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GroupWaAdminBackground)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Group Admin",
                    color = GroupWaAdminText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Thin wrapper that subscribes to [onlineStatusFlow] independently so that online-status
 * changes only recompose member rows — NOT the entire GroupInfoScreen and its setting rows.
 */
@Composable
private fun MemberRowWithStatus(
    row: GroupInfoViewModel.MemberRow,
    onlineStatusFlow: StateFlow<Map<String, Boolean>>,
    showMenu: Boolean,
    onMenuClick: () -> Unit
) {
    val isOnlineFlow = remember(row.user.id, onlineStatusFlow) {
        onlineStatusFlow
            .map { statusMap -> statusMap[row.user.id] == true }
            .distinctUntilChanged()
    }
    val isOnline by isOnlineFlow.collectAsState(initial = false)
    MemberRowItem(
        row = row,
        isOnline = isOnline,
        showMenu = showMenu,
        onMenuClick = onMenuClick
    )
}

@Composable
private fun MemberActionsDialog(
    row: GroupInfoViewModel.MemberRow,
    onDismiss: () -> Unit,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glyphTheme.backgroundElevated,
        titleContentColor = glyphTheme.textPrimary,
        title = {
            Text(ContactDisplayNameResolver.getDisplayName(
                otherUserId = row.user.id,
                remoteProfileName = row.user.username,
                remotePhoneNumber = row.user.phoneNumber,
                fallback = "Member"
            ))
        },
        text = {
            Column {
                if (row.isAdmin) {
                    DialogActionRow("Dismiss as admin", glyphTheme.textPrimary, onDemote)
                } else {
                    DialogActionRow("Make group admin", glyphTheme.textPrimary, onPromote)
                }
                DialogActionRow("Remove from group", glyphTheme.actionDestructive, onRemove)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = glyphTheme.textPrimary)
            }
        }
    )
}

@Composable
private fun DialogActionRow(label: String, color: Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = glyphTheme.backgroundElevated,
        titleContentColor = glyphTheme.textPrimary,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { text = it },
                cursorBrush = androidx.compose.ui.graphics.SolidColor(glyphTheme.actionPrimary),
                textStyle = androidx.compose.ui.text.TextStyle(color = glyphTheme.textPrimary, fontSize = 15.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(glyphTheme.backgroundSecondary)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(confirmLabel, color = glyphTheme.actionPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = glyphTheme.textPrimary)
            }
        }
    )
}
