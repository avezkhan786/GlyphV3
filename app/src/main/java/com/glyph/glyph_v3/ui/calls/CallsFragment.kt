package com.glyph.glyph_v3.ui.calls

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.databinding.FragmentCallsBinding
import com.glyph.glyph_v3.ui.chat.ChatActivity
import com.glyph.glyph_v3.ui.users.UserListActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

class CallsFragment : Fragment() {
    private var _binding: FragmentCallsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CallsViewModel by viewModels()

    private lateinit var callHistoryAdapter: CallHistoryAdapter
    private lateinit var callsHeaderAdapter: CallsHeaderAdapter
    private var favoriteHeaderMenuPopup: FavoriteHeaderMenuPopup? = null
    private var latestItems: List<CallHistoryUiModel> = emptyList()
    private var latestFavorites: List<FavoriteCallTarget> = emptyList()
    private var searchQuery: String = ""

    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitSelectionMode()
        }
    }

    private val callContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val selection = FavoriteCallTarget(
            userId = data.getStringExtra(UserListActivity.EXTRA_SELECTED_USER_ID).orEmpty(),
            displayName = data.getStringExtra(UserListActivity.EXTRA_SELECTED_USER_NAME).orEmpty(),
            avatarUrl = data.getStringExtra(UserListActivity.EXTRA_SELECTED_USER_AVATAR).orEmpty()
        )
        if (selection.userId.isBlank()) return@registerForActivityResult
        showCallTypeChooser(selection)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInsets()
        setupToolbar()
        setupHeaderRecyclerView()
        setupRecyclerView()
        collectUiState()
        collectFavorites()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(requireContext())
        val backgroundColor = when (currentTheme) {
            com.glyph.glyph_v3.utils.ThemeManager.THEME_DARK -> ContextCompat.getColor(requireContext(), R.color.dark_background)
            com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY -> ContextCompat.getColor(requireContext(), R.color.pastel_background)
            else -> ContextCompat.getColor(requireContext(), R.color.light_bubble_other_mid)
        }
        binding.root.setBackgroundColor(backgroundColor)
    }

    override fun onDestroyView() {
        favoriteHeaderMenuPopup?.dismiss()
        favoriteHeaderMenuPopup = null
        super.onDestroyView()
        _binding = null
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { appBar, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            appBar.updatePadding(top = topInset)
            insets
        }
    }

    private fun setupToolbar() {
        binding.btnSearch.setOnClickListener { showSearchDialog() }
        binding.btnMore.setOnClickListener { showOverflowMenu() }
        binding.fabNewCall.setOnClickListener { launchCallContactPicker() }
        setupSelectionToolbar()
    }

    private fun setupSelectionToolbar() {
        binding.toolbarSelection.setNavigationOnClickListener { exitSelectionMode() }
        binding.btnSelectionDelete.setOnClickListener { confirmDeleteSelected() }
        binding.btnSelectionFavorite.setOnClickListener {
            val selectedFavorites = callHistoryAdapter.getSelectedItems().map {
                FavoriteCallTarget(
                    userId = it.peerId,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl
                )
            }
            CallFavoritesStore.addAll(requireContext(), selectedFavorites)
            exitSelectionMode()
            Snackbar.make(binding.root, R.string.calls_favorites_added, Snackbar.LENGTH_SHORT).show()
        }
        binding.btnSelectionMore.setOnClickListener { showSelectionOverflowMenu() }
    }

    private fun enterSelectionMode(count: Int) {
        binding.toolbar.visibility = View.GONE
        binding.toolbarSelection.visibility = View.VISIBLE
        binding.toolbarSelection.title = count.toString()
        selectionBackCallback.isEnabled = true
    }

    private fun exitSelectionMode() {
        callHistoryAdapter.exitSelectionMode()
        binding.toolbarSelection.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        selectionBackCallback.isEnabled = false
    }

    private fun confirmDeleteSelected() {
        val items = callHistoryAdapter.getSelectedItems()
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.calls_delete_title)
            .setMessage(R.string.calls_delete_message)
            .setPositiveButton(R.string.calls_delete_confirm) { _, _ ->
                viewModel.deleteGroups(items.map { it.groupKey }.toSet())
                exitSelectionMode()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSelectionOverflowMenu() {
        PopupMenu(requireContext(), binding.btnSelectionMore).apply {
            menu.add(0, MENU_SELECT_ALL, 0, R.string.calls_menu_select_all)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_SELECT_ALL -> {
                        callHistoryAdapter.selectAll()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun setupHeaderRecyclerView() {
        callsHeaderAdapter = CallsHeaderAdapter(
            onActionClick = { action ->
                when (action) {
                    CallsHeaderAction.CALL -> launchCallContactPicker()
                    CallsHeaderAction.SCHEDULE -> launchScheduleIntent()
                    CallsHeaderAction.KEYPAD -> launchDialPad()
                    CallsHeaderAction.FAVORITES -> openFavoritesScreen()
                }
            },
            onFavoriteClick = { target ->
                when (OutgoingCallLauncher.launch(requireContext(), target, CallType.VOICE)) {
                    OutgoingCallLaunchResult.STARTED -> Unit
                    OutgoingCallLaunchResult.ANOTHER_CALL_ACTIVE -> {
                        Snackbar.make(binding.root, R.string.calls_another_call_active, Snackbar.LENGTH_SHORT).show()
                    }

                    OutgoingCallLaunchResult.FAILED -> {
                        Toast.makeText(requireContext(), R.string.calls_unable_to_start, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onFavoriteLongClick = { anchor, target ->
                showFavoriteHeaderMenu(anchor, target)
            }
        )

        binding.recyclerViewHeader.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false).apply {
                initialPrefetchItemCount = 6
            }
            adapter = callsHeaderAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            itemAnimator = null
        }
        renderHeader()
    }

    private fun setupRecyclerView() {
        callHistoryAdapter = CallHistoryAdapter(
            onItemClick = { item -> redial(item) },
            onCallTypeClick = { item -> redial(item) },
            onSelectionChanged = { count ->
                if (count > 0) enterSelectionMode(count) else exitSelectionMode()
            }
        )
        binding.recyclerViewCalls.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                initialPrefetchItemCount = 12
            }
            adapter = callHistoryAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            itemAnimator = null
        }
    }

    private fun collectUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ensureObservationActive()
                viewModel.uiState.collect { state ->
                    latestItems = state.items
                    renderList(state.isLoading)
                }
            }
        }
    }

    private fun collectFavorites() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallFavoritesStore.observe(requireContext()).collect { favorites ->
                    latestFavorites = favorites
                    renderHeader()
                }
            }
        }
    }

    private fun renderHeader() {
        if (!::callsHeaderAdapter.isInitialized) return
        callsHeaderAdapter.submitList(
            buildList {
                add(CallsHeaderItem.Action(CallsHeaderAction.CALL, R.string.calls_call, R.drawable.ic_phone))
                add(CallsHeaderItem.Action(CallsHeaderAction.SCHEDULE, R.string.calls_schedule, R.drawable.ic_schedule))
                add(CallsHeaderItem.Action(CallsHeaderAction.KEYPAD, R.string.calls_keypad, R.drawable.ic_dialpad))
                latestFavorites.forEach { favorite -> add(CallsHeaderItem.Favorite(favorite)) }
                add(CallsHeaderItem.Action(CallsHeaderAction.FAVORITES, R.string.calls_favorites, R.drawable.ic_star_v2))
            }
        )
    }

    private fun renderList(isLoading: Boolean) {
        val query = searchQuery.trim()
        val visibleItems = if (query.isBlank()) {
            latestItems
        } else {
            latestItems.filter { item ->
                item.displayName.contains(query, ignoreCase = true) ||
                    item.timeLabel.contains(query, ignoreCase = true)
            }
        }

        callHistoryAdapter.submitList(visibleItems)
        binding.progressCalls.isVisible = isLoading && latestItems.isEmpty()

        val showEmpty = !isLoading && visibleItems.isEmpty()
        binding.emptyState.isVisible = showEmpty
        binding.recyclerViewCalls.isVisible = visibleItems.isNotEmpty()

        if (showEmpty) {
            val hasSearch = query.isNotBlank()
            binding.tvEmptyTitle.setText(if (hasSearch) R.string.calls_empty_search_title else R.string.calls_empty_title)
            binding.tvEmptySubtitle.setText(if (hasSearch) R.string.calls_empty_search_subtitle else R.string.calls_empty_subtitle)
        }
    }

    private fun showSearchDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.calls_search_hint)
            setText(searchQuery)
            setSelection(text?.length ?: 0)
            setPadding(
                resources.getDimensionPixelSize(R.dimen.calls_search_field_horizontal_padding),
                resources.getDimensionPixelSize(R.dimen.calls_search_field_vertical_padding),
                resources.getDimensionPixelSize(R.dimen.calls_search_field_horizontal_padding),
                resources.getDimensionPixelSize(R.dimen.calls_search_field_vertical_padding)
            )
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.calls_search_title)
            .setView(input)
            .setPositiveButton(R.string.action_search) { _, _ ->
                searchQuery = input.text?.toString().orEmpty().trim()
                renderList(isLoading = false)
            }
            .setNeutralButton(R.string.action_clear) { _, _ ->
                searchQuery = ""
                renderList(isLoading = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOverflowMenu() {
        PopupMenu(requireContext(), binding.btnMore).apply {
            menu.add(0, MENU_REFRESH, 0, R.string.calls_menu_refresh)
            if (searchQuery.isNotBlank()) {
                menu.add(0, MENU_CLEAR_SEARCH, 1, R.string.calls_menu_clear_search)
            }
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_REFRESH -> {
                        viewModel.refresh()
                        true
                    }

                    MENU_CLEAR_SEARCH -> {
                        searchQuery = ""
                        renderList(isLoading = false)
                        true
                    }

                    else -> false
                }
            }
            show()
        }
    }

    private fun launchCallContactPicker() {
        val intent = Intent(requireContext(), UserListActivity::class.java).apply {
            putExtra(UserListActivity.EXTRA_SELECTION_MODE, UserListActivity.SELECTION_MODE_CALL)
        }
        callContactLauncher.launch(intent)
    }

    private fun launchScheduleIntent() {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, getString(R.string.calls_schedule_default_title))
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.calls_no_calendar_app, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun launchDialPad() {
        val intent = Intent(Intent.ACTION_DIAL)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.calls_no_dialer_app, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun openFavoritesScreen() {
        startActivity(Intent(requireContext(), FavoritesActivity::class.java))
    }

    private fun showFavoriteHeaderMenu(anchor: View, target: FavoriteCallTarget) {
        favoriteHeaderMenuPopup?.dismiss()
        favoriteHeaderMenuPopup = FavoriteHeaderMenuPopup(
            context = requireContext(),
            rootView = binding.root,
            anchorView = anchor,
            target = target,
            onVoiceCall = { startOutgoingCall(it, CallType.VOICE) },
            onVideoCall = { startOutgoingCall(it, CallType.VIDEO) },
            onMessage = { openChatWithFavorite(it) },
            onRemove = {
                CallFavoritesStore.remove(requireContext(), it.userId)
            }
        )
        favoriteHeaderMenuPopup?.show()
    }

    private fun openChatWithFavorite(target: FavoriteCallTarget) {
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (currentUserId.isBlank() || target.userId.isBlank()) return
        val chatId = if (currentUserId < target.userId) {
            "${currentUserId}_${target.userId}"
        } else {
            "${target.userId}_${currentUserId}"
        }
        startActivity(
            ChatActivity.newIntent(
                requireContext(),
                chatId,
                target.userId,
                target.displayName,
                target.avatarUrl
            )
        )
    }

    private fun showCallTypeChooser(selection: FavoriteCallTarget) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.calls_start_call_with, selection.displayName.ifBlank { getString(R.string.calls_unknown_contact) }))
            .setItems(R.array.calls_call_type_options) { _, which ->
                val callType = if (which == 1) CallType.VIDEO else CallType.VOICE
                startOutgoingCall(selection, callType)
            }
            .show()
    }

    private fun redial(item: CallHistoryUiModel) {
        startOutgoingCall(
            FavoriteCallTarget(
                userId = item.peerId,
                displayName = item.displayName,
                avatarUrl = item.avatarUrl
            ),
            item.callType
        )
    }

    private fun startOutgoingCall(selection: FavoriteCallTarget, callType: CallType) {
        val target = selection.copy(
            displayName = selection.displayName.ifBlank { getString(R.string.calls_unknown_contact) }
        )
        when (OutgoingCallLauncher.launch(requireContext(), target, callType)) {
            OutgoingCallLaunchResult.STARTED -> Unit
            OutgoingCallLaunchResult.ANOTHER_CALL_ACTIVE -> {
                Snackbar.make(binding.root, R.string.calls_another_call_active, Snackbar.LENGTH_SHORT).show()
            }

            OutgoingCallLaunchResult.FAILED -> {
                Toast.makeText(requireContext(), R.string.calls_unable_to_start, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val MENU_REFRESH = 1
        const val MENU_CLEAR_SEARCH = 2
        const val MENU_SELECT_ALL = 3
    }
}
