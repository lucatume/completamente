/**
 * Harness: DocSearch Kotlin Prototype
 * =============================================================================
 * Explores how a DocSearch tool could satisfy model requests for documentation
 * by querying the Dash API, taking the first 2 matches, fetching their HTML
 * content, stripping it to plain text, and returning a compact result string.
 *
 * This mirrors the existing tool pattern in Order89Tools.kt:
 *   - Pure functions for logic (searchDocs, stripHtml, formatDocResults)
 *   - Object singleton for the tool entry point (DocSearchTool)
 *   - Returns a plain string for the model to consume
 *
 * Run with: kotlinc -script rhdp/83-harness-docsearch-kotlin-prototype.kt
 *   or:     kotlin rhdp/83-harness-docsearch-kotlin-prototype.kt  (Kotlin 1.9+)
 *   or:     ./gradlew run  (if wired into a test or main)
 *
 * Requires: Dash.app running with its HTTP API enabled.
 * Port discovery: reads ~/Library/Application Support/Dash/.dash_api_server/status.json
 * =============================================================================
 */

// Using only java.net.http (project convention: no heavyweight HTTP libs)
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

// ---------------------------------------------------------------------------
// HTML stripping — pure function, no library dependency
// ---------------------------------------------------------------------------

/** Strip HTML to plain text. Removes script/style blocks, tags, collapses whitespace. */
fun stripHtml(html: String): String {
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
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
    // Decode numeric entities
    text = Regex("&#(\\d+);").replace(text) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null && code in 32..126) code.toChar().toString() else ""
    }
    // Collapse whitespace: multiple spaces → single, preserve newlines
    text = Regex("[ \\t]+").replace(text, " ")
    text = Regex("\\n[ \\t]+").replace(text, "\n")
    text = Regex("\\n{3,}").replace(text, "\n\n")
    return text.trim()
}

/** Truncate to a character limit on a word boundary. */
fun truncate(text: String, maxChars: Int = 2000): String {
    if (text.length <= maxChars) return text
    val cutoff = text.lastIndexOf(' ', maxChars)
    return if (cutoff > 0) text.substring(0, cutoff) + " [...]" else text.substring(0, maxChars) + " [...]"
}

// ---------------------------------------------------------------------------
// Dash API client — uses java.net.http (project convention)
// ---------------------------------------------------------------------------

data class DashDocResult(
    val name: String,
    val type: String,
    val docset: String,
    val loadUrl: String,
    val description: String?
)

/** Minimal JSON value extraction — avoids kotlinx.serialization dependency for the script. */
fun jsonStringField(json: String, field: String): String? {
    val pattern = Regex(""""$field"\s*:\s*"([^"]*?)"""")
    return pattern.find(json)?.groupValues?.get(1)
}

fun jsonArrayField(json: String, field: String): List<String> {
    val pattern = Regex(""""$field"\s*:\s*\[([^\]]*)]""", RegexOption.DOT_MATCHES_ALL)
    val arrayContent = pattern.find(json)?.groupValues?.get(1) ?: return emptyList()
    // Split on },{ to get individual objects
    return Regex("\\{[^}]+}").findAll(arrayContent).map { it.value }.toList()
}

class DashClient(private val apiPort: Int) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val apiBase = "http://127.0.0.1:$apiPort"

    // Cache: platform string → docset identifier
    private var docsetIds: Map<String, String>? = null

    fun health(): Boolean {
        val body = get("$apiBase/health")
        return body.contains("\"ok\"")
    }

    /** Discover installed docsets and cache their identifiers. */
    fun loadDocsets(): Map<String, String> {
        docsetIds?.let { return it }
        val body = get("$apiBase/docsets/list")
        val ids = mutableMapOf<String, String>()
        for (obj in jsonArrayField(body, "docsets")) {
            val platform = jsonStringField(obj, "platform") ?: continue
            val identifier = jsonStringField(obj, "identifier") ?: continue
            ids[platform] = identifier
        }
        docsetIds = ids
        return ids
    }

    /** Search a set of docsets for a query, return up to maxResults. */
    fun search(query: String, platforms: List<String>, maxResults: Int = 2): List<DashDocResult> {
        val ids = loadDocsets()
        val docsetIdentifiers = platforms.mapNotNull { ids[it] }.joinToString(",")
        if (docsetIdentifiers.isEmpty()) return emptyList()

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiBase/search?query=$encodedQuery&docset_identifiers=$docsetIdentifiers&max_results=$maxResults"
        val body = get(url)

        return jsonArrayField(body, "results").mapNotNull { obj ->
            val name = jsonStringField(obj, "name") ?: return@mapNotNull null
            val loadUrl = jsonStringField(obj, "load_url") ?: return@mapNotNull null
            DashDocResult(
                name = name,
                type = jsonStringField(obj, "type") ?: "Unknown",
                docset = jsonStringField(obj, "docset") ?: "Unknown",
                loadUrl = loadUrl,
                description = jsonStringField(obj, "description")
            )
        }
    }

    /** Fetch raw HTML from a load_url. */
    fun fetchContent(loadUrl: String): String = get(loadUrl)

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() == 200) response.body() else ""
    }
}

