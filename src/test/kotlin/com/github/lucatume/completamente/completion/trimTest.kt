package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class trimTest : BaseCompletionTest() {

    // --- Leading whitespace stripping (line 0) ---

    fun testNoLeadingWhitespaceUnchanged() {
        assertEquals("foo()", trimCompletion("foo()", 4))
    }

    fun testLeadingSpacesEqualToCursorCol() {
        // Cursor at column 4, suggestion has 4 leading spaces → strip all.
        assertEquals("foo()", trimCompletion("    foo()", 4))
    }

    fun testLeadingSpacesLessThanCursorCol() {
        // Cursor at column 8, suggestion has 4 leading spaces → strip all 4.
        assertEquals("foo()", trimCompletion("    foo()", 8))
    }

    fun testLeadingSpacesGreaterThanCursorCol() {
        // Cursor at column 4, suggestion has 8 leading spaces → strip only 4.
        assertEquals("    foo()", trimCompletion("        foo()", 4))
    }

    fun testCursorColZeroNoStripping() {
        assertEquals("    foo()", trimCompletion("    foo()", 0))
    }

    fun testLeadingTabsStrippedByCursorCol() {
        // Each tab counts as 1 character for stripping.
        assertEquals("foo()", trimCompletion("\t\tfoo()", 2))
    }

    fun testLeadingTabsPartialStrip() {
        assertEquals("\tfoo()", trimCompletion("\t\t\tfoo()", 2))
    }

    fun testMixedSpacesAndTabsStripped() {
        // "\t  " = 3 ws chars. cursorCol=2 → strip 2, leaving " foo()".
        assertEquals(" foo()", trimCompletion("\t  foo()", 2))
    }

    fun testSingleLineLeadingSpaces() {
        assertEquals("return;", trimCompletion("        return;", 8))
    }

    // --- Leading whitespace with multi-line suggestions ---

    fun testMultiLineOnlyLine0Trimmed() {
        // Line 0 has 4 leading spaces (stripped). Lines 1+ are untouched.
        val input = "    foo()\n        bar()\n        baz()"
        val expected = "foo()\n        bar()\n        baz()"
        assertEquals(expected, trimCompletion(input, 4))
    }

    fun testMultiLineLine0NoLeadingWs() {
        val input = "foo()\n    bar()"
        assertEquals(input, trimCompletion(input, 4))
    }

    // --- Trailing whitespace ---

    fun testTrailingNewlinesRemoved() {
        assertEquals("foo()", trimCompletion("foo()\n\n\n", 0))
    }

    fun testTrailingBlankLineRemoved() {
        // Last line is whitespace-only → removed.
        assertEquals("foo()\n    bar()", trimCompletion("foo()\n    bar()\n        ", 0))
    }

    fun testMultipleTrailingBlankLinesRemoved() {
        assertEquals("foo()", trimCompletion("foo()\n    \n        \n", 0))
    }

    fun testTrailingSpacesOnLastContentLine() {
        assertEquals("foo()", trimCompletion("foo()   ", 0))
    }

    fun testTrailingNewlineAndSpaces() {
        // "\n       " = blank trailing line → removed, then trailing spaces stripped.
        assertEquals("foo()", trimCompletion("foo()\n       ", 0))
    }

    fun testTrailingTabsStripped() {
        assertEquals("foo()", trimCompletion("foo()\t\t", 0))
    }

    // --- Combined leading + trailing ---

    fun testLeadingAndTrailingTrimmed() {
        val input = "        foo()\n    bar()\n        "
        val expected = "foo()\n    bar()"
        assertEquals(expected, trimCompletion(input, 8))
    }

    fun testLine0TrimmedTrailingBlankLineRemoved() {
        val input = "    addCommand('solo');\n    "
        val expected = "addCommand('solo');"
        assertEquals(expected, trimCompletion(input, 4))
    }

    // --- Edge cases ---

    fun testEmptyString() {
        assertEquals("", trimCompletion("", 0))
    }

    fun testEmptyStringWithCursorCol() {
        assertEquals("", trimCompletion("", 8))
    }

    fun testSingleNewline() {
        assertEquals("", trimCompletion("\n", 0))
    }

    fun testOnlyWhitespace() {
        assertEquals("", trimCompletion("    \n    \n  ", 0))
    }

    fun testOnlySpaces() {
        assertEquals("", trimCompletion("        ", 4))
    }

    fun testSingleCharacter() {
        assertEquals("x", trimCompletion("x", 0))
    }

    fun testSingleCharacterWithLeadingSpaces() {
        assertEquals("x", trimCompletion("    x", 4))
    }

    fun testAllLeadingWhitespaceStrippedLine0BecomesEmpty() {
        // Line 0 is all spaces, cursorCol >= count → line 0 becomes empty.
        // Then "bar()" remains on line 2.
        assertEquals("\nbar()", trimCompletion("    \nbar()", 8))
    }

    fun testNegativeCursorColTreatedAsZero() {
        // Defensive: negative cursorCol should not cause an exception.
        assertEquals("    foo()", trimCompletion("    foo()", -1))
    }

    fun testVeryLargeCursorCol() {
        // cursorCol far exceeds leading whitespace → strips only the whitespace.
        assertEquals("foo()", trimCompletion("    foo()", 10000))
    }

    fun testMultipleNewlinesOnly() {
        assertEquals("", trimCompletion("\n\n\n", 0))
    }

    // --- CRLF normalization ---

    fun testCrlfNormalizedBeforeTrimming() {
        assertEquals("foo()\n    bar()", trimCompletion("    foo()\r\n    bar()\r\n", 4))
    }

    fun testCrNormalizedBeforeTrimming() {
        assertEquals("foo()\n    bar()", trimCompletion("    foo()\r    bar()\r", 4))
    }

    // --- Harness-observed patterns ---

    fun testPositionAEmptyLineBetweenMethods() {
        // Harness 47: Position A returned "    /**\n     * @return void\n     */"
        // Cursor at column 0 on empty line → cursorCol=0 → no leading strip.
        val input = "    /**\n     * @return void\n     */"
        assertEquals(input, trimCompletion(input, 0))
    }

    fun testPositionCCursorAtColumnZeroNoStrip() {
        // Harness 47: Position C actual scenario — cursor at column 0, no stripping.
        val input = "        \$this->register_commands();\n    }"
        assertEquals(input, trimCompletion(input, 0))
    }

    fun testPositionCCursorAtColumnEightStripsAll() {
        // Hypothetical: if cursor were at column 8, strip leading spaces.
        val input = "        \$this->register_commands();\n    }"
        assertEquals("\$this->register_commands();\n    }", trimCompletion(input, 8))
    }

    fun testPositionDTrailingNewlineAndSpaces() {
        // Harness 47: Position D completion D.3 had trailing \n + 7 spaces.
        val input = "        // comment\n        code();\n       "
        assertEquals("// comment\n        code();", trimCompletion(input, 8))
    }

    fun testPositionD3PureTrailingNewlineAndSpaces() {
        // Harness 47: D.3 trailing pattern in isolation (no leading strip interaction).
        val input = "// some code\n       "
        assertEquals("// some code", trimCompletion(input, 0))
    }

    fun testMidLineCompletionNoWhitespace() {
        // Harness 45: Mid-line after "WP_CLI::" → no leading/trailing ws.
        val input = "addCommand( 'solo', __NAMESPACE__ . '\\Command' );"
        assertEquals(input, trimCompletion(input, 16))
    }
}
