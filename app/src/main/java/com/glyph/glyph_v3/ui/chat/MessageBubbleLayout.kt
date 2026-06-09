package com.glyph.glyph_v3.ui.chat

import android.content.Context
import android.text.StaticLayout
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import kotlin.math.max
import kotlin.math.ceil
import kotlin.math.min
import com.glyph.glyph_v3.R

/**
 * Custom layout that positions message content and metadata (timestamp/status) like WhatsApp:
 * - For short single-line messages: timestamp appears inline after the text
 * - For multi-line messages where last line has space: timestamp appears inline at bottom-right
 * - For messages where last line is too long: timestamp appears below the text
 * 
 * Key insight: To prevent overlap, we must ensure the bubble is wide enough that when
 * metadata is positioned at bottom-right, it doesn't overlap with the text's last line.
 */
class MessageBubbleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val maxBubbleWidthFraction = 0.85f

    private var fitsInline = false
    private var lastLineWidth = 0
    private var metadataReservedWidth = 0
    private var bubblePaddingLeft = 0
    private var bubblePaddingRight = 0
    private var bubblePaddingTop = 0
    private var bubblePaddingBottom = 0

    private val gap = (6 * resources.displayMetrics.density).toInt() // Gap between text and metadata
    private val verticalOffset = (2 * resources.displayMetrics.density).toInt() // How much to lower timestamp
    private val compactPreviewBottomPadding = (6 * resources.displayMetrics.density).toInt()
    private val compactPreviewHorizontalInset = 0
    private val compactPreviewTopInset = 0
    private val previewMetadataEndInset = (10 * resources.displayMetrics.density).toInt()
    private val inlineSafetyInset = (4 * resources.displayMetrics.density).toInt()

    private var cachedTextView: TextView? = null

    init {
        // Default padding — incoming-style. Overridden per-direction by ChatAdapter
        // via setPaddingRelative() on every bind, so these are just the pre-bind defaults.
        bubblePaddingLeft = (9 * resources.displayMetrics.density).toInt()
        bubblePaddingRight = (8 * resources.displayMetrics.density).toInt()
        bubblePaddingTop = (3 * resources.displayMetrics.density).toInt()
        bubblePaddingBottom = (2 * resources.displayMetrics.density).toInt()

        clipToPadding = false
        clipChildren = false

        setPaddingRelative(bubblePaddingLeft, bubblePaddingTop, bubblePaddingRight, bubblePaddingBottom)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // If a child is an AUDIO player (VoiceMessageView), let it handle its own touches
        // without the bubble layout or parent card/recyclerview intercepting it for clicks.
        if (childCount > 0) {
            val content = getChildAt(0)
            if (content is ViewGroup) {
                for (i in 0 until content.childCount) {
                    val child = content.getChildAt(i)
                    if (child is VoiceMessageView) {
                        val rect = android.graphics.Rect()
                        child.getHitRect(rect)
                        if (rect.contains(ev.getX().toInt(), ev.getY().toInt())) {
                            return false // Do not intercept, let VoiceMessageView (waveform) handle it
                        }
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val specSize = MeasureSpec.getSize(widthMeasureSpec)
        val maxAvailableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else specSize
        val maxBubbleWidth = if (maxAvailableWidth == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            max(1, (maxAvailableWidth * maxBubbleWidthFraction).toInt())
        }

        if (childCount < 2) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val content = getChildAt(0)
        val metadata = getChildAt(1)

        // Measure metadata first (timestamp + status icon) using wrap_content width so it doesn't stretch
        val metadataWidthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val metadataHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        measureChild(metadata, metadataWidthSpec, metadataHeightSpec)
        
        val paddingLeft = paddingLeft
        val paddingRight = paddingRight
        val paddingTop = paddingTop
        val paddingBottom = paddingBottom
        
        val availableContentWidth = maxBubbleWidth - paddingLeft - paddingRight
        val metadataWidth = metadata.measuredWidth
        val metadataHeight = metadata.measuredHeight
        metadataReservedWidth = metadataWidth + gap + inlineSafetyInset
        val hasVisibleLinkPreview = content.findViewById<View>(R.id.linkPreviewCard)?.visibility == View.VISIBLE
        val activePaddingLeft = if (hasVisibleLinkPreview) compactPreviewHorizontalInset else paddingLeft
        val activePaddingRight = if (hasVisibleLinkPreview) compactPreviewHorizontalInset else paddingRight
        val activePaddingTop = if (hasVisibleLinkPreview) compactPreviewTopInset else paddingTop
        val activePaddingBottom = if (hasVisibleLinkPreview) compactPreviewBottomPadding else paddingBottom

        // If parent specifies EXACTLY, measure content to fill that width (for images)
        // Otherwise use AT_MOST for text to wrap content
        val contentWidthSpec = if (widthMode == MeasureSpec.EXACTLY || hasVisibleLinkPreview) {
            MeasureSpec.makeMeasureSpec(maxBubbleWidth - activePaddingLeft - activePaddingRight, MeasureSpec.EXACTLY)
        } else {
            MeasureSpec.makeMeasureSpec(availableContentWidth, MeasureSpec.AT_MOST)
        }

        // Measure content (text/image/audio) with proper width spec
        content.measure(contentWidthSpec, heightMeasureSpec)
        var contentWidth = content.measuredWidth
        var contentHeight = content.measuredHeight

        // FORCE minimum width if we have a reply preview visible
        // Calculate width based on BOTH main text and preview text, using the larger
        val replyPreview = content.findViewById<View>(R.id.includeReplyPreview)
        val hasReply = replyPreview != null && replyPreview.visibility == View.VISIBLE
        
        var minContentWidth = 0
        
        if (hasReply) {
            // Find preview text views
            val tvReplyContact = replyPreview.findViewById<TextView>(R.id.tvReplyContact)
            val tvReplyContent = replyPreview.findViewById<TextView>(R.id.tvReplyContent)
            
            // Measure preview text widths
            var previewTextWidth = 0f
            if (tvReplyContact != null && tvReplyContact.visibility == View.VISIBLE) {
                val contactText = tvReplyContact.text?.toString() ?: ""
                previewTextWidth = maxOf(previewTextWidth, tvReplyContact.paint.measureText(contactText))
            }
            if (tvReplyContent != null && tvReplyContent.visibility == View.VISIBLE) {
                val contentText = tvReplyContent.text?.toString() ?: ""
                previewTextWidth = maxOf(previewTextWidth, tvReplyContent.paint.measureText(contentText))
            }
            
            // Add preview padding: accent bar (4dp) + margins (4dp + 8dp) + text padding (~16dp) + container padding (8dp)
            val previewExtraWidth = (40 * resources.displayMetrics.density).toInt()
            
            // Account for visible media thumbnail / collage width inside the preview
            val ivReplyImage = replyPreview.findViewById<View>(R.id.ivReplyImage)
            val collageContainer = replyPreview.findViewById<View>(R.id.replyCollageContainer)
            val mediaWidth = if (collageContainer != null && collageContainer.visibility == View.VISIBLE) {
                // Stacked collage: count visible children * offset + base size + end margin
                var visibleCount = 0
                if (collageContainer is ViewGroup) {
                    for (i in 0 until collageContainer.childCount) {
                        if (collageContainer.getChildAt(i).visibility == View.VISIBLE) visibleCount++
                    }
                }
                val offsetPx = (8 * resources.displayMetrics.density).toInt()
                val basePx = (36 * resources.displayMetrics.density).toInt()
                val marginPx = (8 * resources.displayMetrics.density).toInt()
                basePx + offsetPx * (visibleCount - 1).coerceAtLeast(0) + marginPx
            } else if (ivReplyImage != null && ivReplyImage.visibility == View.VISIBLE) {
                // Single thumbnail: 36dp + 8dp margin
                (44 * resources.displayMetrics.density).toInt()
            } else 0
            
            val previewRequiredWidth = previewTextWidth.toInt() + previewExtraWidth + mediaWidth
            
            // Find main message TextView
            if (cachedTextView == null) {
                cachedTextView = findMessageTextView(content)
            }
            val messageTextView = cachedTextView
            
            // Measure main text width including timestamp/status space
            var mainTextWidth = 0
            if (messageTextView != null) {
                val messageText = messageTextView.text?.toString() ?: ""
                val textOnlyWidth = ceil(messageTextView.paint.measureText(messageText)).toInt() + 
                               messageTextView.paddingLeft + messageTextView.paddingRight
                // Add space for timestamp + status icon (~70dp)
                val timestampSpace = (70 * resources.displayMetrics.density).toInt()
                mainTextWidth = textOnlyWidth + timestampSpace
            }
            
            // Use the LARGER of preview or main text+timestamp, with a minimum of 140dp
            val minWidthDp = (140 * resources.displayMetrics.density).toInt()
            minContentWidth = maxOf(previewRequiredWidth, mainTextWidth, minWidthDp)
        }
        
        if (contentWidth < minContentWidth) {
            val forceWidthSpec = MeasureSpec.makeMeasureSpec(minOf(minContentWidth, availableContentWidth), MeasureSpec.EXACTLY)
            content.measure(forceWidthSpec, heightMeasureSpec)
            contentWidth = content.measuredWidth
            contentHeight = content.measuredHeight
        }
        
        val effectiveContentWidth = contentWidth

        // Find the message TextView to analyze its line structure
        if (cachedTextView == null) {
            cachedTextView = findMessageTextView(content)
        }
        val textView = cachedTextView
        val isEmojiOnly = textView?.getTag(R.id.tag_emoji_only) == true
        
        fitsInline = false
        lastLineWidth = 0
        
        var finalWidth: Int
        var finalHeight: Int

        val availableInnerWidth = maxBubbleWidth - activePaddingLeft - activePaddingRight

        if (hasVisibleLinkPreview) {
            fitsInline = false
            finalWidth = maxBubbleWidth
            finalHeight = contentHeight + metadataHeight + activePaddingTop + activePaddingBottom
        } else if (isEmojiOnly) {
            fitsInline = false
            finalWidth = max(effectiveContentWidth, metadataWidth) + activePaddingLeft + activePaddingRight
            finalHeight = contentHeight + metadataHeight + activePaddingTop + activePaddingBottom
        } else if (textView != null && textView.layout != null && textView.lineCount > 0) {
            val layout = textView.layout
            val lineCount = layout.lineCount
            lastLineWidth = ceil(layout.getLineWidth(lineCount - 1)).toInt()
            
            // Calculate the actual text container width
            val textContainerWidth = effectiveContentWidth
            val lastLineEnd = lastLineWidth + textView.paddingLeft + textView.paddingRight
            
            if (lineCount == 1) {
                // Single line: neededForInline = text + gap + timestamp
                val neededForInline = lastLineEnd + metadataReservedWidth
                
                if (neededForInline <= availableInnerWidth) {
                    fitsInline = true
                    finalWidth = max(effectiveContentWidth, neededForInline) + activePaddingLeft + activePaddingRight
                    finalHeight = max(contentHeight, metadataHeight) + activePaddingTop + activePaddingBottom + verticalOffset
                } else {
                    fitsInline = false
                    finalWidth = max(effectiveContentWidth, metadataWidth) + activePaddingLeft + activePaddingRight
                    finalHeight = contentHeight + metadataHeight + activePaddingTop + activePaddingBottom
                }
            } else {
                // Multi-line list or wrapped text
                val bubbleInnerWidth = min(effectiveContentWidth, availableInnerWidth)
                val spaceOnLastLine = bubbleInnerWidth - lastLineEnd
                
                // IMPORTANT: For list items or manual breaks, check if there's enough total space
                // even if spaceOnLastLine is tight, we might be able to expand the bubble slightly
                // if it's within maxAvailableWidth.
                if (spaceOnLastLine >= metadataReservedWidth) {
                    fitsInline = true
                    finalWidth = effectiveContentWidth + activePaddingLeft + activePaddingRight
                    finalHeight = contentHeight + activePaddingTop + activePaddingBottom + verticalOffset
                } else if (lastLineEnd + metadataReservedWidth <= availableInnerWidth) {
                    // We can expand bubble width to fit timestamp on same line
                    fitsInline = true
                    finalWidth = max(effectiveContentWidth, lastLineEnd + metadataReservedWidth) + activePaddingLeft + activePaddingRight
                    finalHeight = contentHeight + activePaddingTop + activePaddingBottom + verticalOffset
                } else {
                    fitsInline = false
                    finalWidth = max(effectiveContentWidth, metadataWidth) + activePaddingLeft + activePaddingRight
                    finalHeight = contentHeight + metadataHeight + activePaddingTop + activePaddingBottom
                }
            }
        } else if (textView != null) {
            // ...fallback...
            val textPaint = textView.paint
            val text = textView.text?.toString() ?: ""
            val textWidth = textPaint.measureText(text)
            lastLineWidth = ceil(textWidth).toInt()
            val lastLineEnd = lastLineWidth + textView.paddingLeft + textView.paddingRight
            
            val neededForInline = lastLineEnd + metadataReservedWidth
            
            if (neededForInline <= availableInnerWidth) {
                fitsInline = true
                finalWidth = max(effectiveContentWidth, neededForInline) + activePaddingLeft + activePaddingRight
                finalHeight = max(contentHeight, metadataHeight) + activePaddingTop + activePaddingBottom + verticalOffset
            } else {
                fitsInline = false
                finalWidth = max(effectiveContentWidth, metadataWidth) + activePaddingLeft + activePaddingRight
                finalHeight = contentHeight + metadataHeight + activePaddingTop + activePaddingBottom
            }
        } else {
            // No text (image/audio only) - metadata goes below
            fitsInline = false

            // For image/audio, use the full available width if parent specified EXACTLY
            // This ensures images fill the bubble properly
            if (widthMode == MeasureSpec.EXACTLY) {
                finalWidth = maxAvailableWidth
            } else {
                finalWidth = max(contentWidth, metadataWidth) + activePaddingLeft + activePaddingRight
            }
            finalHeight = contentHeight + metadataHeight + activePaddingTop + activePaddingBottom
        }

        // Clamp to our max bubble width to keep text bubbles consistent
        finalWidth = min(finalWidth, maxBubbleWidth)
        
        // Ensure minimum dimensions
        finalWidth = max(finalWidth, suggestedMinimumWidth)
        finalHeight = max(finalHeight, suggestedMinimumHeight)

        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount < 2) return

        val content = getChildAt(0)
        val metadata = getChildAt(1)

        val metadataWidth = metadata.measuredWidth
        val metadataHeight = metadata.measuredHeight
        val hasVisibleLinkPreview = content.findViewById<View>(R.id.linkPreviewCard)?.visibility == View.VISIBLE
        val activePaddingLeft = if (hasVisibleLinkPreview) compactPreviewHorizontalInset else paddingLeft
        val activePaddingRight = if (hasVisibleLinkPreview) compactPreviewHorizontalInset else paddingRight
        val activePaddingTop = if (hasVisibleLinkPreview) compactPreviewTopInset else paddingTop

        val bubbleWidth = measuredWidth
        val bubbleHeight = measuredHeight

        // Layout content to fill the bubble width (minus padding)
        // This ensures that if the bubble was expanded due to minWidth constraints,
        // the content (like reply previews) stretches to fill that new space.
        val contentLayoutWidth = bubbleWidth - activePaddingLeft - activePaddingRight
        
        content.layout(
            activePaddingLeft,
            activePaddingTop,
            activePaddingLeft + contentLayoutWidth,
            activePaddingTop + content.measuredHeight
        )

        // Keep metadata pinned to the bubble bottom-right and avoid overlap with content
        // Note: We use the *measured* height of content for placement calculations to avoid jumping
        // but visually the content view is stretched.
        val contentHeight = content.measuredHeight
        val metadataEndInset = if (hasVisibleLinkPreview) previewMetadataEndInset else activePaddingRight
        val metaLeft = bubbleWidth - metadataEndInset - metadataWidth
        val metaTop = if (fitsInline) {
            val offsetTop = activePaddingTop + contentHeight - metadataHeight + verticalOffset
            offsetTop.coerceAtLeast(activePaddingTop)
        } else {
            activePaddingTop + contentHeight
        }
        metadata.layout(metaLeft, metaTop, metaLeft + metadataWidth, metaTop + metadataHeight)
    }

    /**
     * Find the main message TextView by looking for tvMessage ID first, then fallback to any visible TextView
     */
    override fun onViewRemoved(child: View?) {
        super.onViewRemoved(child)
        cachedTextView = null
    }

    private fun findMessageTextView(view: View): TextView? {
        // First try to find by ID (most reliable)
        val messageTextView = view.findViewById<TextView>(R.id.tvMessage)
        if (messageTextView != null && messageTextView.visibility == View.VISIBLE) {
            return messageTextView
        }
        
        // Fallback: find any visible TextView with content
        return findAnyTextView(view)
    }
    
    private fun findAnyTextView(view: View): TextView? {
        if (view is TextView && view.visibility == View.VISIBLE && !view.text.isNullOrEmpty()) {
            return view
        }
        if (view is ViewGroup) {
            for (child in view.children) {
                val found = findAnyTextView(child)
                if (found != null) return found
            }
        }
        return null
    }
}
