package com.glyph.glyph_v3.ui.share

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.databinding.ItemUserBinding
import com.glyph.glyph_v3.ui.users.ContactListItem

class ShareTargetAdapter(
    private var contacts: List<ContactListItem>,
    private val isSelected: (ContactListItem) -> Boolean,
    private val onUserClick: (ContactListItem) -> Unit
) : RecyclerView.Adapter<ShareTargetAdapter.ContactViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount(): Int = contacts.size

    fun updateList(newList: List<ContactListItem>) {
        contacts = newList
        notifyDataSetChanged()
    }

    fun refreshSelection() {
        notifyDataSetChanged()
    }

    inner class ContactViewHolder(private val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < contacts.size) {
                    onUserClick(contacts[position])
                }
            }
        }

        fun bind(item: ContactListItem) {
            binding.tvUsername.text = item.name

            val initial = item.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvAvatarInitial.text = initial

            if (item.isRegistered) {
                binding.tvStatus.text = "On Glyph"
                val user = item.registeredUser
                if (user != null && user.profileImageUrl.isNotEmpty()) {
                    Glide.with(binding.root.context).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(null)

                    val localAvatarPath = AvatarCacheManager.getLocalAvatarPath(user.id)
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

            val selected = item.isRegistered && isSelected(item)
            binding.selectionHighlight.visibility = if (selected) View.VISIBLE else View.GONE
            binding.selectionBadge.visibility = if (selected) View.VISIBLE else View.GONE
        }
    }
}