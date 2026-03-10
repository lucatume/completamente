# Text Color Effects and Gradients in IntelliJ IDEs

**Question**: What color effects (gradients, animated/changing colors, bold styling) can be applied to
editor text in IntelliJ IDEs, and how can we use them to make FIM/NEP completions and Order 89 output
visually "pop"?

---

## Current Rendering in completamente

| Feature | Current Style | File | Technique |
|---------|--------------|------|-----------|
| Ghost text (FIM/NEP) | Gray `RGB(150,150,150)`, italic | `GhostTextRenderer.kt:18,83` | `EditorCustomElementRenderer` + `TextAttributes` |
| Jump suggestion (new) | White on green `RGB(0,191,0)` | `GhostTextRenderer.kt:20` | Background + foreground color |
| Jump suggestion (old) | Red strikethrough | `GhostTextRenderer.kt:146` | `EffectType.STRIKEOUT` |
| Order 89 animation | Cycling Unicode symbols every 250ms | `Order89Action.kt:171-194` | `javax.swing.Timer` + `inlay.repaint()` |
| Order 89 status text | `JBColor.GRAY` | `Order89Action.kt:180` | Direct color in `paint()` |

All rendering goes through `EditorCustomElementRenderer` implementations (inline and block inlays),
which provide a `paint(inlay, g: Graphics, targetRegion, textAttributes)` method with full access to
Java2D painting.

---

## Available Standard Effects

### TextAttributes Properties

- `foregroundColor` / `backgroundColor` — solid colors only
- `effectColor` + `effectType` — decoration effects
- `fontType` — `EditorFontType.PLAIN`, `ITALIC`, `BOLD`, `BOLD_ITALIC`
- `additionalEffects` — `Map<EffectType, Color>` for stacking multiple effects

### EffectType Enum

| Effect | Description |
|--------|-------------|
| `LINE_UNDERSCORE` | Standard underline |
| `BOLD_LINE_UNDERSCORE` | Bold underline |
| `WAVE_UNDERSCORE` | Wavy underline (spell-check style) |
| `BOLD_DOTTED_LINE` | Bold dotted underline |
| `STRIKEOUT` | Strike-through |
| `BOXED` | Box around text |
| `ROUNDED_BOX` | Rounded box (experimental) |
| `SEARCH_MATCH` | Search result highlight |

**Limitation**: `TextAttributes` only supports flat, solid colors — no gradients, no animation.

---

## Advanced Effects via Graphics2D (Custom Renderers)

Since `EditorCustomElementRenderer.paint()` receives a `Graphics` object, we can cast to `Graphics2D`
and use the full Java2D painting API. This is the path to gradients and animated colors.

### Gradient Text

```kotlin
override fun paint(inlay: Inlay<*>, g: Graphics, r: Rectangle, attrs: TextAttributes) {
    val g2d = g as Graphics2D
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

    val font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
    g2d.font = font

    // --- Option A: LinearGradientPaint (multi-stop) ---
    val gradient = LinearGradientPaint(
        r.x.toFloat(), r.y.toFloat(),
        (r.x + r.width).toFloat(), r.y.toFloat(),
        floatArrayOf(0f, 0.5f, 1f),
        arrayOf(Color(138, 43, 226), Color(255, 215, 0), Color(138, 43, 226)) // violet-gold-violet
    )
    g2d.paint = gradient
    g2d.drawString(text, r.x, r.y + editor.ascent)

    // --- Option B: GradientPaint (two-color) ---
    val simple = GradientPaint(
        r.x.toFloat(), 0f, Color(138, 43, 226),   // violet
        (r.x + r.width).toFloat(), 0f, Color(255, 215, 0) // gold
    )
    g2d.paint = simple
    g2d.drawString(text, r.x, r.y + editor.ascent)
}
```

**Key classes**:
- `java.awt.GradientPaint` — two-color linear gradient
- `java.awt.LinearGradientPaint` — multi-stop linear gradient
- `java.awt.RadialGradientPaint` — radial gradient (less useful for text)

### Animated / Pulsing Colors

Already demonstrated in Order 89 with the spinner symbols. The pattern:

```kotlin
// 1. Create a Timer that increments a frame counter
val timer = Timer(intervalMs) {
    frameCounter++
    inlay.repaint()  // triggers paint() again
}

// 2. In paint(), compute color from frameCounter
override fun paint(...) {
    val g2d = g as Graphics2D
    // Pulse between violet and gold
    val t = (sin(frameCounter * 0.1) + 1) / 2  // 0..1 oscillation
    val r = lerp(138, 255, t)   // violet.r -> gold.r
    val gv = lerp(43, 215, t)   // violet.g -> gold.g
    val b = lerp(226, 0, t)     // violet.b -> gold.b
    g2d.color = Color(r.toInt(), gv.toInt(), b.toInt())
    g2d.drawString(text, ...)
}

fun lerp(a: Int, b: Int, t: Double) = a + (b - a) * t
```

