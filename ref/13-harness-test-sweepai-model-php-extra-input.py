"""
Test harness for the sweepai/sweep-next-edit-1.5B model — PHP with extra input.

Tests FIM and NEP scenarios where the PHP file being edited depends on other
files. The content of those dependency files is passed as context_files so the
model can use type information, function signatures, and class structures from
the project when predicting completions.

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 13-harness-test-sweepai-model-php-extra-input.py
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
# FIM tests with dependency context
# ---------------------------------------------------------------------------

def test_fim_use_interface_method():
    """FIM: complete a class method using an interface defined in another file."""
    print_section("FIM: Complete method using interface from dependency (PHP)")

    file_path = "UserRepository.php"
    original_content = ""

    context_files = {
        "RepositoryInterface.php": """<?php

namespace App\\Contracts;

interface RepositoryInterface
{
    public function find(int $id): ?array;
    public function findAll(): array;
    public function create(array $data): int;
    public function update(int $id, array $data): bool;
    public function delete(int $id): bool;
}""",
        "Database.php": """<?php

namespace App\\Database;

class Database
{
    private \\PDO $pdo;

    public function __construct(string $dsn, string $user, string $pass)
    {
        $this->pdo = new \\PDO($dsn, $user, $pass);
    }

    public function query(string $sql, array $params = []): array
    {
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        return $stmt->fetchAll(\\PDO::FETCH_ASSOC);
    }

    public function execute(string $sql, array $params = []): int
    {
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        return $stmt->rowCount();
    }
}""",
    }

    current_content = """<?php

namespace App\\Repositories;

use App\\Contracts\\RepositoryInterface;
use App\\Database\\Database;

class UserRepository implements RepositoryInterface
{
    private Database $db;

    public function __construct(Database $db)
    {
        $this->db = $db;
    }

    public function find(int $id): ?array
    {

    }
}"""

    prompt = build_prompt(context_files, [], file_path, original_content, current_content)
    print(f"CONTEXT FILES: {list(context_files.keys())}")
    print(f"\nCURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_call_helper_function():
    """FIM: complete code that calls functions from a helper file."""
    print_section("FIM: Complete code using helper functions (PHP)")

    file_path = "OrderService.php"
    original_content = ""

    context_files = {
        "PriceCalculator.php": """<?php

namespace App\\Services;

class PriceCalculator
{
    public function calculateSubtotal(array $items): float
    {
        return array_sum(array_map(
            fn(array $item) => $item['price'] * $item['quantity'],
            $items
        ));
    }

    public function applyDiscount(float $subtotal, float $discountPercent): float
    {
        return $subtotal * (1 - $discountPercent / 100);
    }

    public function calculateTax(float $amount, float $taxRate = 0.2): float
    {
        return round($amount * $taxRate, 2);
    }
}""",
    }

    current_content = """<?php

namespace App\\Services;

class OrderService
{
    private PriceCalculator $calculator;

    public function __construct(PriceCalculator $calculator)
    {
        $this->calculator = $calculator;
    }

    public function calculateTotal(array $items, float $discountPercent = 0): float
    {

    }
}"""

    prompt = build_prompt(context_files, [], file_path, original_content, current_content)
    print(f"CONTEXT FILES: {list(context_files.keys())}")
    print(f"\nCURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


def test_fim_extend_abstract_class():
    """FIM: complete a child class that extends an abstract parent."""
    print_section("FIM: Extend abstract class from dependency (PHP)")

    file_path = "JsonLogger.php"
    original_content = ""

    context_files = {
        "AbstractLogger.php": """<?php

namespace App\\Logging;

abstract class AbstractLogger
{
    protected string $logFile;

    public function __construct(string $logFile)
    {
        $this->logFile = $logFile;
    }

    abstract public function log(string $level, string $message, array $context = []): void;

    protected function formatTimestamp(): string
    {
        return date('Y-m-d H:i:s');
    }

