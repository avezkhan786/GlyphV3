package com.glyph.glyph_v3.ui.chat.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import com.glyph.glyph_v3.data.models.Message

/**
 * ChatInputState - Manages all input-related state with strict recomposition boundaries
 * 
 * Architecture Goals:
 * 1. High-frequency state (typing, cursor) isolated in smallest composables
 * 2. Low-frequency state (reply/edit previews) never cause input recomposition
 * 3. Zero lambda allocations during typing
 * 4. State split by update frequency
 * 
 * Usage:
 * - TextInputState: Read ONLY in InputSlot (high-frequency)
 * - ReplyEditState: Read ONLY in PreviewSlot (low-frequency)
 * - RecorderState: Read ONLY in OverlaySlot (high-frequency during recording)
 * - UIVisibilityState: Read ONLY where visibility decisions are made
 */

/**
 * TextInputState - High-frequency state for text input
 * CRITICAL: Only InputSlot should read this to prevent cascading recompositions
 */
@Stable
class TextInputState {
    private val _text = mutableStateOf("")
    
    val text: State<String> = _text
    
    /**
     * Derived state - avoids recomposition when only checking empty/non-empty
     * Use this in ActionSlot to decide mic vs send button
     */
    val hasText = derivedStateOf { _text.value.isNotEmpty() }
    
    fun updateText(newText: String) {
        _text.value = newText
    }
    
    fun clear() {
        _text.value = ""
    }
}

/**
 * ReplyEditState - Low-frequency state for reply/edit previews
 * Changes trigger PreviewSlot recomposition only, never InputSlot
 */
@Stable
class ReplyEditState {
    private val _replyToMessage = mutableStateOf<Message?>(null)
    private val _editingMessage = mutableStateOf<Message?>(null)
    
    val replyToMessage: State<Message?> = _replyToMessage
    val editingMessage: State<Message?> = _editingMessage
    
    // Derived states for visibility checks without reading full message
    val isReplying = derivedStateOf { _replyToMessage.value != null }
    val isEditing = derivedStateOf { _editingMessage.value != null }
    val hasPreview = derivedStateOf { _replyToMessage.value != null || _editingMessage.value != null }
    
    fun setReply(message: Message?) {
        // Clear edit mode when setting reply
        if (message != null) {
            _editingMessage.value = null
        }
        _replyToMessage.value = message
    }
    
    fun setEditing(message: Message?) {
        // Clear reply mode when setting edit
        if (message != null) {
            _replyToMessage.value = null
        }
        _editingMessage.value = message
    }
    
    fun clearReply() {
        _replyToMessage.value = null
    }
    
    fun clearEdit() {
        _editingMessage.value = null
    }
    
    fun clearAll() {
        _replyToMessage.value = null
        _editingMessage.value = null
    }
}

/**
 * RecorderState - High-frequency state during voice recording
 * CRITICAL: Amplitude updates at ~10Hz - must be isolated in smallest composable
 */
@Stable
class RecorderState {
    private val _isRecording = mutableStateOf(false)
    private val _isLocked = mutableStateOf(false)
    private val _duration = mutableStateOf("0:00")
    private val _amplitude = mutableStateOf(0)
    private val _recordingFile = mutableStateOf<java.io.File?>(null)
    private val _offsetX = mutableStateOf(0f)
    private val _offsetY = mutableStateOf(0f)
    
    val isRecording: State<Boolean> = _isRecording
    val isLocked: State<Boolean> = _isLocked
    val duration: State<String> = _duration
    val amplitude: State<Int> = _amplitude
    val recordingFile: State<java.io.File?> = _recordingFile
    val offsetX: State<Float> = _offsetX
    val offsetY: State<Float> = _offsetY
    
    // Derived states
    val isActiveRecording = derivedStateOf { _isRecording.value && !_isLocked.value }
    val isLockedRecording = derivedStateOf { _isRecording.value && _isLocked.value }
    
    fun startRecording(file: java.io.File) {
        _recordingFile.value = file
        _isRecording.value = true
        _isLocked.value = false
        _duration.value = "0:00"
        _amplitude.value = 0
        _offsetX.value = 0f
        _offsetY.value = 0f
    }
    
    fun lockRecording() {
        _isLocked.value = true
        _offsetX.value = 0f
        _offsetY.value = 0f
    }
    
    fun updateOffsets(x: Float, y: Float) {
        _offsetX.value = x
        _offsetY.value = y
    }
    
