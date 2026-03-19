#!/usr/bin/env python3
"""
Harness: Test Order 89 prompt with <Order89OutputCode> XML tags for output fencing.

Follow-up to harness 51 — tests whether asking the model to wrap output in
<Order89OutputCode>...</Order89OutputCode> tags works reliably.

Server: llama.cpp at http://localhost:8012 (/completion endpoint)
"""

import json
import re
import sys
import time
import urllib.request
import urllib.error
import textwrap

SERVER_URL = "http://localhost:8012"
COMPLETION_ENDPOINT = f"{SERVER_URL}/completion"
RUNS_PER_VARIANT = 10
MAX_TOKENS = 1024

# ---------------------------------------------------------------------------
# File content (same as harness 51)
# ---------------------------------------------------------------------------

BEFORE_CURSOR = r'''<?php
/**
 * The Dependency Injection container.
 *
 * @package lucatume\DI52
 */

namespace lucatume\DI52;

use ArrayAccess;
use Closure;
use Exception;
use lucatume\DI52\Builders\BuilderInterface;
use lucatume\DI52\Builders\ValueBuilder;
use Psr\Container\ContainerInterface;
use ReflectionClass;
use ReflectionException;
use ReflectionMethod;
use ReturnTypeWillChange;
use Throwable;
use function spl_object_hash;

/**
 * Class Container
 *
 * @package lucatume\DI52
 * @implements ArrayAccess<string,object>
 */
class Container implements ArrayAccess, ContainerInterface
{
    const EXCEPTION_MASK_NONE = 0;
    const EXCEPTION_MASK_MESSAGE = 1;
    const EXCEPTION_MASK_FILE_LINE = 2;

    /**
     * An array cache to store the results of the class exists checks.
     *
     * @var array<string,bool>
     */
    protected $classIsInstantiatableCache = [];
    /**
     * A cache of what methods are static and what are not.
     *
     * @var array<string,bool>
     */
    protected $isStaticMethodCache = [];
    /**
     * A list of bound and resolved singletons.
     *
     * @var array<string|class-string,bool>
     */
    protected $singletons = [];
    /**
     * @var array<ServiceProvider>
     */
    protected $deferred = [];
    /**
     * @var array<string,array<string|object|callable>>
     */
    protected $tags = [];
    /**
     * @var array<ServiceProvider>
     */
    protected $bootable = [];
    /**
     * @var string
     */
    protected $whenClass;
    /**
     * @var string
     */
    protected $needsClass;
    /**
     * A map from class name and static methods to the built callback.
     *
     * @var array<string,Closure>
     */
    protected $callbacks = [];
    /**
     * @var Builders\Resolver
     */
    protected $resolver;
    /**
     * @var Builders\Factory
     */
    protected $builders;
    /**
     * What kind of masking should be applied to throwables catched by the container during resolution.
     *
     * @var int
     */
    private $maskThrowables = self::EXCEPTION_MASK_MESSAGE | self::EXCEPTION_MASK_FILE_LINE;

    /**
     * Container constructor.
     *
     * @param false $resolveUnboundAsSingletons Whether unbound classes should be resolved as singletons by default,
     *                                          or not.
     */
    public function __construct($resolveUnboundAsSingletons = false)
    {
        $this->resolver = new Builders\Resolver($resolveUnboundAsSingletons);
        $this->builders = new Builders\Factory($this, $this->resolver);
        $this->bindThis();
    }

    /**
     * Sets a variable on the container.
     *
     * @param string $key   The alias the container will use to reference the variable.
     * @param mixed  $value The variable value.
     *
     * @return void The method does not return any value.
     */
    public function setVar($key, $value)
    {
        $this->resolver->bind($key, ValueBuilder::of($value));
    }

    /**
     * Sets a variable on the container using the ArrayAccess API.
     *
     * When using the container as an array bindings will be bound as singletons.
     * These are equivalent: `$container->singleton('foo','ClassOne');`, `$container['foo'] = 'ClassOne';`.
     *
     * @param string $offset The alias the container will use to reference the variable.
     * @param mixed  $value  The variable value.
     *
     * @return void This method does not return any value.
     *
     * @throws ContainerException If the closure building fails.
     */
    #[ReturnTypeWillChange]
    public function offsetSet($offset, $value)
    {
        $this->singleton($offset, $value);
    }

    /**
     * Binds an interface a class or a string slug to an implementation and will always return the same instance.
     *
     * @param string             $id                A class or interface fully qualified name or a string slug.
     * @param mixed              $implementation    The implementation that should be bound to the alias(es); can be a
     *                                              class name, an object or a closure.
     * @param array<string>|null $afterBuildMethods An array of methods that should be called on the built
     *                                              implementation after resolving it.
     *
     * @return void This method does not return any value.
     * @throws ContainerException If there's any issue reflecting on the class, interface or the implementation.
     */
    public function singleton($id, $implementation = null, ?array $afterBuildMethods = null)
    {
        if ($implementation === null) {
            $implementation = $id;
        }

        $this->resolver->singleton($id, $this->builders->getBuilder($id, $implementation, $afterBuildMethods));
    }

    /**
     * Returns a variable stored in the container.
     *
     * If the variable is a binding then the binding will be resolved before returning it.
     *
     * @param string     $key     The alias of the variable or binding to fetch.
     * @param mixed|null $default A default value to return if the variable is not set in the container.
     *
     * @return mixed The variable value or the resolved binding.
     * @throws ContainerException If there's an issue resolving the variable.
     *
     * @see Container::get()
     */
    public function getVar($key, $default = null)
    {
        if ($this->resolver->isBound($key)) {
            return $this->resolver->resolve($key);
        }

        return $default;
    }

    '''

