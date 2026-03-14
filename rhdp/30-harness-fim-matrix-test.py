#!/usr/bin/env python3
"""
============================================================================
Harness: FIM matrix test across languages, cursor positions, file sizes,
         and code complexity levels.
============================================================================

Tests the /infill endpoint against a running llama.cpp server with a
FIM-capable model across a full matrix of:

  Languages:    PHP, JavaScript, TypeScript, Kotlin
  Cursor pos:   start (no prefix), near-start, middle, near-end, end (no suffix)
  File sizes:   25, 100, 500 lines
  Complexity:   low, medium, high, very-high

Total combinations: 4 × 5 × 3 × 4 = 240 test cases.

Prerequisites:
  - llama.cpp server running on localhost:8012 (or set LLAMA_URL)
  - Server loaded with a FIM-capable model (e.g., sweepai/sweep-next-edit-1.5B)
  - Python 3.8+ (no external dependencies)

Usage:
  python3 rhdp/30-harness-fim-matrix-test.py
  python3 rhdp/30-harness-fim-matrix-test.py --lang php --size 25
  python3 rhdp/30-harness-fim-matrix-test.py --complexity high --position middle

Output:
  Results written to rhdp/31-output-fim-matrix-test.txt
  Summary table printed to STDOUT.
============================================================================
"""

import argparse
import json
import os
import sys
import time
import urllib.request
import urllib.error
from dataclasses import dataclass, field
from typing import Optional


# ============================================================================
# Configuration
# ============================================================================

LLAMA_URL = os.environ.get("LLAMA_URL", "http://localhost:8012")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_FILE = os.path.join(SCRIPT_DIR, "31-output-fim-matrix-test.txt")

LANGUAGES = ["php", "js", "typescript", "kotlin"]
POSITIONS = ["start", "near_start", "middle", "near_end", "end"]
SIZES = [25, 100, 500]
COMPLEXITIES = ["low", "medium", "high", "very_high"]


# ============================================================================
# Code generators — one per language × complexity
# ============================================================================

def _pad_to(lines: list[str], target: int, lang: str) -> list[str]:
    """Pad a code block to the target line count with contextually appropriate code."""
    comment = {"php": "//", "js": "//", "typescript": "//", "kotlin": "//"}[lang]
    while len(lines) < target:
        idx = len(lines)
        # Insert filler functions/comments to reach target size
        lines.append(f"{comment} utility helper #{idx}")
        lines.append("")
    return lines[:target]


# ---- PHP ----

def php_low(size: int) -> list[str]:
    lines = [
        "<?php",
        "",
        "function add(int $a, int $b): int {",
        "    return $a + $b;",
        "}",
        "",
        "function subtract(int $a, int $b): int {",
        "    return $a - $b;",
        "}",
        "",
        "function multiply(int $a, int $b): int {",
        "    return $a * $b;",
        "}",
        "",
        "function divide(int $a, int $b): float {",
        "    return $a / $b;",
        "}",
        "",
        '$result = add(10, 5);',
        'echo "Result: $result\\n";',
        "",
        '$diff = subtract(10, 5);',
        'echo "Difference: $diff\\n";',
        "",
        "// end of file",
    ]
    return _pad_to(lines, size, "php")


def php_medium(size: int) -> list[str]:
    lines = [
        "<?php",
        "",
        "class Calculator {",
        "    private array $history = [];",
        "",
        "    public function add(float $a, float $b): float {",
        "        $result = $a + $b;",
        '        $this->history[] = ["op" => "add", "result" => $result];',
        "        return $result;",
        "    }",
        "",
        "    public function subtract(float $a, float $b): float {",
        "        $result = $a - $b;",
        '        $this->history[] = ["op" => "sub", "result" => $result];',
        "        return $result;",
        "    }",
        "",
        "    public function getHistory(): array {",
        "        return $this->history;",
        "    }",
        "",
        "    public function clearHistory(): void {",
        "        $this->history = [];",
        "    }",
        "}",
        "",
        "$calc = new Calculator();",
        '$sum = $calc->add(10.5, 3.2);',
        'echo "Sum: $sum\\n";',
        "",
        "foreach ($calc->getHistory() as $entry) {",
        '    echo $entry["op"] . ": " . $entry["result"] . "\\n";',
        "}",
    ]
    return _pad_to(lines, size, "php")


def php_high(size: int) -> list[str]:
    lines = [
        "<?php",
        "",
        "declare(strict_types=1);",
        "",
        "namespace App\\Services;",
        "",
        "use App\\Contracts\\CacheInterface;",
        "use App\\Exceptions\\ServiceException;",
        "use Psr\\Log\\LoggerInterface;",
        "",
        "class UserService {",
        "    private CacheInterface $cache;",
        "    private LoggerInterface $logger;",
        "    private array $config;",
        "",
        "    public function __construct(",
        "        CacheInterface $cache,",
        "        LoggerInterface $logger,",
        "        array $config = []",
        "    ) {",
        "        $this->cache = $cache;",
        "        $this->logger = $logger;",
        '        $this->config = array_merge(self::DEFAULT_CONFIG, $config);',
        "    }",
        "",
        "    private const DEFAULT_CONFIG = [",
        "        'cache_ttl' => 3600,",
        "        'max_retries' => 3,",
        "        'batch_size' => 100,",
        "    ];",
        "",
        "    public function findById(int $id): ?array {",
        "        $cacheKey = \"user:{$id}\";",
        "        ",
        "        if ($cached = $this->cache->get($cacheKey)) {",
        '            $this->logger->debug("Cache hit for user", ["id" => $id]);',
        "            return $cached;",
        "        }",
        "",
        "        try {",
        "            $user = $this->fetchFromDatabase($id);",
        "            if ($user !== null) {",
        "                $this->cache->set($cacheKey, $user, $this->config['cache_ttl']);",
        "            }",
        "            return $user;",
        "        } catch (\\Exception $e) {",
        '            $this->logger->error("Failed to fetch user", [',
        '                "id" => $id,',
        '                "error" => $e->getMessage(),',
        "            ]);",
        "            throw new ServiceException(",
        "                \"Unable to retrieve user {$id}\",",
        "                previous: $e",
        "            );",
        "        }",
        "    }",
        "",
        "    public function findByIds(array $ids): array {",
        "        $results = [];",
        "        $missing = [];",
        "",
        "        foreach ($ids as $id) {",
        "            $cacheKey = \"user:{$id}\";",
        "            if ($cached = $this->cache->get($cacheKey)) {",
        "                $results[$id] = $cached;",
        "            } else {",
        "                $missing[] = $id;",
        "            }",
        "        }",
        "",
        "        if (!empty($missing)) {",
        "            $batches = array_chunk($missing, $this->config['batch_size']);",
        "            foreach ($batches as $batch) {",
        "                $fetched = $this->fetchBatchFromDatabase($batch);",
        "                foreach ($fetched as $id => $user) {",
        "                    $results[$id] = $user;",
        "                    $this->cache->set(\"user:{$id}\", $user, $this->config['cache_ttl']);",
        "                }",
        "            }",
        "        }",
        "",
        "        return $results;",
        "    }",
        "",
        "    private function fetchFromDatabase(int $id): ?array {",
        '        // Database query simulation',
        "        return null;",
        "    }",
        "",
        "    private function fetchBatchFromDatabase(array $ids): array {",
        '        // Batch database query simulation',
        "        return [];",
        "    }",
        "}",
    ]
    return _pad_to(lines, size, "php")


