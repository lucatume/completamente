package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.util.Disposer
import com.intellij.ui.scale.JBUIScale
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * Regression: the wedge under the narration inlay must point at the start of the visible
 * highlighted text, not at column 0 of the line. The walkthrough agent commonly emits
 * `range="L:1-..."` for indented code, which previously left the wedge stranded over the
 * gutter whitespace far to the left of the actual code the user is reading.
 *
 * Two tests:
 *  - [testComputeAnchorOffsetSkipsLeadingIndentationWithinRange] — pure-function pin,
 *    fails without `computeAnchorOffset` and trivially passes with it. Documents the
 *    contract WalkthroughSession depends on.
 *  - [testWedgeTipXAlignsWithFirstNonWhitespaceCharacterOnIndentedLine] — end-to-end
 *    pin through the renderer paint pass. Computes the live X via the production
 *    resolver and asserts the rendered wedge tip lands on it (not on column 0).
 */
class WedgeAnchoringTest : BaseCompletionTest() {

    fun testComputeAnchorOffsetSkipsLeadingIndentationWithinRange() {
        // Line content: "    const x = 1\n    const y = 2\n"
        //                ^0  ^4         ^15
        // Range covers the whole first line including its indentation (startCol=0).
        val text = "    const x = 1\n    const y = 2\n"
        val startOffset = 0           // line 0 col 0 — agent emitted "L:1-..."
        val endOffset = 15            // through the trailing '1', exclusive of '\n'
        val anchor = computeAnchorOffset(text, startOffset, endOffset)
        assertEquals(
            "anchor must skip leading whitespace and land on 'c' of \"const\"",
            4,
            anchor,
        )
    }

    fun testComputeAnchorOffsetReturnsStartOffsetWhenLineWithinRangeIsAllWhitespace() {
        // The range covers only the indentation (no non-whitespace within the range on this
        // line) — anchor falls back to startOffset rather than scanning past `endOffset`.
        val text = "    const x = 1\n"
        val anchor = computeAnchorOffset(text, 0, 3)
        assertEquals(0, anchor)
    }

    fun testComputeAnchorOffsetHonorsAnchorAlreadyOnNonWhitespace() {
        // Range starts inside the visible code (e.g. agent emitted exact column). The
        // function must return that offset unchanged — never re-walk back to whitespace.
        val text = "    const x = 1\n"
        val anchor = computeAnchorOffset(text, /* 'c' */ 4, 15)
        assertEquals(4, anchor)
    }

    fun testComputeAnchorOffsetDoesNotScanBeyondLineEnd() {
        // Line 0 is all whitespace; line 1 begins with 'c'. Range spans both lines.
        // Anchor must NOT cross the '\n' to grab a non-whitespace char from line 1; the
        // wedge is below the START line, so the anchor must be on the start line.
        val text = "    \n    const y = 2\n"
        val anchor = computeAnchorOffset(text, 0, /* deep into line 1 */ 18)
        // Whole start line is whitespace within the range → fall back to startOffset (0).
        assertEquals(0, anchor)
    }

    /**
     * End-to-end pin: with a blank-leading line and a wedge X resolver pointing at the
     * first non-whitespace character's X, the rendered wedge tip must land on that X
     * (not on column 0).
     */
    fun testWedgeTipXAlignsWithFirstNonWhitespaceCharacterOnIndentedLine() {
        val source = "    const manifest = {}\n"
        val editor = myFixture.configureByText("Sample.kt", source).let {
            myFixture.editor.also { it.contentComponent.setSize(800, 600) }
        }
        // Anchor offset: index of 'c' on line 0.
        val anchorOffset = source.indexOf('c')
        assertEquals("'c' must exist", 4, anchorOffset)

        val parent = Disposer.newDisposable("wedge-test").also { Disposer.register(testRootDisposable, it) }

        // Render via the production NarrationInlay constructor — that's the integration
        // surface that wires `anchorOffset` to the live resolver.
        val inlay = NarrationInlay(
            editor = editor,
            rangeStartOffset = 0,
            narration = "x",
            stepCounter = "step 1/1",
            footerStatus = null,
            anchorOffset = anchorOffset,
            parentDisposable = parent,
            navEnabled = disabledNav().first,
            navCallbacks = disabledNav().second,
        )
        assertTrue("inlay must attach for the test to be meaningful", inlay.isAttached())

        // Live X for the anchor — what the production paint path computes.
        val anchorX = resolveAnchorX(editor, anchorOffset)
        // For column-zero comparison: the X for the line-start offset.
        val columnZeroX = resolveAnchorX(editor, 0)
        assertTrue(
            "test invariant: anchor X must be strictly right of column-zero X " +
                "($anchorX vs $columnZeroX); otherwise the IDE isn't rendering the indent",
            anchorX > columnZeroX,
        )

        // Paint into a recorder and read the wedge polygon.
        val target = Rectangle(0, 0, 800, inlay.renderer.let {
            it.prepareLayout(800)
            it.heightCache.coerceAtLeast(40)
        })
        val img = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_ARGB)
        val recorder = RecordingGraphics2D(img.createGraphics())
        try {
            inlay.renderer.paint(
                requireNotNull(inlay.attachedInlay),
                recorder as java.awt.Graphics,
                target,
                com.intellij.openapi.editor.markup.TextAttributes(),
            )
        } finally {
            recorder.dispose()
        }
        val polygons = recorder.polygons
        assertTrue("expected at least one wedge polygon", polygons.isNotEmpty())
        val (xs, _, _) = polygons.first()
        val tipX = (xs.min() + xs.max()) / 2
        // The wedge clamps to the body's left edge if the anchor lies to the left of the
        // body. With a non-trivial indent and the standard 8dp inset, the anchor should
        // sit inside the body — assert the tip equals anchorX exactly.
        val bodyLeftInsetPx = JBUIScale.scale(NarrationInlay.BODY_LEFT_INSET_DP)
        if (anchorX >= bodyLeftInsetPx) {
            assertEquals(
                "wedge tip X must equal the live anchor X (first non-whitespace char), " +
                    "not column-zero X",
                anchorX,
                tipX,
            )
        }
        // And — load-bearing — the tip must NOT collapse to column-zero X.
        assertTrue(
            "wedge tip ($tipX) must not anchor at column-zero X ($columnZeroX) — that's the bug",
            tipX != columnZeroX,
        )
    }
}
