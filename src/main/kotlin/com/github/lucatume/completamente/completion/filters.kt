package com.github.lucatume.completamente.completion

/**
 * Returns true if the suggestion should be discarded (i.e., not shown to the user).
 *
 * A suggestion is discarded when it is empty/whitespace, echoes existing suffix text,
 * or completes to a line that already exists in the suffix.
 *
 * @param suggestion The completion suggestion text from the model
 * @param suffixText The text after the cursor in the current document
 * @param prefixLastLine The text on the current line before the cursor
 */
fun shouldDiscardSuggestion(
    suggestion: String,
    suffixText: String,
    prefixLastLine: String
): Boolean {
    // Rule 1: empty or whitespace-only
    if (suggestion.isBlank()) return true

    val suggestionLines = suggestion.replace("\r\n", "\n").replace("\r", "\n").trimEnd('\n').split("\n")
    val firstLine = suggestionLines[0]

    val normalizedSuffix = suffixText.replace("\r\n", "\n").replace("\r", "\n")
    if (normalizedSuffix.isEmpty()) return false
    val suffixLines = normalizedSuffix.trimEnd('\n').split("\n")

    // Rule 2: first line of suggestion matches the first line of suffixText (single-line only)
    // Rule 2 only applies to single-line suggestions. Multi-line suggestions that share the
    // first line with the suffix but differ on subsequent lines are legitimate completions —
    // Rule 4 handles the case where ALL lines match.
    if (suggestionLines.size == 1) {
        val firstSuffixLine = suffixLines[0]
        if (firstLine == firstSuffixLine) return true
    }

    // Rule 3: prefixLastLine + firstLine matches next non-blank line in suffixText.
    // Unlike Rule 2, this applies to BOTH single-line and multi-line suggestions — if the
    // first line combined with what the user typed completes to an existing line, the whole
    // suggestion is discarded regardless of how many lines follow.
    // Uses firstOrNull { isNotBlank() } to skip blank lines between cursor and the next
    // meaningful line. This means a match can occur even if blank lines separate the cursor
    // from the matching line — this is intentional, as the model often generates content that
    // would duplicate a line further down.
    val combined = prefixLastLine + firstLine
    val nextNonBlank = suffixLines.firstOrNull { it.isNotBlank() }
    if (nextNonBlank != null && combined == nextNonBlank) return true

    // Rule 4: multi-line suggestion where all lines match consecutive lines at the start of suffixText
    // Rule 4 only fires when ALL suggestion lines match consecutive suffix lines from the start.
    // A multi-line suggestion that shares only the first line with the suffix is kept — Rule 2
    // handles the single-line echo case.
    if (suggestionLines.size > 1) {
        if (suggestionLines.size <= suffixLines.size) {
            val allMatch = suggestionLines.indices.all { i -> suggestionLines[i] == suffixLines[i] }
            if (allMatch) return true
        }
    }

    return false
}

/**
 * Returns true if auto-trigger should be suppressed.
 *
 * Suppresses when there is too much text after the cursor on the current line,
 * indicating the cursor is not at or near the end of the line.
 *
 * @param lineAfterCursor The text on the current line after the cursor position
 * @param maxSuffixLength The maximum number of characters after the cursor on the current line
 *     before auto-trigger is suppressed. Default of 8 matches llama.vim.
 */
fun shouldSuppressAutoTrigger(
    lineAfterCursor: String,
    maxSuffixLength: Int = 8
): Boolean {
    // Operates on raw character count — no CRLF normalization needed since it receives only
    // the current line's content after cursor, which won't contain line endings.
    return maxSuffixLength >= 0 && lineAfterCursor.length > maxSuffixLength
}
