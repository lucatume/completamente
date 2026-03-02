"""
Test harness: measure llama-server prompt caching effectiveness across
simulated editing sessions.

Simulates a user editing a file keystroke-by-keystroke and measures how many
tokens the server reuses from its KV cache between consecutive requests.

Tests two prompt ordering strategies:
  A) CURRENT ORDER (definition chunks, ring chunks, diffs, original, current, updated)
     — diffs change in the middle of the prompt, breaking the prefix cache early.
  B) STABLE-FIRST ORDER (definition chunks, ring chunks, original, current, diffs, updated)
     — diffs are placed after the file windows, keeping the stable prefix longer.

The server's /completion response includes:
  - timings.prompt_n:  total prompt tokens
  - timings.tokens_cached (older) or prompt_tokens (response field): tokens reused

Dependencies:
    pip install httpx

Usage:
    # Start the server first (with --cache-prompt, the default):
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then:
    #   python 31-harness-prompt-cache-effectiveness.py
    #
    # Output goes to STDOUT and to ref/32-output-prompt-cache-effectiveness.txt
"""

import json
import sys
import time
from pathlib import Path

import httpx

BASE_URL = "http://127.0.0.1:8017"
TIMEOUT = 120.0
SCRIPT_DIR = Path(__file__).parent
OUTPUT_FILE = SCRIPT_DIR / "32-output-prompt-cache-effectiveness.txt"


# ---------------------------------------------------------------------------
# Simulated editing session data
# ---------------------------------------------------------------------------

# A realistic Kotlin file the user is editing.  We simulate 6 keystrokes
# where the user is adding a new method line-by-line.
ORIGINAL_FILE = """\
package com.example.app

import java.time.LocalDate

class UserService(private val repo: UserRepository) {

    fun getUser(id: Long): User? {
        return repo.findById(id)
    }

    fun listActiveUsers(): List<User> {
        return repo.findAll().filter { it.active }
    }
}"""

# Each entry: (current_file_content, list_of_diffs_so_far)
# The user is adding a deleteUser method.
EDITS = [
    # Keystroke 0: user places cursor after listActiveUsers and types newline
    (
        """\
package com.example.app

import java.time.LocalDate

class UserService(private val repo: UserRepository) {

    fun getUser(id: Long): User? {
        return repo.findById(id)
    }

    fun listActiveUsers(): List<User> {
        return repo.findAll().filter { it.active }
    }

}""",
        [],
    ),
    # Keystroke 1: user starts typing the function signature
    (
        """\
package com.example.app

import java.time.LocalDate

class UserService(private val repo: UserRepository) {

    fun getUser(id: Long): User? {
        return repo.findById(id)
    }

    fun listActiveUsers(): List<User> {
        return repo.findAll().filter { it.active }
    }

    fun delete""",
        [
            {
                "file_path": "UserService.kt",
                "original": "    }\n}",
                "updated": "    }\n\n    fun delete",
            }
        ],
    ),
    # Keystroke 2: user completes the signature
    (
        """\
package com.example.app

import java.time.LocalDate

class UserService(private val repo: UserRepository) {

    fun getUser(id: Long): User? {
        return repo.findById(id)
    }

    fun listActiveUsers(): List<User> {
        return repo.findAll().filter { it.active }
    }

    fun deleteUser(id: Long): Boolean {""",
        [
            {
                "file_path": "UserService.kt",
                "original": "    }\n}",
                "updated": "    }\n\n    fun deleteUser(id: Long): Boolean {",
            }
        ],
    ),
    # Keystroke 3: user adds first line of body
    (
        """\
package com.example.app

import java.time.LocalDate

class UserService(private val repo: UserRepository) {

    fun getUser(id: Long): User? {
        return repo.findById(id)
    }

    fun listActiveUsers(): List<User> {
        return repo.findAll().filter { it.active }
    }

    fun deleteUser(id: Long): Boolean {
        val user = repo.findById(id) ?: return false""",
        [
            {
                "file_path": "UserService.kt",
                "original": "    }\n}",
                "updated": "    }\n\n    fun deleteUser(id: Long): Boolean {\n        val user = repo.findById(id) ?: return false",
            }
        ],
    ),
    # Keystroke 4: user adds second line
    (
        """\
package com.example.app

import java.time.LocalDate

class UserService(private val repo: UserRepository) {

    fun getUser(id: Long): User? {
        return repo.findById(id)
    }

    fun listActiveUsers(): List<User> {
        return repo.findAll().filter { it.active }
    }

    fun deleteUser(id: Long): Boolean {
        val user = repo.findById(id) ?: return false
        repo.delete(user)""",
        [
            {
                "file_path": "UserService.kt",
                "original": "    }\n}",
                "updated": "    }\n\n    fun deleteUser(id: Long): Boolean {\n        val user = repo.findById(id) ?: return false\n        repo.delete(user)",
            }
        ],
    ),
    # Keystroke 5: user adds return and closing brace
    (
        """\
package com.example.app

import java.time.LocalDate

class UserService(private val repo: UserRepository) {

    fun getUser(id: Long): User? {
        return repo.findById(id)
    }

    fun listActiveUsers(): List<User> {
        return repo.findAll().filter { it.active }
    }

    fun deleteUser(id: Long): Boolean {
        val user = repo.findById(id) ?: return false
        repo.delete(user)
        return true
    }
}""",
        [
            {
                "file_path": "UserService.kt",
                "original": "    }\n}",
                "updated": "    }\n\n    fun deleteUser(id: Long): Boolean {\n        val user = repo.findById(id) ?: return false\n        repo.delete(user)\n        return true\n    }\n}",
            }
        ],
    ),
]

