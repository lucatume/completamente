#!/usr/bin/env python3
"""
Harness: Fix the convention-matching regression when structure context is added.

Problem from harness 57: structure context gives 100% API accuracy (calls real
methods, try/catch) but 0% docblocks — the model mimics the context files' bare
style instead of the main file's documented style.

Approach: keep no-context as the target (11.4/12.5) and try prompt variations
on the structure variant to recover docblock generation while keeping API accuracy.

Variants:
  A. No context (baseline, untouched — the convention winner)
  B. Structure baseline (reproduction from harness 57)
  C-H. Structure with different prompts to fix convention matching

Server: llama.cpp at http://localhost:8012 (/completion endpoint)
"""

import json
import re
import sys
import time
import urllib.request
import urllib.error
import textwrap
from dataclasses import dataclass

SERVER_URL = "http://localhost:8012"
COMPLETION_ENDPOINT = f"{SERVER_URL}/completion"
RUNS_PER_VARIANT = 10
MAX_TOKENS = 1024

# ---------------------------------------------------------------------------
# File content (same as all previous harnesses)
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
# Structure-extracted context (same as harness 57 C_structure)
# ---------------------------------------------------------------------------

STRUCTURE_CONTEXT = {
    "src/Builders/Resolver.php": r'''<?php
namespace lucatume\DI52\Builders;

use lucatume\DI52\NotFoundException;

class Resolver
{
    /** @var array<string,BuilderInterface|mixed> */
    protected $bindings = [];
    /** @var bool */
    protected $resolveUnboundAsSingletons = false;
    /** @var array<string,bool> */
    protected $singletons = [];
    /** @var array<string,array<string,BuilderInterface>> */
    protected $whenNeedsGive = [];
    /** @var array<string> */
    protected $buildLine = [];

    public function __construct($resolveUnboundAsSingletons = false)
    public function bind($id, BuilderInterface $implementation)
    public function singleton($id, BuilderInterface $implementation)
    public function isBound($id)
    public function unbind($id)
    public function isSingleton($id)
    public function whenNeedsGive($id, $paramClass)
    public function setWhenNeedsGive($whenClass, $needsClass, BuilderInterface $builder)
    public function resolveWithArgs($id, ?array $afterBuildMethods = null, ...$buildArgs)
    public function resolve($id, ?array $buildLine = null)
    public function addToBuildLine($type, $parameterName)
    public function getBuildLine()
    public function buildLinePop()
}''',

    "src/Builders/Factory.php": r'''<?php
namespace lucatume\DI52\Builders;

use Closure;
use lucatume\DI52\Container;
use lucatume\DI52\NotFoundException;

class Factory
{
    /** @var Resolver */
    protected $resolver;
    /** @var Container */
    protected $container;

    public function __construct(Container $container, Resolver $resolver)
    public function getBuilder($id, $implementation = null, ?array $afterBuildMethods = null, ...$buildArgs)
    public function setContainer(Container $container)
    public function setResolver(Resolver $resolver)
}''',

    "src/Builders/BuilderInterface.php": r'''<?php
namespace lucatume\DI52\Builders;

interface BuilderInterface
{
    public function build();
}''',

    "src/Builders/ValueBuilder.php": r'''<?php
namespace lucatume\DI52\Builders;

class ValueBuilder implements BuilderInterface
{
    /** @var mixed */
    private $value;

    public function __construct($value)
    public static function of($value)
    public function build()
}''',

    "src/ContainerException.php": r'''<?php
namespace lucatume\DI52;

use Exception;
use Psr\Container\ContainerExceptionInterface;

class ContainerException extends Exception implements ContainerExceptionInterface
{
    public static function fromThrowable($id, $thrown, $maskThrowables, array $buildLine)
}''',

    "src/NotFoundException.php": r'''<?php
namespace lucatume\DI52;

use Psr\Container\NotFoundExceptionInterface;

class NotFoundException extends ContainerException implements NotFoundExceptionInterface
{
}''',
}


