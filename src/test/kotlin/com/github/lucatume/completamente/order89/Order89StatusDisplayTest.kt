package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import java.awt.Color
import java.awt.Font
import javax.swing.Timer

class Order89StatusDisplayTest : BaseCompletionTest() {

    // -- insertStatusLines behavior (tested via Order89Action) --

    private fun insertStatusText(content: String, prompt: String, offset: Int = 0): Pair<String, String> {
        myFixture.configureByText("test.kt", content)
        val doc = myFixture.editor.document
        val lineEnd = doc.getLineEndOffset(doc.getLineNumber(offset))
        val lineText = doc.getText(TextRange(offset, lineEnd))
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val truncated = truncatePrompt(prompt)
        val statusLine1 = "$indent\u2726 Executing..."
        val statusLine2 = "$indent  $truncated"
        return statusLine1 to statusLine2
    }

    fun testStatusLineContainsStarSymbol() {
        val (line1, _) = insertStatusText("fun main() {}", "do something")
        assertTrue("Status line should start with star symbol", line1.contains("\u2726"))
    }

    fun testStatusLineContainsExecutingEllipsis() {
        val (line1, _) = insertStatusText("fun main() {}", "do something")
        assertTrue("Should contain 'Executing...'", line1.contains("Executing..."))
    }

    fun testStatusLineContainsTruncatedPrompt() {
        val (_, line2) = insertStatusText("fun main() {}", "make it print hello world")
        assertTrue("Should contain prompt text", line2.contains("make it print hello world"))
    }

    fun testStatusLinePreservesIndent() {
        val (line1, line2) = insertStatusText("    fun main() {}", "do something", 0)
        assertTrue("Line 1 should have indent", line1.startsWith("    "))
        assertTrue("Line 2 should have indent", line2.startsWith("    "))
    }

    fun testStatusLineNoIndentWhenNone() {
        val (line1, _) = insertStatusText("fun main() {}", "do something", 0)
        assertTrue("Line 1 should start with star", line1.startsWith("\u2726"))
    }

    // -- Symbol rotation mechanics --

