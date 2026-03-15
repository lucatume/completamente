package com.github.lucatume.completamente.completion

data class FileContext(
    val inputPrefix: String,
    val inputSuffix: String,
    val prompt: String,
    val nIndent: Int
)

/**
 * Splits file content at cursor position into infill fields.
 *
 * @param fileContent The full file text
 * @param cursorLine 0-based line index
 * @param cursorColumn 0-based column index
 */
fun buildFileContext(
    fileContent: String,
    cursorLine: Int,
    cursorColumn: Int
): FileContext {
    val lines = fileContent.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    return buildFileContextFromLines(lines, cursorLine, cursorColumn, 0, lines.size)
}

/**
 * Windowed version of [buildFileContext] for large files.
 * Takes up to [prefixLines] lines above cursor and [suffixLines] lines below, clamped at file boundaries.
 *
 * @param fileContent The full file text
 * @param cursorLine 0-based line index
 * @param cursorColumn 0-based column index
 * @param prefixLines Maximum number of lines to include before the cursor line
 * @param suffixLines Maximum number of lines to include after the cursor line
 */
fun buildWindowedFileContext(
    fileContent: String,
    cursorLine: Int,
    cursorColumn: Int,
    prefixLines: Int = 512,
    suffixLines: Int = 128
): FileContext {
    val lines = fileContent.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val windowStart = maxOf(0, cursorLine - prefixLines)
    val windowEnd = minOf(lines.size, cursorLine + 1 + suffixLines)
    val safeWindowStart = minOf(windowStart, windowEnd)
    return buildFileContextFromLines(lines, cursorLine, cursorColumn, safeWindowStart, windowEnd)
}

/**
 * Decision function: if the file fits within [maxFileTokens], use whole file; otherwise use windowed.
 */
fun buildContext(
    fileContent: String,
    cursorLine: Int,
    cursorColumn: Int,
    maxFileTokens: Int = 10000,
    prefixLines: Int = 512,
    suffixLines: Int = 128
): FileContext {
    return if (estimateTokens(fileContent) <= maxFileTokens) {
        buildFileContext(fileContent, cursorLine, cursorColumn)
    } else {
        buildWindowedFileContext(fileContent, cursorLine, cursorColumn, prefixLines, suffixLines)
    }
}

private fun buildFileContextFromLines(
    lines: List<String>,
    cursorLine: Int,
    cursorColumn: Int,
    windowStart: Int,
    windowEnd: Int
): FileContext {
    val safeCursorLine = cursorLine.coerceAtLeast(0)
    val safeCursorColumn = cursorColumn.coerceAtLeast(0)

    // clampedCursorLine can equal `lines.size` (one past last valid index) when cursor is OOB.
    // The guard `if (clampedCursorLine < lines.size)` handles this case.
    val clampedCursorLine = minOf(safeCursorLine, lines.size)
    val currentLine = if (clampedCursorLine < lines.size) lines[clampedCursorLine] else ""
    val isWhitespaceOnly = currentLine.isBlank()

    // When the current line is whitespace-only, prompt is empty and nIndent is 0 so the model
    // generates from scratch for this line. The full whitespace line goes into afterCursor
    // (suffix) — this matches llama.vim behavior where whitespace-only lines are treated as
    // cursor at position 0.
    // nIndent is derived from the line's leading whitespace, NOT the cursor column.
    // This differs from llama.vim (which uses cursor column) but is correct for our use case:
    // when cursor is at col 0 on "    indented", nIndent=4 tells the server to generate text
    // at the correct indentation level for that line, even though the cursor hasn't passed the
    // indentation yet. Using cursor column (0) would generate unindented code.
    val nIndent = if (isWhitespaceOnly) {
        0
    } else {
        currentLine.takeWhile { it == ' ' || it == '\t' }.length
    }

    // inputPrefix: lines from windowStart to cursorLine (exclusive), joined with \n, trailing \n.
    // clampedCursorLine is the correct upper bound for prefix slicing because all lines before
    // the cursor line belong to the prefix, and clampedCursorLine is already bounded by lines.size.
    val prefixContent = if (clampedCursorLine > windowStart) {
        lines.subList(windowStart, clampedCursorLine).joinToString("\n") + "\n"
    } else {
        ""
    }

    val clampedCol = minOf(safeCursorColumn, currentLine.length)
    val prompt = if (isWhitespaceOnly) "" else currentLine.substring(0, clampedCol)
    val afterCursor = if (isWhitespaceOnly) currentLine else currentLine.substring(clampedCol)
    val belowLines = if (clampedCursorLine + 1 < windowEnd) {
        lines.subList(clampedCursorLine + 1, windowEnd).joinToString("\n")
    } else {
        null
    }
    val inputSuffix = when {
        afterCursor.isEmpty() && belowLines == null -> ""
        afterCursor.isEmpty() && belowLines != null -> "\n$belowLines"
        belowLines == null -> afterCursor
        else -> "$afterCursor\n$belowLines"
    }

    return FileContext(
        inputPrefix = prefixContent,
        inputSuffix = inputSuffix,
        prompt = prompt,
        nIndent = nIndent
    )
}
