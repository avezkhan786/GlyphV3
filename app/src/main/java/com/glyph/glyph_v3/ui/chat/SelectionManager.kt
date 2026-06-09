package com.glyph.glyph_v3.ui.chat

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class SelectionManager {
    private val _selectedIds = MutableLiveData<Set<String>>(emptySet())
    val selectedIds: LiveData<Set<String>> = _selectedIds
    
    var onSelectionChanged: ((String) -> Unit)? = null

    fun toggleSelection(id: String) {
        val currentSelection = _selectedIds.value.orEmpty().toMutableSet()
        if (currentSelection.contains(id)) {
            currentSelection.remove(id)
        } else {
            currentSelection.add(id)
        }
        _selectedIds.value = currentSelection
        onSelectionChanged?.invoke(id)
    }

    fun select(id: String) {
        val currentSelection = _selectedIds.value.orEmpty().toMutableSet()
        currentSelection.add(id)
        _selectedIds.value = currentSelection
        onSelectionChanged?.invoke(id)
    }

    fun deselect(id: String) {
        val currentSelection = _selectedIds.value.orEmpty().toMutableSet()
        currentSelection.remove(id)
        _selectedIds.value = currentSelection
        onSelectionChanged?.invoke(id)
    }

    fun clearSelection() {
        val oldSelection = _selectedIds.value.orEmpty()
        _selectedIds.value = emptySet()
        oldSelection.forEach { onSelectionChanged?.invoke(it) }
    }

    fun isSelected(id: String): Boolean {
        return _selectedIds.value?.contains(id) == true
    }

    fun hasSelection(): Boolean {
        return _selectedIds.value?.isNotEmpty() == true
    }
    
    fun getSelectedCount(): Int {
        return _selectedIds.value?.size ?: 0
    }
    
    fun getSelectedIds(): Set<String> {
        return _selectedIds.value ?: emptySet()
    }
}
