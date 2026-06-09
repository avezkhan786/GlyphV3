package com.glyph.glyph_v3.ui.chat.translation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.glyph.glyph_v3.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet for selecting the target translation language.
 *
 * Shows a scrollable list of supported languages with flags.
 * The currently selected language is highlighted with a check mark.
 */
class LanguageSelectorSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_SELECTED_CODE = "selected_code"

        fun newInstance(
            selectedCode: String,
            onLanguageSelected: (TranslationLanguage) -> Unit
        ): LanguageSelectorSheet {
            return LanguageSelectorSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SELECTED_CODE, selectedCode)
                }
                this.onLanguageSelected = onLanguageSelected
            }
        }
    }

    private var onLanguageSelected: ((TranslationLanguage) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_language_selector, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val selectedCode = arguments?.getString(ARG_SELECTED_CODE) ?: TranslationLanguage.DEFAULT.code
        val rv = view.findViewById<RecyclerView>(R.id.rvLanguages)
        val etSearch = view.findViewById<android.widget.EditText>(R.id.etSearchLanguage)
        
        rv.layoutManager = LinearLayoutManager(requireContext())
        
        val allLanguages = TranslationLanguage.entries.toList()
        val prefs = TranslationPreferences.getInstance(requireContext())
        val recentCodes = prefs.recentLanguageCodes
        
        val adapter = LanguageAdapter(
            allLanguages = allLanguages,
            recentCodes = recentCodes,
            selectedCode = selectedCode
        ) { language ->
            // Update Recents
            prefs.addRecentLanguage(language.code)
            onLanguageSelected?.invoke(language)
            dismiss()
        }
        rv.adapter = adapter

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    /**
     * Enhanced Adapter supporting headers and filtering.
     */
    private class LanguageAdapter(
        private val allLanguages: List<TranslationLanguage>,
        private val recentCodes: List<String>,
        private val selectedCode: String,
        private val onClick: (TranslationLanguage) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        sealed class Item {
            data class Header(val title: String) : Item()
            data class Language(val language: TranslationLanguage) : Item()
        }

        private var items: List<Item> = buildList(null)

        private fun buildList(query: String?): List<Item> {
            val result = mutableListOf<Item>()
            
            // Filter mode
            if (!query.isNullOrBlank()) {
                val filtered = allLanguages.filter { 
                    it.displayName.contains(query, ignoreCase = true) || 
                    it.nativeName.contains(query, ignoreCase = true) 
                }
                if (filtered.isNotEmpty()) {
                    filtered.forEach { result.add(Item.Language(it)) }
                }
                return result
            }

            // Default mode (Recents + All)
            val recentLangs = recentCodes.mapNotNull { code -> 
                allLanguages.find { it.code == code } 
            }

            if (recentLangs.isNotEmpty()) {
                result.add(Item.Header("Recent"))
                recentLangs.forEach { result.add(Item.Language(it)) }
                result.add(Item.Header("All Languages"))
            }

            allLanguages.forEach { result.add(Item.Language(it)) }
            return result
        }

        fun filter(query: String?) {
            items = buildList(query)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is Item.Header -> 0
                is Item.Language -> 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                HeaderVH(inflater.inflate(R.layout.item_picker_header, parent, false))
            } else {
                LanguageVH(inflater.inflate(R.layout.item_language, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder as HeaderVH).bind(item.title)
                is Item.Language -> (holder as LanguageVH).bind(item.language, item.language.code == selectedCode)
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvHeader: TextView = itemView.findViewById(R.id.tvHeader)
            fun bind(title: String) {
                tvHeader.text = title
            }
        }

        inner class LanguageVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvFlag: TextView = itemView.findViewById(R.id.tvFlag)
            private val tvName: TextView = itemView.findViewById(R.id.tvLanguageName)
            private val ivCheck: ImageView = itemView.findViewById(R.id.ivCheck)

            fun bind(lang: TranslationLanguage, isSelected: Boolean) {
                tvFlag.text = lang.flag
                tvName.text = lang.displayName
                ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                itemView.setOnClickListener { onClick(lang) }
            }
        }
    }
}
