package com.glyph.glyph_v3.ui.chat.reactions

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * WhatsApp-style full emoji picker shown as a bottom sheet when the user taps the "+"
 * inside the reaction bar.
 *
 * Built entirely programmatically so it has zero XML / drawable dependencies. The sheet
 * has three pieces, top-to-bottom:
 *
 *   1. Drag handle.
 *   2. Sectioned emoji grid (RecyclerView, 8-column GridLayoutManager). Each category is
 *      preceded by a sticky-style header row; the grid scrolls smoothly across all sections.
 *      A "Frequently used" section at the top reflects the user's most-recent picks (saved
 *      in [SharedPreferences]).
 *   3. Sticky bottom category bar — fixed at the sheet bottom with one icon per category;
 *      tapping an icon scrolls the grid to that section, and scrolling updates the active
 *      icon highlight.
 */
class ReactionEmojiPickerSheet : BottomSheetDialogFragment() {

    private var onPick: ((String) -> Unit)? = null
    private lateinit var rv: RecyclerView
    private lateinit var categoryBar: LinearLayout
    private lateinit var adapter: SectionedEmojiAdapter
    private val categoryButtons = mutableListOf<TextView>()
    private var activeCategoryIdx = 0

    fun setOnPickListener(listener: (String) -> Unit) {
        onPick = listener
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
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── 1. Drag handle ─────────────────────────────────────────────────
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(2f).toFloat()
                setColor(Color.parseColor("#5A5A5A"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8f)
                bottomMargin = dp(8f)
            }
        }
        root.addView(handle)

        // ── 2. Sectioned emoji grid ────────────────────────────────────────
        val recents = loadRecents(ctx)
        val categories = buildCategories(recents)
        adapter = SectionedEmojiAdapter(categories) { emoji ->
            saveRecent(ctx, emoji)
            onPick?.invoke(emoji)
            dismissAllowingStateLoss()
        }

