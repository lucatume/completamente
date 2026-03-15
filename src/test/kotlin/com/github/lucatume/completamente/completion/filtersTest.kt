package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.BaseCompletionTest

class filtersTest : BaseCompletionTest() {

    // --- shouldDiscardSuggestion ---

    fun testEmptySuggestionIsDiscarded() {
        assertTrue(shouldDiscardSuggestion("", suffixText = "foo", prefixLastLine = "bar"))
    }

    fun testWhitespaceOnlySuggestionWithSpacesIsDiscarded() {
        assertTrue(shouldDiscardSuggestion("   ", suffixText = "foo", prefixLastLine = "bar"))
    }

    fun testWhitespaceOnlySuggestionWithTabsIsDiscarded() {
        assertTrue(shouldDiscardSuggestion("\t\t", suffixText = "foo", prefixLastLine = "bar"))
    }

    fun testWhitespaceOnlySuggestionWithNewlinesIsDiscarded() {
        assertTrue(shouldDiscardSuggestion("\n\n", suffixText = "foo", prefixLastLine = "bar"))
    }

    fun testSuggestionFirstLineMatchesSuffixFirstLineIsDiscarded() {
        // Model echoed existing text after cursor
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "return x",
                suffixText = "return x\n}\n",
                prefixLastLine = "val y = "
            )
        )
    }

    fun testSuggestionFirstLineDoesNotMatchSuffixIsKept() {
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "doSomething()",
                suffixText = "return x\n}\n",
                prefixLastLine = "val y = "
            )
        )
    }

    fun testRule2FalsePositiveSuggestionStartsSameAsSuffixButDoesNotMatchFullLine() {
        // "return" starts with the same chars as "return result" but is not the full first line
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "return",
                suffixText = "return result\nmore",
                prefixLastLine = "val y = "
            )
        )
    }

    fun testPrefixLastLinePlusSuggestionFirstLineMatchesNextNonBlankSuffixLineIsDiscarded() {
        // The user typed "val x = " and the model suggests "42" completing to "val x = 42"
        // which already exists as the next non-empty line in suffixText
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "42",
                suffixText = "\n\nval x = 42\nreturn x",
                prefixLastLine = "val x = "
            )
        )
    }

    fun testMultiLineSuggestionMatchingConsecutiveSuffixLinesIsDiscarded() {
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "line1\nline2\nline3",
                suffixText = "line1\nline2\nline3\nline4",
                prefixLastLine = "prefix"
            )
        )
    }

    fun testMultiLineSuggestionWhereOnlyFirstLineMatchesIsKept() {
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "line1\nsomethingNew\nanotherNew",
                suffixText = "line1\nline2\nline3",
                prefixLastLine = "prefix"
            )
        )
    }

    fun testLegitimateMultiLineSuggestionWithNoMatchIsKept() {
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "newCode()\nnewCode2()\nnewCode3()",
                suffixText = "existingCode()\nmore()\nend()",
                prefixLastLine = "val x = "
            )
        )
    }

    fun testEmptySuffixTextReturnsFalseForNonEmptySuggestion() {
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "hello()",
                suffixText = "",
                prefixLastLine = "val x = "
            )
        )
    }

    fun testEmptyPrefixLastLineStillChecksOtherRules() {
        // First line of suggestion matches suffix first line → discard
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "return x",
                suffixText = "return x\n}\n",
                prefixLastLine = ""
            )
        )
    }

    fun testRule4SuggestionHasMoreLinesThanSuffixIsKept() {
        // Suggestion has 4 lines, suffix has only 2 → should NOT discard
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "line1\nline2\nline3\nline4",
                suffixText = "line1\nline2",
                prefixLastLine = "prefix"
            )
        )
    }

    fun testRule4EqualLengthAllMatchIsDiscarded() {
        // Suggestion lines == suffix lines and all match → should discard
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "line1\nline2\nline3",
                suffixText = "line1\nline2\nline3",
                prefixLastLine = "prefix"
            )
        )
    }

    fun testCrlfLineEndingsAreHandled() {
        // CRLF suffix with matching suggestion should still be discarded
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "return x",
                suffixText = "return x\r\n}\r\n",
                prefixLastLine = "val y = "
            )
        )
    }

    fun testCrlfMultiLineSuggestionMatchIsDiscarded() {
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "line1\r\nline2\r\nline3",
                suffixText = "line1\r\nline2\r\nline3\r\nline4",
                prefixLastLine = "prefix"
            )
        )
    }

    fun testSuggestionWithTrailingNewline() {
        // "hello\n" after trimEnd('\n') becomes "hello" — single line, no suffix match → kept
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "hello\n",
                suffixText = "world\nmore",
                prefixLastLine = "prefix"
            )
        )
    }

    fun testSuggestionWithTrailingNewlineMatchesSuffixIsDiscarded() {
        // "return x\n" trimmed to "return x" matches suffix first line
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "return x\n",
                suffixText = "return x\n}\n",
                prefixLastLine = "val y = "
            )
        )
    }

    // --- shouldSuppressAutoTrigger ---

    fun testEmptyStringDoesNotSuppress() {
        assertFalse(shouldSuppressAutoTrigger(lineAfterCursor = ""))
    }

    fun testLineAfterCursorLengthExactly8DoesNotSuppress() {
        assertFalse(shouldSuppressAutoTrigger(lineAfterCursor = "12345678"))
    }

    fun testLineAfterCursorLength9Suppresses() {
        assertTrue(shouldSuppressAutoTrigger(lineAfterCursor = "123456789"))
    }

    fun testLineAfterCursorLength100Suppresses() {
        assertTrue(shouldSuppressAutoTrigger(lineAfterCursor = "a".repeat(100)))
    }

    fun testCustomMaxSuffixLength0SuppressesIfAnyCharsAfterCursor() {
        assertTrue(shouldSuppressAutoTrigger(lineAfterCursor = "x", maxSuffixLength = 0))
    }

    // --- Rule 2 multi-line guard ---

    fun testRule2DoesNotFireForMultiLineSuggestionWithMatchingFirstLine() {
        // Multi-line suggestion shares first line with suffix but differs on subsequent lines.
        // Rule 2 is skipped (multi-line), Rule 4 is skipped (not all lines match).
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "return x\nextra",
                suffixText = "return x\n}",
                prefixLastLine = "val y = "
            )
        )
    }

    // --- Rule 4 edge cases ---

    fun testRule4EqualLengthWhereLastLineDiffersIsNotDiscarded() {
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "line1\nline2\nDIFFERENT",
                suffixText = "line1\nline2\nline3",
                prefixLastLine = "prefix"
            )
        )
    }

    fun testRule4SuggestionLongerThanSuffixIsNotDiscarded() {
        // Suggestion has 4 lines, suffix has 2 — should NOT discard
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "a\nb\nc\nd",
                suffixText = "a\nb",
                prefixLastLine = "prefix"
            )
        )
    }

    // --- Rule 3 edge cases ---

    fun testRule3WithEmptyPrefixLastLine() {
        // prefixLastLine is empty, so combined = suggestion first line "val x = 42"
        // matches the next non-blank suffix line "val x = 42" → should discard
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "val x = 42",
                suffixText = "\n\nval x = 42\nreturn",
                prefixLastLine = ""
            )
        )
    }

    fun testRule3WithWhitespaceOnlyPrefixLastLine() {
        // prefixLastLine is "    ", combined = "    return x"
        // matches the next non-blank suffix line "    return x" → should discard
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "return x",
                suffixText = "    return x\nmore",
                prefixLastLine = "    "
            )
        )
    }

    // --- shouldSuppressAutoTrigger whitespace ---

    fun testWhitespaceOnly8SpacesDoesNotSuppress() {
        assertFalse(shouldSuppressAutoTrigger(lineAfterCursor = "        "))  // 8 spaces
    }

    fun testWhitespaceOnly9SpacesSuppresses() {
        assertTrue(shouldSuppressAutoTrigger(lineAfterCursor = "         "))  // 9 spaces
    }

    // --- CRLF / LF normalization ---

    // --- Issue 5: single newline suggestion ---

    fun testSingleNewlineSuggestionIsDiscarded() {
        assertTrue(shouldDiscardSuggestion("\n", suffixText = "foo", prefixLastLine = "bar"))
    }

    // --- Issue 6: suffix is only blank lines ---

    fun testSuffixOnlyBlankLinesWithValidSuggestionIsNotDiscarded() {
        // Rule 3 finds no non-blank line, Rule 4 suggestion lines don't match empty lines
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "doSomething()",
                suffixText = "\n\n\n",
                prefixLastLine = "val x = "
            )
        )
    }

    // --- Issue 7: negative maxSuffixLength ---

    fun testNegativeMaxSuffixLengthDoesNotSuppress() {
        assertFalse(shouldSuppressAutoTrigger(lineAfterCursor = "some text", maxSuffixLength = -1))
    }

    // --- Issue 8: Rule 3 with multi-line suggestion ---

    fun testRule3WithMultiLineSuggestionMatchesNextNonBlank() {
        // prefixLastLine + firstLine = "val x = " + "42" = "val x = 42"
        // matches next non-blank suffix line "val x = 42" → should discard (Rule 3 applies to multi-line too)
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "42\nreturn x",
                suffixText = "\nval x = 42\nreturn x",
                prefixLastLine = "val x = "
            )
        )
    }

    // --- Issue 9: Rule 4 with blank-offset suffix ---

    fun testRule4WithBlankOffsetSuffixIsNotDiscarded() {
        // suffixLines[0] is empty, doesn't match suggestion's "line1"
        assertFalse(
            shouldDiscardSuggestion(
                suggestion = "line1\nline2",
                suffixText = "\nline1\nline2",
                prefixLastLine = "prefix"
            )
        )
    }

    // --- Issue 10: maxSuffixLength = 0 with empty string ---

    fun testMaxSuffixLength0WithEmptyStringDoesNotSuppress() {
        assertFalse(shouldSuppressAutoTrigger(lineAfterCursor = "", maxSuffixLength = 0))
    }

    fun testMixedCrlfLfNormalizationMakesThemMatch() {
        // Suggestion uses \r\n, suffix uses \n — after normalization they should match → discard
        assertTrue(
            shouldDiscardSuggestion(
                suggestion = "line1\r\nline2\r\nline3",
                suffixText = "line1\nline2\nline3\nline4",
                prefixLastLine = "prefix"
            )
        )
    }
}
