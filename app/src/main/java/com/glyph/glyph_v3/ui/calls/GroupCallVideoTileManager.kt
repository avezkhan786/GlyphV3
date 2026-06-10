package com.glyph.glyph_v3.ui.calls

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.models.GroupCallParticipantUiState
import com.glyph.glyph_v3.data.webrtc.GroupCallManager
import com.glyph.glyph_v3.data.resolver.ContactDisplayNameResolver
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Manages participant video tiles for the [AdaptiveVideoGridLayout].
 *
 * Instead of a RecyclerView adapter that recycles views (causing
 * SurfaceViewRenderer init/release churn), this manager keeps one
 * tile per participant alive for the duration of their stay in the call.
 *
 * Tiles are added/removed from the [grid] as participants join/leave.
 * SurfaceViewRenderers are only inited once per tile and reused.
 */
class GroupCallVideoTileManager(
    private val context: Context,
    private val grid: AdaptiveVideoGridLayout
) {

    companion object {
        private const val TAG = "VideoTileManager"
    }

    /** Dynamically resolved so renderers always get a valid context once available. */
    private val eglBaseContext: EglBase.Context?
        get() = GroupCallManager.eglBaseContext

    /** Active tiles keyed by userId. */
    private val tiles = LinkedHashMap<String, TileHolder>()

    /** Tracks which video track is attached to which renderer. */
    private val attachedTracks = mutableMapOf<String, VideoTrack>()

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Update the grid to reflect [states]. Adds new tiles, removes departed
     * ones, and updates all visible tiles in place.
     *
     * This is designed to be called from a StateFlow collector on every emission —
     * it diffs internally and only touches changed tiles.
     */
    fun updateParticipants(states: List<GroupCallParticipantUiState>) {
        val incoming = states.map { it.userId }.toSet()
        val existing = tiles.keys.toSet()

        // Remove departed participants
        val removed = existing - incoming
        for (uid in removed) {
            removeTile(uid)
        }

        // Add new participants
        for (state in states) {
            if (state.userId !in existing) {
                addTile(state)
            }
        }

        // Reorder if needed: remove all views and re-add in new order
        if (needsReorder(states)) {
            grid.removeAllViews()
            for (state in states) {
                tiles[state.userId]?.let { grid.addView(it.root) }
            }
        }

        // Update all tiles
        for (state in states) {
            tiles[state.userId]?.let { updateTile(it, state) }
        }
    }

    /**
     * Release all renderers and clear state. Call from Activity.onDestroy().
     */
    fun releaseAll() {
        for ((uid, holder) in tiles) {
            detachTrack(uid)
            releaseRenderer(holder)
        }
        tiles.clear()
        grid.removeAllViews()
    }

    // ── Tile lifecycle ───────────────────────────────────────────────

    private fun addTile(state: GroupCallParticipantUiState) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_group_call_participant, grid, false)

        val holder = TileHolder(
            root = view,
            videoView = view.findViewById(R.id.participantVideoView),
            avatarContainer = view.findViewById(R.id.participantAvatarContainer),
            ivAvatar = view.findViewById(R.id.ivParticipantAvatar),
            tvNameCenter = view.findViewById(R.id.tvParticipantNameCenter),
            tvName = view.findViewById(R.id.tvParticipantName),
            ivMicIndicator = view.findViewById(R.id.ivMicIndicator),
            ivVideoIndicator = view.findViewById(R.id.ivVideoIndicator),
            connectingOverlay = view.findViewById(R.id.participantConnectingOverlay),
            bottomOverlay = view.findViewById(R.id.participantBottomOverlay),
            userId = state.userId
        )

        tiles[state.userId] = holder
        grid.addView(view)

        // Fade in
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(300).start()

        updateTile(holder, state)
    }

    private fun removeTile(uid: String) {
        val holder = tiles.remove(uid) ?: return
        detachTrack(uid)
        releaseRenderer(holder)

        // Fade out then remove
        holder.root.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { grid.removeView(holder.root) }
            .start()
    }

    private fun updateTile(holder: TileHolder, state: GroupCallParticipantUiState) {
        val displayName = if (state.isSelf) "You" else
            ContactDisplayNameResolver.getDisplayName(
                otherUserId = state.userId,
                remoteProfileName = state.userName
            )
        holder.tvName.text = displayName
        holder.tvNameCenter.text = displayName

        // Avatar
        if (state.userAvatar.isNotEmpty()) {
            Glide.with(context)
                .load(state.userAvatar)
                .transform(CircleCrop())
                .placeholder(R.drawable.ic_default_avatar)
                .into(holder.ivAvatar)
        } else {
            holder.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        }

        // Indicators
        holder.ivMicIndicator.visibility = if (!state.audioEnabled) View.VISIBLE else View.GONE
        holder.ivVideoIndicator.visibility = if (!state.videoEnabled) View.VISIBLE else View.GONE
        holder.connectingOverlay.visibility =
            if (!state.isConnected && !state.isSelf) View.VISIBLE else View.GONE

        // Video track
        val track: VideoTrack? = if (state.isSelf) {
            GroupCallManager.localVideoTrack.value
        } else {
            GroupCallManager.remoteVideoTracks.value[state.userId]
        }

        if (state.videoEnabled && track != null && state.isConnected) {
            attachTrack(holder, state.userId, track, state.isSelf)
            holder.avatarContainer.visibility = View.GONE
            holder.videoView.visibility = View.VISIBLE
            holder.bottomOverlay.visibility = View.VISIBLE
        } else {
            detachTrack(state.userId)
            holder.avatarContainer.visibility = View.VISIBLE
            holder.videoView.visibility = View.GONE
            holder.bottomOverlay.visibility = View.VISIBLE
        }
    }

    // ── Video rendering ──────────────────────────────────────────────

    private fun attachTrack(holder: TileHolder, uid: String, track: VideoTrack, isSelf: Boolean) {
        // Already attached this exact track
        if (attachedTracks[uid] == track) return

        // Detach previous if different
        detachTrack(uid)

        // Initialize renderer if needed
        if (!holder.rendererInitialized && eglBaseContext != null) {
            try {
                holder.videoView.init(eglBaseContext, null)
                holder.videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                holder.videoView.setZOrderMediaOverlay(false)
                holder.videoView.setEnableHardwareScaler(true)
                if (isSelf) holder.videoView.setMirror(true)
                holder.rendererInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init renderer for $uid", e)
                return
            }
        }

        track.addSink(holder.videoView)
        attachedTracks[uid] = track
    }

    private fun detachTrack(uid: String) {
        val track = attachedTracks.remove(uid) ?: return
        val holder = tiles[uid] ?: return
        try {
            track.removeSink(holder.videoView)
        } catch (_: Exception) {}
    }

    private fun releaseRenderer(holder: TileHolder) {
        if (holder.rendererInitialized) {
            try {
                holder.videoView.release()
            } catch (_: Exception) {}
            holder.rendererInitialized = false
        }
    }

    // ── Ordering ─────────────────────────────────────────────────────

    private fun needsReorder(states: List<GroupCallParticipantUiState>): Boolean {
        val currentOrder = tiles.keys.toList()
        val newOrder = states.map { it.userId }
        return currentOrder != newOrder
    }

    // ── View holder ──────────────────────────────────────────────────

    private class TileHolder(
        val root: View,
        val videoView: SurfaceViewRenderer,
        val avatarContainer: LinearLayout,
        val ivAvatar: ImageView,
        val tvNameCenter: TextView,
        val tvName: TextView,
        val ivMicIndicator: ImageView,
        val ivVideoIndicator: ImageView,
        val connectingOverlay: FrameLayout,
        val bottomOverlay: LinearLayout,
        val userId: String,
        var rendererInitialized: Boolean = false
    )
}
