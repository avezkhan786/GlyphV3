package com.glyph.glyph_v3.ui.chat

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CompressionEstimateResult
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.data.models.CompressionUiState
import com.glyph.glyph_v3.data.models.SelectedMediaItem
import com.glyph.glyph_v3.databinding.BottomSheetCompressionV2Binding
import com.glyph.glyph_v3.databinding.ItemCompressionQualityV2Binding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Enhanced bottom sheet for media compression quality selection.
 * Features:
 * - Media preview strip with thumbnails
 * - Fixed-height quality options (no layout shifts)
 * - Per-item quality override support
 * - Smooth animations
 */
class MediaCompressionBottomSheetV2 : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCompressionV2Binding? = null
    private val binding get() = _binding!!

    private val viewModel: CompressionViewModel by activityViewModels()

    private lateinit var qualityOptions: List<QualityOptionViews>
    private var previewAdapter: MediaPreviewAdapter? = null
    
    // Currently selected preview item index (for per-item quality)
    private var selectedPreviewIndex: Int = -1
    private var applyToAll: Boolean = true

    // Callbacks
    var onConfirm: ((CompressionQuality, Map<Uri, CompressionQuality>) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentBottomSheetDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCompressionV2Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBottomSheetBehavior()
        setupMediaPreview()
        setupQualityOptions()
        setupApplyToAll()
        setupButtons()
        observeState()
    }

    private fun setupBottomSheetBehavior() {
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true
            isFitToContents = true
        }
    }

    private fun setupMediaPreview() {
        previewAdapter = MediaPreviewAdapter { index, item ->
            if (!applyToAll) {
                // Select this item for individual quality setting
                selectedPreviewIndex = if (selectedPreviewIndex == index) -1 else index
                previewAdapter?.setSelectedIndex(selectedPreviewIndex)
            }
        }
        
        binding.rvMediaPreview.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = previewAdapter
            setHasFixedSize(true)
            itemAnimator = null // Disable animations for smoothness
        }
    }

    private fun setupQualityOptions() {
        qualityOptions = listOf(
            QualityOptionViews(
                CompressionQuality.ORIGINAL,
                ItemCompressionQualityV2Binding.bind(binding.optionOriginal.root)
            ),
            QualityOptionViews(
                CompressionQuality.HIGH,
                ItemCompressionQualityV2Binding.bind(binding.optionHigh.root)
            ),
            QualityOptionViews(
                CompressionQuality.MEDIUM,
                ItemCompressionQualityV2Binding.bind(binding.optionMedium.root)
            ),
            QualityOptionViews(
                CompressionQuality.LOW,
                ItemCompressionQualityV2Binding.bind(binding.optionLow.root)
            )
        )

        qualityOptions.forEach { option ->
            with(option.binding) {
                tvQualityName.text = option.quality.displayName
                tvQualityDescription.text = getShortDescription(option.quality)
                tvRecommended.isVisible = option.quality.isRecommended

                // Click handler
                root.setOnClickListener {
                    selectQuality(option.quality)
                }
            }
        }
    }
    
    private fun getShortDescription(quality: CompressionQuality): String {
        return when (quality) {
            CompressionQuality.ORIGINAL -> "Full size"
            CompressionQuality.HIGH -> "Great quality"
            CompressionQuality.MEDIUM -> "Balanced"
            CompressionQuality.LOW -> "Smaller file"
            CompressionQuality.CUSTOM -> "Per item"
        }
    }

    private fun selectQuality(quality: CompressionQuality) {
        if (applyToAll || selectedPreviewIndex < 0) {
            // Apply to all items
            viewModel.selectQuality(quality)
        } else {
            // Apply to selected item only
            val items = viewModel.uiState.value.selectedItems
            if (selectedPreviewIndex in items.indices) {
                viewModel.setItemOverride(items[selectedPreviewIndex].uri, quality)
                previewAdapter?.notifyItemChanged(selectedPreviewIndex)
            }
        }
    }

    private fun setupApplyToAll() {
        binding.cbApplyToAll.setOnCheckedChangeListener { _, isChecked ->
            applyToAll = isChecked
            if (isChecked) {
                // Clear individual selection
                selectedPreviewIndex = -1
                previewAdapter?.setSelectedIndex(-1)
            }
        }
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }

        binding.btnSend.setOnClickListener {
            val state = viewModel.uiState.value
            onConfirm?.invoke(state.selectedQuality, state.customOverrides)
            dismiss()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: CompressionUiState) {
        // Loading state
        binding.loadingContainer.isVisible = state.isCalculating
        binding.qualityOptionsContainer.isVisible = !state.isCalculating

        // Item count
        val count = state.selectedItems.size
        binding.tvItemCount.text = if (count == 1) "1 item" else "$count items"
        binding.tvItemCount.isVisible = count > 0

        // Show apply-to-all only for multiple items
        binding.applyToAllContainer.isVisible = count > 1

        // Update preview strip
        previewAdapter?.submitList(state.selectedItems, state.customOverrides)

        // Update size summary
        val estimate = state.currentEstimate
        if (estimate != null) {
            binding.tvTotalSize.text = estimate.formattedTotalEstimated
            binding.tvOriginalSize.text = estimate.formattedTotalOriginal
            binding.tvSizeReduction.apply {
                text = estimate.formattedTotalReduction
                isVisible = estimate.totalReductionPercent > 0
            }
        }

        // Update quality options
        updateQualityOptions(state.selectedQuality, state.estimates)
    }

    private fun updateQualityOptions(
        selectedQuality: CompressionQuality,
        estimates: Map<CompressionQuality, CompressionEstimateResult>
    ) {
        qualityOptions.forEach { option ->
            val isSelected = option.quality == selectedQuality
            val estimate = estimates[option.quality]

            with(option.binding) {
                // Selection state with smooth animation
                val wasSelected = selectedBorder.isVisible
                if (isSelected != wasSelected) {
                    if (isSelected) {
                        selectedBorder.alpha = 0f
                        selectedBorder.isVisible = true
                        selectedBorder.animate()
                            .alpha(1f)
                            .setDuration(150)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                        
                        radioInner.scaleX = 0f
                        radioInner.scaleY = 0f
                        radioInner.isVisible = true
                        radioInner.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(150)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                        
                        // Change outer ring color
                        radioOuter.setBackgroundResource(R.drawable.bg_radio_outer_selected)
                    } else {
                        selectedBorder.animate()
                            .alpha(0f)
                            .setDuration(100)
                            .withEndAction { selectedBorder.isVisible = false }
                            .start()
                        
                        radioInner.animate()
                            .scaleX(0f)
                            .scaleY(0f)
                            .setDuration(100)
                            .withEndAction { radioInner.isVisible = false }
                            .start()
                        
                        radioOuter.setBackgroundResource(R.drawable.bg_radio_outer)
                    }
                }

                // Size estimates
                if (estimate != null) {
                    tvEstimatedSize.text = estimate.formattedTotalEstimated
                    tvReduction.apply {
                        text = estimate.formattedTotalReduction
                        isVisible = estimate.totalReductionPercent > 0
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class QualityOptionViews(
        val quality: CompressionQuality,
        val binding: ItemCompressionQualityV2Binding
    )

    /**
     * Adapter for horizontal media preview strip
     */
    private inner class MediaPreviewAdapter(
        private val onItemClick: (Int, SelectedMediaItem) -> Unit
    ) : RecyclerView.Adapter<MediaPreviewAdapter.ViewHolder>() {

        private var items: List<SelectedMediaItem> = emptyList()
        private var overrides: Map<Uri, CompressionQuality> = emptyMap()
        private var selectedIndex: Int = -1

        fun submitList(newItems: List<SelectedMediaItem>, newOverrides: Map<Uri, CompressionQuality>) {
            items = newItems
            overrides = newOverrides
            notifyDataSetChanged()
        }

        fun setSelectedIndex(index: Int) {
            val oldIndex = selectedIndex
            selectedIndex = index
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_preview_thumb, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], position)
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
            private val tvQualityBadge: TextView = itemView.findViewById(R.id.tvQualityBadge)
            private val selectionRing: View = itemView.findViewById(R.id.selectionRing)

            fun bind(item: SelectedMediaItem, position: Int) {
                // Load thumbnail
                Glide.with(ivThumbnail)
                    .load(item.uri)
                    .transform(CenterCrop(), RoundedCorners(24))
                    .placeholder(R.drawable.ic_image_placeholder)
                    .into(ivThumbnail)

                // Video duration
                tvDuration.isVisible = item.isVideo
                if (item.isVideo && item.duration > 0) {
                    tvDuration.text = formatDuration(item.duration)
                }

                // Quality badge for individual override
                val override = overrides[item.uri]
                tvQualityBadge.isVisible = override != null
                override?.let {
                    tvQualityBadge.text = getQualityBadgeText(it)
                }

                // Selection ring
                selectionRing.isVisible = position == selectedIndex

                // Click listener
                itemView.setOnClickListener {
                    onItemClick(position, item)
                }
            }

            private fun formatDuration(ms: Long): String {
                val totalSeconds = ms / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                return "$minutes:${seconds.toString().padStart(2, '0')}"
            }

            private fun getQualityBadgeText(quality: CompressionQuality): String {
                return when (quality) {
                    CompressionQuality.ORIGINAL -> "OG"
                    CompressionQuality.HIGH -> "HD"
                    CompressionQuality.MEDIUM -> "MD"
                    CompressionQuality.LOW -> "SD"
                    CompressionQuality.CUSTOM -> "?"
                }
            }
        }
    }

    companion object {
        const val TAG = "MediaCompressionBottomSheetV2"

        fun newInstance(): MediaCompressionBottomSheetV2 {
            return MediaCompressionBottomSheetV2()
        }
    }
}
