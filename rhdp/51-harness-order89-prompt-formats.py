#!/usr/bin/env python3
"""
Harness: Test different prompt formats for Order 89 against a local llama.cpp server.

Goal: Find which prompt format reliably makes the model output code between proper
fences (triple-backticks, XML tags, etc.) so the plugin can extract it cleanly.

Test scenario:
  - File: Container.php from lucatume/di52 (DI container)
  - Cursor at line 180 (empty line between getVar() and offsetGet())
  - User prompt: "implement the makeWith method"
  - Empty selection (insertion mode)

Server: llama.cpp at http://localhost:8012 (uses /completion endpoint)

Each prompt variant is tested N times (default 3) to assess consistency.
Output is written to STDOUT and to a companion output file.
"""

import json
import sys
import time
import urllib.request
import urllib.error
import textwrap
from dataclasses import dataclass, field
from typing import Optional

SERVER_URL = "http://localhost:8012"
COMPLETION_ENDPOINT = f"{SERVER_URL}/completion"
RUNS_PER_VARIANT = 3
MAX_TOKENS = 1024

# ---------------------------------------------------------------------------
# The file content, split at the cursor position (line 180, empty line
# between getVar() and offsetGet())
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
# Prompt format variants
# ---------------------------------------------------------------------------

@dataclass
class PromptVariant:
    name: str
    prompt: str
    description: str