AFTER_CURSOR = r'''
    /**
     * Finds an entry of the container by its identifier and returns it.
     *
     * @template T
     *
     * @param string|class-string<T> $offset Identifier of the entry to look for.
     *
     * @return T|mixed The value for the offset.
     * @phpstan-return ($offset is class-string ? T : mixed)
     *
     * @throws ContainerException Error while retrieving the entry.
     * @throws NotFoundException  No entry was found for **this** identifier.
     */
    #[ReturnTypeWillChange]
    public function offsetGet($offset)
    {
        return $this->get($offset);
    }

    /**
     * Finds an entry of the container by its identifier and returns it.
     *
     * @template T
     *
     * @param  string|class-string<T>  $id  A fully qualified class or interface name or an already built object.
     *
     * @return T|mixed The entry for an id.
     * @phpstan-return ($id is class-string ? T : mixed)
     *
     * @throws ContainerException Error while retrieving the entry.
     */
    public function get($id)
    {
        try {
            return $this->resolver->resolve($id, [$id]);
        } catch (Throwable $throwable) {
            throw $this->castThrown($throwable, $id);
        } catch (Exception $exception) {
            throw $this->castThrown($exception, $id);
        }
    }

    public function make($id)
    {
        return $this->get($id);
    }
}'''

FILE_PATH = "src/Container.php"
LANGUAGE = "PHP"
USER_INSTRUCTION = "implement the makeWith method"

# ---------------------------------------------------------------------------
# Prompt variants — all use <Order89OutputCode> but with different wording
# ---------------------------------------------------------------------------

