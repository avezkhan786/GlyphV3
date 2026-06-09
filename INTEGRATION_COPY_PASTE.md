# Copy-Paste Integration Code

## 📋 Step-by-Step Code Changes

### 1. Import the New Components (Add to top of ChatScreen.kt if needed)
```kotlin
import com.glyph.glyph_v3.ui.chat.QuotedReplyPreview
import com.glyph.glyph_v3.ui.chat.hasReplyMetadata
import com.glyph.glyph_v3.ui.chat.RenderQuotedPreviewIfExists
```

---

### 2. Update MessageBubble Function Signature

**Location**: ChatScreen.kt, line ~1234

**FIND:**
```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    groupPosition: BubbleGroupPosition,
    isSelf: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onNormalClick: () -> Unit = {},
    onReply: () -> Unit = {},
    context: Context,
    textMeasurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    progress: MediaProgressManager.MediaProgress? = null,
    isWarmingUp: Boolean = false,
    imagesEnabled: Boolean = true
) {
```

**REPLACE WITH:**
```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    groupPosition: BubbleGroupPosition,
    isSelf: Boolean,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onNormalClick: () -> Unit = {},
    onReply: () -> Unit = {},
    context: Context,
    textMeasurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
    onMediaClick: (Message, Int) -> Unit,
    onDownloadMedia: (Message) -> Unit,
    progress: MediaProgressManager.MediaProgress? = null,
    isWarmingUp: Boolean = false,
    imagesEnabled: Boolean = true,
    currentUserPhone: String = ""  // ← ADD THIS LINE
) {
```

---

### 3. Pass currentUserPhone to TextMessageBubble Call

**Location**: ChatScreen.kt, line ~1289 (inside MessageBubble function)

**FIND:**
```kotlin
        } else {
            // TEXT BUBBLE: Standard chat bubble
            TextMessageBubble(
                message = message,
                shape = shape,
                maxWidth = maxWidth,
                gradient = gradient,
                solidColor = solidColor,
                textColor = textColor,
                timestampColor = timestampColor,
                formattedTime = message.formattedTime,
                isSelf = isSelf,
                borderStroke = borderStroke,
                textMeasurer = textMeasurer,
                density = density,
                modifier = bubbleModifier
            )
        }
```

**REPLACE WITH:**
```kotlin
        } else {
            // TEXT BUBBLE: Standard chat bubble
            TextMessageBubble(
                message = message,
                shape = shape,
                maxWidth = maxWidth,
                gradient = gradient,
                solidColor = solidColor,
                textColor = textColor,
                timestampColor = timestampColor,
                formattedTime = message.formattedTime,
                isSelf = isSelf,
                borderStroke = borderStroke,
                textMeasurer = textMeasurer,
                density = density,
                currentUserPhone = currentUserPhone,  // ← ADD THIS LINE
                modifier = bubbleModifier
            )
        }
```

---

### 4. Pass currentUserPhone to MediaMessageBubble Call

**Location**: ChatScreen.kt, line ~1270 (inside MessageBubble function)

**FIND:**
```kotlin
            // PREMIUM MEDIA BUBBLE: WhatsApp-style edge-to-edge media
            MediaMessageBubble(
                message = message,
                shape = shape,
                maxWidth = maxWidth,
                gradient = gradient,
                solidColor = solidColor,
                formattedTime = message.formattedTime,
                isSelf = isSelf,
                timestampColor = timestampColor,
                borderStroke = borderStroke,
                context = context,
                onMediaClick = { onMediaClick(message, 0) },
                onDownloadClick = { onDownloadMedia(message) },
                mediaProgress = progress,
                isWarmingUp = isWarmingUp,
                imagesEnabled = imagesEnabled,
                textMeasurer = textMeasurer,
                density = density,
                modifier = bubbleModifier
            )
```

**REPLACE WITH:**
```kotlin
            // PREMIUM MEDIA BUBBLE: WhatsApp-style edge-to-edge media
            MediaMessageBubble(
                message = message,
                shape = shape,
                maxWidth = maxWidth,
                gradient = gradient,
                solidColor = solidColor,
                formattedTime = message.formattedTime,
                isSelf = isSelf,
                timestampColor = timestampColor,
                borderStroke = borderStroke,
                context = context,
                onMediaClick = { onMediaClick(message, 0) },
                onDownloadClick = { onDownloadMedia(message) },
                mediaProgress = progress,
                isWarmingUp = isWarmingUp,
                imagesEnabled = imagesEnabled,
                textMeasurer = textMeasurer,
                density = density,
                currentUserPhone = currentUserPhone,  // ← ADD THIS LINE
                modifier = bubbleModifier
            )
```

