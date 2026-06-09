package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.MessageStatus
import com.glyph.glyph_v3.databinding.LayoutVoiceMessageViewBinding

class VoiceMessageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: LayoutVoiceMessageViewBinding
    private var currentAvatarUrl: String? = null

    init {
        orientation = HORIZONTAL
        binding = LayoutVoiceMessageViewBinding.inflate(LayoutInflater.from(context), this, true)
    }

    fun bind(
        isSelf: Boolean,
        isPlaying: Boolean,
        progress: Float,
        currentPosition: String,
        totalDuration: String,
        onPlayPause: () -> Unit,
        onSeek: (Float) -> Unit,
        durationMs: Long,
        timestamp: CharSequence,
        status: MessageStatus,
        avatarUrl: String?,
        contentColor: Int
    ) {
        // Set colors
        binding.ivPlayIcon.setColorFilter(contentColor)
        binding.tvDuration.setTextColor(contentColor)
        binding.tvTimestamp.setTextColor(contentColor) // Use alpha in layout or here if needed
        binding.waveformView.setColors(contentColor, (contentColor and 0x00FFFFFF) or 0x40000000)

        // Waveform
        binding.waveformView.setDuration(durationMs)
        binding.waveformView.setProgress(progress)
        binding.waveformView.setOnSeekListener(onSeek)

        // Play/Pause
        binding.ivPlayIcon.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlayPause.setOnClickListener { onPlayPause() }

        // Progress
        // Note: CircularProgressIndicator uses 0-100 range usually, but depends on style. 
        // Material3 CircularProgressIndicator uses 0-100 by default in XML if max is 100.
        // We set progress 0-100.
        binding.progressIndicator.progress = (if (isPlaying) progress * 100 else 0f).toInt()
        binding.progressIndicator.setIndicatorColor(android.graphics.Color.parseColor("#4CAF50"))

        // Time
        binding.tvDuration.text = if (isPlaying) currentPosition else totalDuration
        binding.tvTimestamp.text = timestamp

        // Avatar
        if (currentAvatarUrl != avatarUrl) {
            currentAvatarUrl = avatarUrl
            if (!avatarUrl.isNullOrEmpty()) {
                binding.ivAvatar.visibility = VISIBLE
                binding.avatarOverlay.visibility = VISIBLE
                binding.btnBackground.visibility = GONE
                Glide.with(this)
                    .load(avatarUrl)
                    .centerCrop()
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_person)
                    .into(binding.ivAvatar)
            } else {
                binding.ivAvatar.visibility = GONE
                binding.avatarOverlay.visibility = GONE
                binding.btnBackground.visibility = VISIBLE
                // Set background tint to slightly transparent content color for placeholder feel
                binding.btnBackground.background.setTint((contentColor and 0x00FFFFFF) or 0x1F000000)
            }
        } else if (avatarUrl.isNullOrEmpty()) {
            // Update placeholder tint if color changed
            binding.btnBackground.background.setTint((contentColor and 0x00FFFFFF) or 0x1F000000)
        }

        // Status & Played indicator (Self Only)
        if (isSelf) {
            binding.ivPlayed.visibility = VISIBLE
            binding.ivStatus.visibility = VISIBLE
            
            val isPlayed = status == MessageStatus.PLAYED
            val micColor = if (isPlayed) android.graphics.Color.parseColor("#4FC3F7") else (contentColor and 0x00FFFFFF) or 0x99000000.toInt()
            binding.ivPlayed.setColorFilter(micColor)
            
            val statusIcon = when (status) {
                MessageStatus.PLAYED, MessageStatus.READ -> R.drawable.ic_double_check
                MessageStatus.DELIVERED -> R.drawable.ic_double_check
                MessageStatus.SENT -> R.drawable.ic_check
                else -> R.drawable.ic_clock
            }
            binding.ivStatus.setImageResource(statusIcon)

            // Adjust size and position: bigger and translated down for checkmarks, original for clock
            val density = context.resources.displayMetrics.density
            if (statusIcon != R.drawable.ic_clock) {
                binding.ivStatus.layoutParams.width = (19 * density).toInt()
                binding.ivStatus.layoutParams.height = (19 * density).toInt()
                binding.ivStatus.translationY = 2 * density
            } else {
                binding.ivStatus.layoutParams.width = (16 * density).toInt()
                binding.ivStatus.layoutParams.height = (16 * density).toInt()
                binding.ivStatus.translationY = 0f
            }
            binding.ivStatus.requestLayout()
            
            val statusColor = if (status == MessageStatus.READ || status == MessageStatus.PLAYED) {
                 android.graphics.Color.parseColor("#4FC3F7")
            } else {
                 (contentColor and 0x00FFFFFF) or 0x99000000.toInt()
            }
            binding.ivStatus.setColorFilter(statusColor)

        } else {
            binding.ivPlayed.visibility = GONE
            binding.ivStatus.visibility = GONE
        }
    }
}