# Stable context: definition chunks and ring buffer chunks that don't change
# between keystrokes (simulating what the plugin provides).
DEFINITION_CHUNKS = {
    "UserRepository.kt": """\
interface UserRepository {
    fun findById(id: Long): User?
    fun findAll(): List<User>
    fun save(user: User): User
    fun delete(user: User)
}""",
    "User.kt": """\
data class User(
    val id: Long,
    val name: String,
    val email: String,
    val active: Boolean = true,
    val createdAt: LocalDate = LocalDate.now()
)""",
}

RING_CHUNKS = {
    "Application.kt": """\
fun main() {
    val repo = InMemoryUserRepository()
    val service = UserService(repo)
    service.getUser(1)?.let { println(it.name) }
}""",
}

FILE_PATH = "UserService.kt"
CURSOR_LINE = 14  # Approximate cursor position (near the end of the file)


# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------

def build_prompt_current_order(
    definition_chunks: dict[str, str],
    ring_chunks: dict[str, str],
    diffs: list[dict[str, str]],
    original: str,
    current: str,
    file_path: str,
) -> str:
    """Build prompt with CURRENT order: defs, rings, diffs, original, current, updated."""
    parts: list[str] = []

    for path, content in definition_chunks.items():
        parts.append(f"<|file_sep|>{path}")
        parts.append(content)

    for path, content in ring_chunks.items():
        parts.append(f"<|file_sep|>{path}")
        parts.append(content)

    for diff in diffs:
        parts.append(f"<|file_sep|>{diff['file_path']}.diff")
        parts.append("original:")
        parts.append(diff["original"])
        parts.append("updated:")
        parts.append(diff["updated"])

    parts.append(f"<|file_sep|>original/{file_path}")
    parts.append(original)
    parts.append(f"<|file_sep|>current/{file_path}")
    parts.append(current)
    parts.append(f"<|file_sep|>updated/{file_path}")

    return "\n".join(parts)


def build_prompt_stable_first(
    definition_chunks: dict[str, str],
    ring_chunks: dict[str, str],
    diffs: list[dict[str, str]],
    original: str,
    current: str,
    file_path: str,
) -> str:
    """Build prompt with STABLE-FIRST order: defs, rings, original, current, diffs, updated.

    Moves diffs (which change every keystroke) to the end, after the file
    windows.  The original window is also stable between keystrokes when the
    cursor stays in the same area, so it stays before current.
    """
    parts: list[str] = []

    for path, content in definition_chunks.items():
        parts.append(f"<|file_sep|>{path}")
        parts.append(content)

    for path, content in ring_chunks.items():
        parts.append(f"<|file_sep|>{path}")
        parts.append(content)

    parts.append(f"<|file_sep|>original/{file_path}")
    parts.append(original)
    parts.append(f"<|file_sep|>current/{file_path}")
    parts.append(current)

    for diff in diffs:
        parts.append(f"<|file_sep|>{diff['file_path']}.diff")
        parts.append("original:")
        parts.append(diff["original"])
        parts.append("updated:")
        parts.append(diff["updated"])

    parts.append(f"<|file_sep|>updated/{file_path}")

    return "\n".join(parts)


# ---------------------------------------------------------------------------
# Request helper
# ---------------------------------------------------------------------------

def completion_request(prompt: str, cache_prompt: bool = True) -> dict:
    """Send a /completion request, return the full response JSON."""
    payload = {
        "prompt": prompt,
        "n_predict": 256,
        "temperature": 0.0,
        "stop": ["<|file_sep|>", "</s>"],
        "stream": False,
        "cache_prompt": cache_prompt,
    }
    resp = httpx.post(f"{BASE_URL}/completion", json=payload, timeout=TIMEOUT)
    resp.raise_for_status()
    return resp.json()


