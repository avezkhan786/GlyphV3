package com.glyph.glyph_v3.ui.chat

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.view.children
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.databinding.BottomSheetAttachmentBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AttachmentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAttachmentBinding? = null
    private val binding get() = _binding!!

    var onGalleryClick: (() -> Unit)? = null
    var onCameraClick: (() -> Unit)? = null
    var onDocumentClick: (() -> Unit)? = null
    var onAudioClick: (() -> Unit)? = null
    var onLocationClick: (() -> Unit)? = null
    var onContactClick: (() -> Unit)? = null
    var onPollClick: (() -> Unit)? = null
    var onPaymentClick: (() -> Unit)? = null
    var onEventClick: (() -> Unit)? = null
    var onAiImagesClick: (() -> Unit)? = null

    override fun getTheme(): Int = R.style.TransparentBottomSheetDialog

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheet = (dialogInterface as BottomSheetDialog)
                .findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                it.setBackgroundResource(android.R.color.transparent)
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAttachmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
        animateItemsIn()
    }

    private fun setupClickListeners() {
        binding.optionGallery.setOnClickListener {
            dismissWithAnimation { onGalleryClick?.invoke() }
        }
        binding.optionCamera.setOnClickListener {
            dismissWithAnimation { onCameraClick?.invoke() }
        }
        binding.optionDocument.setOnClickListener {
            dismissWithAnimation { onDocumentClick?.invoke() }
        }
        binding.optionAudio.setOnClickListener {
            dismissWithAnimation { onAudioClick?.invoke() }
        }
        binding.optionLocation.setOnClickListener {
            dismissWithAnimation { onLocationClick?.invoke() }
        }
        binding.optionContact.setOnClickListener {
            dismissWithAnimation { onContactClick?.invoke() }
        }
        binding.optionPoll.setOnClickListener {
            dismissWithAnimation { onPollClick?.invoke() }
        }
        binding.optionPayment.setOnClickListener {
            dismissWithAnimation { onPaymentClick?.invoke() }
        }
        binding.optionEvent.setOnClickListener {
            dismissWithAnimation { onEventClick?.invoke() }
        }
        binding.optionAiImages.setOnClickListener {
            dismissWithAnimation { onAiImagesClick?.invoke() }
        }
    }

    private fun animateItemsIn() {
        val gridItems = mutableListOf<View>()
        
        // Collect all option items
        gridItems.add(binding.optionGallery)
        gridItems.add(binding.optionCamera)
        gridItems.add(binding.optionDocument)
        gridItems.add(binding.optionAudio)
        gridItems.add(binding.optionLocation)
        gridItems.add(binding.optionContact)
        gridItems.add(binding.optionPoll)
        gridItems.add(binding.optionPayment)
        gridItems.add(binding.optionEvent)
        gridItems.add(binding.optionAiImages)

        // Initial state - all items invisible and scaled down
        gridItems.forEach { item ->
            item.alpha = 0f
            item.scaleX = 0.3f
            item.scaleY = 0.3f
        }

        // Container animation
        binding.attachmentContainer.alpha = 0f
        binding.attachmentContainer.translationY = 100f
        
        binding.attachmentContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Staggered animation for each item
        gridItems.forEachIndexed { index, item ->
            item.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 30).toLong())
                .setDuration(300)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun dismissWithAnimation(onComplete: (() -> Unit)?) {
        binding.attachmentContainer.animate()
            .alpha(0f)
            .translationY(100f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                dismiss()
                onComplete?.invoke()
            }
            .start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AttachmentBottomSheet"
        
        fun newInstance(): AttachmentBottomSheet {
            return AttachmentBottomSheet()
        }
    }
}
