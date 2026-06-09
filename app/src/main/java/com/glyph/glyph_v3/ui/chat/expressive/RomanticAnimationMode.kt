package com.glyph.glyph_v3.ui.chat.expressive

enum class RomanticAnimationMode(
    val assetName: String? = null,
    val anchoredToComposer: Boolean = false
) {
    EMOJI_PARTICLES,
    HEART_PARTICLES(assetName = "heart_particles.lottie"),
    BUTTERFLY_HEARTS(assetName = "butterfly_hearts.lottie"),
    LOVE_DANCING(assetName = "love_dancing.lottie", anchoredToComposer = true);

    companion object {
        fun random(): RomanticAnimationMode = values().random()
    }
}