def build_variants():
    variants = []

    # ---- A: Simple instruction to use <Order89OutputCode> tags ----
    variants.append({
        "name": "xml_tags_simple",
        "description": "Ask for <Order89OutputCode> tags, simple instruction",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection.

            <Order89Rules>
            - Wrap your code output in <Order89OutputCode> and </Order89OutputCode> XML tags.
            - Do NOT describe what you are about to do. Do NOT explain your reasoning.
            - Do NOT include any text before or after the <Order89OutputCode> tags.
            - If the selection is empty (<Order89UserSelection></Order89UserSelection>),
              output code to insert at that position.
            - Preserve the indentation style of the surrounding code.
            </Order89Rules>

            Language: {LANGUAGE}
            File: {FILE_PATH}

            <Order89Instruction>
            {USER_INSTRUCTION}
            </Order89Instruction>

            <Order89FileContent>
            {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
            </Order89FileContent>
            </Order89Prompt>"""),
    })

    # ---- B: With explicit output format example ----
    variants.append({
        "name": "xml_tags_with_example",
        "description": "Ask for <Order89OutputCode> tags with explicit example",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection.

            <Order89Rules>
            - Wrap your code output in <Order89OutputCode> and </Order89OutputCode> XML tags.
            - Do NOT explain your reasoning. No prose, no markdown.
            - If the selection is empty, output code to insert at that position.
            - Preserve the indentation style of the surrounding code.
            </Order89Rules>

            <Order89OutputFormat>
            Your response must look exactly like this (with your actual code inside):
            <Order89OutputCode>
            // your generated code here
            </Order89OutputCode>
            </Order89OutputFormat>

            Language: {LANGUAGE}
            File: {FILE_PATH}

            <Order89Instruction>
            {USER_INSTRUCTION}
            </Order89Instruction>

            <Order89FileContent>
            {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
            </Order89FileContent>
            </Order89Prompt>"""),
    })

    # ---- C: Prefill — prompt ends with opening tag to nudge the model ----
    variants.append({
        "name": "xml_tags_prefill",
        "description": "Prompt ends with <Order89OutputCode> to prefill/nudge the model",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection wrapped in <Order89OutputCode> tags.

            <Order89Rules>
            - Output the replacement code between <Order89OutputCode> and </Order89OutputCode>.
            - No explanations, no prose, no markdown fences.
            - If the selection is empty, output code to insert at that position.
            - Preserve the indentation style of the surrounding code.
            </Order89Rules>

            Language: {LANGUAGE}
            File: {FILE_PATH}

            <Order89Instruction>
            {USER_INSTRUCTION}
            </Order89Instruction>

            <Order89FileContent>
            {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
            </Order89FileContent>
            </Order89Prompt>

            <Order89OutputCode>"""),
    })

    # ---- D: Backtick fences (v2 winner from harness 51, for comparison) ----
    variants.append({
        "name": "backtick_fences_baseline",
        "description": "Backtick fences (v2 from harness 51, baseline comparison)",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection.

            <Order89Rules>
            - Wrap your code output in a fenced code block using triple backticks with the language identifier.
            - Do NOT describe what you are about to do. Do NOT explain your reasoning.
            - Do NOT include any text before or after the fenced code block.
            - If the selection is empty (<Order89UserSelection></Order89UserSelection>),
              output code to insert at that position.
            - Preserve the indentation style of the surrounding code.
            </Order89Rules>

            Language: {LANGUAGE}
            File: {FILE_PATH}

            <Order89Instruction>
            {USER_INSTRUCTION}
            </Order89Instruction>

            <Order89FileContent>
            {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
            </Order89FileContent>
            </Order89Prompt>"""),
    })

    return variants

# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------

XML_OUTPUT_TAG = re.compile(r"<Order89OutputCode>([\s\S]*?)</Order89OutputCode>")
XML_OUTPUT_TAG_OPEN_ONLY = re.compile(r"<Order89OutputCode>([\s\S]*)")
BACKTICK_FENCE = re.compile(r"```[\w]*\n([\s\S]*?)\n\s*```")


def extract_code(raw: str) -> tuple[str, str]:
    """Try to extract code. Returns (code, method)."""
    # Try Order89OutputCode XML tags (closed)
    m = XML_OUTPUT_TAG.search(raw)
    if m:
        return m.group(1).strip(), "xml_closed"

    # Try backtick fences
    m = BACKTICK_FENCE.search(raw)
    if m:
        return m.group(1), "backtick_fence"

    # Try Order89OutputCode open-only (model emitted opening tag but closing was a stop token)
    m = XML_OUTPUT_TAG_OPEN_ONLY.search(raw)
    if m:
        return m.group(1).strip(), "xml_open_only"

    return raw.strip(), "raw_unfenced"


def looks_like_php(text: str) -> bool:
    indicators = ["function ", "public ", "private ", "protected ",
                  "$this->", "return ", "/**", "@param", "@return",
                  "array", "->", "::"]
    lines = text.strip().split("\n")
    if not lines:
        return False
    hits = sum(1 for line in lines for ind in indicators if ind in line)
    return hits >= 2


def has_prose(text: str) -> bool:
    prose_patterns = [r"^Here('s| is)", r"^This (method|function|code)",
                      r"^I('ve| have| will)", r"^The (method|function)",
                      r"^Below is", r"^Note:", r"^Let me"]
    for line in text.strip().split("\n")[:3]:
        for p in prose_patterns:
            if re.match(p, line.strip(), re.IGNORECASE):
                return True
    return False


# ---------------------------------------------------------------------------
# Completion request
# ---------------------------------------------------------------------------

