package com.glyph.glyph_v3.ui.chat.expressive

import android.graphics.Color

/**
 * Sentiment categories detected by AI sentiment analysis.
 * Each maps to a gradient palette, emoji set, and display characteristics.
 */
enum class SentimentType(
    val gradientColors: IntArray,
    val emojis: List<String>,
    val label: String
) {
    POSITIVE(
        gradientColors = intArrayOf(
            Color.parseColor("#FFD54F"), // Warm yellow
            Color.parseColor("#FFB74D"), // Orange
            Color.parseColor("#FFF176")  // Light yellow
        ),
        emojis = listOf("😊", "👍", "✨", "🌟", "😄", "🎊", "💛", "🌻", "☀️", "⭐"),
        label = "Positive"
    ),
    NEGATIVE(
        gradientColors = intArrayOf(
            Color.parseColor("#90A4AE"), // Blue-gray
            Color.parseColor("#78909C"),
            Color.parseColor("#B0BEC5")
        ),
        emojis = listOf("😔", "💭", "😕", "😞", "💙", "🌧️"),
        label = "Negative"
    ),
    NEUTRAL(
        gradientColors = intArrayOf(
            Color.parseColor("#E0E0E0"), // Subtle gray
            Color.parseColor("#EEEEEE"),
            Color.parseColor("#F5F5F5")
        ),
        emojis = emptyList(),
        label = "Neutral"
    ),
    EXCITED(
        gradientColors = intArrayOf(
            Color.parseColor("#FF7043"), // Deep orange
            Color.parseColor("#FFAB40"), // Amber
            Color.parseColor("#FFD740")  // Golden
        ),
        emojis = listOf("🎉", "🔥", "⚡", "🚀", "💥", "🎆", "✨", "🌟", "🎊", "🙌"),
        label = "Excited"
    ),
    ANGRY(
        gradientColors = intArrayOf(
            Color.parseColor("#EF5350"), // Red
            Color.parseColor("#E53935"),
            Color.parseColor("#FF8A80")
        ),
        emojis = listOf("😤", "💢", "😡", "🔥", "💥", "👿"),
        label = "Angry"
    ),
    ROMANTIC(
        gradientColors = intArrayOf(
            Color.parseColor("#F48FB1"), // Pink
            Color.parseColor("#CE93D8"), // Purple
            Color.parseColor("#F8BBD0")  // Light pink
        ),
        emojis = listOf("💕", "🥰", "💗", "❤️", "💖", "😍", "💘", "💝", "💞", "🌹"),
        label = "Romantic"
    ),
    SAD(
        gradientColors = intArrayOf(
            Color.parseColor("#64B5F6"), // Blue
            Color.parseColor("#42A5F5"),
            Color.parseColor("#90CAF9")
        ),
        emojis = listOf("😢", "🥺", "💧", "😭", "💔", "😞", "🌧️"),
        label = "Sad"
    ),
    FUNNY(
        gradientColors = intArrayOf(
            Color.parseColor("#AED581"), // Light green
            Color.parseColor("#FFD54F"), // Yellow
            Color.parseColor("#4FC3F7")  // Light blue
        ),
        emojis = listOf("😂", "🤣", "😄", "😆", "😁", "😹", "💀", "🙈"),
        label = "Funny"
    );

    companion object {
        val DEFAULT = NEUTRAL
    }
}