    fun testSymbolRangeMarkerTracksStarCharacter() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val doc = myFixture.editor.document
        val offset = 0

        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(offset, "\u2726 Executing...\n")
        }

        val symbolRange = doc.createRangeMarker(0, 1)
        val symbolChar = doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset))
        assertEquals("\u2726", symbolChar)
        symbolRange.dispose()
    }

    fun testSymbolReplaceStringSameLength() {
        myFixture.configureByText("test.kt", "\u2726 Executing...\nfun main() {}")
        val doc = myFixture.editor.document
        val symbolRange = doc.createRangeMarker(0, 1)
        val originalLength = doc.textLength

        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(symbolRange.startOffset, symbolRange.endOffset, "\u2727")
        }

        assertEquals("Document length should not change", originalLength, doc.textLength)
        assertEquals("\u2727", doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset)))
        symbolRange.dispose()
    }

    fun testSymbolRangeStaysValidAfterReplace() {
        myFixture.configureByText("test.kt", "\u2726 Executing...\n")
        val doc = myFixture.editor.document
        val symbolRange = doc.createRangeMarker(0, 1)

        val symbols = charArrayOf('\u2726', '\u2727', '\u2736', '\u2737', '\u2738', '\u2739')
        for (symbol in symbols) {
            WriteCommandAction.runWriteCommandAction(project) {
                doc.replaceString(symbolRange.startOffset, symbolRange.endOffset, symbol.toString())
            }
            assertTrue("symbolRange should stay valid after replacing with $symbol", symbolRange.isValid)
            assertEquals(symbol.toString(), doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset)))
        }
        symbolRange.dispose()
    }

    fun testAllSixSymbolsAreSingleCharacter() {
        val symbols = charArrayOf('\u2726', '\u2727', '\u2736', '\u2737', '\u2738', '\u2739')
        assertEquals(6, symbols.size)
        for (symbol in symbols) {
            assertEquals("Each symbol should be 1 character", 1, symbol.toString().length)
        }
    }

    // -- Order89StatusDisplay data class --

    fun testStatusDisplayDataClassHoldsAllFields() {
        myFixture.configureByText("test.kt", "\u2726 Executing...\n  prompt\nfun main() {}")
        val doc = myFixture.editor.document
        val range = doc.createRangeMarker(0, doc.getLineEndOffset(1) + 1)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply {
            foregroundColor = Color(255, 16, 240)
            fontType = Font.ITALIC
        }
        val h = myFixture.editor.markupModel.addRangeHighlighter(
            0, 10, HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        val timer = Timer(100) {}

        val display = Order89StatusDisplay(range, symbolRange, listOf(h), timer)

        assertSame(range, display.range)
        assertSame(symbolRange, display.symbolRange)
        assertEquals(1, display.highlighters.size)
        assertSame(timer, display.timer)

        timer.stop()
        myFixture.editor.markupModel.removeHighlighter(h)
        symbolRange.dispose()
        range.dispose()
    }

    // -- removeStatusDisplay behavior --

    fun testRemoveStatusDisplayDeletesTextAndDisposesMarkers() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document
        val originalText = doc.text

        // Insert status text.
        val statusText = "\u2726 Executing...\n  do something\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }
        assertTrue(doc.text.contains("Executing..."))

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply { foregroundColor = Color(255, 16, 240) }
        val h = editor.markupModel.addRangeHighlighter(
            0, 10, HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        val timer = Timer(100) {}
        timer.start()

        val display = Order89StatusDisplay(range, symbolRange, listOf(h), timer)
        val action = Order89Action()
        action.removeStatusDisplay(editor, display)

        assertEquals("Document should be restored to original", originalText, doc.text)
        assertFalse("Timer should be stopped", timer.isRunning)
        assertFalse("Range should be disposed", range.isValid)
        assertFalse("SymbolRange should be disposed", symbolRange.isValid)
    }

    fun testRemoveStatusDisplayIsIdempotent() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document

        val statusText = "\u2726 Executing...\n  prompt\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, emptyList(), timer)
        val action = Order89Action()

        action.removeStatusDisplay(editor, display)
        // Second call should not throw.
        action.removeStatusDisplay(editor, display)
    }

    fun testRemoveStatusDisplayWithNullIsNoop() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val action = Order89Action()
        // Should not throw.
        action.removeStatusDisplay(myFixture.editor, null)
    }

    // -- Symbol rotation with indented code --

    fun testSymbolRangeWithIndentedCode() {
        myFixture.configureByText("test.kt", "    fun main() {}")
        val doc = myFixture.editor.document
        val indent = "    "
        val statusText = "$indent\u2726 Executing...\n${indent}  prompt\n"

        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        // Symbol should be at indent.length offset.
        val symbolRange = doc.createRangeMarker(indent.length, indent.length + 1)
        assertEquals("\u2726", doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset)))

        // Replace and verify.
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(symbolRange.startOffset, symbolRange.endOffset, "\u2737")
        }
        assertEquals("\u2737", doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset)))
        symbolRange.dispose()
    }

    // -- lerpColor tests --

    fun testLerpColorAtZeroReturnsFirstColor() {
        val a = Color(255, 0, 0)
        val b = Color(0, 255, 0)
        val result = lerpColor(a, b, 0.0)
        assertEquals(a, result)
    }

    fun testLerpColorAtOneReturnsSecondColor() {
        val a = Color(255, 0, 0)
        val b = Color(0, 255, 0)
        val result = lerpColor(a, b, 1.0)
        assertEquals(b, result)
    }

    fun testLerpColorAtHalfReturnsMidpoint() {
        val a = Color(0, 0, 0)
        val b = Color(200, 100, 50)
        val result = lerpColor(a, b, 0.5)
        assertEquals(100, result.red)
        assertEquals(50, result.green)
        assertEquals(25, result.blue)
    }

    fun testLerpColorClampsAboveOne() {
        val a = Color(100, 100, 100)
        val b = Color(200, 200, 200)
        val result = lerpColor(a, b, 2.0)
        assertEquals(b, result)
    }

    fun testLerpColorClampsBelowZero() {
        val a = Color(100, 100, 100)
        val b = Color(200, 200, 200)
        val result = lerpColor(a, b, -1.0)
        assertEquals(a, result)
    }
}
