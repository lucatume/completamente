"""
Test harness for the sweepai/sweep-next-edit-1.5B model — JavaScript examples.

Tests both FIM (fill-in-the-middle) and NEP (next-edit-prediction) scenarios
using JavaScript file examples. Uses the shared helpers from 08-harness-test-sweepai-model.

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 10-harness-test-sweepai-model-js.py
"""

import sys
import time

import importlib
harness = importlib.import_module("08-harness-test-sweepai-model")

build_prompt = harness.build_prompt
completion_request = harness.completion_request
print_section = harness.print_section
print_result = harness.print_result
run_tests = harness.run_tests


# ---------------------------------------------------------------------------
# FIM tests — original="" → current=incomplete JS code
# ---------------------------------------------------------------------------

def test_fim_function_body():
    """FIM: complete a function body given signature and a call site below."""
    print_section("FIM: Complete function body (JS)")

    file_path = "even.js"
    original_content = ""
    current_content = """function isEven(n) {

}

console.log(isEven(4));"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)
    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_middle_of_expression():
    """FIM: fill in the middle of a conditional expression."""
    print_section("FIM: Fill middle of conditional (JS)")

    file_path = "classify.js"
    original_content = ""
    current_content = """function classifyAge(age) {
    if (age < 0) {
        return 'invalid';
    } else if () {
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
    """FIM: complete an import given its usage below."""
    print_section("FIM: Complete import statement (JS)")

    file_path = "format_date.js"
    original_content = ""
    current_content = """import { } from 'date-fns';

function formatToday() {
    return format(new Date(), 'yyyy-MM-dd');
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
    print_section("NEP: Add parameter usage (JS)")

    file_path = "greet.js"

    context_files = {
        "utils.js": """export function getTimeOfDay() {
    const hour = new Date().getHours();
    if (hour < 12) {
        return 'morning';
    } else if (hour < 18) {
        return 'afternoon';
    } else {
        return 'evening';
    }
}""",
    }

    recent_diffs = [
        {
            "file_path": "greet.js",
            "original": 'function greet() {\n    console.log("Hello!");',
            "updated": 'function greet(name) {\n    console.log(`Hello, ${name}!`);',
        }
    ]

    original_content = """function greet(name) {
    console.log(`Hello, ${name}!`);
}

greet('Alice');"""

    current_content = """import { getTimeOfDay } from './utils.js';

function greet(name) {
    console.log(`Hello, ${name}!`);
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
    print_section("NEP: Rename variable propagation (JS)")

    file_path = "calc.js"

    recent_diffs = [
        {
            "file_path": "calc.js",
            "original": "    const result = a + b;",
            "updated": "    const total = a + b;",
        }
    ]

    original_content = """function add(a, b) {
    const result = a + b;
    console.log(`The result is ${result}`);
    return result;
}"""

    current_content = """function add(a, b) {
    const total = a + b;
    console.log(`The result is ${result}`);
    return result;
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
    print_section("NEP: Add error handling (JS)")

    file_path = "io_utils.js"

    recent_diffs = [
        {
            "file_path": "io_utils.js",
            "original": "async function readConfig(path) {\n    const data = await fs.readFile(path, 'utf-8');",
            "updated": "async function readConfig(path) {\n    try {\n        const data = await fs.readFile(path, 'utf-8');",
        }
    ]

    original_content = """import fs from 'fs/promises';

async function readConfig(path) {
    const data = await fs.readFile(path, 'utf-8');
    return JSON.parse(data);
}"""

    current_content = """import fs from 'fs/promises';

async function readConfig(path) {
    try {
        const data = await fs.readFile(path, 'utf-8');
        return JSON.parse(data);
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
    print_section("NEP: New file completion (FIM through NEP, JS)")

    file_path = "helpers.js"
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
