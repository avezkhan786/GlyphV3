package com.glyph.glyph_v3.ui.calls

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.GroupCallParticipantUiState
import com.glyph.glyph_v3.data.webrtc.GroupCallManager
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class GroupCallParticipantAdapter(
    private val eglBaseContext: EglBase.Context?
) : ListAdapter<GroupCallParticipantUiState, GroupCallParticipantAdapter.ParticipantViewHolder>(
    DIFF_CALLBACK
) {

    private val attachedRenderers = mutableMapOf<String, SurfaceViewRenderer>()
    private val attachedTracks = mutableMapOf<String, VideoTrack>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_call_participant, parent, false)
        return ParticipantViewHolder(view)
    }

    override fun onBindViewHolder(holder: ParticipantViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ParticipantViewHolder) {
        super.onViewRecycled(holder)
        holder.cleanup()
    }

    fun releaseAll() {
        for ((userId, renderer) in attachedRenderers) {
            try {
                attachedTracks[userId]?.removeSink(renderer)
                renderer.release()
            } catch (_: Exception) {}
        }
        attachedRenderers.clear()
        attachedTracks.clear()
    }

    inner class ParticipantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val videoView: SurfaceViewRenderer = itemView.findViewById(R.id.participantVideoView)
        private val avatarContainer: LinearLayout = itemView.findViewById(R.id.participantAvatarContainer)
        private val ivAvatar: ImageView = itemView.findViewById(R.id.ivParticipantAvatar)
        private val tvNameCenter: TextView = itemView.findViewById(R.id.tvParticipantNameCenter)
        private val tvName: TextView = itemView.findViewById(R.id.tvParticipantName)
        private val ivMicIndicator: ImageView = itemView.findViewById(R.id.ivMicIndicator)
        private val ivVideoIndicator: ImageView = itemView.findViewById(R.id.ivVideoIndicator)
        private val connectingOverlay: FrameLayout = itemView.findViewById(R.id.participantConnectingOverlay)
        private val bottomOverlay: LinearLayout = itemView.findViewById(R.id.participantBottomOverlay)

        private var currentUserId: String? = null
        private var rendererInitialized = false

        fun bind(state: GroupCallParticipantUiState) {
            val previousUserId = currentUserId
            currentUserId = state.userId

            tvName.text = if (state.isSelf) "You" else state.userName
            tvNameCenter.text = if (state.isSelf) "You" else state.userName

            // Load avatar
            if (state.userAvatar.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(state.userAvatar)
                    .transform(CircleCrop())
                    .placeholder(R.drawable.ic_default_avatar)
                    .into(ivAvatar)
            } else {
                ivAvatar.setImageResource(R.drawable.ic_default_avatar)
            }

            // Muted indicator
            ivMicIndicator.visibility = if (!state.audioEnabled) View.VISIBLE else View.GONE

            // Video off indicator
            ivVideoIndicator.visibility = if (!state.videoEnabled) View.VISIBLE else View.GONE

            // Connecting overlay
            connectingOverlay.visibility = if (!state.isConnected && !state.isSelf) View.VISIBLE else View.GONE

            // Video rendering
            val videoTrack = if (state.isSelf) {
                GroupCallManager.localVideoTrack.value
            } else {
                GroupCallManager.remoteVideoTracks.value[state.userId]
            }

            if (state.videoEnabled && videoTrack != null && state.isConnected) {
                showVideo(state.userId, videoTrack, state.isSelf)
                avatarContainer.visibility = View.GONE
                videoView.visibility = View.VISIBLE
                bottomOverlay.visibility = View.VISIBLE
            } else {
                hideVideo(state.userId)
                avatarContainer.visibility = View.VISIBLE
                videoView.visibility = View.GONE
                bottomOverlay.visibility = View.VISIBLE
            }
        }

        private fun showVideo(userId: String, track: VideoTrack, isSelf: Boolean) {
            // Clean up previous attachment
            if (attachedTracks[userId] != track) {
                hideVideo(userId)
            }

            if (!rendererInitialized && eglBaseContext != null) {
                try {
                    videoView.init(eglBaseContext, null)
                    videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    videoView.setZOrderMediaOverlay(false)
                    videoView.setEnableHardwareScaler(true)
                    if (isSelf) {
                        videoView.setMirror(true)
                    }
                    rendererInitialized = true
                } catch (e: Exception) {
                    return
                }
            }

            if (attachedTracks[userId] != track) {
                track.addSink(videoView)
                attachedRenderers[userId] = videoView
                attachedTracks[userId] = track
            }
        }

        private fun hideVideo(userId: String) {
            val previousTrack = attachedTracks.remove(userId)
            val previousRenderer = attachedRenderers.remove(userId)
            if (previousTrack != null && previousRenderer != null) {
                try {
                    previousTrack.removeSink(previousRenderer)
                } catch (_: Exception) {}
            }
        }

        fun cleanup() {
            currentUserId?.let { hideVideo(it) }
            if (rendererInitialized) {
                try {
                    videoView.release()
                } catch (_: Exception) {}
                rendererInitialized = false
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<GroupCallParticipantUiState>() {
            override fun areItemsTheSame(
                oldItem: GroupCallParticipantUiState,
                newItem: GroupCallParticipantUiState
            ) = oldItem.userId == newItem.userId

            override fun areContentsTheSame(
                oldItem: GroupCallParticipantUiState,
                newItem: GroupCallParticipantUiState
            ) = oldItem == newItem
        }
    }
}
