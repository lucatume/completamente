package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import java.awt.Color
import java.awt.Font
import javax.swing.Timer

class Order89StatusDisplayTest : BaseCompletionTest() {

    // -- insertStatusLines behavior (tested via Order89Action) --

    private fun insertStatusLines(content: String, prompt: String, offset: Int = 0): List<String> {
        myFixture.configureByText("test.kt", content)
        val doc = myFixture.editor.document
        val lineEnd = doc.getLineEndOffset(doc.getLineNumber(offset))
        val lineText = doc.getText(TextRange(offset, lineEnd))
        val indent = lineText.takeWhile { it == ' ' || it == '\t' }
        val promptLines = formatPromptLines(prompt)
        val statusLine1 = "$indent\u2726 Executing..."
        val promptPrefix = "$indent \u23BF "
        val continuationPrefix = "$indent   "
        val lines = mutableListOf(statusLine1)
        promptLines.forEachIndexed { i, line ->
            val prefix = if (i == 0) promptPrefix else continuationPrefix
            lines.add("$prefix$line")
        }
        return lines
    }

    fun testStatusLineStartsWithStarAndExecuting() {
        val lines = insertStatusLines("fun main() {}", "do something")
        assertEquals("\u2726 Executing...", lines[0])
    }

    fun testStatusLineFirstPromptLineUsesContSymbol() {
        val lines = insertStatusLines("fun main() {}", "make it print hello world")
        assertEquals(" \u23BF make it print hello world", lines[1])
    }

    fun testStatusLinePreservesIndent() {
        val lines = insertStatusLines("    fun main() {}", "do something", 0)
        assertEquals("    \u2726 Executing...", lines[0])
        assertEquals("     \u23BF do something", lines[1])
    }

    fun testStatusLineNoIndentWhenNone() {
        val lines = insertStatusLines("fun main() {}", "do something", 0)
        assertEquals("\u2726 Executing...", lines[0])
        assertEquals(" \u23BF do something", lines[1])
    }

    fun testMultiLineWrappingWithIndent() {
        val longPrompt = ("abcdefghij ".repeat(20)).trim()
        val promptLines = formatPromptLines(longPrompt)
        val lines = insertStatusLines("    fun main() {}", longPrompt, 0)
        assertEquals("Line count = 1 header + prompt lines", 1 + promptLines.size, lines.size)
        assertEquals("    \u2726 Executing...", lines[0])
        assertEquals("     \u23BF ${promptLines[0]}", lines[1])
        for (i in 1 until promptLines.size) {
            assertEquals("       ${promptLines[i]}", lines[i + 1])
        }
    }

    fun testShortPromptFitsOnOneLine() {
        val lines = insertStatusLines("fun main() {}", "short prompt")
        assertEquals("Should have 2 lines (header + 1 prompt line)", 2, lines.size)
        assertEquals(" \u23BF short prompt", lines[1])
    }

    fun testLongPromptWrapsAcrossMultipleLines() {
        // 7 words x 10 chars + spaces = 76 chars first line, then wraps.
        val longPrompt = ("abcdefghij ".repeat(20)).trim()
        val promptLines = formatPromptLines(longPrompt)
        val lines = insertStatusLines("fun main() {}", longPrompt)
        assertEquals("Line count = 1 header + prompt lines", 1 + promptLines.size, lines.size)
        assertEquals(" \u23BF ${promptLines[0]}", lines[1])
        for (i in 1 until promptLines.size) {
            assertEquals("   ${promptLines[i]}", lines[i + 1])
        }
    }

    fun testContinuationLinesUseSpacePrefixNotSymbol() {
        val longPrompt = ("abcdefghij ".repeat(20)).trim()
        val promptLines = formatPromptLines(longPrompt)
        val lines = insertStatusLines("fun main() {}", longPrompt)
        assertEquals("Line count = 1 header + prompt lines", 1 + promptLines.size, lines.size)
        for (i in 1 until promptLines.size) {
            assertEquals("   ${promptLines[i]}", lines[i + 1])
        }
    }

    fun testEmptyPromptProducesTwoLines() {
        val lines = insertStatusLines("fun main() {}", "")
        assertEquals("Should have 2 lines (header + 1 empty prompt line)", 2, lines.size)
        assertEquals("\u2726 Executing...", lines[0])
        assertEquals(" \u23BF ", lines[1])
    }

    // -- formatPromptLines tests --

    fun testFormatPromptLinesSingleShortLine() {
        val result = formatPromptLines("hello world")
        assertEquals(listOf("hello world"), result)
    }

    fun testFormatPromptLinesWrapsAtMaxWidth() {
        // "aaaaaaaaaa bbbbbbbbbb" = 21 chars, adding " cccccccccc" = 32 > 25 → wraps.
        val result = formatPromptLines("aaaaaaaaaa bbbbbbbbbb cccccccccc dddddddddd", maxWidth = 25)
        assertEquals(listOf("aaaaaaaaaa bbbbbbbbbb", "cccccccccc dddddddddd"), result)
    }

    fun testFormatPromptLinesEmptyInput() {
        assertEquals(listOf(""), formatPromptLines(""))
        assertEquals(listOf(""), formatPromptLines("   "))
        assertEquals(listOf(""), formatPromptLines("\n\n"))
    }

    fun testFormatPromptLinesCollapsesWhitespace() {
        val result = formatPromptLines("hello   world\nfoo   bar")
        assertEquals(listOf("hello world foo bar"), result)
    }

    fun testFormatPromptLinesSingleLongWord() {
        val longWord = "a".repeat(100)
        val result = formatPromptLines(longWord, maxWidth = 80)
        assertEquals("Single word should be on one line even if over maxWidth", 1, result.size)
        assertEquals(longWord, result[0])
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

    // -- Undo after successful Order 89 should restore original text, not status lines --

    fun testUndoAfterSuccessRestoresOriginalTextNotStatusLines() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document
        val originalText = doc.text

        // Open the file in an editor so UndoManager can find a FileEditor.
        val vFile = FileDocumentManager.getInstance().getFile(doc)!!
        FileEditorManager.getInstance(project).openFile(vFile, true)
        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(vFile)!!

        // 1. Insert status text undo-transparently (mimics insertStatusLines).
        val statusText = "\u2726 Executing...\n  do something\n"
        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().runUndoTransparentAction {
                doc.insertString(0, statusText)
            }
        }
        assertTrue(doc.text.contains("Executing..."))

        val statusRange = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(statusRange, symbolRange, emptyList(), timer)

        // Create a range marker for the "selection" (original text shifted by status insertion).
        val selectionRange = doc.createRangeMarker(statusText.length, statusText.length + originalText.length)
        selectionRange.isGreedyToRight = true

        val replacement = "fun main() { println(\"hello\") }"
        val action = Order89Action()

        // 2. Inside a single WriteCommandAction: remove status (undo-transparent) + replace selection (undoable).
        WriteCommandAction.runWriteCommandAction(project, "Order 89", null, {
            action.removeStatusDisplay(editor, display)
            if (selectionRange.isValid) {
                doc.replaceString(selectionRange.startOffset, selectionRange.endOffset, replacement)
            }
        })

        assertEquals(replacement, doc.text)

        // 3. Undo should restore the original text, NOT the status lines.
        val undoManager = UndoManager.getInstance(project)
        assertTrue("Undo should be available", undoManager.isUndoAvailable(fileEditor))
        undoManager.undo(fileEditor)

        assertEquals("Undo should restore original text without status lines", originalText, doc.text)

        selectionRange.dispose()
    }
}
