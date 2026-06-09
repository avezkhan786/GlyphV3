package com.glyph.glyph_v3.ui.chatlist

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glyph.glyph_v3.R
import com.glyph.glyph_v3.ui.theme.GlyphThemeProvider
import com.glyph.glyph_v3.ui.theme.glyphTheme
import com.glyph.glyph_v3.utils.ThemeManager

class HideLockedChatsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this, deepDark = true)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            GlyphThemeProvider(isDeepDark = true) {
                HideLockedChatsScreen(
                    onBackClick = { finish() },
                    onCreateSecretCode = {
                        startActivity(Intent(this, CreateSecretCodeActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HideLockedChatsScreen(
    onBackClick: () -> Unit,
    onCreateSecretCode: () -> Unit
) {
    val surfaceBg = glyphTheme.backgroundPrimary
    val textPrimary = glyphTheme.textPrimary
    val textSecondary = glyphTheme.textSecondary
    val accentGreen = Color(0xFF00A884)
    val dividerColor = glyphTheme.bubbleBorder

    Scaffold(
        containerColor = surfaceBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Hide locked chats",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_back),
                            contentDescription = "Back",
                            tint = glyphTheme.iconPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = surfaceBg,
                    titleContentColor = textPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalDivider(color = dividerColor, thickness = 0.5.dp)

            Spacer(modifier = Modifier.weight(0.2f))

            // Illustration — phone with lock
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1F2C34)),
                contentAlignment = Alignment.Center
            ) {
                // Phone outline
                Box(
                    modifier = Modifier
                        .size(80.dp, 110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2A3942)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // Search bar mockup
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFF3B4D56))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // Lock icon mockup
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chat_lock),
                            contentDescription = null,
                            tint = accentGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // Lines mockup
                        repeat(2) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.7f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF3B4D56))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Heading
            Text(
                text = "Hide your chats with a secret\ncode for more privacy",
                color = textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                lineHeight = 28.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Description
            Text(
                text = "Find locked chats by entering your code into the search bar in the Chats tab. Chats will be unlocked and cleared if you forget your code and create a new one.",
                color = textSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // CTA button
            Button(
                onClick = onCreateSecretCode,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentGreen)
            ) {
                Text(
                    text = "Create secret code",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