def php_very_high(size: int) -> list[str]:
    lines = [
        "<?php",
        "",
        "declare(strict_types=1);",
        "",
        "namespace App\\Pipeline;",
        "",
        "use App\\Contracts\\MiddlewareInterface;",
        "use App\\Contracts\\HandlerInterface;",
        "use App\\Events\\EventDispatcher;",
        "use Psr\\Log\\LoggerInterface;",
        "",
        "/**",
        " * @template TInput",
        " * @template TOutput",
        " */",
        "class Pipeline {",
        "    /** @var array<MiddlewareInterface<TInput, TOutput>> */",
        "    private array $middleware = [];",
        "    private EventDispatcher $events;",
        "    private LoggerInterface $logger;",
        "    private array $errorHandlers = [];",
        "",
        "    public function __construct(",
        "        EventDispatcher $events,",
        "        LoggerInterface $logger",
        "    ) {",
        "        $this->events = $events;",
        "        $this->logger = $logger;",
        "    }",
        "",
        "    /**",
        "     * @param MiddlewareInterface<TInput, TOutput> $middleware",
        "     * @return static",
        "     */",
        "    public function pipe(MiddlewareInterface $middleware): static {",
        "        $this->middleware[] = $middleware;",
        "        return $this;",
        "    }",
        "",
        "    /**",
        "     * @param callable(\\Throwable, TInput): TOutput $handler",
        "     * @return static",
        "     */",
        "    public function onError(callable $handler): static {",
        "        $this->errorHandlers[] = $handler;",
        "        return $this;",
        "    }",
        "",
        "    /**",
        "     * @param TInput $input",
        "     * @return TOutput",
        "     * @throws PipelineException",
        "     */",
        "    public function process(mixed $input): mixed {",
        "        $this->events->dispatch('pipeline.started', ['input' => $input]);",
        "        $startTime = hrtime(true);",
        "",
        "        $handler = $this->buildChain();",
        "",
        "        try {",
        "            $result = $handler($input);",
        "            $elapsed = (hrtime(true) - $startTime) / 1e6;",
        "            $this->events->dispatch('pipeline.completed', [",
        "                'input' => $input,",
        "                'output' => $result,",
        "                'elapsed_ms' => $elapsed,",
        "            ]);",
        "            $this->logger->info('Pipeline completed', [",
        "                'stages' => count($this->middleware),",
        "                'elapsed_ms' => round($elapsed, 2),",
        "            ]);",
        "            return $result;",
        "        } catch (\\Throwable $e) {",
        "            foreach ($this->errorHandlers as $errorHandler) {",
        "                try {",
        "                    return $errorHandler($e, $input);",
        "                } catch (\\Throwable $inner) {",
        "                    $this->logger->warning('Error handler failed', [",
        "                        'error' => $inner->getMessage(),",
        "                    ]);",
        "                    continue;",
        "                }",
        "            }",
        "            throw new PipelineException(",
        "                'Pipeline failed after exhausting all error handlers',",
        "                previous: $e",
        "            );",
        "        }",
        "    }",
        "",
        "    private function buildChain(): callable {",
        "        $chain = fn(mixed $input): mixed => $input;",
        "",
        "        foreach (array_reverse($this->middleware) as $mw) {",
        "            $next = $chain;",
        "            $chain = function (mixed $input) use ($mw, $next): mixed {",
        "                return $mw->handle($input, $next);",
        "            };",
        "        }",
        "",
        "        return $chain;",
        "    }",
        "}",
    ]
    return _pad_to(lines, size, "php")


# ---- JavaScript ----

def js_low(size: int) -> list[str]:
    lines = [
        "const greet = (name) => `Hello, ${name}!`;",
        "",
        "function sum(numbers) {",
        "  let total = 0;",
        "  for (const n of numbers) {",
        "    total += n;",
        "  }",
        "  return total;",
        "}",
        "",
        "function average(numbers) {",
        "  if (numbers.length === 0) return 0;",
        "  return sum(numbers) / numbers.length;",
        "}",
        "",
        "const numbers = [1, 2, 3, 4, 5];",
        "console.log(greet('World'));",
        "console.log('Sum:', sum(numbers));",
        "console.log('Average:', average(numbers));",
        "",
        "const doubled = numbers.map(n => n * 2);",
        "console.log('Doubled:', doubled);",
        "",
        "const evens = numbers.filter(n => n % 2 === 0);",
        "console.log('Evens:', evens);",
    ]
    return _pad_to(lines, size, "js")


def js_medium(size: int) -> list[str]:
    lines = [
        "class EventEmitter {",
        "  #listeners = new Map();",
        "",
        "  on(event, callback) {",
        "    if (!this.#listeners.has(event)) {",
        "      this.#listeners.set(event, []);",
        "    }",
        "    this.#listeners.get(event).push(callback);",
        "    return () => this.off(event, callback);",
        "  }",
        "",
        "  off(event, callback) {",
        "    const cbs = this.#listeners.get(event);",
        "    if (!cbs) return;",
        "    const idx = cbs.indexOf(callback);",
        "    if (idx !== -1) cbs.splice(idx, 1);",
        "  }",
        "",
        "  emit(event, ...args) {",
        "    const cbs = this.#listeners.get(event) || [];",
        "    for (const cb of cbs) {",
        "      cb(...args);",
        "    }",
        "  }",
        "",
        "  once(event, callback) {",
        "    const wrapper = (...args) => {",
        "      this.off(event, wrapper);",
        "      callback(...args);",
        "    };",
        "    return this.on(event, wrapper);",
        "  }",
        "}",
        "",
        "const emitter = new EventEmitter();",
        "emitter.on('data', (msg) => console.log('Received:', msg));",
        "emitter.emit('data', 'hello');",
    ]
    return _pad_to(lines, size, "js")


