package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Chunk
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

const val MAX_DEFINITION_CHUNKS = 6
const val DEFINITION_CONTEXT_LINES = 19  // 9 above + target + 9 below

data class DefinitionLocation(
    val filePath: String,
    val line: Int,
    val documentText: String,
    val totalLines: Int
)

/**
 * Resolves cross-file definition chunks by finding the symbol at [cursorOffset],
 * resolving its references, and extracting ±9 line windows around each definition site.
 */
fun resolveDefinitionChunks(
    project: Project,
    psiFile: PsiFile,
    cursorOffset: Int,
    currentFilePath: String
): List<Chunk> {
    val locations = collectDefinitionLocationsAtOffset(project, psiFile, cursorOffset, currentFilePath)
    return mergeAndExtractDefinitionChunks(locations)
}

/**
 * Phase A: Find the PSI element at [cursorOffset], walk up the parent chain (up to 3 levels)
 * looking for resolvable references, and collect definition locations deduplicated by (filePath, line).
 */
fun collectDefinitionLocationsAtOffset(
    project: Project,
    psiFile: PsiFile,
    cursorOffset: Int,
    currentFilePath: String
): List<DefinitionLocation> {
    val seen = mutableSetOf<Pair<String, Int>>()
    val locations = mutableListOf<DefinitionLocation>()
    val psiDocManager = PsiDocumentManager.getInstance(project)

    var element = psiFile.findElementAt(cursorOffset)
    var depth = 0

    while (element != null && depth < 3) {
        try {
            val references = element.references
            for (ref in references) {
                try {
                    val resolved = ref.resolve() ?: continue
                    val containingFile = resolved.containingFile ?: continue
                    val virtualFile = containingFile.virtualFile ?: continue
                    if (!virtualFile.isInLocalFileSystem) continue
                    val resolvedPath = virtualFile.path
                    if (resolvedPath == currentFilePath) continue

                    val document = psiDocManager.getDocument(containingFile) ?: continue
                    val line = document.getLineNumber(resolved.textOffset)
                    val key = Pair(resolvedPath, line)
                    if (seen.add(key)) {
                        locations.add(
                            DefinitionLocation(
                                filePath = resolvedPath,
                                line = line,
                                documentText = document.text,
                                totalLines = document.lineCount
                            )
                        )
                    }
                } catch (_: Exception) {
                    // Skip individual reference failures.
                }
            }
        } catch (_: Exception) {
            // Skip element failures.
        }
        element = element.parent
        depth++
    }

    return locations
}

/**
 * Phase B: Group locations by file, merge overlapping ±9 line windows, and extract chunks.
 * Caps at [MAX_DEFINITION_CHUNKS] total chunks.
 */
fun mergeAndExtractDefinitionChunks(locations: List<DefinitionLocation>): List<Chunk> {
    if (locations.isEmpty()) return emptyList()

    val chunks = mutableListOf<Chunk>()
    val grouped = locations.groupBy { it.filePath }

    for ((filePath, fileLocations) in grouped) {
        if (chunks.size >= MAX_DEFINITION_CHUNKS) break

        val sorted = fileLocations.sortedBy { it.line }
        val docText = sorted.first().documentText
        val totalLines = sorted.first().totalLines

        // Merge overlapping windows.
        val windows = mutableListOf<Pair<Int, Int>>() // (startLine, endLine) inclusive
        var currentStart = (sorted[0].line - 9).coerceAtLeast(0)
        var currentEnd = (sorted[0].line + 9).coerceAtMost(totalLines - 1)

        for (i in 1 until sorted.size) {
            val nextStart = (sorted[i].line - 9).coerceAtLeast(0)
            val nextEnd = (sorted[i].line + 9).coerceAtMost(totalLines - 1)

            if (nextStart <= currentEnd) {
                // Overlapping, extend.
                currentEnd = maxOf(currentEnd, nextEnd)
            } else {
                windows.add(Pair(currentStart, currentEnd))
                currentStart = nextStart
                currentEnd = nextEnd
            }
        }
        windows.add(Pair(currentStart, currentEnd))

        // Extract text for each merged window.
        for ((winStart, winEnd) in windows) {
            if (chunks.size >= MAX_DEFINITION_CHUNKS) break

            val text = extractLinesFromText(docText, winStart, winEnd)
            if (text.isNotBlank()) {
                chunks.add(Chunk(text = text, time = System.currentTimeMillis(), filename = filePath, estimatedTokens = estimateTokens("<|file_sep|>$filePath\n$text")))
            }
        }
    }

    return chunks
}

/**
 * Extracts lines [startLine..endLine] (inclusive) from the given text.
 */
fun extractLinesFromText(text: String, startLine: Int, endLine: Int): String {
    val lines = text.lines()
    val safeStart = startLine.coerceAtLeast(0)
    val safeEnd = endLine.coerceAtMost(lines.size - 1)
    if (safeStart > safeEnd) return ""
    return lines.subList(safeStart, safeEnd + 1).joinToString("\n")
}
