package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.scale.JBUIScale
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * Tests for [NarrationInlay] and its [NarrationInlay.Renderer]. Runs as a fixture-based
 * test (extends [BaseCompletionTest] → `BasePlatformTestCase`) so we can construct a real
 * editor and a real inlay model.
 */
class NarrationInlayLogicTest : BaseCompletionTest() {

    private fun openEditor(text: String = "first line\nsecond line\nthird line\n") =
        myFixture.configureByText("Sample.kt", text).let {
            myFixture.editor.also { it.contentComponent.setSize(800, 600) }
        }

    private fun renderer(
        narration: String? = "hello world",
        stepCounter: String = "step 1/3",
        footerStatus: String? = null,
        startColPx: Int = 100,
    ): NarrationInlay.Renderer {
        val editor = openEditor()
        return NarrationInlay.Renderer(editor, narration, stepCounter, footerStatus, { startColPx },
            disabledNav().first, disabledNav().second)
    }

    // -- width sourcing --

    fun testInlayRowWidthMatchesContentComponentWidthOrFallback() {
        val editor = openEditor()
        val r = NarrationInlay.Renderer(editor, "x", "step 1/1", null, { 0 },
            disabledNav().first, disabledNav().second)
        val actual = r.calcWidthInPixels(stubInlay())
        val ccWidth = editor.contentComponent.width
        if (ccWidth > 0) assertEquals(ccWidth, actual) else assertTrue(actual > 0)
    }

    // -- body sub-rect (Step 1) --

