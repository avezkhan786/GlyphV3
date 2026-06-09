package com.glyph.glyph_v3.ui.viewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.glyph.glyph_v3.data.models.MediaItem
import com.glyph.glyph_v3.databinding.ItemImageViewerBinding
import com.github.chrisbanes.photoview.PhotoView

/**
 * ViewPager2 adapter for displaying full-screen zoomable images.
 */
class ImagePagerAdapter(
    private val mediaItems: List<MediaItem>,
    private val onImageTap: () -> Unit
) : RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val binding = ItemImageViewerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ImageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(mediaItems[position])
    }

    override fun getItemCount(): Int = mediaItems.size

    inner class ImageViewHolder(
        private val binding: ItemImageViewerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            // Load full-resolution image
            Glide.with(binding.root.context)
                .load(item.displayUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(binding.photoView)
            
            // Handle tap to toggle UI
            binding.photoView.setOnPhotoTapListener { _, _, _ ->
                onImageTap()
            }
        }
    }
}
