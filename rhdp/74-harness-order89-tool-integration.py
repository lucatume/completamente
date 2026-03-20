#!/usr/bin/env python3
"""
Harness: Order 89 + Tool Calling Integration

Tests whether the model can correctly decide to call tools (FileSearch, WebSearch)
when an Order 89 code transformation request requires external information, AND
still produce correct code output after receiving tool results.

Uses the winning G_plaintext tool format (107 tokens) combined with the current
Order 89 prompt structure (from Order89Executor.kt / harness 66).

Two phases per prompt:
  Phase 1 — Does the model emit a tool call instead of (or before) code?
  Phase 2 — Given a simulated tool result, does it produce correct code?

Test prompts designed to require tool usage:
  T1: "Add a method that calls the UserService.getById method" (needs FileSearch to find signature)
  T2: "Replace this with the standard ISO 8601 date format" (needs WebSearch for format spec)
  T3: "Add error handling matching the pattern used in OrderController" (needs FileSearch)
  T4: "Implement retry logic using Kotlin's recommended approach" (needs WebSearch)
  T5: "Call the processPayment method from PaymentGateway" (needs FileSearch)

Control prompts (should NOT trigger tool use):
  C1: "Convert this to a when expression" (purely syntactic, no tool needed)
  C2: "Add null safety checks" (local transformation, no tool needed)

5 runs per prompt × 7 prompts = 35 calls for phase 1
5 runs per prompt × 5 tool prompts = 25 calls for phase 2

Expected runtime: ~8-12 minutes
"""

import json
import time
import sys
import urllib.request
import re
from typing import Optional

SERVER = "http://localhost:8017"
RUNS_PER_PROMPT = 5
OUTPUT_FILE = "rhdp/75-output-order89-tool-integration.txt"

# ─── Tool Spec (G_plaintext winner) ───────────────────────────────────────

TOOL_SPEC = """\
You have two tools you may call to gather information before writing code:

1. FileSearch — Finds files in the project containing a string. Returns file:line pairs. Case-insensitive by default.
   Parameters: query (required, string), case_sensitive (optional, boolean, default false), path (optional, string, file or directory to search recursively)

2. WebSearch — Searches the web.
   Parameters: query (required, string)

To use a tool, respond with:
<tool_call>
{"name": "<tool-name>", "arguments": {<args>}}
</tool_call>

When you have enough information, produce your code output as specified in the rules."""

# ─── Order 89 Prompt Template ─────────────────────────────────────────────

def build_order89_prompt(instruction: str, selection: str, file_content_before: str,
                         file_content_after: str, language: str, file_path: str,
                         context_files: list[dict] = None, tool_results: list[dict] = None) -> str:
    """Build the full Order 89 prompt with tool spec integrated."""

    # System message with tool spec
    system = f"""\
You are a code transformation tool. You receive a file with a marked selection and an instruction.
You output ONLY the code that replaces the selection.

{TOOL_SPEC}

<Order89Rules>
- Wrap your code output in a fenced code block using triple backticks with the language identifier.
- Do NOT add documentation blocks, comments, or type annotations that the surrounding
  code does not already use. Conversely, if the surrounding code includes documentation
  blocks on every function, include one on yours in the same format.
- Preserve the indentation style, brace placement, and whitespace patterns of the
  surrounding code.
- Do NOT describe what you are about to do. Do NOT explain your reasoning.
- Do NOT include any text before or after the fenced code block.
- If the selection is empty (<Order89UserSelection></Order89UserSelection>),
  output code to insert at that position.
- If you need information from project files or the web to correctly implement the instruction,
  call the appropriate tool FIRST. Once you have the information, produce the code.
</Order89Rules>"""

    # Context files section
    context_section = ""
    if context_files:
        context_section = "\n\n<Order89Context>\n"
        context_section += "The following files are referenced by the file under edit. Use them to understand\n"
        context_section += "the APIs and types available. These files are NOT style references — the file\n"
        context_section += "under edit is the sole source of truth for style.\n"
        for cf in context_files:
            context_section += f'\n<Order89ContextFile path="{cf["path"]}">\n{cf["content"]}\n</Order89ContextFile>\n'
        context_section += "</Order89Context>"

    # User message
    user_msg = f"""{context_section}

Language: {language}
File: {file_path}

<Order89Instruction>
{instruction}
</Order89Instruction>

REMINDER: Match the file's documentation style.

<Order89FileContent>
{file_content_before}<Order89UserSelection>{selection}</Order89UserSelection>{file_content_after}
</Order89FileContent>"""

    # Build chat prompt
    prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"

    # If we have tool results, add them as a multi-turn conversation
    if tool_results:
        # First assistant turn: the tool call
        for tr in tool_results:
            prompt += f"<|im_start|>assistant\n<tool_call>\n{json.dumps(tr['call'])}\n</tool_call><|im_end|>\n"
            prompt += f"<|im_start|>user\n<tool_response>\n{tr['result']}\n</tool_response><|im_end|>\n"

    prompt += "<|im_start|>assistant\n"
    return prompt


