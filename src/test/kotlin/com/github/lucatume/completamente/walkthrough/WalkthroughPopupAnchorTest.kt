package com.github.lucatume.completamente.walkthrough

import com.github.lucatume.completamente.BaseCompletionTest

/**
 * Pure tests for [WalkthroughPopup.chooseAnchorY] — the geometry helper that decides
 * whether the popup goes above or below the highlighted range. All values are in
 * editor-content coordinates (Y grows downward).
 */
class WalkthroughPopupAnchorTest : BaseCompletionTest() {

    fun testPlacesPopupAboveLineWhenRoomExists() {
        // Single-line range at viewport middle, popup small enough to fit above.
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 400,
            rangeEndY = 400,
            lineHeight = 16,
            popupHeight = 160,
            visibleTopY = 100,
            gap = 4
        )
        // above = 400 - 160 - 4 = 236; >= visibleTopY=100 → above wins.
        assertEquals(236, y)
    }

    fun testFlipsBelowWhenAboveOverflowsVisibleArea() {
        // Range near the top of the viewport — no room above for a tall popup.
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 110,
            rangeEndY = 110,
            lineHeight = 16,
            popupHeight = 200,
            visibleTopY = 100,
            gap = 4
        )
        // above = 110 - 200 - 4 = -94 < 100 → flip; below = 110 + 16 + 4 = 130
        assertEquals(130, y)
    }

    fun testAbovePreferredEvenWhenTight() {
        // above just fits exactly at visibleTopY.
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 264,
            rangeEndY = 264,
            lineHeight = 16,
            popupHeight = 160,
            visibleTopY = 100,
            gap = 4
        )
        // above = 264 - 160 - 4 = 100 == visibleTopY → still above
        assertEquals(100, y)
    }

    fun testGapAppliedAboveAndBelow() {
        val above = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 500, rangeEndY = 500, lineHeight = 16,
            popupHeight = 200, visibleTopY = 0, gap = 8
        )
        // 500 - 200 - 8 = 292
        assertEquals(292, above)

        val below = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 10, rangeEndY = 10, lineHeight = 16,
            popupHeight = 200, visibleTopY = 0, gap = 8
        )
        // above = 10 - 200 - 8 = -198 < 0 → flip; below = 10 + 16 + 8 = 34
        assertEquals(34, below)
    }

    fun testPopupTallerThanViewportFallsBackBelow() {
        // Popup taller than the visible area entirely — both placements imperfect, but the
        // helper consistently picks below (predictability matters more than perfection).
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 200,
            rangeEndY = 200,
            lineHeight = 16,
            popupHeight = 800,
            visibleTopY = 0,
            gap = 4
        )
        assertEquals(220, y) // below = 200 + 16 + 4
    }

    // -- multi-line ranges --

    fun testBelowFallbackUsesEndLineForMultiLineRange() {
        // Range spans 5 lines (start at y=100, end at y=164 with lineHeight=16). When forced
        // below, the popup must clear the *end* of the range, not the start.
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 100,
            rangeEndY = 164,
            lineHeight = 16,
            popupHeight = 200, // tall enough to force below
            visibleTopY = 50,
            gap = 4
        )
        // above = 100 - 200 - 4 = -104 < 50 → flip; below = 164 + 16 + 4 = 184
        assertEquals(184, y)
    }

    fun testAboveFallbackUsesStartLineForMultiLineRange() {
        // When above-the-range fits, we anchor relative to the start line — the popup floats
        // above the first line of the range.
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 500,
            rangeEndY = 564, // 5-line range
            lineHeight = 16,
            popupHeight = 160,
            visibleTopY = 0,
            gap = 4
        )
        // above = 500 - 160 - 4 = 336 (independent of rangeEndY)
        assertEquals(336, y)
    }

    // -- degenerate inputs --

    fun testNegativeRangeYFallsBack() {
        // Range scrolled above the viewport (rangeY < visibleTopY). above is always below
        // visibleTopY in that case → fallback below.
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = -50,
            rangeEndY = -50,
            lineHeight = 16,
            popupHeight = 100,
            visibleTopY = 0,
            gap = 4
        )
        // above = -50 - 100 - 4 = -154 < 0 → flip; below = -50 + 16 + 4 = -30
        assertEquals(-30, y)
    }

    fun testZeroPopupHeightCollapsesToGapOnly() {
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 200,
            rangeEndY = 200,
            lineHeight = 16,
            popupHeight = 0,
            visibleTopY = 0,
            gap = 4
        )
        // above = 200 - 0 - 4 = 196
        assertEquals(196, y)
    }

    fun testZeroGap() {
        val y = WalkthroughPopup.chooseAnchorY(
            rangeStartY = 200,
            rangeEndY = 200,
            lineHeight = 16,
            popupHeight = 100,
            visibleTopY = 0,
            gap = 0
        )
        // above = 200 - 100 - 0 = 100
        assertEquals(100, y)
    }
}
