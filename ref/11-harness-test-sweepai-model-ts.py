"""
Test harness for the sweepai/sweep-next-edit-1.5B model — TypeScript examples.

Tests both FIM (fill-in-the-middle) and NEP (next-edit-prediction) scenarios
using TypeScript file examples. Uses the shared helpers from 08-harness-test-sweepai-model.

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 11-harness-test-sweepai-model-ts.py
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
# FIM tests — original="" → current=incomplete TS code
# ---------------------------------------------------------------------------

def test_fim_function_body():
    """FIM: complete a function body given signature and a call site below."""
    print_section("FIM: Complete function body (TS)")

    file_path = "even.ts"
    original_content = ""
    current_content = """function isEven(n: number): boolean {

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
    print_section("FIM: Fill middle of conditional (TS)")

    file_path = "classify.ts"
    original_content = ""
    current_content = """function classifyAge(age: number): string {
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
    print_section("FIM: Complete import statement (TS)")

    file_path = "format_date.ts"
    original_content = ""
    current_content = """import { } from 'date-fns';

function formatToday(): string {
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
    """NEP: after adding a typed parameter, predict using it in the body."""
    print_section("NEP: Add parameter usage (TS)")

    file_path = "greet.ts"

    context_files = {
        "utils.ts": """export function getTimeOfDay(): string {
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
            "file_path": "greet.ts",
            "original": 'function greet(): void {\n    console.log("Hello!");',
            "updated": 'function greet(name: string): void {\n    console.log(`Hello, ${name}!`);',
        }
    ]

    original_content = """function greet(name: string): void {
    console.log(`Hello, ${name}!`);
}

greet('Alice');"""

    current_content = """import { getTimeOfDay } from './utils';

function greet(name: string): void {
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
    print_section("NEP: Rename variable propagation (TS)")

    file_path = "calc.ts"

    recent_diffs = [
        {
            "file_path": "calc.ts",
            "original": "    const result: number = a + b;",
            "updated": "    const total: number = a + b;",
        }
    ]

    original_content = """function add(a: number, b: number): number {
    const result: number = a + b;
    console.log(`The result is ${result}`);
    return result;
}"""

    current_content = """function add(a: number, b: number): number {
    const total: number = a + b;
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
    print_section("NEP: Add error handling (TS)")

    file_path = "io_utils.ts"

    recent_diffs = [
        {
            "file_path": "io_utils.ts",
            "original": "async function readConfig(path: string): Promise<Record<string, unknown>> {\n    const data = await fs.readFile(path, 'utf-8');",
            "updated": "async function readConfig(path: string): Promise<Record<string, unknown>> {\n    try {\n        const data = await fs.readFile(path, 'utf-8');",
        }
    ]

    original_content = """import fs from 'fs/promises';

async function readConfig(path: string): Promise<Record<string, unknown>> {
    const data = await fs.readFile(path, 'utf-8');
    return JSON.parse(data);
}"""

    current_content = """import fs from 'fs/promises';

async function readConfig(path: string): Promise<Record<string, unknown>> {
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
    print_section("NEP: New file completion (FIM through NEP, TS)")

    file_path = "helpers.ts"
    original_content = ""
    current_content = "function isEven(n: number): boolean {"

    prompt = build_prompt({}, [], file_path, original_content, current_content)

    print("ORIGINAL: (empty — new file)")
    print(f"\nCURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_add_interface_field():
    """NEP: after adding a field to an interface, predict updating the implementation."""
    print_section("NEP: Add interface field (TS)")

    file_path = "user.ts"

    recent_diffs = [
        {
            "file_path": "user.ts",
            "original": "interface User {\n    name: string;\n    age: number;\n}",
            "updated": "interface User {\n    name: string;\n    age: number;\n    email: string;\n}",
        }
    ]

    original_content = """interface User {
    name: string;
    age: number;
}

function createUser(name: string, age: number): User {
    return { name, age };
}"""

    current_content = """interface User {
    name: string;
    age: number;
    email: string;
}

function createUser(name: string, age: number): User {
    return { name, age };
}"""

    prompt = build_prompt({}, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

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
    test_nep_add_interface_field,
]

if __name__ == "__main__":
    sys.exit(run_tests(ALL_TESTS))
