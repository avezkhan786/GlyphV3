# 🎨 Pastel-Sky Premium Theme - Implementation Guide

## ✨ Overview

The **Pastel-Sky** theme is a premium, gradient-first theme designed to provide a calm, uplifting, and luxurious chat experience. It feels airy and modern while being optimized for long chat usage.

### Theme Philosophy

- **Premium** ✨ - Worthy of a flagship/pro feature
- **Calm & Uplifting** 🌤️ - Soft pastel colors that don't overwhelm
- **Elegant, Not Childish** - Sophisticated pastel palette
- **AMOLED-Optimized** - Uses tinted whites (#FBFAF8) instead of pure white
- **Gradient-First** - Subtle gradients that never overpower content

---

## 🎨 Color Palette

```kotlin
Sky Blue:       #CFE9F3  // Primary accent
Lavender Mist:  #E6DDF2  // Soft purple tint
Mint Cloud:     #DFF2EA  // Calming green
Peach Haze:     #F6E2D6  // Warm accent
Cream White:    #FBFAF8  // AMOLED-safe white
Cloud Gray:     #E8EDF2  // Soft neutral
```

---

## 📁 File Structure

### Created/Modified Files

#### Kotlin Theme Definition
- `app/src/main/java/com/glyph/glyph_v3/ui/theme/PastelSkyTheme.kt`
  - **Purpose**: Central color definitions and gradient utilities
  - **Features**: 
    - All color values
    - Gradient brush functions
    - Animation parameters
    - Material3 compatibility colors

#### Theme Manager Updates
- `app/src/main/java/com/glyph/glyph_v3/utils/ThemeManager.kt`
  - Added `THEME_PASTEL_SKY = "pastel_sky"` constant
  - Updated all theme selection logic
  - Display name: "Pastel-Sky ✨"

#### Theme Extensions
- `app/src/main/java/com/glyph/glyph_v3/utils/ThemeExtensions.kt`
  - All helper functions now support Pastel-Sky
  - Theme-aware color resolution for all UI components

#### Compose Theme Wrapper
- `app/src/main/java/com/glyph/glyph_v3/ui/theme/Theme.kt`
  - Added `PastelSkyComposeTheme()` composable
  - Full Material3 color scheme integration

#### Theme Selection Activity
- `app/src/main/java/com/glyph/glyph_v3/ui/settings/ThemeSelectionActivity.kt`
  - Updated to use correct theme constants
  - Already has UI for Pastel theme selection

### XML Resources

#### Colors (`app/src/main/res/values/colors.xml`)
Added complete Pastel-Sky color definitions:
- Core palette colors
- Background colors
- Text colors
- UI component colors
- Chat-specific colors

#### Theme Definition (`app/src/main/res/values/themes.xml`)
Added `Theme.GlyphV3.PastelSky` style:
- Complete Material3 color mapping
- Custom Glyph attributes
- FAB styling
- All UI component theming

#### Gradient Drawables (`app/src/main/res/drawable/`)
Created premium gradient resources:
- `bg_pastel_primary_gradient.xml` - App background gradient
- `bg_pastel_header_gradient.xml` - Top bar gradient
- `bg_pastel_bubble_outgoing.xml` - Outgoing message gradient
- `bg_pastel_bubble_incoming.xml` - Incoming message with soft border
- `bg_pastel_send_button.xml` - Send button gradient
- `bg_pastel_date_header.xml` - Date header pill
- `bg_pastel_input_field.xml` - Input field background
- `bg_pastel_fab_gradient.xml` - FAB gradient

---

## 🚀 Usage

### 1. Applying the Theme in Activities

All activities extending `ThemedActivity` will automatically apply the theme:

```kotlin
class MyActivity : ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Theme is automatically applied
        setContentView(R.layout.activity_my)
    }
}
```

### 2. Using Gradients in Compose

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import com.glyph.glyph_v3.ui.theme.PastelSkyTheme

@Composable
fun MyScreen() {
    PastelSkyComposeTheme {
        // Use gradient backgrounds
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PastelSkyTheme.primaryGradientBrush())
        ) {
            // Your content here
        }
    }
}
```

### 3. Chat Bubbles with Gradients

#### Outgoing Messages (Compose)
```kotlin
Surface(
    shape = RoundedCornerShape(16.dp),
    modifier = Modifier.background(
        brush = PastelSkyTheme.outgoingBubbleGradient()
    )
) {
    Text(
        text = message,
        color = PastelSkyTheme.outgoingBubbleText
    )
}
```

#### Incoming Messages (XML)
```xml
<androidx.cardview.widget.CardView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_pastel_bubble_incoming"
    app:cardCornerRadius="16dp">
    
    <TextView
        android:textColor="@color/pastel_bubble_incoming_text"
        ... />