# ---------------------------------------------------------------------------
# Test runner
# ---------------------------------------------------------------------------

def run_strategy(
    name: str,
    builder,
    output_lines: list[str],
):
    """Run all edit steps with a given prompt builder and collect cache stats."""
    header = f"\n{'=' * 70}\n  Strategy: {name}\n{'=' * 70}"
    print(header)
    output_lines.append(header)

    results = []

    for i, (current_content, diffs) in enumerate(EDITS):
        prompt = builder(
            definition_chunks=DEFINITION_CHUNKS,
            ring_chunks=RING_CHUNKS,
            diffs=diffs,
            original=ORIGINAL_FILE,
            current=current_content,
            file_path=FILE_PATH,
        )

        prompt_len = len(prompt)
        start = time.time()
        resp = completion_request(prompt, cache_prompt=True)
        elapsed = time.time() - start

        # Extract cache stats from response.
        # Field semantics (verified from raw JSON):
        #   timings.cache_n   = tokens reused from KV cache (the actual cache hit)
        #   timings.prompt_n  = tokens that needed NEW evaluation (cache miss)
        #   tokens_evaluated  = total prompt tokens (cache_n + prompt_n)
        #   tokens_cached     = total tokens in the slot's KV cache after request
        timings = resp.get("timings", {})
        cache_n = timings.get("cache_n", 0)       # tokens reused
        prompt_n = timings.get("prompt_n", 0)      # tokens newly evaluated
        total_prompt = resp.get("tokens_evaluated", 0)  # total prompt tokens
        prompt_ms = timings.get("prompt_ms", 0)
        predicted_n = timings.get("predicted_n", 0)

        cache_pct = (cache_n / total_prompt * 100) if total_prompt > 0 else 0

        line = (
            f"  Edit {i}: prompt_chars={prompt_len:5d}  "
            f"total_tokens={total_prompt:4d}  "
            f"cached={cache_n:4d}  "
            f"new_eval={prompt_n:4d}  "
            f"cache%={cache_pct:5.1f}%  "
            f"prompt_ms={prompt_ms:7.1f}  "
            f"gen_tokens={predicted_n:3d}  "
            f"total={elapsed:.2f}s"
        )
        print(line)
        output_lines.append(line)

        results.append({
            "edit": i,
            "prompt_chars": prompt_len,
            "total_tokens": total_prompt,
            "cache_n": cache_n,
            "prompt_n": prompt_n,
            "cache_pct": cache_pct,
            "prompt_ms": prompt_ms,
            "elapsed_s": elapsed,
        })

    # Summary
    avg_cache = sum(r["cache_pct"] for r in results[1:]) / max(len(results) - 1, 1)
    summary = f"\n  Average cache hit (edits 1-{len(results)-1}): {avg_cache:.1f}%"
    print(summary)
    output_lines.append(summary)

    return results


def run_no_cache_baseline(output_lines: list[str]):
    """Run with cache_prompt=false to measure baseline (0% cache)."""
    header = f"\n{'=' * 70}\n  Strategy: NO CACHE (baseline)\n{'=' * 70}"
    print(header)
    output_lines.append(header)

    for i, (current_content, diffs) in enumerate(EDITS):
        prompt = build_prompt_current_order(
            definition_chunks=DEFINITION_CHUNKS,
            ring_chunks=RING_CHUNKS,
            diffs=diffs,
            original=ORIGINAL_FILE,
            current=current_content,
            file_path=FILE_PATH,
        )

        start = time.time()
        resp = completion_request(prompt, cache_prompt=False)
        elapsed = time.time() - start

        timings = resp.get("timings", {})
        cache_n = timings.get("cache_n", 0)
        prompt_n = timings.get("prompt_n", 0)
        total_prompt = resp.get("tokens_evaluated", 0)
        prompt_ms = timings.get("prompt_ms", 0)

        line = (
            f"  Edit {i}: total_tokens={total_prompt:4d}  "
            f"cached={cache_n:4d}  "
            f"new_eval={prompt_n:4d}  "
            f"prompt_ms={prompt_ms:7.1f}  "
            f"total={elapsed:.2f}s"
        )
        print(line)
        output_lines.append(line)


