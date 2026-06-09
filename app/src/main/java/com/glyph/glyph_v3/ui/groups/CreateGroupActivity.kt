package com.glyph.glyph_v3.ui.groups

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.glyph.glyph_v3.GlyphApplication
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.User
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.launch
import java.io.File

private val WaBackground = Color(0xFF0B1014)
private val WaSurface = Color(0xFF111B21)
private val WaSurfaceAlt = Color(0xFF1F2C33)
private val WaDivider = Color(0xFF202C33)
private val WaTextPrimary = Color(0xFFE9EDEF)
private val WaTextSecondary = Color(0xFF8696A0)
private val WaGreen = Color(0xFF25D366)
private val WaDarkGreen = Color(0xFF00A884)

class CreateGroupActivity : ComponentActivity() {

    private val viewModel: CreateGroupViewModel by viewModels {
        val app = applicationContext as GlyphApplication
        CreateGroupViewModelFactory(app.getOrCreateGroupChatRepository())
    }

    // ── Activity-level pick→crop launchers (mirrors AvatarPreviewActivity) ──

    private var tempIconSourceUri: Uri? = null

    private val cropIconLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val cropped = UCrop.getOutput(result.data!!)
            if (cropped != null) viewModel.onGroupIconPicked(cropped)
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            Toast.makeText(this, "Crop failed: ${UCrop.getError(result.data!!)?.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickIconLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { startGroupIconCrop(it) } }

    private fun startGroupIconCrop(source: Uri) {
        tempIconSourceUri = source
        val dest = Uri.fromFile(File(cacheDir, "group_icon_crop_${System.currentTimeMillis()}.jpg"))
        val options = UCrop.Options().apply {
            setCircleDimmedLayer(true)
            setShowCropGrid(false)
            setCompressionQuality(90)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(true)
            setToolbarColor(ContextCompat.getColor(this@CreateGroupActivity, R.color.black))
            setStatusBarColor(ContextCompat.getColor(this@CreateGroupActivity, R.color.black))
            setToolbarWidgetColor(ContextCompat.getColor(this@CreateGroupActivity, R.color.white))
        }
        UCrop.of(source, dest)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(1080, 1080)
            .withOptions(options)
            .getIntent(this)
            .also { cropIconLauncher.launch(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContent {
            com.glyph.glyph_v3.ui.theme.GlyphThemeProvider {
                CreateGroupScreen(
                    viewModel = viewModel,
                    onPickGroupIcon = { pickIconLauncher.launch("image/*") },
                    onClose = { finish() },
                    onGroupCreated = { chatId, name, iconUrl ->
                        val intent = ChatActivity.newIntent(
                            context = this,
                            chatId = chatId,
                            otherUserId = "",
                            otherUsername = name,
                            otherUserAvatar = iconUrl
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) }
                        startActivity(intent)
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupScreen(
    viewModel: CreateGroupViewModel,
    onPickGroupIcon: () -> Unit,
    onClose: () -> Unit,
    onGroupCreated: (chatId: String, name: String, iconUrl: String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            scope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.consumeError()
            }
        }
    }

    LaunchedEffect(state.createdChatId) {
        state.createdChatId?.let { chatId ->
            onGroupCreated(chatId, state.groupName.trim(), "")
        }
    }

    BackHandler(enabled = state.step != CreateGroupViewModel.Step.SELECT_MEMBERS) {
        when (state.step) {
            CreateGroupViewModel.Step.SELECT_MEMBERS -> onClose()
            CreateGroupViewModel.Step.GROUP_DETAILS -> viewModel.goBackToSelection()
            CreateGroupViewModel.Step.GROUP_PERMISSIONS -> viewModel.goBackToDetailsStep()
        }
    }

    Scaffold(
        containerColor = WaBackground,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = {
                    Column {
                        Text(
                            text = when (state.step) {
                                CreateGroupViewModel.Step.SELECT_MEMBERS -> "New group"
                                CreateGroupViewModel.Step.GROUP_DETAILS -> "New group"
                                CreateGroupViewModel.Step.GROUP_PERMISSIONS -> "Group permissions"
                            },
                            color = WaTextPrimary,
                            fontSize = if (state.step == CreateGroupViewModel.Step.GROUP_DETAILS) 20.sp else 18.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when (state.step) {
                            CreateGroupViewModel.Step.SELECT_MEMBERS -> "Add participants"
                            CreateGroupViewModel.Step.GROUP_DETAILS -> ""
                            CreateGroupViewModel.Step.GROUP_PERMISSIONS -> state.groupName.ifBlank { "New group" }
                        }
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                color = WaTextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (state.step) {
                            CreateGroupViewModel.Step.SELECT_MEMBERS -> onClose()
                            CreateGroupViewModel.Step.GROUP_DETAILS -> viewModel.goBackToSelection()
                            CreateGroupViewModel.Step.GROUP_PERMISSIONS -> viewModel.goBackToDetailsStep()
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = WaTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WaBackground,
                    titleContentColor = WaTextPrimary
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            when (state.step) {
                CreateGroupViewModel.Step.SELECT_MEMBERS -> {
                    if (state.canProceedFromSelection) {
                        FloatingActionButton(
                            onClick = { viewModel.goToDetailsStep() },
                            containerColor = WaGreen,
                            contentColor = Color.Black,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_chevron_right),
                                contentDescription = "Next"
                            )
                        }
                    }
                }
                CreateGroupViewModel.Step.GROUP_DETAILS -> {
                    if (state.canCreate) {
                        FloatingActionButton(
                            onClick = { viewModel.createGroup() },
                            containerColor = WaGreen,
                            contentColor = Color.Black,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.size(56.dp)
                        ) {
                            if (state.isCreating) {
                                CircularProgressIndicator(
                                    color = Color.Black,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = "Create"
                                )
                            }
                        }
                    }
                }
                CreateGroupViewModel.Step.GROUP_PERMISSIONS -> Unit
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enter = slideInHorizontally(animationSpec = tween(220)) { if (forward) it else -it } + fadeIn()
                val exit = slideOutHorizontally(animationSpec = tween(220)) { if (forward) -it else it } + fadeOut()
                enter togetherWith exit
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "create_group_step"
        ) { step ->
            when (step) {
                CreateGroupViewModel.Step.SELECT_MEMBERS -> SelectMembersStep(
                    state = state,
                    onSearchChange = viewModel::onSearchQueryChanged,
                    onToggle = viewModel::toggleUser
                )
                CreateGroupViewModel.Step.GROUP_DETAILS -> GroupDetailsStep(
                    state = state,
                    onNameChange = viewModel::onGroupNameChanged,
                    onPickIcon = onPickGroupIcon,
                    onPermissionsClick = viewModel::goToPermissionsStep
                )
                CreateGroupViewModel.Step.GROUP_PERMISSIONS -> GroupPermissionsStep(
                    groupName = state.groupName.ifBlank { "New group" }
                )
            }
        }
    }
}

@Composable
private fun SelectMembersStep(
    state: CreateGroupViewModel.UiState,
    onSearchChange: (String) -> Unit,
    onToggle: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WaBackground)
    ) {
        SearchBar(
            query = state.searchQuery,
            onQueryChange = onSearchChange,
            placeholder = "Search name or number..."
        )

        AnimatedVisibility(visible = state.selectedUserIds.isNotEmpty()) {
            SelectedChipsRow(
                allUsers = state.allUsers,
                selectedIds = state.selectedUserIds,
                onRemove = onToggle
            )
        }

        if (state.isLoadingUsers) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = WaGreen)
            }
        } else {
            val users = state.filteredUsers
            if (users.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.searchQuery.isBlank()) "No contacts available" else "No matches",
                        color = WaTextSecondary
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(users, key = { it.id }) { user ->
                        UserSelectableRow(
                            user = user,
                            isSelected = user.id in state.selectedUserIds,
                            onToggle = { onToggle(user.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDetailsStep(
    state: CreateGroupViewModel.UiState,
    onNameChange: (String) -> Unit,
    onPickIcon: () -> Unit,
    onPermissionsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WaBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(WaSurfaceAlt)
                    .clickable { onPickIcon() },
                contentAlignment = Alignment.Center
            ) {
                if (state.groupIconUri != null) {
                    AsyncImage(
                        model = state.groupIconUri,
                        contentDescription = "Group icon",
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = "Pick group icon",
                        tint = WaTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            SubjectTextField(
                value = state.groupName,
                onValueChange = onNameChange,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        DetailsOptionRow(
            icon = R.drawable.ic_disappearing,
            title = "Disappearing messages",
            subtitle = "Off",
            onClick = {}
        )
        DetailsOptionRow(
            icon = R.drawable.ic_settings,
            title = "Group permissions",
            onClick = onPermissionsClick
        )
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Members: ${state.selectedUserIds.size + 1}",
            color = WaTextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(14.dp))

        val selectedUsers = remember(state.allUsers, state.selectedUserIds) {
            state.allUsers.filter { it.id in state.selectedUserIds }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item(key = "self") {
                MemberPreviewChip(label = "You", user = null)
            }
            items(selectedUsers, key = { it.id }) { user ->
                MemberPreviewChip(label = user.username.ifBlank { user.phoneNumber }, user = user)
            }
        }
    }
}

@Composable
private fun SubjectTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .border(2.dp, WaGreen, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isBlank()) {
                Text(
                    text = "Group subject",
                    color = WaTextSecondary,
                    fontSize = 15.sp,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                cursorBrush = SolidColor(WaGreen),
                textStyle = TextStyle(color = WaTextPrimary, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = (100 - value.length).coerceAtLeast(0).toString(),
            color = WaTextSecondary,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Icon(
            painter = painterResource(R.drawable.ic_emoji),
            contentDescription = null,
            tint = WaTextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DetailsOptionRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = WaTextSecondary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = WaTextPrimary, fontSize = 16.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = WaTextSecondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MemberPreviewChip(label: String, user: User?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(78.dp)
    ) {
        if (user == null) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(WaSurfaceAlt),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_person),
                    contentDescription = null,
                    tint = WaTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            UserAvatar(user = user, size = 50.dp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = WaTextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GroupPermissionsStep(groupName: String) {
    var editSettings by rememberSaveable { mutableStateOf(true) }
    var sendMessages by rememberSaveable { mutableStateOf(true) }
    var addMembers by rememberSaveable { mutableStateOf(true) }
    var inviteViaLink by rememberSaveable { mutableStateOf(false) }
    var approveMembers by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(WaBackground),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp)
    ) {
        item {
            Text("Members can:", color = WaTextSecondary, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(12.dp))
        }
        item {
            PermissionSwitchRow(
                icon = R.drawable.ic_edit,
                title = "Edit group settings",
                subtitle = "This includes the name, icon, description, disappearing message timer, and the ability to pin, keep or unkeep messages.",
                checked = editSettings,
                onCheckedChange = { editSettings = it }
            )
        }
        item {
            PermissionSwitchRow(
                icon = R.drawable.ic_chat,
                title = "Send new messages",
                checked = sendMessages,
                onCheckedChange = { sendMessages = it }
            )
        }
        item {
            PermissionSwitchRow(
                icon = R.drawable.ic_person_add,
                title = "Add other members",
                checked = addMembers,
                onCheckedChange = { addMembers = it }
            )
        }
        item {
            PermissionSwitchRow(
                icon = R.drawable.ic_link,
                title = "Invite via link or QR code",
                checked = inviteViaLink,
                onCheckedChange = { inviteViaLink = it }
            )
        }
        item {
            Spacer(modifier = Modifier.height(20.dp))
            Text("Admins can:", color = WaTextSecondary, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(12.dp))
            PermissionSwitchRow(
                icon = R.drawable.ic_person_add,
                title = "Approve new members",
                subtitle = "When turned on, admins must approve anyone who wants to join the group. Learn more",
                checked = approveMembers,
                onCheckedChange = { approveMembers = it }
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(groupName, color = Color.Transparent, fontSize = 1.sp)
        }
    }
}

@Composable
private fun PermissionSwitchRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = WaTextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = WaTextPrimary, fontSize = 15.sp)
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    subtitle,
                    color = WaTextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = WaBackground,
                checkedTrackColor = WaGreen,
                uncheckedThumbColor = WaTextSecondary,
                uncheckedTrackColor = WaSurface,
                uncheckedBorderColor = WaTextSecondary
            )
        )
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(WaSurface)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = WaTextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            if (query.isEmpty()) {
                Text(text = placeholder, color = WaTextSecondary, fontSize = 15.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(WaGreen),
                textStyle = TextStyle(color = WaTextPrimary, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SelectedChipsRow(
    allUsers: List<User>,
    selectedIds: Set<String>,
    onRemove: (String) -> Unit
) {
    val selected = remember(allUsers, selectedIds) {
        allUsers.filter { it.id in selectedIds }
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(selected, key = { it.id }) { user ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(70.dp)
                    .clickable { onRemove(user.id) }
            ) {
                Box(modifier = Modifier.size(52.dp)) {
                    UserAvatar(user = user, size = 52.dp)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(WaBackground)
                            .padding(1.5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(WaSurfaceAlt),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Remove",
                                tint = WaTextPrimary,
                                modifier = Modifier.size(9.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = user.username.ifBlank { user.phoneNumber },
                    color = WaTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun UserSelectableRow(user: User, isSelected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user = user, size = 46.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.username.ifBlank { "Unknown" },
                color = WaTextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (user.phoneNumber.isNotBlank()) {
                Text(
                    text = user.phoneNumber,
                    color = WaTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        SelectionCircle(isSelected = isSelected)
    }
}

@Composable
private fun UserDisplayRow(user: User) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user = user, size = 40.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = user.username.ifBlank { user.phoneNumber.ifBlank { "Unknown" } },
            color = WaTextPrimary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SelectionCircle(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (isSelected) WaGreen else Color.Transparent)
            .border(
                width = 1.25.dp,
                color = if (isSelected) WaGreen else WaTextSecondary,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

@Composable
internal fun UserAvatar(user: User, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColorFor(user.id.ifBlank { user.username }.hashCode())),
        contentAlignment = Alignment.Center
    ) {
        if (user.profileImageUrl.isNotBlank()) {
            val imageRequest = remember(user.profileImageUrl) {
                ImageRequest.Builder(context)
                    .data(user.profileImageUrl)
                    .memoryCacheKey("avatar:${user.profileImageUrl}")
                    .diskCacheKey(user.profileImageUrl)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            Text(
                text = user.username.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = Color.White,
                fontSize = (size.value / 2.5f).sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

internal fun avatarColorFor(seed: Int): Color {
    val palette = listOf(
        0xFF25D366, 0xFF128C7E, 0xFF075E54, 0xFF34B7F1,
        0xFF00A884, 0xFFD4AC0D, 0xFFE74C3C, 0xFF9B59B6,
        0xFF3498DB, 0xFFE67E22
    )
    return Color(palette[(kotlin.math.abs(seed)) % palette.size])
}

@Composable
private fun LabelledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = glyphTheme.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (singleLine) 44.dp else 64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(glyphTheme.backgroundSecondary)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                cursorBrush = SolidColor(glyphTheme.actionPrimary),
                textStyle = TextStyle(color = glyphTheme.textPrimary, fontSize = 15.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
