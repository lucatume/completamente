package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Settings
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.openapi.util.TextRange
import kotlin.math.max
import kotlin.math.min

data class LocalContext(
    val prefix: String,
    val middle: String,
    val suffix: String,
    val indent: Int,
    val lineCur: String,
    val lineCurPrefix: String,
    val lineCurSuffix: String
)

/**
 * Get the local context at the cursor position.
 * Port of s:fim_ctx_local from llama.vim.
 *
 * @param request The InlineCompletionRequest containing document and cursor position.
 * @param settings The Settings object containing nPrefix and nSuffix.
 * @param prev Optional previous completion for this position. If provided, creates
 *             the local context as if the completion was already inserted.
 * @param indentLast The last indentation level that was accepted (used when prev is provided).
 * @return LocalContext containing prefix, middle, suffix, and related data.
 */
fun buildLocalContext(
    request: InlineCompletionRequest,
    settings: Settings,
    prev: List<String>?,
    indentLast: Int = -1
): LocalContext {
    val document = request.document

    /**
     * Adjust the offset depending on the start and end offsets.
     * Stort of file is offset 0.
     * The startOffset is where the cursor was before the user input.
     * The endOffset is where the cursor is after the user input.
     * At the start of a file the first suggestion is for (0,0), the next suggestion is for (0,1).
     * The distance between the two might be larger than 1 to include spaces and new lines added by the IDE.
     */
    val offset = if(request.startOffset != request.endOffset){
        // Typical suggestion.
        request.startOffset + 1
    } else {
        // Either we're at the start (startOffset = 0, endOffset = 0) or we're inside a set of matching parenthesis.
        request.startOffset
    }

    // IntelliJ uses 0-based line numbers
    val maxY = document.lineCount
    val posY = document.getLineNumber(offset)
    val lineStartOffset = document.getLineStartOffset(posY)
    val posX = offset - lineStartOffset

    val lineCur: String
    val lineCurPrefix: String
    val lineCurSuffix: String
    val linesPrefix: List<String>
    val linesSuffix: List<String>
    val indent: Int

    if (prev.isNullOrEmpty()) {
        // Get the current line
        val lineEndOffset = document.getLineEndOffset(posY)
        val currentLineText = document.getText(TextRange(lineStartOffset, lineEndOffset))

        lineCur = currentLineText

        // Get prefix lines (lines before current line)
        val prefixStartLine = max(0, posY - settings.nPrefix)
        linesPrefix = if (posY > 0) {
            (prefixStartLine until posY).map { lineNum ->
                val start = document.getLineStartOffset(lineNum)
                val end = document.getLineEndOffset(lineNum)
                document.getText(TextRange(start, end))
            }
        } else {
            emptyList()
        }

        // Get suffix lines (lines after current line)
        val suffixEndLine = min(maxY - 1, posY + settings.nSuffix)
        linesSuffix = if (posY < maxY - 1) {
            ((posY + 1)..suffixEndLine).map { lineNum ->
                val start = document.getLineStartOffset(lineNum)
                val end = document.getLineEndOffset(lineNum)
                document.getText(TextRange(start, end))
            }
        } else {
            emptyList()
        }

        // Special handling of lines full of whitespaces - start from the beginning of the line
        if (currentLineText.matches(Regex("^\\s*$"))) {
            indent = 0
            lineCurPrefix = ""
            lineCurSuffix = ""
        } else {
            // The indentation of the current line (count leading whitespace)
            indent = currentLineText.takeWhile { it.isWhitespace() }.length
            lineCurPrefix = currentLineText.substring(0, min(posX, currentLineText.length))
            lineCurSuffix = if (posX < currentLineText.length) currentLineText.substring(posX) else ""
        }
    } else {
        // When prev is provided, create context as if the completion was already inserted
        lineCur = if (prev.size == 1) {
            // Single line: append prev to current line
            val lineEndOffset = document.getLineEndOffset(posY)
            val currentLineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
            currentLineText + prev[0]
        } else {
            // Multi-line: the last line of prev becomes the current line
            prev.last()
        }

        lineCurPrefix = lineCur
        lineCurSuffix = ""

        // Adjust prefix lines to account for prev lines
        val prefixStartLine = max(0, posY - settings.nPrefix + prev.size - 1)
        val basePrefix = if (posY > 0) {
            (prefixStartLine until posY).map { lineNum ->
                val start = document.getLineStartOffset(lineNum)
                val end = document.getLineEndOffset(lineNum)
                document.getText(TextRange(start, end))
            }.toMutableList()
        } else {
            mutableListOf()
        }

        if (prev.size > 1) {
            // Add the current line + first part of prev
            val lineEndOffset = document.getLineEndOffset(posY)
            val currentLineText = document.getText(TextRange(lineStartOffset, lineEndOffset))
            basePrefix.add(currentLineText + prev[0])

            // Add middle lines of prev (excluding first and last)
            for (i in 1 until prev.size - 1) {
                basePrefix.add(prev[i])
            }
        }
        linesPrefix = basePrefix

        // Suffix lines remain the same
        val suffixEndLine = min(maxY - 1, posY + settings.nSuffix)
        linesSuffix = if (posY < maxY - 1) {
            ((posY + 1)..suffixEndLine).map { lineNum ->
                val start = document.getLineStartOffset(lineNum)
                val end = document.getLineEndOffset(lineNum)
                document.getText(TextRange(start, end))
            }
        } else {
            emptyList()
        }

        indent = indentLast
    }

    // Build the final strings
    val prefix = linesPrefix.joinToString("\n") + "\n"

    val middle = lineCurPrefix

    val suffix = lineCurSuffix + "\n" + linesSuffix.joinToString("\n") + "\n"

    return LocalContext(
        prefix = prefix,
        middle = middle,
        suffix = suffix,
        indent = indent,
        lineCur = lineCur,
        lineCurPrefix = lineCurPrefix,
        lineCurSuffix = lineCurSuffix
    )
}