def js_high(size: int) -> list[str]:
    lines = [
        "class TaskQueue {",
        "  #queue = [];",
        "  #running = 0;",
        "  #concurrency;",
        "  #results = new Map();",
        "  #onDrain = null;",
        "",
        "  constructor({ concurrency = 4 } = {}) {",
        "    this.#concurrency = concurrency;",
        "  }",
        "",
        "  add(id, taskFn, { priority = 0, retries = 0, timeout = 0 } = {}) {",
        "    return new Promise((resolve, reject) => {",
        "      this.#queue.push({ id, taskFn, priority, retries, timeout, resolve, reject });",
        "      this.#queue.sort((a, b) => b.priority - a.priority);",
        "      this.#processNext();",
        "    });",
        "  }",
        "",
        "  async #processNext() {",
        "    if (this.#running >= this.#concurrency || this.#queue.length === 0) return;",
        "",
        "    this.#running++;",
        "    const task = this.#queue.shift();",
        "",
        "    try {",
        "      const result = await this.#runWithTimeout(task);",
        "      this.#results.set(task.id, { status: 'fulfilled', value: result });",
        "      task.resolve(result);",
        "    } catch (error) {",
        "      if (task.retries > 0) {",
        "        task.retries--;",
        "        this.#queue.unshift(task);",
        "      } else {",
        "        this.#results.set(task.id, { status: 'rejected', reason: error });",
        "        task.reject(error);",
        "      }",
        "    } finally {",
        "      this.#running--;",
        "      this.#processNext();",
        "      if (this.#running === 0 && this.#queue.length === 0 && this.#onDrain) {",
        "        this.#onDrain();",
        "      }",
        "    }",
        "  }",
        "",
        "  async #runWithTimeout(task) {",
        "    if (task.timeout <= 0) return task.taskFn();",
        "",
        "    return Promise.race([",
        "      task.taskFn(),",
        "      new Promise((_, reject) =>",
        "        setTimeout(() => reject(new Error(`Task ${task.id} timed out`)), task.timeout)",
        "      ),",
        "    ]);",
        "  }",
        "",
        "  drain() {",
        "    return new Promise((resolve) => {",
        "      if (this.#running === 0 && this.#queue.length === 0) {",
        "        resolve(this.#results);",
        "      } else {",
        "        this.#onDrain = () => resolve(this.#results);",
        "      }",
        "    });",
        "  }",
        "",
        "  get pending() { return this.#queue.length; }",
        "  get active() { return this.#running; }",
        "}",
        "",
        "module.exports = { TaskQueue };",
    ]
    return _pad_to(lines, size, "js")


def js_very_high(size: int) -> list[str]:
    lines = [
        "const createReactiveStore = (initialState, { plugins = [], middleware = [] } = {}) => {",
        "  let state = structuredClone(initialState);",
        "  const subscribers = new Map();",
        "  const computedCache = new Map();",
        "  let batchDepth = 0;",
        "  let pendingNotifications = new Set();",
        "",
        "  const proxyCache = new WeakMap();",
        "",
        "  const createProxy = (target, path = []) => {",
        "    if (proxyCache.has(target)) return proxyCache.get(target);",
        "",
        "    const proxy = new Proxy(target, {",
        "      get(obj, prop) {",
        "        if (prop === '__raw') return obj;",
        "        if (typeof prop === 'symbol') return Reflect.get(obj, prop);",
        "",
        "        const value = Reflect.get(obj, prop);",
        "        const fullPath = [...path, prop].join('.');",
        "",
        "        if (currentComputed) {",
        "          if (!computedDeps.has(currentComputed)) {",
        "            computedDeps.set(currentComputed, new Set());",
        "          }",
        "          computedDeps.get(currentComputed).add(fullPath);",
        "        }",
        "",
        "        if (value !== null && typeof value === 'object') {",
        "          return createProxy(value, [...path, prop]);",
        "        }",
        "        return value;",
        "      },",
        "",
        "      set(obj, prop, value) {",
        "        const fullPath = [...path, prop].join('.');",
        "        const oldValue = Reflect.get(obj, prop);",
        "        if (Object.is(oldValue, value)) return true;",
        "",
        "        let finalValue = value;",
        "        for (const mw of middleware) {",
        "          finalValue = mw({ path: fullPath, oldValue, newValue: finalValue, state });",
        "        }",
        "",
        "        Reflect.set(obj, prop, finalValue);",
        "        invalidateComputed(fullPath);",
        "",
        "        if (batchDepth > 0) {",
        "          pendingNotifications.add(fullPath);",
        "        } else {",
        "          notify(fullPath);",
        "        }",
        "        return true;",
        "      },",
        "    });",
        "",
        "    proxyCache.set(target, proxy);",
        "    return proxy;",
        "  };",
        "",
        "  let currentComputed = null;",
        "  const computedDeps = new Map();",
        "",
        "  const invalidateComputed = (changedPath) => {",
        "    for (const [name, deps] of computedDeps) {",
        "      if (deps.has(changedPath)) {",
        "        computedCache.delete(name);",
        "      }",
        "    }",
        "  };",
        "",
        "  const notify = (path) => {",
        "    for (const [selector, cbs] of subscribers) {",
        "      if (path.startsWith(selector) || selector === '*') {",
        "        for (const cb of cbs) cb(state);",
        "      }",
        "    }",
        "  };",
        "",
        "  const store = {",
        "    get state() { return createProxy(state); },",
        "",
        "    subscribe(selector, callback) {",
        "      if (!subscribers.has(selector)) subscribers.set(selector, new Set());",
        "      subscribers.get(selector).add(callback);",
        "      return () => subscribers.get(selector)?.delete(callback);",
        "    },",
        "",
        "    batch(fn) {",
        "      batchDepth++;",
        "      try {",
        "        fn(store.state);",
        "      } finally {",
        "        batchDepth--;",
        "        if (batchDepth === 0) {",
        "          for (const path of pendingNotifications) notify(path);",
        "          pendingNotifications.clear();",
        "        }",
        "      }",
        "    },",
        "",
        "    computed(name, fn) {",
        "      return () => {",
        "        if (computedCache.has(name)) return computedCache.get(name);",
        "        const prev = currentComputed;",
        "        currentComputed = name;",
        "        computedDeps.delete(name);",
        "        const result = fn(store.state);",
        "        currentComputed = prev;",
        "        computedCache.set(name, result);",
        "        return result;",
        "      };",
        "    },",
        "",
        "    snapshot() { return structuredClone(state); },",
        "  };",
        "",
        "  for (const plugin of plugins) plugin(store);",
        "  return store;",
        "};",
        "",
        "module.exports = { createReactiveStore };",
    ]
    return _pad_to(lines, size, "js")


# ---- TypeScript ----

def typescript_low(size: int) -> list[str]:
    lines = [
        "interface Point {",
        "  x: number;",
        "  y: number;",
        "}",
        "",
        "function distance(a: Point, b: Point): number {",
        "  const dx = b.x - a.x;",
        "  const dy = b.y - a.y;",
        "  return Math.sqrt(dx * dx + dy * dy);",
        "}",
        "",
        "function midpoint(a: Point, b: Point): Point {",
        "  return {",
        "    x: (a.x + b.x) / 2,",
        "    y: (a.y + b.y) / 2,",
        "  };",
        "}",
        "",
        "const p1: Point = { x: 0, y: 0 };",
        "const p2: Point = { x: 3, y: 4 };",
        "",
        "console.log('Distance:', distance(p1, p2));",
        "console.log('Midpoint:', midpoint(p1, p2));",
        "",
        "export { Point, distance, midpoint };",
    ]
    return _pad_to(lines, size, "typescript")


