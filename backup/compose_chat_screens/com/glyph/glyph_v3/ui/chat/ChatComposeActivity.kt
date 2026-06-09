package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.glyph.glyph_v3.data.local.AppDatabase
import com.glyph.glyph_v3.data.media.MediaTransferManager
import com.glyph.glyph_v3.data.repo.RealtimeMessageRepository
import com.glyph.glyph_v3.utils.ThemeManager
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import androidx.core.view.WindowCompat
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.SharedPreferences

class ChatComposeActivity : ComponentActivity() {
    
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
    }
    
    companion object {
        private const val PREF_USE_OPTIMIZED = "use_optimized_chat"
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_OTHER_USER_ID = "other_user_id"
        private const val EXTRA_OTHER_USERNAME = "other_username"
        private const val EXTRA_OTHER_USER_AVATAR = "other_user_avatar"

        fun newIntent(
            context: Context,
            chatId: String,
            otherUserId: String,
            otherUsername: String = "",
            otherUserAvatar: String = ""
        ): Intent {
            return Intent(context, ChatComposeActivity::class.java).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_OTHER_USER_ID, otherUserId)
                putExtra(EXTRA_OTHER_USERNAME, otherUsername)
                putExtra(EXTRA_OTHER_USER_AVATAR, otherUserAvatar)
            }
        }
    }
    
    private val chatId by lazy { 
        val id = intent.getStringExtra(EXTRA_CHAT_ID) ?: ""
        Log.d("ChatComposeActivity", "chatId: $id")
        id
    }
    private val otherUserId by lazy { 
        val id = intent.getStringExtra(EXTRA_OTHER_USER_ID) ?: ""
        Log.d("ChatComposeActivity", "otherUserId: $id")
        id
    }
    private val otherUsername by lazy { intent.getStringExtra(EXTRA_OTHER_USERNAME) ?: "" }
    private val otherUserAvatar by lazy { intent.getStringExtra(EXTRA_OTHER_USER_AVATAR) ?: "" }

    private val viewModel: ChatViewModel by viewModels {
        Log.d("ChatComposeActivity", "Initializing ViewModel for chatId: $chatId")
        val db = AppDatabase.getDatabase(applicationContext)
        val repository = RealtimeMessageRepository(db.messageDao(), db.chatDao(), db.deletedMessageDao(), applicationContext)
        val mediaTransferManager = MediaTransferManager.getInstance(applicationContext, db.messageDao())
        
        ChatViewModelFactory(
            applicationContext,
            repository,
            mediaTransferManager,
            chatId,
            otherUserId,
            otherUsername,
            otherUserAvatar
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            GlyphThemeProvider {
                val uiState by viewModel.uiState.collectAsState()
                
                // State for showing selector dialog and which screen to use
                var showSelectorDialog by remember { mutableStateOf(false) }
                var useOptimizedScreen by remember { 
                    mutableStateOf(prefs.getBoolean(PREF_USE_OPTIMIZED, false))
                }
                
                // Show selector dialog on every launch
                LaunchedEffect(Unit) {
                    showSelectorDialog = true
                }

                val toastMessage = uiState.toastMessage
                LaunchedEffect(toastMessage) {
                    if (!toastMessage.isNullOrBlank()) {
                        Toast.makeText(this@ChatComposeActivity, toastMessage, Toast.LENGTH_SHORT).show()
                        viewModel.consumeToastMessage()
                    }
                }
                
                // Show selector dialog
                if (showSelectorDialog) {
                    ChatScreenSelectorDialog(
                        onDismiss = { 
                            showSelectorDialog = false
                            // If no preference set, default to optimized
                            if (!prefs.contains(PREF_USE_OPTIMIZED)) {
                                prefs.edit().putBoolean(PREF_USE_OPTIMIZED, true).apply()
                            }
                        },
                        onSelectXml = {
                            // Close this activity and launch XML ChatActivity
                            showSelectorDialog = false
                            val xmlIntent = Intent(this@ChatComposeActivity, ChatActivity::class.java).apply {
                                putExtra("chat_id", chatId)
                                putExtra("other_user_id", otherUserId)
                                putExtra("other_user_phone", uiState.otherUserPhone)
                                putExtra("other_username", otherUsername)
                            }
                            startActivity(xmlIntent)
                            finish()
                        },
                        onSelectOriginal = {
                            useOptimizedScreen = false
                            prefs.edit().putBoolean(PREF_USE_OPTIMIZED, false).apply()
                            showSelectorDialog = false
                        },
                        onSelectOptimized = {
                            useOptimizedScreen = true
                            prefs.edit().putBoolean(PREF_USE_OPTIMIZED, true).apply()
                            showSelectorDialog = false
                        }
                    )
                }
                
                // Render selected chat screen
                if (useOptimizedScreen) {
                    ChatScreenV2(
                        uiState = uiState,
                        onSendMessage = viewModel::sendMessage,
                        onTyping = viewModel::onTyping,
                        onBackClick = { finish() },
                        onSendMedia = viewModel::sendMedia,
                        onSendVoice = viewModel::sendVoice,
                        onDownloadMedia = viewModel::downloadMedia,
                        onToggleSelection = viewModel::toggleSelection,
                        onClearSelection = viewModel::clearSelection,
                        onDeleteSelected = viewModel::deleteSelectedMessages,
                        onDeleteSelectedForAll = viewModel::deleteSelectedMessagesForAll,
                        onCopySelected = { 
                            val text = viewModel.getSelectedMessagesText()
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Copied Text", text)
                            clipboard.setPrimaryClip(clip)
                            viewModel.clearSelection()
                        },
                        onPinSelected = viewModel::pinSelectedMessages,
                        onReply = viewModel::onReply,
                        onCancelReply = viewModel::onCancelReply,
                        onEnterEditMode = viewModel::enterEditMode,
                        onCancelEditMode = viewModel::cancelEditMode,
                        onViewDetails = {
                            val message = viewModel.getSelectedMessage()
                            if (message != null) {
                                startActivity(
                                    MessageDetailsActivity.newIntent(
                                        this@ChatComposeActivity,
                                        message,
                                        currentUserPhone = uiState.currentUserPhone,
                                        otherUserPhone = uiState.otherUserPhone,
                                        otherUsername = uiState.otherUserUsername
                                    )
                                )
                                viewModel.clearSelection()
                            }
                        }
                    )
                } else {
                    ChatScreen(
                        uiState = uiState,
                        onSendMessage = viewModel::sendMessage,
                        onTyping = viewModel::onTyping,
                        onBackClick = { finish() },
                        onSendMedia = viewModel::sendMedia,
                        onSendVoice = viewModel::sendVoice,
                        onDownloadMedia = viewModel::downloadMedia,
                        onToggleSelection = viewModel::toggleSelection,
                        onClearSelection = viewModel::clearSelection,
                        onDeleteSelected = viewModel::deleteSelectedMessages,
                        onDeleteSelectedForAll = viewModel::deleteSelectedMessagesForAll,
                        onCopySelected = { 
                            val text = viewModel.getSelectedMessagesText()
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Copied Text", text)
                            clipboard.setPrimaryClip(clip)
                            viewModel.clearSelection()
                        },
                        onPinSelected = viewModel::pinSelectedMessages,
                        onReply = viewModel::onReply,
                        onCancelReply = viewModel::onCancelReply,
                        onEnterEditMode = viewModel::enterEditMode,
                        onCancelEditMode = viewModel::cancelEditMode,
                        onViewDetails = {
                            val message = viewModel.getSelectedMessage()
                            if (message != null) {
                                startActivity(
                                    MessageDetailsActivity.newIntent(
                                        this@ChatComposeActivity,
                                        message,
                                        currentUserPhone = uiState.currentUserPhone,
                                        otherUserPhone = uiState.otherUserPhone,
                                        otherUsername = uiState.otherUserUsername
                                    )
                                )
                                viewModel.clearSelection()
                            }
                        }
                    )
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Mark this chat as active to suppress notifications for this conversation
        if (chatId.isNotEmpty()) {
            com.glyph.glyph_v3.data.service.ActiveChatManager.setActiveChat(chatId, this)
        }
        viewModel.setViewingChat(true)
        viewModel.onChatResumed()
    }
    
    override fun onPause() {
        super.onPause()
        // Clear active chat so notifications resume for other screens / chats
        com.glyph.glyph_v3.data.service.ActiveChatManager.clearActiveChat()
        viewModel.setViewingChat(false)
        viewModel.onChatPaused()
    }
}