# ---------------------------------------------------------------------------
# Prompt builders
# ---------------------------------------------------------------------------

def format_context_files(files: dict[str, str]) -> str:
    parts = []
    for path, content in files.items():
        parts.append(f"<Order89ContextFile path=\"{path}\">\n{content.strip()}\n</Order89ContextFile>")
    return "\n\n".join(parts)


CONTEXT_FILES_BLOCK = format_context_files(STRUCTURE_CONTEXT)


def build_no_context_prompt() -> str:
    """Variant A: no context baseline (identical to harness 57 winner)."""
    return textwrap.dedent(f"""\
        <Order89Prompt>
        You are a code transformation tool. You receive a file with a marked selection and an instruction.
        You output ONLY the code that replaces the selection.

        <Order89Rules>
        - Wrap your code output in a fenced code block using triple backticks with the language identifier.
        - Do NOT add documentation blocks, comments, or type annotations that the surrounding
          code does not already use. Conversely, if the surrounding code includes documentation
          blocks on every function, include one on yours in the same format.
        - Preserve the indentation style, brace placement, and whitespace patterns of the
          surrounding code.
        - Do NOT describe what you are about to do. Do NOT explain your reasoning.
        - Do NOT include any text before or after the fenced code block.
        - If the selection is empty (<Order89UserSelection></Order89UserSelection>),
          output code to insert at that position.
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


def build_structure_prompt(context_intro: str, rules_addition: str = "") -> str:
    """Build a structure-context prompt with configurable intro and rules."""
    rules_block = textwrap.dedent(f"""\
        <Order89Rules>
        - Wrap your code output in a fenced code block using triple backticks with the language identifier.
        - Do NOT add documentation blocks, comments, or type annotations that the surrounding
          code does not already use. Conversely, if the surrounding code includes documentation
          blocks on every function, include one on yours in the same format.
        - Preserve the indentation style, brace placement, and whitespace patterns of the
          surrounding code.
        - Do NOT describe what you are about to do. Do NOT explain your reasoning.
        - Do NOT include any text before or after the fenced code block.
        - If the selection is empty (<Order89UserSelection></Order89UserSelection>),
          output code to insert at that position.{rules_addition}
        </Order89Rules>""")

    return textwrap.dedent(f"""\
        <Order89Prompt>
        You are a code transformation tool. You receive a file with a marked selection and an instruction.
        You output ONLY the code that replaces the selection.

        {rules_block}

        <Order89Context>
        {context_intro}

        {CONTEXT_FILES_BLOCK}
        </Order89Context>

        Language: {LANGUAGE}
        File: {FILE_PATH}

        <Order89Instruction>
        {USER_INSTRUCTION}
        </Order89Instruction>

        <Order89FileContent>
        {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
        </Order89FileContent>
        </Order89Prompt>""")


def build_variants():
    variants = []

    # ---- A: No context baseline ----
    variants.append({
        "name": "A_no_context",
        "description": "No context baseline (harness 57 winner)",
        "prompt": build_no_context_prompt(),
    })

    # ---- B: Structure baseline (reproduction from harness 57) ----
    variants.append({
        "name": "B_struct_baseline",
        "description": "Structure context, original intro (harness 57 reproduction)",
        "prompt": build_structure_prompt(
            "The following files are referenced by the file under edit. Use them to understand\n"
            "the APIs and types available, so your generated code calls real methods with correct signatures."
        ),
    })

    # ---- C: "API reference only" — tell model these are just signatures ----
    variants.append({
        "name": "C_api_ref_only",
        "description": "Context intro: 'API reference only — signatures, not style examples'",
        "prompt": build_structure_prompt(
            "The following are API signatures from referenced files. They are provided ONLY as\n"
            "an API reference so you call real methods with correct signatures. These are NOT\n"
            "style examples — do not imitate their formatting or documentation style."
        ),
    })

    # ---- D: "Structure view" framing ----
    variants.append({
        "name": "D_structure_view",
        "description": "Context intro: 'Structure view — stripped signatures, not representative of style'",
        "prompt": build_structure_prompt(
            "The following are structure-view extractions of referenced files. They show only\n"
            "class/method signatures with bodies removed. These are machine-generated summaries,\n"
            "not the actual source code — do not use them as style references."
        ),
    })

    # ---- E: Explicit "file under edit is the sole style authority" in rules ----
    variants.append({
        "name": "E_sole_authority",
        "description": "Rules addition: 'The file under edit is the sole source of truth for style'",
        "prompt": build_structure_prompt(
            "The following files are referenced by the file under edit. Use them to understand\n"
            "the APIs and types available, so your generated code calls real methods with correct signatures.",
            rules_addition="\n- The file under edit (<Order89FileContent>) is the sole source of truth for coding\n"
            "  conventions. Match its documentation style, not the style of context files."
        ),
    })

    # ---- F: Combined — structure-view framing + sole authority rule ----
    variants.append({
        "name": "F_combined",
        "description": "Structure-view intro + sole-authority rule combined",
        "prompt": build_structure_prompt(
            "The following are structure-view extractions of referenced files (signatures only,\n"
            "bodies stripped). Use them as an API reference for correct method calls and types.\n"
            "Do not use them as style references.",
            rules_addition="\n- The file under edit (<Order89FileContent>) is the sole source of truth for coding\n"
            "  conventions. Match its documentation style, not the style of context files."
        ),
    })

    # ---- G: Context AFTER the file — change attention proximity ----
    # Instead of context between rules and file, put it after the file
    variants.append({
        "name": "G_context_after",
        "description": "Context section placed AFTER the file content (attention proximity experiment)",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection.

            <Order89Rules>
            - Wrap your code output in a fenced code block using triple backticks with the language identifier.
            - Do NOT add documentation blocks, comments, or type annotations that the surrounding
              code does not already use. Conversely, if the surrounding code includes documentation
              blocks on every function, include one on yours in the same format.
            - Preserve the indentation style, brace placement, and whitespace patterns of the
              surrounding code.
            - Do NOT describe what you are about to do. Do NOT explain your reasoning.
            - Do NOT include any text before or after the fenced code block.
            - If the selection is empty (<Order89UserSelection></Order89UserSelection>),
              output code to insert at that position.
            - The file under edit (<Order89FileContent>) is the sole source of truth for coding
              conventions. Match its documentation style, not the style of context files.
            </Order89Rules>

            Language: {LANGUAGE}
            File: {FILE_PATH}

            <Order89Instruction>
            {USER_INSTRUCTION}
            </Order89Instruction>

            <Order89FileContent>
            {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
            </Order89FileContent>

            <Order89Context>
            API signatures from referenced files (for correct method calls only, not style):

            {CONTEXT_FILES_BLOCK}
            </Order89Context>
            </Order89Prompt>"""),
    })

    # ---- H: Minimal context disclaimer — one-liner in the intro ----
    variants.append({
        "name": "H_oneliner_disclaim",
        "description": "One-liner disclaimer: 'For API reference only, not style'",
        "prompt": build_structure_prompt(
            "API reference (signatures only, not style examples):"
        ),
    })

    # ---- I: Strong negative — "Do NOT look at context files for conventions" ----
    variants.append({
        "name": "I_strong_negative",
        "description": "Strong negative: 'Do NOT use context files for coding conventions'",
        "prompt": build_structure_prompt(
            "The following are API signatures from referenced files, provided for method call\n"
            "accuracy only.",
            rules_addition="\n- Do NOT look at the context files (<Order89Context>) for coding conventions.\n"
            "  Do NOT imitate their formatting, documentation style, or lack of documentation.\n"
            "  The context files have been machine-stripped of documentation — they are NOT\n"
            "  representative of the project's actual style.\n"
            "- ONLY use the file under edit (<Order89FileContent>) to determine coding conventions.\n"
            "  If the file under edit has documentation blocks on every function, you MUST include\n"
            "  a documentation block on your generated code in the same format."
        ),
    })

    return variants


