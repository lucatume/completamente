package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.services.DebugLog
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.concurrency.ThreadingAssertions
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Stroke
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedString

/**
 * Block-element inlay that renders narration + step counter + optional footer between the line
 * above the highlighted range and the highlighted line. Painted entirely with [Graphics2D];
 * no Swing components inside.
 */
internal class NarrationInlay(
    editor: Editor,
    rangeStartOffset: Int,
    narration: String?,
    stepCounter: String,
    footerStatus: String?,
    startColPx: Int,
    parentDisposable: Disposable,
    navEnabled: BooleanArray,
    navCallbacks: Array<() -> Unit>,
) {

    val renderer: Renderer = Renderer(editor, narration, stepCounter, footerStatus, startColPx, navEnabled, navCallbacks)
    private val inlay: Inlay<Renderer>?

    /** Exposed to the session so it can read `bounds` for manual hit-testing in `mouseClicked`. */
    internal val attachedInlay: Inlay<*>? get() = inlay

    /** Navigation button index discipline — used by `routeClick` and `WalkthroughSession`. */
    enum class NavButton { FIRST, PREV, NEXT, LAST }

    init {
        val doc = editor.document
        val safeOffset = rangeStartOffset.coerceIn(0, doc.textLength)
        val anchor = doc.getLineStartOffset(doc.getLineNumber(safeOffset))
        inlay = editor.inlayModel.addBlockElement(
            anchor, /*relatesToPrecedingText=*/false, /*showAbove=*/true, /*priority=*/0, renderer
        )
        if (inlay != null) {
            Disposer.register(parentDisposable, inlay)
        } else {
            DebugLog.log("NarrationInlay: inlayModel.addBlockElement returned null at offset=$anchor")
        }
    }

    /** True iff the platform accepted the block-element placement. */
    fun isAttached(): Boolean = inlay != null

    /**
     * Custom-painted renderer. State (cached `TextLayout`s) is recomputed lazily on width
     * change inside [prepareLayout]; `paint` allocates nothing per call beyond what
     * `TextLayout.draw` does internally.
     */
    internal class Renderer(
        private val editor: Editor,
        private val narration: String?,
        internal val stepCounter: String,
        internal val footerStatus: String?,
        val startColPx: Int,
        private val navEnabled: BooleanArray,
        private val navCallbacks: Array<() -> Unit>,
    ) : EditorCustomElementRenderer {

        internal var laidOutWidth: Int = -1
        internal var lines: List<TextLayout> = emptyList()
        internal var heightCache: Int = 0

        // Reused per paint; mutation is single-threaded (EDT-only via prepareLayout assertion).
        private val wedgeXs = IntArray(3)
        private val wedgeYs = IntArray(3)

        // Body sub-rect cache — recomputed in prepareLayout when width changes.
        // bodyXOffsetPx is the body's left edge expressed as an offset from `target.x` (so the
        // absolute X is computed at paint time as `target.x + bodyXOffsetPx`).
        internal var bodyXOffsetPx: Int = 0
        internal var bodyWidthPx: Int = 0
        // Scaled DP→PX caches; sole source of truth is `prepareLayout` / `refreshFontCacheIfNeeded`.
        private var bodyPaddingPx: Int = 0
        private var bodyLeftInsetPx: Int = 0
        private var maxBodyWidthPx: Int = 0
        private var wedgeWidthPx: Int = 0
        private var wedgeHeightPx: Int = 0
        private var borderStrokePx: Int = 0
        private var borderStroke: BasicStroke = BasicStroke(1f)

        // Font/color caches — invalidated alongside `laidOutWidth` when the editor scheme changes.
        private var cachedSchemeFontSize: Int = -1
        private var cachedBodyFont: Font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        private var cachedFooterFont: Font = Font(Font.MONOSPACED, Font.ITALIC, 12)
        // Bigger + bold chevron font: gives the controls a clear visual affordance and a wider
        // hit target without changing body padding.
        private var cachedChevronFont: Font = Font(Font.MONOSPACED, Font.BOLD, 16)
        private var cachedBodyFontMetrics: FontMetrics? = null
        private var cachedFooterFontMetrics: FontMetrics? = null
        private var cachedChevronFontMetrics: FontMetrics? = null

        // Chevron caches — chevronLayouts built alongside fonts; chevronHitRects mutated in
        // place every paint (no per-paint allocation).
        internal lateinit var chevronLayouts: Array<TextLayout>
        internal val chevronHitRects: Array<Rectangle> = Array(4) { Rectangle() }
        private var chevronGapPx: Int = 0
        private var chevronHitWidthPx: Int = 0

        override fun calcWidthInPixels(inlay: Inlay<*>): Int {
            val w = editor.contentComponent.width
            return if (w > 0) w else FALLBACK_WIDTH_PX
        }

        override fun calcHeightInPixels(inlay: Inlay<*>): Int {
            prepareLayout(calcWidthInPixels(inlay))
            return heightCache
        }

        override fun paint(inlay: Inlay<*>, gRaw: Graphics, target: Rectangle, attrs: TextAttributes) {
            val g = gRaw as Graphics2D
            prepareLayout(target.width)
            // Save AA hint and stroke so the inlay's settings don't leak to other editor paint
            // subroutines sharing the same Graphics2D.
            val priorAa: Any? = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
            val priorStroke: Stroke = g.stroke
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            try {
                paintBody(g, target)
                paintWedge(g, target)
                paintNarration(g, target)
                paintCounter(g, target)
                paintFooter(g, target)
            } finally {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, priorAa)
                g.stroke = priorStroke
            }
        }

        internal fun prepareLayout(width: Int) {
            ThreadingAssertions.assertEventDispatchThread()
            refreshFontCacheIfNeeded()
            if (width == laidOutWidth) return
            laidOutWidth = width
            // Recompute body sub-rect for this width.
            bodyLeftInsetPx = JBUIScale.scale(BODY_LEFT_INSET_DP)
            maxBodyWidthPx = JBUIScale.scale(MAX_BODY_WIDTH_DP)
            bodyPaddingPx = JBUIScale.scale(BODY_PADDING_DP)
            wedgeWidthPx = JBUIScale.scale(WEDGE_WIDTH_DP)
            wedgeHeightPx = JBUIScale.scale(WEDGE_HEIGHT_DP)
            bodyXOffsetPx = bodyLeftInsetPx     // target.x is added at paint time
            bodyWidthPx = (width - 2 * bodyLeftInsetPx)
                .coerceAtMost(maxBodyWidthPx)
                .coerceAtLeast(1)
            lines = if (narration.isNullOrEmpty()) emptyList()
                    else wrapNarration(narration, (bodyWidthPx - 2 * bodyPaddingPx).coerceAtLeast(1), cachedBodyFont)
            heightCache = computeHeight(lines)
        }

        private fun refreshFontCacheIfNeeded() {
            val size = editor.colorsScheme.editorFontSize
            if (size == cachedSchemeFontSize) return
            cachedSchemeFontSize = size
            cachedBodyFont = Font(Font.MONOSPACED, Font.PLAIN, size)
            cachedFooterFont = Font(Font.MONOSPACED, Font.ITALIC, size)
            // Chevron font: bold, scaled up from body font for visual affordance.
            val chevronSize = (size * CHEVRON_FONT_SCALE).toInt().coerceAtLeast(size + 2)
            cachedChevronFont = Font(Font.MONOSPACED, Font.BOLD, chevronSize)
            cachedBodyFontMetrics = editor.contentComponent.getFontMetrics(cachedBodyFont)
            cachedFooterFontMetrics = editor.contentComponent.getFontMetrics(cachedFooterFont)
            cachedChevronFontMetrics = editor.contentComponent.getFontMetrics(cachedChevronFont)
            // Force layout rebuild on the next prepareLayout call — `TextLayout`s baked from
            // the old font would otherwise keep their stale advances/heights.
            laidOutWidth = -1
            // Border stroke also recomputes here (cheap, scale-stable across font changes).
            borderStrokePx = JBUIScale.scale(BORDER_STROKE_DP)
            borderStroke = BasicStroke(borderStrokePx.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            // Chevron TextLayouts cached at the same cadence; one glyph each, shared FRC.
            chevronGapPx = JBUIScale.scale(CHEVRON_GAP_DP)
            chevronHitWidthPx = JBUIScale.scale(CHEVRON_HIT_WIDTH_DP)
            val frc = FontRenderContext(null, true, true)
            chevronLayouts = arrayOf(
                TextLayout(CHEVRON_FIRST, cachedChevronFont, frc),
                TextLayout(CHEVRON_PREV,  cachedChevronFont, frc),
                TextLayout(CHEVRON_NEXT,  cachedChevronFont, frc),
                TextLayout(CHEVRON_LAST,  cachedChevronFont, frc),
            )
        }

        private fun computeHeight(lines: List<TextLayout>): Int {
            val lineHeight = editor.lineHeight
            val narrationPx = lines.sumOf { (it.ascent + it.descent + it.leading).toInt() }
            // Chrome row must fit the tallest glyph in the row — chevron font is bigger than body.
            val chromeRowHeight = maxOf(lineHeight, chromeRowFontMetricsRowHeight())
            return bodyPaddingPx +
                narrationPx +
                (if (lines.isEmpty()) 0 else bodyPaddingPx) +
                chromeRowHeight +
                (if (footerStatus != null) lineHeight else 0) +
                wedgeHeightPx +
                bodyPaddingPx
        }

        // Chrome row's full vertical extent in pixels (chevrons are the tallest element).
        private fun chromeRowFontMetricsRowHeight(): Int {
            val chev = cachedChevronFontMetrics ?: editor.contentComponent.getFontMetrics(cachedChevronFont)
            return chev.ascent + chev.descent
        }

        private fun bodyForeground(): Color = editor.colorsScheme.defaultForeground
        private fun bodyBackground(): Color = editor.colorsScheme.defaultBackground

        // Y-math seams — used by paintWedge / paintFooter / chromeRowBaselineY (and tests).
        internal fun bodyBottomYFor(target: Rectangle): Int =
            target.y + target.height - 1 - wedgeHeightPx

        internal fun chromeRowBaselineY(target: Rectangle): Int {
            val chev = cachedChevronFontMetrics ?: editor.contentComponent.getFontMetrics(cachedChevronFont)
            // Use the deeper of body and chevron descent so the row's bottom always clears the
            // tallest glyph below the baseline.
            val maxDescent = maxOf(chromeRowFontMetrics().descent, chev.descent)
            return bodyBottomYFor(target) - bodyPaddingPx -
                (if (footerStatus != null) editor.lineHeight else 0) -
                maxDescent
        }

        // FontMetrics cached in refreshFontCacheIfNeeded; non-null after first prepareLayout.
        private fun chromeRowFontMetrics(): FontMetrics = cachedBodyFontMetrics
            ?: editor.contentComponent.getFontMetrics(cachedBodyFont)

        private fun footerFontMetrics(): FontMetrics = cachedFooterFontMetrics
            ?: editor.contentComponent.getFontMetrics(cachedFooterFont)

        private fun paintBody(g: Graphics2D, target: Rectangle) {
            val bodyAbsX = target.x + bodyXOffsetPx
            val bodyHeightPx = target.height - wedgeHeightPx
            g.color = bodyBackground()
            g.fillRect(bodyAbsX, target.y, bodyWidthPx, bodyHeightPx)
            g.stroke = borderStroke
            g.color = ACCENT_COLOR
            g.drawRect(bodyAbsX, target.y, bodyWidthPx - 1, bodyHeightPx - 1)
        }

        private fun paintWedge(g: Graphics2D, target: Rectangle) {
            // Wedge sits below the body, pointing DOWN at the highlighted line.
            // Wedge X aligns under `startColPx`, clamped to body bounds.
            val bodyAbsX = target.x + bodyXOffsetPx
            val ideal = startColPx - bodyAbsX - wedgeWidthPx / 2
            val wedgeLeftWithinBody = ideal.coerceIn(0, (bodyWidthPx - wedgeWidthPx).coerceAtLeast(0))
            val wedgeLeftAbs = bodyAbsX + wedgeLeftWithinBody
            val bodyBottomY = bodyBottomYFor(target)
            val tipY = target.y + target.height - 1
            // Fill the wedge interior with body bg so it visually merges with the body.
            wedgeXs[0] = wedgeLeftAbs;                     wedgeYs[0] = bodyBottomY
            wedgeXs[1] = wedgeLeftAbs + wedgeWidthPx;      wedgeYs[1] = bodyBottomY
            wedgeXs[2] = wedgeLeftAbs + wedgeWidthPx / 2;  wedgeYs[2] = tipY
            g.color = bodyBackground()
            g.fillPolygon(wedgeXs, wedgeYs, 3)
            // Overpaint a `borderStrokePx`-thick band of the body's bottom border under the wedge
            // base so the body's stroke "opens" exactly where the wedge attaches.
            g.fillRect(wedgeLeftAbs, bodyBottomY - borderStrokePx + 1, wedgeWidthPx, borderStrokePx)
            // Stroke the two sloped sides only — never `drawPolygon` (would re-stroke the base).
            g.stroke = borderStroke
            g.color = ACCENT_COLOR
            g.drawLine(wedgeLeftAbs, bodyBottomY, wedgeLeftAbs + wedgeWidthPx / 2, tipY)
            g.drawLine(wedgeLeftAbs + wedgeWidthPx / 2, tipY, wedgeLeftAbs + wedgeWidthPx, bodyBottomY)
        }

        private fun paintNarration(g: Graphics2D, target: Rectangle) {
            if (lines.isEmpty()) return
            val bodyAbsX = target.x + bodyXOffsetPx
            g.color = bodyForeground()
            var y = target.y + bodyPaddingPx
            for (layout in lines) {
                y += layout.ascent.toInt()
                layout.draw(g, (bodyAbsX + bodyPaddingPx).toFloat(), y.toFloat())
                y += (layout.descent + layout.leading).toInt()
            }
        }

        private fun paintCounter(g: Graphics2D, target: Rectangle) {
            val bodyAbsX = target.x + bodyXOffsetPx
            val baselineY = chromeRowBaselineY(target)
            val fg = bodyForeground()
            val dim = dimmedForeground()
            // Render chevrons left-to-right at the chevron font; populate hit rects.
            // Hit rect width = max(chevronHitWidthPx, advance) so wider glyphs are not clipped.
            g.font = cachedChevronFont
            var x = bodyAbsX + bodyPaddingPx
            for (i in NavButton.entries.indices) {
                val layout = chevronLayouts[i]
                g.color = if (navEnabled[i]) fg else dim
                layout.draw(g, x.toFloat(), baselineY.toFloat())
                val advance = layout.advance.toInt()
                val hitWidth = maxOf(chevronHitWidthPx, advance)
                // Hit rects are stored in inlay-local coordinates (caller passes
                // mx - bounds.x, my - bounds.y). Subtract target.x/target.y so the rects
                // remain valid wherever the platform places this block inlay.
                chevronHitRects[i].setBounds(
                    x - target.x - (hitWidth - advance) / 2,
                    baselineY - target.y - layout.ascent.toInt(),
                    hitWidth,
                    (layout.ascent + layout.descent).toInt(),
                )
                x += advance + chevronGapPx
            }
            // Counter glyph at body font, after the last chevron.
            g.font = cachedBodyFont
            g.color = fg
            g.drawString(stepCounter, x + chevronGapPx, baselineY)
        }

        // Recomputed every paint so disabled-chevron color tracks LAF changes (JBColor.red/g/b
        // snapshots the active variant; caching would freeze it across theme switches).
        private fun dimmedForeground(): Color {
            val fg = bodyForeground()
            return Color(fg.red, fg.green, fg.blue, 128)
        }

        /**
         * Hit-test a click in inlay-local coords against the chevron rects. Returns the matching
         * callback iff that chevron's [navEnabled] is true; otherwise returns null. Walks
         * `NavButton.entries.indices` (no magic indices).
         */
        internal fun routeClick(localX: Int, localY: Int): (() -> Unit)? {
            for (i in NavButton.entries.indices) {
                if (navEnabled[i] && chevronHitRects[i].contains(localX, localY)) {
                    return navCallbacks[i]
                }
            }
            return null
        }

        private fun paintFooter(g: Graphics2D, target: Rectangle) {
            val status = footerStatus ?: return
            val bodyAbsX = target.x + bodyXOffsetPx
            g.font = cachedFooterFont
            g.color = bodyForeground()
            // Footer uses its own FontMetrics so descent matches the italic font that paints,
            // even if italic descent diverges slightly from plain at the same size.
            val y = bodyBottomYFor(target) - bodyPaddingPx - footerFontMetrics().descent
            g.drawString(status, bodyAbsX + bodyPaddingPx, y)
        }
    }

    companion object {
        internal const val MAX_BODY_WIDTH_DP: Int = 420
        internal const val BODY_LEFT_INSET_DP: Int = 8
        internal const val BODY_PADDING_DP: Int = 8
        internal const val WEDGE_WIDTH_DP: Int = 14
        internal const val WEDGE_HEIGHT_DP: Int = 8
        internal const val BORDER_STROKE_DP: Int = 1
        internal const val CHEVRON_GAP_DP: Int = 6
        internal const val CHEVRON_HIT_WIDTH_DP: Int = 28
        // Chevron font is scaled up from the editor body font for visual affordance + bigger hit
        // target. 2× of body size.
        internal const val CHEVRON_FONT_SCALE: Float = 2.0f
        internal const val FALLBACK_WIDTH_PX: Int = 600
        internal const val CHEVRON_FIRST: String = "«"  // «
        internal const val CHEVRON_PREV:  String = "‹"  // ‹
        internal const val CHEVRON_NEXT:  String = "›"  // ›
        internal const val CHEVRON_LAST:  String = "»"  // »
        // Saturated blue accent — stable across light/dark themes; allocated once at class load.
        private val ACCENT_COLOR: com.intellij.ui.JBColor =
            com.intellij.ui.JBColor(Color(0x3592C4), Color(0x4FA3D9))
    }
}