def complete(prompt: str, stop_tokens: list[str]) -> str | None:
    payload = json.dumps({
        "prompt": prompt,
        "n_predict": MAX_TOKENS,
        "temperature": 0.2,
        "top_p": 0.9,
        "stop": stop_tokens,
        "cache_prompt": False,
    }).encode()

    req = urllib.request.Request(
        COMPLETION_ENDPOINT, data=payload,
        headers={"Content-Type": "application/json"}, method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode())
            return data.get("content", "")
    except Exception as e:
        print(f"  ERROR: {e}", file=sys.stderr)
        return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else RUNS_PER_VARIANT

    print(f"Checking llama.cpp server at {SERVER_URL}...")
    try:
        with urllib.request.urlopen(f"{SERVER_URL}/health", timeout=5) as resp:
            health = json.loads(resp.read().decode())
            print(f"  Server status: {health.get('status', 'unknown')}")
    except Exception as e:
        print(f"  ERROR: Cannot reach server: {e}")
        sys.exit(1)

    variants = build_variants()
    print(f"\nTesting {len(variants)} variants, {runs} run(s) each.\n")
    print("=" * 90)

    results = {}

    for vi, v in enumerate(variants):
        name = v["name"]
        print(f"\n[{vi+1}/{len(variants)}] {name}")
        print(f"  {v['description']}")

        # Use different stop tokens depending on variant
        if "prefill" in name:
            # Prefill variant: the prompt already opened the tag, stop at closing tag
            stop_tokens = ["</Order89OutputCode>", "\n\n\n\n"]
        elif "backtick" in name:
            stop_tokens = ["</Order89Prompt>", "\n\n\n\n"]
        else:
            # XML tag variants: do NOT stop at </Order89OutputCode> — let the model close it
            stop_tokens = ["</Order89Prompt>", "\n\n\n\n"]

        run_results = []
        for ri in range(runs):
            print(f"  Run {ri+1}/{runs}...", end=" ", flush=True)
            t0 = time.time()
            raw = complete(v["prompt"], stop_tokens)
            duration = time.time() - t0

            if raw is None:
                print(f"FAILED ({duration:.1f}s)")
                continue

            code, method = extract_code(raw)
            is_php = looks_like_php(code)
            prose = has_prose(raw)

            run_results.append({
                "raw": raw, "code": code, "method": method,
                "is_php": is_php, "prose": prose, "duration": duration,
            })

            parts = [f"extraction={method}", f"php={'yes' if is_php else 'NO'}",
                     f"prose={'YES' if prose else 'no'}", f"{duration:.1f}s",
                     f"len={len(code)}"]
            print(" | ".join(parts))

        results[name] = {"desc": v["description"], "runs": run_results}

    # ---- Summary ----
    print("\n" + "=" * 90)
    print("SUMMARY")
    print("=" * 90)
    print(f"\n{'Variant':<30} {'Fenced%':>8} {'PHP%':>6} {'Prose%':>7} {'AvgLen':>7} {'Avg(s)':>7}")
    print("-" * 72)

    for name, data in results.items():
        rs = data["runs"]
        if not rs:
            continue
        fenced = sum(1 for r in rs if r["method"] != "raw_unfenced") / len(rs)
        php = sum(1 for r in rs if r["is_php"]) / len(rs)
        prose = sum(1 for r in rs if r["prose"]) / len(rs)
        avg_len = sum(len(r["code"]) for r in rs) / len(rs)
        avg_dur = sum(r["duration"] for r in rs) / len(rs)
        print(f"{name:<30} {fenced*100:>7.0f}% {php*100:>5.0f}% {prose*100:>6.0f}% {avg_len:>7.0f} {avg_dur:>6.1f}")

    # ---- Show first raw output per variant ----
    print("\n" + "=" * 90)
    print("SAMPLE OUTPUTS (first run per variant)")
    print("=" * 90)

    for name, data in results.items():
        rs = data["runs"]
        if not rs:
            continue
        r = rs[0]
        print(f"\n--- {name} (extraction={r['method']}) ---")
        print(f"Raw output ({len(r['raw'])} chars):")
        for line in r["raw"][:800].split("\n"):
            print(f"  | {line}")
        if len(r["raw"]) > 800:
            print("  | ... (truncated)")
        print(f"\nExtracted code ({len(r['code'])} chars):")
        for line in r["code"][:500].split("\n"):
            print(f"  | {line}")


if __name__ == "__main__":
    main()
