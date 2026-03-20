#!/usr/bin/env python3
"""
Harness: Fix Order 89 Tool-Calling Loop

The model enters a tool-calling loop after receiving tool results — it keeps
calling more tools instead of producing code. This harness tests prompt
structure variants to break the loop WITHOUT explicitly telling the model
it has enough information.

Approaches tested:
  V1_multiturn      — Current multi-turn: assistant→tool_call, user→tool_response (baseline, known broken)
  V2_inline_result  — Inject tool result directly into user message as <ToolResult> block, single turn
  V3_system_inject  — Add tool result to system message as gathered context, single turn
  V4_thinking_turn  — assistant→tool_call, tool→result (tool role instead of user), assistant continues
  V5_assistant_cont — Single assistant turn: tool_call + result + continue generating
  V6_two_system     — Two-phase: first system has tools, second system after result drops tools
  V7_result_context — Tool result injected as an Order89ContextFile, single turn (no tool framing)

Uses the 3 FileSearch prompts from harness 74 (T1, T3, T5) which had 100% Phase 1
success but 0% Phase 2 success.

5 runs × 3 prompts × 7 variants = 105 calls
Expected runtime: ~10-15 minutes
"""

import json
import time
import sys
import urllib.request
import re
from typing import Optional, Callable

SERVER = "http://localhost:8017"
RUNS_PER_PROMPT = 5
OUTPUT_FILE = "rhdp/77-output-order89-tool-loop-fix.txt"

# ─── Tool Spec ─────────────────────────────────────────────────────────────

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

When you have gathered the information you need, produce your code output as specified in the rules."""

TOOL_SPEC_NO_TOOLS = ""  # Empty — tools no longer available

ORDER89_RULES = """\
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


# ─── Test Data ─────────────────────────────────────────────────────────────

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

PROMPTS = [
    {
        "id": "T1_find_method_signature",
        "instruction": "Add a @GetMapping endpoint that calls UserService.getById to fetch a single user by ID",
        "selection": "",
        "before": KOTLIN_FILE_BEFORE,
        "after": KOTLIN_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/controller/UserController.kt",
        "tool_call": {"name": "FileSearch", "arguments": {"query": "getById"}},
        "tool_result": "src/main/kotlin/com/example/app/service/UserService.kt:15:    fun getById(id: Long): User?\nsrc/main/kotlin/com/example/app/service/UserService.kt:16:        return userRepository.findById(id).orElse(null)",
        "expected_code_contains": ["getById", "GetMapping"],
    },
    {
        "id": "T3_find_error_pattern",
        "instruction": "Add error handling for the payment step, matching the error handling pattern used in OrderValidator",
        "selection": "",
        "before": PAYMENT_FILE_BEFORE,
        "after": PAYMENT_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/service/OrderService.kt",
        "tool_call": {"name": "FileSearch", "arguments": {"query": "OrderValidator"}},
        "tool_result": "src/main/kotlin/com/example/app/service/OrderValidator.kt:22:    fun validate(order: Order): ValidationResult {\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:24:        try {\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:28:        } catch (e: ValidationException) {\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:29:            logger.warn(\"Validation failed for order ${order.id}: ${e.message}\")\nsrc/main/kotlin/com/example/app/service/OrderValidator.kt:30:            return ValidationResult.failure(e.message ?: \"Unknown validation error\")",
        "expected_code_contains": ["try", "catch", "logger"],
    },
    {
        "id": "T5_find_api_method",
        "instruction": "Call PaymentGateway.processPayment with the order total and get the transaction ID",
        "selection": "",
        "before": PAYMENT_FILE_BEFORE,
        "after": PAYMENT_FILE_AFTER,
        "language": "kotlin",
        "file_path": "src/main/kotlin/com/example/app/service/OrderService.kt",
        "tool_call": {"name": "FileSearch", "arguments": {"query": "processPayment"}},
        "tool_result": "src/main/kotlin/com/example/app/gateway/PaymentGateway.kt:10:    fun processPayment(amount: BigDecimal, currency: String = \"USD\"): PaymentResult\nsrc/main/kotlin/com/example/app/gateway/PaymentGateway.kt:14:data class PaymentResult(val transactionId: String, val success: Boolean, val errorMessage: String? = null)",
        "expected_code_contains": ["processPayment", "transactionId"],
    },
]


# ─── Prompt Builders ──────────────────────────────────────────────────────