        val sectionedAdapter = adapter   // capture outer field; inside apply{} 'adapter' would resolve to RecyclerView.adapter
        rv = RecyclerView(ctx).apply {
            val lm = GridLayoutManager(ctx, 8)
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (sectionedAdapter.isHeader(position)) 8 else 1
            }
            layoutManager = lm
            setHasFixedSize(false)
            setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
            clipToPadding = false
            // ~55% of screen height — leaves room for the category bar below.
            val maxH = (Resources.getSystem().displayMetrics.heightPixels * 0.55f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxH
            )
        }
        rv.adapter = adapter

        // Update active category indicator while the user scrolls the grid.
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val first = (recyclerView.layoutManager as GridLayoutManager)
                    .findFirstVisibleItemPosition()
                if (first < 0) return
                val idx = adapter.categoryIndexForPosition(first)
                if (idx != activeCategoryIdx) setActiveCategory(idx, scroll = false)
            }
        })

        root.addView(rv)

        // Thin divider between the grid and the category bar.
        val divider = View(ctx).apply {
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(0.5f).coerceAtLeast(1)
            )
        }
        root.addView(divider)

        // ── 3. Sticky bottom category navigation bar ───────────────────────
        categoryBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        categories.forEachIndexed { idx, cat ->
            val btn = TextView(ctx).apply {
                text = cat.icon
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                includeFontPadding = false
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f)
                setOnClickListener { setActiveCategory(idx, scroll = true) }
            }
            categoryButtons.add(btn)
            categoryBar.addView(btn)
        }
        root.addView(categoryBar)

        // Initial highlight.
        setActiveCategory(0, scroll = false)

        return root
    }

    private fun setActiveCategory(idx: Int, scroll: Boolean) {
        if (idx < 0 || idx >= categoryButtons.size) return
        activeCategoryIdx = idx
        val density = Resources.getSystem().displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        categoryButtons.forEachIndexed { i, btn ->
            btn.background = if (i == idx) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10f).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                }
            } else null
        }
        if (scroll) {
            val pos = adapter.headerPositionForCategory(idx)
            (rv.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(pos, 0)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.let { sheet ->
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    // ── Recents persistence ────────────────────────────────────────────────

    private fun loadRecents(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val csv = prefs.getString(KEY_RECENTS, "") ?: ""
        return csv.split(SEP).filter { it.isNotBlank() }
    }

    private fun saveRecent(ctx: Context, emoji: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = (prefs.getString(KEY_RECENTS, "") ?: "")
            .split(SEP).filter { it.isNotBlank() }.toMutableList()
        current.remove(emoji)
        current.add(0, emoji)
        while (current.size > MAX_RECENTS) current.removeAt(current.size - 1)
        prefs.edit().putString(KEY_RECENTS, current.joinToString(SEP)).apply()
    }

    // ── Categories ─────────────────────────────────────────────────────────

    private data class Category(
        val key: String,
        val label: String,
        val icon: String,
        val emojis: List<String>
    )

    /** Build the category list, prepending "Frequently used" iff the user has any recents. */
    private fun buildCategories(recents: List<String>): List<Category> {
        val list = mutableListOf<Category>()
        if (recents.isNotEmpty()) {
            list += Category("recent", "Frequently used", "\uD83D\uDD52", recents)
        }
        list += Category("smileys", "Smileys & People", "\uD83D\uDE00", SMILEYS)
        list += Category("animals", "Animals & Nature", "\uD83D\uDC36", ANIMALS)
        list += Category("food",    "Food & Drink",     "\uD83C\uDF54", FOOD)
        list += Category("activity","Activities",       "\u26BD",       ACTIVITIES)
        list += Category("travel",  "Travel & Places",  "\uD83D\uDE97", TRAVEL)
        list += Category("objects", "Objects",          "\uD83D\uDCA1", OBJECTS)
        list += Category("symbols", "Symbols",          "\u2764\uFE0F", SYMBOLS)
        list += Category("flags",   "Flags",            "\uD83C\uDFC1", FLAGS)
        return list
    }

    // ── Adapter (sectioned: HEADER spans 8, EMOJI spans 1) ─────────────────

    private class SectionedEmojiAdapter(
        private val categories: List<Category>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        // Flat item list with type tags. We pre-compute it so getItemViewType / onBind are O(1).
        private sealed class Row {
            data class Header(val title: String) : Row()
            data class Emoji(val value: String) : Row()
        }

        private val items: List<Row>
        private val headerPositions: List<Int>

        init {
            val rows = mutableListOf<Row>()
            val headers = mutableListOf<Int>()
            categories.forEach { cat ->
                headers += rows.size
                rows += Row.Header(cat.label)
                cat.emojis.forEach { rows += Row.Emoji(it) }
            }
            items = rows
            headerPositions = headers
        }

        fun isHeader(position: Int): Boolean = items[position] is Row.Header

        fun headerPositionForCategory(idx: Int): Int =
            headerPositions.getOrElse(idx) { 0 }

        fun categoryIndexForPosition(position: Int): Int {
            // Largest header index that is <= position.
            var lo = 0
            var hi = headerPositions.size - 1
            var result = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (headerPositions[mid] <= position) {
                    result = mid; lo = mid + 1
                } else hi = mid - 1
            }
            return result
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is Row.Header) TYPE_HEADER else TYPE_EMOJI

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val density = Resources.getSystem().displayMetrics.density
            return if (viewType == TYPE_HEADER) {
                val tv = TextView(ctx).apply {
                    setTextColor(Color.parseColor("#AAAAAA"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    val pad = (8 * density).toInt()
                    setPadding(pad, (12 * density).toInt(), pad, (6 * density).toInt())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                HeaderVH(tv)
            } else {
                val size = (40 * density).toInt()
                val tv = TextView(ctx).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    includeFontPadding = false
                    layoutParams = ViewGroup.LayoutParams(size, size)
                    isClickable = true
                    isFocusable = true
                    background = createRipple(ctx)
                }
                EmojiVH(tv)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder as HeaderVH).text.text = row.title
                is Row.Emoji  -> {
                    val tv = (holder as EmojiVH).text
                    tv.text = row.value
                    tv.setOnClickListener { onClick(row.value) }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        private fun createRipple(ctx: Context): RippleDrawable {
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            return RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                null, mask
            )
        }

        private class HeaderVH(val text: TextView) : RecyclerView.ViewHolder(text)
        private class EmojiVH(val text: TextView) : RecyclerView.ViewHolder(text)

        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_EMOJI = 1
        }
    }

    companion object {
        const val TAG = "ReactionEmojiPickerSheet"
        private const val PREFS = "glyph_reaction_picker_prefs"
        private const val KEY_RECENTS = "recents_csv"
        private const val SEP = "\u241F" // unit-separator, won't collide with emoji bytes
        private const val MAX_RECENTS = 24

        fun show(fm: FragmentManager, onPick: (String) -> Unit) {
            val sheet = ReactionEmojiPickerSheet()
            sheet.setOnPickListener(onPick)
            sheet.show(fm, TAG)
        }

        // ── Curated emoji sets per category (covers the common ground) ─────

        private val SMILEYS = listOf(
            "\uD83D\uDE00","\uD83D\uDE03","\uD83D\uDE04","\uD83D\uDE01","\uD83D\uDE06","\uD83D\uDE05","\uD83E\uDD23","\uD83D\uDE02",
            "\uD83D\uDE42","\uD83D\uDE43","\uD83D\uDE09","\uD83D\uDE0A","\uD83D\uDE07","\uD83E\uDD70","\uD83D\uDE0D","\uD83E\uDD29",
            "\uD83D\uDE18","\uD83D\uDE17","\uD83D\uDE19","\uD83D\uDE1A","\uD83D\uDE0B","\uD83D\uDE1B","\uD83D\uDE1C","\uD83E\uDD2A",
            "\uD83D\uDE1D","\uD83E\uDD11","\uD83E\uDD17","\uD83E\uDD2D","\uD83E\uDD2B","\uD83E\uDD14","\uD83E\uDD10","\uD83E\uDD28",
            "\uD83D\uDE10","\uD83D\uDE11","\uD83D\uDE36","\uD83D\uDE0F","\uD83D\uDE12","\uD83D\uDE44","\uD83D\uDE2C","\uD83E\uDD25",
            "\uD83D\uDE0C","\uD83D\uDE14","\uD83D\uDE2A","\uD83E\uDD24","\uD83D\uDE34","\uD83D\uDE37","\uD83E\uDD12","\uD83E\uDD15",
            "\uD83E\uDD22","\uD83E\uDD2E","\uD83E\uDD27","\uD83D\uDE35","\uD83E\uDD2F","\uD83E\uDD20","\uD83E\uDD73","\uD83D\uDE0E",
            "\uD83E\uDD13","\uD83E\uDDD0","\uD83D\uDE15","\uD83D\uDE1F","\uD83D\uDE41","\u2639\uFE0F","\uD83D\uDE2E","\uD83D\uDE2F",
            "\uD83D\uDE32","\uD83D\uDE33","\uD83D\uDE26","\uD83D\uDE27","\uD83D\uDE28","\uD83D\uDE30","\uD83D\uDE25","\uD83D\uDE22",
            "\uD83D\uDE2D","\uD83D\uDE31","\uD83D\uDE16","\uD83D\uDE23","\uD83D\uDE1E","\uD83D\uDE13","\uD83D\uDE29","\uD83D\uDE2B",
            "\uD83D\uDE24","\uD83D\uDE21","\uD83D\uDE20","\uD83E\uDD2C","\uD83D\uDE08","\uD83D\uDC7F","\uD83D\uDC80","\uD83D\uDC7B",
            "\uD83D\uDC4D","\uD83D\uDC4E","\uD83D\uDC4F","\uD83D\uDE4C","\uD83D\uDE4F","\uD83D\uDC4A","\u270C\uFE0F","\uD83E\uDD1E",
            "\uD83E\uDD1F","\uD83E\uDD18","\uD83D\uDC46","\uD83D\uDC47","\uD83D\uDC48","\uD83D\uDC49","\uD83D\uDD95","\u270B"
        )

        private val ANIMALS = listOf(
            "\uD83D\uDC36","\uD83D\uDC31","\uD83D\uDC2D","\uD83D\uDC39","\uD83D\uDC30","\uD83E\uDD8A","\uD83D\uDC3B","\uD83D\uDC3C",
            "\uD83D\uDC28","\uD83D\uDC2F","\uD83E\uDD81","\uD83D\uDC2E","\uD83D\uDC37","\uD83D\uDC38","\uD83D\uDC35","\uD83D\uDE48",
            "\uD83D\uDE49","\uD83D\uDE4A","\uD83D\uDC12","\uD83D\uDC14","\uD83D\uDC27","\uD83D\uDC26","\uD83D\uDC24","\uD83D\uDC23",
            "\uD83E\uDD86","\uD83E\uDD89","\uD83E\uDD85","\uD83E\uDD87","\uD83D\uDC3A","\uD83D\uDC17","\uD83D\uDC34","\uD83E\uDD84",
            "\uD83D\uDC1D","\uD83D\uDC1B","\uD83E\uDD8B","\uD83D\uDC0C","\uD83D\uDC1E","\uD83D\uDC1C","\uD83E\uDD97","\uD83D\uDD77",
            "\uD83E\uDD82","\uD83E\uDD80","\uD83E\uDD9E","\uD83D\uDC19","\uD83D\uDC20","\uD83D\uDC1F","\uD83D\uDC21","\uD83D\uDC2C",
            "\uD83D\uDC33","\uD83D\uDC0B","\uD83E\uDD88","\uD83D\uDC22","\uD83D\uDC0A","\uD83D\uDC0D","\uD83D\uDC32","\uD83D\uDC09",
            "\uD83C\uDF35","\uD83C\uDF84","\uD83C\uDF32","\uD83C\uDF33","\uD83C\uDF34","\uD83C\uDF31","\uD83C\uDF40","\uD83C\uDF38",
            "\uD83C\uDF37","\uD83C\uDF39","\uD83C\uDF3A","\uD83C\uDF3B","\uD83C\uDF3C","\uD83C\uDF42","\uD83C\uDF43","\uD83C\uDF41"
        )

        private val FOOD = listOf(
            "\uD83C\uDF4F","\uD83C\uDF50","\uD83C\uDF51","\uD83C\uDF52","\uD83C\uDF53","\uD83C\uDF47","\uD83C\uDF48","\uD83C\uDF49",
            "\uD83C\uDF4A","\uD83C\uDF4B","\uD83C\uDF4C","\uD83C\uDF4D","\uD83E\uDD6D","\uD83C\uDF4E","\uD83C\uDF44","\uD83C\uDF45",
            "\uD83C\uDF46","\uD83E\uDD51","\uD83E\uDD55","\uD83C\uDF3D","\uD83C\uDF36","\uD83E\uDD52","\uD83E\uDD6C","\uD83C\uDF55",
            "\uD83C\uDF56","\uD83C\uDF57","\uD83E\uDD69","\uD83C\uDF54","\uD83C\uDF5F","\uD83C\uDF5D","\uD83C\uDF2D","\uD83E\uDD6A",
            "\uD83C\uDF2E","\uD83C\uDF2F","\uD83E\uDD59","\uD83E\uDD5A","\uD83C\uDF73","\uD83E\uDD58","\uD83C\uDF72","\uD83E\uDD63",
            "\uD83C\uDF7F","\uD83C\uDF71","\uD83C\uDF58","\uD83C\uDF59","\uD83C\uDF5A","\uD83C\uDF5B","\uD83C\uDF5C","\uD83C\uDF5E",
            "\uD83C\uDF60","\uD83C\uDF61","\uD83C\uDF62","\uD83C\uDF63","\uD83C\uDF64","\uD83C\uDF65","\uD83C\uDF66","\uD83C\uDF67",
            "\uD83C\uDF68","\uD83C\uDF69","\uD83C\uDF6A","\uD83C\uDF82","\uD83C\uDF70","\uD83C\uDF6E","\uD83C\uDF6F","\uD83C\uDF7C",
            "\uD83C\uDF75","\u2615","\uD83C\uDF76","\uD83C\uDF7A","\uD83C\uDF7B","\uD83C\uDF77","\uD83C\uDF78","\uD83C\uDF79"
        )

        private val ACTIVITIES = listOf(
            "\u26BD","\uD83C\uDFC0","\uD83C\uDFC8","\u26BE","\uD83C\uDFBE","\uD83C\uDFD0","\uD83C\uDFC9","\uD83C\uDFB1",
            "\uD83C\uDFD3","\uD83C\uDFF8","\uD83E\uDD4A","\uD83C\uDFAF","\u26F3","\u26F8","\uD83C\uDFA3","\uD83C\uDFBD",
            "\uD83C\uDFBF","\u26F7","\uD83C\uDFC2","\uD83C\uDFC4","\uD83C\uDFCA","\uD83D\uDEA3","\uD83C\uDFC7","\uD83D\uDEB4",
            "\uD83D\uDEB5","\uD83C\uDFCB","\uD83E\uDD3A","\uD83E\uDD3C","\uD83E\uDD38","\u26F9","\uD83C\uDFCC","\uD83C\uDFC6",
            "\uD83C\uDF96","\uD83C\uDF97","\uD83C\uDFC5","\uD83C\uDFAA","\uD83C\uDFAD","\uD83C\uDFA8","\uD83C\uDFAC","\uD83C\uDFA4",
            "\uD83C\uDFA7","\uD83C\uDFB7","\uD83C\uDFB8","\uD83C\uDFB9","\uD83C\uDFBA","\uD83C\uDFBB","\uD83E\uDD41","\uD83C\uDFB2",
            "\uD83C\uDFAE","\uD83C\uDFB0","\uD83E\uDDE9","\uD83C\uDFB4","\uD83C\uDFB5","\uD83C\uDFB6","\uD83C\uDF9F","\uD83C\uDFAB"
        )

        private val TRAVEL = listOf(
            "\uD83D\uDE97","\uD83D\uDE95","\uD83D\uDE99","\uD83D\uDE8C","\uD83D\uDE8E","\uD83C\uDFCE","\uD83D\uDE93","\uD83D\uDE92",
            "\uD83D\uDE91","\uD83D\uDE90","\uD83D\uDE9A","\uD83D\uDE9B","\uD83D\uDE9C","\uD83D\uDEF4","\uD83D\uDEB2","\uD83D\uDEF5",
            "\uD83C\uDFCD","\uD83D\uDE8D","\uD83D\uDE9F","\uD83D\uDEA8","\uD83D\uDE94","\uD83D\uDE98","\uD83D\uDE96","\uD83D\uDE9E",
            "\uD83D\uDE85","\uD83D\uDE84","\uD83D\uDE88","\uD83D\uDE82","\uD83D\uDE86","\uD83D\uDE87","\uD83D\uDE83","\uD83D\uDE89",
            "\uD83D\uDE81","\u2708\uFE0F","\uD83D\uDEEB","\uD83D\uDEEC","\uD83D\uDE80","\uD83D\uDEF0","\uD83D\uDCBA","\uD83D\uDEA4",
            "\u26F5","\uD83D\uDEA2","\u2693","\u26FD","\uD83D\uDEA7","\uD83D\uDEA6","\uD83D\uDEA5","\uD83D\uDDFA","\uD83D\uDDFD",
            "\uD83D\uDDFC","\uD83C\uDFDB","\u26F2","\uD83C\uDFDF","\uD83C\uDFA1","\uD83C\uDFA2","\uD83C\uDFA0","\u26F1","\uD83C\uDFD6"
        )

        private val OBJECTS = listOf(
            "\u231A","\uD83D\uDCF1","\uD83D\uDCBB","\u2328\uFE0F","\uD83D\uDDA5","\uD83D\uDDA8","\uD83D\uDDB1","\uD83D\uDDB2",
            "\uD83D\uDD79","\uD83D\uDDDC","\uD83D\uDCBD","\uD83D\uDCBE","\uD83D\uDCBF","\uD83D\uDCC0","\uD83D\uDCFC","\uD83D\uDCF7",
            "\uD83D\uDCF8","\uD83D\uDCF9","\uD83C\uDFA5","\uD83D\uDCFD","\uD83C\uDF9E","\uD83D\uDCDE","\u260E\uFE0F","\uD83D\uDCDF",
            "\uD83D\uDCE0","\uD83D\uDCFA","\uD83D\uDCFB","\uD83C\uDF99","\uD83C\uDF9A","\uD83C\uDF9B","\u23F1","\u23F2","\u23F0",
            "\uD83D\uDD70","\u231B","\u23F3","\uD83D\uDCA1","\uD83D\uDD26","\uD83D\uDD6F","\uD83E\uDDEF","\uD83D\uDED2","\uD83D\uDD0C",
            "\uD83D\uDD0B","\uD83D\uDDDD","\uD83D\uDD27","\uD83D\uDD28","\u2692","\uD83D\uDEE0","\u26CF","\uD83E\uDD1A","\uD83D\uDD29",
            "\u2699\uFE0F","\uD83D\uDD17","\u26D3","\u2697\uFE0F","\uD83D\uDC8A","\uD83D\uDC89","\uD83E\uDDEC","\uD83D\uDD2C","\uD83D\uDD2D"
        )

        private val SYMBOLS = listOf(
            "\u2764\uFE0F","\uD83E\uDDE1","\uD83D\uDC9B","\uD83D\uDC9A","\uD83D\uDC99","\uD83D\uDC9C","\uD83D\uDD8A","\uD83D\uDDA4",
            "\uD83D\uDC94","\u2763\uFE0F","\uD83D\uDC95","\uD83D\uDC9E","\uD83D\uDC93","\uD83D\uDC97","\uD83D\uDC96","\uD83D\uDC98",
            "\uD83D\uDC9D","\uD83D\uDC9F","\u262E\uFE0F","\u271D\uFE0F","\u262A\uFE0F","\uD83D\uDD49","\u2638\uFE0F","\u2721\uFE0F",
            "\uD83D\uDD2F","\uD83D\uDD46","\u262F\uFE0F","\u2626\uFE0F","\uD83D\uDD4E","\uD83D\uDD2F","\uD83D\uDD2E","\uD83D\uDCAF",
            "\uD83D\uDD20","\uD83D\uDD21","\uD83D\uDD22","\uD83D\uDD23","\uD83D\uDD24","\u2705","\u274C","\u2B55","\u26D4","\u26A0\uFE0F",
            "\uD83D\uDEAB","\uD83D\uDEAD","\uD83D\uDEAF","\uD83D\uDEB1","\uD83D\uDEB3","\u203C\uFE0F","\u2049\uFE0F","\u2753","\u2754",
            "\u2755","\u2757","\u3030\uFE0F","\u00A9\uFE0F","\u00AE\uFE0F","\u2122\uFE0F","\u2611\uFE0F","\u2714\uFE0F","\u2716\uFE0F"
        )

        private val FLAGS = listOf(
            "\uD83C\uDFC1","\uD83D\uDEA9","\uD83C\uDF8C","\uD83C\uDFF4","\uD83C\uDFF3\uFE0F","\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08",
            "\uD83C\uDDFA\uD83C\uDDF8","\uD83C\uDDEC\uD83C\uDDE7","\uD83C\uDDEB\uD83C\uDDF7","\uD83C\uDDE9\uD83C\uDDEA",
            "\uD83C\uDDEE\uD83C\uDDF9","\uD83C\uDDEA\uD83C\uDDF8","\uD83C\uDDF5\uD83C\uDDF9","\uD83C\uDDF7\uD83C\uDDFA",
            "\uD83C\uDDE8\uD83C\uDDF3","\uD83C\uDDEF\uD83C\uDDF5","\uD83C\uDDF0\uD83C\uDDF7","\uD83C\uDDEE\uD83C\uDDF3",
            "\uD83C\uDDE7\uD83C\uDDF7","\uD83C\uDDE8\uD83C\uDDE6","\uD83C\uDDE6\uD83C\uDDFA","\uD83C\uDDF2\uD83C\uDDFD",
            "\uD83C\uDDFF\uD83C\uDDE6","\uD83C\uDDF3\uD83C\uDDEC","\uD83C\uDDEA\uD83C\uDDEC","\uD83C\uDDF8\uD83C\uDDE6",
            "\uD83C\uDDE6\uD83C\uDDEA","\uD83C\uDDF9\uD83C\uDDF7","\uD83C\uDDF5\uD83C\uDDF0","\uD83C\uDDE7\uD83C\uDDE9",
            "\uD83C\uDDF1\uD83C\uDDF0","\uD83C\uDDF3\uD83C\uDDF5","\uD83C\uDDE6\uD83C\uDDEB","\uD83C\uDDEE\uD83C\uDDF7"
        )
    }
}