    fun updateDuration(newDuration: String) {
        _duration.value = newDuration
    }
    
    fun updateAmplitude(newAmplitude: Int) {
        _amplitude.value = newAmplitude
    }
    
    fun stopRecording(): java.io.File? {
        val file = _recordingFile.value
        _isRecording.value = false
        _isLocked.value = false
        _recordingFile.value = null
        _duration.value = "0:00"
        _amplitude.value = 0
        return file
    }
    
    fun cancelRecording() {
        _isRecording.value = false
        _isLocked.value = false
        _recordingFile.value = null
        _duration.value = "0:00"
        _amplitude.value = 0
    }
}

/**
 * UIVisibilityState - Controls overlay and menu visibility
 * Medium-frequency updates - affects overlay rendering
 */
@Stable
class UIVisibilityState {
    private val _showAttachmentMenu = mutableStateOf(false)
    private val _showEmojiPicker = mutableStateOf(false)
    private val _showCompressionDialog = mutableStateOf(false)
    
    val showAttachmentMenu: State<Boolean> = _showAttachmentMenu
    val showEmojiPicker: State<Boolean> = _showEmojiPicker
    val showCompressionDialog: State<Boolean> = _showCompressionDialog
    
    // Derived state to check if any overlay is showing
    val hasActiveOverlay = derivedStateOf {
        _showAttachmentMenu.value || _showEmojiPicker.value || _showCompressionDialog.value
    }
    
    fun toggleAttachmentMenu() {
        _showAttachmentMenu.value = !_showAttachmentMenu.value
        // Close other overlays
        if (_showAttachmentMenu.value) {
            _showEmojiPicker.value = false
        }
    }
    
    fun showAttachmentMenu() {
        _showAttachmentMenu.value = true
        _showEmojiPicker.value = false
    }
    
    fun hideAttachmentMenu() {
        _showAttachmentMenu.value = false
    }
    
    fun toggleEmojiPicker() {
        _showEmojiPicker.value = !_showEmojiPicker.value
        // Close other overlays
        if (_showEmojiPicker.value) {
            _showAttachmentMenu.value = false
        }
    }
    
    fun showEmojiPicker() {
        _showEmojiPicker.value = true
        _showAttachmentMenu.value = false
    }
    
    fun hideEmojiPicker() {
        _showEmojiPicker.value = false
    }
    
    fun showCompressionDialog() {
        _showCompressionDialog.value = true
        hideAllMenus()
    }
    
    fun hideCompressionDialog() {
        _showCompressionDialog.value = false
    }
    
    fun hideAllMenus() {
        _showAttachmentMenu.value = false
        _showEmojiPicker.value = false
    }
    
    fun hideAll() {
        _showAttachmentMenu.value = false
        _showEmojiPicker.value = false
        _showCompressionDialog.value = false
    }
}

/**
 * MessageListState - Controls list-level state without affecting message items
 */
@Stable
class MessageListState {
    private val _highlightedMessageId = mutableStateOf<String?>(null)
    private val _isUserScrolling = mutableStateOf(false)
    private val _swipingMessageId = mutableStateOf<String?>(null)
    
    val highlightedMessageId: State<String?> = _highlightedMessageId
    val isUserScrolling: State<Boolean> = _isUserScrolling
    val swipingMessageId: State<String?> = _swipingMessageId
    
    fun highlightMessage(messageId: String) {
        _highlightedMessageId.value = messageId
    }
    
    fun clearHighlight() {
        _highlightedMessageId.value = null
    }
    
    fun setUserScrolling(scrolling: Boolean) {
        _isUserScrolling.value = scrolling
    }
    
    fun startSwipe(messageId: String) {
        _swipingMessageId.value = messageId
    }
    
    fun endSwipe() {
        _swipingMessageId.value = null
    }
}

/**
 * Immutable snapshot of all input state for passing to ViewModel
 */
@Immutable
data class ChatInputSnapshot(
    val text: String,
    val replyToMessageId: String?,
    val editingMessageId: String?,
    val hasText: Boolean
)

/**
 * Factory function to create snapshot without reading individual states
 */
fun createInputSnapshot(
    textState: TextInputState,
    replyEditState: ReplyEditState
): ChatInputSnapshot {
    return ChatInputSnapshot(
        text = textState.text.value,
        replyToMessageId = replyEditState.replyToMessage.value?.id,
        editingMessageId = replyEditState.editingMessage.value?.id,
        hasText = textState.hasText.value
    )
}
