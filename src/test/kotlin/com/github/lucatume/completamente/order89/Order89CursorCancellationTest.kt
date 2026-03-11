package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.TextRange
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

class Order89CursorCancellationTest : BaseCompletionTest() {

    private val processes = mutableListOf<Process>()

    private fun makeTestSession(range: RangeMarker): Order89Session {
        val process = ProcessBuilder("sleep", "300").start()
        processes.add(process)
        val future = CompletableFuture<Order89Result>()
        return Order89Session(process, future, null, range)
    }

    override fun tearDown() {
        processes.forEach { it.destroyForcibly() }
        processes.clear()
        super.tearDown()
    }

    private fun findSessionAtCaret(
        doc: Document,
        caretLine: Int,
        sessions: ArrayDeque<Order89Session>
    ): Order89Session? {
        return sessions.firstOrNull { s ->
            s.range.isValid &&
                caretLine >= doc.getLineNumber(s.range.startOffset) &&
                caretLine <= doc.getLineNumber(s.range.endOffset)
        }
    }

    // RangeMarker basics.

    fun testRangeMarkerOffsetsMapToExpectedLines() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\nline4\n"
        )
        val doc = myFixture.editor.document
        val startOffset = doc.getLineStartOffset(1)
        val endOffset = doc.getLineEndOffset(3)
        val marker = doc.createRangeMarker(startOffset, endOffset)

        assertEquals(1, doc.getLineNumber(marker.startOffset))
        assertEquals(3, doc.getLineNumber(marker.endOffset))
        marker.dispose()
    }

    fun testRangeMarkerIsValidAfterCreationAndInvalidAfterDispose() {
        myFixture.configureByText("test.kt", "line0\nline1\n")
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(0, 5)

        assertTrue(marker.isValid)
        marker.dispose()
        assertFalse(marker.isValid)
    }

    // Cursor-to-range matching.

    fun testCursorOnFirstLineOfRangeMatches() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\n"
        )
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(
            doc.getLineStartOffset(1),
            doc.getLineEndOffset(2)
        )
        val sessions = ArrayDeque<Order89Session>()
        sessions.addLast(makeTestSession(marker))

        assertNotNull(findSessionAtCaret(doc, 1, sessions))
        marker.dispose()
    }

    fun testCursorOnLastLineOfRangeMatches() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\n"
        )
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(
            doc.getLineStartOffset(1),
            doc.getLineEndOffset(2)
        )
        val sessions = ArrayDeque<Order89Session>()
        sessions.addLast(makeTestSession(marker))

        assertNotNull(findSessionAtCaret(doc, 2, sessions))
        marker.dispose()
    }

    fun testCursorOneLineAboveRangeDoesNotMatch() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\n"
        )
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(
            doc.getLineStartOffset(2),
            doc.getLineEndOffset(3)
        )
        val sessions = ArrayDeque<Order89Session>()
        sessions.addLast(makeTestSession(marker))

        assertNull(findSessionAtCaret(doc, 1, sessions))
        marker.dispose()
    }

    fun testCursorOneLineBelowRangeDoesNotMatch() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\nline4\n"
        )
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(
            doc.getLineStartOffset(1),
            doc.getLineEndOffset(2)
        )
        val sessions = ArrayDeque<Order89Session>()
        sessions.addLast(makeTestSession(marker))

        assertNull(findSessionAtCaret(doc, 3, sessions))
        marker.dispose()
    }

    fun testTwoSessionsCursorOnSecondMatchesSecond() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\nline4\n"
        )
        val doc = myFixture.editor.document
        val firstMarker = doc.createRangeMarker(
            doc.getLineStartOffset(0),
            doc.getLineEndOffset(1)
        )
        val secondMarker = doc.createRangeMarker(
            doc.getLineStartOffset(3),
            doc.getLineEndOffset(4)
        )
        val sessions = ArrayDeque<Order89Session>()
        val firstSession = makeTestSession(firstMarker)
        val secondSession = makeTestSession(secondMarker)
        sessions.addLast(firstSession)
        sessions.addLast(secondSession)

        val match = findSessionAtCaret(doc, 3, sessions)
        assertNotNull(match)
        assertEquals(secondMarker, match!!.range)
        firstMarker.dispose()
        secondMarker.dispose()
    }

    fun testOverlappingSessionsCursorInOverlapMatchesFirst() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\nline4\n"
        )
        val doc = myFixture.editor.document
        val firstMarker = doc.createRangeMarker(
            doc.getLineStartOffset(1),
            doc.getLineEndOffset(3)
        )
        val secondMarker = doc.createRangeMarker(
            doc.getLineStartOffset(2),
            doc.getLineEndOffset(4)
        )
        val sessions = ArrayDeque<Order89Session>()
        val firstSession = makeTestSession(firstMarker)
        val secondSession = makeTestSession(secondMarker)
        sessions.addLast(firstSession)
        sessions.addLast(secondSession)

        val match = findSessionAtCaret(doc, 2, sessions)
        assertNotNull(match)
        assertEquals(firstMarker, match!!.range)
        firstMarker.dispose()
        secondMarker.dispose()
    }

    fun testDisposedSessionIsSkipped() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\n"
        )
        val doc = myFixture.editor.document
        val disposedMarker = doc.createRangeMarker(
            doc.getLineStartOffset(0),
            doc.getLineEndOffset(1)
        )
        val validMarker = doc.createRangeMarker(
            doc.getLineStartOffset(0),
            doc.getLineEndOffset(1)
        )
        disposedMarker.dispose()
        val sessions = ArrayDeque<Order89Session>()
        sessions.addLast(makeTestSession(disposedMarker))
        sessions.addLast(makeTestSession(validMarker))

        val match = findSessionAtCaret(doc, 0, sessions)
        assertNotNull(match)
        assertEquals(validMarker, match!!.range)
        validMarker.dispose()
    }

    fun testEmptySessionsListReturnsNull() {
        myFixture.configureByText("test.kt", "line0\nline1\n")
        val doc = myFixture.editor.document

        assertNull(findSessionAtCaret(doc, 0, ArrayDeque()))
    }

    // Zero-width range marker.

    fun testZeroWidthRangeMarkerMatchesCursorOnSameLine() {
        // Create a range marker where start == end (caret position, no selection).
        // Verify cursor on that line matches.
        // Verify cursor on adjacent line does not match.
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\n"
        )
        val doc = myFixture.editor.document
        val offset = doc.getLineStartOffset(1)
        val marker = doc.createRangeMarker(offset, offset)
        assertEquals(marker.startOffset, marker.endOffset)

        val sessions = ArrayDeque<Order89Session>()
        sessions.addLast(makeTestSession(marker))

        assertNotNull(findSessionAtCaret(doc, 1, sessions))
        assertNull(findSessionAtCaret(doc, 0, sessions))
        assertNull(findSessionAtCaret(doc, 2, sessions))
        marker.dispose()
    }

    // Deleting entire range content collapses marker.

    fun testDeleteEntireRangeContentCollapsesMarker() {
        // Create a range marker spanning some text.
        // Delete all text within the range via WriteCommandAction.
        // The marker should still be valid but with startOffset == endOffset.
        // Verify the isValid guard pattern: the marker is valid but zero-width,
        // so cursor matching still works (only matches the collapse line).
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\n"
        )
        val doc = myFixture.editor.document
        val startOffset = doc.getLineStartOffset(1)
        val endOffset = doc.getLineEndOffset(2)
        val marker = doc.createRangeMarker(startOffset, endOffset)
        marker.isGreedyToRight = true

        WriteCommandAction.runWriteCommandAction(project) {
            doc.deleteString(marker.startOffset, marker.endOffset)
        }

        assertTrue("Marker should still be valid after content deletion.", marker.isValid)
        assertEquals(
            "Marker should be zero-width after all content is deleted.",
            marker.startOffset,
            marker.endOffset
        )

        val collapseLine = doc.getLineNumber(marker.startOffset)
        val sessions = ArrayDeque<Order89Session>()
        sessions.addLast(makeTestSession(marker))

        assertNotNull(
            "Cursor on the collapse line should match.",
            findSessionAtCaret(doc, collapseLine, sessions)
        )
        if (collapseLine > 0) {
            assertNull(
                "Cursor above the collapse line should not match.",
                findSessionAtCaret(doc, collapseLine - 1, sessions)
            )
        }
        marker.dispose()
    }

    // Document edit tracking.

    fun testInsertAboveShiftsMarkerDown() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\n"
        )
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(
            doc.getLineStartOffset(5),
            doc.getLineEndOffset(9)
        )
        assertEquals(5, doc.getLineNumber(marker.startOffset))
        assertEquals(9, doc.getLineNumber(marker.endOffset))

        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(doc.getLineStartOffset(1), "inserted\n")
        }

        assertEquals(6, doc.getLineNumber(marker.startOffset))
        assertEquals(10, doc.getLineNumber(marker.endOffset))
        marker.dispose()
    }

    fun testDeleteAboveShiftsMarkerUp() {
        myFixture.configureByText(
            "test.kt",
            "line0\nline1\nline2\nline3\nline4\nline5\n"
        )
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(
            doc.getLineStartOffset(3),
            doc.getLineEndOffset(4)
        )
        assertEquals(3, doc.getLineNumber(marker.startOffset))
        assertEquals(4, doc.getLineNumber(marker.endOffset))

        // Delete "line1\n" (the entire second line including its newline).
        WriteCommandAction.runWriteCommandAction(project) {
            doc.deleteString(doc.getLineStartOffset(1), doc.getLineStartOffset(2))
        }

        assertEquals(2, doc.getLineNumber(marker.startOffset))
        assertEquals(3, doc.getLineNumber(marker.endOffset))
        marker.dispose()
    }

    fun testEditInFirstRangeAdjustsSecondMarker() {
        myFixture.configureByText(
            "test.kt",
            "aaa\nbbb\nccc\nddd\neee\n"
        )
        val doc = myFixture.editor.document
        val first = doc.createRangeMarker(
            doc.getLineStartOffset(0),
            doc.getLineEndOffset(1)
        )
        val second = doc.createRangeMarker(
            doc.getLineStartOffset(3),
            doc.getLineEndOffset(4)
        )
        val secondStartBefore = second.startOffset

        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(doc.getLineEndOffset(0), "EXTRA")
        }

        assertEquals(secondStartBefore + 5, second.startOffset)
        first.dispose()
        second.dispose()
    }

    // isGreedyToRight behavior.

    fun testGreedyToRightExpandsOnLongerReplacement() {
        myFixture.configureByText("test.kt", "ABCDE\n")
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(0, 5)
        marker.isGreedyToRight = true
        val originalEnd = marker.endOffset

        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(0, 5, "ABCDEFGHIJ")
        }

        assertTrue(
            "endOffset should expand: was $originalEnd, now ${marker.endOffset}.",
            marker.endOffset > originalEnd
        )
        marker.dispose()
    }

    fun testGreedyToRightContractsOnShorterReplacement() {
        myFixture.configureByText("test.kt", "ABCDEFGHIJ\n")
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(0, 10)
        marker.isGreedyToRight = true
        val originalEnd = marker.endOffset

        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(0, 10, "AB")
        }

        assertTrue(
            "endOffset should contract: was $originalEnd, now ${marker.endOffset}.",
            marker.endOffset < originalEnd
        )
        marker.dispose()
    }

    fun testGreedyToRightEndOffsetEqualsStartPlusNewTextLength() {
        myFixture.configureByText("test.kt", "ABCDE\n")
        val doc = myFixture.editor.document
        val start = 0
        val marker = doc.createRangeMarker(start, 5)
        marker.isGreedyToRight = true
        val replacement = "XY replaced text here"

        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(start, 5, replacement)
        }

        // Production code relies on this for reformatText range calculation.
        assertEquals(start + replacement.length, marker.endOffset)
        marker.dispose()
    }

    // isValid guard pattern.

    fun testIsValidGuardPreventsStaleWrite() {
        myFixture.configureByText("test.kt", "original content\n")
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(0, 16)
        marker.dispose()
        val textBefore = doc.text

        // Replicate the guard pattern from Order89Action line 163.
        WriteCommandAction.runWriteCommandAction(project) {
            if (!marker.isValid) return@runWriteCommandAction
            doc.replaceString(marker.startOffset, marker.endOffset, "SHOULD NOT APPEAR")
        }

        assertEquals(textBefore, doc.text)
    }

    fun testValidRangeAllowsWrite() {
        myFixture.configureByText("test.kt", "original content\n")
        val doc = myFixture.editor.document
        val marker = doc.createRangeMarker(0, 16)
        marker.isGreedyToRight = true

        WriteCommandAction.runWriteCommandAction(project) {
            if (!marker.isValid) return@runWriteCommandAction
            doc.replaceString(marker.startOffset, marker.endOffset, "replaced")
        }

        assertTrue(doc.text.startsWith("replaced"))
        marker.dispose()
    }

    // Chained replaceString + range-offset read-back.

    fun testReplaceStringThenReadBackViaGreedyRange() {
        // Reproduces the production pattern from Order89Action lines 163-168:
        // replaceString through a greedy range, then use the updated offsets
        // to read back the replacement text from the document.
        myFixture.configureByText("test.kt", "prefix ORIGINAL suffix\n")
        val doc = myFixture.editor.document
        val rangeStart = "prefix ".length
        val rangeEnd = "prefix ORIGINAL".length
        val range = doc.createRangeMarker(rangeStart, rangeEnd)
        range.isGreedyToRight = true

        val newText = "REPLACED_LONGER_TEXT"

        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(range.startOffset, range.endOffset, newText)
        }

        // The range should now span exactly the replacement text.
        assertEquals(
            "Range length should equal replacement text length.",
            newText.length,
            range.endOffset - range.startOffset
        )

        // The range offsets should let us read the replacement back from the document.
        assertEquals(
            "Reading the document through the range should yield the replacement.",
            newText,
            doc.getText(TextRange(range.startOffset, range.endOffset))
        )

        range.dispose()
    }
}
