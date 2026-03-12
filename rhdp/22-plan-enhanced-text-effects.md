# Plan: Enhanced Text Effects for FIM/NEP and Order 89

Implements gradient text for FIM/NEP ghost completions and pulsing/shimmer effects for Order 89
"thinking" status. Based on research in `21-research-intellij-text-color-effects.md`.

---

## Part A: FIM/NEP Gradient Ghost Text

**Goal**: Ghost text renders with a violet-to-gold `LinearGradientPaint` instead of flat gray.

### Step 1: Add Graphics2D imports to GhostTextRenderer.kt

**File**: `src/main/kotlin/com/github/lucatume/completamente/fim/GhostTextRenderer.kt`

Add imports:
```kotlin
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
```

### Step 2: Define gradient color constants

**File**: `GhostTextRenderer.kt`, replace `GHOST_COLOR` block (lines 18-23).

Keep `GHOST_COLOR` as a fallback but add gradient endpoints:

```kotlin
private val GHOST_COLOR = Color(150, 150, 150)
private val GRADIENT_START = Color(138, 43, 226)  // violet
private val GRADIENT_END = Color(255, 215, 0)     // gold
```

### Step 3: Extract a gradient-aware drawString helper

**File**: `GhostTextRenderer.kt`, add a top-level private function.

```kotlin
private fun drawGradientString(g: Graphics, text: String, x: Int, y: Int, width: Int) {
    val g2d = g as? Graphics2D ?: run { g.drawString(text, x, y); return }
    g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    if (width <= 0) { g2d.color = GRADIENT_START; g2d.drawString(text, x, y); return }
    val gradient = LinearGradientPaint(
        x.toFloat(), 0f, (x + width).toFloat(), 0f,
        floatArrayOf(0f, 1f), arrayOf(GRADIENT_START, GRADIENT_END)
    )
    g2d.paint = gradient
    g2d.drawString(text, x, y)
}
```

This is a pure function that handles the `Graphics` → `Graphics2D` cast, sets anti-aliasing, builds
the gradient, and draws. Both renderers call it instead of `g.drawString()`.

### Step 4: Update InlineGhostRenderer.paint()

**File**: `GhostTextRenderer.kt`, `InlineGhostRenderer.paint()` (line 37-47).

Replace the non-jump path (when `bgColor == null` and `color == GHOST_COLOR`):

```kotlin
override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
    val editor = inlay.editor
    if (bgColor != null) {
        g.color = bgColor
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
    }
    g.font = ghostFont(editor)
    val textX = if (bgColor != null) targetRegion.x + BG_PAD else targetRegion.x
    if (bgColor == null) {
        drawGradientString(g, text, textX, targetRegion.y + editor.ascent, targetRegion.width)
    } else {
        g.color = color
        g.drawString(text, textX, targetRegion.y + editor.ascent)
    }
}
```

The jump path (`bgColor != null`) keeps its existing solid-color rendering.

**Checkpoint**: Build and run. FIM/NEP inline suggestions should show a violet→gold gradient. Jump
suggestions should look unchanged.

### Step 5: Update BlockGhostRenderer.paint()

**File**: `GhostTextRenderer.kt`, `BlockGhostRenderer.paint()` (line 66-79).

Same pattern — use gradient for the non-jump path:

```kotlin
override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
    val editor = inlay.editor
    val lineHeight = editor.lineHeight
    if (bgColor != null) {
        g.color = bgColor
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
    }
    g.font = ghostFont(editor)
    val textX = if (bgColor != null) targetRegion.x + BG_PAD else targetRegion.x
    for ((i, line) in lines.withIndex()) {
        val y = targetRegion.y + editor.ascent + (i * lineHeight)
        if (bgColor == null) {
            val fm = g.fontMetrics
            drawGradientString(g, line, textX, y, fm.stringWidth(line))
        } else {
            g.color = color
            g.drawString(line, textX, y)
        }
    }
}
```

Each block line gets its own gradient span based on its own pixel width.

**Checkpoint**: Multi-line FIM completions should each show the gradient independently per line.

---

## Part B: Order 89 Pulsing Gradient "Thinking" Effect

**Goal**: The "Executing Order 89" status text pulses between violet and gold, and the spinner
symbol uses a shifting gradient. The truncated prompt line stays a subdued color for contrast.

### Step 6: Add Graphics2D imports to Order89Action.kt

**File**: `src/main/kotlin/com/github/lucatume/completamente/order89/Order89Action.kt`

Add imports:
```kotlin
import java.awt.Color
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
```

### Step 7: Add color constants and lerp helper in Order89Action.kt

**File**: `Order89Action.kt`, at file level (near `truncatePrompt`).

