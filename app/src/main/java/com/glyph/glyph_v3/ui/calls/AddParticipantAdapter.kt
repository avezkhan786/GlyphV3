package com.glyph.glyph_v3.ui.calls

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.User

class AddParticipantAdapter(
    private val users: List<User>,
    private val onUserSelected: (User) -> Unit
) : RecyclerView.Adapter<AddParticipantAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_add_participant, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(users[position])
    }

    override fun getItemCount() = users.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivAddParticipantAvatar)
        private val tvName: TextView = itemView.findViewById(R.id.tvAddParticipantName)
        private val btnAdd: ImageView = itemView.findViewById(R.id.btnAddToCall)

        fun bind(user: User) {
            tvName.text = user.username

            if (user.profileImageUrl.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(user.profileImageUrl)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_default_avatar)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            }

            btnAdd.setOnClickListener { onUserSelected(user) }
            itemView.setOnClickListener { onUserSelected(user) }
        }
    }
}
