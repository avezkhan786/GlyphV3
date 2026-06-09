package com.glyph.glyph_v3.ui.chat.reactions

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * WhatsApp-style reaction details sheet.
 *
 * Tapping the reaction chip on a message bubble opens this sheet, which shows:
 *   - A header "N reactions".
 *   - A horizontal row of filter pills: an "Add reaction" pill, an "All" pill, and one
 *     pill per distinct emoji (with count). Selecting a pill filters the user list.
 *   - A vertical list of users who reacted, each row showing avatar, name, and that
 *     user's emoji on the right. The current user's row reads "Tap to remove" — tapping
 *     it removes their reaction and dismisses the sheet.
 *
 * Pure programmatic UI — no XML layouts or drawables needed.
 */
class ReactionDetailsSheet : BottomSheetDialogFragment() {

    /** uid → emoji map; the only state we need to fully render the sheet. */
    private var reactionsMap: Map<String, String> = emptyMap()
    private var currentUserId: String? = null
    private var otherUsername: String = ""
    private var currentUserAvatar: String? = null
    private var otherUserAvatar: String? = null

    private var onRemoveOwn: (() -> Unit)? = null
    private var onAddNew: (() -> Unit)? = null
    private var onChangeOwn: (() -> Unit)? = null

    private lateinit var pillRow: LinearLayout
    private lateinit var rv: RecyclerView
    private lateinit var titleView: TextView

    /** Currently selected emoji filter; null means "All". */
    private var activeFilter: String? = null

    private val pillButtons = mutableMapOf<String?, View>()

    fun configure(
        reactions: Map<String, String>,
        currentUserId: String?,
        otherUsername: String,
        currentUserAvatar: String?,
        otherUserAvatar: String?,
        onRemoveOwn: () -> Unit,
        onAddNew: () -> Unit,
        onChangeOwn: () -> Unit
    ): ReactionDetailsSheet {
        this.reactionsMap = reactions
        this.currentUserId = currentUserId
        this.otherUsername = otherUsername
        this.currentUserAvatar = currentUserAvatar
        this.otherUserAvatar = otherUserAvatar
        this.onRemoveOwn = onRemoveOwn
        this.onAddNew = onAddNew
        this.onChangeOwn = onChangeOwn
        return this
    }