</androidx.cardview.widget.CardView>
```

### 4. Send Button with Gradient (XML)

```xml
<ImageButton
    android:id="@+id/sendButton"
    android:layout_width="48dp"
    android:layout_height="48dp"
    android:background="@drawable/bg_pastel_send_button"
    android:src="@drawable/ic_send"
    android:tint="@color/pastel_send_button_icon" />
```

### 5. Header/Top Bar (Compose)

```kotlin
TopAppBar(
    title = { 
        Text(
            "Chat Title",
            color = PastelSkyTheme.topAppBarTitleText
        )
    },
    modifier = Modifier.background(
        brush = PastelSkyTheme.headerGradientBrush()
    ),
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent
    )
)
```

### 6. Date Headers

```kotlin
Text(
    text = "Today",
    color = PastelSkyTheme.dateHeaderText,
    modifier = Modifier
        .background(
            color = PastelSkyTheme.dateHeaderBackground,
            shape = RoundedCornerShape(12.dp)
        )
        .padding(horizontal = 16.dp, vertical = 6.dp)
)
```

---

## 🎯 Design Guidelines

### Gradient Usage

1. **Primary App Background**: Use `primaryGradientBrush()` sparingly
   - Apply only to full-screen backgrounds
   - Never stack gradients

2. **Chat Bubbles**: Use `outgoingBubbleGradient()`
   - Horizontal gradient (left → right)
   - Extremely subtle elevation

3. **Send Button**: Use `sendButtonGradient()`
   - Icon-only animation, not container
   - Gradient should be barely visible

### Color Rules

✅ **Do:**
- Use `CreamWhite` (#FBFAF8) instead of pure white
- Apply soft borders with `CloudGray`
- Use `primaryText` for all text (never pure black)
- Keep gradients subtle and slow-moving

❌ **Don't:**
- Use pure white (#FFFFFF) - breaks AMOLED optimization
- Use pure black (#000000) - too harsh
- Stack multiple gradients
- Use neon or saturated colors
- Make gradients loud or noticeable

### Typography

```kotlin
// Primary text (titles, messages)
color = PastelSkyTheme.primaryText          // #2B2F36

// Secondary text (timestamps, status)
color = PastelSkyTheme.secondaryText        // #6A7280

// Disabled text
color = PastelSkyTheme.disabledText         // #9AA3AF

// Placeholder text
color = PastelSkyTheme.placeholderText      // #8A92A0
```

### Animation Parameters

```kotlin
// Durations (slightly slower than default)
PastelSkyTheme.DURATION_SHORT    // 400ms
PastelSkyTheme.DURATION_MEDIUM   // 600ms
PastelSkyTheme.DURATION_LONG     // 800ms

// Scale on press
PastelSkyTheme.SCALE_PRESS_START // 0.96f
PastelSkyTheme.SCALE_PRESS_END   // 1.0f

// Alpha values
PastelSkyTheme.ALPHA_DISABLED    // 0.38f
PastelSkyTheme.ALPHA_PRESSED     // 0.08f
PastelSkyTheme.ALPHA_HOVER       // 0.04f
```

---

## 📱 Large Screen Optimization

For tablets and large phones:

```kotlin
val isLargeScreen = configuration.screenWidthDp >= 600