# ---------------------------------------------------------------------------
# Scoring (identical to harness 57)
# ---------------------------------------------------------------------------

BACKTICK_FENCE = re.compile(r"```[\w]*\n([\s\S]*?)\n\s*```")

def extract_code(raw: str) -> tuple[str, bool]:
    m = BACKTICK_FENCE.search(raw)
    if m:
        return m.group(1), True
    return raw.strip(), False

def has_docblock(code: str) -> bool:
    return bool(re.search(r'/\*\*[\s\S]*?\*/', code))

def has_param_tags(code: str) -> bool:
    return '@param' in code

def has_return_tag(code: str) -> bool:
    return '@return' in code

def has_throws_tag(code: str) -> bool:
    return '@throws' in code

def has_visibility(code: str) -> bool:
    return bool(re.search(r'\b(public|protected|private)\s+function\b', code))

def correct_indentation(code: str) -> bool:
    for line in code.split('\n'):
        if line.strip() == '':
            continue
        leading = len(line) - len(line.lstrip())
        if leading > 0 and leading % 4 != 0:
            return False
    return True

def uses_resolver_pattern(code: str) -> bool:
    return '$this->resolver' in code or '$this->builders' in code

def calls_real_method(code: str) -> bool:
    real_methods = ['resolve(', 'resolveWithArgs(', 'cloneBuilder(',
                    'getBuilder(', 'isBound(', 'bind(', 'singleton(']
    return any(m in code for m in real_methods)

