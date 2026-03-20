#!/usr/bin/env python3
"""
Harness: Multi-Tool Call Format Test

Tests whether the model can emit multiple <tool_call> blocks in a single response.
Focuses on the top 3 formats from harness 70 (G_plaintext, F_json_compact, C_xml_terse)
plus the native Qwen3 baseline.

Test prompts require 2 or 3 tool calls in one response.

Evaluation:
  - How many tool calls were emitted
  - Whether all expected tools were called with correct params
  - Whether the response is cleanly parseable

5 runs per prompt × 3 prompts × 4 formats = 60 calls

Expected runtime: ~5-8 minutes
"""

import json
import time
import sys
import urllib.request
import re
from typing import Optional

SERVER = "http://localhost:8017"
RUNS_PER_PROMPT = 5
OUTPUT_FILE = "rhdp/73-output-tool-format-multi-call.txt"

# ─── Format Definitions (top 3 + baseline) ─────────────────────────────────

FORMATS = {}

FORMATS["A_native_qwen3"] = """\
# Tools

You may call one or more functions to assist with the user query.

You are provided with function signatures within <tools></tools> XML tags:
<tools>
{"type": "function", "function": {"name": "FileSearch", "description": "Find files in the project that contain a certain string. Returns file:line pairs. Case-insensitive by default.", "parameters": {"type": "object", "required": ["query"], "properties": {"query": {"type": "string", "description": "The search string to find in files"}, "case_sensitive": {"type": "boolean", "description": "If true, search is case-sensitive. Default: false"}, "path": {"type": "string", "description": "File or directory path to search in. Directories are searched recursively. If omitted, searches entire project."}}}}}
{"type": "function", "function": {"name": "WebSearch", "description": "Search the web and return results.", "parameters": {"type": "object", "required": ["query"], "properties": {"query": {"type": "string", "description": "The search query"}}}}}
</tools>

For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:
<tool_call>
{"name": <function-name>, "arguments": <args-json-object>}
</tool_call>"""

FORMATS["C_xml_terse"] = """\
<tools>
<t n="FileSearch" d="Find files containing a string, returns file:line. Case-insensitive by default.">
<p n="query" t="str" r="1">search string</p>
<p n="case_sensitive" t="bool">case-sensitive search</p>
<p n="path" t="str">file/dir to search (recursive)</p>
</t>
<t n="WebSearch" d="Search the web.">
<p n="query" t="str" r="1">search query</p>
</t>
</tools>

Call: <tool_call>{"name":"<tool>","arguments":{...}}</tool_call>"""

FORMATS["F_json_compact"] = """\
Available tools (call via <tool_call>{"name":"<name>","arguments":{...}}</tool_call>):
[{"name":"FileSearch","desc":"Find files containing a string, returns file:line pairs. Case-insensitive by default.","params":{"query":{"type":"str","req":true},"case_sensitive":{"type":"bool","default":false},"path":{"type":"str","desc":"file/dir to search recursively"}}},{"name":"WebSearch","desc":"Search the web.","params":{"query":{"type":"str","req":true}}}]"""

FORMATS["G_plaintext"] = """\
You have two tools:

1. FileSearch — Finds files in the project containing a string. Returns file:line pairs. Case-insensitive by default.
   Parameters: query (required, string), case_sensitive (optional, boolean, default false), path (optional, string, file or directory to search recursively)

2. WebSearch — Searches the web.
   Parameters: query (required, string)

To use a tool, respond with:
<tool_call>
{"name": "<tool-name>", "arguments": {<args>}}
</tool_call>"""


# ─── Multi-Tool Prompts ───────────────────────────────────────────────────

PROMPTS = [
    {
        "id": "M1_two_file_searches",
        "user_msg": "Find all files containing 'useState' in src/components, and also find all files containing 'useEffect' in src/hooks.",
        "expected_calls": [
            {"tool": "FileSearch", "check": {"query": "useState", "path": "src/components"}},
            {"tool": "FileSearch", "check": {"query": "useEffect", "path": "src/hooks"}},
        ],
    },
    {
        "id": "M2_file_and_web",
        "user_msg": "Search the project for files containing 'deprecated' and also search the web for 'React useEffect cleanup best practices'.",
        "expected_calls": [
            {"tool": "FileSearch", "check": {"query": "deprecated"}},
            {"tool": "WebSearch", "check": {"query_contains": "useEffect"}},
        ],
    },
    {
        "id": "M3_three_calls",
        "user_msg": "I need three things: find files with 'TODO' in the project, find files with 'FIXME' in the project, and search the web for 'Kotlin TODO vs FIXME conventions'.",
        "expected_calls": [
            {"tool": "FileSearch", "check": {"query": "TODO"}},
            {"tool": "FileSearch", "check": {"query": "FIXME"}},
            {"tool": "WebSearch", "check": {"query_contains": "TODO"}},
        ],
    },
]


