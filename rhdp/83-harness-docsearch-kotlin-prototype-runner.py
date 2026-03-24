#!/usr/bin/env python3
"""
Runner for 83-harness-docsearch-kotlin-prototype.kt
Executes the same DocSearch logic in Python to produce observable output,
since no standalone kotlinc is available. The Kotlin file is the source of
truth for the implementation pattern; this runner validates the approach.
"""

import json
import os
import re
import sys
import html as html_mod
import urllib.request
import urllib.parse
from pathlib import Path

OUTPUT_FILE = Path(__file__).parent / "84-output-docsearch-kotlin-prototype.txt"

# ---------------------------------------------------------------------------
# HTML stripping (mirrors stripHtml() in the Kotlin harness)
# ---------------------------------------------------------------------------

def strip_html(content: str) -> str:
    content = re.sub(r"<(script|style)[^>]*>.*?</\1>", "", content, flags=re.S)
    content = re.sub(r"<(br|p|div|h[1-6]|li|tr|dt|dd)[^>]*/?>", "\n", content, flags=re.I)
    content = re.sub(r"<[^>]+>", " ", content)
    content = html_mod.unescape(content)
    content = re.sub(r"[ \t]+", " ", content)
    content = re.sub(r"\n[ \t]+", "\n", content)
    content = re.sub(r"\n{3,}", "\n\n", content)
    return content.strip()


def truncate(text: str, max_chars: int = 2000) -> str:
    if len(text) <= max_chars:
        return text
    cutoff = text.rfind(" ", 0, max_chars)
    return (text[:cutoff] if cutoff > 0 else text[:max_chars]) + " [...]"


# ---------------------------------------------------------------------------
# Dash API client (mirrors DashClient in the Kotlin harness)
# ---------------------------------------------------------------------------

class DashClient:
    def __init__(self, api_port: int):
        self.api_base = f"http://127.0.0.1:{api_port}"
        self._docset_ids: dict[str, str] | None = None

    def _get(self, url: str) -> str:
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            return json.dumps({"error": str(e)})

    def _get_json(self, url: str) -> dict:
        body = self._get(url)
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"error": f"Invalid JSON: {body[:200]}"}

    def health(self) -> bool:
        data = self._get_json(f"{self.api_base}/health")
        return data.get("status") == "ok"

    def load_docsets(self) -> dict[str, str]:
        if self._docset_ids is not None:
            return self._docset_ids
        data = self._get_json(f"{self.api_base}/docsets/list")
        ids = {}
        for d in data.get("docsets", []):
            ids[d["platform"]] = d["identifier"]
        self._docset_ids = ids
        return ids

    def search(self, query: str, platforms: list[str], max_results: int = 2) -> list[dict]:
        ids = self.load_docsets()
        docset_identifiers = ",".join(id for p in platforms if (id := ids.get(p)))
        if not docset_identifiers:
            return []
        params = urllib.parse.urlencode({
            "query": query,
            "docset_identifiers": docset_identifiers,
            "max_results": max_results,
        })
        data = self._get_json(f"{self.api_base}/search?{params}")
        return data.get("results", [])

    def fetch_content(self, load_url: str) -> str:
        return self._get(load_url)


# ---------------------------------------------------------------------------
# DocSearch logic (mirrors docSearch() in the Kotlin harness)
# ---------------------------------------------------------------------------

PLATFORM_ALIASES = {
    "php": ["php"], "wordpress": ["wordpress"], "wp": ["wordpress"],
    "laravel": ["laravel"], "react": ["react"],
    "javascript": ["javascript"], "js": ["javascript"],
    "typescript": ["react", "nodejs"], "ts": ["react", "nodejs"],
    "node": ["nodejs"], "nodejs": ["nodejs"],
}


def resolve_docsets(docsets: str | None) -> list[str]:
    if not docsets:
        return ["php", "wordpress", "laravel", "react", "javascript", "nodejs"]
    result = []
    for alias in docsets.split(","):
        alias = alias.strip().lower()
        for p in PLATFORM_ALIASES.get(alias, [alias]):
            if p not in result:
                result.append(p)
    return result