def make_user_msg(p: dict, extra_context: str = "") -> str:
    ctx = extra_context
    return f"""{ctx}

Language: {p['language']}
File: {p['file_path']}

<Order89Instruction>
{p['instruction']}
</Order89Instruction>

REMINDER: Match the file's documentation style.

<Order89FileContent>
{p['before']}<Order89UserSelection>{p['selection']}</Order89UserSelection>{p['after']}
</Order89FileContent>"""


def build_V1_multiturn(p: dict) -> str:
    """Baseline: multi-turn assistant→tool_call, user→tool_response"""
    system = f"You are a code transformation tool. You receive a file with a marked selection and an instruction.\nYou output ONLY the code that replaces the selection.\n\n{TOOL_SPEC}\n\n{ORDER89_RULES}"
    user_msg = make_user_msg(p)
    prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"
    prompt += f"<|im_start|>assistant\n<tool_call>\n{json.dumps(p['tool_call'])}\n</tool_call><|im_end|>\n"
    prompt += f"<|im_start|>user\n<tool_response>\n{p['tool_result']}\n</tool_response><|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt


def build_V2_inline_result(p: dict) -> str:
    """Inject tool result as <ToolResult> block in a single user message."""
    system = f"You are a code transformation tool. You receive a file with a marked selection and an instruction.\nYou output ONLY the code that replaces the selection.\n\n{TOOL_SPEC}\n\n{ORDER89_RULES}"
    extra = f"\n<ToolResult tool=\"{p['tool_call']['name']}\" query=\"{p['tool_call']['arguments']['query']}\">\n{p['tool_result']}\n</ToolResult>\n"
    user_msg = make_user_msg(p, extra_context=extra)
    prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt


def build_V3_system_inject(p: dict) -> str:
    """Add tool result to system message as gathered context."""
    gathered = f"\n\n<GatheredContext>\nThe following information was retrieved from the project to help with this task:\n\nFileSearch for \"{p['tool_call']['arguments']['query']}\":\n{p['tool_result']}\n</GatheredContext>"
    system = f"You are a code transformation tool. You receive a file with a marked selection and an instruction.\nYou output ONLY the code that replaces the selection.\n\n{TOOL_SPEC}\n\n{ORDER89_RULES}{gathered}"
    user_msg = make_user_msg(p)
    prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt


def build_V4_tool_role(p: dict) -> str:
    """Use a 'tool' role for the result instead of 'user'."""
    system = f"You are a code transformation tool. You receive a file with a marked selection and an instruction.\nYou output ONLY the code that replaces the selection.\n\n{TOOL_SPEC}\n\n{ORDER89_RULES}"
    user_msg = make_user_msg(p)
    prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"
    prompt += f"<|im_start|>assistant\n<tool_call>\n{json.dumps(p['tool_call'])}\n</tool_call><|im_end|>\n"
    prompt += f"<|im_start|>tool\n{p['tool_result']}<|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt


def build_V5_assistant_cont(p: dict) -> str:
    """Single assistant turn: tool_call, then result injected inline, model continues."""
    system = f"You are a code transformation tool. You receive a file with a marked selection and an instruction.\nYou output ONLY the code that replaces the selection.\n\n{TOOL_SPEC}\n\n{ORDER89_RULES}"
    user_msg = make_user_msg(p)
    prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"
    # Single assistant turn that already contains the tool call and result
    prompt += f"<|im_start|>assistant\n<tool_call>\n{json.dumps(p['tool_call'])}\n</tool_call>\n\n<tool_response>\n{p['tool_result']}\n</tool_response>\n\n"
    return prompt


def build_V6_two_system(p: dict) -> str:
    """Two-phase: first exchange has tools, second system drops tools entirely."""
    system1 = f"You are a code transformation tool. You receive a file with a marked selection and an instruction.\nYou output ONLY the code that replaces the selection.\n\n{TOOL_SPEC}\n\n{ORDER89_RULES}"
    user_msg = make_user_msg(p)
    system2 = f"You are a code transformation tool. The information you requested has been provided. Now produce ONLY the code output as specified in the rules.\n\n{ORDER89_RULES}"
    prompt = f"<|im_start|>system\n{system1}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"
    prompt += f"<|im_start|>assistant\n<tool_call>\n{json.dumps(p['tool_call'])}\n</tool_call><|im_end|>\n"
    prompt += f"<|im_start|>user\n<tool_response>\n{p['tool_result']}\n</tool_response><|im_end|>\n"
    prompt += f"<|im_start|>system\n{system2}<|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt


