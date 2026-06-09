package com.glyph.glyph_v3.ui.users

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.databinding.ItemUserBinding

class UserAdapter(
    private var contacts: List<ContactListItem>,
    private val onUserClick: (ContactListItem) -> Unit
) : RecyclerView.Adapter<UserAdapter.ContactViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ContactViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(contacts[position])
    }

    override fun getItemCount(): Int = contacts.size
    
    @SuppressLint("NotifyDataSetChanged")
    fun updateList(newList: List<ContactListItem>) {
        contacts = newList
        notifyDataSetChanged() // Simple update for search filtering
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

            // Initials fallback (always available)
            val initial = item.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvAvatarInitial.text = initial
            
            if (item.isRegistered) {
                binding.tvStatus.text = "On Glyph"
                val user = item.registeredUser
                if (user != null && user.profileImageUrl.isNotEmpty()) {
                    // Clear any previous image from recycled views before loading
                    Glide.with(binding.root.context).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(null)

                    // Try to load from local cache first for instant display
                    val localAvatarPath = com.glyph.glyph_v3.data.cache.AvatarCacheManager.getLocalAvatarPath(user.id)
                    
                    if (localAvatarPath != null) {
                        // Load from local storage - INSTANT
                        // Use signature() with file timestamp to force Glide to reload when file changes
                        val file = java.io.File(localAvatarPath)
                        Glide.with(binding.root.context)
                            .load(file)
                            .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                            .skipMemoryCache(true)  // Force re-decode from file
                            .transform(CircleCrop())
                            .into(binding.ivAvatar)
                    } else {
                        // Fallback to URL
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