def typescript_medium(size: int) -> list[str]:
    lines = [
        "type Status = 'pending' | 'active' | 'completed' | 'failed';",
        "",
        "interface Task {",
        "  id: string;",
        "  title: string;",
        "  status: Status;",
        "  assignee?: string;",
        "  dueDate?: Date;",
        "  tags: string[];",
        "}",
        "",
        "class TaskManager {",
        "  private tasks: Map<string, Task> = new Map();",
        "  private nextId = 1;",
        "",
        "  create(title: string, assignee?: string): Task {",
        "    const task: Task = {",
        "      id: `task-${this.nextId++}`,",
        "      title,",
        "      status: 'pending',",
        "      assignee,",
        "      tags: [],",
        "    };",
        "    this.tasks.set(task.id, task);",
        "    return task;",
        "  }",
        "",
        "  transition(id: string, newStatus: Status): void {",
        "    const task = this.tasks.get(id);",
        "    if (!task) throw new Error(`Task ${id} not found`);",
        "    task.status = newStatus;",
        "  }",
        "",
        "  findByStatus(status: Status): Task[] {",
        "    return [...this.tasks.values()].filter(t => t.status === status);",
        "  }",
        "",
        "  findByAssignee(assignee: string): Task[] {",
        "    return [...this.tasks.values()].filter(t => t.assignee === assignee);",
        "  }",
        "",
        "  addTag(id: string, tag: string): void {",
        "    const task = this.tasks.get(id);",
        "    if (task && !task.tags.includes(tag)) {",
        "      task.tags.push(tag);",
        "    }",
        "  }",
        "",
        "  get all(): Task[] {",
        "    return [...this.tasks.values()];",
        "  }",
        "}",
        "",
        "export { Task, TaskManager, Status };",
    ]
    return _pad_to(lines, size, "typescript")


def typescript_high(size: int) -> list[str]:
    lines = [
        "interface Result<T, E = Error> {",
        "  readonly ok: boolean;",
        "  map<U>(fn: (value: T) => U): Result<U, E>;",
        "  flatMap<U>(fn: (value: T) => Result<U, E>): Result<U, E>;",
        "  unwrapOr(defaultValue: T): T;",
        "  match<U>(handlers: { ok: (value: T) => U; err: (error: E) => U }): U;",
        "}",
        "",
        "class Ok<T, E = Error> implements Result<T, E> {",
        "  readonly ok = true as const;",
        "  constructor(private readonly value: T) {}",
        "",
        "  map<U>(fn: (value: T) => U): Result<U, E> {",
        "    return new Ok(fn(this.value));",
        "  }",
        "",
        "  flatMap<U>(fn: (value: T) => Result<U, E>): Result<U, E> {",
        "    return fn(this.value);",
        "  }",
        "",
        "  unwrapOr(_defaultValue: T): T {",
        "    return this.value;",
        "  }",
        "",
        "  match<U>(handlers: { ok: (value: T) => U; err: (error: E) => U }): U {",
        "    return handlers.ok(this.value);",
        "  }",
        "}",
        "",
        "class Err<T, E = Error> implements Result<T, E> {",
        "  readonly ok = false as const;",
        "  constructor(private readonly error: E) {}",
        "",
        "  map<U>(_fn: (value: T) => U): Result<U, E> {",
        "    return new Err(this.error);",
        "  }",
        "",
        "  flatMap<U>(_fn: (value: T) => Result<U, E>): Result<U, E> {",
        "    return new Err(this.error);",
        "  }",
        "",
        "  unwrapOr(defaultValue: T): T {",
        "    return defaultValue;",
        "  }",
        "",
        "  match<U>(handlers: { ok: (value: T) => U; err: (error: E) => U }): U {",
        "    return handlers.err(this.error);",
        "  }",
        "}",
        "",
        "function tryCatch<T>(fn: () => T): Result<T> {",
        "  try {",
        "    return new Ok(fn());",
        "  } catch (e) {",
        "    return new Err(e instanceof Error ? e : new Error(String(e)));",
        "  }",
        "}",
        "",
        "async function tryCatchAsync<T>(fn: () => Promise<T>): Promise<Result<T>> {",
        "  try {",
        "    return new Ok(await fn());",
        "  } catch (e) {",
        "    return new Err(e instanceof Error ? e : new Error(String(e)));",
        "  }",
        "}",
        "",
        "function combine<T>(...results: Result<T>[]): Result<T[]> {",
        "  const values: T[] = [];",
        "  for (const r of results) {",
        "    if (!r.ok) return r as unknown as Result<T[]>;",
        "    values.push(r.unwrapOr(undefined as unknown as T));",
        "  }",
        "  return new Ok(values);",
        "}",
        "",
        "export { Result, Ok, Err, tryCatch, tryCatchAsync, combine };",
    ]
    return _pad_to(lines, size, "typescript")


