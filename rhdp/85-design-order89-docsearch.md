# Design: Order 89 DocSearch Tool

## Goal

Replace the stubbed WebSearch tool with a DocSearch tool that queries local Dash.app documentation.
The model gains access to real API references for PHP, WordPress, Laravel, React, JavaScript, Node.js,
and Kotlin — without any external network calls.

## Non-Goals

- Building a general-purpose web search tool (WebSearch is removed, not reimplemented)
- Supporting Dash docsets beyond the ones installed on the user's machine
- Full-text search within Dash (we use the standard search endpoint, not FTS)
- Caching documentation content across Order 89 sessions (Dash is local and fast enough)

## What Changes

### WebSearch removal

WebSearch was added as a stub in the original tool design (doc 80) and never implemented. It returns
a hardcoded `"WebSearch is not yet implemented."` string. Removing it:

- Deletes `WebSearchTool` from `Order89Tools.kt`
- Removes the `"WebSearch"` branch from the tool executor lambda in `Order89Action.kt`
- Removes WebSearch from `TOOL_SPEC` in `Order89Executor.kt`
- Removes WebSearch from the tool-calling rule
- Removes any test cases exercising the WebSearch stub

The model will no longer see WebSearch in the tool spec and will not attempt to call it.

### DocSearch addition

A new `DocSearchTool` object in `Order89Tools.kt` that queries the local Dash HTTP API for
documentation. It follows the same pattern as `FileSearchTool`: an `object` with an `execute()`
method returning a plain `String`.

## Dash API Pipeline

Discovered and validated in harnesses 81–84. The pipeline is:

```
1. Read API port from ~/Library/Application Support/Dash/.dash_api_server/status.json
2. GET /docsets/list → cache platform → identifier mapping
3. GET /search?query=<q>&docset_identifiers=<ids>&max_results=2
4. For each result: GET <load_url> → strip HTML → truncate
5. Format as "[Docset] Name (Type)\n<text content>" separated by ---
```

The API port is dynamic (changes on each Dash restart). The content server port (embedded in
`load_url` responses) is different from the API port. Both are localhost-only, no auth required.

### Port discovery

