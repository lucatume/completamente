package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class contextTest : BaseCompletionTest() {

    // --- buildFileContext: cursor at middle of multi-line file ---
    fun testBuildFileContextMiddleOfFile() {
        val content = "line0\nline1\nline2\nline3\nline4"
        val result = buildFileContext(content, cursorLine = 2, cursorColumn = 3)
        assertEquals("line0\nline1\n", result.inputPrefix)
        assertEquals("e2\nline3\nline4", result.inputSuffix)
        assertEquals("lin", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: cursor at start of file (line 0, col 0) ---
    fun testBuildFileContextStartOfFile() {
        val content = "line0\nline1\nline2"
        val result = buildFileContext(content, cursorLine = 0, cursorColumn = 0)
        assertEquals("", result.inputPrefix)
        assertEquals("line0\nline1\nline2", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: cursor at end of file (last line, end of line) ---
    fun testBuildFileContextEndOfFile() {
        val content = "line0\nline1\nline2"
        val result = buildFileContext(content, cursorLine = 2, cursorColumn = 5)
        assertEquals("line0\nline1\n", result.inputPrefix)
        assertEquals("", result.inputSuffix)
        assertEquals("line2", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: cursor at start of a line (col 0) ---
    fun testBuildFileContextStartOfLine() {
        val content = "line0\nline1\nline2"
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 0)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("line1\nline2", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: cursor at end of a line ---
    fun testBuildFileContextEndOfLine() {
        val content = "line0\nline1\nline2"
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 5)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("\nline2", result.inputSuffix)
        assertEquals("line1", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: single-line file ---
    fun testBuildFileContextSingleLineFile() {
        val content = "hello"
        val result = buildFileContext(content, cursorLine = 0, cursorColumn = 3)
        assertEquals("", result.inputPrefix)
        assertEquals("lo", result.inputSuffix)
        assertEquals("hel", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: empty file ---
    fun testBuildFileContextEmptyFile() {
        val content = ""
        val result = buildFileContext(content, cursorLine = 0, cursorColumn = 0)
        assertEquals("", result.inputPrefix)
        assertEquals("", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: whitespace-only current line ---
    fun testBuildFileContextWhitespaceOnlyLine() {
        val content = "line0\n    \nline2"
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 2)
        assertEquals("line0\n", result.inputPrefix)
        // whitespace-only: full line preserved in suffix
        assertEquals("    \nline2", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: line with leading spaces, correct nIndent ---
    fun testBuildFileContextLeadingSpaces() {
        val content = "line0\n    code\nline2"
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 6)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("de\nline2", result.inputSuffix)
        assertEquals("    co", result.prompt)
        assertEquals(4, result.nIndent)
    }

    // --- buildFileContext: tab indentation counts as 1 per tab ---
    fun testBuildFileContextTabIndentation() {
        val content = "line0\n\t\tcode\nline2"
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 4)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("de\nline2", result.inputSuffix)
        assertEquals("\t\tco", result.prompt)
        assertEquals(2, result.nIndent)
    }

    // --- buildFileContext: mixed tabs and spaces ---
    fun testBuildFileContextMixedIndentation() {
        val content = "line0\n\t  code\nline2"
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 5)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("de\nline2", result.inputSuffix)
        assertEquals("\t  co", result.prompt)
        assertEquals(3, result.nIndent)
    }

    // --- buildWindowedFileContext: cursor near top (clamped at file start) ---
    fun testBuildWindowedFileContextClampedAtStart() {
        val lines = (0..999).map { "line$it" }
        val content = lines.joinToString("\n")
        val result = buildWindowedFileContext(content, cursorLine = 2, cursorColumn = 3, prefixLines = 512, suffixLines = 128)
        // Only 2 lines above cursor (lines 0 and 1)
        assertEquals("line0\nline1\n", result.inputPrefix)
        // suffix: rest of line 2 ("e2") + lines 3..130 (128 lines)
        val expectedSuffixLines = listOf("e2") + (3..130).map { "line$it" }
        assertEquals(expectedSuffixLines.joinToString("\n"), result.inputSuffix)
        assertEquals("lin", result.prompt)
    }

    // --- buildWindowedFileContext: cursor near bottom (clamped at file end) ---
    fun testBuildWindowedFileContextClampedAtEnd() {
        val lines = (0..999).map { "line$it" }
        val content = lines.joinToString("\n")
        val result = buildWindowedFileContext(content, cursorLine = 998, cursorColumn = 4, prefixLines = 512, suffixLines = 128)
        // Only 1 line below cursor (line 999)
        assertEquals("998\nline999", result.inputSuffix)
        assertEquals("line", result.prompt)
        // prefix should contain 512 lines above cursor (lines 486..997)
        val expectedPrefix = (486..997).map { "line$it" }.joinToString("\n") + "\n"
        assertEquals(expectedPrefix, result.inputPrefix)
    }

    // --- buildWindowedFileContext: large file with cursor in middle ---
    fun testBuildWindowedFileContextMiddleOfLargeFile() {
        val lines = (0..1999).map { "line$it" }
        val content = lines.joinToString("\n")
        val result = buildWindowedFileContext(content, cursorLine = 1000, cursorColumn = 5, prefixLines = 512, suffixLines = 128)
        // prefix: 512 lines above cursor (lines 488..999)
        val expectedPrefix = (488..999).map { "line$it" }.joinToString("\n") + "\n"
        assertEquals(expectedPrefix, result.inputPrefix)
        // suffix: rest of line 1000 ("000") + lines 1001..1128
        val expectedSuffixLines = listOf("000") + (1001..1128).map { "line$it" }
        assertEquals(expectedSuffixLines.joinToString("\n"), result.inputSuffix)
        assertEquals("line1", result.prompt)
    }

    // --- buildContext: small file uses whole file (hard-coded assertions) ---
    fun testBuildContextSmallFile() {
        val content = "line0\nline1\nline2"
        val result = buildContext(content, cursorLine = 1, cursorColumn = 3, maxFileTokens = 10000)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("e1\nline2", result.inputSuffix)
        assertEquals("lin", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildContext: large file uses windowed fallback (hard-coded assertions) ---
    fun testBuildContextLargeFile() {
        // estimateTokens = (length + 2) / 3, so for maxFileTokens=10, we need length > 28
        val content = "abcdefghij\nklmnopqrst\nuvwxyz1234\n567890abcd"
        assertTrue("file should exceed token limit", estimateTokens(content) > 10)
        val result = buildContext(content, cursorLine = 2, cursorColumn = 3, maxFileTokens = 10, prefixLines = 512, suffixLines = 128)
        assertEquals("abcdefghij\nklmnopqrst\n", result.inputPrefix)
        assertEquals("xyz1234\n567890abcd", result.inputSuffix)
        assertEquals("uvw", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: whitespace-only single-line file ---
    fun testBuildFileContextWhitespaceOnlyFile() {
        val content = "   "
        val result = buildFileContext(content, cursorLine = 0, cursorColumn = 1)
        assertEquals("", result.inputPrefix)
        // whitespace-only: full line preserved in suffix
        assertEquals("   ", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: cursor at col 0 on indented line ---
    fun testBuildFileContextCol0IndentedLine() {
        val content = "line0\n    indented\nline2"
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 0)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("    indented\nline2", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(4, result.nIndent)
    }

    // --- Out-of-bounds cursor column clamps correctly ---
    fun testBuildFileContextOutOfBoundsCursorColumn() {
        val content = "line0\nline1\nline2\nline3\nline4"
        // cursorColumn = 999 on "line0" which has 5 characters
        val result = buildFileContext(content, cursorLine = 0, cursorColumn = 999)
        assertEquals("", result.inputPrefix)
        assertEquals("\nline1\nline2\nline3\nline4", result.inputSuffix)
        assertEquals("line0", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- Out-of-bounds cursor line handles gracefully ---
    fun testBuildFileContextOutOfBoundsCursorLine() {
        val content = "line0\nline1\nline2\nline3\nline4"
        // cursorLine = 999 on a 5-line file; currentLine becomes ""
        val result = buildFileContext(content, cursorLine = 999, cursorColumn = 0)
        // All 5 lines are before cursor, but cursorLine > lines.size so prefix is all lines
        assertEquals("line0\nline1\nline2\nline3\nline4\n", result.inputPrefix)
        assertEquals("", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- Trailing newline file splits correctly ---
    fun testBuildFileContextTrailingNewline() {
        val content = "line0\nline1\n"
        // split("\n") produces ["line0", "line1", ""]
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 3)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("e1\n", result.inputSuffix)
        assertEquals("lin", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- CRLF handling: produces same result as LF ---
    fun testBuildFileContextCRLF() {
        val lfContent = "line0\nline1\nline2"
        val crlfContent = "line0\r\nline1\r\nline2"
        val lfResult = buildFileContext(lfContent, cursorLine = 1, cursorColumn = 3)
        val crlfResult = buildFileContext(crlfContent, cursorLine = 1, cursorColumn = 3)
        assertEquals(lfResult, crlfResult)
        // Also verify concrete values
        assertEquals("line0\n", crlfResult.inputPrefix)
        assertEquals("e1\nline2", crlfResult.inputSuffix)
        assertEquals("lin", crlfResult.prompt)
        assertEquals(0, crlfResult.nIndent)
    }

    // --- buildFileContext: negative cursor line produces same result as cursorLine = 0 ---
    fun testBuildFileContextNegativeCursorLine() {
        val content = "line0\nline1\nline2"
        val negResult = buildFileContext(content, cursorLine = -1, cursorColumn = 3)
        val zeroResult = buildFileContext(content, cursorLine = 0, cursorColumn = 3)
        assertEquals(zeroResult, negResult)
    }

    // --- buildFileContext: negative cursor column produces same result as cursorColumn = 0 ---
    fun testBuildFileContextNegativeCursorColumn() {
        val content = "line0\nline1\nline2"
        val negResult = buildFileContext(content, cursorLine = 1, cursorColumn = -1)
        val zeroResult = buildFileContext(content, cursorLine = 1, cursorColumn = 0)
        assertEquals(zeroResult, negResult)
    }

    // --- buildWindowedFileContext: OOB cursor line on small file ---
    fun testBuildWindowedFileContextOOBCursorLine() {
        val lines = (0..99).map { "line$it" }
        val content = lines.joinToString("\n")
        // Should not crash; cursor past end of file
        // With cursorLine=999, windowStart = max(0, 999-512) = 487 which is past file end (100 lines).
        // Compare against buildFileContext which also clamps gracefully.
        val result = buildWindowedFileContext(content, cursorLine = 999, cursorColumn = 0, prefixLines = 512, suffixLines = 128)
        val wholeResult = buildFileContext(content, cursorLine = 999, cursorColumn = 0)
        // Both should produce empty prompt with cursor past end
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
        assertEquals("", result.inputSuffix)
        assertEquals("", result.inputPrefix)
    }

    // --- buildFileContext: trailing CRLF ---
    fun testBuildFileContextTrailingCRLF() {
        val content = "line0\r\nline1\r\n"
        // After CRLF normalization: "line0\nline1\n" -> split -> ["line0", "line1", ""]
        val result = buildFileContext(content, cursorLine = 1, cursorColumn = 3)
        assertEquals("line0\n", result.inputPrefix)
        assertEquals("e1\n", result.inputSuffix)
        assertEquals("lin", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: cursor on empty trailing line ---
    fun testBuildFileContextCursorOnEmptyTrailingLine() {
        val content = "line0\nline1\n"
        // split -> ["line0", "line1", ""], cursor on line 2 (the empty trailing line)
        val result = buildFileContext(content, cursorLine = 2, cursorColumn = 0)
        assertEquals("line0\nline1\n", result.inputPrefix)
        assertEquals("", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildContext: file at maxFileTokens + 1 uses windowed path ---
    fun testBuildContextThresholdPlusOne() {
        // estimateTokens = (length + 2) / 3
        // For maxFileTokens = 6, threshold + 1 means estimateTokens = 7
        // (length + 2) / 3 = 7 when length >= 19. "abcd\nefgh\nijklmnopqrs".length = 21, tokens = 7.
        val content = "abcd\nefgh\nijklmnopqrs"
        assertEquals(7, estimateTokens(content))
        assertTrue("file should exceed token limit", estimateTokens(content) > 6)
        // At threshold + 1, should use windowed path
        val result = buildContext(content, cursorLine = 1, cursorColumn = 2, maxFileTokens = 6)
        val windowedResult = buildWindowedFileContext(content, cursorLine = 1, cursorColumn = 2)
        assertEquals(windowedResult, result)
    }

    // --- buildContext: file exactly at maxFileTokens threshold uses whole file ---
    fun testBuildContextExactlyAtThreshold() {
        // estimateTokens = (length + 2) / 3
        // For maxFileTokens = 6, we need length such that (length + 2) / 3 = 6, so length = 16
        val content = "abcd\nefgh\nijklmn"
        assertEquals(16, content.length)
        assertEquals(6, estimateTokens(content))
        // At threshold (<=), should use whole file
        val result = buildContext(content, cursorLine = 1, cursorColumn = 2, maxFileTokens = 6)
        val wholeResult = buildFileContext(content, cursorLine = 1, cursorColumn = 2)
        assertEquals(wholeResult, result)
        // Verify concrete values
        assertEquals("abcd\n", result.inputPrefix)
        assertEquals("gh\nijklmn", result.inputSuffix)
        assertEquals("ef", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: lone \r (classic Mac) line endings ---
    fun testBuildFileContextLoneCR() {
        val crContent = "line0\rline1\rline2"
        val lfContent = "line0\nline1\nline2"
        val crResult = buildFileContext(crContent, cursorLine = 1, cursorColumn = 3)
        val lfResult = buildFileContext(lfContent, cursorLine = 1, cursorColumn = 3)
        assertEquals(lfResult, crResult)
        assertEquals("line0\n", crResult.inputPrefix)
        assertEquals("e1\nline2", crResult.inputSuffix)
        assertEquals("lin", crResult.prompt)
        assertEquals(0, crResult.nIndent)
    }

    // --- buildWindowedFileContext: prefixLines = 0 yields empty prefix ---
    fun testBuildWindowedFileContextZeroPrefixLines() {
        val content = "line0\nline1\nline2\nline3\nline4"
        val result = buildWindowedFileContext(content, cursorLine = 2, cursorColumn = 3, prefixLines = 0, suffixLines = 128)
        assertEquals("", result.inputPrefix)
        assertEquals("e2\nline3\nline4", result.inputSuffix)
        assertEquals("lin", result.prompt)
        assertEquals(0, result.nIndent)
    }

    // --- buildFileContext: single newline file ---
    fun testBuildFileContextSingleNewline() {
        val content = "\n"
        // split("\n") -> ["", ""]
        val result = buildFileContext(content, cursorLine = 0, cursorColumn = 0)
        assertEquals("", result.inputPrefix)
        assertEquals("\n", result.inputSuffix)
        assertEquals("", result.prompt)
        assertEquals(0, result.nIndent)
    }
}
