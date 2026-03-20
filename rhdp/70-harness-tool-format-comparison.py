#!/usr/bin/env python3
"""
Harness: Tool Specification Format Comparison for Qwen3-Coder-30B-A3B-Instruct

Tests multiple tool specification formats against the local llama.cpp model to find
the most token-efficient format that the model reliably understands and uses correctly.

Tools under test:
  - FileSearch: grep-like project search returning file:line pairs
  - WebSearch: web search returning results

Formats tested:
  A. native_qwen3      — Official Qwen3 <tools> + JSON Schema (baseline)
  B. xml_full           — Pure XML with nested elements for params
  C. xml_terse          — Minimal XML, attributes instead of elements
  D. xml_typehints      — XML with inline type hints (like function signatures)
  E. markdown           — Markdown headers + param tables
  F. json_compact       — Compact JSON array, no XML wrapper
  G. plaintext          — Natural language descriptions
  H. xml_funcdef        — XML wrapping function-signature-style definitions

Each format is tested with 3 prompts × 5 runs = 15 calls per format.
Evaluation criteria:
  1. Token count of tool spec (measured via /tokenize)
  2. Correct tool selection (did it pick the right tool?)
  3. Correct parameter usage (right param names, valid values)
  4. Parseable response format (can we extract the tool call?)
  5. Overall quality score (0-3: 0=broken, 1=wrong tool/params, 2=right tool minor issues, 3=perfect)

Output: STDOUT summary + detailed results file

Expected runtime: ~10-15 minutes depending on model speed
"""

import json
import time
import sys
import urllib.request
import urllib.error
import re
from typing import Optional

SERVER = "http://localhost:8017"
RUNS_PER_PROMPT = 5
OUTPUT_FILE = "rhdp/71-output-tool-format-comparison.txt"

# ─── Tool Format Definitions ───────────────────────────────────────────────

FORMATS = {}

# A. Native Qwen3 format (baseline)
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

# B. Full XML
FORMATS["B_xml_full"] = """\
<tools>
<tool name="FileSearch">
<description>Find files in the project that contain a certain string. Returns file:line pairs. Case-insensitive by default.</description>
<parameters>
<param name="query" type="string" required="true">The search string to find in files</param>
<param name="case_sensitive" type="boolean" required="false">If true, search is case-sensitive. Default: false</param>
<param name="path" type="string" required="false">File or directory path to search in. Directories are searched recursively. If omitted, searches entire project.</param>
</parameters>
</tool>
<tool name="WebSearch">
<description>Search the web and return results.</description>
<parameters>
<param name="query" type="string" required="true">The search query</param>
</parameters>
</tool>
</tools>

To call a tool, use:
<tool_call>
{"name": "<tool-name>", "arguments": {<args>}}
</tool_call>"""

# C. Terse XML (attributes, short names)
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

# D. XML with type hints (function signature style)
FORMATS["D_xml_typehints"] = """\
<tools>
<tool name="FileSearch">FileSearch(query: str, case_sensitive?: bool = false, path?: str) -> list[file:line]
Find files containing a string. Searches recursively. Case-insensitive by default.</tool>
<tool name="WebSearch">WebSearch(query: str) -> list[result]
Search the web.</tool>
</tools>

Call: <tool_call>{"name":"<tool>","arguments":{...}}</tool_call>"""

# E. Markdown
FORMATS["E_markdown"] = """\
## Available Tools

### FileSearch
Find files in the project that contain a certain string. Returns file:line pairs. Case-insensitive by default.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| query | string | yes | The search string to find in files |
| case_sensitive | boolean | no | If true, search is case-sensitive. Default: false |
| path | string | no | File or directory path to search in (recursive) |

### WebSearch
Search the web and return results.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| query | string | yes | The search query |

To call a tool, respond with:
```
<tool_call>
{"name": "<tool-name>", "arguments": {<args>}}
</tool_call>
```"""

# F. Compact JSON
FORMATS["F_json_compact"] = """\
Available tools (call via <tool_call>{"name":"<name>","arguments":{...}}</tool_call>):
[{"name":"FileSearch","desc":"Find files containing a string, returns file:line pairs. Case-insensitive by default.","params":{"query":{"type":"str","req":true},"case_sensitive":{"type":"bool","default":false},"path":{"type":"str","desc":"file/dir to search recursively"}}},{"name":"WebSearch","desc":"Search the web.","params":{"query":{"type":"str","req":true}}}]"""