def typescript_very_high(size: int) -> list[str]:
    lines = [
        "type EventMap = Record<string, unknown[]>;",
        "",
        "type Listener<Args extends unknown[]> = (...args: Args) => void;",
        "",
        "type Middleware<Args extends unknown[]> = (",
        "  args: Args,",
        "  next: () => void",
        ") => void;",
        "",
        "interface TypedEmitter<E extends EventMap> {",
        "  on<K extends keyof E>(event: K, listener: Listener<E[K]>): () => void;",
        "  emit<K extends keyof E>(event: K, ...args: E[K]): void;",
        "  use<K extends keyof E>(event: K, middleware: Middleware<E[K]>): void;",
        "}",
        "",
        "class EventBus<E extends EventMap> implements TypedEmitter<E> {",
        "  private listeners = new Map<keyof E, Set<Listener<any>>>();",
        "  private middlewares = new Map<keyof E, Middleware<any>[]>();",
        "  private history: Array<{ event: keyof E; args: unknown[]; timestamp: number }> = [];",
        "  private maxHistory: number;",
        "",
        "  constructor(options: { maxHistory?: number } = {}) {",
        "    this.maxHistory = options.maxHistory ?? 100;",
        "  }",
        "",
        "  on<K extends keyof E>(event: K, listener: Listener<E[K]>): () => void {",
        "    if (!this.listeners.has(event)) {",
        "      this.listeners.set(event, new Set());",
        "    }",
        "    this.listeners.get(event)!.add(listener as Listener<any>);",
        "    return () => {",
        "      this.listeners.get(event)?.delete(listener as Listener<any>);",
        "    };",
        "  }",
        "",
        "  use<K extends keyof E>(event: K, middleware: Middleware<E[K]>): void {",
        "    if (!this.middlewares.has(event)) {",
        "      this.middlewares.set(event, []);",
        "    }",
        "    this.middlewares.get(event)!.push(middleware as Middleware<any>);",
        "  }",
        "",
        "  emit<K extends keyof E>(event: K, ...args: E[K]): void {",
        "    this.history.push({ event, args, timestamp: Date.now() });",
        "    if (this.history.length > this.maxHistory) {",
        "      this.history = this.history.slice(-this.maxHistory);",
        "    }",
        "",
        "    const mws = this.middlewares.get(event) ?? [];",
        "    let idx = 0;",
        "",
        "    const dispatch = (): void => {",
        "      if (idx < mws.length) {",
        "        const mw = mws[idx++];",
        "        mw(args as any, dispatch);",
        "      } else {",
        "        const listeners = this.listeners.get(event);",
        "        if (listeners) {",
        "          for (const listener of listeners) {",
        "            listener(...(args as any));",
        "          }",
        "        }",
        "      }",
        "    };",
        "",
        "    dispatch();",
        "  }",
        "",
        "  replay<K extends keyof E>(event: K, since?: number): void {",
        "    const entries = this.history.filter(",
        "      (e) => e.event === event && (!since || e.timestamp >= since)",
        "    );",
        "    for (const entry of entries) {",
        "      this.emit(event, ...(entry.args as E[K]));",
        "    }",
        "  }",
        "",
        "  inspect(): ReadonlyArray<{ event: keyof E; timestamp: number }> {",
        "    return this.history.map(({ event, timestamp }) => ({ event, timestamp }));",
        "  }",
        "}",
        "",
        "// Usage types",
        "interface AppEvents extends EventMap {",
        "  'user:login': [userId: string, ip: string];",
        "  'user:logout': [userId: string];",
        "  'data:sync': [table: string, rowCount: number];",
        "  'error': [error: Error, context: Record<string, unknown>];",
        "}",
        "",
        "export { EventBus, TypedEmitter, EventMap, AppEvents };",
    ]
    return _pad_to(lines, size, "typescript")


# ---- Kotlin ----

def kotlin_low(size: int) -> list[str]:
    lines = [
        "data class Person(val name: String, val age: Int)",
        "",
        "fun greet(person: Person): String {",
        '    return "Hello, ${person.name}! You are ${person.age} years old."',
        "}",
        "",
        "fun isAdult(person: Person): Boolean {",
        "    return person.age >= 18",
        "}",
        "",
        "fun oldestPerson(people: List<Person>): Person? {",
        "    return people.maxByOrNull { it.age }",
        "}",
        "",
        "fun main() {",
        '    val people = listOf(',
        '        Person("Alice", 30),',
        '        Person("Bob", 25),',
        '        Person("Charlie", 35)',
        "    )",
        "",
        "    for (person in people) {",
        "        println(greet(person))",
        "    }",
        "    println(oldestPerson(people))",
        "}",
    ]
    return _pad_to(lines, size, "kotlin")


def kotlin_medium(size: int) -> list[str]:
    lines = [
        "sealed class Shape {",
        "    abstract fun area(): Double",
        "    abstract fun perimeter(): Double",
        "",
        "    data class Circle(val radius: Double) : Shape() {",
        "        override fun area() = Math.PI * radius * radius",
        "        override fun perimeter() = 2 * Math.PI * radius",
        "    }",
        "",
        "    data class Rectangle(val width: Double, val height: Double) : Shape() {",
        "        override fun area() = width * height",
        "        override fun perimeter() = 2 * (width + height)",
        "    }",
        "",
        "    data class Triangle(val a: Double, val b: Double, val c: Double) : Shape() {",
        "        override fun area(): Double {",
        "            val s = perimeter() / 2",
        "            return Math.sqrt(s * (s - a) * (s - b) * (s - c))",
        "        }",
        "        override fun perimeter() = a + b + c",
        "    }",
        "}",
        "",
        "fun describeShape(shape: Shape): String = when (shape) {",
        '    is Shape.Circle -> "Circle with radius ${shape.radius}"',
        '    is Shape.Rectangle -> "Rectangle ${shape.width}x${shape.height}"',
        '    is Shape.Triangle -> "Triangle with sides ${shape.a}, ${shape.b}, ${shape.c}"',
        "}",
        "",
        "fun main() {",
        "    val shapes = listOf(",
        "        Shape.Circle(5.0),",
        "        Shape.Rectangle(4.0, 6.0),",
        "        Shape.Triangle(3.0, 4.0, 5.0)",
        "    )",
        "",
        '    shapes.forEach { println("${describeShape(it)}: area=${it.area()}") }',
        "}",
    ]
    return _pad_to(lines, size, "kotlin")


def kotlin_high(size: int) -> list[str]:
    lines = [
        "import kotlinx.coroutines.*",
        "import kotlinx.coroutines.channels.Channel",
        "import kotlinx.coroutines.flow.*",
        "",
        "data class WorkItem<T>(val id: String, val payload: T, val priority: Int = 0)",
        "data class WorkResult<T>(val id: String, val result: T, val durationMs: Long)",
        "",
        "class WorkerPool<I, O>(",
        "    private val concurrency: Int = 4,",
        "    private val processor: suspend (I) -> O",
        ") {",
        "    private val inputChannel = Channel<WorkItem<I>>(Channel.BUFFERED)",
        "    private val _results = MutableSharedFlow<WorkResult<O>>()",
        "    val results: SharedFlow<WorkResult<O>> = _results.asSharedFlow()",
        "",
        "    private var scope: CoroutineScope? = null",
        "",
        "    fun start(parentScope: CoroutineScope) {",
        "        scope = parentScope",
        "        repeat(concurrency) { workerId ->",
        "            parentScope.launch {",
        "                for (item in inputChannel) {",
        "                    val start = System.currentTimeMillis()",
        "                    try {",
        "                        val output = processor(item.payload)",
        "                        val duration = System.currentTimeMillis() - start",
        "                        _results.emit(WorkResult(item.id, output, duration))",
        "                    } catch (e: Exception) {",
        '                        println("Worker $workerId failed on ${item.id}: ${e.message}")',
        "                    }",
        "                }",
        "            }",
        "        }",
        "    }",
        "",
        "    suspend fun submit(item: WorkItem<I>) {",
        "        inputChannel.send(item)",
        "    }",
        "",
        "    suspend fun submitAll(items: List<WorkItem<I>>) {",
        "        items.sortedByDescending { it.priority }.forEach { submit(it) }",
        "    }",
        "",
        "    fun shutdown() {",
        "        inputChannel.close()",
        "    }",
        "}",
        "",
        "suspend fun main() = coroutineScope {",
        "    val pool = WorkerPool<String, Int>(concurrency = 3) { input ->",
        "        delay(100)",
        "        input.length",
        "    }",
        "",
        "    pool.start(this)",
        "",
        "    launch {",
        "        pool.results.take(3).collect { result ->",
        '            println("Result ${result.id}: ${result.result} (${result.durationMs}ms)")',
        "        }",
        "    }",
        "",
        "    pool.submitAll(listOf(",
        '        WorkItem("a", "hello", priority = 1),',
        '        WorkItem("b", "world", priority = 2),',
        '        WorkItem("c", "kotlin", priority = 0)',
        "    ))",
        "",
        "    delay(500)",
        "    pool.shutdown()",
        "}",
    ]
    return _pad_to(lines, size, "kotlin")


