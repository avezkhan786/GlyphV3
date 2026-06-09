# 🎨 Glyph Theming Architecture Rebuild - Implementation Plan

## Executive Summary

Complete redesign of the theming system to establish a **Single Source of Truth** for all visual styling across the entire app. This addresses fragmentation, inconsistencies, and broken theme inheritance.

---

## Current Problems Identified

### 1. **Fragmented Color Definitions**
- Colors defined in 3+ places: LightTheme.kt, PastelSkyTheme.kt, colors.xml, themes.xml
- Hardcoded Color() values in 50+ locations across Compose files
- No semantic naming - components use raw colors instead of semantic tokens

### 2. **Inconsistent Theme Application**
- Some screens use `MaterialTheme.colorScheme` 
- Others use custom theme objects (`LightTheme`, `PastelSkyTheme`)
- XML layouts use `?attr/glyph*` attributes
- No unified approach = broken themes in many screens

### 3. **Missing Coverage**
- Many UI elements don't respond to theme changes:
  - Letter avatars (hardcoded colors)
  - Archive row icons
  - Various typography colors
  - Status/system bar colors on some screens
  - Bottom sheets and dialogs

### 4. **No Runtime Safety**
- Theme switches require manual `recreate()` calls
- No automatic recomposition in Compose
- Fragments/Activities don't sync theme state

### 5. **Pastel-Sky Theme Incomplete**
- Only partially integrated
- Gradients not applied consistently
- Missing from many components

---

## Solution Architecture

### Phase 1: Core Semantic Token System ✅ DONE
**File Created:** `GlyphThemeTokens.kt`

- Defined `GlyphThemeTokens` interface with 100+ semantic properties
- Created `LocalGlyphTheme` CompositionLocal for Compose
- Created `glyphTheme` extension property for easy access

**Benefits:**
- Single interface that all themes must implement
- Type-safe, compile-time checked
- Semantic naming (what it represents, not what it looks like)

---

### Phase 2: Implement All Themes (IN PROGRESS)

#### 2.1 Light Theme
**File:** `LightTheme.kt` → `LightThemeTokens.kt`

```kotlin
object LightThemeTokens : GlyphThemeTokens {
    // Implement all 100+ properties with warm beige palette
    override val backgroundPrimary = Beige4 // #EEEAE6
    override val textPrimary = Color(0xFF2B2B2B)
    // ... complete implementation
}
```

#### 2.2 Dark Theme  
**File:** `DarkThemeTokens.kt` (NEW)

```kotlin
object DarkThemeTokens : GlyphThemeTokens {
    // Complete dark mode with AMOLED-friendly colors
    override val backgroundPrimary = Color(0xFF0B141A)
    override val textPrimary = Color(0xFFE3E3E3)
    // ... complete implementation
}
```

#### 2.3 Pastel-Sky Premium Theme
**File:** `PastelSkyTheme.kt` → `PastelSkyThemeTokens.kt`

```kotlin
object PastelSkyThemeTokens : GlyphThemeTokens {
    // Premium gradient-first theme
    override val backgroundPrimary = CreamWhite
    override val gradientPrimary = primaryGradientBrush()
    override val gradientHeader = headerGradientBrush()
    // ... complete implementation with all gradients
}
```

