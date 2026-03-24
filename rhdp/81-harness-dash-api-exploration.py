#!/usr/bin/env python3
"""
Harness: Dash API Exploration
=============================================================================
Tests the Dash HTTP API to understand the full query-to-content pipeline.

The Dash API serves local documentation from installed docsets. The flow is:
  1. GET /health          -> confirm server is up
  2. GET /docsets/list    -> discover installed docsets and their identifiers
  3. GET /search          -> search for a term, get results with load_url
  4. GET <load_url>       -> fetch the actual HTML documentation content

NOTE: The API server port changes on each Dash restart. The schema endpoint
is at one port but load_url content is served on a different port.

Docsets tested: PHP, WordPress, Laravel, React, JavaScript, NodeJS (TypeScript)

Expected output: JSON responses from each endpoint, demonstrating the full
pipeline from query to rendered documentation content.
=============================================================================
"""

import json
import os
import re
import sys
import html as html_mod
import urllib.request
import urllib.parse
from pathlib import Path

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

DASH_STATUS_FILE = Path.home() / "Library/Application Support/Dash/.dash_api_server/status.json"

try:
    with open(DASH_STATUS_FILE) as f:
        API_PORT = json.load(f).get("port", 49228)
except (PermissionError, FileNotFoundError, json.JSONDecodeError):
    API_PORT = int(os.environ.get("DASH_API_PORT", "49228"))

API_BASE = f"http://127.0.0.1:{API_PORT}"

# Platform -> search query mapping for the requested languages/frameworks
TARGETS = {
    "php":        "array_map",
    "wordpress":  "add_action",
    "laravel":    "Route",
    "react":      "useState",
    "javascript": "Promise",
    "nodejs":     "fs.readFile",
}

OUTPUT_FILE = Path(__file__).parent / "82-output-dash-api-exploration.txt"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def separator(title: str) -> None:
    print()
    print("=" * 66)
    print(f" {title}")
    print("=" * 66)


def api_get(url: str) -> str:
    """Fetch a URL and return the response body as string."""
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        return json.dumps({"error": str(e)})


def api_json(url: str) -> dict:
    """Fetch a URL and parse the JSON response."""
    body = api_get(url)
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {"error": f"Invalid JSON: {body[:200]}"}


def html_to_text(content: str, max_chars: int = 3000) -> str:
    """Rough HTML-to-text: strip scripts/styles/tags, decode entities."""
    content = re.sub(r"<(script|style)[^>]*>.*?</\1>", "", content, flags=re.S)
    content = re.sub(r"<[^>]+>", " ", content)
    content = html_mod.unescape(content)
    content = re.sub(r"[ \t]+", " ", content)
    content = re.sub(r"\n\s*\n", "\n\n", content)
    return content.strip()[:max_chars]


