package com.github.lucatume.completamente.completion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.github.lucatume.completamente.services.Chunk
import com.github.lucatume.completamente.services.DiffEntry
import java.net.HttpURLConnection
import java.net.URI

// --- Data classes ---

@Serializable
data class CompletionRequestBody(
    val prompt: String,
    @SerialName("n_predict") val nPredict: Int,
    val temperature: Double,
    val stop: List<String>,
    val stream: Boolean,
    @SerialName("cache_prompt") val cachePrompt: Boolean = true
)

@Serializable
data class CompletionResponseBody(
    val content: String
)

data class EditRegion(
    val startOffset: Int,
    val endOffset: Int,
    val newText: String
)

sealed class EditKind {
    data class Inline(val editRegion: EditRegion) : EditKind()
    data class Jump(val editRegion: EditRegion) : EditKind()
    data object Suppress : EditKind()
}

data class FimRequest(
    val filePath: String,
    val currentContent: String,
    val originalContent: String = "",
    val cursorLine: Int,
    val windowedContent: String = "",
    val windowStartLine: Int = 0,
    val recentDiffs: List<DiffEntry> = emptyList(),
    val chunks: List<Chunk> = emptyList(),
    val structureFiles: List<Chunk> = emptyList(),
    val windowStructureFiles: List<Chunk> = emptyList()
)

data class FimResponse(
    val content: String,
    val error: String? = null
)

// --- Sweepai limits ---

const val MAX_DIFF_CHARS = 20_000
const val MAX_FILE_CHUNK_LINES = 60
const val MAX_FILE_CHARACTERS = 500_000
const val MAX_FILE_LINES = 10_000
const val MAX_AVG_LINE_LENGTH = 500
const val MAX_RECENT_DIFFS_IN_PROMPT = 6
const val MAX_RING_CHUNKS_IN_PROMPT = 6

private val json = Json { ignoreUnknownKeys = true }

fun truncateDiffText(text: String): String {
    if (text.length <= MAX_DIFF_CHARS) return text
    return text.substring(0, MAX_DIFF_CHARS) + "...[truncated]"
}

fun isFileTooLarge(text: String): Boolean {
    if (text.length > MAX_FILE_CHARACTERS) return true
    val lines = text.lines()
    if (lines.size > MAX_FILE_LINES) return true
    if (lines.isNotEmpty()) {
        val avgLength = text.length.toDouble() / lines.size
        if (avgLength > MAX_AVG_LINE_LENGTH) return true
    }
    return false
}

fun buildLineWindowWithStart(lines: List<String>, focusLine: Int, maxLines: Int = MAX_FILE_CHUNK_LINES): Pair<String, Int> {
    if (lines.size <= maxLines) return Pair(lines.joinToString("\n"), 0)

    val above = (maxLines * 3) / 4
    val start = (focusLine - above).coerceAtLeast(0)
    val end = (start + maxLines).coerceAtMost(lines.size)
    val adjustedStart = (end - maxLines).coerceAtLeast(0)

    return Pair(lines.subList(adjustedStart, end).joinToString("\n"), adjustedStart)
}

fun buildLineWindow(lines: List<String>, focusLine: Int, maxLines: Int = MAX_FILE_CHUNK_LINES): String {
    return buildLineWindowWithStart(lines, focusLine, maxLines).first
}

// --- Offset helpers ---

fun computeWindowStartOffset(fullText: String, windowStartLine: Int): Int {
    if (windowStartLine <= 0) return 0
    var offset = 0
    var line = 0
    for (ch in fullText) {
        if (line >= windowStartLine) return offset
        if (ch == '\n') line++
        offset++
    }
    return offset
}

fun computeOffsetInWindow(windowedContent: String, lineInWindow: Int): Int {
    if (lineInWindow <= 0) return 0
    var offset = 0
    var line = 0
    for (ch in windowedContent) {
        if (line >= lineInWindow) return offset
        if (ch == '\n') line++
        offset++
    }
    return offset
}

// --- Token estimation ---

fun estimateTokens(text: String): Int = (text.length + 2) / 3

// --- Prompt & request ---

fun buildFimRequest(
    filePath: String,
    currentContent: String,
    cursorLine: Int,
    originalContent: String = "",
    recentDiffs: List<DiffEntry> = emptyList(),
    chunks: List<Chunk> = emptyList(),
    structureFiles: List<Chunk> = emptyList()
): FimRequest {
    val lines = currentContent.lines()
    val (windowedContent, windowStartLine) = buildLineWindowWithStart(lines, cursorLine)
    return FimRequest(
        filePath = filePath,
        currentContent = currentContent,
        originalContent = originalContent,
        cursorLine = cursorLine,
        windowedContent = windowedContent,
        windowStartLine = windowStartLine,
        recentDiffs = recentDiffs,
        chunks = chunks,
        structureFiles = structureFiles
    )
}

