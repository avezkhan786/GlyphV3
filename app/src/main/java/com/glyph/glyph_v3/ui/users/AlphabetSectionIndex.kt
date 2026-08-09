package com.glyph.glyph_v3.ui.users

import java.text.Normalizer
import java.util.Locale

/**
 * Computes an alphabetical index for the displayed Contact Selection list.
 *
 * Responsibilities:
 *  - Bucket each contact name under a "sort letter" (A..Z or "#" for anything else,
 *    including accented / emoji / numbers / symbols).
 *  - Insert sparse section-header rows so the user-visible row index of each
 *    contact is stable and discoverable.
 *  - Map a tapped or dragged letter to the *nearest available* contact row
 *    when the contact list has a gap for that letter.
 */
object AlphabeticalSortKey {
    /** Parses the first character of [name] into an upper-case A-Z letter, or "#". */
    fun letterFor(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "#"
        val first = trimmed[0]
        // Best-effort de-accent: NFD then drop combining marks, then upper-case.
        // Falls back to the raw char if normaliser isn't available on this VM.
        val decomposed = runCatching {
            Normalizer.normalize(first.toString(), Normalizer.Form.NFD)
        }.getOrNull().orEmpty()
        val base = decomposed.firstOrNull { ch -> ch.code > 0 && !ch.isSurrogate() } ?: first
        val upper = base.uppercaseChar()
        return if (upper in 'A'..'Z') upper.toString() else "#"
    }
}

/**
 * Snapshot of the currently-displayed contacts plus the alphabet bucket they
 * fall into. Fully derived — recomputed any time the filtered contact list
 * changes. Designed to be cheap; the list is small enough relative to one
 * frame budget that we recompute on the main thread, but never inside the
 * bind loop.
 */
class AlphabetSectionIndex(
    private val contacts: List<ContactListItem>,
) {
    /** Flat list shown in the RecyclerView, mixing header and item rows. */
    val rows: List<Row>

    /** Lower-case letter sorted set actually present in [contacts]. Used by the
     *  index view to highlight which letters are selectable for fast jumps. */
    val presentLetters: Set<String>

    /** Map of lower-case letter -> first row index of the matching contact section. */
    private val firstRowIndexByLetter: Map<String, Int>

    init {
        val buildRows = ArrayList<Row>(contacts.size)
        val firstSeen = HashMap<String, Int>(32)
        var lastLetter: String? = null
        for (c in contacts) {
            val letter = AlphabeticalSortKey.letterFor(c.name).uppercase(Locale.ROOT)
            if (letter != lastLetter) {
                buildRows += Row.Header(letter)
                lastLetter = letter
                // Record the row that *follows* this header as the start of the section.
                // We don't yet know whether a contact actually shows up — we overwrite
                // below if the section happens to be empty.
                if (!firstSeen.containsKey(letter)) firstSeen[letter] = buildRows.size
            }
            buildRows += Row.Item(c, letter)
        }
        // Drop headers whose section ended up empty (defensive: shouldn't happen given
        // how the loop above sets `lastLetter`, but cheap insurance).
        rows = buildRows.filterIndexed { i, row ->
            if (row is Row.Header) {
                val next = buildRows.getOrNull(i + 1)
                next is Row.Item && next.letter == row.letter
            } else true
        }
        // Rebuild first-row map from the filtered list so indices match the final rows.
        val final = HashMap<String, Int>(firstSeen.size)
        rows.forEachIndexed { idx, row ->
            if (row is Row.Header) final.putIfAbsent(row.letter, idx + 1)
        }
        firstRowIndexByLetter = final
        presentLetters = contacts
            .mapTo(HashSet(contacts.size)) { AlphabeticalSortKey.letterFor(it.name).uppercase(Locale.ROOT) }
    }

    /** First contact-row index for [letter], or null when no contact sits under it. */
    fun firstContactIndexFor(letter: String): Int? {
        val key = letter.uppercase(Locale.ROOT)
        val rowIndex = firstRowIndexByLetter[key] ?: return null
        val row = rows.getOrNull(rowIndex) ?: return null
        // Scroll target is the actual contact row (skip its header).
        return if (row is Row.Item) rowIndex else null
    }

    /**
     * Letter under which the row at flat index [idx] sits. Returns null when
     * [idx] is outside the rows range. Used by the host to derive the
     * "currently visible section" for the strip's persistent indicator.
     */
    fun sectionAtIndex(idx: Int): String? {
        if (idx < 0) return null
        return when (val row = rows.getOrNull(idx) ?: return null) {
            is Row.Header -> row.letter
            is Row.Item -> row.letter
        }
    }

    /**
     * Returns the row index to scroll to when the user lifts on [letter].
     * Falls back to the nearest alphabetical neighbor (preceding or following
     * section) when the pressed letter has no contacts.
     *
     * Falls back to `-1` only when [rows] is empty.
     */
    fun nearestIndexFor(letter: String): Int {
        if (rows.isEmpty()) return -1
        firstContactIndexFor(letter)?.let { return it }
        val target = letter.uppercase(Locale.ROOT).firstOrNull()?.toString().orEmpty()
        if (target.isEmpty()) return 0
        // Walk the union of all 27 alphabet slots in canonical order and pick the
        // closest present letter by codepoint distance. Cheap (27 iterations max).
        val presentChars = presentLetters.mapNotNull { it.firstOrNull()?.code }.toSet()
        if (presentChars.isEmpty()) return 0
        val targetCode = target.first().code
        val bestCode = presentChars.minBy { kotlin.math.abs(it - targetCode) }
        val bestLetter = bestCode.toChar().toString().uppercase(Locale.ROOT)
        return firstContactIndexFor(bestLetter) ?: 0
    }

    sealed class Row {
        abstract val stableKey: String

        data class Header(val letter: String) : Row() {
            override val stableKey: String = "header_$letter"
        }

        data class Item(val contact: ContactListItem, val letter: String) : Row() {
            override val stableKey: String = "item_${contact.phoneNumber}_${contact.name}"
        }
    }
}