    override fun getTheme(): Int =
        com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val density = Resources.getSystem().displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // #121212 dark sheet w/ rounded top corners
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    dp(22f).toFloat(), dp(22f).toFloat(),
                    dp(22f).toFloat(), dp(22f).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(Color.parseColor("#121212"))
            }
            // Bottom padding is set dynamically via WindowInsets below.
            setPadding(0, 0, 0, dp(12f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Drag handle
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2f).toFloat()
                setColor(Color.parseColor("#6E6E6E"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(38f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10f)
                bottomMargin = dp(8f)
            }
        })

        // Header: "N reactions"
        titleView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16f), dp(6f), dp(16f), dp(10f))
        }
        root.addView(titleView)

        // Pill row (scrollable horizontally if many distinct emojis)
        pillRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(2f), dp(12f), dp(10f))
        }
        val pillScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                pillRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(pillScroll)

        // User list. Cap height so the sheet stays in the lower half on long lists; the
        // BottomSheetBehavior will let it become scrollable past that.
        val rvMaxH = (Resources.getSystem().displayMetrics.heightPixels * 0.45f).toInt()
        rv = object : RecyclerView(ctx) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(rvMaxH, MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            layoutManager = LinearLayoutManager(ctx)
            setHasFixedSize(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(rv)

        renderPills()
        renderUserList()

        // Apply bottom padding equal to the system navigation bar height so the sheet
        // content is never obscured by either gesture or three-button nav bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(0, 0, 0, navBar.bottom.coerceAtLeast(dp(12f)))
            insets
        }

        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { dialog ->
            val window = dialog.window ?: return@let
            // Layout the sheet edge-to-edge behind system bars.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Make the nav bar background match the sheet (#121212) for visual continuity.
            window.navigationBarColor = Color.parseColor("#121212")
        }
        dialog?.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.let { sheet ->
            sheet.setBackgroundColor(Color.TRANSPARENT)
            // Let window insets pass through to our root view so the nav-bar padding
            // applied via ViewCompat.setOnApplyWindowInsetsListener fires correctly.
            ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
                ViewCompat.dispatchApplyWindowInsets(
                    (sheet as? ViewGroup)?.getChildAt(0) ?: sheet,
                    insets
                )
                insets
            }
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────

    private fun renderPills() {
        val ctx = requireContext()
        val density = Resources.getSystem().displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        pillRow.removeAllViews()
        pillButtons.clear()

        // Group reactions by emoji preserving first-seen order
        val grouped = LinkedHashMap<String, Int>()
        for ((_, emoji) in reactionsMap) {
            grouped[emoji] = (grouped[emoji] ?: 0) + 1
        }

        titleView.text = if (reactionsMap.size == 1) "1 reaction" else "${reactionsMap.size} reactions"

        // 1) "+" Add-reaction pill
        val addPill = buildAddReactionPill(ctx)
        pillRow.addView(addPill, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(38f)
        ).apply { marginEnd = dp(8f) })

        // 2) "All" pill (only meaningful when > 1 distinct emoji — mirrors WA)
        if (grouped.size > 1) {
            val allPill = buildEmojiCountPill(ctx, label = "All  ${reactionsMap.size}", emoji = null)
            pillRow.addView(allPill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38f)
            ).apply { marginEnd = dp(8f) })
            pillButtons[null] = allPill
            allPill.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                setActiveFilter(null)
            }
        } else {
            // Single distinct → default selection lands on the only emoji
            activeFilter = grouped.keys.firstOrNull() ?: activeFilter
        }

        // 3) Per-emoji pills with count
        for ((emoji, count) in grouped) {
            val pill = buildEmojiCountPill(ctx, label = "$emoji  $count", emoji = emoji)
            pillRow.addView(pill, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38f)
            ).apply { marginEnd = dp(8f) })
            pillButtons[emoji] = pill
            pill.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                setActiveFilter(emoji)
            }
        }

        // Apply selection styling
        applyPillSelection()
    }

    private fun buildAddReactionPill(ctx: Context): View {
        val density = Resources.getSystem().displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val container = FrameLayout(ctx).apply {
            background = unselectedPillBg(dp(20f).toFloat())
            setPadding(dp(14f), 0, dp(14f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onAddNew?.invoke()
                dismissAllowingStateLoss()
            }
        }
        val tv = TextView(ctx).apply {
            text = "\uD83D\uDE42\u2009+"  // 🙂 +
            setTextColor(Color.parseColor("#CFCFCF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(tv)
        return container
    }

    private fun buildEmojiCountPill(ctx: Context, label: String, emoji: String?): View {
        val density = Resources.getSystem().displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val container = FrameLayout(ctx).apply {
            setPadding(dp(14f), 0, dp(14f), 0)
            isClickable = true
            isFocusable = true
        }
        val tv = TextView(ctx).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(tv)
        container.tag = tv     // store text view for state styling (label color)
        return container
    }

    private fun setActiveFilter(emoji: String?) {
        activeFilter = emoji
        applyPillSelection()
        renderUserList()
    }

    private fun applyPillSelection() {
        val density = Resources.getSystem().displayMetrics.density
        val radius = (20 * density)
        for ((emoji, view) in pillButtons) {
            val isSelected = (emoji == activeFilter)
            view.background = if (isSelected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    // Subtle premium indigo-violet — stands out clearly on the #121212
                    // dark sheet without clashing with any brand green.
                    setColor(Color.parseColor("#6C63FF"))
                }
            } else {
                unselectedPillBg(radius)
            }
            (view.tag as? TextView)?.setTextColor(
                // White text on the indigo fill remains legible at all font sizes.
                if (isSelected) Color.WHITE else Color.parseColor("#CFCFCF")
            )
        }
    }

    private fun unselectedPillBg(radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(Color.parseColor("#2A2A2A"))
    }

    private fun renderUserList() {
        val items = buildUserRows()
        rv.adapter = UserRowAdapter(
            items,
            currentUserId,
            currentUserAvatar,
            otherUserAvatar
        ) { row ->
            if (row.uid == currentUserId) {
                onRemoveOwn?.invoke()
                dismissAllowingStateLoss()
            }
        }
    }

    private fun buildUserRows(): List<UserRow> {
        val all = reactionsMap.entries.map { (uid, emoji) ->
            val isMe = uid == currentUserId
            UserRow(
                uid = uid,
                displayName = if (isMe) "You" else otherUsername.ifBlank { "User" },
                emoji = emoji,
                isMe = isMe
            )
        }
            // Put "You" first, like WhatsApp
            .sortedByDescending { it.isMe }

        return if (activeFilter == null) all else all.filter { it.emoji == activeFilter }
    }

    // ── Row data + adapter ────────────────────────────────────────────────

    private data class UserRow(
        val uid: String,
        val displayName: String,
        val emoji: String,
        val isMe: Boolean
    )

    private class UserRowAdapter(
        private val rows: List<UserRow>,
        private val currentUserId: String?,
        private val currentUserAvatar: String?,
        private val otherUserAvatar: String?,
        private val onRowClick: (UserRow) -> Unit
    ) : RecyclerView.Adapter<UserRowAdapter.VH>() {

        class VH(
            val container: LinearLayout,
            val avatar: ImageView,
            val name: TextView,
            val subtitle: TextView,
            val emoji: TextView
        ) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = Resources.getSystem().displayMetrics.density
            fun dp(v: Float) = (v * density).toInt()

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                isClickable = true
                isFocusable = true
                // No ripple drawable — clean, minimal touch feedback via haptics only.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val avatar = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#3A3A3A"))
                }
                clipToOutline = true
            }
            container.addView(avatar)

            val textColumn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(12f) }
            }
            val name = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.NORMAL)
            }
            val subtitle = TextView(ctx).apply {
                setTextColor(Color.parseColor("#9E9E9E"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                visibility = View.GONE
            }
            textColumn.addView(name)
            textColumn.addView(subtitle)
            container.addView(textColumn)

            val emoji = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                includeFontPadding = false
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(emoji)

            return VH(container, avatar, name, subtitle, emoji)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.name.text = row.displayName
            holder.emoji.text = row.emoji
            if (row.isMe) {
                holder.subtitle.visibility = View.VISIBLE
                holder.subtitle.text = "Tap to remove"
            } else {
                holder.subtitle.visibility = View.GONE
            }
            val url = if (row.uid == currentUserId) currentUserAvatar else otherUserAvatar
            if (!url.isNullOrBlank()) {
                Glide.with(holder.avatar.context)
                    .load(url)
                    .transform(CircleCrop())
                    .into(holder.avatar)
            } else {
                holder.avatar.setImageDrawable(null)
            }
            holder.container.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onRowClick(row)
            }
        }

        override fun getItemCount(): Int = rows.size
    }

    companion object {
        const val TAG = "ReactionDetailsSheet"

        fun show(
            fm: FragmentManager,
            reactions: Map<String, String>,
            currentUserId: String?,
            otherUsername: String,
            currentUserAvatar: String?,
            otherUserAvatar: String?,
            onRemoveOwn: () -> Unit,
            onAddNew: () -> Unit,
            onChangeOwn: () -> Unit
        ) {
            ReactionDetailsSheet()
                .configure(
                    reactions,
                    currentUserId,
                    otherUsername,
                    currentUserAvatar,
                    otherUserAvatar,
                    onRemoveOwn, onAddNew, onChangeOwn
                )
                .show(fm, TAG)
        }
    }
}