fun buildFimPrompt(request: FimRequest, tokenBudget: Int = 7680): String {
    val windowedContent = if (request.windowedContent.isNotEmpty()) {
        request.windowedContent
    } else {
        buildLineWindow(request.currentContent.lines(), request.cursorLine)
    }

    val windowedOriginal = if (request.originalContent.isNotEmpty()) {
        buildLineWindow(request.originalContent.lines(), request.cursorLine)
    } else {
        ""
    }

    val originalSection = "<|file_sep|>original/${request.filePath}\n$windowedOriginal"
    val currentSection = "<|file_sep|>current/${request.filePath}\n$windowedContent"
    val updatedMarker = "<|file_sep|>updated/${request.filePath}"

    val mandatoryTokens = estimateTokens(originalSection) +
            estimateTokens(currentSection) +
            estimateTokens(updatedMarker)
    var remaining = tokenBudget - mandatoryTokens

    val includedDiffs = mutableListOf<DiffEntry>()
    for (diff in request.recentDiffs.take(MAX_RECENT_DIFFS_IN_PROMPT)) {
        if (diff.estimatedTokens > remaining) break
        includedDiffs.add(diff)
        remaining -= diff.estimatedTokens
    }

    val includedStructure = mutableListOf<Chunk>()
    for (chunk in request.structureFiles) {
        if (chunk.estimatedTokens > remaining) break
        includedStructure.add(chunk)
        remaining -= chunk.estimatedTokens
    }

    val includedWindowStructure = mutableListOf<Chunk>()
    for (chunk in request.windowStructureFiles) {
        if (chunk.estimatedTokens > remaining) break
        includedWindowStructure.add(chunk)
        remaining -= chunk.estimatedTokens
    }

    val includedRing = mutableListOf<Chunk>()
    for (chunk in request.chunks.take(MAX_RING_CHUNKS_IN_PROMPT)) {
        if (chunk.estimatedTokens > remaining) break
        includedRing.add(chunk)
        remaining -= chunk.estimatedTokens
    }

    val parts = mutableListOf<String>()

    for (chunk in includedStructure) {
        parts.add("<|file_sep|>${chunk.filename}")
        parts.add(chunk.text)
    }

    for (chunk in includedWindowStructure) {
        parts.add("<|file_sep|>${chunk.filename}")
        parts.add(chunk.text)
    }

    for (chunk in includedRing) {
        parts.add("<|file_sep|>${chunk.filename}")
        parts.add(chunk.text)
    }

    for (diff in includedDiffs) {
        parts.add("<|file_sep|>${diff.filePath}.diff")
        parts.add("original:")
        parts.add(diff.original)
        parts.add("updated:")
        parts.add(diff.updated)
    }

    parts.add(originalSection)
    parts.add(currentSection)
    parts.add(updatedMarker)

    return parts.joinToString("\n")
}