def build_variants() -> list[PromptVariant]:
    """Build all prompt format variants to test."""

    variants = []

    # ---- Variant 1: Current Order 89 format (XML tags, "raw code only") ----
    variants.append(PromptVariant(
        name="v1_current_xml_raw",
        description="Current Order 89 format: XML tags, asks for raw code only (no fences)",
        prompt=textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection. Nothing else.

            <Order89Rules>
            - Output raw code only. No markdown fences, no backticks, no explanations.
            - Do NOT describe what you are about to do. Do NOT explain your reasoning.
            - Do NOT include any text before or after the replacement code.
            - Do NOT wrap output in ```code fences```.
            - If the instruction asks for comments in the code, include them. But never include
              conversational text — only text that is valid in the target language.
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
            </Order89Prompt>""")
    ))

    # ---- Variant 2: XML tags, explicitly request fenced output ----
    variants.append(PromptVariant(
        name="v2_xml_request_fences",
        description="XML tags, explicitly asks model to wrap output in triple-backtick fences",
        prompt=textwrap.dedent(f"""\
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
            </Order89Prompt>""")
    ))

    # ---- Variant 3: XML tags, request XML-fenced output ----
    variants.append(PromptVariant(
        name="v3_xml_request_xml_output",
        description="XML tags, asks model to wrap output in <code>...</code> XML tags",
        prompt=textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the replacement code wrapped in <code> tags.

            <Order89Rules>
            - Wrap your output in <code> and </code> XML tags. Nothing outside the tags.
            - Do NOT explain your reasoning or describe the code.
            - Do NOT include any text before or after the <code> tags.
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
            </Order89Prompt>""")
    ))

    # ---- Variant 4: Chat/instruct style (no XML), request fenced output ----
    variants.append(PromptVariant(
        name="v4_chat_fences",
        description="Plain chat/instruct style with no XML tags, asks for fenced output",
        prompt=textwrap.dedent(f"""\
            You are a code transformation tool. Given a file and an instruction, output ONLY the replacement code in a fenced code block.

            RULES:
            1. Output a single fenced code block (```php ... ```) containing the replacement code.
            2. No explanations, no prose, no text outside the code block.
            3. The cursor is at an empty line — generate code to insert there.
            4. Match the indentation style of the surrounding code.

            LANGUAGE: {LANGUAGE}
            FILE: {FILE_PATH}
            INSTRUCTION: {USER_INSTRUCTION}

            FILE CONTENT (the cursor position is marked with {{{{CURSOR}}}}):
            {BEFORE_CURSOR}{{{{CURSOR}}}}{AFTER_CURSOR}""")
    ))

    # ---- Variant 5: FIM-style (prefix/suffix/middle) ----
    variants.append(PromptVariant(
        name="v5_fim_style",
        description="FIM-style with prefix/suffix markers and instruction as comment",
        prompt=textwrap.dedent(f"""\
            You are a code completion tool. Complete the code at the cursor position.
            The user wants you to: {USER_INSTRUCTION}

            Output ONLY the code to insert. Wrap it in ```php ... ``` fences.
            No explanations.

            <prefix>
            {BEFORE_CURSOR}
            </prefix>

            <suffix>
            {AFTER_CURSOR}
            </suffix>

            <middle>""")
    ))

    # ---- Variant 6: Structured XML with explicit output format example ----
    variants.append(PromptVariant(
        name="v6_xml_with_example",
        description="XML tags with an explicit output format example showing fences",
        prompt=textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool.

            <Order89Rules>
            - You receive a file with a marked insertion point and an instruction.
            - Output the replacement code in a fenced code block.
            - Use triple backticks with the language identifier.
            - Nothing else — no prose, no explanation.
            </Order89Rules>

            <Order89OutputFormat>
            Your response must look exactly like this (with your actual code inside):
            ```php
            // your generated code here
            ```
            </Order89OutputFormat>

            Language: {LANGUAGE}
            File: {FILE_PATH}

            <Order89Instruction>
            {USER_INSTRUCTION}
            </Order89Instruction>

            <Order89FileContent>
            {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
            </Order89FileContent>
            </Order89Prompt>""")
    ))

    # ---- Variant 7: Minimal prompt, raw code ----
    variants.append(PromptVariant(
        name="v7_minimal_raw",
        description="Minimal prompt: just the instruction and file, asks for raw code only",
        prompt=textwrap.dedent(f"""\
            Insert code at the cursor position in this PHP file.
            Instruction: {USER_INSTRUCTION}
            Output only the raw PHP code to insert. No fences, no markdown, no explanation.

            {BEFORE_CURSOR}<<INSERT_HERE>>{AFTER_CURSOR}""")
    ))

    # ---- Variant 8: JSON-structured request ----
    variants.append(PromptVariant(
        name="v8_json_structured",
        description="JSON-structured request asking for response in JSON with code field",
        prompt=json.dumps({
            "task": "code_insertion",
            "instruction": USER_INSTRUCTION,
            "language": LANGUAGE,
            "file": FILE_PATH,
            "context_before": BEFORE_CURSOR[-800:],  # Trim to last 800 chars
            "context_after": AFTER_CURSOR[:800],      # Trim to first 800 chars
            "output_format": "Respond with ONLY a JSON object: {\"code\": \"<your code here>\"}. No other text."
        }, indent=2)
    ))

    return variants


# ---------------------------------------------------------------------------
# Extraction helpers — try to pull code from various fence formats
# ---------------------------------------------------------------------------

import re

BACKTICK_FENCE = re.compile(r"```[\w]*\n([\s\S]*?)\n\s*```")
XML_CODE_TAG = re.compile(r"<code>([\s\S]*?)</code>")
JSON_CODE_FIELD = re.compile(r'"code"\s*:\s*"((?:[^"\\]|\\.)*)"')
MIDDLE_TAG = re.compile(r"<middle>([\s\S]*?)(?:</middle>|$)")


def extract_code(raw: str) -> tuple[str, str]:
    """Try to extract code from the raw output. Returns (code, method_used)."""
    # Try backtick fences
    m = BACKTICK_FENCE.search(raw)
    if m:
        return m.group(1), "backtick_fence"

    # Try XML <code> tags
    m = XML_CODE_TAG.search(raw)
    if m:
        return m.group(1), "xml_code_tag"

    # Try JSON code field
    m = JSON_CODE_FIELD.search(raw)
    if m:
        return m.group(1).encode().decode('unicode_escape'), "json_code_field"

    # Try <middle> tag
    m = MIDDLE_TAG.search(raw)
    if m:
        return m.group(1), "middle_tag"

    # No fence found — return raw
    return raw.strip(), "raw_unfenced"


def looks_like_php_code(text: str) -> bool:
    """Quick heuristic: does this look like PHP code?"""
    indicators = [
        "function ", "public ", "private ", "protected ",
        "$this->", "return ", "/**", "@param", "@return",
        "array", "?array", "->", "::"
    ]
    lines = text.strip().split("\n")
    if not lines:
        return False
    hits = sum(1 for line in lines for ind in indicators if ind in line)
    return hits >= 2


def has_prose(text: str) -> bool:
    """Check if the output contains conversational prose."""
    prose_patterns = [
        r"^Here('s| is)",
        r"^This (method|function|code|implementation)",
        r"^I('ve| have| will)",
        r"^The (method|function|code|implementation)",
        r"^Below is",
        r"^Note:",
        r"^Let me",
    ]
    first_lines = text.strip().split("\n")[:3]
    for line in first_lines:
        for pattern in prose_patterns:
            if re.match(pattern, line.strip(), re.IGNORECASE):
                return True
    return False


# ---------------------------------------------------------------------------
# Run a single completion request
# ---------------------------------------------------------------------------

def complete(prompt: str) -> Optional[str]:
    """Send a completion request to the llama.cpp server."""
    payload = json.dumps({
        "prompt": prompt,
        "n_predict": MAX_TOKENS,
        "temperature": 0.2,
        "top_p": 0.9,
        "stop": ["</Order89Prompt>", "</code>", "</middle>", "\n\n\n\n"],
        "cache_prompt": False,
    }).encode()

    req = urllib.request.Request(
        COMPLETION_ENDPOINT,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode())
            return data.get("content", "")
    except urllib.error.URLError as e:
        print(f"  ERROR: {e}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"  ERROR: {e}", file=sys.stderr)
        return None


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

@dataclass
class RunResult:
    raw_output: str
    extracted_code: str
    extraction_method: str
    is_php: bool
    has_prose_contamination: bool
    duration_s: float


@dataclass
class VariantScore:
    name: str
    description: str
    runs: list[RunResult] = field(default_factory=list)

    @property
    def avg_duration(self) -> float:
        if not self.runs:
            return 0
        return sum(r.duration_s for r in self.runs) / len(self.runs)

    @property
    def fence_rate(self) -> float:
        """How often did the output come in a parseable fence?"""
        if not self.runs:
            return 0
        fenced = sum(1 for r in self.runs if r.extraction_method != "raw_unfenced")
        return fenced / len(self.runs)

    @property
    def code_quality_rate(self) -> float:
        """How often did the extracted output look like valid PHP code?"""
        if not self.runs:
            return 0
        good = sum(1 for r in self.runs if r.is_php)
        return good / len(self.runs)

    @property
    def prose_contamination_rate(self) -> float:
        """How often did the output contain conversational prose?"""
        if not self.runs:
            return 0
        bad = sum(1 for r in self.runs if r.has_prose_contamination)
        return bad / len(self.runs)

    @property
    def composite_score(self) -> float:
        """Composite: fence_rate * 0.4 + code_quality * 0.4 + (1 - prose) * 0.2"""
        return (self.fence_rate * 0.4 +
                self.code_quality_rate * 0.4 +
                (1 - self.prose_contamination_rate) * 0.2)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else RUNS_PER_VARIANT

    # Check server connectivity
    print(f"Checking llama.cpp server at {SERVER_URL}...")
    try:
        with urllib.request.urlopen(f"{SERVER_URL}/health", timeout=5) as resp:
            health = json.loads(resp.read().decode())
            print(f"  Server status: {health.get('status', 'unknown')}")
    except Exception as e:
        print(f"  ERROR: Cannot reach server at {SERVER_URL}: {e}")
        print("  Make sure llama.cpp server is running on port 8012.")
        sys.exit(1)

    variants = build_variants()
    scores: list[VariantScore] = []

    print(f"\nTesting {len(variants)} prompt variants, {runs} run(s) each.\n")
    print("=" * 80)

    for vi, variant in enumerate(variants):
        print(f"\n[{vi+1}/{len(variants)}] {variant.name}")
        print(f"  {variant.description}")
        score = VariantScore(name=variant.name, description=variant.description)

        for run_i in range(runs):
            print(f"  Run {run_i+1}/{runs}...", end=" ", flush=True)
            t0 = time.time()
            raw = complete(variant.prompt)
            duration = time.time() - t0

            if raw is None:
                print(f"FAILED ({duration:.1f}s)")
                continue

            code, method = extract_code(raw)
            is_php = looks_like_php_code(code)
            prose = has_prose(raw)

            result = RunResult(
                raw_output=raw,
                extracted_code=code,
                extraction_method=method,
                is_php=is_php,
                has_prose_contamination=prose,
                duration_s=duration,
            )
            score.runs.append(result)

            status_parts = [
                f"extraction={method}",
                f"php={'yes' if is_php else 'NO'}",
                f"prose={'YES' if prose else 'no'}",
                f"{duration:.1f}s",
            ]
            print(" | ".join(status_parts))

        scores.append(score)

    # ---- Summary ----
    print("\n" + "=" * 80)
    print("SUMMARY")
    print("=" * 80)
    print(f"\n{'Variant':<30} {'Fence%':>7} {'PHP%':>7} {'Prose%':>7} {'Score':>7} {'Avg(s)':>7}")
    print("-" * 72)

    scores.sort(key=lambda s: s.composite_score, reverse=True)

    for s in scores:
        print(f"{s.name:<30} {s.fence_rate*100:>6.0f}% {s.code_quality_rate*100:>6.0f}% "
              f"{s.prose_contamination_rate*100:>6.0f}% {s.composite_score:>6.2f} {s.avg_duration:>6.1f}")

    print(f"\nBest variant: {scores[0].name} (score {scores[0].composite_score:.2f})")

    # ---- Detailed output per variant ----
    print("\n" + "=" * 80)
    print("DETAILED OUTPUTS")
    print("=" * 80)

    for s in scores:
        print(f"\n--- {s.name} ---")
        print(f"Description: {s.description}")
        for ri, r in enumerate(s.runs):
            print(f"\n  [Run {ri+1}] extraction={r.extraction_method} php={r.is_php} prose={r.has_prose_contamination}")
            print(f"  Raw output ({len(r.raw_output)} chars):")
            # Show first 600 chars of raw output, indented
            preview = r.raw_output[:600]
            if len(r.raw_output) > 600:
                preview += "\n... (truncated)"
            for line in preview.split("\n"):
                print(f"    | {line}")

    # ---- Recommendation ----
    best = scores[0]
    print("\n" + "=" * 80)
    print("RECOMMENDATION")
    print("=" * 80)
    print(f"\nBest prompt format: {best.name}")
    print(f"  Fence extraction rate: {best.fence_rate*100:.0f}%")
    print(f"  PHP code quality rate: {best.code_quality_rate*100:.0f}%")
    print(f"  Prose contamination:   {best.prose_contamination_rate*100:.0f}%")
    print(f"  Composite score:       {best.composite_score:.2f}")
    print(f"  Avg response time:     {best.avg_duration:.1f}s")

    if best.fence_rate >= 0.8:
        print(f"\n  The model reliably produces fenced output with this format.")
        print(f"  Extraction method: {best.runs[0].extraction_method if best.runs else 'N/A'}")
    else:
        print(f"\n  WARNING: Fence rate is below 80%. Consider:")
        print(f"  - Using a larger or more instruction-tuned model")
        print(f"  - Adding few-shot examples to the prompt")
        print(f"  - Falling back to the raw-code extraction pipeline (current cleanOutput())")


if __name__ == "__main__":
    main()