---

### 5. Update TextMessageBubble Function

**Location**: ChatScreen.kt, line ~1649

**STEP A: Update function signature**

**FIND:**
```kotlin
@Composable
fun TextMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    gradient: Brush?,
    solidColor: Color,
    textColor: Color,
    timestampColor: Color,
    formattedTime: String,
    isSelf: Boolean,
    borderStroke: BorderStroke,
    textMeasurer: TextMeasurer,
    density: Density,
    modifier: Modifier = Modifier
) {
```

**REPLACE WITH:**
```kotlin
@Composable
fun TextMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    gradient: Brush?,
    solidColor: Color,
    textColor: Color,
    timestampColor: Color,
    formattedTime: String,
    isSelf: Boolean,
    borderStroke: BorderStroke,
    textMeasurer: TextMeasurer,
    density: Density,
    currentUserPhone: String = "",  // ← ADD THIS LINE
    modifier: Modifier = Modifier
) {
```

**STEP B: Wrap content with Column for quoted preview**

Find the return statement (around the Spacer with drawWithCache). It should look like:

```kotlin
    // ULTRA-FLATTENED: Single Spacer with drawWithCache
    Spacer(
        modifier = modifier
            .size(
                width = with(density) { bubbleWidth.toDp() },
                height = with(density) { bubbleHeight.toDp() }
            )
            .drawWithCache {
                // ... drawing code ...
            }
    )
}
```

**REPLACE WITH:**
```kotlin
    // ULTRA-FLATTENED: Single Spacer with drawWithCache
    // Wrap in Column if message has reply metadata
    if (message.hasReplyMetadata()) {
        Column(modifier = modifier) {
            QuotedReplyPreview(
                replyToText = message.replyToText,
                replyToType = message.replyToType,
                replyToSenderId = message.replyToSenderId,
                isSelf = isSelf,
                currentUserPhone = currentUserPhone,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            
            Spacer(
                modifier = Modifier
                    .size(
                        width = with(density) { bubbleWidth.toDp() },
                        height = with(density) { bubbleHeight.toDp() }
                    )
                    .drawWithCache {
                        // ... existing drawing code (unchanged) ...
                        onDrawBehind {
                            // 1. Draw Background
                            if (gradient != null) {
                                drawPath(path, brush = gradient)
                            } else {
                                drawPath(path, color = solidColor)
                            }
                            
                            // 2. Draw Border
                            val strokeWidthPx = borderStroke.width.toPx()
                            if (strokeWidthPx > 0.5f) {
                                drawPath(
                                    path = path,
                                    brush = borderStroke.brush,
                                    style = Stroke(width = strokeWidthPx)
                                )
                            }
                            
                            // 3. Draw Message Text
                            drawText(
                                textLayoutResult = textLayoutResult,
                                topLeft = Offset(paddingH, paddingV)
                            )
                            
                            // 4. Draw Status Area (Time + Icon)
                            val statusX = size.width - paddingH - statusAreaWidth
                            val statusY = size.height - paddingV - timeLayoutResult.size.height.toFloat() + 4.dp.toPx()
                            
                            drawText(
                                textLayoutResult = timeLayoutResult,
                                topLeft = Offset(statusX, statusY)
                            )
                            
                            if (statusPainter != null) {
                                val iconX = size.width - paddingH - iconSize
                                val iconY = size.height - paddingV - iconSize + 1.dp.toPx() + 4.dp.toPx()
                                
                                translate(iconX, iconY) {
                                    with(statusPainter) {
                                        draw(
                                            size = Size(iconSize, iconSize),
                                            alpha = 1f,
                                            colorFilter = ColorFilter.tint(statusTint)
                                        )
                                    }
                                }
                            }
                        }
                    }
            )
        }
    } else {
        Spacer(
            modifier = modifier
                .size(
                    width = with(density) { bubbleWidth.toDp() },
                    height = with(density) { bubbleHeight.toDp() }
                )
                .drawWithCache {
                    // ... existing drawing code (same as above, unchanged) ...
                }
        )
    }
}
```

---

### 6. Update MediaMessageBubble Function

**Location**: ChatScreen.kt, line ~1396

**STEP A: Update function signature**

**FIND:**
```kotlin
@Composable
fun MediaMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    gradient: Brush?,
    solidColor: Color,
    formattedTime: String,
    isSelf: Boolean,
    timestampColor: Color,
    borderStroke: BorderStroke,
    context: Context,
    onMediaClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
    mediaProgress: MediaProgressManager.MediaProgress? = null,
    isWarmingUp: Boolean = false,
    imagesEnabled: Boolean = true,
    textMeasurer: TextMeasurer,
    density: Density,
    modifier: Modifier = Modifier
) {
```