# ─── Helpers ──────────────────────────────────────────────────────────────

def tokenize(text: str) -> int:
    data = json.dumps({"content": text}).encode()
    req = urllib.request.Request(f"{SERVER}/tokenize", data=data,
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read())
            return len(result.get("tokens", []))
    except Exception as e:
        return -1


def complete(prompt: str, max_tokens: int = 1024) -> Optional[str]:
    payload = {
        "prompt": prompt,
        "n_predict": max_tokens,
        "temperature": 0.7,
        "top_p": 0.8,
        "top_k": 20,
        "repetition_penalty": 1.05,
        "stop": ["<|im_end|>", "<|im_start|>"],
        "cache_prompt": False,
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(f"{SERVER}/completion", data=data,
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            result = json.loads(resp.read())
            return result.get("content", "")
    except Exception as e:
        print(f"  [WARN] completion failed: {e}", file=sys.stderr)
        return None


def build_prompt(tool_spec: str, user_msg: str) -> str:
    return (
        f"<|im_start|>system\n{tool_spec}<|im_end|>\n"
        f"<|im_start|>user\n{user_msg}<|im_end|>\n"
        f"<|im_start|>assistant\n"
    )


def extract_all_tool_calls(response: str) -> list[dict]:
    """Extract all <tool_call>...</tool_call> blocks from response."""
    calls = []

    # Find all <tool_call>...</tool_call> pairs
    pattern = r'<tool_call>\s*(\{.*?\})\s*</tool_call>'
    for m in re.finditer(pattern, response, re.DOTALL):
        try:
            calls.append(json.loads(m.group(1)))
        except json.JSONDecodeError:
            pass

    # Also try bare JSON tool calls (no closing tag — model might vary)
    if not calls:
        pattern2 = r'<tool_call>\s*(\{[^<]*\})'
        for m in re.finditer(pattern2, response, re.DOTALL):
            try:
                calls.append(json.loads(m.group(1)))
            except json.JSONDecodeError:
                pass

    # Fallback: find any {"name":...,"arguments":...} blocks
    if not calls:
        pattern3 = r'\{\s*"name"\s*:\s*"[^"]+"\s*,\s*"arguments"\s*:\s*\{[^}]*\}\s*\}'
        for m in re.finditer(pattern3, response, re.DOTALL):
            try:
                calls.append(json.loads(m.group(0)))
            except json.JSONDecodeError:
                pass

    return calls


def match_call(call: dict, expected: dict) -> bool:
    """Check if a parsed tool call matches an expected call."""
    if call.get("name") != expected["tool"]:
        return False

    args = call.get("arguments", {})
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except:
            return False

    check = expected["check"]
    for key, val in check.items():
        if key == "query_contains":
            q = args.get("query", "")
            if val.lower() not in q.lower():
                return False
        else:
            actual = args.get(key)
            if actual is None:
                # Optional params like path — skip if not present
                if key in ("path",):
                    continue
                return False
            if isinstance(val, str) and isinstance(actual, str):
                if val.lower() not in actual.lower():
                    return False
            elif actual != val:
                return False
    return True


def score_multi_response(response: Optional[str], prompt_info: dict) -> dict:
    """Score a multi-tool response."""
    expected = prompt_info["expected_calls"]
    n_expected = len(expected)

    result = {
        "n_expected": n_expected,
        "n_found": 0,
        "n_matched": 0,
        "calls": [],
        "raw": response[:500] if response else "(no response)",
    }

    if not response:
        return result

    calls = extract_all_tool_calls(response)
    result["n_found"] = len(calls)
    result["calls"] = calls

    # Match each expected call to a found call (greedy, no reuse)
    used = set()
    for exp in expected:
        for i, call in enumerate(calls):
            if i not in used and match_call(call, exp):
                used.add(i)
                result["n_matched"] += 1
                break

    return result


# ─── Main ──────────────────────────────────────────────────────────────────

def main():
    print("=" * 70)
    print("Multi-Tool Call Format Test")
    print(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}")
    print(f"Formats: {len(FORMATS)} | Prompts: {len(PROMPTS)} | Runs/prompt: {RUNS_PER_PROMPT}")
    print(f"Total calls: {len(FORMATS) * len(PROMPTS) * RUNS_PER_PROMPT}")
    print("=" * 70)

    # Token counts
    print("\n--- Token Counts ---")
    token_counts = {}
    for fmt_name, fmt_spec in FORMATS.items():
        tokens = tokenize(fmt_spec)
        token_counts[fmt_name] = tokens
        print(f"  {fmt_name:20s} → {tokens:4d} tokens")

    # Run completions
    print("\n--- Completions ---")
    all_results = {}
    detailed_log = []

    for fmt_name, fmt_spec in FORMATS.items():
        fmt_results = []
        print(f"\n  [{fmt_name}]")

        for prompt_info in PROMPTS:
            prompt_scores = []
            prompt = build_prompt(fmt_spec, prompt_info["user_msg"])
            n_exp = len(prompt_info["expected_calls"])

            for run in range(RUNS_PER_PROMPT):
                response = complete(prompt)
                scored = score_multi_response(response, prompt_info)
                prompt_scores.append(scored)

                if scored["n_matched"] == n_exp:
                    marker = "✓"
                elif scored["n_matched"] > 0:
                    marker = "~"
                else:
                    marker = "✗"
                sys.stdout.write(marker)
                sys.stdout.flush()
                time.sleep(0.3)

                detailed_log.append({
                    "format": fmt_name,
                    "prompt": prompt_info["id"],
                    "run": run + 1,
                    "n_expected": n_exp,
                    "n_found": scored["n_found"],
                    "n_matched": scored["n_matched"],
                    "raw": scored["raw"],
                    "calls": scored["calls"],
                })

            avg_found = sum(s["n_found"] for s in prompt_scores) / len(prompt_scores)
            avg_matched = sum(s["n_matched"] for s in prompt_scores) / len(prompt_scores)
            perfect = sum(1 for s in prompt_scores if s["n_matched"] == n_exp)
            fmt_results.append({
                "prompt_id": prompt_info["id"],
                "n_expected": n_exp,
                "avg_found": avg_found,
                "avg_matched": avg_matched,
                "perfect": perfect,
                "scores": prompt_scores,
            })
            print(f" {prompt_info['id']:25s} expect={n_exp} found={avg_found:.1f} matched={avg_matched:.1f} perfect={perfect}/{RUNS_PER_PROMPT}")

        all_results[fmt_name] = fmt_results

    # Summary
    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)

    print(f"\n{'Format':<20s} {'Tokens':>6s} {'AvgFound':>9s} {'AvgMatch':>9s} {'Perfect':>8s} {'Total':>6s}")
    print("-" * 65)
    for fmt_name in FORMATS:
        tokens = token_counts[fmt_name]
        results = all_results[fmt_name]
        total_runs = sum(RUNS_PER_PROMPT for _ in PROMPTS)
        total_perfect = sum(r["perfect"] for r in results)
        avg_found = sum(r["avg_found"] for r in results) / len(results)
        avg_matched = sum(r["avg_matched"] for r in results) / len(results)
        print(f"{fmt_name:<20s} {tokens:>6d} {avg_found:>9.2f} {avg_matched:>9.2f} {total_perfect:>4d}/{total_runs:<3d} ")

    # Per-prompt breakdown
    print(f"\n{'Format':<20s} {'Prompt':<25s} {'Exp':>4s} {'Found':>6s} {'Match':>6s} {'Perf':>5s}")
    print("-" * 75)
    for fmt_name in FORMATS:
        for r in all_results[fmt_name]:
            print(f"{fmt_name:<20s} {r['prompt_id']:<25s} {r['n_expected']:>4d} {r['avg_found']:>6.1f} {r['avg_matched']:>6.1f} {r['perfect']:>2d}/{RUNS_PER_PROMPT}")

    # Write detailed output
    with open(OUTPUT_FILE, "w") as f:
        f.write("Multi-Tool Call Format Test — Detailed Results\n")
        f.write(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M')}\n")
        f.write("=" * 70 + "\n\n")

        f.write("SUMMARY\n")
        f.write(f"{'Format':<20s} {'Tokens':>6s} {'AvgFound':>9s} {'AvgMatch':>9s} {'Perfect':>8s}\n")
        f.write("-" * 55 + "\n")
        for fmt_name in FORMATS:
            tokens = token_counts[fmt_name]
            results = all_results[fmt_name]
            total_runs = sum(RUNS_PER_PROMPT for _ in PROMPTS)
            total_perfect = sum(r["perfect"] for r in results)
            avg_found = sum(r["avg_found"] for r in results) / len(results)
            avg_matched = sum(r["avg_matched"] for r in results) / len(results)
            f.write(f"{fmt_name:<20s} {tokens:>6d} {avg_found:>9.2f} {avg_matched:>9.2f} {total_perfect:>4d}/{total_runs}\n")

        f.write("\n\nDETAILED LOG\n")
        f.write("=" * 70 + "\n")
        for entry in detailed_log:
            f.write(f"\n--- {entry['format']} | {entry['prompt']} | Run {entry['run']} ---\n")
            f.write(f"Expected: {entry['n_expected']} | Found: {entry['n_found']} | Matched: {entry['n_matched']}\n")
            if entry['calls']:
                f.write("Parsed calls:\n")
                for c in entry['calls']:
                    f.write(f"  {json.dumps(c)}\n")
            raw = entry['raw'].replace("\n", "\\n")
            f.write(f"Raw: {raw}\n")

    print(f"\nDetailed results written to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
