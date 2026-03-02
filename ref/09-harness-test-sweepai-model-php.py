"""
Test harness for the sweepai/sweep-next-edit-1.5B model — PHP examples.

Tests both FIM (fill-in-the-middle) and NEP (next-edit-prediction) scenarios
using PHP file examples. Uses the shared helpers from 08-harness-test-sweepai-model.

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 09-harness-test-sweepai-model-php.py
"""

import sys
import time

# The module name uses hyphens on disk; importlib handles that.
import importlib
harness = importlib.import_module("08-harness-test-sweepai-model")

build_prompt = harness.build_prompt
completion_request = harness.completion_request
print_section = harness.print_section
print_result = harness.print_result
run_tests = harness.run_tests


# ---------------------------------------------------------------------------
# FIM tests — original="" → current=incomplete PHP code
# ---------------------------------------------------------------------------

def test_fim_function_body():
    """FIM: complete a function body given signature and a call site below."""
    print_section("FIM: Complete function body (PHP)")

    file_path = "even.php"
    original_content = ""
    current_content = """<?php

function isEven(int $n): bool
{

}

echo isEven(4) ? 'true' : 'false';"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)
    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_middle_of_expression():
    """FIM: fill in the middle of a conditional expression."""
    print_section("FIM: Fill middle of conditional (PHP)")

    file_path = "classify.php"
    original_content = ""
    current_content = """<?php

function classifyAge(int $age): string
{
    if ($age < 0) {
        return 'invalid';
    } elseif () {
        return 'senior';
    } else {
        return 'adult';
    }
}"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)
    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_import_statement():
    """FIM: complete a use/require statement given its usage below."""
    print_section("FIM: Complete use statement (PHP)")

    file_path = "format_date.php"
    original_content = ""
    current_content = """<?php

use DateTime

function formatToday(): string
{
    $now = new DateTime();
    return $now->format('Y-m-d');
}"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)
    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


# ---------------------------------------------------------------------------
# NEP tests — original=before edit → current=after partial edit
# ---------------------------------------------------------------------------

def test_nep_add_parameter_usage():
    """NEP: after adding a parameter, predict using it in the body."""
    print_section("NEP: Add parameter usage (PHP)")

    file_path = "greet.php"

    context_files = {
        "utils.php": """<?php

function getTimeOfDay(): string
{
    $hour = (int) date('G');
    if ($hour < 12) {
        return 'morning';
    } elseif ($hour < 18) {
        return 'afternoon';
    } else {
        return 'evening';
    }
}""",
    }

    recent_diffs = [
        {
            "file_path": "greet.php",
            "original": 'function greet(): void\n{\n    echo "Hello!";',
            "updated": 'function greet(string $name): void\n{\n    echo "Hello, {$name}!";',
        }
    ]

    original_content = """<?php

function greet(string $name): void
{
    echo "Hello, {$name}!";
}

greet('Alice');"""

    current_content = """<?php

require_once 'utils.php';

function greet(string $name): void
{
    echo "Hello, {$name}!";
}

greet('Alice');"""

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
    print_section("NEP: Rename variable propagation (PHP)")

    file_path = "calc.php"

    recent_diffs = [
        {
            "file_path": "calc.php",
            "original": "    $result = $a + $b;",
            "updated": "    $total = $a + $b;",
        }
    ]

    original_content = """<?php

function add(int $a, int $b): int
{
    $result = $a + $b;
    echo "The result is {$result}";
    return $result;
}"""

    current_content = """<?php

function add(int $a, int $b): int
{
    $total = $a + $b;
    echo "The result is {$result}";
    return $result;
}"""

    prompt = build_prompt({}, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_add_error_handling():
    """NEP: after adding a try block, predict the catch clause."""
    print_section("NEP: Add error handling (PHP)")

    file_path = "io_utils.php"

    recent_diffs = [
        {
            "file_path": "io_utils.php",
            "original": "function readConfig(string $path): array\n{\n    $content = file_get_contents($path);",
            "updated": "function readConfig(string $path): array\n{\n    try {\n        $content = file_get_contents($path);",
        }
    ]

    original_content = """<?php

function readConfig(string $path): array
{
    $content = file_get_contents($path);
    return json_decode($content, true);
}"""

    current_content = """<?php

function readConfig(string $path): array
{
    try {
        $content = file_get_contents($path);
        return json_decode($content, true);
    }
}"""

    prompt = build_prompt({}, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_new_file_completion():
    """NEP: new file scenario — demonstrates FIM through NEP."""
    print_section("NEP: New file completion (FIM through NEP, PHP)")

    file_path = "helpers.php"
    original_content = ""
    current_content = "<?php\n\nfunction isEven("

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
    test_fim_function_body,
    test_fim_middle_of_expression,
    test_fim_import_statement,
    test_nep_add_parameter_usage,
    test_nep_rename_variable,
    test_nep_add_error_handling,
    test_nep_new_file_completion,
]

if __name__ == "__main__":
    sys.exit(run_tests(ALL_TESTS))