def has_correct_signature(code: str) -> bool:
    return bool(re.search(r'function\s+makeWith\s*\(\s*\$\w+\s*,\s*array\s+\$\w+', code))

def has_try_catch(code: str) -> bool:
    return 'try {' in code or 'try{' in code

def has_prose(text: str) -> bool:
    prose_patterns = [r"^Here('s| is)", r"^This (method|function|code)",
                      r"^I('ve| have| will)", r"^The (method|function)",
                      r"^Below is", r"^Note:", r"^Let me"]
    for line in text.strip().split("\n")[:3]:
        for p in prose_patterns:
            if re.match(p, line.strip(), re.IGNORECASE):
                return True
    return False

def looks_like_php(text: str) -> bool:
    indicators = ["function ", "public ", "$this->", "return ", "/**", "@param", "array", "->"]
    return sum(1 for ind in indicators if ind in text) >= 2

def calls_resolve_with_args(code: str) -> bool:
    """Specifically checks for resolveWithArgs — the correct method for makeWith."""
    return 'resolveWithArgs(' in code


@dataclass
class Score:
    fenced: bool
    is_php: bool
    prose: bool
    has_docblock: bool
    has_param: bool
    has_return: bool
    has_throws: bool
    correct_indent: bool
    uses_resolver: bool
    has_visibility: bool
    calls_real: bool
    correct_sig: bool
    has_try_catch: bool
    calls_rwa: bool  # resolveWithArgs specifically
    code_length: int
    prompt_length: int
    duration: float
    raw: str
    code: str

    @property
    def convention_score(self) -> float:
        """Convention matching: max 9.5"""
        s = 0.0
        if self.fenced: s += 1.0
        if self.is_php: s += 0.5
        if not self.prose: s += 1.0
        if self.has_docblock: s += 2.0
        if self.has_param: s += 1.0
        if self.has_return: s += 1.0
        if self.has_throws: s += 0.5
        if self.has_visibility: s += 1.0
        if self.correct_indent: s += 1.0
        if self.uses_resolver: s += 1.0
        return s

    @property
    def api_accuracy_score(self) -> float:
        """API accuracy: max 4.0"""
        s = 0.0
        if self.calls_real: s += 1.0
        if self.correct_sig: s += 1.0
        if self.has_try_catch: s += 1.0
        if self.calls_rwa: s += 1.0  # bonus for calling the right method
        return s

    @property
    def total_score(self) -> float:
        """Combined: max 13.5"""
        return self.convention_score + self.api_accuracy_score


# ---------------------------------------------------------------------------
# Completion
# ---------------------------------------------------------------------------