```kotlin
private val ORDER89_VIOLET = Color(138, 43, 226)
private val ORDER89_GOLD = Color(255, 215, 0)
private val ORDER89_PROMPT_COLOR = Color(130, 130, 130)

private fun lerpColor(a: Color, b: Color, t: Double): Color {
    val ct = t.coerceIn(0.0, 1.0)
    return Color(
        (a.red + (b.red - a.red) * ct).toInt(),
        (a.green + (b.green - a.green) * ct).toInt(),
        (a.blue + (b.blue - a.blue) * ct).toInt()
    )
}
```

### Step 8: Update addExecutingInlay renderer to pulse

**File**: `Order89Action.kt`, `addExecutingInlay()` (lines 170-201).

Replace the renderer's `paint()` method. The `symbolIndex` variable already increments every 250ms.
Use it to derive a smooth oscillation:

```kotlin
val renderer = object : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 0

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2d = g as? Graphics2D
        g2d?.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val lineHeight = editor.lineHeight
        g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)

        // Pulse factor: oscillates 0→1→0 using the frame counter
        val t = (Math.sin(symbolIndex * 0.5) + 1.0) / 2.0
        val pulseColor = lerpColor(ORDER89_VIOLET, ORDER89_GOLD, t)

        // Line 1: spinner + "Executing Order 89" in pulsing color
        val statusText = "${symbols[symbolIndex]} Executing Order 89"
        if (g2d != null) {
            val fm = g2d.fontMetrics
            val textWidth = fm.stringWidth(statusText)
            if (textWidth > 0) {
                val gradient = LinearGradientPaint(
                    indentX.toFloat(), 0f,
                    (indentX + textWidth).toFloat(), 0f,
                    floatArrayOf(0f, 1f),
                    arrayOf(pulseColor, lerpColor(ORDER89_GOLD, ORDER89_VIOLET, t))
                )
                g2d.paint = gradient
            } else {
                g2d.color = pulseColor
            }
            g2d.drawString(statusText, indentX, targetRegion.y + editor.ascent)
        } else {
            g.color = pulseColor
            g.drawString(statusText, indentX, targetRegion.y + editor.ascent)
        }

        // Line 2: prompt text in subdued gray
        g.color = ORDER89_PROMPT_COLOR
        val promptX = indentX + g.fontMetrics.stringWidth("${symbols[symbolIndex]} ")
        g.drawString(truncated, promptX, targetRegion.y + editor.ascent + lineHeight)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight * 2
}
```

The gradient endpoints themselves shift each frame (via `pulseColor` and its complement), creating
a shimmer that flows across the text as the Timer ticks.

**Checkpoint**: Trigger Order 89. The "Executing Order 89" line should show a gradient that gently
shifts between violet and gold every 250ms. The prompt line below should stay gray.

### Step 9: Tune animation speed

**File**: `Order89Action.kt`, Timer interval (line 192).

The current 250ms interval works well for the spinner. For smoother color pulsing, consider reducing
to 100ms. This is a single-number change:

```kotlin
val timer = Timer(100) { ... }
```

At 100ms the `symbolIndex` increments faster, so the `sin(symbolIndex * 0.5)` wave completes a full
cycle every ~12.6 frames ≈ 1.26 seconds — a comfortable breathing rhythm.

If 100ms feels too fast for the spinner characters, decouple them: keep the Timer at 100ms but only
advance `symbolIndex` for the spinner every 3rd tick:

```kotlin
var frameCount = 0
val timer = Timer(100) {
    frameCount++
    if (frameCount % 3 == 0) symbolIndex = (symbolIndex + 1) % symbols.size
    inlay.repaint()
}
```

Use `frameCount` for the color oscillation and `symbolIndex` for the spinner.

**Checkpoint**: Verify the animation feels smooth but not distracting. Adjust the `0.5` multiplier
in `sin(symbolIndex * 0.5)` to speed up (larger) or slow down (smaller) the color pulse.

---

## Summary

| Step | File | Change | Effort |
|------|------|--------|--------|
| 1 | GhostTextRenderer.kt | Add Graphics2D/gradient imports | Trivial |
| 2 | GhostTextRenderer.kt | Add gradient color constants | Trivial |
| 3 | GhostTextRenderer.kt | Add `drawGradientString()` helper | Small |
| 4 | GhostTextRenderer.kt | Update `InlineGhostRenderer.paint()` | Small |
| 5 | GhostTextRenderer.kt | Update `BlockGhostRenderer.paint()` | Small |
| 6 | Order89Action.kt | Add Graphics2D/gradient imports | Trivial |
| 7 | Order89Action.kt | Add color constants and `lerpColor()` | Trivial |
| 8 | Order89Action.kt | Rewrite renderer `paint()` with gradient pulse | Medium |
| 9 | Order89Action.kt | Tune animation timing | Trivial |

Total: ~2 files changed, ~50 net new lines.
