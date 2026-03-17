package com.github.lucatume.completamente.completion

/**
 * Trims leading and trailing whitespace artifacts from a FIM completion.
 *
 * **Leading whitespace (line 0):** The model may emit indentation spaces that
 * duplicate whitespace already present before the cursor. This strips leading
 * space/tab characters from line 0 up to [cursorCol] characters — never more,
 * so intentional deeper indentation is preserved.
 *
 * **Trailing whitespace:** Removes trailing blank lines (a token-limit truncation
 * artifact where the model started a new line and emitted indentation before
 * being cut off), trailing spaces on the last line, and trailing newlines.
 *
 * This function runs before [reindentSuggestion] in the completion pipeline.
 */
fun trimCompletion(suggestion: String, cursorCol: Int): String {
    if (suggestion.isEmpty()) return suggestion

    // Normalize line endings.
    val normalized = suggestion.replace("\r\n", "\n").replace("\r", "\n")

    // --- Leading whitespace on line 0 ---
    val firstNewline = normalized.indexOf('\n')
    val line0 = if (firstNewline < 0) normalized else normalized.substring(0, firstNewline)
    val rest = if (firstNewline < 0) null else normalized.substring(firstNewline) // includes the '\n'

    val leadingWsCount = line0.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) line0.length else it }
    // coerceAtLeast(0) is defensive: cursorCol is offset - lineStart which should
    // always be non-negative, but this guards against unexpected upstream values.
    val charsToStrip = minOf(leadingWsCount, cursorCol).coerceAtLeast(0)
    val trimmedLine0 = line0.substring(charsToStrip)

    val joined = if (rest != null) trimmedLine0 + rest else trimmedLine0

    // --- Trailing whitespace ---
    // Remove trailing blank lines and trailing spaces.
    var result = joined.trimEnd('\n')

    // After trimming trailing newlines, remove a trailing blank line:
    // if the last line is whitespace-only, drop it.
    while (result.contains('\n')) {
        val lastNewline = result.lastIndexOf('\n')
        val lastLine = result.substring(lastNewline + 1)
        if (lastLine.isBlank()) {
            result = result.substring(0, lastNewline)
        } else {
            break
        }
    }

    // Strip trailing spaces/tabs from the final line.
    result = result.trimEnd(' ', '\t')

    return result
}
