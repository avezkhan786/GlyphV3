package com.glyph.glyph_v3.ui.calls

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.PopupWindow
import com.glyph.glyph_v3.databinding.LayoutCallsFavoriteMenuBinding

class FavoriteHeaderMenuPopup(
    private val context: Context,
    private val rootView: View,
    private val anchorView: View,
    private val target: FavoriteCallTarget,
    private val onVoiceCall: (FavoriteCallTarget) -> Unit,
    private val onVideoCall: (FavoriteCallTarget) -> Unit,
    private val onMessage: (FavoriteCallTarget) -> Unit,
    private val onRemove: (FavoriteCallTarget) -> Unit
) {
    private val density = context.resources.displayMetrics.density
    private var popupWindow: PopupWindow? = null

    fun show() {
        dismiss()

        val binding = LayoutCallsFavoriteMenuBinding.inflate(LayoutInflater.from(context))
        binding.tvContactName.text = target.displayName

        val popup = PopupWindow(
            binding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 18f * density
        }

        binding.rowVoiceCall.setOnClickListener {
            popup.dismiss()
            onVoiceCall(target)
        }
        binding.rowVideoCall.setOnClickListener {
            popup.dismiss()
            onVideoCall(target)
        }
        binding.rowMessage.setOnClickListener {
            popup.dismiss()
            onMessage(target)
        }
        binding.rowRemoveFavorite.setOnClickListener {
            popup.dismiss()
            onRemove(target)
        }

        binding.root.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val anchorLocation = IntArray(2)
        anchorView.getLocationInWindow(anchorLocation)

        val popupWidth = binding.root.measuredWidth
        val popupHeight = binding.root.measuredHeight
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        val preferredX = anchorLocation[0] - popupWidth / 2 + (anchorView.width / 2)
        val minX = (12f * density).toInt()
        val maxX = screenWidth - popupWidth - minX
        val finalX = preferredX.coerceIn(minX, maxX)

        val anchorBottom = anchorLocation[1] + anchorView.height
        val belowY = anchorBottom + (12f * density).toInt()
        val aboveY = anchorLocation[1] - popupHeight - (12f * density).toInt()
        val finalY = if (belowY + popupHeight < screenHeight - (24f * density).toInt()) {
            belowY
        } else {
            aboveY.coerceAtLeast((24f * density).toInt())
        }

        popup.showAtLocation(rootView, Gravity.NO_GRAVITY, finalX, finalY)

        binding.cardMenu.alpha = 0f
        binding.cardMenu.translationY = 8f * density
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.cardMenu, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.cardMenu, View.TRANSLATION_Y, 8f * density, 0f)
            )
            duration = 180L
            interpolator = DecelerateInterpolator()
            start()
        }

        popupWindow = popup
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
    }
}