def build_V7_result_context(p: dict) -> str:
    """Tool result injected as an Order89ContextFile — no tool framing at all."""
    system = f"You are a code transformation tool. You receive a file with a marked selection and an instruction.\nYou output ONLY the code that replaces the selection.\n\n{TOOL_SPEC}\n\n{ORDER89_RULES}"
    # Wrap result as a context file
    query = p['tool_call']['arguments']['query']
    extra = f"\n<Order89Context>\nThe following files are referenced by the file under edit. Use them to understand\nthe APIs and types available.\n\n<Order89ContextFile path=\"FileSearch results for '{query}'\">\n{p['tool_result']}\n</Order89ContextFile>\n</Order89Context>\n"
    user_msg = make_user_msg(p, extra_context=extra)
    prompt = f"<|im_start|>system\n{system}<|im_end|>\n"
    prompt += f"<|im_start|>user\n{user_msg}<|im_end|>\n"
    prompt += "<|im_start|>assistant\n"
    return prompt


VARIANTS = {
    "V1_multiturn":     build_V1_multiturn,
    "V2_inline_result": build_V2_inline_result,
    "V3_system_inject": build_V3_system_inject,
    "V4_tool_role":     build_V4_tool_role,
    "V5_assistant_cont": build_V5_assistant_cont,
    "V6_two_system":    build_V6_two_system,
    "V7_result_context": build_V7_result_context,
}


# ─── Helpers ──────────────────────────────────────────────────────────────

