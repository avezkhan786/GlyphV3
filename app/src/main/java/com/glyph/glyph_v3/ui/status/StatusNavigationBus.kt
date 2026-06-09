package com.glyph.glyph_v3.ui.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StatusOpenRequest(
    val userId: String,
    val requestToken: Long = System.nanoTime()
)

object StatusNavigationBus {
    private val _pendingRequest = MutableStateFlow<StatusOpenRequest?>(null)
    val pendingRequest: StateFlow<StatusOpenRequest?> = _pendingRequest.asStateFlow()

    fun openContactStatus(userId: String) {
        if (userId.isBlank()) return
        _pendingRequest.value = StatusOpenRequest(userId = userId)
    }

    fun clear(request: StatusOpenRequest) {
        if (_pendingRequest.value == request) {
            _pendingRequest.value = null
        }
    }
}