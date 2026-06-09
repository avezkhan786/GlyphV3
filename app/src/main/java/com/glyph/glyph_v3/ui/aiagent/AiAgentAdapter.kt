package com.glyph.glyph_v3.ui.aiagent

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.graphics.Color
import android.util.TypedValue
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.data.local.entity.AiMessage
import com.glyph.glyph_v3.data.repo.AiAgentRepository
import org.json.JSONArray
import java.util.Date
import java.util.regex.Pattern

/**
 * RecyclerView adapter for the AI Agent chat.
 *
 * View types:
 * • USER_MSG – right-aligned bubble
 * • MODEL_MSG – left-aligned bubble with AI label, TTS, copy, and optional sources
 *
 * Follows the same ListAdapter pattern as ChatAdapter.
 */
class AiAgentAdapter(
    private val onCopyClick: (String) -> Unit,
    private val onTtsClick: (String) -> Unit,
    private val onSourceClick: (AiAgentRepository.SourceCitation) -> Unit
) : ListAdapter<AiMessage, RecyclerView.ViewHolder>(AiMessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_MODEL = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).role == "user") VIEW_TYPE_USER else VIEW_TYPE_MODEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = inflater.inflate(R.layout.item_ai_message_user, parent, false)
                UserViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_ai_message_model, parent, false)
                ModelViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        
        // Determine grouping
        val isFirstInGroup = if (position == 0) {
            true
        } else {
            val prev = getItem(position - 1)
            prev.role != message.role
        }

        // Determine if next message is from same sender (detects orphan vs group head)
        val isNextSameUser = if (position < itemCount - 1) {
            val next = getItem(position + 1)
            next.role == message.role
        } else {
            false
        }

        when (holder) {
            is UserViewHolder -> holder.bind(message, isFirstInGroup, isNextSameUser)
            is ModelViewHolder -> holder.bind(message, isFirstInGroup, isNextSameUser)
        }
    }

    // ─── User ViewHolder ─────────────────────────────────

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.msgContainer)
        private val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(message: AiMessage, isFirstInGroup: Boolean, isNextSameUser: Boolean) {
            tvText.text = renderMarkdown(message.text)
            tvTimestamp.text = formatTimestamp(message.timestamp)

            val ctx = itemView.context
            val d   = ctx.resources.displayMetrics.density
            val bp  = (BubbleDrawable.BODY_PADDING_DP * d).toInt()
            val tp  = (BubbleDrawable.TAIL_PADDING_DP * d).toInt()
            val vp  = (BubbleDrawable.VERT_PADDING_DP  * d).toInt()
            val tw  = (BubbleDrawable.TAIL_WIDTH_DP    * d).toInt()

            val lp = (container.layoutParams as? ViewGroup.MarginLayoutParams)

            val ownColor = getThemeColor(ctx, R.attr.glyphBubbleOwn)
            if (isFirstInGroup) {
                (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 24
                container.background = BubbleDrawable(ctx, isOwn = true, hasPointer = true,  fillColor = ownColor)
                container.setPadding(bp, vp, tp, vp)
                lp?.marginStart = tw
                lp?.marginEnd   = 0
            } else {
                (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 4
                container.background = BubbleDrawable(ctx, isOwn = true, hasPointer = false, fillColor = ownColor)
                container.setPadding(bp, vp, bp, vp)
                lp?.marginStart = tw
                lp?.marginEnd   = tw
            }
            container.layoutParams = lp
        }
    }

    // ─── Model ViewHolder ────────────────────────────────

    inner class ModelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val container: LinearLayout = itemView.findViewById(R.id.msgContainer)
        private val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvAiLabel: TextView = itemView.findViewById(R.id.tvAiLabel)
        private val btnTts: ImageButton = itemView.findViewById(R.id.btnTts)
        private val btnCopy: ImageButton = itemView.findViewById(R.id.btnCopy)
        private val sourcesContainer: LinearLayout = itemView.findViewById(R.id.sourcesContainer)
        private val sourcesList: LinearLayout = itemView.findViewById(R.id.sourcesList)

        fun bind(message: AiMessage, isFirstInGroup: Boolean, isNextSameUser: Boolean) {
            tvText.text = renderMarkdown(message.text)
            tvTimestamp.text = formatTimestamp(message.timestamp)

            // Bubble shape & spacing
            val ctx = itemView.context
            val d   = ctx.resources.displayMetrics.density
            val bp  = (BubbleDrawable.BODY_PADDING_DP * d).toInt()
            val tp  = (BubbleDrawable.TAIL_PADDING_DP * d).toInt()
            val vp  = (BubbleDrawable.VERT_PADDING_DP  * d).toInt()
            val tw  = (BubbleDrawable.TAIL_WIDTH_DP    * d).toInt()

            val lp = (container.layoutParams as? ViewGroup.MarginLayoutParams)

            val otherColor = getThemeColor(ctx, R.attr.glyphBubbleOther)
            if (isFirstInGroup) {
                (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 24
                tvAiLabel.visibility = View.VISIBLE
                container.background = BubbleDrawable(ctx, isOwn = false, hasPointer = true,  fillColor = otherColor)
                container.setPadding(tp, vp, bp, vp)
                lp?.marginStart = 0
                lp?.marginEnd   = tw
            } else {
                (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 4
                tvAiLabel.visibility = View.GONE
                container.background = BubbleDrawable(ctx, isOwn = false, hasPointer = false, fillColor = otherColor)
                container.setPadding(bp, vp, bp, vp)
                lp?.marginStart = tw
                lp?.marginEnd   = tw
            }
            container.layoutParams = lp

            // Show streaming placeholder
            if (message.isStreaming) {
                tvText.text = "…"
                tvText.alpha = 0.5f
            } else {
                tvText.alpha = 1.0f
            }

            // Mode-specific label
            tvAiLabel.text = when (message.mode) {
                "search" -> "Glyph AI · Search"
                "app" -> "Glyph AI · App Help"
                else -> "Glyph AI"
            }

            // Action buttons
            btnCopy.setOnClickListener { onCopyClick(message.text) }
            btnTts.setOnClickListener { onTtsClick(message.text) }

            // Sources (search mode only)
            val sources = parseSources(message.sourcesJson)
            if (sources.isNotEmpty()) {
                sourcesContainer.visibility = View.VISIBLE
                sourcesList.removeAllViews()
                sources.forEach { source ->
                    val sourceView = createSourceView(source)
                    sourcesList.addView(sourceView)
                }
            } else {
                sourcesContainer.visibility = View.GONE
            }
        }

        private fun createSourceView(source: AiAgentRepository.SourceCitation): View {
            val container = LinearLayout(itemView.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 4, 0, 4)
                setOnClickListener { onSourceClick(source) }
            }

            // Chat label (e.g. "Chat with Ahmad")
            if (source.conversationWith.isNotBlank()) {
                val labelTv = TextView(itemView.context).apply {
                    text = "Chat with ${source.conversationWith}"
                    textSize = 11f
                    setTextColor(getThemeColor(itemView.context, R.attr.glyphPrimary))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
                container.addView(labelTv)
            }

            // Message preview
            val tv = TextView(itemView.context).apply {
                text = buildString {
                    append("• ")
                    val preview = source.text.take(80)
                    append(preview)
                    if (source.text.length > 80) append("…")
                }
                textSize = 12f
                setTextColor(getThemeColor(itemView.context, R.attr.glyphTextSecondary))
            }
            container.addView(tv)

            return container
        }
    }

    // ─── Helpers ─────────────────────────────────────────

    private fun formatTimestamp(millis: Long): String {
        return DateFormat.format("h:mm a", Date(millis)).toString()
    }

    /**
     * Renders basic Markdown (bold via **text**) into a styled [SpannableStringBuilder].
     */
    private fun renderMarkdown(text: String): CharSequence {
        val spannable = SpannableStringBuilder()
        val pattern = Pattern.compile("\\*\\*(.+?)\\*\\*")
        val matcher = pattern.matcher(text)
        var lastEnd = 0

        while (matcher.find()) {
            spannable.append(text, lastEnd, matcher.start())
            val start = spannable.length
            spannable.append(matcher.group(1))
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                start, spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            lastEnd = matcher.end()
        }
        spannable.append(text, lastEnd, text.length)
        return spannable
    }

    private fun parseSources(sourcesJson: String?): List<AiAgentRepository.SourceCitation> {
        if (sourcesJson.isNullOrBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(sourcesJson)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                AiAgentRepository.SourceCitation(
                    chatId = obj.optString("chatId", ""),
                    msgId = obj.optString("msgId", ""),
                    text = obj.optString("text", ""),
                    timestamp = obj.optLong("timestamp", 0L),
                    senderId = obj.optString("senderId", ""),
                    conversationWith = obj.optString("conversationWith", "")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ─── Theme helper ────────────────────────────────────

fun getThemeColor(context: android.content.Context, attr: Int): Int {
    val typedValue = TypedValue()
    context.theme.resolveAttribute(attr, typedValue, true)
    return typedValue.data
}

/**
 * DiffUtil callback for [AiMessage].
 */
class AiMessageDiffCallback : DiffUtil.ItemCallback<AiMessage>() {
    override fun areItemsTheSame(oldItem: AiMessage, newItem: AiMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: AiMessage, newItem: AiMessage): Boolean {
        return oldItem == newItem
    }
}
