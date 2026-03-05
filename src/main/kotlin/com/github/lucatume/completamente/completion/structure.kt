package com.github.lucatume.completamente.completion

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

const val MAX_STRUCTURE_FILES = 16
const val MAX_HEADER_LINES = 16
const val MAX_WINDOW_STRUCTURE_FILES = 8

fun extractSurface(psiFile: PsiFile, project: Project): String {
    try {
        val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(psiFile)
                as? TreeBasedStructureViewBuilder ?: return extractSurfaceFallback(psiFile)

        val model = builder.createStructureViewModel(null)
        try {
            val result = StringBuilder()
            collectStructureElements(model.root, result, 0)
            val surface = result.toString().trim()
            return surface.ifEmpty { extractSurfaceFallback(psiFile) }
        } finally {
            model.dispose()
        }
    } catch (_: Exception) {
        return extractSurfaceFallback(psiFile)
    }
}

private fun collectStructureElements(
    element: StructureViewTreeElement,
    result: StringBuilder,
    depth: Int
) {
    val psiElement = element.value
    if (psiElement is PsiElement && depth > 0) {
        val text = psiElement.text ?: ""
        val braceIdx = text.indexOf('{')
        val signature = if (braceIdx > 0) text.substring(0, braceIdx).trim() else text.trim()
        if (signature.isNotEmpty()) {
            val indent = "    ".repeat((depth - 1).coerceAtLeast(0))
            result.appendLine("$indent$signature")
        }
    }

    for (child in element.children) {
        if (child is StructureViewTreeElement) {
            collectStructureElements(child, result, depth + 1)
        }
    }
}

fun extractSurfaceFallback(psiFile: PsiFile): String {
    val lines = psiFile.text.lines()
    return lines.take(60).joinToString("\n")
}
