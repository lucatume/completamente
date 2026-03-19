package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import java.awt.Color
import java.awt.Font
import javax.swing.Timer

class Order89StatusDisplayTest : BaseCompletionTest() {

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

    // -- Order89StatusDisplay data class --

    fun testStatusDisplayDataClassHoldsAllFields() {
        myFixture.configureByText("test.kt", "\u2726 Executing...\n  prompt\nfun main() {}")
        val doc = myFixture.editor.document
        val range = doc.createRangeMarker(0, doc.getLineEndOffset(1) + 1)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply {
            foregroundColor = Color(0, 128, 255)
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
        val originalText = doc.text

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
        assertEquals("Document should be restored after first removal", originalText, doc.text)
        // Second call should not throw and should leave document unchanged.
        action.removeStatusDisplay(editor, display)
        assertEquals("Document should remain unchanged after second removal", originalText, doc.text)
    }

    fun testRemoveStatusDisplayWithNullLeavesDocumentUnchanged() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val originalText = myFixture.editor.document.text
        val action = Order89Action()
        action.removeStatusDisplay(myFixture.editor, null)
        assertEquals("Document should be unchanged after null removal", originalText, myFixture.editor.document.text)
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

    // -- statusLineColors tests --

    fun testStatusLineColorsReturnsNonNullPair() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val (popColor, defaultFg) = statusLineColors(myFixture.editor)
        assertNotNull(popColor)
        assertNotNull(defaultFg)
    }

    fun testStatusLineColorsDefaultFgMatchesScheme() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val (_, defaultFg) = statusLineColors(myFixture.editor)
        val expected = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.defaultForeground
        assertEquals(expected, defaultFg)
    }

    fun testStatusLineColorsPopColorDiffersFromDefaultWhenHyperlinkColorSet() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val scheme = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
        val hyperlinkAttrs = scheme.getAttributes(com.intellij.openapi.editor.colors.EditorColors.REFERENCE_HYPERLINK_COLOR)
        val hyperlinkFg = hyperlinkAttrs?.foregroundColor
        val (popColor, defaultFg) = statusLineColors(myFixture.editor)
        if (hyperlinkFg != null && hyperlinkFg != scheme.defaultForeground) {
            assertNotSame("Pop color should differ from default foreground when hyperlink color is set", defaultFg, popColor)
        } else {
            assertEquals("Pop color should fall back to default foreground", defaultFg, popColor)
        }
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
                UndoUtil.disableUndoIn(doc) {
                    doc.insertString(0, statusText)
                }
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
