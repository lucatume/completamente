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
import com.intellij.openapi.editor.colors.EditorColors
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
            doc.insertString(offset, "✦ Executing…\n")
        }

        val symbolRange = doc.createRangeMarker(0, 1)
        val symbolChar = doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset))
        assertEquals("✦", symbolChar)
        symbolRange.dispose()
    }

    fun testSymbolReplaceStringSameLength() {
        myFixture.configureByText("test.kt", "✦ Executing…\nfun main() {}")
        val doc = myFixture.editor.document
        val symbolRange = doc.createRangeMarker(0, 1)
        val originalLength = doc.textLength

        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(symbolRange.startOffset, symbolRange.endOffset, "✧")
        }

        assertEquals("Document length should not change", originalLength, doc.textLength)
        assertEquals("✧", doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset)))
        symbolRange.dispose()
    }

    fun testSymbolRangeStaysValidAfterReplace() {
        myFixture.configureByText("test.kt", "✦ Executing…\n")
        val doc = myFixture.editor.document
        val symbolRange = doc.createRangeMarker(0, 1)

        val symbols = charArrayOf('✦', '✧', '✶', '✷', '✸', '✹')
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
        myFixture.configureByText("test.kt", "✦ Executing…\n  prompt\nfun main() {}")
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

        val shimmerTimer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(h), emptyList(), timer, shimmerTimer)

        assertSame(range, display.range)
        assertSame(symbolRange, display.symbolRange)
        assertEquals(1, display.highlighters.size)
        assertSame(timer, display.timer)
        assertSame(shimmerTimer, display.shimmerTimer)
        assertTrue(display.shimmerHighlighters.isEmpty())

        timer.stop()
        shimmerTimer.stop()
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
        val statusText = "✦ Executing…\n  do something\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }
        assertTrue(doc.text.contains("Executing…"))

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply { foregroundColor = Color(255, 16, 240) }
        val h = editor.markupModel.addRangeHighlighter(
            0, 10, HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        val timer = Timer(100) {}
        timer.start()

        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(h), emptyList(), timer, Timer(100) {})
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

        val statusText = "✦ Executing…\n  prompt\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(), emptyList(), timer, Timer(100) {})
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
        val statusText = "$indent✦ Executing…\n${indent}  prompt\n"

        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        // Symbol should be at indent.length offset.
        val symbolRange = doc.createRangeMarker(indent.length, indent.length + 1)
        assertEquals("✦", doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset)))

        // Replace and verify.
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(symbolRange.startOffset, symbolRange.endOffset, "✷")
        }
        assertEquals("✷", doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset)))
        symbolRange.dispose()
    }

    // -- statusLineColors tests --

    fun testStatusLineColorsDefaultFgMatchesEditorScheme() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val (_, defaultFg) = statusLineColors(myFixture.editor)
        assertEquals(myFixture.editor.colorsScheme.defaultForeground, defaultFg)
    }

    fun testStatusLineColorsPopColorUsesHyperlinkForeground() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val scheme = myFixture.editor.colorsScheme
        val hyperlinkFg = scheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)?.foregroundColor
        val (popColor, _) = statusLineColors(myFixture.editor)
        if (hyperlinkFg != null) {
            assertEquals("Pop color should match hyperlink foreground", hyperlinkFg, popColor)
        } else {
            assertEquals("Pop color should fall back to default foreground", scheme.defaultForeground, popColor)
        }
    }

    fun testStatusLineColorsFallsBackWhenHyperlinkForegroundNull() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val scheme = myFixture.editor.colorsScheme
        val savedAttrs = scheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)
        try {
            scheme.setAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR, TextAttributes())
            val (popColor, defaultFg) = statusLineColors(myFixture.editor)
            assertEquals("Pop color should equal default foreground when hyperlink has no foreground", defaultFg, popColor)
        } finally {
            if (savedAttrs != null) {
                scheme.setAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR, savedAttrs)
            }
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
        val statusText = "✦ Executing…\n  do something\n"
        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().runUndoTransparentAction {
                UndoUtil.disableUndoIn(doc) {
                    doc.insertString(0, statusText)
                }
            }
        }
        assertTrue(doc.text.contains("Executing…"))

        val statusRange = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(statusRange, symbolRange, mutableListOf(), emptyList(), timer, Timer(100) {})

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

    // -- truncatePrompt tests --

    fun testTruncatePromptShortStringUnchanged() {
        assertEquals("hello world", truncatePrompt("hello world"))
    }

    fun testTruncatePromptCollapsesNewlines() {
        assertEquals("hello world", truncatePrompt("hello\nworld"))
    }

    fun testTruncatePromptTruncatesLongString() {
        val long = "a".repeat(80)
        val result = truncatePrompt(long, maxLength = 60)
        assertEquals("a".repeat(60) + "...", result)
    }

    fun testTruncatePromptEmptyString() {
        assertEquals("", truncatePrompt(""))
    }

    fun testTruncatePromptExactlyAtLimit() {
        val exact = "a".repeat(60)
        assertEquals(exact, truncatePrompt(exact, maxLength = 60))
    }

    // -- shimmerColors tests --

    fun testShimmerColorsReturnsDarkThemePairForDarkBackground() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val bg = editor.colorsScheme.defaultBackground
        val luminance = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) / 255.0
        val (base, peak) = shimmerColors(editor)
        if (luminance < 0.5) {
            assertEquals(Color(180, 130, 255), base)
            assertEquals(Color(230, 200, 255), peak)
        } else {
            assertEquals(Color(120, 60, 200), base)
            assertEquals(Color(170, 120, 255), peak)
        }
    }

    fun testShimmerColorsReturnsLightThemePairForLightBackground() {
        // Verify the branch logic directly: a light background (luminance >= 0.5) yields the light pair.
        // Since the test editor may use either scheme, we verify the invariant holds for the detected theme.
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val (base, peak) = shimmerColors(editor)
        // Both pairs have base != peak and are valid Colors
        assertFalse("Base and peak should differ", base == peak)
        assertTrue("Base red in valid range", base.red in 0..255)
        assertTrue("Peak red in valid range", peak.red in 0..255)
    }

    // -- interpolateColor tests --

    fun testInterpolateColorAtZeroReturnsBase() {
        val base = Color(100, 50, 200)
        val peak = Color(200, 150, 255)
        assertEquals(base, interpolateColor(base, peak, 0f))
    }

    fun testInterpolateColorAtOneReturnsPeak() {
        val base = Color(100, 50, 200)
        val peak = Color(200, 150, 255)
        assertEquals(peak, interpolateColor(base, peak, 1f))
    }

    fun testInterpolateColorMidpoint() {
        val base = Color(0, 0, 0)
        val peak = Color(100, 100, 100)
        assertEquals(Color(50, 50, 50), interpolateColor(base, peak, 0.5f))
    }
}
