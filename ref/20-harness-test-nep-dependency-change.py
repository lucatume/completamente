"""
Test harness for the sweepai/sweep-next-edit-1.5B model — NEP dependency change.

Isolated test for the scenario where a dependency file's signature changed and
the model must predict updating the caller accordingly.

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 20-harness-test-nep-dependency-change.py
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


def test_nep_update_method_after_dependency_change():
    """NEP: after a dependency's return type changed, predict updating the caller."""
    print_section("NEP: Update caller after dependency signature change (PHP)")

    file_path = "ReportGenerator.php"

    context_files = {
        "DataFetcher.php": """<?php

namespace App\\Services;

class DataFetcher
{
    /**
     * @return array{data: array, metadata: array{total: int, page: int}}
     */
    public function fetchUsers(int $page = 1, int $perPage = 50): array
    {
        // Returns paginated result with metadata
    }
}""",
    }

    recent_diffs = [
        # Edit 1: add docblock to fetchUsers
        {
            "file_path": "DataFetcher.php",
            "original": "    public function fetchUsers(): array\n    {\n        // Returns flat user array\n    }",
            "updated": "    /**\n     * @return array\n     */\n    public function fetchUsers(): array\n    {\n        // Returns flat user array\n    }",
        },
        # Edit 2: change return type annotation to paginated shape
        {
            "file_path": "DataFetcher.php",
            "original": "    /**\n     * @return array\n     */\n    public function fetchUsers(): array\n    {\n        // Returns flat user array\n    }",
            "updated": "    /**\n     * @return array{data: array, metadata: array{total: int, page: int}}\n     */\n    public function fetchUsers(): array\n    {\n        // Returns paginated result with metadata\n    }",
        },
        # Edit 3: add pagination parameters to signature
        {
            "file_path": "DataFetcher.php",
            "original": "    public function fetchUsers(): array\n    {\n        // Returns paginated result with metadata\n    }",
            "updated": "    public function fetchUsers(int $page = 1, int $perPage = 50): array\n    {\n        // Returns paginated result with metadata\n    }",
        },
    ]

    original_content = """<?php

namespace App\\Reports;

use App\\Services\\DataFetcher;

class ReportGenerator
{
    private DataFetcher $fetcher;

    public function __construct(DataFetcher $fetcher)
    {
        $this->fetcher = $fetcher;
    }

    public function generateUserReport(): string
    {
        $users = $this->fetcher->fetchUsers();
        $count = count($users);
        return "Total users: {$count}";
    }
}"""

    current_content = """<?php

namespace App\\Reports;

use App\\Services\\DataFetcher;

class ReportGenerator
{
    private DataFetcher $fetcher;

    public function __construct(DataFetcher $fetcher)
    {
        $this->fetcher = $fetcher;
    }

    public function generateUserReport(): string
    {
        $users = $this->fetcher->fetchUsers();
        $count = count($users);
        return "Total users: {$count}";
    }
}"""

    prompt = build_prompt(context_files, recent_diffs, file_path,
                          original_content, current_content)

    print(f"CONTEXT FILES: {list(context_files.keys())}")
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
    test_nep_update_method_after_dependency_change,
]

if __name__ == "__main__":
    sys.exit(run_tests(ALL_TESTS))
