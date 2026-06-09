package com.glyph.glyph_v3.ui.chat

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.CompressionEstimateResult
import com.glyph.glyph_v3.data.models.CompressionQuality
import com.glyph.glyph_v3.data.models.MediaEstimate
import com.glyph.glyph_v3.databinding.BottomSheetCompressionBinding
import com.glyph.glyph_v3.databinding.ItemCompressionQualityBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Bottom sheet dialog for selecting media compression quality before upload.
 * Shows estimated sizes for each quality preset with real-time updates.
 */
class MediaCompressionBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetCompressionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CompressionViewModel by activityViewModels()

    private lateinit var qualityOptions: List<QualityOptionViews>
    private var perItemAdapter: MediaEstimateAdapter? = null

    // Callback for when user confirms upload
    var onConfirm: ((CompressionQuality) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetCompressionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBottomSheetBehavior()
        setupQualityOptions()
        setupPerItemList()
        setupButtons()
        observeState()
    }

    private fun setupBottomSheetBehavior() {
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true
        }
    }

    private fun setupQualityOptions() {
        // Map quality options to their views
        qualityOptions = listOf(
            QualityOptionViews(
                CompressionQuality.ORIGINAL,
                ItemCompressionQualityBinding.bind(binding.optionOriginal.root)
            ),
            QualityOptionViews(
                CompressionQuality.HIGH,
                ItemCompressionQualityBinding.bind(binding.optionHigh.root)
            ),
            QualityOptionViews(
                CompressionQuality.MEDIUM,
                ItemCompressionQualityBinding.bind(binding.optionMedium.root)
            ),
            QualityOptionViews(
                CompressionQuality.LOW,
                ItemCompressionQualityBinding.bind(binding.optionLow.root)
            )
        )

        // Set up each quality option
        qualityOptions.forEach { option ->
            option.binding.tvQualityName.text = option.quality.displayName
            option.binding.tvQualityDescription.text = option.quality.description
            option.binding.tvRecommended.isVisible = option.quality.isRecommended

            // Click listener for the whole card
            option.binding.root.setOnClickListener {
                viewModel.selectQuality(option.quality)
            }
        }
    }

    private fun setupPerItemList() {
        perItemAdapter = MediaEstimateAdapter()
        binding.rvPerItemList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = perItemAdapter
        }

        binding.tvPerItemToggle.setOnClickListener {
            viewModel.togglePerItemList()
        }
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }

        binding.btnSend.setOnClickListener {
            val quality = viewModel.uiState.value.selectedQuality
            onConfirm?.invoke(quality)
            dismiss()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    updateUI(state.selectedQuality, state.currentEstimate, state.isCalculating, state.showPerItemList, state.selectedItems.size)
                }
            }
        }
    }

    private fun updateUI(
        selectedQuality: CompressionQuality,
        estimate: CompressionEstimateResult?,
        isCalculating: Boolean,
        showPerItemList: Boolean,
        itemCount: Int
    ) {
        // Show/hide loading
        binding.loadingContainer.isVisible = isCalculating
        binding.qualityOptionsContainer.isVisible = !isCalculating

        // Update item count badge
        binding.tvItemCount.text = if (itemCount == 1) "1 item" else "$itemCount items"

        // Show per-item toggle only for multiple items
        binding.perItemContainer.isVisible = itemCount > 1

        if (estimate == null) return

        // Update total size summary
        binding.tvTotalSize.text = estimate.formattedTotalEstimated
        binding.tvOriginalSize.text = estimate.formattedTotalOriginal
        binding.tvSizeReduction.apply {
            text = estimate.formattedTotalReduction
            isVisible = estimate.totalReductionPercent > 0
        }

        // Update each quality option with its estimate
        qualityOptions.forEach { option ->
            val optionEstimate = viewModel.uiState.value.estimates[option.quality]
            updateQualityOption(option, optionEstimate, option.quality == selectedQuality)
        }

        // Update per-item list
        binding.rvPerItemList.isVisible = showPerItemList
        binding.tvPerItemToggle.text = if (showPerItemList) "Hide individual files" else "Show individual files"
        binding.tvPerItemToggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0,
            if (showPerItemList) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
            0
        )

        if (showPerItemList) {
            perItemAdapter?.submitList(estimate.estimates)
        }
    }

    private fun updateQualityOption(
        option: QualityOptionViews,
        estimate: CompressionEstimateResult?,
        isSelected: Boolean
    ) {
        with(option.binding) {
            radioQuality.isChecked = isSelected
            selectedBorder.isVisible = isSelected

            if (estimate != null) {
                tvEstimatedSize.text = estimate.formattedTotalEstimated
                tvReduction.apply {
                    text = estimate.formattedTotalReduction
                    isVisible = estimate.totalReductionPercent > 0
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Helper class to hold quality option views
     */
    private data class QualityOptionViews(
        val quality: CompressionQuality,
        val binding: ItemCompressionQualityBinding
    )

    /**
     * Adapter for per-item estimate list
     */
    private inner class MediaEstimateAdapter : RecyclerView.Adapter<MediaEstimateAdapter.ViewHolder>() {

        private var estimates: List<MediaEstimate> = emptyList()

        fun submitList(list: List<MediaEstimate>) {
            estimates = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_estimate, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(estimates[position])
        }

        override fun getItemCount() = estimates.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
            private val ivVideoIndicator: ImageView = itemView.findViewById(R.id.ivVideoIndicator)
            private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
            private val tvResolution: TextView = itemView.findViewById(R.id.tvResolution)
            private val tvNewSize: TextView = itemView.findViewById(R.id.tvNewSize)
            private val tvOldSize: TextView = itemView.findViewById(R.id.tvOldSize)

            fun bind(estimate: MediaEstimate) {
                val item = estimate.item

                // Load thumbnail
                Glide.with(itemView.context)
                    .load(item.uri)
                    .centerCrop()
                    .placeholder(R.drawable.ic_image_placeholder)
                    .into(ivThumbnail)

                // Show video indicator
                ivVideoIndicator.isVisible = item.isVideo

                // File name
                tvFileName.text = item.displayName.ifEmpty { 
                    if (item.isVideo) "Video" else "Image" 
                }

                // Resolution change
                tvResolution.text = "${item.width}×${item.height} → ${estimate.outputWidth}×${estimate.outputHeight}"

                // Size change
                tvNewSize.text = estimate.formattedSize
                tvOldSize.text = "was ${estimate.formattedOriginalSize}"
            }
        }
    }

    companion object {
        const val TAG = "MediaCompressionBottomSheet"

        /**
         * Create and show the compression bottom sheet for the given media items.
         */
        fun show(
            fragmentManager: androidx.fragment.app.FragmentManager,
            mediaItems: List<Pair<Uri, String>>,
            viewModel: CompressionViewModel,
            onConfirm: (CompressionQuality) -> Unit,
            onCancel: () -> Unit = {}
        ): MediaCompressionBottomSheet {
            // Initialize the ViewModel with media items
            viewModel.initializeWithMedia(mediaItems)

            return MediaCompressionBottomSheet().apply {
                this.onConfirm = onConfirm
                this.onCancel = onCancel
            }.also {
                it.show(fragmentManager, TAG)
            }
        }
    }
}
