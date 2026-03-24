package com.github.lucatume.completamente.order89

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*

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

fun stripHtml(html: String): String {
    if (html.isEmpty()) return ""
    var text = html
    // Remove script and style blocks
    text = Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL).replace(text, "")
    // Replace block-level tags with newlines for readability
    text = Regex("<(br|p|div|h[1-6]|li|tr|dt|dd)[^>]*/?>", RegexOption.IGNORE_CASE).replace(text, "\n")
    // Remove all remaining tags
    text = Regex("<[^>]+>").replace(text, " ")
    // Decode common HTML entities
    text = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace("&#x27;", "'")
        .replace("&mdash;", "\u2014")
        .replace("&ndash;", "\u2013")
    // Decode numeric entities
    text = Regex("&#(\\d+);").replace(text) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0xFFFF) code.toChar().toString() else ""
    }
    // Collapse whitespace: multiple spaces/tabs to single, preserve newlines
    text = Regex("[ \\t]+").replace(text, " ")
    text = Regex("\\n[ \\t]+").replace(text, "\n")
    text = Regex("\\n{3,}").replace(text, "\n\n")
    text = text.trim()

    // Nav-filtering second pass: remove runs of 3+ consecutive short lines (<=3 words each)
    val lines = text.split("\n")
    val keep = BooleanArray(lines.size) { true }
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.isNotEmpty() && trimmed.split(Regex("\\s+")).size <= 3) {
            var j = i + 1
            while (j < lines.size) {
                val t = lines[j].trim()
                if (t.isEmpty() || t.split(Regex("\\s+")).size > 3) break
                j++
            }
            val runLength = j - i
            if (runLength >= 3) {
                for (k in i until j) keep[k] = false
            }
            i = j
        } else {
            i++
        }
    }
    text = lines.filterIndexed { index, _ -> keep[index] }.joinToString("\n")
    // Re-collapse newlines after filtering
    text = Regex("\\n{3,}").replace(text, "\n\n")
    return text.trim()
}

fun truncateOnWordBoundary(text: String, maxChars: Int = 2000): String {
    if (text.length <= maxChars) return text
    val cutoff = text.lastIndexOf(' ', maxChars)
    return if (cutoff > 0) text.substring(0, cutoff) + " [...]" else text.substring(0, maxChars) + " [...]"
}

data class DashDocResult(val name: String, val type: String, val docset: String, val loadUrl: String)

fun formatDocResults(results: List<DashDocResult>, contentByUrl: Map<String, String>, maxCharsPerResult: Int = 2000): String {
    if (results.isEmpty()) return "No documentation found."
    return results.joinToString("\n\n---\n\n") { result ->
        val header = "[${result.docset}] ${result.name} (${result.type})"
        val html = contentByUrl[result.loadUrl]
        val body = if (html != null) {
            truncateOnWordBoundary(stripHtml(html), maxCharsPerResult)
        } else {
            "(content unavailable)"
        }
        "$header\n\n$body"
    }
}

val PLATFORM_ALIASES: Map<String, List<String>> = mapOf(
    "php" to listOf("php"),
    "wordpress" to listOf("wordpress"),
    "wp" to listOf("wordpress"),
    "laravel" to listOf("laravel"),
    "react" to listOf("react"),
    "javascript" to listOf("javascript"),
    "js" to listOf("javascript"),
    "typescript" to listOf("react", "nodejs"),
    "ts" to listOf("react", "nodejs"),
    "node" to listOf("nodejs"),
    "nodejs" to listOf("nodejs"),
    "kotlin" to listOf("usercontribKotlin"),
    "kt" to listOf("usercontribKotlin"),
)

fun resolveDocsets(docsets: String?): List<String> {
    if (docsets.isNullOrBlank()) {
        return listOf("php", "wordpress", "laravel", "react", "javascript", "nodejs")
    }
    val seen = linkedSetOf<String>()
    docsets.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { alias ->
        val platforms = PLATFORM_ALIASES[alias]
        if (platforms != null) {
            seen.addAll(platforms)
        } else {
            seen.add(alias)
        }
    }
    return seen.toList()
}

object DocSearchTool {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Volatile
    private var cachedDocsetIds: Map<String, String>? = null

    fun execute(query: String, docsets: String? = null, maxResults: Int = 2, maxCharsPerResult: Int = 2000): String {
        if (query.isBlank()) return "No documentation found for: (empty query)"

        val apiPort = discoverApiPort() ?: return "DocSearch unavailable: Dash is not running."

        val ids = loadDocsetIds(apiPort) ?: return "DocSearch unavailable: cannot reach Dash API."

        val platforms = resolveDocsets(docsets)
        val identifiers = platforms.mapNotNull { ids[it] }.joinToString(",")
        if (identifiers.isEmpty()) return "No matching docsets installed for: ${docsets ?: "all"}"

        val results = search(apiPort, query, identifiers, maxResults)
        if (results.isEmpty()) return "No documentation found for: $query"

        val contentByUrl = results.associate { it.loadUrl to fetchContent(it.loadUrl) }
            .filterValues { it.isNotEmpty() }

        return formatDocResults(results, contentByUrl, maxCharsPerResult)
    }

    private fun discoverApiPort(): Int? {
        return try {
            val statusPath = java.nio.file.Path.of(
                System.getProperty("user.home"),
                "Library/Application Support/Dash/.dash_api_server/status.json"
            )
            val content = java.nio.file.Files.readString(statusPath)
            val json = Json.parseToJsonElement(content).jsonObject
            json["port"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }
    }

    private fun loadDocsetIds(apiPort: Int): Map<String, String>? {
        cachedDocsetIds?.let { return it }
        return try {
            val body = httpGet("http://127.0.0.1:$apiPort/docsets/list")
            val json = Json.parseToJsonElement(body).jsonObject
            val docsets = json["docsets"]?.jsonArray ?: return null
            val ids = mutableMapOf<String, String>()
            for (docset in docsets) {
                val obj = docset.jsonObject
                val platform = obj["platform"]?.jsonPrimitive?.content ?: continue
                val identifier = obj["identifier"]?.jsonPrimitive?.content ?: continue
                ids[platform] = identifier
            }
            cachedDocsetIds = ids
            ids
        } catch (_: Exception) {
            null
        }
    }

    private fun search(apiPort: Int, query: String, identifiers: String, maxResults: Int): List<DashDocResult> {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "http://127.0.0.1:$apiPort/search?query=$encodedQuery&docset_identifiers=$identifiers&max_results=$maxResults"
            val body = httpGet(url)
            val json = Json.parseToJsonElement(body).jsonObject
            val results = json["results"]?.jsonArray ?: return emptyList()
            results.mapNotNull { element ->
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val loadUrl = obj["load_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                DashDocResult(
                    name = name,
                    type = obj["type"]?.jsonPrimitive?.content ?: "Unknown",
                    docset = obj["docset"]?.jsonPrimitive?.content ?: "Unknown",
                    loadUrl = loadUrl
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchContent(loadUrl: String): String {
        return try {
            httpGet(loadUrl)
        } catch (_: Exception) {
            ""
        }
    }

    private fun httpGet(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) response.body() else ""
    }
}