# ─── Test Scenarios ───────────────────────────────────────────────────────

KOTLIN_FILE_BEFORE = """\
package com.example.app.controller

import com.example.app.service.UserService
import com.example.app.model.User
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {

    @GetMapping
    fun listUsers(): List<User> {
        return userService.findAll()
    }

"""

KOTLIN_FILE_AFTER = """
    @DeleteMapping("/{id}")
    fun deleteUser(@PathVariable id: Long) {
        userService.delete(id)
    }
}
"""

KOTLIN_SELECTION_EMPTY = ""

PAYMENT_FILE_BEFORE = """\
package com.example.app.service

import java.math.BigDecimal

class OrderService {
    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    fun createOrder(items: List<Item>, total: BigDecimal): Order {
        val order = Order(items = items, total = total)
        orderRepository.save(order)
"""

PAYMENT_FILE_AFTER = """\
        return order
    }
}
"""

SIMPLE_FILE_BEFORE = """\
fun processItems(items: List<Item>): List<Result> {
"""

SIMPLE_FILE_AFTER = """\
}
"""

SIMPLE_SELECTION = """\
    val results = mutableListOf<Result>()
    if (items[0].type == "A") {
        results.add(handleTypeA(items[0]))
    } else if (items[0].type == "B") {
        results.add(handleTypeB(items[0]))
    } else if (items[0].type == "C") {
        results.add(handleTypeC(items[0]))
    } else {
        results.add(handleDefault(items[0]))
    }
    return results"""

