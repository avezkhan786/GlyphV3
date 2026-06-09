# 🎨 Pastel-Sky Theme - Quick Reference

## 📦 Quick Access

```kotlin
// Theme constant
ThemeManager.THEME_PASTEL_SKY  // "pastel_sky"

// Display name
"Pastel-Sky ✨"

// Switch theme
ThemeManager.setTheme(activity, ThemeManager.THEME_PASTEL_SKY)
```

---

## 🎨 Core Colors (Copy-Paste Ready)

```kotlin
// Import
import com.glyph.glyph_v3.ui.theme.PastelSkyTheme

// Primary Palette
PastelSkyTheme.SkyBlue          // #CFE9F3
PastelSkyTheme.LavenderMist     // #E6DDF2
PastelSkyTheme.MintCloud        // #DFF2EA
PastelSkyTheme.PeachHaze        // #F6E2D6
PastelSkyTheme.CreamWhite       // #FBFAF8 (use instead of white!)
PastelSkyTheme.CloudGray        // #E8EDF2

// Text
PastelSkyTheme.primaryText      // #2B2F36
PastelSkyTheme.secondaryText    // #6A7280
PastelSkyTheme.disabledText     // #9AA3AF
PastelSkyTheme.placeholderText  // #8A92A0
```

---

## 🌈 Gradient Functions (Compose)

```kotlin
// App background (Top → Bottom)
Modifier.background(PastelSkyTheme.primaryGradientBrush())

// Header (Top → Bottom)
Modifier.background(PastelSkyTheme.headerGradientBrush())

// Outgoing bubble (Left → Right)
Modifier.background(PastelSkyTheme.outgoingBubbleGradient())

// Send button (Left → Right)
Modifier.background(PastelSkyTheme.sendButtonGradient())
```

---

## 📐 XML Drawables

```xml
<!-- App background -->
android:background="@drawable/bg_pastel_primary_gradient"

<!-- Header -->
android:background="@drawable/bg_pastel_header_gradient"

<!-- Outgoing bubble -->
android:background="@drawable/bg_pastel_bubble_outgoing"

<!-- Incoming bubble -->
android:background="@drawable/bg_pastel_bubble_incoming"

<!-- Send button -->
android:background="@drawable/bg_pastel_send_button"

<!-- Date header -->
android:background="@drawable/bg_pastel_date_header"

<!-- Input field -->
android:background="@drawable/bg_pastel_input_field"

<!-- FAB -->
android:background="@drawable/bg_pastel_fab_gradient"
```

---

## 📱 XML Color References

```xml
<!-- Backgrounds -->
@color/pastel_background
@color/pastel_surface
@color/pastel_surface_elevated

<!-- Text -->
@color/pastel_text_primary
@color/pastel_text_secondary
@color/pastel_text_tertiary
@color/pastel_text_hint

<!-- Top Bar -->
@color/pastel_top_bar
@color/pastel_top_bar_title
@color/pastel_top_bar_icon

<!-- Chat -->
@color/pastel_bubble_incoming
@color/pastel_bubble_incoming_text
@color/pastel_bubble_outgoing_start
@color/pastel_bubble_outgoing_end
@color/pastel_bubble_outgoing_text

<!-- Input -->
@color/pastel_input_bg
@color/pastel_input_border
@color/pastel_cursor

<!-- Button -->
@color/pastel_send_button_start
@color/pastel_send_button_end
@color/pastel_send_button_icon
```

---

## ⚡ Common Patterns

### Compose Theme Wrapper
```kotlin
@Composable
fun MyScreen() {
    PastelSkyComposeTheme {
        // Your UI
    }
}
```

### Chat Bubble (Compose)
```kotlin
// Outgoing
Surface(
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.background(
        PastelSkyTheme.outgoingBubbleGradient()
    )
) {
    Text(text, color = PastelSkyTheme.outgoingBubbleText)
}

// Incoming
Surface(
    color = PastelSkyTheme.incomingBubbleBackground,
    border = BorderStroke(0.5.dp, PastelSkyTheme.incomingBubbleBorder),
    shape = RoundedCornerShape(16.dp)
) {
    Text(text, color = PastelSkyTheme.incomingBubbleText)
}
```

### Date Header (Compose)
```kotlin
Text(
    text = "Today",
    color = PastelSkyTheme.dateHeaderText,
    modifier = Modifier
        .background(
            PastelSkyTheme.dateHeaderBackground,
            RoundedCornerShape(12.dp)
        )
        .padding(horizontal = 16.dp, vertical = 6.dp)
)
```

### Top App Bar (Compose)
```kotlin
TopAppBar(
    title = { Text("Title", color = PastelSkyTheme.topAppBarTitleText) },
    modifier = Modifier.background(PastelSkyTheme.headerGradientBrush()),
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent
    )
)
```

---

## ⚙️ Animation Values

```kotlin
PastelSkyTheme.DURATION_SHORT     // 400ms
PastelSkyTheme.DURATION_MEDIUM    // 600ms
PastelSkyTheme.DURATION_LONG      // 800ms

PastelSkyTheme.SCALE_PRESS_START  // 0.96f
PastelSkyTheme.SCALE_PRESS_END    // 1.0f

PastelSkyTheme.ALPHA_DISABLED     // 0.38f
PastelSkyTheme.ALPHA_PRESSED      // 0.08f
PastelSkyTheme.ALPHA_HOVER        // 0.04f
```

---

## 🚫 Don't Use These!

```kotlin
❌ Color(0xFFFFFFFF)  // Pure white - use CreamWhite
❌ Color(0xFF000000)  // Pure black - use primaryText
❌ Color.White        // Use CreamWhite
❌ Color.Black        // Use primaryText

✅ PastelSkyTheme.CreamWhite      // #FBFAF8
✅ PastelSkyTheme.primaryText     // #2B2F36
```

---

## 🔧 Helper Functions

```kotlin
// Context extensions (in Activities/Fragments)
context.getThemeBackgroundColor()
context.getThemeSurfaceColor()
context.getThemePrimaryColor()
context.getThemeTextColor()
context.getThemeTextSecondaryColor()
context.getThemeBubbleOwnColor()
context.getThemeBubbleOtherColor()
context.getThemeSendButtonColor()

// Theme manager
ThemeManager.getCurrentTheme(context)
ThemeManager.setTheme(activity, theme)
ThemeManager.getThemeDisplayName(theme)
ThemeManager.isDarkMode(context)
```

---

## 📋 Theme Attributes (XML)

```xml
<!-- Use in XML layouts -->
?attr/glyphBackground
?attr/glyphTextPrimary
?attr/glyphTextSecondary
?attr/glyphPrimary
?attr/glyphBubbleOwn
?attr/glyphBubbleOther
?attr/glyphSendButton
?attr/glyphDivider
```

---

## ✅ Quick Checklist

When implementing Pastel-Sky in a new component:

- [ ] Use `CreamWhite` instead of pure white
- [ ] Use `primaryText` instead of pure black
- [ ] Apply gradients with proper brush functions
- [ ] Use theme colors, never hardcode
- [ ] Test on AMOLED display
- [ ] Verify gradients are subtle (barely noticeable)
- [ ] Check contrast for accessibility
- [ ] Ensure theme switching works

---

## 🎯 Key Principles

1. **Subtle Over Bold** - Gradients should be barely visible
2. **AMOLED First** - Always use tinted whites
3. **Gradient Sparingly** - Don't stack or overuse
4. **Premium Feel** - Achieved through restraint, not excess
5. **Accessibility** - Maintain proper contrast ratios

---

**Last Updated**: December 24, 2025
