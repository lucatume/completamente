package com.github.lucatume.completamente.completion

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor

/**
 * Resolves project files referenced by symbols in the given line range of a PSI file.
 * Walks PSI elements, resolves references, filters to project-local files, deduplicates.
 *
 * @param psiFile The file to scan for references
 * @param startLine 0-based inclusive start line
 * @param endLine 0-based exclusive end line
 * @return Set of VirtualFiles referenced within the line range, excluding the file itself and library files
 */
fun collectReferencedFiles(
    psiFile: PsiFile,
    startLine: Int,
    endLine: Int
): Set<VirtualFile> {
    val project = psiFile.project
    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return emptySet()
    val lineCount = document.lineCount
    if (lineCount == 0 || startLine >= lineCount || startLine >= endLine) return emptySet()

    val clampedStart = startLine.coerceAtLeast(0)
    val clampedEnd = endLine.coerceAtMost(lineCount)
    val startOffset = document.getLineStartOffset(clampedStart)
    val endOffset = document.getLineEndOffset((clampedEnd - 1).coerceAtLeast(0))

    val currentFile = psiFile.virtualFile
    val fileIndex = ProjectFileIndex.getInstance(project)
    val result = mutableSetOf<VirtualFile>()

    psiFile.accept(object : PsiRecursiveElementVisitor() {
        override fun visitElement(element: PsiElement) {
            val range = element.textRange ?: return
            // Skip elements entirely outside our range
            if (range.endOffset < startOffset || range.startOffset > endOffset) return

            // Only process leaf-ish elements for references
            for (ref in element.references) {
                val resolved = ref.resolve() ?: continue
                val resolvedFile = resolved.containingFile?.virtualFile ?: continue
                if (resolvedFile == currentFile) continue
                if (!fileIndex.isInContent(resolvedFile)) continue
                result.add(resolvedFile)
            }

            super.visitElement(element)
        }
    })

    return result
}

/**
 * States for the character-scanning state machine used by [surfaceExtract].
 * Only braces encountered in [NORMAL] state are counted for depth tracking.
 */
private enum class ScanState {
    NORMAL,
    IN_STRING,
    IN_CHAR,
    IN_LINE_COMMENT,
    IN_BLOCK_COMMENT,
    IN_MULTILINE_STRING
}

/**
 * Pattern that matches container declarations: zero or more modifier words followed by
 * class/object/interface/enum as a standalone keyword. Anchored at start of line so
 * `val classLoader` does NOT match (since `classLoader` is one word, not `class` + space).
 */
private val containerPattern = Regex(
    """^(\w+\s+)*(class|object|interface|enum)\s"""
)

/**
 * Pattern that matches function or property declarations that should NOT be treated as containers.
 */
private val funcOrPropPrefix = Regex(
    """^(override |suspend |inline |private |internal |protected |public |actual |expect |external |tailrec |operator |infix )*(fun |val |var ).*"""
)

/**
 * Checks whether a trimmed line declares a container type (class, object, interface, enum).
 * Modifier keywords may precede the container keyword.
 */
private fun isContainerDeclaration(trimmedLine: String): Boolean {
    return containerPattern.containsMatchIn(trimmedLine) &&
        !funcOrPropPrefix.containsMatchIn(trimmedLine)
}

/**
 * Surface-extracts public signatures from a file via PSI tree walking.
 * Returns a compact string of class/function/property declarations without method bodies.
 * Uses a language-agnostic heuristic: for each top-level declaration, takes the first line
 * (which typically contains the class name, function signature, or property declaration).
 * For declarations with bodies, includes the opening line up to and including the opening brace.
 *
 * A state machine skips braces inside string literals, char literals, line comments,
 * block comments, and multiline strings so they do not corrupt depth tracking.
 *
 * @param project The current project
 * @param file The file to extract structure from
 * @return Extracted signatures as a string, or null if extraction fails
 */