# G. Plain text
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

# H. XML function-def style
FORMATS["H_xml_funcdef"] = """\
<tools>
<fn name="FileSearch" returns="list[file:line]">
Find files containing a string. Case-insensitive by default. Directories searched recursively.
<args>
  query: str          # search string (required)
  case_sensitive: bool # default false
  path: str           # file/dir to search
</args>
</fn>
<fn name="WebSearch" returns="list[result]">
Search the web.
<args>
  query: str  # search query (required)
</args>
</fn>
</tools>

Call: <tool_call>{"name":"<tool>","arguments":{...}}</tool_call>"""


# ─── Test Prompts ──────────────────────────────────────────────────────────

PROMPTS = [
    {
        "id": "P1_file_search_simple",
        "user_msg": "Find all files that contain 'TODO' in the project.",
        "expected_tool": "FileSearch",
        "expected_params": {"query": "TODO"},
        "check_params": ["query"],
    },
    {
        "id": "P2_file_search_with_path",
        "user_msg": "Search for 'import React' in the src/components directory, case-sensitive.",
        "expected_tool": "FileSearch",
        "expected_params": {"query": "import React", "case_sensitive": True, "path": "src/components"},
        "check_params": ["query", "case_sensitive", "path"],
    },
    {
        "id": "P3_web_search",
        "user_msg": "Search the web for 'Kotlin coroutines best practices 2024'.",
        "expected_tool": "WebSearch",
        "expected_params": {"query": "Kotlin coroutines best practices 2024"},
        "check_params": ["query"],
    },
]


# ─── Helper Functions ──────────────────────────────────────────────────────

def tokenize(text: str) -> int:
    """Count tokens using the llama.cpp /tokenize endpoint."""
    data = json.dumps({"content": text}).encode()
    req = urllib.request.Request(f"{SERVER}/tokenize", data=data,
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read())
            return len(result.get("tokens", []))
    except Exception as e:
        print(f"  [WARN] tokenize failed: {e}", file=sys.stderr)
        return -1