def kotlin_very_high(size: int) -> list[str]:
    lines = [
        "import kotlin.reflect.KProperty",
        "",
        "interface Validator<T> {",
        "    fun validate(value: T): ValidationResult",
        "}",
        "",
        "sealed class ValidationResult {",
        "    data object Valid : ValidationResult()",
        "    data class Invalid(val errors: List<String>) : ValidationResult()",
        "",
        "    operator fun plus(other: ValidationResult): ValidationResult = when {",
        "        this is Valid && other is Valid -> Valid",
        "        this is Invalid && other is Invalid -> Invalid(this.errors + other.errors)",
        "        this is Invalid -> this",
        "        else -> other",
        "    }",
        "}",
        "",
        "class ValidatedProperty<T>(",
        "    private var value: T,",
        "    private val validators: List<Validator<T>>",
        ") {",
        "    private var dirty = false",
        "    private var lastResult: ValidationResult = ValidationResult.Valid",
        "",
        "    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value",
        "",
        "    operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {",
        "        value = newValue",
        "        dirty = true",
        "        lastResult = validators.fold(ValidationResult.Valid as ValidationResult) { acc, v ->",
        "            acc + v.validate(newValue)",
        "        }",
        "    }",
        "",
        "    fun isValid(): Boolean = lastResult is ValidationResult.Valid",
        "    fun isDirty(): Boolean = dirty",
        "    fun errors(): List<String> = when (val r = lastResult) {",
        "        is ValidationResult.Invalid -> r.errors",
        "        else -> emptyList()",
        "    }",
        "}",
        "",
        "inline fun <reified T> validated(",
        "    initial: T,",
        "    vararg validators: Validator<T>",
        "): ValidatedProperty<T> = ValidatedProperty(initial, validators.toList())",
        "",
        "// Built-in validators",
        "class NotBlank : Validator<String> {",
        "    override fun validate(value: String) =",
        '        if (value.isBlank()) ValidationResult.Invalid(listOf("Must not be blank"))',
        "        else ValidationResult.Valid",
        "}",
        "",
        "class MinLength(private val min: Int) : Validator<String> {",
        "    override fun validate(value: String) =",
        '        if (value.length < min) ValidationResult.Invalid(listOf("Min length: $min"))',
        "        else ValidationResult.Valid",
        "}",
        "",
        "class InRange<T : Comparable<T>>(private val range: ClosedRange<T>) : Validator<T> {",
        "    override fun validate(value: T) =",
        '        if (value !in range) ValidationResult.Invalid(listOf("Must be in $range"))',
        "        else ValidationResult.Valid",
        "}",
        "",
        "class Matches(private val pattern: Regex) : Validator<String> {",
        "    override fun validate(value: String) =",
        "        if (!pattern.matches(value))",
        '            ValidationResult.Invalid(listOf("Must match ${pattern.pattern}"))',
        "        else ValidationResult.Valid",
        "}",
        "",
        "// Composite form",
        "abstract class ValidatedForm {",
        "    private val fields = mutableListOf<ValidatedProperty<*>>()",
        "",
        "    protected fun <T> field(initial: T, vararg validators: Validator<T>): ValidatedProperty<T> {",
        "        return validated(initial, *validators).also { fields.add(it) }",
        "    }",
        "",
        "    fun validate(): ValidationResult {",
        "        return fields.fold(ValidationResult.Valid as ValidationResult) { acc, f ->",
        "            val fieldResult = if (f.isValid()) ValidationResult.Valid",
        "                else ValidationResult.Invalid(f.errors())",
        "            acc + fieldResult",
        "        }",
        "    }",
        "",
        "    fun isValid(): Boolean = fields.all { it.isValid() }",
        "    fun isDirty(): Boolean = fields.any { it.isDirty() }",
        "}",
        "",
        "// Usage example",
        "class UserForm : ValidatedForm() {",
        "    var name by field(\"\", NotBlank(), MinLength(2))",
        '    var email by field("", NotBlank(), Matches(Regex(".+@.+\\\\..+")))',
        "    var age by field(0, InRange(0..150))",
        "}",
    ]
    return _pad_to(lines, size, "kotlin")


# ============================================================================
# Generator registry
# ============================================================================

GENERATORS = {
    ("php", "low"): php_low,
    ("php", "medium"): php_medium,
    ("php", "high"): php_high,
    ("php", "very_high"): php_very_high,
    ("js", "low"): js_low,
    ("js", "medium"): js_medium,
    ("js", "high"): js_high,
    ("js", "very_high"): js_very_high,
    ("typescript", "low"): typescript_low,
    ("typescript", "medium"): typescript_medium,
    ("typescript", "high"): typescript_high,
    ("typescript", "very_high"): typescript_very_high,
    ("kotlin", "low"): kotlin_low,
    ("kotlin", "medium"): kotlin_medium,
    ("kotlin", "high"): kotlin_high,
    ("kotlin", "very_high"): kotlin_very_high,
}

EXTENSIONS = {
    "php": ".php",
    "js": ".js",
    "typescript": ".ts",
    "kotlin": ".kt",
}


# ============================================================================
# Test runner
# ============================================================================

@dataclass
class TestCase:
    lang: str
    complexity: str
    size: int
    position: str
    prefix: str
    suffix: str
    filename: str
    total_lines: int
    split_line: int


@dataclass
class TestResult:
    test: TestCase
    content: str = ""
    tokens_cached: int = 0
    tokens_evaluated: int = 0
    stop_type: str = ""
    truncated: bool = False
    prompt_ms: float = 0.0
    predicted_ms: float = 0.0
    predicted_n: int = 0
    error: str = ""
    http_status: int = 0
    elapsed_ms: float = 0.0


