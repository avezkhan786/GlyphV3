package com.glyph.glyph_v3.ui.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.auth.components.AuthScaffold
import com.glyph.glyph_v3.ui.auth.components.GlyphButton
import com.glyph.glyph_v3.ui.base.ThemedActivity
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import kotlinx.coroutines.delay

/**
 * Screen 1: Get Started — Welcome/Onboarding.
 *
 * Introduces the app with a logo, name, tagline, and terms/privacy links.
 * The "Get Started" button at the bottom transitions to [PhoneNumberActivity].
 */
class WelcomeActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GlyphThemeProvider {
                WelcomeScreen(
                    onGetStarted = {
                        startActivity(Intent(this, PhoneNumberActivity::class.java))
                        AuthAnimationUtils.forward(this)
                    },
                    onOpenUrl = { url ->
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val theme = glyphTheme
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AuthScaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top breathing space
            Spacer(modifier = Modifier.weight(1f))

            // Logo with staggered fade-in
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(AuthAnimationUtils.FADE_IN_MS))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "Glyph logo",
                    modifier = Modifier.size(140.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // App name
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(AuthAnimationUtils.FADE_IN_MS + AuthAnimationUtils.STAGGER_DELAY_MS))
            ) {
                Text(
                    text = "Glyph",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tagline
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(AuthAnimationUtils.FADE_IN_MS + AuthAnimationUtils.STAGGER_DELAY_MS * 2))
            ) {
                Text(
                    text = "Private, fast and secure conversations.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = theme.textSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Terms & Privacy
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(AuthAnimationUtils.FADE_IN_MS + AuthAnimationUtils.STAGGER_DELAY_MS * 3))
            ) {
                TermsAndPrivacyText(onOpenUrl = onOpenUrl)
            }

            // Bottom section with button
            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(AuthAnimationUtils.FADE_IN_MS + AuthAnimationUtils.STAGGER_DELAY_MS * 4))
            ) {
                GlyphButton(
                    text = "Get Started",
                    onClick = onGetStarted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun TermsAndPrivacyText(onOpenUrl: (String) -> Unit) {
    val theme = glyphTheme
    val annotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = theme.textTertiary, fontSize = 13.sp)) {
            append("By tapping Get Started, you agree to our ")
        }
        pushStringAnnotation("terms", "https://glyph.app/terms")
        withStyle(SpanStyle(color = theme.textLink, fontSize = 13.sp, fontWeight = FontWeight.Medium)) {
            append("Terms of Service")
        }
        pop()
        withStyle(SpanStyle(color = theme.textTertiary, fontSize = 13.sp)) {
            append(" and ")
        }
        pushStringAnnotation("privacy", "https://glyph.app/privacy")
        withStyle(SpanStyle(color = theme.textLink, fontSize = 13.sp, fontWeight = FontWeight.Medium)) {
            append("Privacy Policy")
        }
        pop()
    }

    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
        onClick = { offset ->
            annotatedString.getStringAnnotations("terms", offset, offset).firstOrNull()?.let {
                onOpenUrl("https://glyph.app/terms")
            }
            annotatedString.getStringAnnotations("privacy", offset, offset).firstOrNull()?.let {
                onOpenUrl("https://glyph.app/privacy")
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    )
}