// ---------------------------------------------------------------------------
// DocSearchTool — mirrors FileSearchTool / WebSearchTool pattern
// ---------------------------------------------------------------------------

/** Platform aliases the model might use → Dash platform strings. */
val PLATFORM_ALIASES = mapOf(
    "php" to listOf("php"),
    "wordpress" to listOf("wordpress"),
    "wp" to listOf("wordpress"),
    "laravel" to listOf("laravel"),
    "react" to listOf("react"),
    "javascript" to listOf("javascript"),
    "js" to listOf("javascript"),
    "typescript" to listOf("react", "nodejs"),  // no standalone TS docset
    "ts" to listOf("react", "nodejs"),
    "node" to listOf("nodejs"),
    "nodejs" to listOf("nodejs"),
)

fun resolveDocsets(docsets: String?): List<String> {
    if (docsets.isNullOrBlank()) {
        // Default: search all supported docsets
        return listOf("php", "wordpress", "laravel", "react", "javascript", "nodejs")
    }
    return docsets.split(",").map { it.trim().lowercase() }.flatMap { alias ->
        PLATFORM_ALIASES[alias] ?: listOf(alias)
    }.distinct()
}

/**
 * Execute a DocSearch: query Dash, fetch first 2 results' HTML, strip to text.
 * Returns a formatted string the model can consume directly.
 */
fun docSearch(client: DashClient, query: String, docsets: String? = null, maxResults: Int = 2, maxCharsPerResult: Int = 2000): String {
    val platforms = resolveDocsets(docsets)
    val results = client.search(query, platforms, maxResults)

    if (results.isEmpty()) return "No documentation found for: $query"

    return buildString {
        for ((i, result) in results.withIndex()) {
            if (i > 0) appendLine("\n---\n")
            appendLine("[${result.docset}] ${result.name} (${result.type})")
            result.description?.let { appendLine(it) }
            appendLine()

            val html = client.fetchContent(result.loadUrl)
            if (html.isNotEmpty()) {
                val text = truncate(stripHtml(html), maxCharsPerResult)
                appendLine(text)
            } else {
                appendLine("(content unavailable)")
            }
        }
    }.trim()
}

// ---------------------------------------------------------------------------
// Main — run the exploration
// ---------------------------------------------------------------------------

fun discoverApiPort(): Int {
    val statusPath = Path.of(System.getProperty("user.home"),
        "Library/Application Support/Dash/.dash_api_server/status.json")
    return try {
        val content = Files.readString(statusPath)
        val portMatch = Regex(""""port"\s*:\s*(\d+)""").find(content)
        portMatch?.groupValues?.get(1)?.toInt() ?: 49228
    } catch (_: Exception) {
        49228
    }
}