def complete(prompt: str) -> str | None:
    payload = json.dumps({
        "prompt": prompt,
        "n_predict": MAX_TOKENS,
        "temperature": 0.7,
        "top_p": 0.8,
        "top_k": 20,
        "repeat_penalty": 1.05,
        "stop": ["</Order89Prompt>", "\n\n\n\n"],
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
    all_scores: dict[str, list[Score]] = {}

    print(f"\nTesting {len(variants)} variants, {runs} run(s) each.")
    print(f"Target: match no-context conventions (docblock, @param, @return) + structure API accuracy (resolveWithArgs, try/catch)")
    print("=" * 120)

    for vi, v in enumerate(variants):
        name = v["name"]
        prompt = v["prompt"]
        prompt_len = len(prompt)
        print(f"\n[{vi+1}/{len(variants)}] {name} (prompt: {prompt_len:,} chars)")
        print(f"  {v['description']}")

        scores = []
        for ri in range(runs):
            print(f"  Run {ri+1}/{runs}...", end=" ", flush=True)
            t0 = time.time()
            raw = complete(prompt)
            duration = time.time() - t0

            if raw is None:
                print(f"FAILED ({duration:.1f}s)")
                continue

            code, fenced = extract_code(raw)

            s = Score(
                fenced=fenced,
                is_php=looks_like_php(code),
                prose=has_prose(raw),
                has_docblock=has_docblock(code),
                has_param=has_param_tags(code),
                has_return=has_return_tag(code),
                has_throws=has_throws_tag(code),
                correct_indent=correct_indentation(code),
                uses_resolver=uses_resolver_pattern(code),
                has_visibility=has_visibility(code),
                calls_real=calls_real_method(code),
                correct_sig=has_correct_signature(code),
                has_try_catch=has_try_catch(code),
                calls_rwa=calls_resolve_with_args(code),
                code_length=len(code),
                prompt_length=prompt_len,
                duration=duration,
                raw=raw,
                code=code,
            )
            scores.append(s)

            flags = [
                f"fence={'Y' if s.fenced else 'N'}",
                f"doc={'Y' if s.has_docblock else 'N'}",
                f"@p={'Y' if s.has_param else 'N'}",
                f"@r={'Y' if s.has_return else 'N'}",
                f"try={'Y' if s.has_try_catch else 'N'}",
                f"rwa={'Y' if s.calls_rwa else 'N'}",
                f"conv={s.convention_score:.1f}",
                f"api={s.api_accuracy_score:.1f}",
                f"tot={s.total_score:.1f}/13.5",
                f"{duration:.1f}s",
            ]
            print(" | ".join(flags))

        all_scores[name] = scores

    # ---- Summary ----
    print("\n" + "=" * 120)
    print("SUMMARY — sorted by total score")
    print("=" * 120)

    header = (f"{'Variant':<22} {'Total':>6} {'Conv':>5} {'API':>4} "
              f"{'Fence%':>7} {'Doc%':>5} {'@par%':>6} {'@ret%':>6} {'@thr%':>6} "
              f"{'Try%':>5} {'RWA%':>5} "
              f"{'Prompt':>8} {'AvgLen':>7} {'Avg(s)':>7}")
    print(header)
    print("-" * len(header))

    summary = []
    for name, scores in all_scores.items():
        if not scores:
            continue
        n = len(scores)
        avg_total = sum(s.total_score for s in scores) / n
        avg_conv = sum(s.convention_score for s in scores) / n
        avg_api = sum(s.api_accuracy_score for s in scores) / n
        fence_pct = sum(1 for s in scores if s.fenced) / n
        doc_pct = sum(1 for s in scores if s.has_docblock) / n
        param_pct = sum(1 for s in scores if s.has_param) / n
        ret_pct = sum(1 for s in scores if s.has_return) / n
        throws_pct = sum(1 for s in scores if s.has_throws) / n
        try_pct = sum(1 for s in scores if s.has_try_catch) / n
        rwa_pct = sum(1 for s in scores if s.calls_rwa) / n
        prompt_len = scores[0].prompt_length
        avg_len = sum(s.code_length for s in scores) / n
        avg_dur = sum(s.duration for s in scores) / n
        summary.append((name, avg_total, avg_conv, avg_api, fence_pct, doc_pct,
                         param_pct, ret_pct, throws_pct, try_pct, rwa_pct,
                         prompt_len, avg_len, avg_dur))

    summary.sort(key=lambda x: x[1], reverse=True)

    for row in summary:
        (name, avg_total, avg_conv, avg_api, fence_pct, doc_pct,
         param_pct, ret_pct, throws_pct, try_pct, rwa_pct,
         prompt_len, avg_len, avg_dur) = row
        print(f"{name:<22} {avg_total:>5.1f} {avg_conv:>5.1f} {avg_api:>4.1f} "
              f"{fence_pct*100:>6.0f}% {doc_pct*100:>4.0f}% {param_pct*100:>5.0f}% "
              f"{ret_pct*100:>5.0f}% {throws_pct*100:>5.0f}% "
              f"{try_pct*100:>4.0f}% {rwa_pct*100:>4.0f}% "
              f"{prompt_len:>7,} {avg_len:>7.0f} {avg_dur:>6.1f}")

    # ---- Sample outputs from top variants ----
    print("\n" + "=" * 120)
    print("SAMPLE OUTPUTS (first run from top 4 variants)")
    print("=" * 120)

    for name, *_ in summary[:4]:
        scores = all_scores[name]
        if not scores:
            continue
        s = scores[0]
        print(f"\n--- {name} (total={s.total_score:.1f}/13.5, conv={s.convention_score:.1f}, api={s.api_accuracy_score:.1f}) ---")
        print(f"Extracted code ({s.code_length} chars):")
        for line in s.code[:1000].split("\n"):
            print(f"  | {line}")
        if s.code_length > 1000:
            print("  | ... (truncated)")

    # ---- Winner analysis ----
    winner = summary[0]
    baseline_no_ctx = next((r for r in summary if r[0] == "A_no_context"), None)
    baseline_struct = next((r for r in summary if r[0] == "B_struct_baseline"), None)

    print(f"\n{'=' * 120}")
    print(f"WINNER: {winner[0]} (avg total {winner[1]:.1f}/13.5)")
    print(f"{'=' * 120}")

    if baseline_no_ctx and baseline_struct:
        print(f"\nComparison to baselines:")
        print(f"  vs A_no_context:      total {winner[1]:.1f} vs {baseline_no_ctx[1]:.1f} "
              f"(conv {winner[2]:.1f} vs {baseline_no_ctx[2]:.1f}, api {winner[3]:.1f} vs {baseline_no_ctx[3]:.1f})")
        print(f"  vs B_struct_baseline: total {winner[1]:.1f} vs {baseline_struct[1]:.1f} "
              f"(conv {winner[2]:.1f} vs {baseline_struct[2]:.1f}, api {winner[3]:.1f} vs {baseline_struct[3]:.1f})")

        if winner[1] > baseline_no_ctx[1] and winner[1] > baseline_struct[1]:
            print(f"\n  SUCCESS: {winner[0]} beats both baselines!")
        elif winner[0] == "A_no_context":
            print(f"\n  No structure variant beat the no-context baseline on total score.")
            # Find best structure variant
            best_struct = next((r for r in summary if r[0] != "A_no_context"), None)
            if best_struct:
                print(f"  Best structure variant: {best_struct[0]} ({best_struct[1]:.1f}/13.5)")
                print(f"    Convention gap: {baseline_no_ctx[2]:.1f} - {best_struct[2]:.1f} = {baseline_no_ctx[2] - best_struct[2]:.1f}")
                print(f"    API advantage:  {best_struct[3]:.1f} - {baseline_no_ctx[3]:.1f} = {best_struct[3] - baseline_no_ctx[3]:.1f}")


if __name__ == "__main__":
    main()
