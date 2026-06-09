package com.glyph.glyph_v3.ui.calls

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.signature.ObjectKey
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.cache.AvatarCacheManager
import com.glyph.glyph_v3.data.models.CallType
import com.glyph.glyph_v3.databinding.ItemCallHistoryBinding
import java.io.File

class CallHistoryAdapter(
    private val onItemClick: (CallHistoryUiModel) -> Unit,
    private val onCallTypeClick: (CallHistoryUiModel) -> Unit,
    private val onSelectionChanged: (count: Int) -> Unit
) : ListAdapter<CallHistoryUiModel, CallHistoryAdapter.CallHistoryViewHolder>(DiffCallback) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).groupKey.hashCode().toLong()

    private val selectedKeys = mutableSetOf<String>()
    var isInSelectionMode = false
        private set

    fun exitSelectionMode() {
        if (!isInSelectionMode && selectedKeys.isEmpty()) return
        isInSelectionMode = false
        selectedKeys.clear()
        notifyDataSetChanged()
    }

    fun selectAll() {
        currentList.forEach { selectedKeys.add(it.groupKey) }
        notifyDataSetChanged()
        onSelectionChanged(selectedKeys.size)
    }

    fun getSelectedItems(): List<CallHistoryUiModel> =
        currentList.filter { it.groupKey in selectedKeys }

    private fun toggleItem(groupKey: String, position: Int) {
        if (!isInSelectionMode) isInSelectionMode = true
        if (groupKey in selectedKeys) {
            selectedKeys.remove(groupKey)
        } else {
            selectedKeys.add(groupKey)
        }
        notifyItemChanged(position)
        onSelectionChanged(selectedKeys.size)
        if (selectedKeys.isEmpty()) {
            isInSelectionMode = false
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallHistoryViewHolder {
        val binding = ItemCallHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CallHistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CallHistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CallHistoryViewHolder(
        private val binding: ItemCallHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CallHistoryUiModel) {
            val displayName = if (item.count > 1) {
                binding.root.context.getString(R.string.calls_name_with_count, item.displayName, item.count)
            } else {
                item.displayName
            }

            binding.tvName.text = displayName
            binding.tvTime.text = item.timeLabel
            bindNameColor(item)
            bindDirection(item)
            bindCallType(item.callType)
            bindAvatar(item)

            val isSelected = item.groupKey in selectedKeys
            binding.root.isActivated = isSelected
            binding.btnCallType.visibility = View.VISIBLE
            binding.ivSelectionCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) toggleItem(item.groupKey, pos)
                true
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (isInSelectionMode) {
                    toggleItem(item.groupKey, pos)
                } else {
                    onItemClick(item)
                }
            }

            binding.btnCallType.setOnClickListener {
                if (!isInSelectionMode) onCallTypeClick(item)
            }
        }

        private fun bindNameColor(item: CallHistoryUiModel) {
            val isMissedIncoming = item.direction == CallHistoryDirection.MISSED
            val nameColor = if (isMissedIncoming) {
                MISSED_COLOR
            } else {
                binding.tvName.currentTextColor
            }
            binding.tvName.setTextColor(nameColor)
        }

        private fun bindDirection(item: CallHistoryUiModel) {
            val (iconRes, tintColor) = when (item.direction) {
                CallHistoryDirection.OUTGOING -> R.drawable.ic_call_direction_outgoing to SUCCESS_TINT
                CallHistoryDirection.INCOMING -> R.drawable.ic_call_direction_incoming to SUCCESS_TINT
                CallHistoryDirection.MISSED -> R.drawable.ic_call_missed_incoming to MISSED_TINT
                CallHistoryDirection.MISSED_OUTGOING -> R.drawable.ic_call_missed_outgoing to MISSED_TINT
            }
            binding.ivDirection.setImageResource(iconRes)
            binding.ivDirection.imageTintList = tintColor
        }

        private fun bindCallType(callType: CallType) {
            val iconRes = if (callType == CallType.VIDEO) R.drawable.ic_video_call else R.drawable.ic_phone
            binding.btnCallType.setImageResource(iconRes)
            binding.btnCallType.imageTintList = CALL_TYPE_TINT
        }

        private fun bindAvatar(item: CallHistoryUiModel) {
            val context = binding.root.context
            val fallbackInitial = item.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            binding.tvAvatarInitial.text = fallbackInitial

            val localAvatarPath = AvatarCacheManager.getLocalAvatarPath(item.peerId)
            when {
                localAvatarPath != null -> {
                    val avatarFile = File(localAvatarPath)
                    loadAvatar(FileAvatarSource(avatarFile))
                }

                item.avatarUrl.isNotBlank() -> {
                    loadAvatar(UrlAvatarSource(item.avatarUrl))
                }

                else -> {
                    Glide.with(context).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(null)
                    binding.tvAvatarInitial.visibility = View.VISIBLE
                }
            }
        }

        private fun loadAvatar(source: AvatarSource) {
            binding.tvAvatarInitial.visibility = View.GONE
            val request = Glide.with(binding.root.context)
                .load(source.model)
                .placeholder(R.drawable.ic_default_avatar)
                .transform(CircleCrop())

            if (source is FileAvatarSource) {
                request.signature(ObjectKey(source.file.lastModified()))
            }

            request.into(binding.ivAvatar)
        }
    }

    private sealed interface AvatarSource {
        val model: Any
    }

    private data class FileAvatarSource(val file: File) : AvatarSource {
        override val model: Any = file
    }

    private data class UrlAvatarSource(val url: String) : AvatarSource {
        override val model: Any = url
    }

    private companion object {
        val SUCCESS_TINT: ColorStateList = ColorStateList.valueOf(Color.parseColor("#29ce67"))
        val MISSED_TINT: ColorStateList = ColorStateList.valueOf(Color.parseColor("#db5160"))
        val CALL_TYPE_TINT: ColorStateList = ColorStateList.valueOf(Color.parseColor("#f7f8fa"))
        const val MISSED_COLOR: Int = -2404000

        val DiffCallback = object : DiffUtil.ItemCallback<CallHistoryUiModel>() {
            override fun areItemsTheSame(oldItem: CallHistoryUiModel, newItem: CallHistoryUiModel): Boolean {
                return oldItem.groupKey == newItem.groupKey
            }

            override fun areContentsTheSame(oldItem: CallHistoryUiModel, newItem: CallHistoryUiModel): Boolean {
                return oldItem == newItem
            }
        }
    }
}