**REPLACE WITH:**
```kotlin
@Composable
fun MediaMessageBubble(
    message: Message,
    shape: RoundedCornerShape,
    maxWidth: Dp,
    gradient: Brush?,
    solidColor: Color,
    formattedTime: String,
    isSelf: Boolean,
    timestampColor: Color,
    borderStroke: BorderStroke,
    context: Context,
    onMediaClick: () -> Unit,
    onDownloadClick: () -> Unit = {},
    mediaProgress: MediaProgressManager.MediaProgress? = null,
    isWarmingUp: Boolean = false,
    imagesEnabled: Boolean = true,
    textMeasurer: TextMeasurer,
    density: Density,
    currentUserPhone: String = "",  // ← ADD THIS LINE
    modifier: Modifier = Modifier
) {
```

**STEP B: Wrap content with Column for quoted preview**

Find the main Box that contains the media. It starts with:

```kotlin
    Box(
        modifier = modifier
            .width(maxWidth)
            .aspectRatio(aspectRatio)
            .drawWithCache {
                // ... drawing code ...
            }
    ) {
        // Edge-to-edge media
        Box(...) { ... }
    }
}
```

**REPLACE WITH:**
```kotlin
    // Wrap in Column if message has reply metadata
    if (message.hasReplyMetadata()) {
        Column(modifier = modifier) {
            QuotedReplyPreview(
                replyToText = message.replyToText,
                replyToType = message.replyToType,
                replyToSenderId = message.replyToSenderId,
                isSelf = isSelf,
                currentUserPhone = currentUserPhone,
                modifier = Modifier.padding(8.dp).padding(bottom = 4.dp)
            )
            
            Box(
                modifier = Modifier
                    .width(maxWidth)
                    .aspectRatio(aspectRatio)
                    .drawWithCache {
                        // ... existing drawing code (unchanged) ...
                    }
            ) {
                // Edge-to-edge media
                Box(...) { ... }  // existing code unchanged
            }
        }
    } else {
        Box(
            modifier = modifier
                .width(maxWidth)
                .aspectRatio(aspectRatio)
                .drawWithCache {
                    // ... existing drawing code (unchanged) ...
                }
        ) {
            // Edge-to-edge media
            Box(...) { ... }  // existing code unchanged
        }
    }
}
```

---

### 7. Update ChatViewModel sendMessage Function

**Location**: ChatViewModel.kt

**FIND (approximately):**
```kotlin
fun sendMessage(text: String) {
    if (text.isBlank()) return
    
    val message = Message(
        id = UUID.randomUUID().toString(),
        chatId = chatId,
        text = text.trim(),
        senderId = firebaseRepository.currentUserId ?: "",
        timestamp = System.currentTimeMillis(),
        status = MessageStatus.SENDING,
        isIncoming = false,
        type = MessageType.TEXT
    )
    
    _uiState.update { it.copy(inputText = "") }
    
    viewModelScope.launch {
        repository.sendMessage(message)
    }
}
```

**REPLACE WITH:**
```kotlin
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
        // ↓ ADD REPLY METADATA
        replyToMessageId = replyTo?.id,
        replyToText = when(replyTo?.type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.VIDEO -> "🎥 Video"
            MessageType.AUDIO -> "🎤 Audio"
            MessageType.MEDIA_GROUP -> "📷 Album"
            MessageType.CONTACT -> "👤 Contact"
            else -> replyTo?.text?.take(100)
        },
        replyToSenderId = replyTo?.senderId,
        replyToType = replyTo?.type
    )
    
    // Clear both input and reply state
    _uiState.update { 
        it.copy(
            inputText = "",
            replyToMessage = null  // ← CLEAR REPLY STATE
        ) 
    }
    
    viewModelScope.launch {
        repository.sendMessage(message)
    }
}
```

---

## 🎉 That's It!

After applying these 7 changes:

1. ✅ Message data model has reply fields
2. ✅ Swipe gesture triggers reply
3. ✅ Reply preview appears in input area
4. ✅ Quoted preview shows in message bubbles
5. ✅ ViewModel captures and clears reply state
6. ✅ Messages are sent with reply metadata

**Test it**: Swipe on a message → reply preview appears → type and send → quoted preview shows in sent message!

For Firebase persistence, see `IntegrationExamples.kt` - it's optional but recommended for production.
