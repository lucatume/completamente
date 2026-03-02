"""
Test harness for the sweepai/sweep-next-edit-1.5B model served by llama-server.

Tests both FIM (fill-in-the-middle) and NEP (next-edit-prediction) scenarios
using the same <|file_sep|> original/current/updated prompt format throughout.

From 01-discovery-nep-solutions.md §1 "Key Insight: FIM Through NEP":
    NEP naturally handles FIM scenarios. When original_file_contents is empty
    and file_contents contains partial code, the model sees "empty file became
    partial code" and predicts the completed version. No special FIM tokens
    (<|fim_prefix|>, <|fim_suffix|>, <|fim_middle|>) are needed — the
    original → current → updated framing already encodes the intent.

See the discovery documents for background:
  - 01-discovery-nep-solutions.md: NEP vs FIM comparison, FIM-through-NEP
  - 05-discovery-sweep-approach.md: Sweep prompt format, fixed 21-line window

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 08-harness-test-sweepai-model.py
"""

import sys
import time
from typing import Callable

import httpx

BASE_URL = "http://127.0.0.1:8017"
TIMEOUT = 60.0

__all__ = [
    "BASE_URL",
    "TIMEOUT",
    "completion_request",
    "build_prompt",
    "print_section",
    "print_result",
    "run_tests",
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def completion_request(prompt: str, max_tokens: int = 512, temperature: float = 0.0,
                       stop: list[str] | None = None) -> dict:
    """Send a /completion request to llama-server."""
    payload = {
        "prompt": prompt,
        "n_predict": max_tokens,
        "temperature": temperature,
        "stop": stop or ["<|file_sep|>", "</s>"],
        "stream": False,
    }
    resp = httpx.post(f"{BASE_URL}/completion", json=payload, timeout=TIMEOUT)
    resp.raise_for_status()
    return resp.json()


def build_prompt(
    context_files: dict[str, str],
    recent_diffs: list[dict[str, str]],
    file_path: str,
    original_content: str,
    current_content: str,
) -> str:
    """Build a Sweep-style original/current/updated prompt.

    Used for both FIM and NEP scenarios. The format is identical — only the
    inputs differ:

    FIM:  original="" (empty/no prior state), current=incomplete code
    NEP:  original=file before edit,          current=file after partial edit

    Follows the format documented in 05-discovery-sweep-approach.md §1:
        <|file_sep|>{path}            — context files
        <|file_sep|>{path}.diff       — recent diffs (original:/updated:)
        <|file_sep|>original/{path}   — file before the most recent change
        <|file_sep|>current/{path}    — current file state
        <|file_sep|>updated/{path}    — model generates this
    """
    parts: list[str] = []

    for path, content in context_files.items():
        parts.append(f"<|file_sep|>{path}")
        parts.append(content)

    for diff in recent_diffs:
        parts.append(f"<|file_sep|>{diff['file_path']}.diff")
        parts.append("original:")
        parts.append(diff["original"])
        parts.append("updated:")
        parts.append(diff["updated"])

    parts.append(f"<|file_sep|>original/{file_path}")
    parts.append(original_content)
    parts.append(f"<|file_sep|>current/{file_path}")
    parts.append(current_content)
    parts.append(f"<|file_sep|>updated/{file_path}")

    return "\n".join(parts)


def print_section(title: str) -> None:
    print(f"\n{'=' * 80}")
    print(f"  {title}")
    print("=" * 80)


def print_result(label: str, text: str, elapsed: float) -> None:
    print(f"\n--- {label} ({elapsed:.2f}s) ---")
    print(text)
    print("---")


# ---------------------------------------------------------------------------
# FIM tests — uses original/current/updated format with original=""
#
# From 01-discovery-nep-solutions.md "Key Insight: FIM Through NEP":
# The model sees "empty → partial" and predicts the completed version.
# For suffix context, the current content is the full file (with the
# incomplete code in place), and the model predicts the corrected version.
# ---------------------------------------------------------------------------

def test_fim_function_body():
    """FIM: complete a function body given signature and a call site below."""
    print_section("FIM: Complete function body")

    file_path = "even.py"
    original_content = ""
    current_content = """def is_even(n):


is_even(4)"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)

    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start

    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_middle_of_expression():
    """FIM: fill in the middle of a conditional expression."""
    print_section("FIM: Fill middle of conditional")

    file_path = "classify.py"
    original_content = ""
    current_content = """def classify_age(age):
    if age < 0:
        return "invalid"
    elif :
        return "senior"
    else:
        return "adult"
"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)

    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start

    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_import_statement():
    """FIM: complete an import given its usage below."""
    print_section("FIM: Complete import statement")

    file_path = "format_date.py"
    original_content = ""
    current_content = """from datetime import

def format_today():
    return datetime.now().strftime("%Y-%m-%d")
"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)

    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start

    print_result("Predicted updated file", result["content"], elapsed)


# ---------------------------------------------------------------------------
# NEP tests — uses original/current/updated format with actual edit history
# (see 05-discovery-sweep-approach.md §1 and 01-discovery-nep-solutions.md §1)
# ---------------------------------------------------------------------------

def test_nep_add_parameter_usage():
    """NEP: after adding a parameter, predict using it in the body.

    This is the canonical NEP scenario from 05-discovery-sweep-approach.md:
    the user added a `name` parameter and imported a utility — the model
    should predict incorporating both into the function body.
    """
    print_section("NEP: Add parameter usage (canonical example)")

    file_path = "greet.py"

    context_files = {
        "utils.py": """def get_time_of_day():
    from datetime import datetime
    hour = datetime.now().hour
    if hour < 12:
        return "morning"
    elif hour < 18:
        return "afternoon"
    else:
        return "evening"
""",
    }

    recent_diffs = [
        {
            "file_path": "greet.py",
            "original": 'def greet():\n    print("Hello!")',
            "updated": 'def greet(name):\n    print(f"Hello, {name}!")',
        }
    ]

    original_content = """def greet(name):
    print(f"Hello, {name}!")

greet("Alice")"""

    current_content = """from utils import get_time_of_day

def greet(name):
    print(f"Hello, {name}!")

greet("Alice")"""

    prompt = build_prompt(context_files, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start

    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_rename_variable():
    """NEP: after renaming a variable in its declaration, predict renaming usages."""
    print_section("NEP: Rename variable propagation")

    file_path = "calc.py"

    recent_diffs = [
        {
            "file_path": "calc.py",
            "original": "    result = a + b",
            "updated": "    total = a + b",
        }
    ]

    original_content = """def add(a, b):
    result = a + b
    print(f"The result is {result}")
    return result"""

    current_content = """def add(a, b):
    total = a + b
    print(f"The result is {result}")
    return result"""

    prompt = build_prompt({}, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start

    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_add_error_handling():
    """NEP: after adding a try block, predict the except clause."""
    print_section("NEP: Add error handling")

    file_path = "io_utils.py"

    recent_diffs = [
        {
            "file_path": "io_utils.py",
            "original": "def read_config(path):\n    with open(path) as f:",
            "updated": "def read_config(path):\n    try:\n        with open(path) as f:",
        }
    ]

    original_content = """def read_config(path):
    with open(path) as f:
        return json.load(f)"""

    current_content = """def read_config(path):
    try:
        with open(path) as f:
            return json.load(f)"""

    prompt = build_prompt({}, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start

    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_new_file_completion():
    """NEP: new file scenario — demonstrates FIM through NEP.

    From 01-discovery-nep-solutions.md §1 "Key Insight: FIM Through NEP":
    when original_file_contents is empty and current has partial code, the
    model sees "empty → partial" and predicts the completed version.
    """
    print_section("NEP: New file completion (FIM through NEP)")

    file_path = "utils.js"
    original_content = ""
    current_content = "function isEven("

    prompt = build_prompt({}, [], file_path, original_content, current_content)

    print("ORIGINAL: (empty — new file)")
    print(f"\nCURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start

    print_result("Predicted updated file", result["content"], elapsed)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

ALL_TESTS = [
    # FIM via NEP format (original="" → current=incomplete code)
    test_fim_function_body,
    test_fim_middle_of_expression,
    test_fim_import_statement,
    # NEP (original=before edit → current=after partial edit)
    test_nep_add_parameter_usage,
    test_nep_rename_variable,
    test_nep_add_error_handling,
    test_nep_new_file_completion,
]


def run_tests(tests: list[Callable]) -> int:
    """Run a list of test functions after checking the server is reachable.

    Returns 0 on success, 1 if the server is unreachable.
    Importable by language-specific harness scripts.
    """
    try:
        httpx.get(f"{BASE_URL}/health", timeout=5.0).raise_for_status()
    except (httpx.ConnectError, httpx.HTTPStatusError) as e:
        print(f"Error: cannot reach llama-server at {BASE_URL}: {e}", file=sys.stderr)
        print("Start it first with: ./07-harness-sweepai-llama-cpp-server.sh", file=sys.stderr)
        return 1

    print(f"Server reachable at {BASE_URL}")

    for test_fn in tests:
        try:
            test_fn()
        except Exception as e:
            print(f"\n!! Test {test_fn.__name__} failed: {e}", file=sys.stderr)

    print_section("All tests completed")
    return 0


def main() -> int:
    return run_tests(ALL_TESTS)


if __name__ == "__main__":
    sys.exit(main())