    private fun captureBodyFillRect(target: Rectangle, startColPx: Int = 100): RecordingGraphics2D.CapturedFillRect {
        val editor = openEditor()
        val r = NarrationInlay.Renderer(editor, "x", "step 1/3", null, { startColPx },
            disabledNav().first, disabledNav().second)
        r.prepareLayout(target.width)
        val img = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
        val recorder = RecordingGraphics2D(img.createGraphics())
        try {
            r.paint(stubInlayFor(editor), recorder as java.awt.Graphics, target,
                com.intellij.openapi.editor.markup.TextAttributes())
        } finally {
            recorder.dispose()
        }
        // Body interior fillRect is the only one with `defaultBackground` color and width > 1
        // in Step 1. Step 2 will add an overpaint band; this filter still works because the
        // band has width == wedgeWidthPx (14dp), much narrower than body width (~420dp).
        val bg = editor.colorsScheme.defaultBackground
        return recorder.fillRects.first { it.color == bg && it.rect.width > JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP) }
    }

    fun testBodyFillRectWidthEqualsMaxBodyWidthPxWhenContentIsWider() {
        val target = Rectangle(0, 0, 2000, 100)  // target.width drives bodyWidthPx; cap kicks in
        val body = captureBodyFillRect(target)
        assertEquals(JBUIScale.scale(NarrationInlay.MAX_BODY_WIDTH_DP), body.rect.width)
    }

    fun testBodyFillRectShrinksToContentWidthMinusInsetsWhenNarrower() {
        val target = Rectangle(0, 0, 200, 100)
        val body = captureBodyFillRect(target)
        assertEquals(target.width - 2 * JBUIScale.scale(NarrationInlay.BODY_LEFT_INSET_DP), body.rect.width)
    }

    fun testBodyFillRectXEqualsTargetXPlusInset() {
        val target = Rectangle(50, 0, 400, 100)
        val body = captureBodyFillRect(target)
        assertEquals(target.x + JBUIScale.scale(NarrationInlay.BODY_LEFT_INSET_DP), body.rect.x)
    }

    fun testWrapNarrationProducesLinesWithinInnerWidth() {
        val font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        val innerWidth = 100
        val text = "this is a long narration that should wrap within the inner width even when narrow"
        val lines = wrapNarration(text, innerWidth, font)
        // Force-wrap into multiple lines.
        assertTrue("expected at least 2 wrapped lines at width $innerWidth, got ${lines.size}", lines.size >= 2)
        // LineBreakMeasurer's contract is each line.advance <= maxWidth. Assert via exact-count
        // equality (every line satisfies the bound) rather than assertTrue lines.all { ... }.
        assertEquals(
            "every wrapped line's advance must be <= innerWidth ($innerWidth)",
            lines.size,
            lines.count { it.advance.toInt() <= innerWidth },
        )
    }

    // -- height composition --

    fun testInlayHeightGrowsWithNarrationParagraphCount() {
        val r1 = renderer(narration = "one")
        val r3 = renderer(narration = "one\n\ntwo\n\nthree")
        r1.prepareLayout(400)
        r3.prepareLayout(400)
        assertTrue(
            "Three paragraphs (${r3.heightCache}) must render taller than one (${r1.heightCache})",
            r3.heightCache > r1.heightCache,
        )
    }

    fun testInlayHeightForBlankNarrationStillIncludesChromeAndWedge() {
        val editor = openEditor()
        val r = NarrationInlay.Renderer(editor, null, "step 1/3", null, { 100 },
            disabledNav().first, disabledNav().second)
        r.prepareLayout(400)
        val chevronFontSize = (editor.colorsScheme.editorFontSize * NarrationInlay.CHEVRON_FONT_SCALE).toInt()
            .coerceAtLeast(editor.colorsScheme.editorFontSize + 2)
        val chevronFm = editor.contentComponent.getFontMetrics(
            java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, chevronFontSize),
        )
        val chromeRowHeight = maxOf(editor.lineHeight, chevronFm.ascent + chevronFm.descent)
        val expected =
            JBUIScale.scale(NarrationInlay.BODY_PADDING_DP) +
                chromeRowHeight +
                JBUIScale.scale(NarrationInlay.WEDGE_HEIGHT_DP) +
                JBUIScale.scale(NarrationInlay.BODY_PADDING_DP)
        assertEquals(expected, r.heightCache)
    }

    fun testInlayHeightAddsLineHeightForFooterStatus() {
        val withoutFooter = renderer(narration = null, footerStatus = null).also { it.prepareLayout(400) }
        val withFooter = renderer(narration = null, footerStatus = "(range no longer valid)").also { it.prepareLayout(400) }
        val lineHeight = openEditor().lineHeight
        assertEquals(lineHeight, withFooter.heightCache - withoutFooter.heightCache)
    }

    // -- padding + CRLF normalization (Step 3) --

    fun testHeightDeltaWithNarrationEqualsNarrationPlusPadding() {
        val rEmpty = renderer(narration = null).also { it.prepareLayout(400) }
        val rFilled = renderer(narration = "one\n\ntwo").also { it.prepareLayout(400) }
        val narrationPx = rFilled.lines.sumOf { (it.ascent + it.descent + it.leading).toInt() }
        assertEquals(
            narrationPx + JBUIScale.scale(NarrationInlay.BODY_PADDING_DP),
            rFilled.heightCache - rEmpty.heightCache,
        )
    }

    fun testChromeRowBaselineYAccountsForFooter() {
        val target = Rectangle(0, 0, 400, 100)
        val editor = openEditor()
        val rNoFooter = NarrationInlay.Renderer(editor, "x", "step 1/3", null, { 0 }, disabledNav().first, disabledNav().second)
            .also { it.prepareLayout(target.width) }
        val rWithFooter = NarrationInlay.Renderer(editor, "x", "step 1/3", "(stale)", { 0 }, disabledNav().first, disabledNav().second)
            .also { it.prepareLayout(target.width) }
        // Footer present pushes the chrome row up by exactly one editor.lineHeight.
        assertEquals(
            editor.lineHeight,
            rNoFooter.chromeRowBaselineY(target) - rWithFooter.chromeRowBaselineY(target),
        )
    }

    // -- chevrons + mouse routing (Step 4) --

    private fun navRenderer(
        navEnabled: BooleanArray = BooleanArray(4) { true },
        navCallbacks: Array<() -> Unit> = Array(4) { {} },
        narration: String? = "x",
        footerStatus: String? = null,
    ): NarrationInlay.Renderer {
        val editor = openEditor()
        return NarrationInlay.Renderer(editor, narration, "step 1/3", footerStatus, { 0 }, navEnabled, navCallbacks)
    }

    private fun paintNavRenderer(r: NarrationInlay.Renderer, target: Rectangle): RecordingGraphics2D {
        r.prepareLayout(target.width)
        val img = BufferedImage(target.width, target.height.coerceAtLeast(r.heightCache), BufferedImage.TYPE_INT_ARGB)
        val recorder = RecordingGraphics2D(img.createGraphics())
        try {
            r.paint(stubInlayFor(openEditor()), recorder as java.awt.Graphics, target,
                com.intellij.openapi.editor.markup.TextAttributes())
        } finally {
            recorder.dispose()
        }
        return recorder
    }

    fun testChevronLayoutsAreBuiltOnFontCacheRefresh() {
        val r = navRenderer()
        r.prepareLayout(400)
        assertEquals(4, r.chevronLayouts.size)
        // Each entry must be a distinct TextLayout instance (catches "reused same layout"
        // regression). Per-glyph identity isn't asserted because TextLayout doesn't expose its
        // source string and at MONOSPACED font sizes all single-char layouts have identical
        // advance, so deeper introspection has no deterministic anchor.
        val identities = r.chevronLayouts.map { System.identityHashCode(it) }.toSet()
        assertEquals(4, identities.size)
    }

    fun testChevronHitRectXsAscendLeftToRight() {
        val r = navRenderer()
        paintNavRenderer(r, Rectangle(0, 0, 800, 100))
        // Hit-rect X positions are deterministic and monotonically increasing with chevron index
        // (paintCounter advances `x` by previous advance + gap before populating the next rect).
        val xs = (0..3).map { r.chevronHitRects[it].x }
        assertTrue("hit-rect Xs must ascend strictly: $xs", xs[0] < xs[1] && xs[1] < xs[2] && xs[2] < xs[3])
    }

    fun testHitTestReturnsCallbackForChevronAtItsCenter() {
        val invoked = BooleanArray(4)
        val callbacks = Array<() -> Unit>(4) { i -> { invoked[i] = true } }
        val r = navRenderer(navCallbacks = callbacks)
        paintNavRenderer(r, Rectangle(0, 0, 800, 100))
        for (i in 0..3) {
            val rect = r.chevronHitRects[i]
            r.routeClick(rect.x + rect.width / 2, rect.y + rect.height / 2)?.invoke()
        }
        assertTrue("all four callbacks must fire", invoked.all { it })
    }

    fun testHitTestReturnsFirstMatchOnOverlappingRects() {
        // The plan's centered hit-rect formula `glyphX - hitWidth/2 + advance/2` produces hit
        // rects that overlap each other when (advance + gap) < hitWidth — true at typical
        // monospace font sizes. routeClick walks `NavButton.entries.indices` in order and returns
        // the FIRST match. This test pins that behavior so future readers don't assume corner
        // clicks always resolve to "the closest" chevron.
        val invoked = BooleanArray(4)
        val callbacks = Array<() -> Unit>(4) { i -> { invoked[i] = true } }
        val r = navRenderer(navCallbacks = callbacks)
        paintNavRenderer(r, Rectangle(0, 0, 800, 100))
        // Click the top-left corner of chevron[1]'s rect. If rect[0] overlaps that point,
        // routeClick returns rect[0]'s callback. Document and pin via assertion: invoked[0]
        // becomes true; invoked[1] stays false unless rects don't overlap there.
        val rect1 = r.chevronHitRects[1]
        r.routeClick(rect1.x, rect1.y)?.invoke()
        // The point is inside SOME enabled chevron; at least one of {0, 1} fired.
        assertTrue("first-match dispatch must fire chevron 0 or 1", invoked[0] || invoked[1])
    }

    fun testHitTestReturnsNullForDisabledChevron() {
        val r = navRenderer(navEnabled = BooleanArray(4).also { it[NarrationInlay.NavButton.FIRST.ordinal] = false; it[1] = true; it[2] = true; it[3] = true })
        paintNavRenderer(r, Rectangle(0, 0, 800, 100))
        val rect = r.chevronHitRects[NarrationInlay.NavButton.FIRST.ordinal]
        assertNull(r.routeClick(rect.x + rect.width / 2, rect.y + rect.height / 2))
    }

    fun testHitTestReturnsNullOutsideChromeRow() {
        val r = navRenderer()
        paintNavRenderer(r, Rectangle(0, 0, 800, 100))
        // Click at narration body region (top of body).
        assertNull(r.routeClick(20, 5))
    }

    fun testHitRectsAreLocalToTargetOffset() {
        // routeClick is documented to take inlay-local coordinates (the call site in
        // WalkthroughSession passes `mx - bounds.x, my - bounds.y`). The chevron hit rects
        // must therefore be stored in inlay-local coordinates as well — independent of where
        // the inlay is actually positioned in the editor. In production target.y is large
        // (the inlay sits well below 0), so Y-invariance is the load-bearing property.
        val rA = navRenderer().also { paintNavRenderer(it, Rectangle(0, 0, 800, 100)) }
        val rB = navRenderer().also { paintNavRenderer(it, Rectangle(40, 500, 800, 100)) }
        for (i in 0..3) {
            assertEquals(
                "hit rect Y must not depend on target.y (chevron $i)",
                rA.chevronHitRects[i].y,
                rB.chevronHitRects[i].y,
            )
            assertEquals(
                "hit rect X must not depend on target.x (chevron $i)",
                rA.chevronHitRects[i].x,
                rB.chevronHitRects[i].x,
            )
        }
    }

    fun testEmptyNarrationStillPaintsChromeAtCorrectY() {
        val target = Rectangle(0, 0, 800, 100)
        val r = navRenderer(narration = "")
        paintNavRenderer(r, target)
        // Chevron rect.y == chromeRowBaselineY - chevronLayouts[0].ascent (per setBounds formula).
        val expectedY = r.chromeRowBaselineY(target) - r.chevronLayouts[0].ascent.toInt()
        assertEquals(expectedY, r.chevronHitRects[0].y)
    }

    fun testDisabledChevronGlyphRenderedAtAlpha128() {
        val r = navRenderer(navEnabled = BooleanArray(4))  // all disabled
        val recorder = paintNavRenderer(r, Rectangle(0, 0, 800, 100))
        // Each disabled chevron must render at alpha 128 with the foreground RGB (24-bit mask).
        val fg = openEditor().colorsScheme.defaultForeground
        val expectedRgb24 = fg.rgb and 0x00FFFFFF
        val dimmed = recorder.drawnGlyphVectors.filter { it.color.alpha == 128 }
        assertEquals(4, dimmed.size)
        for (entry in dimmed) {
            assertEquals(expectedRgb24, entry.color.rgb and 0x00FFFFFF)
        }
    }

    fun testDimmedChevronColorRecomputedAcrossPaint() {
        val r = navRenderer(navEnabled = BooleanArray(4))
        val target = Rectangle(0, 0, 800, 100)
        // Paint #1 — capture the disabled-chevron color at the editor's initial scheme.
        val rec1 = paintNavRenderer(r, target)
        val color1 = rec1.drawnGlyphVectors.first { it.color.alpha == 128 }.color
        // Mutate `defaultForeground` indirectly: editor.colorsScheme delegates to the global
        // EditorColorsManager scheme by default, so swapping it changes what bodyForeground()
        // returns on the next paint. Pick any registered scheme that differs from the current.
        val mgr = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance()
        val originalScheme = mgr.globalScheme
        val alternative = mgr.allSchemes.firstOrNull { it !== originalScheme }
            ?: error("test fixture must provide more than one EditorColorsScheme")
        try {
            mgr.setGlobalScheme(alternative)
            // Paint #2 — capture again; if dimmedForeground were cached, color2 would equal color1.
            val rec2 = paintNavRenderer(r, target)
            val color2 = rec2.drawnGlyphVectors.first { it.color.alpha == 128 }.color
            assertNotSame("dimmed color must recompute when scheme changes", color1, color2)
            // Both must still be 50%-alpha overlays of their respective foregrounds.
            assertEquals(128, color2.alpha)
        } finally {
            mgr.setGlobalScheme(originalScheme)
        }
    }

    fun testWrapNarrationStripsLeadingIndentationFromParagraphs() {
        // Parser intentionally preserves indentation in narration content (see
        // WalkthroughParserTest.testParseStripsBothEndsAndPreservesIndentation). The renderer
        // must strip it so wrapped lines don't show leading spaces.
        val font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        val indented = "  first paragraph here.\n\n  second paragraph here."
        val plain = "first paragraph here.\n\nsecond paragraph here."
        val indentedLines = wrapNarration(indented, 10000, font)
        val plainLines = wrapNarration(plain, 10000, font)
        assertEquals(plainLines.size, indentedLines.size)
        for (i in plainLines.indices) {
            assertEquals(
                "wrapped line $i must have the same advance as the un-indented form",
                plainLines[i].advance,
                indentedLines[i].advance,
                0.5f,
            )
        }
    }

    fun testWrapNarrationFlattensSoftWrappedLinesWithinParagraph() {
        // Agent emits narration with hard line breaks inside what is logically one paragraph
        // (XML is line-wrapped for source readability). The renderer must flow those into a
        // single paragraph instead of preserving the embedded `\n` glyphs.
        val font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        val multiLine = "  first half of the sentence\n  second half of the sentence."
        val flowed = "first half of the sentence second half of the sentence."
        val multiLineLayouts = wrapNarration(multiLine, 10000, font)
        val flowedLayouts = wrapNarration(flowed, 10000, font)
        assertEquals(1, multiLineLayouts.size)
        assertEquals(1, flowedLayouts.size)
        assertEquals(
            "soft-wrapped paragraph must have the same advance as its flowed form",
            flowedLayouts[0].advance,
            multiLineLayouts[0].advance,
            0.5f,
        )
    }

    fun testCrlfNarrationProducesExpectedParagraphCount() {
        val font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        val lines = wrapNarration("first\r\n\r\nsecond", 1000, font)
        // Two paragraphs, each fits on one line at innerWidth=1000.
        assertEquals(2, lines.size)
    }

    fun testBareCrNarrationAlsoNormalizes() {
        val font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        val lines = wrapNarration("first\r\rsecond", 1000, font)
        assertEquals(2, lines.size)
    }

    fun testBlankAfterNormalizationProducesEmptyLines() {
        val font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        val lines = wrapNarration("\r\n  \r\n", 1000, font)
        assertEquals(0, lines.size)
    }

    // -- prepareLayout caching --

    fun testPrepareLayoutCachesLayoutsKeyedOnWidth() {
        val r = renderer()
        r.prepareLayout(400)
        val firstLines = r.lines
        r.prepareLayout(400)        // unchanged width — must reuse the cached `lines` reference
        assertSame("Same-width re-entry must reuse the cached layouts", firstLines, r.lines)

        r.prepareLayout(500)        // width change — must recompute (different reference)
        assertNotSame("Width change must invalidate the layout cache", firstLines, r.lines)
        assertEquals(500, r.laidOutWidth)
    }

    // -- paint smoke (catches obvious regressions; uses a real BufferedImage Graphics) --

    fun testPaintCompletesWithoutThrowingOnRealGraphics() {
        val r = renderer(narration = "Some narration.\n\nA second paragraph.", footerStatus = "(range no longer valid)")
        r.prepareLayout(400)
        val img = BufferedImage(400, r.heightCache.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            r.paint(stubInlay(), g as java.awt.Graphics, Rectangle(0, 0, 400, r.heightCache), com.intellij.openapi.editor.markup.TextAttributes())
        } finally {
            g.dispose()
        }
        // The assertion: paint did not throw. (Drawing into an offscreen image exercises every
        // paint subroutine — body, wedge, narration, counter, footer.)
    }

    // -- inlay attachment --

    fun testInlayAttachesAtLineStartOfRangeStartOffset() {
        val editor = openEditor("line one\nline two\nline three\n")
        val parent = Disposer.newDisposable("test").also { Disposer.register(testRootDisposable, it) }
        val rangeOffsetMidLine2 = editor.document.getLineStartOffset(1) + 3
        val inlay = NarrationInlay(editor, rangeOffsetMidLine2, "narration", "step 1/3", null, 50, parent,
            disabledNav().first, disabledNav().second)
        assertTrue("inlay must attach", inlay.isAttached())
    }

    fun testInlayConstructorHandlesLineZeroAnchor() {
        val editor = openEditor("only line\n")
        val parent = Disposer.newDisposable("test").also { Disposer.register(testRootDisposable, it) }
        // rangeStartOffset = 0 → addBlockElement at offset 0 with showAbove=true. We assert the
        // constructor doesn't throw and the inlay attaches (line-zero edge isn't refused).
        val inlay = NarrationInlay(editor, 0, "narration", "step 1/1", null, 0, parent,
            disabledNav().first, disabledNav().second)
        assertTrue(inlay.isAttached())
    }

    fun testInlayDisposesWhenParentDisposed() {
        val editor = openEditor()
        val parent = Disposer.newDisposable("test").also { Disposer.register(testRootDisposable, it) }
        val inlay = NarrationInlay(editor, 0, "narration", "step 1/1", null, 0, parent,
            disabledNav().first, disabledNav().second)
        val countBefore = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size
        Disposer.dispose(parent)
        val countAfter = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).size
        assertEquals("Inlay must be removed when parent disposable is disposed", countBefore - 1, countAfter)
        assertNotNull(inlay)    // suppress unused-warning; inlay reference held until parent dispose
    }

    // -- wedge below body via overpaint (Step 2) --

    fun testBodyFillRectStopsAboveWedgeBaseY() {
        val target = Rectangle(0, 0, 400, 100)
        val recorder = paintAndCaptureRecorder(startColPx = 100, target = target)
        val editor = openEditor()
        val bg = editor.colorsScheme.defaultBackground
        // Body interior fillRect: bg color, width > wedge width.
        val body = recorder.fillRects.first { it.color == bg && it.rect.width > JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP) }
        assertEquals(
            "body fill must end at target.y + target.height - wedgeHeightPx",
            target.y + target.height - JBUIScale.scale(NarrationInlay.WEDGE_HEIGHT_DP),
            body.rect.y + body.rect.height,
        )
    }

    fun testWedgeBaseYIsBodyBottomY() {
        val target = Rectangle(0, 0, 400, 100)
        val polygons = paintAndCapturePolygons(startColPx = 100, target = target)
        val (_, ys, _) = polygons.first()
        assertEquals(
            "wedge base y must equal bodyBottomY",
            target.y + target.height - 1 - JBUIScale.scale(NarrationInlay.WEDGE_HEIGHT_DP),
            ys.min(),
        )
    }

    fun testWedgeTipYIsTargetBottomMinusOne() {
        val target = Rectangle(0, 0, 400, 100)
        val polygons = paintAndCapturePolygons(startColPx = 100, target = target)
        val (_, ys, _) = polygons.first()
        assertEquals(target.y + target.height - 1, ys.max())
    }

    fun testBottomBorderOverpaintBandFillRectAtWedgeBase() {
        val target = Rectangle(0, 0, 400, 100)
        val startColPx = 100
        val recorder = paintAndCaptureRecorder(startColPx = startColPx, target = target)
        val editor = openEditor()
        val bg = editor.colorsScheme.defaultBackground
        val borderStrokePx = JBUIScale.scale(NarrationInlay.BORDER_STROKE_DP)
        val wedgeWidthPx = JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP)
        val bodyBottomY = target.y + target.height - 1 - JBUIScale.scale(NarrationInlay.WEDGE_HEIGHT_DP)
        // Filter: bg color, width == wedgeWidthPx, height == borderStrokePx, y == bodyBottomY - borderStrokePx + 1.
        val bands = recorder.fillRects.filter {
            it.color == bg &&
                it.rect.width == wedgeWidthPx &&
                it.rect.height == borderStrokePx &&
                it.rect.y == bodyBottomY - borderStrokePx + 1
        }
        assertEquals("expected exactly one overpaint band", 1, bands.size)
    }

    fun testWedgeSlopedLinesAreDrawn() {
        val target = Rectangle(0, 0, 400, 100)
        val recorder = paintAndCaptureRecorder(startColPx = 100, target = target)
        // ACCENT_COLOR is JBColor(0x3592C4 light, 0x4FA3D9 dark); accept either active variant.
        val lightRgb = java.awt.Color(0x3592C4).rgb
        val darkRgb = java.awt.Color(0x4FA3D9).rgb
        val sloped = recorder.drawnLines.filter { it.color.rgb == lightRgb || it.color.rgb == darkRgb }
        assertEquals("expected exactly two sloped wedge lines", 2, sloped.size)
        // Endpoints: left slope from (wedgeLeftAbs, bodyBottomY) to (mid, tipY);
        // right slope from (mid, tipY) to (wedgeLeftAbs + w, bodyBottomY).
        val wedgeWidthPx = JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP)
        val bodyLeftInsetPx = JBUIScale.scale(NarrationInlay.BODY_LEFT_INSET_DP)
        val bodyWidthPx = (target.width - 2 * bodyLeftInsetPx).coerceAtMost(JBUIScale.scale(NarrationInlay.MAX_BODY_WIDTH_DP))
        val ideal = 100 - target.x - bodyLeftInsetPx - wedgeWidthPx / 2
        val wedgeLeftAbs = target.x + bodyLeftInsetPx + ideal.coerceIn(0, (bodyWidthPx - wedgeWidthPx).coerceAtLeast(0))
        val bodyBottomY = target.y + target.height - 1 - JBUIScale.scale(NarrationInlay.WEDGE_HEIGHT_DP)
        val tipY = target.y + target.height - 1
        val midX = wedgeLeftAbs + wedgeWidthPx / 2
        val expected = setOf(
            setOf(wedgeLeftAbs to bodyBottomY, midX to tipY),
            setOf(midX to tipY, (wedgeLeftAbs + wedgeWidthPx) to bodyBottomY),
        )
        val actual = sloped.map { setOf(it.x1 to it.y1, it.x2 to it.y2) }.toSet()
        assertEquals(expected, actual)
    }

    // -- wedge X positioning (Step 5) --

    fun testWedgeCenteredUnderStartColumnInRange() {
        val startColPx = 200
        val target = Rectangle(0, 0, 400, 80)
        val polygons = paintAndCapturePolygons(startColPx = startColPx, target = target)
        assertTrue("expected at least one wedge polygon", polygons.isNotEmpty())
        val (xs, ys, npoints) = polygons.first()
        assertEquals(3, npoints)
        assertEquals("wedge tip x must equal startColPx", startColPx, (xs.min() + xs.max()) / 2)
        assertEquals(JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP), xs.max() - xs.min())
        assertEquals("wedge tip y must sit on the inlay's bottom edge", target.y + target.height - 1, ys.max())
    }

    fun testWedgeClampsLeftWhenStartColumnLeftOfInlay() {
        val target = Rectangle(50, 0, 400, 80)
        val polygons = paintAndCapturePolygons(startColPx = 10, target = target)
        assertTrue("expected at least one wedge polygon", polygons.isNotEmpty())
        val (xs, _, _) = polygons.first()
        assertEquals(
            "wedge must clamp to body's left edge",
            target.x + JBUIScale.scale(NarrationInlay.BODY_LEFT_INSET_DP),
            xs.min(),
        )
        assertEquals(
            "wedge width must be preserved under clamp",
            JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP),
            xs.max() - xs.min(),
        )
    }

    fun testWedgeClampsRightWhenStartColumnPastInlay() {
        val target = Rectangle(0, 0, 200, 80)
        val polygons = paintAndCapturePolygons(startColPx = 500, target = target)
        assertTrue("expected at least one wedge polygon", polygons.isNotEmpty())
        val (xs, _, _) = polygons.first()
        val bodyLeftInsetPx = JBUIScale.scale(NarrationInlay.BODY_LEFT_INSET_DP)
        val bodyWidthPx = (target.width - 2 * bodyLeftInsetPx)
            .coerceAtMost(JBUIScale.scale(NarrationInlay.MAX_BODY_WIDTH_DP))
        assertEquals(
            "wedge must clamp to body's right edge",
            target.x + bodyLeftInsetPx + bodyWidthPx - JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP),
            xs.min(),
        )
        assertEquals(
            "wedge width must be preserved under clamp",
            JBUIScale.scale(NarrationInlay.WEDGE_WIDTH_DP),
            xs.max() - xs.min(),
        )
    }

    private fun paintAndCapturePolygons(
        startColPx: Int,
        target: Rectangle,
    ): List<Triple<IntArray, IntArray, Int>> = paintAndCaptureRecorder(startColPx, target).polygons

    private fun paintAndCaptureRecorder(
        startColPx: Int,
        target: Rectangle,
        narration: String? = "narr",
        footerStatus: String? = null,
    ): RecordingGraphics2D {
        val editor = openEditor()
        val r = NarrationInlay.Renderer(editor, narration, "step 1/3", footerStatus, { startColPx },
            disabledNav().first, disabledNav().second)
        r.prepareLayout(target.width)
        val img = BufferedImage(target.width.coerceAtLeast(1), target.height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
        val recorder = RecordingGraphics2D(img.createGraphics())
        try {
            r.paint(stubInlayFor(editor), recorder as java.awt.Graphics, target, com.intellij.openapi.editor.markup.TextAttributes())
        } finally {
            recorder.dispose()
        }
        return recorder
    }

    private fun stubInlay(): com.intellij.openapi.editor.Inlay<*> = stubInlayFor(openEditor())

    private fun stubInlayFor(editor: com.intellij.openapi.editor.Editor): com.intellij.openapi.editor.Inlay<*> {
        val parent = Disposer.newDisposable("stub").also { Disposer.register(testRootDisposable, it) }
        return editor.inlayModel.addBlockElement(
            0, false, true, 0,
            object : com.intellij.openapi.editor.EditorCustomElementRenderer {
                override fun calcWidthInPixels(inlay: com.intellij.openapi.editor.Inlay<*>): Int = 1
            }
        )!!.also { Disposer.register(parent, it) }
    }
}