#### 2.4 AMOLED Black Theme (FUTURE)
- Pure blacks (#000000) for power savings
- High contrast for readability

---

### Phase 3: Unified Theme Provider

**File:** `GlyphThemeProvider.kt` (NEW)

```kotlin
@Composable
fun GlyphThemeProvider(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val currentThemeId = ThemeManager.getCurrentTheme(context)
    
    val tokens = remember(currentThemeId) {
        when (currentThemeId) {
            ThemeManager.THEME_LIGHT -> LightThemeTokens
            ThemeManager.THEME_DARK -> DarkThemeTokens
            ThemeManager.THEME_PASTEL_SKY -> PastelSkyThemeTokens
            else -> LightThemeTokens
        }
    }
    
    GlyphTheme(tokens = tokens) {
        // Also wrap with MaterialTheme for compatibility
        MaterialTheme(
            colorScheme = tokens.toMaterial3ColorScheme(),
            content = content
        )
    }
}
```

---

### Phase 4: Update All Compose Components

**Files to Update:**
- `ChatListScreen.kt` - Replace all Color() with `glyphTheme.property`
- `ChatRow` - Use semantic tokens
- `Avatar` - Use token-based letter colors
- `PresenceIndicator` - Use `indicatorOnline`
- All other Composables

**Pattern:**
```kotlin
// ❌ OLD - Hardcoded
Text(
    text = "Hello",
    color = Color(0xFF2B2B2B)
)

// ✅ NEW - Semantic token
Text(
    text = "Hello",
    color = glyphTheme.textPrimary
)
```

---

### Phase 5: Update XML Theme System

**File:** `themes.xml`

Ensure all `glyph*` attributes map correctly to semantic tokens:

```xml
<!-- Light Theme -->
<item name="glyphTextPrimary">@color/light_text_primary</item>
<item name="glyphBackgroundPrimary">@color/light_background_primary</item>
<!-- ... complete mapping -->

<!-- Dark Theme -->
<item name="glyphTextPrimary">@color/dark_text_primary</item>
<item name="glyphBackgroundPrimary">@color/dark_background_primary</item>
<!-- ... complete mapping -->

<!-- Pastel-Sky Theme -->
<item name="glyphTextPrimary">@color/pastel_text_primary</item>
<item name="glyphBackgroundPrimary">@color/pastel_background</item>
<!-- ... complete mapping -->
```

---

### Phase 6: Runtime Theme Switching

**File:** `ThemeManager.kt` (UPDATED)

```kotlin
object ThemeManager {
    // Emit theme changes as a Flow
    private val _themeFlow = MutableStateFlow(THEME_LIGHT)
    val themeFlow: StateFlow<String> = _themeFlow.asStateFlow()
    
    fun setTheme(context: Context, theme: String) {
        saveTheme(context, theme)
        _themeFlow.value = theme
        
        // Trigger all activities to recompose
        if (context is Activity) {
            context.recreate()
        }
    }
}
```

**Update GlyphThemeProvider:**
```kotlin
@Composable
fun GlyphThemeProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val currentTheme by ThemeManager.themeFlow.collectAsState()
    
    val tokens = remember(currentTheme) {
        // Auto-switches when theme changes
        getThemeTokens(currentTheme)
    }
    
    GlyphTheme(tokens) { content() }
}
```

---

### Phase 7: Validation & Testing

#### 7.1 Automated Checks
- [  ] No hardcoded Color() in any Composable
- [  ] All XML layouts use `?attr/glyph*` 
- [  ] No direct MaterialTheme color usage (except in provider)
- [  ] All themes implement 100% of GlyphThemeTokens

#### 7.2 Visual Testing
- [  ] Light theme: All screens render correctly
- [  ] Dark theme: All screens render correctly
- [  ] Pastel-Sky: Gradients visible, no harsh contrast
- [  ] Theme switching: Instant, no crashes, no white flashes

#### 7.3 Edge Cases
- [  ] Status bar colors match theme
- [  ] Navigation bar colors match theme
- [  ] System dialogs (pickers, permissions) themed
- [  ] Keyboard (if themeable) matches
- [  ] Splash screen matches theme

---

## Implementation Order

### Week 1: Foundation
1. ✅ Create `GlyphThemeTokens.kt` interface
2. ⏳ Implement `LightThemeTokens` completely
3. ⏳ Implement `DarkThemeTokens` completely
4. ⏳ Implement `PastelSkyThemeTokens` completely
5. ⏳ Create `GlyphThemeProvider.kt`

### Week 2: Compose Migration
6. ⏳ Update `ChatListScreen.kt` to use tokens
7. ⏳ Update `Theme.kt` (old GlyphComposeTheme → use GlyphThemeProvider)
8. ⏳ Update all other Composables
9. ⏳ Remove old LightTheme/PastelSkyTheme objects (keep only Tokens versions)

### Week 3: XML & Integration
10. ⏳ Update `themes.xml` with complete mappings
11. ⏳ Update `colors.xml` with semantic names
12. ⏳ Update `ThemeManager` with Flow-based switching
13. ⏳ Update all Activities to use ThemedActivity base class

### Week 4: Testing & Polish
14. ⏳ Visual regression testing
15. ⏳ Fix any broken UI
16. ⏳ Performance testing (theme switch speed)
17. ⏳ Documentation & examples

---

## Success Criteria

### Must Have
- ✅ 100% semantic token coverage
- ⏳ Zero hardcoded colors in UI code
- ⏳ All 4 themes (Light, Dark, Pastel-Sky, AMOLED) fully functional
- ⏳ Instant theme switching without app restart
- ⏳ No visual glitches or unreadable text

### Nice to Have
- ⏳ Animated theme transitions (color interpolation)
- ⏳ Per-screen theme override (e.g., dark mode for media viewer)
- ⏳ User-customizable accent colors
- ⏳ Dynamic color extraction from user avatar

---

## File Structure (After Refactor)

```
ui/theme/
├── GlyphThemeTokens.kt         # Interface (100+ properties)
├── GlyphThemeProvider.kt       # Compose provider
├── LightThemeTokens.kt         # Light theme implementation
├── DarkThemeTokens.kt          # Dark theme implementation
├── PastelSkyThemeTokens.kt     # Premium theme implementation
├── AmoledThemeTokens.kt        # AMOLED black theme (future)
├── ThemeExtensions.kt          # Helper extensions (glyphTheme, etc.)
└── Color.kt                    # Base color palette (rarely used directly)

res/values/
├── themes.xml                  # XML theme definitions (maps to tokens)
├── colors.xml                  # Named colors (rarely used directly)
└── attrs.xml                   # Custom attributes (glyph*)

utils/
└── ThemeManager.kt             # Theme switching logic + Flow
```

---

## Migration Strategy

### Step 1: Parallel Implementation
- Keep old system working
- Build new system alongside
- Gradually migrate screens

### Step 2: Feature Flag
```kotlin
object FeatureFlags {
    const val USE_NEW_THEME_SYSTEM = true // Toggle here
}
```

### Step 3: Gradual Rollout
- Migrate one screen at a time
- Test thoroughly
- Roll back if issues found

---

## Risk Mitigation

### Risk 1: Breaking Existing UI
**Mitigation:** Feature flag, gradual migration, extensive testing

### Risk 2: Performance Impact
**Mitigation:** Use `remember()`, avoid recomposition storms, profile with Android Studio

### Risk 3: Third-party Components
**Mitigation:** Keep MaterialTheme compatibility layer, map semantic tokens to Material3 colors

---

## Next Steps

1. **Finalize LightThemeTokens implementation** (all 100+ properties)
2. **Create DarkThemeTokens** (mirror structure)
3. **Create PastelSkyThemeTokens** (with gradients)
4. **Create GlyphThemeProvider** (unified entry point)
5. **Migrate ChatListScreen as proof-of-concept**
6. **Roll out to all screens**

---

## Questions to Resolve

1. Should we support per-component theme overrides?
2. Should we animate color transitions when theme switches?
3. Should we support custom user themes (user-defined colors)?
4. Should we extract theme data to JSON for easier customization?

---

**Status:** 🟡 IN PROGRESS
**Owner:** AI Assistant
**Last Updated:** December 24, 2025
**Next Review:** After Phase 2 completion

---

This is a comprehensive rebuild. The foundation (GlyphThemeTokens) is complete. 
Ready to proceed with full implementation across all themes and components?