def main() -> int:
    # Check server health
    try:
        httpx.get(f"{BASE_URL}/health", timeout=5.0).raise_for_status()
    except (httpx.ConnectError, httpx.HTTPStatusError) as e:
        print(f"Error: cannot reach llama-server at {BASE_URL}: {e}", file=sys.stderr)
        print("Start it first with: ./07-harness-sweepai-llama-cpp-server.sh", file=sys.stderr)
        return 1

    print(f"Server reachable at {BASE_URL}")
    print(f"Simulating {len(EDITS)} editing steps with {len(DEFINITION_CHUNKS)} "
          f"definition chunks and {len(RING_CHUNKS)} ring chunks.\n")
    print("Each strategy sends the same prompts in sequence, measuring how many")
    print("tokens the server reuses from its KV cache between requests.\n")
    print("Response fields used:")
    print("  prompt_n (timings)  = total prompt tokens")
    print("  tokens_cached       = tokens reused from KV cache")
    print("  tokens_evaluated    = tokens that needed processing")
    print("  prompt_ms (timings) = prompt processing time in ms\n")

    output_lines: list[str] = [
        "Prompt Cache Effectiveness Test Results",
        "=" * 40,
        f"Server: {BASE_URL}",
        f"Edit steps: {len(EDITS)}",
        f"Definition chunks: {len(DEFINITION_CHUNKS)}",
        f"Ring chunks: {len(RING_CHUNKS)}",
        "",
    ]

    # Strategy A: current order
    results_current = run_strategy(
        "CURRENT ORDER (defs → rings → diffs → original → current → updated)",
        build_prompt_current_order,
        output_lines,
    )

    # Flush the KV cache between strategies by sending a completely different prompt
    print("\n--- Flushing KV cache between strategies ---")
    completion_request("Hello world, this is a cache flush.", cache_prompt=False)

    # Strategy B: stable-first order
    results_stable = run_strategy(
        "STABLE-FIRST ORDER (defs → rings → original → current → diffs → updated)",
        build_prompt_stable_first,
        output_lines,
    )

    # Flush again
    print("\n--- Flushing KV cache for baseline ---")
    completion_request("Hello world, this is a cache flush.", cache_prompt=False)

    # Baseline: no caching
    run_no_cache_baseline(output_lines)

    # Comparison
    comparison = [
        "",
        "=" * 70,
        "  COMPARISON SUMMARY",
        "=" * 70,
    ]

    avg_current = sum(r["cache_pct"] for r in results_current[1:]) / max(len(results_current) - 1, 1)
    avg_stable = sum(r["cache_pct"] for r in results_stable[1:]) / max(len(results_stable) - 1, 1)

    avg_prompt_ms_current = sum(r["prompt_ms"] for r in results_current[1:]) / max(len(results_current) - 1, 1)
    avg_prompt_ms_stable = sum(r["prompt_ms"] for r in results_stable[1:]) / max(len(results_stable) - 1, 1)

    comparison.append(f"  Current order  — avg cache: {avg_current:5.1f}%  avg prompt_ms: {avg_prompt_ms_current:7.1f}")
    comparison.append(f"  Stable-first   — avg cache: {avg_stable:5.1f}%  avg prompt_ms: {avg_prompt_ms_stable:7.1f}")

    if avg_prompt_ms_current > 0 and avg_prompt_ms_stable > 0:
        speedup = avg_prompt_ms_current / avg_prompt_ms_stable
        comparison.append(f"  Prompt processing speedup (stable-first vs current): {speedup:.2f}x")

    for line in comparison:
        print(line)

    output_lines.extend(comparison)

    # Also dump full response JSON for the last request of each strategy
    # for manual inspection of all available fields
    output_lines.append("")
    output_lines.append("=" * 70)
    output_lines.append("  RAW RESPONSE FIELDS (last request of each strategy)")
    output_lines.append("=" * 70)

    # Re-send the last edit of each strategy to capture the full response
    for label, builder in [
        ("Current order", build_prompt_current_order),
        ("Stable-first", build_prompt_stable_first),
    ]:
        last_content, last_diffs = EDITS[-1]
        prompt = builder(
            definition_chunks=DEFINITION_CHUNKS,
            ring_chunks=RING_CHUNKS,
            diffs=last_diffs,
            original=ORIGINAL_FILE,
            current=last_content,
            file_path=FILE_PATH,
        )
        resp = completion_request(prompt)
        # Remove the content field to keep the output focused on metadata
        resp_meta = {k: v for k, v in resp.items() if k != "content"}
        output_lines.append(f"\n  {label}:")
        output_lines.append(f"  {json.dumps(resp_meta, indent=2)}")

    # Write output file
    OUTPUT_FILE.write_text("\n".join(output_lines) + "\n")
    print(f"\nResults written to {OUTPUT_FILE}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