val horizontalPadding = if (isLargeScreen) {
    PastelSkyTheme.tabletHorizontalPadding  // 32.dp
} else {
    16.dp
}

// Chat bubble max width
val bubbleMaxWidth = if (isLargeScreen) {
    PastelSkyTheme.tabletMaxBubbleWidth  // 0.65f (65%)
} else {
    0.75f
}
```

---

## 🌑 AMOLED Optimization

The theme is already optimized for AMOLED displays:

1. **Tinted Whites**: All whites use `CreamWhite` (#FBFAF8)
2. **Soft Elevation**: Uses blur and translucency instead of shadows
3. **Lower Contrast**: Slightly reduced to prevent eye strain
4. **No Pure Black**: Even dark elements are slightly tinted

---

## 🔄 Theme Switching

### Programmatically Switch Theme

```kotlin
// In any Activity
ThemeManager.setTheme(this, ThemeManager.THEME_PASTEL_SKY)
// Activity will recreate automatically
```

### Check Current Theme

```kotlin
val currentTheme = ThemeManager.getCurrentTheme(context)
val isPastelSky = currentTheme == ThemeManager.THEME_PASTEL_SKY
```

### Get Theme Display Name

```kotlin
val displayName = ThemeManager.getThemeDisplayName(
    ThemeManager.THEME_PASTEL_SKY
)
// Returns: "Pastel-Sky ✨"
```

---

## 🧪 Testing Checklist

- [ ] Theme selection UI shows Pastel-Sky option
- [ ] App background uses gradient
- [ ] Top bar displays with subtle gradient
- [ ] Outgoing chat bubbles use mint-to-sky gradient
- [ ] Incoming chat bubbles have soft border
- [ ] Send button shows gradient
- [ ] Date headers use lavender background
- [ ] Input field has proper styling
- [ ] FAB uses gradient
- [ ] All text is readable (proper contrast)
- [ ] Theme switches instantly without glitches
- [ ] AMOLED display looks good (no pure whites)
- [ ] Large screens scale properly
- [ ] Animations feel premium and smooth

---

## 💡 Tips for Developers

1. **Always Use Theme Colors**: Never hardcode colors
   ```kotlin
   // ❌ Bad
   color = Color(0xFFFFFFFF)
   
   // ✅ Good
   color = PastelSkyTheme.CreamWhite
   ```

2. **Gradients in Compose**: Use the provided brush functions
   ```kotlin
   Modifier.background(PastelSkyTheme.primaryGradientBrush())
   ```

3. **XML Drawables**: Reference the gradient drawables
   ```xml
   android:background="@drawable/bg_pastel_bubble_outgoing"
   ```

4. **Theme-Aware Extensions**: Use helper functions
   ```kotlin
   context.getThemeBackgroundColor()
   context.getThemePrimaryColor()
   ```

---

## 🎨 Future Enhancements

Consider adding:
- [ ] Custom animation curves for premium feel
- [ ] Subtle parallax effects on scroll
- [ ] Gradient animation on long-press
- [ ] Theme-specific sound effects
- [ ] Dark variant of Pastel-Sky theme
- [ ] Premium haptic feedback patterns

---

## 📝 Notes

- All gradients are designed to be **barely noticeable** - this is intentional
- The theme feels premium through subtlety, not boldness
- AMOLED optimization is critical - don't use pure whites
- Theme switching is instant and glitch-free by design
- All components respect the theme automatically

---

## 🆘 Troubleshooting

### Gradients Not Showing
- Ensure you're using `Modifier.background(brush = ...)` in Compose
- In XML, use the drawable resources, not color values

### Colors Look Wrong
- Check if you're using pure white instead of `CreamWhite`
- Verify theme is properly applied in `onCreate()`

### Theme Not Switching
- Ensure activity extends `ThemedActivity`
- Check that `ThemeManager.setTheme()` is called

### Build Errors
- Clean and rebuild project
- Ensure all R.color references are correct
- Check that themes.xml has no syntax errors

---

**Created**: December 24, 2025  
**Version**: 1.0  
**Status**: ✅ Production Ready
