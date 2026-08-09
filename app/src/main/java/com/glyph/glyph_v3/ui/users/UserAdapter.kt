package com.glyph.glyph_v3.ui.users

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.databinding.ItemUserBinding
import com.glyph.glyph_v3.databinding.ItemUserSectionHeaderBinding

class UserAdapter(
    private var rows: List<AlphabetSectionIndex.Row>,
    private val onUserClick: (ContactListItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_ITEM = 1
    }

    fun updateRows(newRows: List<AlphabetSectionIndex.Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is AlphabetSectionIndex.Row.Header -> VIEW_TYPE_HEADER
        is AlphabetSectionIndex.Row.Item -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemUserSectionHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemUserBinding.inflate(inflater, parent, false)
                ContactViewHolder(binding)
            }
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is AlphabetSectionIndex.Row.Header -> (holder as HeaderViewHolder).bind(row.letter)
            is AlphabetSectionIndex.Row.Item -> (holder as ContactViewHolder).bind(row.contact)
        }
    }

    inner class HeaderViewHolder(
        private val binding: ItemUserSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(letter: String) {
            binding.tvSectionLetter.text = letter
        }
    }

    inner class ContactViewHolder(
        private val binding: ItemUserBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val row = rows.getOrNull(position) as? AlphabetSectionIndex.Row.Item
                    ?: return@setOnClickListener
                onUserClick(row.contact)
            }
        }

        fun bind(item: ContactListItem) {
            binding.tvUsername.text = item.name

            // Initials fallback (always available)
            val initial = item.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvAvatarInitial.text = initial

            if (item.isRegistered) {
                binding.tvStatus.text = "On Glyph"
                val user = item.registeredUser
                if (user != null && user.profileImageUrl.isNotEmpty()) {
                    Glide.with(binding.root.context).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(null)

                    val localAvatarPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(user.id)

                    if (localAvatarPath != null) {
                        val file = java.io.File(localAvatarPath)
                        Glide.with(binding.root.context)
                            .load(file)
                            .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                            .skipMemoryCache(true)
                            .transform(CircleCrop())
                            .into(binding.ivAvatar)
                    } else {
                        Glide.with(binding.root.context)
                            .load(user.profileImageUrl)
                            .transform(CircleCrop())
                            .into(binding.ivAvatar)
                    }

                    binding.tvAvatarInitial.visibility = View.GONE
                } else {
                    Glide.with(binding.root.context).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(null)
                    binding.tvAvatarInitial.visibility = View.VISIBLE
                }
            } else {
                binding.tvStatus.text = "Invite"
                Glide.with(binding.root.context).clear(binding.ivAvatar)
                binding.ivAvatar.setImageDrawable(null)
                binding.tvAvatarInitial.visibility = View.VISIBLE
            }
        }
    }
}
