package com.glyph.glyph_v3.ui.chat

/**
 * INTEGRATION EXAMPLE: How to add quoted reply preview to existing message bubbles
 * 
 * This file shows how to integrate the QuotedReplyPreview component into
 * TextMessageBubble and MediaMessageBubble functions.
 */

// ========================================
// EXAMPLE 1: TextMessageBubble Integration
// ========================================

/*
BEFORE (existing code):

@Composable
fun TextMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    // ... other params
    modifier: Modifier = Modifier
) {
    // ... existing pre-measure code ...
    
    Spacer(
        modifier = modifier
            .size(width = bubbleWidth, height = bubbleHeight)
            .drawWithCache {
                // ... existing drawing code ...
            }
    )
}

AFTER (with quoted reply):

@Composable
fun TextMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    // ... other params
    currentUserPhone: String = "",
    otherUsername: String = "Other User",  // ADD THIS PARAMETER
    modifier: Modifier = Modifier
) {
    // ... existing pre-measure code ...
    
    // WRAP IN COLUMN IF MESSAGE HAS REPLY
    if (message.hasReplyMetadata()) {
        Column(modifier = modifier) {
            message.RenderQuotedPreviewIfExists(
                isSelf = isSelf,
                currentUserPhone = currentUserPhone,
                otherUsername = otherUsername,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            
            Spacer(
                modifier = Modifier
                    .size(width = bubbleWidth, height = bubbleHeight)
                    .drawWithCache {
                        // ... existing drawing code ...
                    }
            )
        }
    } else {
        // Original single Spacer for non-reply messages
        Spacer(
            modifier = modifier
                .size(width = bubbleWidth, height = bubbleHeight)
                .drawWithCache {
                    // ... existing drawing code ...
                }
        )
    }
}
*/

// ========================================
// EXAMPLE 2: MediaMessageBubble Integration
// ========================================

/*
BEFORE (existing code):

@Composable
fun MediaMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    // ... other params
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(maxWidth)
            .aspectRatio(aspectRatio)
            .drawWithCache {
                // ... existing drawing code ...
            }
    ) {
        // Edge-to-edge media
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(...)
        }
    }
}

AFTER (with quoted reply):

@Composable
fun MediaMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    // ... other params
    currentUserPhone: String = "",
    otherUsername: String = "Other User",  // ADD THIS PARAMETER
    modifier: Modifier = Modifier
) {
    // WRAP IN COLUMN IF MESSAGE HAS REPLY
    if (message.hasReplyMetadata()) {
        Column(modifier = modifier) {
            message.RenderQuotedPreviewIfExists(
                isSelf = isSelf,
                currentUserPhone = currentUserPhone,
                otherUsername = otherUsername,
                modifier = Modifier.padding(8.dp).padding(bottom = 4.dp)
            )
            
            Box(
                modifier = Modifier
                    .width(maxWidth)
                    .aspectRatio(aspectRatio)
                    .drawWithCache {
                        // ... existing drawing code ...
                    }
            ) {
                // Edge-to-edge media
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(...)
                }
            }
        }
    } else {
        // Original Box for non-reply messages
        Box(
            modifier = modifier
                .width(maxWidth)
                .aspectRatio(aspectRatio)
                .drawWithCache {
                    // ... existing drawing code ...
                }
        ) {
            // Edge-to-edge media
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(...)
            }
        }
    }
}
*/

// ========================================
// EXAMPLE 3: MessageBubble Parameter Propagation
// ========================================

/*
In MessageBubble function, when calling TextMessageBubble or MediaMessageBubble:

BEFORE:
TextMessageBubble(
    message = message,
    shape = shape,
    maxWidth = maxWidth,
    // ... other params
    modifier = bubbleModifier
)

AFTER:
TextMessageBubble(
    message = message,
    shape = shape,
    maxWidth = maxWidth,
    // ... other params
    currentUserPhone = currentUserPhone,  // ADD THIS
    modifier = bubbleModifier
)
*/

// ========================================
// EXAMPLE 4: ViewModel sendMessage Integration
// ========================================

/*
In ChatViewModel.kt:

fun sendMessage(text: String) {
    if (text.isBlank()) return
    
    val currentState = _uiState.value
    val replyTo = currentState.replyToMessage
    
    val message = Message(
        id = UUID.randomUUID().toString(),
        chatId = chatId,
        text = text.trim(),
        senderId = firebaseRepository.currentUserId ?: "",
        timestamp = System.currentTimeMillis(),
        status = MessageStatus.SENDING,
        isIncoming = false,
        type = MessageType.TEXT,
        
        // ADD REPLY METADATA
        replyToMessageId = replyTo?.id,
        replyToText = when(replyTo?.type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO -> "🎤 Audio"
            MessageType.MEDIA_GROUP -> "📷 Album"
            MessageType.CONTACT -> "👤 Contact"
            else -> replyTo?.text?.take(100) // Limit to 100 chars
        },
        replyToSenderId = replyTo?.senderId,
        replyToType = replyTo?.type
    )
    
    // Clear reply state and input
    _uiState.update { 
        it.copy(
            inputText = "",
            replyToMessage = null  // CLEAR REPLY STATE
        ) 
    }
    
    // Send message to repository
    viewModelScope.launch {
        repository.sendMessage(message)
    }
}

fun setReplyToMessage(message: Message) {
    _uiState.update { it.copy(replyToMessage = message) }
}

fun cancelReply() {
    _uiState.update { it.copy(replyToMessage = null) }
}
*/

// ========================================
// EXAMPLE 5: Firebase Persistence
// ========================================

/*
In RealtimeMessageRepository.kt or FirebaseRepository.kt:

fun sendMessage(message: Message) {
    val messageMap = hashMapOf(
        "id" to message.id,
        "chatId" to message.chatId,
        "text" to message.text,
        "senderId" to message.senderId,
        "timestamp" to message.timestamp,
        "status" to message.status.name,
        "isIncoming" to message.isIncoming,
        "type" to message.type.name,
        
        // ADD REPLY FIELDS
        "replyToMessageId" to message.replyToMessageId,
        "replyToText" to message.replyToText,
        "replyToSenderId" to message.replyToSenderId,
        "replyToType" to message.replyToType?.name
    )
    
    firestore.collection("chats")
        .document(message.chatId)
        .collection("messages")
        .document(message.id)
        .set(messageMap)
        .addOnSuccessListener {
            // Update local message status
        }
}

// When loading messages from Firestore:
private fun DocumentSnapshot.toMessage(): Message {
    return Message(
        id = getString("id") ?: "",
        chatId = getString("chatId") ?: "",
        text = getString("text") ?: "",
        senderId = getString("senderId") ?: "",
        timestamp = getLong("timestamp") ?: 0L,
        status = MessageStatus.valueOf(getString("status") ?: "SENT"),
        isIncoming = getBoolean("isIncoming") ?: false,
        type = MessageType.valueOf(getString("type") ?: "TEXT"),
        
        // LOAD REPLY FIELDS
        replyToMessageId = getString("replyToMessageId"),
        replyToText = getString("replyToText"),
        replyToSenderId = getString("replyToSenderId"),
        replyToType = getString("replyToType")?.let { MessageType.valueOf(it) }
    )
}
*/