    protected function write(string $formatted): void
    {
        file_put_contents($this->logFile, $formatted . PHP_EOL, FILE_APPEND);
    }
}""",
    }

    current_content = """<?php

namespace App\\Logging;

class JsonLogger extends AbstractLogger
{
    public function log(string $level, string $message, array $context = []): void
    {

    }
}"""

    prompt = build_prompt(context_files, [], file_path, original_content, current_content)
    print(f"CONTEXT FILES: {list(context_files.keys())}")
    print(f"\nCURRENT:\n{current_content}")

    start = time.time()
    result = completion_request(prompt)
    elapsed = time.time() - start
    print_result("Predicted updated file", result["content"], elapsed)


# ---------------------------------------------------------------------------
# NEP tests with dependency context
# ---------------------------------------------------------------------------

def test_nep_add_method_using_dependency():
    """NEP: after adding a new use statement, predict the method that uses the dependency."""
    print_section("NEP: Add method using newly imported dependency (PHP)")

    file_path = "UserController.php"

    context_files = {
        "Validator.php": """<?php

namespace App\\Validation;

class Validator
{
    public function validate(array $data, array $rules): array
    {
        $errors = [];
        foreach ($rules as $field => $rule) {
            if ($rule === 'required' && empty($data[$field])) {
                $errors[$field] = "{$field} is required";
            }
            if ($rule === 'email' && !filter_var($data[$field] ?? '', FILTER_VALIDATE_EMAIL)) {
                $errors[$field] = "{$field} must be a valid email";
            }
        }
        return $errors;
    }
}""",
        "UserRepository.php": """<?php

namespace App\\Repositories;

class UserRepository
{
    public function find(int $id): ?array
    {
        // ...
    }

    public function create(array $data): int
    {
        // ...
    }

    public function findByEmail(string $email): ?array
    {
        // ...
    }
}""",
    }

    recent_diffs = [
        {
            "file_path": "UserController.php",
            "original": "use App\\Repositories\\UserRepository;",
            "updated": "use App\\Repositories\\UserRepository;\nuse App\\Validation\\Validator;",
        }
    ]

    original_content = """<?php

namespace App\\Controllers;

use App\\Repositories\\UserRepository;

class UserController
{
    private UserRepository $users;

    public function __construct(UserRepository $users)
    {
        $this->users = $users;
    }

    public function show(int $id): array
    {
        $user = $this->users->find($id);
        if ($user === null) {
            throw new \\RuntimeException('User not found');
        }
        return $user;
    }
}"""

    current_content = """<?php

namespace App\\Controllers;

use App\\Repositories\\UserRepository;
use App\\Validation\\Validator;

class UserController
{
    private UserRepository $users;

    public function __construct(UserRepository $users)
    {
        $this->users = $users;
    }