class Tee:
    """Write to both stdout and a file."""
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


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    tee = Tee(OUTPUT_FILE)
    sys.stdout = tee

    # --- Step 1: Health Check ---
    separator("STEP 1: Health Check")
    url = f"{API_BASE}/health"
    print(f"GET {url}\n")
    health = api_json(url)
    print(json.dumps(health, indent=2))

    if health.get("status") != "ok":
        print("\nERROR: Dash API not responding. Is Dash running?")
        print(f"Check: {DASH_STATUS_FILE}")
        sys.exit(1)
    print("\nHealth check passed.")

    # --- Step 2: List Installed Docsets ---
    separator("STEP 2: List Installed Docsets")
    url = f"{API_BASE}/docsets/list"
    print(f"GET {url}\n")
    docsets_data = api_json(url)

    print(f"  {'Platform':<20} {'Name':<35} {'ID':<12} FTS")
    print(f"  {'─'*20} {'─'*35} {'─'*12} {'─'*10}")
    for d in docsets_data.get("docsets", []):
        print(f"  {d['platform']:<20} {d['name']:<35} {d['identifier']:<12} {d['full_text_search']}")

    # Build identifier lookup
    platform_to_id = {}
    for d in docsets_data.get("docsets", []):
        platform_to_id[d["platform"]] = d["identifier"]

    print("\nResolved target docset identifiers:")
    target_ids = {}
    for platform in TARGETS:
        did = platform_to_id.get(platform, "")
        target_ids[platform] = did
        status = did if did else "NOT INSTALLED"
        print(f"  {platform}: {status}")

    # --- Step 3: Search Queries ---
    separator("STEP 3: Search Queries (one per framework)")

    first_load_urls = {}  # platform -> load_url of first result

    for platform, query in TARGETS.items():
        docset_id = target_ids.get(platform, "")
        print(f"\n--- {platform.upper()}: searching for '{query}' ---")

        if not docset_id:
            print("  SKIPPED (docset not installed)")
            continue

        params = urllib.parse.urlencode({
            "query": query,
            "docset_identifiers": docset_id,
            "max_results": 3,
        })
        url = f"{API_BASE}/search?{params}"
        print(f"GET {url}\n")

        data = api_json(url)
        results = data.get("results", [])
        print(f"  Found {len(results)} result(s):")

        for i, r in enumerate(results):
            print(f"  [{i+1}] {r['name']}")
            print(f"      type: {r['type']}")
            print(f"      platform: {r.get('platform', 'n/a')}")
            print(f"      docset: {r.get('docset', 'n/a')}")
            print(f"      load_url: {r['load_url']}")
            if r.get("description"):
                print(f"      description: {r['description']}")
            print()

        if results:
            first_load_urls[platform] = results[0]["load_url"]

    # --- Step 4: Fetch Content ---
    separator("STEP 4: Fetch Content from load_url (first result per framework)")

    for platform, query in TARGETS.items():
        print(f"\n--- {platform.upper()}: content for '{query}' ---")

        load_url = first_load_urls.get(platform)
        if not load_url:
            print("  SKIPPED (no search results)")
            continue

        print(f"GET {load_url}\n")
        raw_html = api_get(load_url)
        text = html_to_text(raw_html)

        if text:
            lines = text.split("\n")[:60]
            print("\n".join(lines))
            if len(text.split("\n")) > 60:
                print("\n  [... truncated to 60 lines ...]")
        else:
            print("  ERROR: No content returned from load_url")
        print()

    # --- Step 5: Cross-docset Search ---
    separator("STEP 5: Cross-docset Search (multiple docsets at once)")

    combined_ids = ",".join(
        filter(None, [target_ids.get("php", ""), target_ids.get("wordpress", ""), target_ids.get("laravel", "")])
    )
    params = urllib.parse.urlencode({
        "query": "request",
        "docset_identifiers": combined_ids,
        "max_results": 5,
    })
    url = f"{API_BASE}/search?{params}"
    print(f"Searching 'request' across PHP + WordPress + Laravel")
    print(f"GET {url}\n")

    data = api_json(url)
    results = data.get("results", [])
    print(f"Found {len(results)} result(s) across docsets:")
    for i, r in enumerate(results):
        print(f"  [{i+1}] {r.get('docset', '?')}: {r['name']} ({r['type']})")

    # --- Step 6: TypeScript via React + NodeJS ---
    separator("STEP 6: TypeScript via React + NodeJS docsets")

    ts_ids = ",".join(
        filter(None, [target_ids.get("react", ""), target_ids.get("nodejs", "")])
    )
    params = urllib.parse.urlencode({
        "query": "TypeScript",
        "docset_identifiers": ts_ids,
        "max_results": 5,
    })
    url = f"{API_BASE}/search?{params}"
    print(f"Searching 'TypeScript' across React + NodeJS")
    print(f"GET {url}\n")

    data = api_json(url)
    results = data.get("results", [])
    print(f"Found {len(results)} result(s):")
    for i, r in enumerate(results):
        print(f"  [{i+1}] {r.get('docset', '?')}: {r['name']} ({r['type']})")

    print()
    print("NOTE: Dash does not have a standalone TypeScript docset.")
    print("TypeScript docs are found within the React and NodeJS docsets.")

    # --- Summary ---
    separator("SUMMARY: API Flow")
    print("""
The Dash API pipeline from query to content:

  1. GET /health
     -> {"status": "ok", "timestamp": <unix_ts>}

  2. GET /docsets/list
     -> {"docsets": [{"name": "...", "identifier": "<8chars>", "platform": "...", ...}]}
     Note: identifier is a random 8-char string, unique per install.

  3. GET /search?query=<term>&docset_identifiers=<id1,id2>&max_results=<n>
     -> {"results": [{"name": "...", "load_url": "http://...", "type": "...", ...}]}
     Note: load_url uses a DIFFERENT port than the API. Results include:
       - name: display name of the entry
       - load_url: URL to fetch the actual HTML content
       - type: Function, Class, Method, Guide, Section, etc.
       - platform: the docset's platform string
       - docset: display name of the source docset
       - description: optional brief description (often null)

  4. GET <load_url>
     -> Raw HTML of the documentation page
     This is a full HTML page mirrored from the original documentation source.
     To extract useful content, you need to strip HTML tags and parse the body.

  Key observations:
  - The API port is dynamic; find it in:
    ~/Library/Application Support/Dash/.dash_api_server/status.json
  - The content server port (in load_url) is different from the API port
  - Docset identifiers are random per-install; always discover them via /docsets/list
  - load_url may include anchor fragments (#//dash_ref_xxx/...) for section-level results
  - Cross-docset search works by passing comma-separated identifiers
  - No authentication required (localhost only)
  - Full-text search can be enabled per-docset via /docsets/enable_fts (POST with identifier)
""")

    print(f"\nOutput saved to: {OUTPUT_FILE}")
    sys.stdout = tee.stdout
    tee.close()


if __name__ == "__main__":
    main()