/**
 * Pure narration wrapping — normalize line terminators (CRLF → LF, bare CR → LF), short-circuit
 * on blank input, then split on blank lines and wrap each paragraph within `innerWidth`.
 */
internal fun wrapNarration(text: String, innerWidth: Int, font: Font): List<TextLayout> {
    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    if (normalized.isBlank()) return emptyList()
    val frc = FontRenderContext(null, true, true)
    val out = mutableListOf<TextLayout>()
    for (rawParagraph in normalized.split(Regex("\n\\s*\n"))) {
        if (rawParagraph.isEmpty()) {
            out += TextLayout(" ", font, frc)
            continue
        }
        // Parser preserves agent-side XML indentation; flatten it here. Trim each line so
        // ragged indentation across lines is normalized, and join with single spaces so
        // soft-wrapped paragraphs flow as one (LineBreakMeasurer doesn't honor `\n` as a
        // break point, so leaving them in produces literal control-glyph artifacts).
        val paragraph = rawParagraph
            .lines()
            .joinToString(" ") { it.trim() }
            .trim()
        if (paragraph.isEmpty()) continue
        val attributed = AttributedString(paragraph).apply {
            addAttribute(TextAttribute.FONT, font)
        }
        val measurer = LineBreakMeasurer(attributed.iterator, frc)
        while (measurer.position < paragraph.length) {
            out += measurer.nextLayout(innerWidth.toFloat())
        }
    }
    return out
}
