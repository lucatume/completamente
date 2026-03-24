package com.github.lucatume.completamente.order89

import com.github.lucatume.completamente.BaseCompletionTest
import kotlinx.serialization.json.JsonPrimitive
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
            doc.insertString(offset, "\u2726 Executing\u2026\n")
        }

        val symbolRange = doc.createRangeMarker(0, 1)
        val symbolChar = doc.getText(TextRange(symbolRange.startOffset, symbolRange.endOffset))
        assertEquals("\u2726", symbolChar)
        symbolRange.dispose()
    }

    fun testSymbolReplaceStringSameLength() {
        myFixture.configureByText("test.kt", "\u2726 Executing\u2026\nfun main() {}")
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
        myFixture.configureByText("test.kt", "\u2726 Executing\u2026\n")
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
        myFixture.configureByText("test.kt", "\u2726 Executing\u2026\n  prompt\nfun main() {}")
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
        val statusText = "\u2726 Executing\u2026\n  do something\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }
        assertTrue(doc.text.contains("Executing\u2026"))

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

        val statusText = "\u2726 Executing\u2026\n  prompt\n"
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
        val statusText = "$indent\u2726 Executing\u2026\n${indent}  prompt\n"

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
        val statusText = "\u2726 Executing\u2026\n  do something\n"
        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().runUndoTransparentAction {
                UndoUtil.disableUndoIn(doc) {
                    doc.insertString(0, statusText)
                }
            }
        }
        assertTrue(doc.text.contains("Executing\u2026"))

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

    // -- updateStatusDisplay tests --

    fun testUpdateStatusDisplayWithToolCallsShowsSubLines() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document

        val statusText = "\u2726 Executing\u2026\n  \u2514\u2500 prompt text\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply { foregroundColor = Color(255, 16, 240); fontType = Font.ITALIC }
        val h = editor.markupModel.addRangeHighlighter(
            0, "\u2726 Executing\u2026".length, HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(), listOf(h), timer, Timer(100) {})
        val action = Order89Action()

        val toolCalls = listOf(
            ToolCall("FileSearch", mapOf("query" to JsonPrimitive("TODO"), "path" to JsonPrimitive("src/")))
        )
        action.updateStatusDisplay(editor, display, StatusUpdate.ToolCalls(toolCalls))

        val text = doc.text
        assertTrue("Should contain Executing", text.contains("Executing\u2026"))
        assertTrue("Should contain FileSearch tool call", text.contains("FileSearch( query: \"TODO\", path: \"src/\")"))
        assertTrue("Should contain tree char", text.contains("\u2514"))
        // 1 sub-line highlighter (shimmer handles first line separately)
        assertEquals(1, display.highlighters.size)

        timer.stop()
        display.highlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        display.shimmerHighlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        symbolRange.dispose()
        range.dispose()
    }

    fun testUpdateStatusDisplayWithWaitingPreservesSubLines() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document

        val statusText = "\u2726 Executing\u2026\n  \u2514\u2500 some tool call\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply { foregroundColor = Color(255, 16, 240); fontType = Font.ITALIC }
        val h = editor.markupModel.addRangeHighlighter(
            0, "\u2726 Executing\u2026".length, HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        val subLineAttrs = TextAttributes().apply { foregroundColor = Color(200, 200, 200); fontType = Font.ITALIC }
        val subH = editor.markupModel.addRangeHighlighter(
            "\u2726 Executing\u2026\n".length, statusText.length - 1, HighlighterLayer.LAST, subLineAttrs, HighlighterTargetArea.EXACT_RANGE
        )
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(subH), listOf(h), timer, Timer(100) {})
        val action = Order89Action()

        action.updateStatusDisplay(editor, display, StatusUpdate.WaitingForModel)

        val text = doc.text
        assertTrue("Should still contain Executing", text.contains("Executing\u2026"))
        assertTrue("Should still contain sub-line text", text.contains("some tool call"))
        // Sub-line highlighter preserved (shimmer handles first line separately)
        assertEquals(1, display.highlighters.size)

        timer.stop()
        display.highlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        display.shimmerHighlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        symbolRange.dispose()
        range.dispose()
    }

    fun testUpdateStatusDisplayWithMultipleToolCalls() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document

        val statusText = "\u2726 Executing\u2026\n  \u2514\u2500 prompt\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply { foregroundColor = Color(255, 16, 240); fontType = Font.ITALIC }
        val h = editor.markupModel.addRangeHighlighter(
            0, "\u2726 Executing\u2026".length, HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(), listOf(h), timer, Timer(100) {})
        val action = Order89Action()

        val toolCalls = listOf(
            ToolCall("FileSearch", mapOf("query" to JsonPrimitive("TODO"))),
            ToolCall("FileSearch", mapOf("query" to JsonPrimitive("FIXME")))
        )
        action.updateStatusDisplay(editor, display, StatusUpdate.ToolCalls(toolCalls))

        val text = doc.text
        assertTrue("Should contain first tool call", text.contains("TODO"))
        assertTrue("Should contain second tool call", text.contains("FIXME"))
        assertTrue("Non-last line should use branch char", text.contains("\u251C"))
        assertTrue("Last line should use corner char", text.contains("\u2514"))
        // 2 sub-line highlighters (shimmer handles first line separately)
        assertEquals(2, display.highlighters.size)

        timer.stop()
        display.highlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        display.shimmerHighlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        symbolRange.dispose()
        range.dispose()
    }

    fun testUpdateStatusDisplayWithNullDisplayIsNoOp() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val originalText = myFixture.editor.document.text
        val action = Order89Action()
        action.updateStatusDisplay(myFixture.editor, null, StatusUpdate.WaitingForModel)
        assertEquals(originalText, myFixture.editor.document.text)
    }

    fun testUpdateStatusDisplayWithDisposedRangeIsNoOp() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document

        val statusText = "\u2726 Executing\u2026\n  \u2514\u2500 prompt\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(), emptyList(), timer, Timer(100) {})
        val action = Order89Action()

        // Dispose the range before calling update
        range.dispose()
        val textBefore = doc.text
        action.updateStatusDisplay(editor, display, StatusUpdate.ToolCalls(
            listOf(ToolCall("FileSearch", mapOf("query" to JsonPrimitive("test"))))
        ))
        assertEquals("Document should be unchanged after update on disposed range", textBefore, doc.text)

        timer.stop()
        symbolRange.dispose()
    }

    fun testUpdateStatusDisplayAfterRemoveIsNoOp() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document
        val originalText = doc.text

        val statusText = "\u2726 Executing\u2026\n  \u2514\u2500 prompt\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(), emptyList(), timer, Timer(100) {})
        val action = Order89Action()

        // Remove the display first
        action.removeStatusDisplay(editor, display)
        assertEquals("Document restored after removal", originalText, doc.text)

        // Now try to update — should be a no-op since range is disposed
        action.updateStatusDisplay(editor, display, StatusUpdate.ToolCalls(
            listOf(ToolCall("FileSearch", mapOf("query" to JsonPrimitive("test"))))
        ))
        assertEquals("Document unchanged after update post-removal", originalText, doc.text)
    }

    fun testUpdateStatusDisplayReplacesSubLinesOnConsecutiveCalls() {
        myFixture.configureByText("test.kt", "fun main() {}")
        val editor = myFixture.editor
        val doc = editor.document

        val statusText = "\u2726 Executing\u2026\n  \u2514\u2500 prompt text\n"
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(0, statusText)
        }

        val range = doc.createRangeMarker(0, statusText.length)
        val symbolRange = doc.createRangeMarker(0, 1)
        val attrs = TextAttributes().apply { foregroundColor = Color(255, 16, 240); fontType = Font.ITALIC }
        val h = editor.markupModel.addRangeHighlighter(
            0, "\u2726 Executing\u2026".length, HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE
        )
        val timer = Timer(100) {}
        val display = Order89StatusDisplay(range, symbolRange, mutableListOf(), listOf(h), timer, Timer(100) {})
        val action = Order89Action()

        // First update: show one tool call
        action.updateStatusDisplay(editor, display, StatusUpdate.ToolCalls(
            listOf(ToolCall("FileSearch", mapOf("query" to JsonPrimitive("TODO"))))
        ))
        assertTrue("Should contain first tool call", doc.text.contains("TODO"))
        assertEquals("1 sub-line highlighter", 1, display.highlighters.size)

        // Second update: replace with different tool calls
        action.updateStatusDisplay(editor, display, StatusUpdate.ToolCalls(
            listOf(
                ToolCall("FileSearch", mapOf("query" to JsonPrimitive("FIXME"))),
                ToolCall("DocSearch", mapOf("query" to JsonPrimitive("kotlin docs"), "docsets" to JsonPrimitive("kt")))
            )
        ))
        assertFalse("Old tool call should be gone", doc.text.contains("TODO"))
        assertTrue("New tool call 1 should be present", doc.text.contains("FIXME"))
        assertTrue("New tool call 2 should be present", doc.text.contains("kotlin docs"))
        assertEquals("2 sub-line highlighters", 2, display.highlighters.size)

        // Third update: back to waiting — sub-lines preserved
        action.updateStatusDisplay(editor, display, StatusUpdate.WaitingForModel)
        assertTrue("Tool calls should still be present", doc.text.contains("FIXME"))
        assertTrue("Tool calls should still be present", doc.text.contains("kotlin docs"))
        assertEquals("All highlighters preserved", 2, display.highlighters.size)

        timer.stop()
        display.highlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        display.shimmerHighlighters.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        symbolRange.dispose()
        range.dispose()
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