def split_at_position(lines: list[str], position: str) -> tuple[int, str, str]:
    """Split lines at the given cursor position. Returns (split_line, prefix, suffix)."""
    total = len(lines)
    if position == "start":
        return 0, "", "\n".join(lines)
    elif position == "near_start":
        split = max(1, total // 8)  # ~12% into file
        return split, "\n".join(lines[:split]) + "\n", "\n".join(lines[split:])
    elif position == "middle":
        split = total // 2
        return split, "\n".join(lines[:split]) + "\n", "\n".join(lines[split:])
    elif position == "near_end":
        split = total - max(1, total // 8)  # ~88% into file
        return split, "\n".join(lines[:split]) + "\n", "\n".join(lines[split:])
    elif position == "end":
        return total, "\n".join(lines), ""
    else:
        raise ValueError(f"Unknown position: {position}")


def build_test_cases(
    langs: list[str],
    positions: list[str],
    sizes: list[int],
    complexities: list[str],
) -> list[TestCase]:
    cases = []
    for lang in langs:
        for complexity in complexities:
            gen = GENERATORS.get((lang, complexity))
            if gen is None:
                continue
            for size in sizes:
                code_lines = gen(size)
                actual_lines = len(code_lines)
                ext = EXTENSIONS[lang]
                for position in positions:
                    split_line, prefix, suffix = split_at_position(code_lines, position)
                    filename = f"test_{complexity}{ext}"
                    cases.append(TestCase(
                        lang=lang,
                        complexity=complexity,
                        size=size,
                        position=position,
                        prefix=prefix,
                        suffix=suffix,
                        filename=filename,
                        total_lines=actual_lines,
                        split_line=split_line,
                    ))
    return cases


def _http_post(url: str, payload: dict, timeout: int = 30) -> tuple[int, dict | str]:
    """POST JSON using stdlib urllib. Returns (status_code, parsed_json_or_error_str)."""
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8")
            return resp.status, json.loads(body)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")[:200]
        return e.code, f"HTTP {e.code}: {body}"
    except urllib.error.URLError as e:
        raise ConnectionError(str(e.reason)) from e


def _http_get(url: str, timeout: int = 5) -> int:
    """GET and return status code."""
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as e:
        return e.code
    except urllib.error.URLError as e:
        raise ConnectionError(str(e.reason)) from e


def run_test(case: TestCase, base_url: str) -> TestResult:
    """Run a single /infill test case."""
    result = TestResult(test=case)

    payload = {
        "input_prefix": case.prefix,
        "input_suffix": case.suffix,
        "n_predict": 128,
        "temperature": 0.0,
        "stream": False,
        "cache_prompt": True,
    }

    start = time.monotonic()
    try:
        status, data = _http_post(f"{base_url}/infill", payload, timeout=30)
        result.elapsed_ms = (time.monotonic() - start) * 1000
        result.http_status = status

        if status != 200 or isinstance(data, str):
            result.error = data if isinstance(data, str) else f"HTTP {status}"
            return result

        result.content = data.get("content", "")
        result.tokens_cached = data.get("tokens_cached", 0)
        result.tokens_evaluated = data.get("tokens_evaluated", 0)
        result.stop_type = data.get("stop_type", "")
        result.truncated = data.get("truncated", False)

        timings = data.get("timings", {})
        if isinstance(timings, dict):
            result.prompt_ms = timings.get("prompt_ms", 0.0)
            result.predicted_ms = timings.get("predicted_ms", 0.0)
            result.predicted_n = timings.get("predicted_n", 0)

    except ConnectionError:
        result.error = "Connection refused"
    except TimeoutError:
        result.error = "Request timed out"
    except Exception as e:
        result.error = str(e)

    return result


def content_preview(content: str, max_len: int = 60) -> str:
    """Truncate content for display."""
    clean = content.replace("\n", "\\n")
    if len(clean) > max_len:
        return clean[:max_len - 3] + "..."
    return clean


# ============================================================================
# Output formatting
# ============================================================================

COL_RESET = "\033[0m"
COL_BOLD = "\033[1m"
COL_GREEN = "\033[0;32m"
COL_RED = "\033[0;31m"
COL_CYAN = "\033[0;36m"
COL_YELLOW = "\033[0;33m"


def print_summary_table(results: list[TestResult], file=None):
    """Print a summary table of all results."""
    header = (
        f"{'#':>4}  {'Lang':<11} {'Cmplx':<10} {'Size':>5} {'Position':<11} "
        f"{'Split':>5} {'Eval':>5} {'Cached':>6} {'PredN':>5} "
        f"{'ElapsedMs':>10} {'StopType':<8} {'Content Preview'}"
    )
    sep = "-" * 140

    def write(s=""):
        print(s)
        if file:
            # Strip ANSI for file output
            import re
            clean = re.sub(r'\033\[[0-9;]*m', '', s)
            file.write(clean + "\n")

    write(sep)
    write(header)
    write(sep)

    for i, r in enumerate(results, 1):
        tc = r.test
        if r.error:
            status = f"{COL_RED}ERR: {r.error[:50]}{COL_RESET}"
            line = (
                f"{i:>4}  {tc.lang:<11} {tc.complexity:<10} {tc.size:>5} "
                f"{tc.position:<11} {tc.split_line:>5} {'':>5} {'':>6} {'':>5} "
                f"{'':>10} {'':8} {status}"
            )
        else:
            preview = content_preview(r.content, 50)
            line = (
                f"{i:>4}  {tc.lang:<11} {tc.complexity:<10} {tc.size:>5} "
                f"{tc.position:<11} {tc.split_line:>5} {r.tokens_evaluated:>5} "
                f"{r.tokens_cached:>6} {r.predicted_n:>5} "
                f"{r.elapsed_ms:>10.1f} {r.stop_type:<8} {preview}"
            )
        write(line)

    write(sep)


def print_detail(result: TestResult, file=None):
    """Print detailed result for a single test."""
    tc = result.test
    def write(s=""):
        print(s)
        if file:
            import re
            clean = re.sub(r'\033\[[0-9;]*m', '', s)
            file.write(clean + "\n")

    write(f"\n{'='*80}")
    write(f"Test: {tc.lang} | {tc.complexity} | {tc.size} lines | cursor={tc.position} (line {tc.split_line})")
    write(f"{'='*80}")
    write(f"Prefix ({len(tc.prefix)} chars, {tc.prefix.count(chr(10))} lines):")
    if tc.prefix:
        preview_lines = tc.prefix.split("\n")
        for pl in preview_lines[:5]:
            write(f"  | {pl}")
        if len(preview_lines) > 5:
            write(f"  | ... ({len(preview_lines) - 5} more lines)")
    else:
        write("  (empty)")

    write(f"Suffix ({len(tc.suffix)} chars, {tc.suffix.count(chr(10))} lines):")
    if tc.suffix:
        preview_lines = tc.suffix.split("\n")
        for pl in preview_lines[:5]:
            write(f"  | {pl}")
        if len(preview_lines) > 5:
            write(f"  | ... ({len(preview_lines) - 5} more lines)")
    else:
        write("  (empty)")

    if result.error:
        write(f"ERROR: {result.error}")
    else:
        write(f"Generated content ({len(result.content)} chars):")
        for cl in result.content.split("\n"):
            write(f"  > {cl}")
        write(f"Tokens evaluated: {result.tokens_evaluated}, cached: {result.tokens_cached}")
        write(f"Stop type: {result.stop_type}, truncated: {result.truncated}")
        write(f"Elapsed: {result.elapsed_ms:.1f}ms")


# ============================================================================
# Main
# ============================================================================

def main():
    parser = argparse.ArgumentParser(description="FIM matrix test harness")
    parser.add_argument("--lang", choices=LANGUAGES, nargs="+", default=LANGUAGES)
    parser.add_argument("--position", choices=POSITIONS, nargs="+", default=POSITIONS)
    parser.add_argument("--size", type=int, choices=SIZES, nargs="+", default=SIZES)
    parser.add_argument("--complexity", choices=COMPLEXITIES, nargs="+", default=COMPLEXITIES)
    parser.add_argument("--url", default=LLAMA_URL, help="llama.cpp server URL")
    parser.add_argument("--verbose", "-v", action="store_true", help="Print detailed results")
    parser.add_argument("--output", "-o", default=OUTPUT_FILE, help="Output file path")
    args = parser.parse_args()

    base_url = args.url

    # Health check
    print(f"{COL_BOLD}Checking server health at {base_url}...{COL_RESET}")
    try:
        status = _http_get(f"{base_url}/health", timeout=5)
        if status != 200:
            print(f"{COL_RED}Server not healthy (HTTP {status}){COL_RESET}")
            sys.exit(1)
    except ConnectionError:
        print(f"{COL_RED}Cannot connect to {base_url}{COL_RESET}")
        print("Start a llama.cpp server with a FIM-capable model, e.g.:")
        print(f"  llama-server --host 127.0.0.1 --port 8012 -hf sweepai/sweep-next-edit-1.5B --ctx-size 8192")
        sys.exit(1)
    print(f"{COL_GREEN}Server healthy{COL_RESET}")

    # Build test matrix
    cases = build_test_cases(args.lang, args.position, args.size, args.complexity)
    total = len(cases)
    print(f"\n{COL_BOLD}Running {total} test cases...{COL_RESET}")
    print(f"  Languages:    {', '.join(args.lang)}")
    print(f"  Positions:    {', '.join(args.position)}")
    print(f"  Sizes:        {', '.join(str(s) for s in args.size)}")
    print(f"  Complexities: {', '.join(args.complexity)}")
    print()

    # Run tests
    results = []
    errors = 0
    for i, case in enumerate(cases, 1):
        label = f"{case.lang}/{case.complexity}/{case.size}L/{case.position}"
        print(f"\r  [{i:>3}/{total}] {label:<50}", end="", flush=True)

        result = run_test(case, base_url)
        results.append(result)
        if result.error:
            errors += 1
            print(f" {COL_RED}ERR{COL_RESET}", end="")

    print(f"\r  {' ' * 70}\r", end="")  # Clear progress line

    # Write output
    with open(args.output, "w") as f:
        f.write(f"FIM Matrix Test Results\n")
        f.write(f"Server: {base_url}\n")
        f.write(f"Date: {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}\n")
        f.write(f"Total tests: {total}, Errors: {errors}\n")
        f.write(f"Languages: {', '.join(args.lang)}\n")
        f.write(f"Positions: {', '.join(args.position)}\n")
        f.write(f"Sizes: {', '.join(str(s) for s in args.size)}\n")
        f.write(f"Complexities: {', '.join(args.complexity)}\n\n")

        # Summary table
        print(f"\n{COL_BOLD}Summary ({total} tests, {errors} errors):{COL_RESET}\n")
        print_summary_table(results, file=f)

        # Detailed output
        if args.verbose:
            f.write("\n\nDETAILED RESULTS\n")
            for r in results:
                print_detail(r, file=f)
        else:
            # Always write details to file even without --verbose
            f.write("\n\nDETAILED RESULTS\n")
            for r in results:
                print_detail(r, file=f)

    print(f"\n{COL_GREEN}Output written to: {args.output}{COL_RESET}")

    # Aggregate stats
    successful = [r for r in results if not r.error]
    if successful:
        print(f"\n{COL_BOLD}Aggregate stats (successful tests):{COL_RESET}")

        # By language
        print(f"\n  {'Language':<12} {'Tests':>5} {'Avg Eval':>9} {'Avg Cached':>11} {'Avg Ms':>9}")
        for lang in args.lang:
            lang_results = [r for r in successful if r.test.lang == lang]
            if lang_results:
                avg_eval = sum(r.tokens_evaluated for r in lang_results) / len(lang_results)
                avg_cached = sum(r.tokens_cached for r in lang_results) / len(lang_results)
                avg_ms = sum(r.elapsed_ms for r in lang_results) / len(lang_results)
                print(f"  {lang:<12} {len(lang_results):>5} {avg_eval:>9.1f} {avg_cached:>11.1f} {avg_ms:>9.1f}")

        # By position
        print(f"\n  {'Position':<12} {'Tests':>5} {'Avg Eval':>9} {'Avg Cached':>11} {'Avg Ms':>9}")
        for pos in args.position:
            pos_results = [r for r in successful if r.test.position == pos]
            if pos_results:
                avg_eval = sum(r.tokens_evaluated for r in pos_results) / len(pos_results)
                avg_cached = sum(r.tokens_cached for r in pos_results) / len(pos_results)
                avg_ms = sum(r.elapsed_ms for r in pos_results) / len(pos_results)
                print(f"  {pos:<12} {len(pos_results):>5} {avg_eval:>9.1f} {avg_cached:>11.1f} {avg_ms:>9.1f}")

        # By size
        print(f"\n  {'Size':<12} {'Tests':>5} {'Avg Eval':>9} {'Avg Cached':>11} {'Avg Ms':>9}")
        for sz in args.size:
            sz_results = [r for r in successful if r.test.size == sz]
            if sz_results:
                avg_eval = sum(r.tokens_evaluated for r in sz_results) / len(sz_results)
                avg_cached = sum(r.tokens_cached for r in sz_results) / len(sz_results)
                avg_ms = sum(r.elapsed_ms for r in sz_results) / len(sz_results)
                print(f"  {sz:<12} {len(sz_results):>5} {avg_eval:>9.1f} {avg_cached:>11.1f} {avg_ms:>9.1f}")

        # By complexity
        print(f"\n  {'Complexity':<12} {'Tests':>5} {'Avg Eval':>9} {'Avg Cached':>11} {'Avg Ms':>9}")
        for cx in args.complexity:
            cx_results = [r for r in successful if r.test.complexity == cx]
            if cx_results:
                avg_eval = sum(r.tokens_evaluated for r in cx_results) / len(cx_results)
                avg_cached = sum(r.tokens_cached for r in cx_results) / len(cx_results)
                avg_ms = sum(r.elapsed_ms for r in cx_results) / len(cx_results)
                print(f"  {cx:<12} {len(cx_results):>5} {avg_eval:>9.1f} {avg_cached:>11.1f} {avg_ms:>9.1f}")


if __name__ == "__main__":
    main()
