package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class reindentTest : BaseCompletionTest() {

    private val fourSpaces = IndentStyle(useTabs = false, indentSize = 4)
    private val twoSpaces = IndentStyle(useTabs = false, indentSize = 2)
    private val tabs = IndentStyle(useTabs = true, indentSize = 4)

    // --- Basic cases ---

    fun testSingleLineSuggestionReturnedUnchanged() {
        val result = reindentSuggestion("foo()", "    ", fourSpaces)
        assertEquals("foo()", result)
    }

    fun testEmptySuggestionReturnedUnchanged() {
        val result = reindentSuggestion("", "    ", fourSpaces)
        assertEquals("", result)
    }

    fun testTwoLineSuggestionModelMatchesProject() {
        val suggestion = "foo()\n    bar()"
        val result = reindentSuggestion(suggestion, "    ", fourSpaces)
        assertEquals("foo()\n    bar()", result)
    }

    // --- Style conversion: space width changes ---

    fun testModelTwoSpaceProjectFourSpace() {
        val suggestion = "foo()\n  bar()\n    baz()"
        val result = reindentSuggestion(suggestion, "        ", fourSpaces)
        assertEquals("foo()\n        bar()\n            baz()", result)
    }

    fun testModelFourSpaceProjectTwoSpace() {
        val suggestion = "foo()\n    bar()\n        baz()"
        val result = reindentSuggestion(suggestion, "    ", twoSpaces)
        assertEquals("foo()\n    bar()\n      baz()", result)
    }

    // --- Style conversion: tabs vs spaces ---

    fun testModelSpacesProjectTabs() {
        val suggestion = "foo()\n    bar()\n        baz()"
        val result = reindentSuggestion(suggestion, "\t\t", tabs)
        assertEquals("foo()\n\t\tbar()\n\t\t\tbaz()", result)
    }

    fun testModelTabsProjectSpaces() {
        val suggestion = "foo()\n\tbar()\n\t\tbaz()"
        val result = reindentSuggestion(suggestion, "        ", fourSpaces)
        assertEquals("foo()\n        bar()\n            baz()", result)
    }

    // --- Relative indent preservation ---

    fun testThreeLevelNesting() {
        val suggestion = "if (true) {\n  foo()\n    bar()\n      baz()\n}"
        val result = reindentSuggestion(suggestion, "", fourSpaces)
        // Model unit=2. Base=0 (the "}" line). Levels: 1, 2, 3, 0.
        assertEquals("if (true) {\n    foo()\n        bar()\n            baz()\n}", result)
    }

    fun testDedentClosingBrace() {
        // Model generates correct relative levels: body at 1 level, closing braces at 0.
        val suggestion = "doStuff()\n    }\n}"
        val result = reindentSuggestion(suggestion, "        ", fourSpaces)
        // Model unit=4. Levels: 4/4=1, 0/0=0. Base=0.
        // Line 1: relative=1 → "        " + "    ". Line 2: relative=0 → "        ".
        assertEquals("doStuff()\n            }\n        }", result)
    }

    // --- Cursor indent integration ---

    fun testCursorIndentEightSpaces() {
        val suggestion = "foo()\nbar()"
        val result = reindentSuggestion(suggestion, "        ", fourSpaces)
        assertEquals("foo()\n        bar()", result)
    }

    fun testCursorIndentTwoTabs() {
        val suggestion = "foo()\n\tbar()\n\t\tbaz()"
        val result = reindentSuggestion(suggestion, "\t\t", tabs)
        assertEquals("foo()\n\t\tbar()\n\t\t\tbaz()", result)
    }

    fun testCursorIndentZero() {
        val suggestion = "foo()\n    bar()"
        val result = reindentSuggestion(suggestion, "", fourSpaces)
        // Model unit=4. Base=1. Relative=0 → no cursor indent.
        assertEquals("foo()\nbar()", result)
    }

    // --- Edge cases ---

    fun testAllTailLinesBlank() {
        val suggestion = "foo()\n\n\n"
        val result = reindentSuggestion(suggestion, "    ", fourSpaces)
        assertEquals("foo()\n\n\n", result)
    }

    fun testLineZeroWithLeadingWhitespaceNotModified() {
        val suggestion = "    foo()\n    bar()"
        val result = reindentSuggestion(suggestion, "    ", fourSpaces)
        assertEquals("    foo()\n    bar()", result)
    }

    fun testModelIndentUnitOneSpace() {
        val suggestion = "foo()\n bar()\n  baz()"
        val result = reindentSuggestion(suggestion, "", fourSpaces)
        assertEquals("foo()\nbar()\n    baz()", result)
    }

    fun testDeepNestingTenLevels() {
        val modelLines = (0..10).map { level ->
            "  ".repeat(level) + "level$level"
        }
        val suggestion = modelLines.joinToString("\n")
        val result = reindentSuggestion(suggestion, "", fourSpaces)
        // Model unit=2. Lines 1..10 have levels 1..10. Base=1. Relative = level-1.
        val expectedLines = listOf("level0") + (1..10).map { level ->
            "    ".repeat(level - 1) + "level$level"
        }
        assertEquals(expectedLines.joinToString("\n"), result)
    }

    fun testSuggestionEndingWithNewline() {
        val suggestion = "foo()\n    bar()\n"
        val result = reindentSuggestion(suggestion, "    ", fourSpaces)
        assertEquals("foo()\n    bar()\n", result)
    }

    fun testMixedTabsAndSpacesReturnedUnchanged() {
        // Mixed tabs and spaces — cannot determine indent levels reliably, return unchanged.
        val suggestion = "foo()\n\tbar()\n  baz()"
        val result = reindentSuggestion(suggestion, "    ", fourSpaces)
        assertEquals(suggestion, result)
    }

    // --- CRLF normalization ---

    fun testCrlfNormalizedToLf() {
        val suggestion = "foo()\r\n    bar()\r\n        baz()"
        val result = reindentSuggestion(suggestion, "\t\t", tabs)
        // After CRLF normalization, same as testModelSpacesProjectTabs.
        assertEquals("foo()\n\t\tbar()\n\t\t\tbaz()", result)
    }

    fun testCrNormalizedToLf() {
        val suggestion = "foo()\r    bar()"
        val result = reindentSuggestion(suggestion, "  ", twoSpaces)
        assertEquals("foo()\n  bar()", result)
    }

    fun testMultiLineCrOnlyNormalized() {
        val suggestion = "foo()\r  bar()\r    baz()"
        val result = reindentSuggestion(suggestion, "        ", fourSpaces)
        // After CR→LF normalization, model unit=2. Base=1. Relative levels: 0, 1.
        assertEquals("foo()\n        bar()\n            baz()", result)
    }

    // --- detectModelIndentUnit tests ---

    fun testDetectTabIndent() {
        assertEquals("\t", detectModelIndentUnit(listOf("\t", "\t\t")))
    }

    fun testDetectTwoSpaceIndent() {
        assertEquals("  ", detectModelIndentUnit(listOf("  ", "    ", "      ")))
    }

    fun testDetectFourSpaceIndent() {
        assertEquals("    ", detectModelIndentUnit(listOf("    ", "        ")))
    }

    fun testDetectGcdFallbackToMinimum() {
        // Indent counts: 3, 5. GCD = 1. Min = 3 (> 1), so use 3.
        assertEquals("   ", detectModelIndentUnit(listOf("   ", "     ")))
    }

    fun testDetectSingleSpaceIndent() {
        assertEquals(" ", detectModelIndentUnit(listOf(" ", "  ")))
    }

    fun testDetectMixedTabsAndSpacesReturnsNull() {
        assertNull(detectModelIndentUnit(listOf("\t", "  ")))
    }

    fun testDetectSingleElementList() {
        assertEquals("    ", detectModelIndentUnit(listOf("    ")))
    }

    fun testDetectAllZeroLengthIndents() {
        // All indents are empty strings (0 indent). spaceCounts is empty after filter.
        // Returns " " as a placeholder — callers compute level 0 for all lines regardless.
        assertEquals(" ", detectModelIndentUnit(listOf("", "")))
    }

    fun testDetectEmptyListNotCalledDirectly() {
        // detectModelIndentUnit is guarded by nonBlankTailIndents.isEmpty() in reindentSuggestion,
        // so it is never called with an empty list. Verify the guard works end-to-end.
        val suggestion = "foo()\n\n\n"
        val result = reindentSuggestion(suggestion, "    ", fourSpaces)
        assertEquals("foo()\n\n\n", result)
    }

    // --- Additional edge cases ---

    fun testSingleNonBlankTailLine() {
        val suggestion = "foo()\n    bar()"
        val result = reindentSuggestion(suggestion, "  ", twoSpaces)
        assertEquals("foo()\n  bar()", result)
    }

    fun testTailLinesWithNoIndentation() {
        val suggestion = "foo()\nbar()\nbaz()"
        val result = reindentSuggestion(suggestion, "    ", fourSpaces)
        assertEquals("foo()\n    bar()\n    baz()", result)
    }

    fun testSuggestionIsSingleNewline() {
        val result = reindentSuggestion("\n", "    ", fourSpaces)
        assertEquals("\n", result)
    }

    fun testProjectIndentSizeZeroCoercedToOne() {
        val zeroIndent = IndentStyle(useTabs = false, indentSize = 0)
        val suggestion = "foo()\n  bar()\n    baz()"
        val result = reindentSuggestion(suggestion, "", zeroIndent)
        // indentSize coerced to 1. Model unit=2. Base=1. Line 1: relative=0 → "".
        // Line 2: relative=1 → " " (1 space).
        assertEquals("foo()\nbar()\n baz()", result)
    }

    fun testCursorIndentMixedTabsAndSpaces() {
        // Cursor indent has mixed tabs+spaces (from existing file). Should be prepended as-is.
        val suggestion = "foo()\n    bar()"
        val result = reindentSuggestion(suggestion, "\t  ", fourSpaces)
        assertEquals("foo()\n\t  bar()", result)
    }

    fun testTailLinesNoIndentWithTabProject() {
        val suggestion = "foo()\nbar()\nbaz()"
        val result = reindentSuggestion(suggestion, "\t\t", tabs)
        assertEquals("foo()\n\t\tbar()\n\t\tbaz()", result)
    }
}
