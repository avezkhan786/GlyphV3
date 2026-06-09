package com.glyph.glyph_v3.ui.calls

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.signature.ObjectKey
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.databinding.ItemCallFavoriteAddBinding
import com.glyph.glyph_v3.databinding.ItemCallFavoriteInfoBinding
import com.glyph.glyph_v3.databinding.ItemCallFavoriteRowBinding
import java.io.File

class CallFavoritesAdapter(
    private val onAddClick: () -> Unit,
    private val onVoiceCallClick: (FavoriteCallTarget) -> Unit,
    private val onVideoCallClick: (FavoriteCallTarget) -> Unit,
    private val onRemoveClick: (FavoriteCallTarget) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var isEditing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (favorites.isNotEmpty()) notifyItemRangeChanged(1, favorites.size)
        }

    private val favorites = mutableListOf<FavoriteCallTarget>()

    override fun getItemCount(): Int = favorites.size + 2

    override fun getItemViewType(position: Int): Int {
        return when (position) {
            0 -> VIEW_TYPE_ADD
            itemCount - 1 -> VIEW_TYPE_INFO
            else -> VIEW_TYPE_FAVORITE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ADD -> AddViewHolder(ItemCallFavoriteAddBinding.inflate(inflater, parent, false))
            VIEW_TYPE_INFO -> InfoViewHolder(ItemCallFavoriteInfoBinding.inflate(inflater, parent, false))
            VIEW_TYPE_FAVORITE -> FavoriteViewHolder(ItemCallFavoriteRowBinding.inflate(inflater, parent, false))
            else -> error("Unsupported view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddViewHolder -> holder.bind()
            is FavoriteViewHolder -> holder.bind(favorites[position - 1])
            is InfoViewHolder -> holder.bind()
        }
    }

    fun submitFavorites(items: List<FavoriteCallTarget>) {
        favorites.clear()
        favorites.addAll(items)
        notifyDataSetChanged()
    }

    fun isFavoriteRow(position: Int): Boolean {
        return position in 1..favorites.size
    }

    fun moveFavorite(fromAdapterPosition: Int, toAdapterPosition: Int): Boolean {
        if (!isFavoriteRow(fromAdapterPosition) || !isFavoriteRow(toAdapterPosition)) return false
        val fromIndex = fromAdapterPosition - 1
        val toIndex = toAdapterPosition - 1
        if (fromIndex == toIndex) return true

        val item = favorites.removeAt(fromIndex)
        favorites.add(toIndex, item)
        notifyItemMoved(fromAdapterPosition, toAdapterPosition)
        return true
    }

    fun currentOrderIds(): List<String> = favorites.map { it.userId }

    private inner class AddViewHolder(
        private val binding: ItemCallFavoriteAddBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener { onAddClick() }
        }
    }

    private inner class FavoriteViewHolder(
        private val binding: ItemCallFavoriteRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FavoriteCallTarget) {
            binding.tvName.text = item.displayName
            binding.tvAvatarInitial.text = item.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            bindAvatar(item)

            binding.btnVoiceCall.isVisible = !isEditing
            binding.btnVideoCall.isVisible = !isEditing
            binding.btnRemove.isVisible = isEditing
            binding.dragHandle.isVisible = isEditing

            binding.btnVoiceCall.setOnClickListener { onVoiceCallClick(item) }
            binding.btnVideoCall.setOnClickListener { onVideoCallClick(item) }
            binding.btnRemove.setOnClickListener { onRemoveClick(item) }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN && isEditing) {
                    onStartDrag(this)
                }
                false
            }
            binding.root.setOnLongClickListener {
                if (isEditing) {
                    onStartDrag(this)
                    true
                } else {
                    false
                }
            }
        }

        private fun bindAvatar(item: FavoriteCallTarget) {
            val localAvatarPath = AvatarCacheManager.getLocalAvatarPath(item.userId)
            when {
                localAvatarPath != null -> {
                    binding.tvAvatarInitial.visibility = View.GONE
                    val avatarFile = File(localAvatarPath)
                    Glide.with(binding.root.context)
                        .load(avatarFile)
                        .signature(ObjectKey(avatarFile.lastModified()))
                        .placeholder(R.drawable.ic_default_avatar)
                        .transform(CircleCrop())
                        .into(binding.ivAvatar)
                }

                item.avatarUrl.isNotBlank() -> {
                    binding.tvAvatarInitial.visibility = View.GONE
                    Glide.with(binding.root.context)
                        .load(item.avatarUrl)
                        .placeholder(R.drawable.ic_default_avatar)
                        .transform(CircleCrop())
                        .into(binding.ivAvatar)
                }

                else -> {
                    Glide.with(binding.root.context).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(null)
                    binding.tvAvatarInitial.visibility = View.VISIBLE
                }
            }
        }
    }

    private inner class InfoViewHolder(
        binding: ItemCallFavoriteInfoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind() = Unit
    }

    private companion object {
        const val VIEW_TYPE_ADD = 1
        const val VIEW_TYPE_FAVORITE = 2
        const val VIEW_TYPE_INFO = 3
    }
}