    public function show(int $id): array
    {
        $user = $this->users->find($id);
        if ($user === null) {
            throw new \\RuntimeException('User not found');
        }
        return $user;
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
        {
            "file_path": "DataFetcher.php",
            "original": "    public function fetchUsers(): array\n    {\n        // Returns flat user array\n    }",
            "updated": "    /**\n     * @return array{data: array, metadata: array{total: int, page: int}}\n     */\n    public function fetchUsers(int $page = 1, int $perPage = 50): array\n    {\n        // Returns paginated result with metadata\n    }",
        }
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


def test_nep_implement_interface_method_with_deps():
    """NEP: after adding implements clause, predict the method bodies using dependency context."""
    print_section("NEP: Implement interface methods using dependency context (PHP)")

    file_path = "FileCache.php"

    context_files = {
        "CacheInterface.php": """<?php

namespace App\\Contracts;

interface CacheInterface
{
    public function get(string $key, mixed $default = null): mixed;
    public function set(string $key, mixed $value, int $ttl = 3600): bool;
    public function has(string $key): bool;
    public function delete(string $key): bool;
    public function clear(): bool;
}""",
        "Serializer.php": """<?php

namespace App\\Support;

class Serializer
{
    public function serialize(mixed $value): string
    {
        return serialize($value);
    }

    public function unserialize(string $data): mixed
    {
        return unserialize($data);
    }
}""",
    }

    recent_diffs = [
        {
            "file_path": "FileCache.php",
            "original": "class FileCache",
            "updated": "class FileCache implements CacheInterface",
        }
    ]

    original_content = """<?php

namespace App\\Cache;

use App\\Support\\Serializer;

class FileCache
{
    private string $dir;
    private Serializer $serializer;

    public function __construct(string $dir, Serializer $serializer)
    {
        $this->dir = $dir;
        $this->serializer = $serializer;
    }
}"""

    current_content = """<?php

namespace App\\Cache;

use App\\Contracts\\CacheInterface;
use App\\Support\\Serializer;

class FileCache implements CacheInterface
{
    private string $dir;
    private Serializer $serializer;

    public function __construct(string $dir, Serializer $serializer)
    {
        $this->dir = $dir;
        $this->serializer = $serializer;
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


def test_nep_compose_multiple_services():
    """NEP: after injecting two services, predict using them together."""
    print_section("NEP: Compose multiple dependency services (PHP)")

    file_path = "NotificationService.php"

    context_files = {
        "Mailer.php": """<?php

namespace App\\Mail;

class Mailer
{
    public function send(string $to, string $subject, string $body): bool
    {
        // sends email, returns success
    }
}""",
        "TemplateEngine.php": """<?php

namespace App\\Templating;

class TemplateEngine
{
    public function render(string $template, array $vars = []): string
    {
        // renders a template with variables, returns HTML string
    }

    public function exists(string $template): bool
    {
        // checks if a template file exists
    }
}""",
        "UserRepository.php": """<?php

namespace App\\Repositories;

class UserRepository
{
    public function find(int $id): ?array
    {
        // returns ['id' => int, 'name' => string, 'email' => string] or null
    }
}""",
    }

    recent_diffs = [
        {
            "file_path": "NotificationService.php",
            "original": "class NotificationService\n{\n    private Mailer $mailer;",
            "updated": "class NotificationService\n{\n    private Mailer $mailer;\n    private TemplateEngine $templates;\n    private UserRepository $users;",
        },
        {
            "file_path": "NotificationService.php",
            "original": "    public function __construct(Mailer $mailer)\n    {\n        $this->mailer = $mailer;\n    }",
            "updated": "    public function __construct(Mailer $mailer, TemplateEngine $templates, UserRepository $users)\n    {\n        $this->mailer = $mailer;\n        $this->templates = $templates;\n        $this->users = $users;\n    }",
        }
    ]

    original_content = """<?php

namespace App\\Notifications;

use App\\Mail\\Mailer;

class NotificationService
{
    private Mailer $mailer;

    public function __construct(Mailer $mailer)
    {
        $this->mailer = $mailer;
    }

    public function notifyUser(int $userId, string $message): bool
    {
        return $this->mailer->send("user@example.com", "Notification", $message);
    }
}"""

    current_content = """<?php

namespace App\\Notifications;

use App\\Mail\\Mailer;
use App\\Templating\\TemplateEngine;
use App\\Repositories\\UserRepository;

class NotificationService
{
    private Mailer $mailer;
    private TemplateEngine $templates;
    private UserRepository $users;

    public function __construct(Mailer $mailer, TemplateEngine $templates, UserRepository $users)
    {
        $this->mailer = $mailer;
        $this->templates = $templates;
        $this->users = $users;
    }

    public function notifyUser(int $userId, string $message): bool
    {
        return $this->mailer->send("user@example.com", "Notification", $message);
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
    # FIM with dependency context
    test_fim_use_interface_method,
    test_fim_call_helper_function,
    test_fim_extend_abstract_class,
    # NEP with dependency context
    test_nep_add_method_using_dependency,
    test_nep_update_method_after_dependency_change,
    test_nep_implement_interface_method_with_deps,
    test_nep_compose_multiple_services,
]

if __name__ == "__main__":
    sys.exit(run_tests(ALL_TESTS))