def tokenize(text: str) -> int:
    data = json.dumps({"content": text}).encode()
    req = urllib.request.Request(f"{SERVER}/tokenize", data=data,
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return len(json.loads(resp.read()).get("tokens", []))
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
            return json.loads(resp.read()).get("content", "")
    except Exception as e:
        print(f"  [WARN] completion failed: {e}", file=sys.stderr)
        return None


def extract_code_block(response: str) -> Optional[str]:
    m = re.search(r'```\w*\n(.*?)```', response, re.DOTALL)
    return m.group(1).strip() if m else None


def has_tool_call(response: str) -> bool:
    return "<tool_call>" in response


def score(response: Optional[str], p: dict) -> dict:
    if not response:
        return {"has_code": False, "called_tool": False, "matches": 0, "total": len(p["expected_code_contains"]), "raw": "(no response)", "code": None}

    called = has_tool_call(response)
    code = extract_code_block(response)
    expected = p["expected_code_contains"]
    matches = sum(1 for kw in expected if code and kw.lower() in code.lower()) if code else 0

    return {
        "has_code": code is not None,
        "called_tool": called,
        "matches": matches,
        "total": len(expected),
        "perfect": code is not None and matches == len(expected) and not called,
        "raw": response[:500],
        "code": code[:300] if code else None,
    }


# ─── Main ──────────────────────────────────────────────────────────────────

def main():
    print("=" * 70)
    print("Order 89 Tool Loop Fix — Prompt Variant Comparison")
    print(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}")
    print(f"Variants: {len(VARIANTS)} | Prompts: {len(PROMPTS)} | Runs: {RUNS_PER_PROMPT}")
    print(f"Total calls: {len(VARIANTS) * len(PROMPTS) * RUNS_PER_PROMPT}")
    print("=" * 70)

    # Measure prompt token overhead per variant (using T1)
    print("\n--- Prompt Token Counts (T1) ---")
    token_counts = {}
    for vname, builder in VARIANTS.items():
        prompt = builder(PROMPTS[0])
        tokens = tokenize(prompt)
        token_counts[vname] = tokens
        print(f"  {vname:22s} → {tokens:5d} tokens")

    # Run
    print("\n--- Completions ---")
    all_results = {}
    detailed_log = []

    for vname, builder in VARIANTS.items():
        v_results = []
        print(f"\n  [{vname}]")

        for p in PROMPTS:
            prompt = builder(p)
            scores = []

            for run in range(RUNS_PER_PROMPT):
                response = complete(prompt)
                s = score(response, p)
                scores.append(s)

                if s["perfect"]:
                    sys.stdout.write("✓")
                elif s["has_code"] and not s["called_tool"]:
                    sys.stdout.write("~")
                elif s["called_tool"]:
                    sys.stdout.write("✗")
                else:
                    sys.stdout.write("?")
                sys.stdout.flush()
                time.sleep(0.3)

                detailed_log.append({"variant": vname, "prompt": p["id"], "run": run + 1, **s})

            n_perfect = sum(1 for s in scores if s["perfect"])
            n_code = sum(1 for s in scores if s["has_code"])
            n_tool = sum(1 for s in scores if s["called_tool"])
            avg_match = sum(s["matches"] for s in scores) / len(scores)
            v_results.append({
                "prompt_id": p["id"], "perfect": n_perfect, "has_code": n_code,
                "called_tool": n_tool, "avg_match": avg_match, "total_expected": scores[0]["total"],
            })
            print(f" {p['id']:25s} perfect={n_perfect}/{RUNS_PER_PROMPT} code={n_code} tool_loop={n_tool} match={avg_match:.1f}/{scores[0]['total']}")

        all_results[vname] = v_results

    # Summary
    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)

    print(f"\n{'Variant':<22s} {'Tokens':>6s} {'Perfect':>8s} {'Code':>5s} {'Loop':>5s} {'AvgMatch':>9s}")
    print("-" * 62)
    summary_rows = []
    for vname in VARIANTS:
        tokens = token_counts[vname]
        results = all_results[vname]
        total = len(PROMPTS) * RUNS_PER_PROMPT
        perfect = sum(r["perfect"] for r in results)
        code = sum(r["has_code"] for r in results)
        loop = sum(r["called_tool"] for r in results)
        avg_match = sum(r["avg_match"] for r in results) / len(results)
        max_exp = results[0]["total_expected"]
        summary_rows.append({"variant": vname, "tokens": tokens, "perfect": perfect, "total": total,
                             "code": code, "loop": loop, "avg_match": avg_match, "max_exp": max_exp})
        print(f"{vname:<22s} {tokens:>6d} {perfect:>4d}/{total:<3d} {code:>5d} {loop:>5d} {avg_match:>6.1f}/{max_exp}")

    # Rank
    summary_rows.sort(key=lambda r: (-r["perfect"], r["loop"], r["tokens"]))
    print(f"\nRanking (by perfect, then fewer loops, then fewer tokens):")
    for i, r in enumerate(summary_rows, 1):
        print(f"  {i}. {r['variant']:<22s} perfect={r['perfect']}/{r['total']} loop={r['loop']} tokens={r['tokens']}")

    # Write output
    with open(OUTPUT_FILE, "w") as f:
        f.write("Order 89 Tool Loop Fix — Detailed Results\n")
        f.write(f"Model: Qwen3-Coder-30B-A3B-Instruct @ {SERVER}\n")
        f.write(f"Date: {time.strftime('%Y-%m-%d %H:%M')}\n")
        f.write("=" * 70 + "\n\n")

        f.write("SUMMARY\n")
        f.write(f"{'Variant':<22s} {'Tokens':>6s} {'Perfect':>8s} {'Code':>5s} {'Loop':>5s} {'AvgMatch':>9s}\n")
        f.write("-" * 62 + "\n")
        for r in summary_rows:
            f.write(f"{r['variant']:<22s} {r['tokens']:>6d} {r['perfect']:>4d}/{r['total']:<3d} {r['code']:>5d} {r['loop']:>5d} {r['avg_match']:>6.1f}/{r['max_exp']}\n")

        f.write("\n\nPER-PROMPT BREAKDOWN\n")
        f.write("-" * 70 + "\n")
        for vname in VARIANTS:
            for r in all_results[vname]:
                f.write(f"{vname:<22s} {r['prompt_id']:<25s} perf={r['perfect']}/{RUNS_PER_PROMPT} code={r['has_code']} loop={r['called_tool']} match={r['avg_match']:.1f}/{r['total_expected']}\n")

        f.write("\n\nDETAILED LOG\n")
        f.write("=" * 70 + "\n")
        for entry in detailed_log:
            f.write(f"\n--- {entry['variant']} | {entry['prompt']} | Run {entry['run']} ---\n")
            f.write(f"  perfect={entry.get('perfect', False)} has_code={entry['has_code']} called_tool={entry['called_tool']} matches={entry['matches']}/{entry['total']}\n")
            if entry.get('code'):
                f.write(f"  code: {str(entry['code']).replace(chr(10), chr(10) + '        ')}\n")
            raw = str(entry['raw']).replace('\n', '\\n')[:400]
            f.write(f"  raw: {raw}\n")

    print(f"\nDetailed results written to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