fun surfaceExtract(
    project: Project,
    file: VirtualFile
): String? {
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
    val text = psiFile.text
    if (text.isBlank()) return null

    val lines = text.lines()
    val result = StringBuilder()

    // Heuristic: track brace depth and whether the construct that opened
    // each depth level is a "container" (class/object/interface/enum) or a
    // "body" (function/method). We include lines at depth 0 (top-level) and
    // depth 1 when inside a container (member signatures). We skip lines
    // inside function/method bodies at any depth.
    var depth = 0
    // Stack tracking whether each brace-depth level is a container.
    // true = container (class/object/interface/enum), false = function body
    val containerStack = mutableListOf<Boolean>()

    // Persistent state machine state (block comments and multiline strings span lines)
    var state = ScanState.NORMAL

    for (line in lines) {
        val trimmed = line.trim()

        // Always include package and import lines regardless of depth.
        // Don't skip the state machine — a package/import line could theoretically
        // contain a block comment opener (e.g. "import foo.Bar /* unusual").
        val isPackageOrImport = trimmed.startsWith("package ") || trimmed.startsWith("import ")

        // Check if we are already inside a block comment or multiline string
        // from a previous line. Lines that START in these states should be
        // suppressed regardless of depth.
        val inCommentOrString = state == ScanState.IN_BLOCK_COMMENT || state == ScanState.IN_MULTILINE_STRING

        val depthBefore = depth

        // Determine if this line is a signature we should include.
        val inContainerBody = depthBefore > 0 && containerStack.all { it }
        val isSurface = depthBefore == 0 || inContainerBody

        // Detect if this line opens a container (class/object/interface/enum)
        val isContainer = isContainerDeclaration(trimmed)

        // Track whether we have seen the first brace on this line
        var firstBraceOnLine = true

        // Scan characters with state machine
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            val next = if (i + 1 < trimmed.length) trimmed[i + 1] else '\u0000'

            when (state) {
                ScanState.NORMAL -> {
                    when {
                        // Multiline string (""") must be checked before single "
                        ch == '"' && next == '"' && i + 2 < trimmed.length && trimmed[i + 2] == '"' -> {
                            state = ScanState.IN_MULTILINE_STRING
                            i += 3
                            continue
                        }
                        ch == '"' -> {
                            state = ScanState.IN_STRING
                            i++
                            continue
                        }
                        ch == '\'' -> {
                            state = ScanState.IN_CHAR
                            i++
                            continue
                        }
                        ch == '/' && next == '/' -> {
                            // Rest of line is a comment; skip entirely
                            state = ScanState.IN_LINE_COMMENT
                            break
                        }
                        ch == '/' && next == '*' -> {
                            state = ScanState.IN_BLOCK_COMMENT
                            i += 2
                            continue
                        }
                        ch == '{' -> {
                            val pushContainer = if (firstBraceOnLine) isContainer else false
                            containerStack.add(pushContainer)
                            depth++
                            firstBraceOnLine = false
                        }
                        ch == '}' -> {
                            depth--
                            if (depth < 0) {
                                // Negative depth: reset both depth and stack
                                depth = 0
                                containerStack.clear()
                            } else if (containerStack.isNotEmpty()) {
                                containerStack.removeAt(containerStack.size - 1)
                            }
                        }
                    }
                }
                ScanState.IN_STRING -> {
                    when {
                        ch == '\\' -> { i += 2; continue } // skip escaped char
                        ch == '"' -> state = ScanState.NORMAL
                    }
                }
                ScanState.IN_CHAR -> {
                    when {
                        ch == '\\' -> { i += 2; continue } // skip escaped char
                        ch == '\'' -> state = ScanState.NORMAL
                    }
                }
                ScanState.IN_LINE_COMMENT -> {
                    // Handled at end of loop; should not reach here
                    break
                }
                ScanState.IN_BLOCK_COMMENT -> {
                    if (ch == '*' && next == '/') {
                        state = ScanState.NORMAL
                        i += 2
                        continue
                    }
                }
                ScanState.IN_MULTILINE_STRING -> {
                    if (ch == '"' && next == '"' && i + 2 < trimmed.length && trimmed[i + 2] == '"') {
                        state = ScanState.NORMAL
                        i += 3
                        continue
                    }
                }
            }
            i++
        }

        // Line comments end at newline
        if (state == ScanState.IN_LINE_COMMENT) {
            state = ScanState.NORMAL
        }

        // Package/import lines are always included. Other lines are included only
        // if they are at surface depth and not inside a block comment/multiline string.
        val shouldInclude = isPackageOrImport || (isSurface && !inCommentOrString)
        if (shouldInclude) {
            if (trimmed.isNotEmpty()) {
                result.appendLine(line)
            } else if (depthBefore == 0) {
                result.appendLine()
            }
        }
    }

    val extracted = result.toString().trim()
    return extracted.ifEmpty { null }
}

/**
 * High-level: resolves referenced project files and extracts their structure as InfillExtraChunks.
 *
 * @param psiFile The current file being edited
 * @param wholeFile If true, scan the entire file for references. If false, use header+window.
 * @param windowStartLine 0-based start of the cursor window (used when wholeFile=false)
 * @param windowEndLine 0-based end of the cursor window (used when wholeFile=false)
 * @param headerLines Number of lines from the top of the file to scan for imports (used when wholeFile=false)
 * @return List of InfillExtraChunk sorted by file path for stable input_extra ordering
 */
fun buildStructureChunks(
    psiFile: PsiFile,
    wholeFile: Boolean,
    windowStartLine: Int = 0,
    windowEndLine: Int = 0,
    headerLines: Int = 32
): List<InfillExtraChunk> {
    val project = psiFile.project
    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return emptyList()
    val lineCount = document.lineCount

    if (lineCount == 0) return emptyList()

    val referencedFiles: Set<VirtualFile> = if (wholeFile) {
        collectReferencedFiles(psiFile, 0, lineCount)
    } else {
        val headerEnd = headerLines.coerceAtMost(lineCount)
        val headerRefs = collectReferencedFiles(psiFile, 0, headerEnd)
        val windowRefs = if (windowStartLine < windowEndLine) {
            collectReferencedFiles(psiFile, windowStartLine, windowEndLine)
        } else {
            emptySet()
        }
        headerRefs + windowRefs
    }

    if (referencedFiles.isEmpty()) return emptyList()

    val basePath = project.basePath ?: ""

    return referencedFiles
        .mapNotNull { file ->
            val extracted = surfaceExtract(project, file) ?: return@mapNotNull null
            if (extracted.isBlank()) return@mapNotNull null
            val relativePath = if (basePath.isNotEmpty()) {
                file.path.removePrefix("$basePath/")
            } else {
                file.path
            }
            InfillExtraChunk(filename = relativePath, text = extracted)
        }
        .sortedBy { it.filename }
}
