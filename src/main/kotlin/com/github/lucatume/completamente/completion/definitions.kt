package com.github.lucatume.completamente.completion

import com.github.lucatume.completamente.services.Chunk
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

fun collectReferencedFilesFromHeader(
    project: Project,
    psiFile: PsiFile,
    currentFilePath: String,
    maxHeaderLines: Int = MAX_HEADER_LINES
): List<PsiFile> {
    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return emptyList()
    val lineCount = document.lineCount
    val endLine = (maxHeaderLines - 1).coerceAtMost(lineCount - 1)
    if (endLine < 0) return emptyList()
    val endOffset = document.getLineEndOffset(endLine)

    val seenPaths = LinkedHashSet<String>()
    var element = psiFile.findElementAt(0)

    while (element != null && element.textOffset < endOffset) {
        try {
            for (ref in element.references) {
                try {
                    val resolved = ref.resolve() ?: continue
                    val containingFile = resolved.containingFile ?: continue
                    val virtualFile = containingFile.virtualFile ?: continue
                    if (!virtualFile.isInLocalFileSystem) continue
                    if (virtualFile.path == currentFilePath) continue
                    seenPaths.add(virtualFile.path)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        element = PsiTreeUtil.nextLeaf(element)
    }

    val psiManager = PsiManager.getInstance(project)
    return seenPaths.take(MAX_STRUCTURE_FILES).mapNotNull { path ->
        LocalFileSystem.getInstance().findFileByPath(path)?.let {
            psiManager.findFile(it)
        }
    }
}

fun collectReferencedFilesFromWindow(
    project: Project,
    psiFile: PsiFile,
    currentFilePath: String,
    windowStartLine: Int,
    windowEndLine: Int,
    excludePaths: Set<String> = emptySet()
): List<PsiFile> {
    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        ?: return emptyList()
    val lineCount = document.lineCount

    // Scan from max(windowStartLine, MAX_HEADER_LINES) to skip the header region.
    val scanStartLine = windowStartLine.coerceAtLeast(MAX_HEADER_LINES)
    val scanEndLine = windowEndLine.coerceAtMost(lineCount - 1)
    if (scanStartLine > scanEndLine) return emptyList()

    val startOffset = document.getLineStartOffset(scanStartLine)
    val endOffset = document.getLineEndOffset(scanEndLine)

    val seenPaths = LinkedHashSet<String>()
    var element = psiFile.findElementAt(startOffset)

    while (element != null && element.textOffset < endOffset) {
        try {
            for (ref in element.references) {
                try {
                    val resolved = ref.resolve() ?: continue
                    val containingFile = resolved.containingFile ?: continue
                    val virtualFile = containingFile.virtualFile ?: continue
                    if (!virtualFile.isInLocalFileSystem) continue
                    if (virtualFile.path == currentFilePath) continue
                    if (virtualFile.path in excludePaths) continue
                    seenPaths.add(virtualFile.path)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        element = PsiTreeUtil.nextLeaf(element)
    }

    val psiManager = PsiManager.getInstance(project)
    return seenPaths.take(MAX_WINDOW_STRUCTURE_FILES).mapNotNull { path ->
        LocalFileSystem.getInstance().findFileByPath(path)?.let {
            psiManager.findFile(it)
        }
    }
}

fun resolveWindowStructureFiles(
    project: Project,
    psiFile: PsiFile,
    currentFilePath: String,
    windowStartLine: Int,
    windowEndLine: Int,
    headerPaths: Set<String>
): List<Chunk> {
    val referencedFiles = collectReferencedFilesFromWindow(
        project, psiFile, currentFilePath,
        windowStartLine, windowEndLine, headerPaths
    )
    return referencedFiles.map { refFile ->
        val surface = extractSurface(refFile, project)
        val path = refFile.virtualFile?.path ?: refFile.name
        Chunk(
            text = surface,
            time = System.currentTimeMillis(),
            filename = path,
            estimatedTokens = estimateTokens("<|file_sep|>$path\n$surface")
        )
    }
}

fun resolveStructureFiles(
    project: Project,
    psiFile: PsiFile,
    currentFilePath: String
): Pair<List<Chunk>, Set<String>> {
    val referencedFiles = collectReferencedFilesFromHeader(project, psiFile, currentFilePath)
    val headerPaths = referencedFiles.mapNotNull { it.virtualFile?.path }.toSet()
    val chunks = referencedFiles.map { refFile ->
        val surface = extractSurface(refFile, project)
        val path = refFile.virtualFile?.path ?: refFile.name
        Chunk(
            text = surface,
            time = System.currentTimeMillis(),
            filename = path,
            estimatedTokens = estimateTokens("<|file_sep|>$path\n$surface")
        )
    }
    return Pair(chunks, headerPaths)
}

fun collectReferencedFilePaths(
    project: Project,
    psiFile: PsiFile,
    currentFilePath: String
): List<String> {
    return collectReferencedFilesFromHeader(project, psiFile, currentFilePath)
        .mapNotNull { it.virtualFile?.path }
}
