package com.glyph.glyph_v3.ui.calls

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.signature.ObjectKey
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import com.glyph.glyph_v3.databinding.ItemCallsHeaderActionBinding
import com.glyph.glyph_v3.databinding.ItemCallsHeaderFavoriteBinding
import java.io.File

sealed interface CallsHeaderItem {
    val stableId: Long

    data class Action(
        val kind: CallsHeaderAction,
        val labelRes: Int,
        val iconRes: Int
    ) : CallsHeaderItem {
        override val stableId: Long = kind.ordinal.toLong() + 1L
    }

    data class Favorite(
        val target: FavoriteCallTarget
    ) : CallsHeaderItem {
        override val stableId: Long = target.userId.hashCode().toLong()
    }
}

enum class CallsHeaderAction {
    CALL,
    SCHEDULE,
    KEYPAD,
    FAVORITES
}

class CallsHeaderAdapter(
    private val onActionClick: (CallsHeaderAction) -> Unit,
    private val onFavoriteClick: (FavoriteCallTarget) -> Unit,
    private val onFavoriteLongClick: (anchor: View, target: FavoriteCallTarget) -> Unit
) : ListAdapter<CallsHeaderItem, RecyclerView.ViewHolder>(DiffCallback) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is CallsHeaderItem.Action -> VIEW_TYPE_ACTION
            is CallsHeaderItem.Favorite -> VIEW_TYPE_FAVORITE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ACTION -> ActionViewHolder(ItemCallsHeaderActionBinding.inflate(inflater, parent, false))
            VIEW_TYPE_FAVORITE -> FavoriteViewHolder(ItemCallsHeaderFavoriteBinding.inflate(inflater, parent, false))
            else -> error("Unsupported view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is CallsHeaderItem.Action -> (holder as ActionViewHolder).bind(item)
            is CallsHeaderItem.Favorite -> (holder as FavoriteViewHolder).bind(item.target)
        }
    }

    private inner class ActionViewHolder(
        private val binding: ItemCallsHeaderActionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CallsHeaderItem.Action) {
            binding.ivAction.setImageResource(item.iconRes)
            binding.tvLabel.setText(item.labelRes)
            binding.root.setOnClickListener { onActionClick(item.kind) }
        }
    }

    private inner class FavoriteViewHolder(
        private val binding: ItemCallsHeaderFavoriteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(target: FavoriteCallTarget) {
            val resolvedName = ContactDisplayNameResolver.getDisplayName(
                otherUserId = target.userId,
                remoteProfileName = target.displayName
            )
            binding.tvLabel.text = resolvedName
            binding.tvAvatarInitial.text = resolvedName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

            val localAvatarPath = AvatarCacheManager.getLocalAvatarPath(target.userId)
            when {
                localAvatarPath != null -> {
                    binding.tvAvatarInitial.visibility = android.view.View.GONE
                    val avatarFile = File(localAvatarPath)
                    Glide.with(binding.root.context)
                        .load(avatarFile)
                        .signature(ObjectKey(avatarFile.lastModified()))
                        .placeholder(R.drawable.ic_default_avatar)
                        .transform(CircleCrop())
                        .into(binding.ivAvatar)
                }

                target.avatarUrl.isNotBlank() -> {
                    binding.tvAvatarInitial.visibility = android.view.View.GONE
                    Glide.with(binding.root.context)
                        .load(target.avatarUrl)
                        .placeholder(R.drawable.ic_default_avatar)
                        .transform(CircleCrop())
                        .into(binding.ivAvatar)
                }

                else -> {
                    Glide.with(binding.root.context).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(null)
                    binding.tvAvatarInitial.visibility = android.view.View.VISIBLE
                }
            }

            binding.root.setOnClickListener { onFavoriteClick(target) }
            binding.root.setOnLongClickListener {
                onFavoriteLongClick(binding.root, target)
                true
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_ACTION = 1
        private const val VIEW_TYPE_FAVORITE = 2

        val DiffCallback = object : DiffUtil.ItemCallback<CallsHeaderItem>() {
            override fun areItemsTheSame(oldItem: CallsHeaderItem, newItem: CallsHeaderItem): Boolean {
                return oldItem.stableId == newItem.stableId
            }

            override fun areContentsTheSame(oldItem: CallsHeaderItem, newItem: CallsHeaderItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}