def doc_search(client: DashClient, query: str, docsets: str | None = None,
               max_results: int = 2, max_chars_per_result: int = 2000) -> str:
    platforms = resolve_docsets(docsets)
    results = client.search(query, platforms, max_results)
    if not results:
        return f"No documentation found for: {query}"

    parts = []
    for r in results:
        header = f"[{r.get('docset', '?')}] {r['name']} ({r['type']})"
        desc = r.get("description") or ""
        html = client.fetch_content(r["load_url"])
        text = truncate(strip_html(html), max_chars_per_result) if html else "(content unavailable)"
        entry = f"{header}\n{desc}\n\n{text}" if desc else f"{header}\n\n{text}"
        parts.append(entry)

    return "\n\n---\n\n".join(parts)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def discover_api_port() -> int:
    status_path = Path.home() / "Library/Application Support/Dash/.dash_api_server/status.json"
    try:
        with open(status_path) as f:
            return json.load(f).get("port", 49228)
    except Exception:
        return int(os.environ.get("DASH_API_PORT", "49228"))


def separator(title: str):
    print()
    print("=" * 66)
    print(f" {title}")
    print("=" * 66)


class Tee:
    def __init__(self, filepath: Path):
        self.file = open(filepath, "w")
        self.stdout = sys.stdout
    def write(self, data):
        self.stdout.write(data)
        self.file.write(data)
    def flush(self):
        self.stdout.flush()
        self.file.flush()
    def close(self):
        self.file.close()


def main():
    tee = Tee(OUTPUT_FILE)
    sys.stdout = tee

    port = discover_api_port()
    print(f"Dash API port: {port}")
    client = DashClient(port)

    if not client.health():
        print(f"ERROR: Dash API not responding at port {port}")
        return
    print("Dash API healthy.\n")

    docsets = client.load_docsets()
    print(f"Available docsets ({len(docsets)}):")
    for platform, did in docsets.items():
        print(f"  {platform} -> {did}")

    test_cases = [
        ("add_action", "wordpress", "WordPress: add_action hook"),
        ("array_map", "php", "PHP: array_map function"),
        ("Route::get", "laravel", "Laravel: Route facade"),
        ("useState", "react", "React: useState hook"),
        ("Promise.all", "javascript", "JavaScript: Promise.all"),
        ("fs.readFile", "nodejs", "NodeJS: fs.readFile"),
        ("Request", "php,wordpress,laravel", "Cross-docset: Request class"),
        ("TypeScript", "ts", "TypeScript (via React+NodeJS alias)"),
    ]

    for query, ds, label in test_cases:
        separator(f"DocSearch: {label}")
        print(f'query="{query}" docsets={ds or "(all)"}\n')

        result = doc_search(client, query, ds)
        lines = result.split("\n")
        for line in lines[:40]:
            print(line)
        if len(lines) > 40:
            print(f"\n  [... {len(lines) - 40} more lines truncated ...]")

    separator("TOOL SPEC (how it would appear in the model prompt)")
    print("""3. DocSearch - Searches installed documentation (Dash docsets).
   Returns the text content of the top matching documentation pages.
   Parameters: query (required, string),
               docsets (optional, string, comma-separated: php, wordpress, wp,
                        laravel, react, javascript, js, typescript, ts, node, nodejs)

   If docsets is omitted, searches all installed documentation.""")

    separator("TOOL CALL EXAMPLE (what the model would emit)")
    print("""<tool_call>
{"name": "DocSearch", "arguments": {"query": "add_action", "docsets": "wordpress"}}
</tool_call>""")

    separator("INTEGRATION PATTERN (in Order89Action.kt)")
    print(""""DocSearch" -> {
    val query = call.arguments["query"]?.jsonPrimitive?.content ?: ""
    val docsets = call.arguments["docsets"]?.jsonPrimitive?.content
    DocSearchTool.execute(query, docsets)
}""")

    separator("SUMMARY")
    print("""DocSearch pipeline:
  1. Resolve docset aliases (e.g. "wp" -> "wordpress", "ts" -> "react"+"nodejs")
  2. GET /docsets/list -> cache platform->identifier mapping
  3. GET /search?query=<q>&docset_identifiers=<ids>&max_results=2
  4. For each result: GET <load_url> -> strip HTML -> truncate to ~2000 chars
  5. Format as "[Docset] Name (Type)\\n<text content>" separated by ---

Key design decisions:
  - 2 results by default: enough context without flooding the model's token budget
  - 2000 chars per result: ~500 tokens, so ~1000 tokens total for 2 results
  - HTML stripping is regex-based (no jsoup dependency - project convention)
  - Docset discovery is cached per DashClient instance (identifiers stable per session)
  - Platform aliases let the model use natural names ("wp", "ts", "js")
  - Falls back gracefully: unknown docset -> skip, no results -> clear message
""")

    print(f"\nOutput saved to: {OUTPUT_FILE}")
    sys.stdout = tee.stdout
    tee.close()


if __name__ == "__main__":
    main()