### Gradient Background Behind Text

```kotlin
// Fill a gradient rectangle behind the text, then draw text on top
val bgGradient = LinearGradientPaint(...)
g2d.paint = bgGradient
g2d.fillRoundRect(r.x, r.y, r.width, r.height, 4, 4)
g2d.color = Color.WHITE  // or contrasting foreground
g2d.drawString(text, r.x + pad, r.y + editor.ascent)
```

### Per-Character Color (Rainbow / Shimmer)

```kotlin
val colors = arrayOf(
    Color(138, 43, 226),  // violet
    Color(75, 0, 130),    // indigo
    Color(255, 215, 0),   // gold
    Color(255, 165, 0),   // orange
)
val fm = g2d.fontMetrics
var xPos = r.x
for ((i, ch) in text.withIndex()) {
    val colorIdx = (i + frameCounter) % colors.size  // shift for animation
    g2d.color = colors[colorIdx]
    g2d.drawString(ch.toString(), xPos, r.y + editor.ascent)
    xPos += fm.charWidth(ch)
}
```

---

## Practical Recommendations

### For FIM/NEP Ghost Text — Make It "Pop"

The current gray `RGB(150,150,150)` is intentionally subdued. To make it pop while still reading as
a suggestion (not confirmed code):

| Approach | Visual | Complexity | Notes |
|----------|--------|------------|-------|
| **Violet text** | Solid `RGB(138,43,226)` | Low | Change `GHOST_COLOR`. Immediately distinctive. |
| **Violet-to-gold gradient** | Gradient across the suggestion | Medium | Use `LinearGradientPaint` in `InlineGhostRenderer.paint()` and `BlockGhostRenderer.paint()` |
| **Subtle glow/halo** | Violet text + semi-transparent violet background | Low | Add `backgroundColor = Color(138,43,226,30)` to `TextAttributes` |
| **Gradient background** | Gradient fill behind text | Medium | Paint gradient rect, then white/light text on top |

**Recommendation**: Start with solid violet (`RGB(138,43,226)`) as the ghost color — minimal code
change, immediately distinctive. Add gradient as an enhancement later.

### For Order 89 — "Pop" While Model Is Thinking

The current implementation already animates spinner symbols. Enhancements:

| Approach | Visual | Complexity | Notes |
|----------|--------|------------|-------|
| **Pulsing violet** | Color oscillates violet ↔ gold | Low | Add color interpolation in existing Timer callback |
| **Gradient status text** | "Executing Order 89..." in violet-gold gradient | Medium | Use `LinearGradientPaint` in `paint()` |
| **Shimmer effect** | Colors shift across characters over time | Medium | Per-character coloring with frame offset |
| **Gradient background pill** | Status text on gradient rounded rect | Medium | `fillRoundRect()` with gradient, white text |

**Recommendation**: Pulsing color is the best bang-for-buck — reuses the existing Timer, adds 5-10
lines of code in `paint()`, and creates a clear "thinking" visual signal.

---

## Boundaries and Caveats

### What Works

- Any `Graphics2D` operation works inside `EditorCustomElementRenderer.paint()`
- `Timer` + `inlay.repaint()` is the correct pattern for animation (already proven in Order 89)
- Gradient paints (`GradientPaint`, `LinearGradientPaint`) work on `drawString()` calls
- Per-character rendering works but is slower for long strings

### What Doesn't Work

- `TextAttributes` alone cannot do gradients — only solid colors
- `RangeHighlighter` (markup model) cannot do gradients — standard API only
- No built-in "glow" or "shadow" text effect in IntelliJ (must paint manually with offset)
- Heavy animation (high-frequency repaints) can cause editor lag — keep intervals >= 100ms

### Performance Considerations

- `LinearGradientPaint` creation is cheap but should be cached if text doesn't change
- Per-character drawing is ~3-5x slower than single `drawString()` — fine for short suggestions
- Block inlays with gradient backgrounds need careful clipping to avoid painting outside bounds
- Timer disposal is critical — always `timer.stop()` in the `Disposer` callback

---

## Sources

- IntelliJ `TextAttributes`: [JetBrains/intellij-community TextAttributes.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/editor/markup/TextAttributes.java)
- IntelliJ `EffectType`: [API docs](https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/editor/markup/EffectType.html)
- IntelliJ `EditorCustomElementRenderer`: [API docs](https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/editor/EditorCustomElementRenderer.html)
- Java `LinearGradientPaint`: [Java SE docs](https://docs.oracle.com/en/java/javase/15/docs/api/java.desktop/java/awt/LinearGradientPaint.html)
- Java `GradientPaint`: [Java SE docs](https://docs.oracle.com/en/java/javase/15/docs/api/java.desktop/java/awt/GradientPaint.html)
- Codebase: `GhostTextRenderer.kt`, `Order89Action.kt`, `FimSuggestionManager.kt`
