package com.glyph.glyph_v3.ui.chat.picker

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.remote.KlipyMediaItem

/**
 * RecyclerView adapter for rendering a grid of picker items.
 * Supports both Klipy media (ImageView) and System Emojis (TextView).
 */
class PickerGridAdapter(
    private val onKlipyClick: (KlipyMediaItem) -> Unit,
    private val onSystemEmojiClick: (String) -> Unit
) : ListAdapter<PickerItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        const val TYPE_KLIPY = 1
        const val TYPE_SYSTEM_EMOJI = 2
        const val TYPE_HEADER = 3

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PickerItem>() {
            override fun areItemsTheSame(a: PickerItem, b: PickerItem) = a.id == b.id
            override fun areContentsTheSame(a: PickerItem, b: PickerItem) = a == b
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is PickerItem.Klipy -> TYPE_KLIPY
            is PickerItem.SystemEmoji -> TYPE_SYSTEM_EMOJI
            is PickerItem.Header -> TYPE_HEADER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SYSTEM_EMOJI -> {
                val view = inflater.inflate(R.layout.item_system_emoji, parent, false)
                SystemEmojiViewHolder(view)
            }
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_picker_header, parent, false)
                HeaderViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_picker_grid, parent, false)
                KlipyViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is KlipyViewHolder -> holder.bind((item as PickerItem.Klipy).media)
            is SystemEmojiViewHolder -> holder.bind((item as PickerItem.SystemEmoji).unicode)
            is HeaderViewHolder -> holder.bind((item as PickerItem.Header).title)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvHeader)
        fun bind(title: String) {
            textView.text = title
        }
    }

    inner class KlipyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.ivPickerItem)

        fun bind(item: KlipyMediaItem) {
            // Let ConstraintLayout's dimensionRatio handle sizing naturally.
            Glide.with(imageView.context)
                .load(item.previewUrl)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .placeholder(R.drawable.bg_picker_placeholder)
                .error(R.drawable.bg_picker_placeholder)
                .into(imageView)

            itemView.setOnClickListener { onKlipyClick(item) }
        }
    }

    inner class SystemEmojiViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.tvSystemEmoji)

        fun bind(emoji: String) {
            textView.text = emoji
            itemView.setOnClickListener { onSystemEmojiClick(emoji) }
        }
    }
}
