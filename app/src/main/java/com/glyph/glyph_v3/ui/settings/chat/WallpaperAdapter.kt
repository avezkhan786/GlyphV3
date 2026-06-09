package com.glyph.glyph_v3.ui.settings.chat

import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.databinding.ItemWallpaperThumbnailBinding
import com.glyph.glyph_v3.utils.ChatWallpaperManager
import com.google.android.material.color.MaterialColors

sealed class WallpaperItem {
    object Gallery : WallpaperItem()
    data class Asset(val fileName: String) : WallpaperItem()
}

class WallpaperAdapter(
    wallpapers: List<String>,
    currentSelection: String?,
    private val onWallpaperSelected: (String) -> Unit,
    private val onGalleryClicked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items: List<WallpaperItem> = listOf(WallpaperItem.Gallery) + wallpapers.map { WallpaperItem.Asset(it) }
    private var selected: String? = currentSelection

    companion object {
        private const val TYPE_GALLERY = 0
        private const val TYPE_ASSET = 1
    }

    fun updateSelection(newSelected: String?) {
        val old = selected
        selected = newSelected
        
        // Notify changes.
        if (old != null) {
            val oldIndex = items.indexOfFirst { it is WallpaperItem.Asset && it.fileName == old }
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
        }
        if (newSelected != null) {
            val newIndex = items.indexOfFirst { it is WallpaperItem.Asset && it.fileName == newSelected }
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is WallpaperItem.Gallery -> TYPE_GALLERY
            is WallpaperItem.Asset -> TYPE_ASSET
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val binding = ItemWallpaperThumbnailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return if (viewType == TYPE_GALLERY) {
            GalleryViewHolder(binding)
        } else {
            AssetViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AssetViewHolder -> {
                val item = items[position] as WallpaperItem.Asset
                holder.bind(item.fileName, item.fileName == selected, onWallpaperSelected)
            }
            is GalleryViewHolder -> {
                holder.bind(onGalleryClicked)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class AssetViewHolder(
        private val binding: ItemWallpaperThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(fileName: String, isSelected: Boolean, onSelected: (String) -> Unit) {
            val context = binding.root.context

            val folder = ChatWallpaperManager.getEffectiveThemeFolder(context)
            // Assets are directly in assets/light_mode/ etc.
            val assetPath = "${folder.assetDir}/$fileName"

            Glide.with(binding.thumbnail)
                .load(Uri.parse("file:///android_asset/$assetPath"))
                .centerCrop()
                .into(binding.thumbnail)

            val selectedStrokeColor = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary,
                0
            )

            binding.card.strokeWidth = if (isSelected) context.resources.displayMetrics.density.times(3f).toInt() else 0
            binding.card.strokeColor = selectedStrokeColor
            
            // Reset scale/padding that might have been set by Gallery view holder
            binding.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            binding.thumbnail.setPadding(0, 0, 0, 0)
            binding.thumbnail.setBackgroundColor(Color.TRANSPARENT)

            binding.root.setOnClickListener { onSelected(fileName) }
        }
    }

    class GalleryViewHolder(
        private val binding: ItemWallpaperThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(onClick: () -> Unit) {
            val context = binding.root.context
            
            binding.thumbnail.setImageResource(R.drawable.ic_attachment_gallery)
            binding.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val padding = context.resources.getDimensionPixelSize(R.dimen.wallpaper_grid_spacing) // Use some padding
            binding.thumbnail.setPadding(padding, padding, padding, padding)
            binding.thumbnail.setBackgroundColor(MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurfaceVariant, 0))

            binding.card.strokeWidth = 0
            
            binding.root.setOnClickListener { onClick() }
        }
    }
}