fun separator(title: String) {
    println()
    println("=" .repeat(66))
    println(" $title")
    println("=".repeat(66))
}

fun main() {
    val port = discoverApiPort()
    println("Dash API port: $port")
    val client = DashClient(port)

    if (!client.health()) {
        println("ERROR: Dash API not responding at port $port")
        return
    }
    println("Dash API healthy.\n")

    // Show available docsets
    val docsets = client.loadDocsets()
    println("Available docsets (${docsets.size}):")
    for ((platform, id) in docsets) {
        println("  $platform → $id")
    }

    // Test queries that a model might make for each framework
    data class TestCase(val query: String, val docsets: String?, val label: String)
    val testCases = listOf(
        TestCase("add_action", "wordpress", "WordPress: add_action hook"),
        TestCase("array_map", "php", "PHP: array_map function"),
        TestCase("Route::get", "laravel", "Laravel: Route facade"),
        TestCase("useState", "react", "React: useState hook"),
        TestCase("Promise.all", "javascript", "JavaScript: Promise.all"),
        TestCase("fs.readFile", "nodejs", "NodeJS: fs.readFile"),
        // Cross-docset: model asks about "request" without specifying framework
        TestCase("Request", "php,wordpress,laravel", "Cross-docset: Request class"),
        // TypeScript via alias
        TestCase("TypeScript", "ts", "TypeScript (via React+NodeJS alias)"),
    )

    for (tc in testCases) {
        separator("DocSearch: ${tc.label}")
        println("query=\"${tc.query}\" docsets=${tc.docsets ?: "(all)"}\n")

        val result = docSearch(client, tc.query, tc.docsets)
        // Show first 40 lines to keep output manageable
        val lines = result.split("\n")
        for (line in lines.take(40)) {
            println(line)
        }
        if (lines.size > 40) {
            println("\n  [... ${lines.size - 40} more lines truncated ...]")
        }
    }

    separator("TOOL SPEC (how it would appear in the model prompt)")
    println("""
3. DocSearch — Searches installed documentation (Dash docsets).
   Returns the text content of the top matching documentation pages.
   Parameters: query (required, string),
               docsets (optional, string, comma-separated: php, wordpress, wp,
                        laravel, react, javascript, js, typescript, ts, node, nodejs)

   If docsets is omitted, searches all installed documentation.""".trimIndent())

    separator("TOOL CALL EXAMPLE (what the model would emit)")
    println("""
<tool_call>
{"name": "DocSearch", "arguments": {"query": "add_action", "docsets": "wordpress"}}
</tool_call>""".trimIndent())

    separator("INTEGRATION PATTERN (in Order89Action.kt)")
    println("""
// Add alongside FileSearch and WebSearch in the toolExecutor lambda:
"DocSearch" -> {
    val query = call.arguments["query"]?.jsonPrimitive?.content ?: ""
    val docsets = call.arguments["docsets"]?.jsonPrimitive?.content
    DocSearchTool.execute(query, docsets)
}""".trimIndent())

    separator("SUMMARY")
    println("""
DocSearch pipeline:
  1. Resolve docset aliases (e.g. "wp" → "wordpress", "ts" → "react"+"nodejs")
  2. GET /docsets/list → cache platform→identifier mapping
  3. GET /search?query=<q>&docset_identifiers=<ids>&max_results=2
  4. For each result: GET <load_url> → strip HTML → truncate to ~2000 chars
  5. Format as "[Docset] Name (Type)\n<text content>" separated by ---

Key design decisions:
  - 2 results by default: enough context without flooding the model's token budget
  - 2000 chars per result: ~500 tokens, so ~1000 tokens total for 2 results
  - HTML stripping is regex-based (no jsoup/library dependency — project convention)
  - Docset discovery is cached per DashClient instance (identifiers are stable per session)
  - Platform aliases let the model use natural names ("wp", "ts", "js")
  - Falls back gracefully: unknown docset → skip, no results → clear message
""".trimIndent())
}

main()