def complete(prompt: str, max_tokens: int = 512) -> Optional[str]:
    """Call /completion with the given prompt."""
    payload = {
        "prompt": prompt,
        "n_predict": max_tokens,
        "temperature": 0.7,
        "top_p": 0.8,
        "top_k": 20,
        "repetition_penalty": 1.05,
        "stop": ["</tool_call>", "<|im_end|>", "<|im_start|>"],
        "cache_prompt": False,
    }
    data = json.dumps(payload).encode()
    req = urllib.request.Request(f"{SERVER}/completion", data=data,
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            result = json.loads(resp.read())
            return result.get("content", "")
    except Exception as e:
        print(f"  [WARN] completion failed: {e}", file=sys.stderr)
        return None


def build_prompt(tool_spec: str, user_msg: str) -> str:
    """Build a full prompt in Qwen3 chat format with tool spec in system message."""
    return (
        f"<|im_start|>system\n{tool_spec}<|im_end|>\n"
        f"<|im_start|>user\n{user_msg}<|im_end|>\n"
        f"<|im_start|>assistant\n"
    )


def extract_tool_call(response: str) -> Optional[dict]:
    """Try to extract a tool call from the model response."""
    # Try <tool_call> tags first (stop sequence trims closing tag)
    m = re.search(r'<tool_call>\s*(\{.*)', response, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(1).strip())
        except json.JSONDecodeError:
            # Try to fix incomplete JSON
            text = m.group(1).strip()
            if not text.endswith("}"):
                text += "}"
            try:
                return json.loads(text)
            except:
                pass

    # Try bare JSON with "name" and "arguments"
    m = re.search(r'\{\s*"name"\s*:.*?"arguments"\s*:\s*\{[^}]*\}\s*\}', response, re.DOTALL)
    if m:
        try:
            return json.loads(m.group(0))
        except:
            pass

    return None


def score_response(response: Optional[str], prompt_info: dict) -> dict:
    """Score a model response. Returns dict with score and details."""
    result = {
        "score": 0,
        "tool_selected": None,
        "params_correct": False,
        "parseable": False,
        "raw": response[:300] if response else "(no response)",
    }

    if not response:
        return result

    tool_call = extract_tool_call(response)
    if not tool_call:
        # Check if response contains tool name at least
        if prompt_info["expected_tool"].lower() in response.lower():
            result["score"] = 0.5
            result["raw_note"] = "Tool name mentioned but not parseable"
        return result

    result["parseable"] = True

    # Check tool name
    called_name = tool_call.get("name", "")
    if called_name == prompt_info["expected_tool"]:
        result["tool_selected"] = called_name
        result["score"] = 1
    elif called_name.lower() == prompt_info["expected_tool"].lower():
        result["tool_selected"] = called_name
        result["score"] = 0.75
    else:
        result["tool_selected"] = called_name
        return result

    # Check parameters
    args = tool_call.get("arguments", {})
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except:
            result["score"] = 1.5
            return result

    params_ok = True
    for param in prompt_info["check_params"]:
        expected = prompt_info["expected_params"].get(param)
        actual = args.get(param)
        if actual is None:
            params_ok = False
            break
        if param == "query":
            # Allow reasonable query variations
            if not isinstance(actual, str) or len(actual) < 2:
                params_ok = False
        elif param == "case_sensitive":
            if actual is not True and actual != "true":
                params_ok = False
        elif param == "path":
            if not isinstance(actual, str) or len(actual) < 2:
                params_ok = False

    if params_ok:
        result["params_correct"] = True
        result["score"] = 3
    else:
        result["score"] = 2
        result["param_details"] = args

    return result


# ─── Main ──────────────────────────────────────────────────────────────────

def main():
    print("=" * 70)
    print("Tool Specification Format Comparison")
    print(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}")
    print(f"Formats: {len(FORMATS)} | Prompts: {len(PROMPTS)} | Runs/prompt: {RUNS_PER_PROMPT}")
    print(f"Total calls: {len(FORMATS) * len(PROMPTS) * RUNS_PER_PROMPT}")
    print("=" * 70)

    # Phase 1: Measure token counts
    print("\n--- Phase 1: Token Counts ---")
    token_counts = {}
    for fmt_name, fmt_spec in FORMATS.items():
        tokens = tokenize(fmt_spec)
        token_counts[fmt_name] = tokens
        print(f"  {fmt_name:25s} → {tokens:4d} tokens ({len(fmt_spec):4d} chars)")

    # Phase 2: Run completions
    print("\n--- Phase 2: Completions ---")
    all_results = {}

    for fmt_name, fmt_spec in FORMATS.items():
        fmt_results = []
        print(f"\n  [{fmt_name}] ({token_counts[fmt_name]} tokens)")

        for prompt_info in PROMPTS:
            prompt_scores = []
            prompt = build_prompt(fmt_spec, prompt_info["user_msg"])

            for run in range(RUNS_PER_PROMPT):
                response = complete(prompt)
                scored = score_response(response, prompt_info)
                prompt_scores.append(scored)
                marker = "✓" if scored["score"] == 3 else "~" if scored["score"] >= 1 else "✗"
                sys.stdout.write(marker)
                sys.stdout.flush()
                time.sleep(0.3)

            avg = sum(s["score"] for s in prompt_scores) / len(prompt_scores)
            fmt_results.append({
                "prompt_id": prompt_info["id"],
                "scores": prompt_scores,
                "avg_score": avg,
            })
            print(f" {prompt_info['id']:30s} avg={avg:.1f}/3")

        all_results[fmt_name] = fmt_results

    # Phase 3: Summary
    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)

    summary_rows = []
    for fmt_name in FORMATS:
        tokens = token_counts[fmt_name]
        results = all_results[fmt_name]
        all_scores = [s["score"] for r in results for s in r["scores"]]
        avg_score = sum(all_scores) / len(all_scores)
        perfect = sum(1 for s in all_scores if s == 3)
        parseable = sum(1 for r in results for s in r["scores"] if s["parseable"])
        correct_tool = sum(1 for r in results for s in r["scores"] if s["tool_selected"] and s["score"] >= 1)

        # Efficiency = quality per token (higher is better)
        efficiency = (avg_score / tokens * 1000) if tokens > 0 else 0

        summary_rows.append({
            "format": fmt_name,
            "tokens": tokens,
            "avg_score": avg_score,
            "perfect": perfect,
            "total": len(all_scores),
            "parseable": parseable,
            "correct_tool": correct_tool,
            "efficiency": efficiency,
        })

    # Sort by avg_score desc, then by tokens asc
    summary_rows.sort(key=lambda r: (-r["avg_score"], r["tokens"]))

    print(f"\n{'Format':<25s} {'Tokens':>6s} {'Avg':>5s} {'Perfect':>8s} {'Parse':>6s} {'Tool':>5s} {'Eff':>6s}")
    print("-" * 70)
    for row in summary_rows:
        print(
            f"{row['format']:<25s} {row['tokens']:>6d} {row['avg_score']:>5.2f} "
            f"{row['perfect']:>3d}/{row['total']:<4d} {row['parseable']:>5d} {row['correct_tool']:>5d} "
            f"{row['efficiency']:>6.2f}"
        )

    # Best overall
    best = summary_rows[0]
    print(f"\nBest quality:    {best['format']} (avg {best['avg_score']:.2f}/3)")

    # Best efficiency (quality >= 2.5)
    efficient = [r for r in summary_rows if r["avg_score"] >= 2.0]
    if efficient:
        efficient.sort(key=lambda r: -r["efficiency"])
        print(f"Best efficiency: {efficient[0]['format']} (eff {efficient[0]['efficiency']:.2f}, {efficient[0]['tokens']} tokens, avg {efficient[0]['avg_score']:.2f})")

    # Token savings
    baseline_tokens = token_counts["A_native_qwen3"]
    print(f"\nToken savings vs native Qwen3 ({baseline_tokens} tokens):")
    for row in summary_rows:
        if row["avg_score"] >= 2.0:
            savings = baseline_tokens - row["tokens"]
            pct = (savings / baseline_tokens * 100) if baseline_tokens > 0 else 0
            print(f"  {row['format']:<25s} {row['tokens']:>4d} tokens → saves {savings:>4d} ({pct:>5.1f}%)")

    # Write detailed output
    with open(OUTPUT_FILE, "w") as f:
        f.write("Tool Specification Format Comparison — Detailed Results\n")
        f.write(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M')}\n")
        f.write("=" * 70 + "\n\n")

        f.write("TOKEN COUNTS\n")
        f.write("-" * 40 + "\n")
        for fmt_name in FORMATS:
            f.write(f"  {fmt_name:25s} → {token_counts[fmt_name]:4d} tokens\n")

        f.write("\n\nSUMMARY TABLE\n")
        f.write("-" * 70 + "\n")
        f.write(f"{'Format':<25s} {'Tokens':>6s} {'Avg':>5s} {'Perfect':>8s} {'Parse':>6s} {'Tool':>5s} {'Eff':>6s}\n")
        for row in summary_rows:
            f.write(
                f"{row['format']:<25s} {row['tokens']:>6d} {row['avg_score']:>5.2f} "
                f"{row['perfect']:>3d}/{row['total']:<4d} {row['parseable']:>5d} {row['correct_tool']:>5d} "
                f"{row['efficiency']:>6.2f}\n"
            )

        f.write("\n\nDETAILED RESULTS PER FORMAT\n")
        f.write("=" * 70 + "\n")
        for fmt_name in FORMATS:
            f.write(f"\n{'─' * 50}\n")
            f.write(f"Format: {fmt_name}\n")
            f.write(f"Tokens: {token_counts[fmt_name]}\n")
            f.write(f"{'─' * 50}\n")
            for r in all_results[fmt_name]:
                f.write(f"\n  Prompt: {r['prompt_id']} (avg {r['avg_score']:.1f}/3)\n")
                for i, s in enumerate(r["scores"]):
                    f.write(f"    Run {i+1}: score={s['score']:.1f} tool={s['tool_selected']} "
                            f"params_ok={s['params_correct']} parseable={s['parseable']}\n")
                    raw = s["raw"].replace("\n", "\\n")[:150]
                    f.write(f"           raw: {raw}\n")

        f.write("\n\nFORMAT SPECIFICATIONS (for reference)\n")
        f.write("=" * 70 + "\n")
        for fmt_name, fmt_spec in FORMATS.items():
            f.write(f"\n{'─' * 50}\n")
            f.write(f"Format: {fmt_name}\n")
            f.write(f"{'─' * 50}\n")
            f.write(fmt_spec + "\n")

    print(f"\nDetailed results written to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
