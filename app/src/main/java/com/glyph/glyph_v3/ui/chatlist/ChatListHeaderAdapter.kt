package com.glyph.glyph_v3.ui.chatlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.databinding.ItemChatListHeaderBinding

class ChatListHeaderAdapter : RecyclerView.Adapter<ChatListHeaderAdapter.HeaderViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val binding = ItemChatListHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HeaderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        // Static header; no binding logic needed
    }

    override fun getItemCount(): Int = 1

    class HeaderViewHolder(binding: ItemChatListHeaderBinding) : RecyclerView.ViewHolder(binding.root)
}
