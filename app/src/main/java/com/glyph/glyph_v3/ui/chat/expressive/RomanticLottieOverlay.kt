package com.glyph.glyph_v3.ui.chat.expressive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition

@Composable
fun RomanticLottieOverlay(
    animationMode: RomanticAnimationMode,
    isActive: Boolean,
    composerTopPx: Int,
    modifier: Modifier = Modifier
) {
    val assetName = animationMode.assetName ?: return
    val composition by rememberLottieComposition(LottieCompositionSpec.Asset(assetName))
    val progress by animateLottieCompositionAsState(
        composition = composition,
        isPlaying = isActive && composition != null,
        iterations = LottieConstants.IterateForever,
        restartOnPlay = false,
        speed = if (animationMode == RomanticAnimationMode.LOVE_DANCING) 1.0f else 1.05f
    )
    val overlayAlpha by animateFloatAsState(
        targetValue = if (isActive && composition != null) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "romanticOverlayAlpha"
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerHeightPx = with(density) { maxHeight.roundToPx() }
        val composerBottomInset = with(density) {
            (containerHeightPx - composerTopPx.coerceIn(0, containerHeightPx)).toDp()
        }

        val animationModifier = if (animationMode.anchoredToComposer) {
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.96f)
                .padding(start = 24.dp, end = 24.dp, bottom = composerBottomInset)
                .heightIn(min = 220.dp, max = maxHeight * 0.58f)
                .alpha(overlayAlpha)
        } else {
            Modifier
                .fillMaxSize()
                .alpha(overlayAlpha)
        }

        LottieAnimation(
            composition = composition,
            progress = { progress },
            modifier = animationModifier,
            alignment = if (animationMode.anchoredToComposer) Alignment.BottomCenter else Alignment.Center,
            contentScale = if (animationMode.anchoredToComposer) ContentScale.Fit else ContentScale.Crop,
            clipToCompositionBounds = false
        )
    }
}