Read `status.json` once per `DocSearchTool` initialization. Fall back to a default port if the file
is missing (Dash not running → return a clear error message, don't crash).

### Docset identifier discovery

Docset identifiers are random 8-character strings, unique per Dash install. They must be discovered
via `/docsets/list` and cached for the session. The cache lives on the `DocSearchTool` object
(effectively a singleton per plugin lifecycle).

## Platform Aliases

The model will use natural language names for docsets. Aliases map model terms to Dash platform
strings:

| Alias | Dash platform(s) |
|-------|-------------------|
| `php` | `php` |
| `wordpress`, `wp` | `wordpress` |
| `laravel` | `laravel` |
| `react` | `react` |
| `javascript`, `js` | `javascript` |
| `typescript`, `ts` | `react`, `nodejs` |
| `node`, `nodejs` | `nodejs` |
| `kotlin`, `kt` | `usercontribKotlin` |

TypeScript has no standalone Dash docset; the `ts`/`typescript` alias searches both React and Node.js
docsets. Kotlin maps to the user-contributed Kotlin docset (`usercontribKotlin`).

If the `docsets` parameter is omitted, all supported platforms are searched.

## HTML Content Stripping

Dash serves full HTML pages mirrored from original documentation sources. The raw HTML includes
navigation, sidebars, footers, and script/style blocks that waste tokens.

Harness 84 confirmed that basic regex stripping (remove `<script>`/`<style>`, replace block tags with
newlines, strip remaining tags, decode entities, collapse whitespace) works but leaks nav noise like
"Skip to content", "WordPress.org", "News", "Showcase".

### Approach: nav/boilerplate filtering

After the basic HTML strip, apply a second pass that removes lines matching common nav patterns:

- Lines that are a single short word or phrase commonly found in navigation (e.g., "News", "Hosting",
  "Showcase", "Extend", "Skip to content", "Table of Contents")
- Consecutive runs of very short lines (≤3 words each, ≥3 in a row) — these are almost always nav
  menus

This is a heuristic. False positives (removing a legitimate short line) are acceptable — the model
needs API signatures and descriptions, not every word of the page.

### Character limit

2000 characters per result (~500 tokens). With 2 results, DocSearch consumes ~1000 tokens — well
within the tool result budget.

## Tool Spec Update

The `TOOL_SPEC` constant in `Order89Executor.kt` changes from two tools to two tools (FileSearch
remains, WebSearch is replaced by DocSearch):

```
You have two tools you may call to gather information before writing code:

1. FileSearch — Finds files in the project containing a string. Returns file:line pairs.
   Case-insensitive by default.
   Parameters: query (required, string), case_sensitive (optional, boolean, default false),
   path (optional, string, file or directory to search recursively)

2. DocSearch — Searches installed documentation (Dash docsets).
   Returns the text content of the top matching documentation pages.
   Parameters: query (required, string),
               docsets (optional, string, comma-separated: php, wordpress, wp,
                        laravel, react, javascript, js, typescript, ts, node, nodejs, kotlin, kt)

To use a tool, respond with:
<tool_call>
{"name": "<tool-name>", "arguments": {<args>}}
</tool_call>

When you have gathered the information you need, produce your code output as specified in the rules.
```

The tool-calling rule changes from:

```
- If you need information from project files or the web to correctly implement the instruction,
  call the appropriate tool FIRST. Once you have the information, produce the code.
```

to:

```
- If you need information from project files or API documentation to correctly implement the
  instruction, call the appropriate tool FIRST. Once you have the information, produce the code.
```

## Tool Executor Wiring

In `Order89Action.kt`, the tool executor lambda changes:

```kotlin
val toolExecutor: (ToolCall) -> String = { call ->
    when (call.name) {
        "FileSearch" -> {
            val query = call.arguments["query"]?.jsonPrimitive?.content ?: ""
            val caseSensitive = call.arguments["case_sensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val path = call.arguments["path"]?.jsonPrimitive?.content
            runReadAction { FileSearchTool.execute(project, query, caseSensitive, path) }
        }
        "DocSearch" -> {
            val query = call.arguments["query"]?.jsonPrimitive?.content ?: ""
            val docsets = call.arguments["docsets"]?.jsonPrimitive?.content
            DocSearchTool.execute(query, docsets)
        }
        else -> "Unknown tool: ${call.name}"
    }
}
```

DocSearch does not need `runReadAction` — it makes HTTP calls to localhost, no PSI access.

## Implementation Shape

All new code goes in `Order89Tools.kt` alongside the existing `FileSearchTool`:

```kotlin
// Pure functions
fun stripHtml(html: String): String
fun truncateOnWordBoundary(text: String, maxChars: Int = 2000): String
fun resolveDocsets(docsets: String?): List<String>
fun formatDocResults(results: List<DashDocResult>, contentByUrl: Map<String, String>): String

// Data class
data class DashDocResult(
    val name: String,
    val type: String,
    val docset: String,
    val loadUrl: String
)

// Singleton tool (mirrors FileSearchTool pattern)
object DocSearchTool {
    fun execute(query: String, docsets: String? = null): String
}
```

HTTP calls use `java.net.http.HttpClient` (project convention — no OkHttp, no Retrofit).
JSON parsing uses `kotlinx.serialization` for the Dash API responses (already a project dependency).

## Status Display

When DocSearch is executing, the status line shows:

```
✦ Searching docs: "add_action"...
```

This reuses the existing `formatToolCallDisplay()` in `ToolTypes.kt`. No new status infrastructure
needed.

## Error Handling

| Condition | Behavior |
|-----------|----------|
| Dash not running (port file missing) | Return `"DocSearch unavailable: Dash is not running."` |
| Dash API unreachable (connection refused) | Return `"DocSearch unavailable: cannot reach Dash API."` |
| No results for query | Return `"No documentation found for: <query>"` |
| HTML fetch fails for a result | Include the result header but show `"(content unavailable)"` |
| Unknown docset alias | Skip it silently; search remaining valid docsets |

All errors return strings the model can understand — no exceptions propagated to the caller.

## Files Changed

| File | Change |
|------|--------|
| `Order89Tools.kt` | Remove `WebSearchTool`. Add `stripHtml()`, `truncateOnWordBoundary()`, `resolveDocsets()`, `formatDocResults()`, `DashDocResult`, `DocSearchTool`. |
| `Order89Executor.kt` | Update `TOOL_SPEC` (WebSearch → DocSearch). Update `TOOL_CALLING_RULE` text. |
| `Order89Action.kt` | Replace `"WebSearch"` branch with `"DocSearch"` in tool executor lambda. |
| `Order89ToolsTest.kt` | Remove WebSearch stub tests. Add tests for `stripHtml()`, `truncateOnWordBoundary()`, `resolveDocsets()`, `formatDocResults()`. DocSearch HTTP integration is not unit-tested (requires running Dash). |
| `ToolTypesTest.kt` | Update any test cases that reference WebSearch in tool call parsing. |

## Alternatives Considered

### Keep WebSearch alongside DocSearch (rejected)

WebSearch was never implemented and the model currently wastes a tool call to get "not implemented"
back. Adding DocSearch while keeping the stub doubles the tool spec size for no benefit. Removing
WebSearch simplifies the spec and gives the model a tool that actually works.

### Use jsoup for HTML parsing (rejected)

Project convention is no heavyweight dependencies. Regex-based stripping with a nav-filtering second
pass is sufficient for documentation pages. The prototype (harness 83) validated this approach.

### Full-text search via Dash FTS endpoint (rejected)

Dash supports enabling FTS per docset via `POST /docsets/enable_fts`. This would improve search
quality but adds complexity (must enable FTS on first use, different query format). Standard search
is good enough for API reference lookups, which is the primary use case.

### Cache HTML content across sessions (rejected)

Dash is local and fast (~10ms per request). Caching adds stale-data risk and memory pressure. The
docset identifier cache (platform → ID mapping) is sufficient.

## Trade-offs

- **Requires Dash.app running** — DocSearch is a no-op when Dash is not available. The model gets a
  clear error message and falls back to its training knowledge. This is acceptable because the tool
  is opt-in (tool usage mode must be `manual` or `auto`).
- **macOS only** — Dash is a macOS app. This limits DocSearch to macOS users. No cross-platform
  alternative is proposed; the tool gracefully degrades (returns "unavailable").
- **Heuristic HTML stripping** — Regex-based stripping will occasionally produce noisy output. This
  is preferable to adding a library dependency. The 2000-char truncation limits the damage.
- **2 results × 2000 chars = ~1000 tokens** — This is a meaningful chunk of the model's context, but
  the two-phase architecture (doc 80) ensures tool results only appear in the Phase 2 prompt where
  they directly support code generation.