# Prompts that SHOULD trigger tool use
TOOL_PROMPTS = [
    {
        "id": "T1_find_method_signature",
        "instruction": "Add a @GetMapping endpoint that calls UserService.getById to fetch a single user by ID",
        "selection": KOTLIN_SELECTION_EMPTY,
        "before": KOTLIN_FILE_BEFORE,
        "after": KOTLIN_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/controller/UserController.kt",
        "context_files": [],
        "expected_tool": "FileSearch",
        "expected_query_contains": ["getById", "UserService"],
        "tool_result": {
            "call": {"name": "FileSearch", "arguments": {"query": "getById"}},
            "result": "src/main/kotlin/com/example/app/service/UserService.kt:15:    fun getById(id: Long): User?\nsrc/main/kotlin/com/example/app/service/UserService.kt:16:        return userRepository.findById(id).orElse(null)"
        },
        "expected_code_contains": ["getById", "GetMapping", "PathVariable"],
    },
    {
        "id": "T2_web_search_format",
        "instruction": "Replace this with code that formats the timestamp as ISO 8601 with timezone offset using java.time",
        "selection": '        val formatted = SimpleDateFormat("yyyy-MM-dd").format(date)',
        "before": "    fun formatTimestamp(date: Date): String {\n",
        "after": "\n        return formatted\n    }",
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/util/DateUtils.kt",
        "context_files": [],
        "expected_tool": "WebSearch",
        "expected_query_contains": ["ISO 8601", "java.time"],
        "tool_result": {
            "call": {"name": "WebSearch", "arguments": {"query": "ISO 8601 format java.time Kotlin"}},
            "result": "ISO 8601 format with timezone offset: use OffsetDateTime or ZonedDateTime with DateTimeFormatter.ISO_OFFSET_DATE_TIME. Example: val formatted = date.toInstant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)"
        },
        "expected_code_contains": ["DateTimeFormatter", "ISO"],
    },
    {
        "id": "T3_find_error_pattern",
        "instruction": "Add error handling for the payment step, matching the error handling pattern used in OrderValidator",
        "selection": KOTLIN_SELECTION_EMPTY,
        "before": PAYMENT_FILE_BEFORE,
        "after": PAYMENT_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/service/OrderService.kt",
        "context_files": [],
        "expected_tool": "FileSearch",
        "expected_query_contains": ["OrderValidator"],
        "tool_result": {
            "call": {"name": "FileSearch", "arguments": {"query": "OrderValidator"}},
            "result": "src/main/kotlin/com/example/app/service/OrderValidator.kt:22:    fun validate(order: Order): ValidationResult {\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:24:        try {\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:28:        } catch (e: ValidationException) {\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:29:            logger.warn(\"Validation failed for order ${order.id}: ${e.message}\")\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:30:            return ValidationResult.failure(e.message ?: \"Unknown validation error\")"
        },
        "expected_code_contains": ["try", "catch", "logger"],
    },
    {
        "id": "T4_web_search_pattern",
        "instruction": "Implement exponential backoff retry logic for this network call using Kotlin's recommended approach",
        "selection": "        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())",
        "before": "    fun fetchData(url: String): String {\n        val request = HttpRequest.newBuilder().uri(URI(url)).build()\n",
        "after": "\n        return response.body()\n    }",
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/client/ApiClient.kt",
        "context_files": [],
        "expected_tool": "WebSearch",
        "expected_query_contains": ["retry", "exponential", "Kotlin"],
        "tool_result": {
            "call": {"name": "WebSearch", "arguments": {"query": "Kotlin exponential backoff retry"}},
            "result": "Recommended pattern: use a loop with delay doubling. var delay = 100L; repeat(maxRetries) { try { return action() } catch (e: Exception) { delay(delay); delay *= 2 } }. For coroutines, use kotlinx.coroutines delay(). Without coroutines, use Thread.sleep()."
        },
        "expected_code_contains": ["retry", "delay"],
    },
    {
        "id": "T5_find_api_method",
        "instruction": "Call PaymentGateway.processPayment with the order total and get the transaction ID",
        "selection": KOTLIN_SELECTION_EMPTY,
        "before": PAYMENT_FILE_BEFORE,
        "after": PAYMENT_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/service/OrderService.kt",
        "context_files": [],
        "expected_tool": "FileSearch",
        "expected_query_contains": ["processPayment", "PaymentGateway"],
        "tool_result": {
            "call": {"name": "FileSearch", "arguments": {"query": "processPayment"}},
            "result": "src/main/kotlin/com/example/app/gateway/PaymentGateway.kt:10:    fun processPayment(amount: BigDecimal, currency: String = \"USD\"): PaymentResult\nsrc/main/kotlin/com/example/app/gateway/PaymentGateway.kt:14:data class PaymentResult(val transactionId: String, val success: Boolean, val errorMessage: String? = null)"
        },
        "expected_code_contains": ["processPayment", "transactionId"],
    },
]

