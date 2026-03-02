"""
Test harness for the sweepai/sweep-next-edit-1.5B model — CSS examples.

Tests both FIM (fill-in-the-middle) and NEP (next-edit-prediction) scenarios
using CSS file examples. Uses the shared helpers from 08-harness-test-sweepai-model.

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 12-harness-test-sweepai-model-css.py
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
# FIM tests — original="" → current=incomplete CSS code
# ---------------------------------------------------------------------------

def test_fim_property_value():
    """FIM: complete a CSS property value."""
    print_section("FIM: Complete property value (CSS)")

    file_path = "button.css"
    original_content = ""
    current_content = """.btn {
    display: inline-block;
    padding: 8px 16px;
    background-color: ;
    color: white;
    border-radius: 4px;
    cursor: pointer;
}

.btn:hover {
    opacity: 0.9;
}"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)
    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_media_query_body():
    """FIM: complete a media query body."""
    print_section("FIM: Complete media query body (CSS)")

    file_path = "responsive.css"
    original_content = ""
    current_content = """.container {
    max-width: 1200px;
    margin: 0 auto;
    padding: 0 16px;
}

@media (max-width: 768px) {

}"""

    prompt = build_prompt({}, [], file_path, original_content, current_content)
    print(f"CURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_selector_block():
    """FIM: complete a selector block given context."""
    print_section("FIM: Complete selector block (CSS)")

    file_path = "card.css"
    original_content = ""
    current_content = """.card {
    border: 1px solid #ddd;
    border-radius: 8px;
    overflow: hidden;
}

.card-header {
    padding: 16px;
    background-color: #f5f5f5;
    font-weight: bold;
}

.card-body {

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

def test_nep_add_dark_mode():
    """NEP: after adding a CSS variable for dark mode, predict the rest."""
    print_section("NEP: Add dark mode variables (CSS)")

    file_path = "theme.css"

    recent_diffs = [
        {
            "file_path": "theme.css",
            "original": ":root {\n    --bg-color: #ffffff;\n    --text-color: #333333;",
            "updated": ":root {\n    --bg-color: #ffffff;\n    --text-color: #333333;\n}\n\n@media (prefers-color-scheme: dark) {\n    :root {\n        --bg-color: #1a1a1a;",
        }
    ]

    original_content = """:root {
    --bg-color: #ffffff;
    --text-color: #333333;
}

body {
    background-color: var(--bg-color);
    color: var(--text-color);
}"""

    current_content = """:root {
    --bg-color: #ffffff;
    --text-color: #333333;
}

@media (prefers-color-scheme: dark) {
    :root {
        --bg-color: #1a1a1a;
    }
}

body {
    background-color: var(--bg-color);
    color: var(--text-color);
}"""

    prompt = build_prompt({}, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_rename_variable():
    """NEP: after renaming a CSS custom property, predict renaming usages."""
    print_section("NEP: Rename CSS variable propagation (CSS)")

    file_path = "variables.css"

    recent_diffs = [
        {
            "file_path": "variables.css",
            "original": "    --primary: #3498db;",
            "updated": "    --brand-primary: #3498db;",
        }
    ]

    original_content = """:root {
    --primary: #3498db;
}

.btn-primary {
    background-color: var(--primary);
    border-color: var(--primary);
}

a {
    color: var(--primary);
}"""

    current_content = """:root {
    --brand-primary: #3498db;
}

.btn-primary {
    background-color: var(--primary);
    border-color: var(--primary);
}

a {
    color: var(--primary);
}"""

    prompt = build_prompt({}, recent_diffs, file_path,
                          original_content, current_content)

    print("ORIGINAL:\n" + original_content)
    print("\nCURRENT:\n" + current_content)

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_nep_add_hover_state():
    """NEP: after adding a transition, predict the hover state."""
    print_section("NEP: Add hover state (CSS)")

    file_path = "link.css"

    recent_diffs = [
        {
            "file_path": "link.css",
            "original": ".link {\n    color: #3498db;\n    text-decoration: none;",
            "updated": ".link {\n    color: #3498db;\n    text-decoration: none;\n    transition: color 0.2s ease;",
        }
    ]

    original_content = """.link {
    color: #3498db;
    text-decoration: none;
}"""

    current_content = """.link {
    color: #3498db;
    text-decoration: none;
    transition: color 0.2s ease;
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
    print_section("NEP: New file completion (FIM through NEP, CSS)")

    file_path = "reset.css"
    original_content = ""
    current_content = """* {
    margin: 0;
    padding: 0;
    box-sizing:"""

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
    test_fim_property_value,
    test_fim_media_query_body,
    test_fim_selector_block,
    test_nep_add_dark_mode,
    test_nep_rename_variable,
    test_nep_add_hover_state,
    test_nep_new_file_completion,
]

if __name__ == "__main__":
    sys.exit(run_tests(ALL_TESTS))
