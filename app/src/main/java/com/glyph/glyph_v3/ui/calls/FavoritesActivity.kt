package com.glyph.glyph_v3.ui.calls

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.databinding.ActivityFavoritesBinding
import com.glyph.glyph_v3.ui.base.ThemedActivity
import com.glyph.glyph_v3.ui.users.UserListActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class FavoritesActivity : ThemedActivity() {
    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var adapter: CallFavoritesAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private val addFavoriteLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val target = FavoriteCallTarget(
            userId = data.getStringExtra(UserListActivity.EXTRA_SELECTED_USER_ID).orEmpty(),
            displayName = data.getStringExtra(UserListActivity.EXTRA_SELECTED_USER_NAME).orEmpty(),
            avatarUrl = data.getStringExtra(UserListActivity.EXTRA_SELECTED_USER_AVATAR).orEmpty()
        )
        if (target.userId.isBlank()) return@registerForActivityResult
        CallFavoritesStore.add(this, target)
        Snackbar.make(binding.root, R.string.calls_favorites_added, Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentTheme = com.glyph.glyph_v3.utils.ThemeManager.getCurrentTheme(this)
        if (currentTheme == com.glyph.glyph_v3.utils.ThemeManager.THEME_PASTEL_SKY) {
            binding.root.background = ContextCompat.getDrawable(this, R.drawable.bg_pastel_gradient)
        }

        setupToolbar()
        setupRecyclerView()
        collectFavorites()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_edit -> {
                    adapter.isEditing = !adapter.isEditing
                    menuItem.setIcon(if (adapter.isEditing) R.drawable.ic_check else R.drawable.ic_edit)
                    true
                }

                else -> false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { appBar, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            appBar.updatePadding(top = topInset)
            insets
        }
    }

    private fun setupRecyclerView() {
        adapter = CallFavoritesAdapter(
            onAddClick = { openAddFavoritePicker() },
            onVoiceCallClick = { launchFavoriteCall(it, CallType.VOICE) },
            onVideoCallClick = { launchFavoriteCall(it, CallType.VIDEO) },
            onRemoveClick = {
                CallFavoritesStore.remove(this, it.userId)
                Snackbar.make(binding.root, R.string.calls_favorites_removed, Snackbar.LENGTH_SHORT).show()
            },
            onStartDrag = { itemTouchHelper.startDrag(it) }
        )

        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (!adapter.isEditing || !adapter.isFavoriteRow(viewHolder.bindingAdapterPosition)) {
                    return 0
                }
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return adapter.moveFavorite(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                CallFavoritesStore.reorder(this@FavoritesActivity, adapter.currentOrderIds())
            }
        })

        binding.recyclerViewFavorites.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewFavorites.adapter = adapter
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewFavorites)
    }

    private fun collectFavorites() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CallFavoritesStore.observe(this@FavoritesActivity).collect { favorites ->
                    adapter.submitFavorites(favorites)
                }
            }
        }
    }

    private fun openAddFavoritePicker() {
        val intent = Intent(this, UserListActivity::class.java).apply {
            putExtra(UserListActivity.EXTRA_SELECTION_MODE, UserListActivity.SELECTION_MODE_CALL)
        }
        addFavoriteLauncher.launch(intent)
    }

    private fun launchFavoriteCall(target: FavoriteCallTarget, callType: CallType) {
        when (OutgoingCallLauncher.launch(this, target, callType)) {
            OutgoingCallLaunchResult.STARTED -> Unit
            OutgoingCallLaunchResult.ANOTHER_CALL_ACTIVE -> {
                Snackbar.make(binding.root, R.string.calls_another_call_active, Snackbar.LENGTH_SHORT).show()
            }

            OutgoingCallLaunchResult.FAILED -> {
                Toast.makeText(this, R.string.calls_unable_to_start, Toast.LENGTH_SHORT).show()
            }
        }
    }
}