# Control prompts that should NOT trigger tool use
CONTROL_PROMPTS = [
    {
        "id": "C1_pure_syntax",
        "instruction": "Convert this if-else chain to a when expression",
        "selection": SIMPLE_SELECTION,
        "before": SIMPLE_FILE_BEFORE,
        "after": SIMPLE_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/Processor.kt",
        "context_files": [],
        "expected_code_contains": ["when"],
    },
    {
        "id": "C2_null_safety",
        "instruction": "Add null safety checks for the items parameter",
        "selection": SIMPLE_SELECTION,
        "before": SIMPLE_FILE_BEFORE,
        "after": SIMPLE_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/Processor.kt",
        "context_files": [],
        "expected_code_contains": ["null", "empty"],
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
    except:
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
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read())
            return result.get("content", "")
    except Exception as e:
        print(f"  [WARN] completion failed: {e}", file=sys.stderr)
        return None


def has_tool_call(response: str) -> bool:
    return "<tool_call>" in response


def extract_tool_calls(response: str) -> list[dict]:
    calls = []
    for m in re.finditer(r'<tool_call>\s*(\{.*?\})\s*</tool_call>', response, re.DOTALL):
        try:
            calls.append(json.loads(m.group(1)))
        except:
            pass
    # Also try unclosed (model might not close before stop)
    if not calls:
        for m in re.finditer(r'<tool_call>\s*(\{[^<]*\})', response, re.DOTALL):
            try:
                calls.append(json.loads(m.group(1)))
            except:
                pass
    return calls


def extract_code_block(response: str) -> Optional[str]:
    m = re.search(r'```\w*\n(.*?)```', response, re.DOTALL)
    if m:
        return m.group(1).strip()
    return None


def check_tool_call_quality(calls: list[dict], prompt_info: dict) -> dict:
    """Check if the tool call matches expectations."""
    if not calls:
        return {"correct_tool": False, "query_match": False}

    call = calls[0]
    correct_tool = call.get("name") == prompt_info["expected_tool"]
    query = call.get("arguments", {}).get("query", "")
    query_match = any(kw.lower() in query.lower() for kw in prompt_info["expected_query_contains"])

    return {"correct_tool": correct_tool, "query_match": query_match, "call": call}


def check_code_quality(code: str, prompt_info: dict) -> dict:
    """Check if the generated code contains expected elements."""
    if not code:
        return {"has_code": False, "matches": 0, "total": 0}

    expected = prompt_info.get("expected_code_contains", [])
    matches = sum(1 for kw in expected if kw.lower() in code.lower())
    return {"has_code": True, "matches": matches, "total": len(expected)}


# ─── Main ──────────────────────────────────────────────────────────────────

def main():
    print("=" * 70)
    print("Order 89 + Tool Calling Integration Test")
    print(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}")
    print(f"Tool prompts: {len(TOOL_PROMPTS)} | Control prompts: {len(CONTROL_PROMPTS)}")
    print(f"Runs/prompt: {RUNS_PER_PROMPT}")
    print("=" * 70)

    detailed_log = []

    # ── Phase 1: Does the model call tools when it should? ─────────────────
    print("\n═══ PHASE 1: Tool call decision ═══")
    print("(Should the model call a tool before generating code?)\n")

    phase1_results = {}

    # Tool prompts — expect tool calls
    print("  Tool-required prompts (should call tools):")
    for p in TOOL_PROMPTS:
        scores = []
        prompt = build_order89_prompt(
            instruction=p["instruction"], selection=p["selection"],
            file_content_before=p["before"], file_content_after=p["after"],
            language=p["language"], file_path=p["file_path"],
            context_files=p["context_files"]
        )

        for run in range(RUNS_PER_PROMPT):
            response = complete(prompt)
            called = has_tool_call(response) if response else False
            calls = extract_tool_calls(response) if response and called else []
            tc_quality = check_tool_call_quality(calls, p) if calls else {}

            score = {
                "called_tool": called,
                "correct_tool": tc_quality.get("correct_tool", False),
                "query_match": tc_quality.get("query_match", False),
                "raw": response[:400] if response else "(no response)",
                "calls": calls,
            }
            scores.append(score)

            if called and tc_quality.get("correct_tool") and tc_quality.get("query_match"):
                sys.stdout.write("✓")
            elif called:
                sys.stdout.write("~")
            else:
                sys.stdout.write("✗")
            sys.stdout.flush()
            time.sleep(0.3)

            detailed_log.append({"phase": 1, "prompt": p["id"], "run": run + 1, **score})

        n_called = sum(1 for s in scores if s["called_tool"])
        n_correct = sum(1 for s in scores if s["correct_tool"])
        n_query = sum(1 for s in scores if s["query_match"])
        phase1_results[p["id"]] = {
            "type": "tool", "called": n_called, "correct_tool": n_correct,
            "query_match": n_query, "total": RUNS_PER_PROMPT
        }
        print(f" {p['id']:30s} called={n_called}/{RUNS_PER_PROMPT} correct_tool={n_correct} query_match={n_query}")

    # Control prompts — should NOT call tools
    print("\n  Control prompts (should NOT call tools):")
    for p in CONTROL_PROMPTS:
        scores = []
        prompt = build_order89_prompt(
            instruction=p["instruction"], selection=p["selection"],
            file_content_before=p["before"], file_content_after=p["after"],
            language=p["language"], file_path=p["file_path"],
            context_files=p["context_files"]
        )

        for run in range(RUNS_PER_PROMPT):
            response = complete(prompt)
            called = has_tool_call(response) if response else False
            code = extract_code_block(response) if response else None
            code_q = check_code_quality(code, p) if code else {}

            score = {
                "called_tool": called,
                "has_code": code is not None,
                "code_matches": code_q.get("matches", 0),
                "raw": response[:400] if response else "(no response)",
            }
            scores.append(score)

            if not called and code is not None:
                sys.stdout.write("✓")
            elif called:
                sys.stdout.write("✗")
            else:
                sys.stdout.write("~")
            sys.stdout.flush()
            time.sleep(0.3)

            detailed_log.append({"phase": 1, "prompt": p["id"], "run": run + 1, **score})

        n_no_tool = sum(1 for s in scores if not s["called_tool"])
        n_code = sum(1 for s in scores if s["has_code"])
        phase1_results[p["id"]] = {
            "type": "control", "no_tool": n_no_tool, "has_code": n_code, "total": RUNS_PER_PROMPT
        }
        print(f" {p['id']:30s} no_tool={n_no_tool}/{RUNS_PER_PROMPT} has_code={n_code}")

    # ── Phase 2: Code quality after tool result ────────────────────────────
    print("\n═══ PHASE 2: Code generation after tool result ═══")
    print("(Given a simulated tool result, does the model produce correct code?)\n")

    phase2_results = {}

    for p in TOOL_PROMPTS:
        scores = []
        prompt = build_order89_prompt(
            instruction=p["instruction"], selection=p["selection"],
            file_content_before=p["before"], file_content_after=p["after"],
            language=p["language"], file_path=p["file_path"],
            context_files=p["context_files"],
            tool_results=[p["tool_result"]]
        )

        for run in range(RUNS_PER_PROMPT):
            response = complete(prompt)
            code = extract_code_block(response) if response else None
            code_q = check_code_quality(code, p) if code else {}
            called_again = has_tool_call(response) if response else False

            score = {
                "has_code": code is not None,
                "code_matches": code_q.get("matches", 0),
                "code_total": code_q.get("total", 0),
                "called_tool_again": called_again,
                "raw": response[:400] if response else "(no response)",
                "code": code[:300] if code else None,
            }
            scores.append(score)

            if code and code_q.get("matches", 0) == code_q.get("total", 0) and not called_again:
                sys.stdout.write("✓")
            elif code:
                sys.stdout.write("~")
            else:
                sys.stdout.write("✗")
            sys.stdout.flush()
            time.sleep(0.3)

            detailed_log.append({"phase": 2, "prompt": p["id"], "run": run + 1, **score})

        n_code = sum(1 for s in scores if s["has_code"])
        n_perfect = sum(1 for s in scores
                        if s["has_code"] and s["code_matches"] == s["code_total"] and not s["called_tool_again"])
        avg_match = sum(s["code_matches"] for s in scores) / len(scores)
        max_total = scores[0]["code_total"] if scores else 0
        phase2_results[p["id"]] = {
            "has_code": n_code, "perfect": n_perfect, "avg_match": avg_match,
            "max_total": max_total, "total": RUNS_PER_PROMPT
        }
        print(f" {p['id']:30s} code={n_code}/{RUNS_PER_PROMPT} perfect={n_perfect} match={avg_match:.1f}/{max_total}")

    # ── Summary ────────────────────────────────────────────────────────────
    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)

    print("\nPhase 1 — Tool Call Decision:")
    print(f"  {'Prompt':<30s} {'Type':<8s} {'Result':>20s}")
    print("  " + "-" * 62)
    for pid, r in phase1_results.items():
        if r["type"] == "tool":
            print(f"  {pid:<30s} {'TOOL':<8s} called={r['called']}/{r['total']} correct={r['correct_tool']} query={r['query_match']}")
        else:
            print(f"  {pid:<30s} {'CTRL':<8s} no_tool={r['no_tool']}/{r['total']} has_code={r['has_code']}")

    total_tool_called = sum(r["called"] for r in phase1_results.values() if r["type"] == "tool")
    total_tool_expected = sum(r["total"] for r in phase1_results.values() if r["type"] == "tool")
    total_ctrl_correct = sum(r["no_tool"] for r in phase1_results.values() if r["type"] == "control")
    total_ctrl_expected = sum(r["total"] for r in phase1_results.values() if r["type"] == "control")

    print(f"\n  Tool prompts:    {total_tool_called}/{total_tool_expected} called a tool ({total_tool_called/total_tool_expected*100:.0f}%)")
    print(f"  Control prompts: {total_ctrl_correct}/{total_ctrl_expected} skipped tools ({total_ctrl_correct/total_ctrl_expected*100:.0f}%)")

    print("\nPhase 2 — Code Quality After Tool Result:")
    print(f"  {'Prompt':<30s} {'Code':>5s} {'Perfect':>8s} {'Match':>10s}")
    print("  " + "-" * 55)
    for pid, r in phase2_results.items():
        print(f"  {pid:<30s} {r['has_code']:>3d}/{r['total']} {r['perfect']:>4d}/{r['total']} {r['avg_match']:>5.1f}/{r['max_total']}")

    total_perfect = sum(r["perfect"] for r in phase2_results.values())
    total_phase2 = sum(r["total"] for r in phase2_results.values())
    print(f"\n  Overall: {total_perfect}/{total_phase2} perfect code outputs ({total_perfect/total_phase2*100:.0f}%)")

    # Write detailed output
    with open(OUTPUT_FILE, "w") as f:
        f.write("Order 89 + Tool Calling Integration — Detailed Results\n")
        f.write(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M')}\n")
        f.write("=" * 70 + "\n\n")

        f.write("PHASE 1 SUMMARY\n")
        f.write("-" * 50 + "\n")
        for pid, r in phase1_results.items():
            f.write(f"  {pid}: {json.dumps(r)}\n")

        f.write("\nPHASE 2 SUMMARY\n")
        f.write("-" * 50 + "\n")
        for pid, r in phase2_results.items():
            f.write(f"  {pid}: {json.dumps(r)}\n")

        f.write("\n\nDETAILED LOG\n")
        f.write("=" * 70 + "\n")
        for entry in detailed_log:
            f.write(f"\n--- Phase {entry['phase']} | {entry['prompt']} | Run {entry['run']} ---\n")
            for k, v in entry.items():
                if k in ("phase", "prompt", "run"):
                    continue
                if k == "raw":
                    f.write(f"  {k}: {str(v).replace(chr(10), '\\n')[:300]}\n")
                elif k == "code":
                    f.write(f"  {k}: {str(v).replace(chr(10), '\\n')[:300]}\n")
                elif k == "calls":
                    f.write(f"  {k}: {json.dumps(v)}\n")
                else:
                    f.write(f"  {k}: {v}\n")

    print(f"\nDetailed results written to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