fun requestFimCompletion(serverUrl: String, request: FimRequest): FimResponse {
    val prompt = buildFimPrompt(request)
    val body = CompletionRequestBody(
        prompt = prompt,
        nPredict = 512,
        temperature = 0.0,
        stop = listOf("<|file_sep|>", "</s>"),
        stream = false
    )

    return try {
        val url = URI("$serverUrl/completion").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 5_000
        connection.readTimeout = 30_000
        connection.doOutput = true

        try {
            connection.outputStream.use { os ->
                os.write(json.encodeToString(body).toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return FimResponse(content = "", error = "Server returned HTTP $responseCode")
            }

            val responseText = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val parsed = json.decodeFromString<CompletionResponseBody>(responseText)

            FimResponse(content = parsed.content)
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        FimResponse(content = "", error = e.message ?: "Unknown error")
    }
}

// --- Edit extraction ---

fun extractEdit(
    windowedCurrentContent: String,
    updatedContent: String,
    cursorLineInWindow: Int
): EditKind {
    val trimmed = updatedContent.trimStart('\n').trimEnd()
    if (trimmed.isEmpty()) return EditKind.Suppress

    val trimmedCurrent = windowedCurrentContent.trimEnd()
    if (trimmed == trimmedCurrent) return EditKind.Suppress

    val currentLines = trimmedCurrent.lines()
    val updatedLines = trimmed.lines()

    // Find first differing line from the top.
    var firstDiff = 0
    while (firstDiff < currentLines.size && firstDiff < updatedLines.size
        && currentLines[firstDiff] == updatedLines[firstDiff]
    ) {
        firstDiff++
    }

    // Find first differing line from the bottom.
    var currentBottom = currentLines.size - 1
    var updatedBottom = updatedLines.size - 1
    while (currentBottom >= firstDiff && updatedBottom >= firstDiff
        && currentLines[currentBottom] == updatedLines[updatedBottom]
    ) {
        currentBottom--
        updatedBottom--
    }

    // If no actual diff found.
    if (firstDiff > updatedBottom && firstDiff > currentBottom) return EditKind.Suppress

    // Special case: when the cursor line is being modified and the updated version
    // merely extends the current content (ignoring leading whitespace from the model),
    // produce a suffix insertion at the end of the current cursor line rather than
    // replacing the whole line. This avoids visual artifacts when the model normalizes
    // whitespace differently from the editor.
    if (firstDiff == cursorLineInWindow && firstDiff < currentLines.size && firstDiff < updatedLines.size) {
        val currentLine = currentLines[firstDiff]
        val updatedLine = updatedLines[firstDiff]
        val currentTrimmed = currentLine.trimStart()
        val updatedTrimmed = updatedLine.trimStart()

        if (currentTrimmed.isNotEmpty() && updatedTrimmed.startsWith(currentTrimmed)
            && updatedTrimmed.length > currentTrimmed.length
        ) {
            val suffix = updatedTrimmed.substring(currentTrimmed.length)
            val lineStartOffset = computeOffsetInWindow(windowedCurrentContent, firstDiff)
            val cursorLineEndOffset = lineStartOffset + currentLine.length

            // End of the replaced region in current content (same logic as the main path).
            val replaceEndOffset = if (currentBottom >= firstDiff) {
                val afterLastLine = computeOffsetInWindow(windowedCurrentContent, currentBottom + 1)
                if (currentBottom + 1 < currentLines.size) afterLastLine
                else trimmedCurrent.length
            } else {
                cursorLineEndOffset
            }

            // Additional new lines beyond the cursor line.
            val additionalLines = if (firstDiff + 1 <= updatedBottom) {
                updatedLines.subList(firstDiff + 1, updatedBottom + 1)
            } else {
                emptyList()
            }

            val allParts = mutableListOf(suffix)
            allParts.addAll(additionalLines)
            val joined = allParts.joinToString("\n")

            val newText = if (replaceEndOffset > cursorLineEndOffset) {
                // Replacing content after cursor (e.g., the newline), preserve line structure.
                joined + "\n"
            } else {
                // Pure insertion at cursor position.
                joined
            }

            val region = EditRegion(cursorLineEndOffset, replaceEndOffset, newText)
            return EditKind.Inline(region)
        }
    }

    // Determine if edit is near cursor (inline) or far (jump).
    val isFarFromCursor = kotlin.math.abs(firstDiff - cursorLineInWindow) > 2

    // Compute startOffset: byte offset of firstDiff line start in windowed content.
    val startOffset = computeOffsetInWindow(windowedCurrentContent, firstDiff)

    // Compute endOffset: byte offset after the last changed line in current content.
    val endOffset = if (currentBottom >= firstDiff) {
        // There are lines being replaced: end is after currentBottom line.
        val afterLastLine = computeOffsetInWindow(windowedCurrentContent, currentBottom + 1)
        if (currentBottom + 1 < currentLines.size) {
            // There are lines after the changed region; endOffset is start of next line.
            afterLastLine
        } else {
            // Changed region goes to end of content.
            trimmedCurrent.length
        }
    } else {
        // Pure insertion: startOffset == endOffset.
        startOffset
    }

    // Assemble new text from updated lines in the diff range.
    val newLines = if (firstDiff <= updatedBottom) {
        updatedLines.subList(firstDiff, updatedBottom + 1)
    } else {
        emptyList()
    }

    val newText = if (newLines.isEmpty()) {
        ""
    } else {
        val joined = newLines.joinToString("\n")
        if (firstDiff >= currentLines.size) {
            // Appending after the last line: need a leading newline.
            "\n" + joined
        } else if (currentBottom + 1 < currentLines.size && endOffset > startOffset) {
            // Replacing lines with more lines after: add trailing newline to match line structure.
            joined + "\n"
        } else if (firstDiff <= updatedBottom && currentBottom < firstDiff && currentBottom + 1 < currentLines.size) {
            // Pure insertion before existing lines.
            joined + "\n"
        } else {
            joined
        }
    }

    if (newText.isEmpty() && startOffset == endOffset) return EditKind.Suppress

    val region = EditRegion(startOffset, endOffset, newText)
    return if (isFarFromCursor) EditKind.Jump(region) else EditKind.Inline(region)
}

// --- Sub-edit splitting ---

/**
 * Splits a single EditRegion into multiple smaller sub-edits by comparing the old text
 * and new text line by line. Each contiguous group of differing lines becomes a sub-edit.
 * For single-line sub-edits, character-level refinement narrows the replacement range.
 *
 * @param oldText the text currently in the document at [baseOffset..baseOffset+oldText.length]
 * @param newText the replacement text for that region
 * @param baseOffset the document offset where oldText starts
 * @return list of EditRegion sub-edits; single-element list if splitting is not possible
 */
fun splitEditIntoSubEdits(oldText: String, newText: String, baseOffset: Int): List<EditRegion> {
    // Pure insertion: nothing to split.
    if (oldText.isEmpty()) {
        return listOf(EditRegion(baseOffset, baseOffset, newText))
    }

    val oldLines = oldText.lines()
    val newLines = newText.lines()

    // Different line counts: fall back to single edit (safe default).
    if (oldLines.size != newLines.size) {
        return listOf(EditRegion(baseOffset, baseOffset + oldText.length, newText))
    }

    // Walk lines in lockstep, group consecutive differing lines into sub-edits.
    data class DiffGroup(val firstLine: Int, val lastLine: Int)

    val groups = mutableListOf<DiffGroup>()
    var i = 0
    while (i < oldLines.size) {
        if (oldLines[i] != newLines[i]) {
            val start = i
            while (i < oldLines.size && oldLines[i] != newLines[i]) {
                i++
            }
            groups.add(DiffGroup(start, i - 1))
        } else {
            i++
        }
    }

    if (groups.isEmpty()) {
        return emptyList()
    }

    // Convert groups to EditRegions.
    // Precompute line start offsets relative to baseOffset.
    val lineStartOffsets = IntArray(oldLines.size + 1)
    lineStartOffsets[0] = 0
    for (idx in oldLines.indices) {
        lineStartOffsets[idx + 1] = lineStartOffsets[idx] + oldLines[idx].length + if (idx < oldLines.size - 1) 1 else 0
    }

    return groups.map { group ->
        val groupStartOffset = baseOffset + lineStartOffsets[group.firstLine]
        val groupEndOffset = baseOffset + lineStartOffsets[group.lastLine] + oldLines[group.lastLine].length

        val groupNewText = (group.firstLine..group.lastLine).joinToString("\n") { newLines[it] }

        // For single-line sub-edits, refine to character level.
        if (group.firstLine == group.lastLine) {
            val oldLine = oldLines[group.firstLine]
            val newLine = newLines[group.firstLine]
            val commonPrefix = oldLine.zip(newLine).takeWhile { (a, b) -> a == b }.size
            val oldSuffix = oldLine.substring(commonPrefix)
            val newSuffix = newLine.substring(commonPrefix)
            val commonSuffix = oldSuffix.reversed().zip(newSuffix.reversed()).takeWhile { (a, b) -> a == b }.size

            val charStart = groupStartOffset + commonPrefix
            val charEnd = groupEndOffset - commonSuffix
            val charNewText = newLine.substring(commonPrefix, newLine.length - commonSuffix)

            EditRegion(charStart, charEnd, charNewText)
        } else {
            EditRegion(groupStartOffset, groupEndOffset, groupNewText)
        }
    }
}

/**
 * Returns a new list of edits with offsets adjusted after applying the edit at [currentIndex].
 * Edits before and at [currentIndex] are kept as-is. Edits after are shifted by the size
 * delta of [appliedEdit].
 */
fun advanceSubEdit(edits: List<EditRegion>, currentIndex: Int, appliedEdit: EditRegion): List<EditRegion> {
    val sizeDelta = appliedEdit.newText.length - (appliedEdit.endOffset - appliedEdit.startOffset)
    return edits.mapIndexed { idx, edit ->
        if (idx <= currentIndex) {
            edit
        } else {
            EditRegion(
                startOffset = edit.startOffset + sizeDelta,
                endOffset = edit.endOffset + sizeDelta,
                newText = edit.newText
            )
        }
    }
}
