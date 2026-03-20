package com.github.lucatume.completamente.order89

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile

data class FileSearchMatch(val relativePath: String, val lineNumber: Int, val lineContent: String)

fun searchFileContent(text: String, query: String, caseSensitive: Boolean = false): List<Pair<Int, String>> {
    if (text.isEmpty() || query.isEmpty()) return emptyList()
    val lines = text.split("\n")
    val results = mutableListOf<Pair<Int, String>>()
    for ((index, line) in lines.withIndex()) {
        val matches = if (caseSensitive) {
            line.contains(query)
        } else {
            line.contains(query, ignoreCase = true)
        }
        if (matches) {
            results.add(Pair(index + 1, line))
        }
    }
    return results
}

fun formatSearchResults(matches: List<FileSearchMatch>, maxResults: Int = 20): String {
    if (matches.isEmpty()) return "No matches found."
    return matches.take(maxResults).joinToString("\n") { match ->
        "${match.relativePath}:${match.lineNumber}: ${match.lineContent}"
    }
}

object FileSearchTool {
    fun execute(project: Project, query: String, caseSensitive: Boolean = false, path: String? = null): String {
        val basePath = project.basePath ?: ""
        val allMatches = mutableListOf<FileSearchMatch>()

        ProjectFileIndex.getInstance(project).iterateContent { file ->
            if (file.isDirectory || !file.isValid) return@iterateContent true
            if (allMatches.size >= 20) return@iterateContent false

            val relativePath = file.path.removePrefix(basePath).removePrefix("/")

            if (path != null && !relativePath.startsWith(path)) {
                return@iterateContent true
            }

            if (!isTextFile(file)) return@iterateContent true

            try {
                val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                val lineMatches = searchFileContent(content, query, caseSensitive)
                for ((lineNumber, lineContent) in lineMatches) {
                    allMatches.add(FileSearchMatch(relativePath, lineNumber, lineContent))
                    if (allMatches.size >= 20) break
                }
            } catch (_: Exception) {
                // Skip unreadable files
            }

            true
        }

        return formatSearchResults(allMatches)
    }

    private fun isTextFile(file: VirtualFile): Boolean {
        val extension = file.extension?.lowercase() ?: return false
        return extension in setOf(
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "rb", "php",
            "c", "cpp", "h", "hpp", "cs", "swift", "scala", "groovy",
            "xml", "json", "yaml", "yml", "toml", "properties", "ini", "cfg",
            "md", "txt", "html", "css", "scss", "less", "sql", "sh", "bash", "zsh",
            "gradle", "kts", "bat", "ps1", "r", "lua", "pl", "pm"
        )
    }
}

object WebSearchTool {
    fun execute(query: String): String = "WebSearch is not yet implemented."
}
