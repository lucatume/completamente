#!/usr/bin/env python3
"""
Harness: Refine the D (structure_view) and E (sole_authority) prompt variants.

These two tied at 12.3/13.5 in harness 63 with 80% docblocks, 100% API accuracy.
Goal: push docblock consistency above 80% while keeping API accuracy at 100%.

Uses recommended Qwen3-Coder params: temp=0.7, top_p=0.8, top_k=20, repeat_penalty=1.05

Variants:
  D0. D baseline (reproduction)
  E0. E baseline (reproduction)
  D1. D with repetition x2 (context disclaimer repeated in rules)
  D2. D with repetition x3 (context disclaimer in intro, rules, and before file)
  E1. E with repetition x2 (sole authority rule stated twice)
  E2. E with repetition x3 (sole authority in intro, rules, and reminder before file)
  DE. D intro + E rule combined (the original F from harness 59, for reference)
  DE1. D intro + E rule, both repeated
  N.  Neighbor-anchored: "look at the functions immediately above and below the cursor"
  R.  Reminder right before file content (style rule as final nudge)

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
# Structure-extracted context
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


def format_context_files(files: dict[str, str]) -> str:
    parts = []
    for path, content in files.items():
        parts.append(f"<Order89ContextFile path=\"{path}\">\n{content.strip()}\n</Order89ContextFile>")
    return "\n\n".join(parts)


CONTEXT_FILES_BLOCK = format_context_files(STRUCTURE_CONTEXT)

# ---------------------------------------------------------------------------
# Shared prompt pieces
# ---------------------------------------------------------------------------

D_INTRO = ("The following are structure-view extractions of referenced files. They show only\n"
           "class/method signatures with bodies removed. These are machine-generated summaries,\n"
           "not the actual source code — do not use them as style references.")

E_RULE = ("\n- The file under edit (<Order89FileContent>) is the sole source of truth for coding\n"
          "  conventions. Match its documentation style, not the style of context files.")

BASE_RULES = textwrap.dedent("""\
    - Wrap your code output in a fenced code block using triple backticks with the language identifier.
    - Do NOT add documentation blocks, comments, or type annotations that the surrounding
      code does not already use. Conversely, if the surrounding code includes documentation
      blocks on every function, include one on yours in the same format.
    - Preserve the indentation style, brace placement, and whitespace patterns of the
      surrounding code.
    - Do NOT describe what you are about to do. Do NOT explain your reasoning.
    - Do NOT include any text before or after the fenced code block.
    - If the selection is empty (<Order89UserSelection></Order89UserSelection>),
      output code to insert at that position.""")


def build_prompt(rules_text: str, context_intro: str, pre_file_reminder: str = "") -> str:
    """Build prompt with full control over rules, context intro, and pre-file reminder."""
    reminder_block = ""
    if pre_file_reminder:
        reminder_block = f"\n{pre_file_reminder}\n"

    return textwrap.dedent(f"""\
        <Order89Prompt>
        You are a code transformation tool. You receive a file with a marked selection and an instruction.
        You output ONLY the code that replaces the selection.

        <Order89Rules>
        {rules_text}
        </Order89Rules>

        <Order89Context>
        {context_intro}

        {CONTEXT_FILES_BLOCK}
        </Order89Context>

        Language: {LANGUAGE}
        File: {FILE_PATH}

        <Order89Instruction>
        {USER_INSTRUCTION}
        </Order89Instruction>
        {reminder_block}
        <Order89FileContent>
        {BEFORE_CURSOR}<Order89UserSelection></Order89UserSelection>{AFTER_CURSOR}
        </Order89FileContent>
        </Order89Prompt>""")


def build_variants():
    variants = []

    # ---- D0: D baseline (structure_view) ----
    variants.append({
        "name": "D0_baseline",
        "description": "D baseline: structure-view intro, no extra rules",
        "prompt": build_prompt(BASE_RULES, D_INTRO),
    })

    # ---- E0: E baseline (sole_authority) ----
    e0_intro = ("The following files are referenced by the file under edit. Use them to understand\n"
                "the APIs and types available, so your generated code calls real methods with correct signatures.")
    variants.append({
        "name": "E0_baseline",
        "description": "E baseline: plain intro + sole-authority rule",
        "prompt": build_prompt(BASE_RULES + E_RULE, e0_intro),
    })

    # ---- D1: D with repetition x2 — context disclaimer repeated in rules ----
    d1_rules = BASE_RULES + ("\n- The context files (<Order89Context>) are machine-generated structure views.\n"
                             "  Do not use them as style references.")
    variants.append({
        "name": "D1_repeat_x2",
        "description": "D intro + same disclaimer repeated as a rule (x2)",
        "prompt": build_prompt(d1_rules, D_INTRO),
    })

    # ---- D2: D with repetition x3 — intro, rules, AND pre-file reminder ----
    d2_reminder = ("REMINDER: The context files are structure views, not style examples.\n"
                   "Match the style of the file below, not the context files.")
    variants.append({
        "name": "D2_repeat_x3",
        "description": "D disclaimer in intro + rules + pre-file reminder (x3)",
        "prompt": build_prompt(d1_rules, D_INTRO, d2_reminder),
    })

    # ---- E1: E with repetition x2 — sole authority stated twice ----
    e1_rules = BASE_RULES + E_RULE + ("\n- Again: coding conventions come ONLY from the file under edit.\n"
                                       "  The context files have been stripped of documentation and are not style references.")
    variants.append({
        "name": "E1_repeat_x2",
        "description": "E sole-authority rule stated twice with reinforcement (x2)",
        "prompt": build_prompt(e1_rules, e0_intro),
    })

    # ---- E2: E with repetition x3 — intro, rules x2, AND pre-file reminder ----
    e2_intro = ("The following files are referenced by the file under edit. Use them ONLY for\n"
                "API signatures and method names. They are NOT style references.")
    e2_reminder = ("REMINDER: The file under edit is the sole source of truth for coding conventions.\n"
                   "If it has documentation blocks on every function, include one on yours.")
    variants.append({
        "name": "E2_repeat_x3",
        "description": "E sole-authority in intro + rules x2 + pre-file reminder (x3)",
        "prompt": build_prompt(e1_rules, e2_intro, e2_reminder),
    })

    # ---- DE: D intro + E rule combined (the F from harness 59) ----
    variants.append({
        "name": "DE_combined",
        "description": "D structure-view intro + E sole-authority rule (F from harness 59)",
        "prompt": build_prompt(BASE_RULES + E_RULE, D_INTRO),
    })

    # ---- DE1: D intro + E rule, both repeated ----
    de1_rules = BASE_RULES + E_RULE + ("\n- The context files are machine-generated structure views, not style references.\n"
                                        "  Coding conventions come ONLY from the file under edit.")
    de1_reminder = ("REMINDER: Match the documentation style of the file under edit, not the context files.")
    variants.append({
        "name": "DE1_both_repeat",
        "description": "D intro + E rule, both repeated in rules + pre-file reminder",
        "prompt": build_prompt(de1_rules, D_INTRO, de1_reminder),
    })

    # ---- N: Neighbor-anchored — explicitly reference adjacent functions ----
    n_rules = BASE_RULES + ("\n- Study the functions immediately above and below the insertion point.\n"
                            "  Your generated code must match their documentation style exactly —\n"
                            "  if they have documentation blocks, include one; if they don't, don't.\n"
                            "  The context files are API references only, not style guides.")
    variants.append({
        "name": "N_neighbor",
        "description": "Neighbor-anchored: 'study the functions above and below the cursor'",
        "prompt": build_prompt(n_rules, D_INTRO),
    })

    # ---- R: Style rule as final nudge right before the file ----
    r_reminder = ("<Order89StyleReminder>\n"
                  "IMPORTANT: Before generating code, look at the documentation style of the\n"
                  "functions in the file below. If every function has a documentation block,\n"
                  "your generated code MUST also have one in the same format.\n"
                  "The context files above are API references only — ignore their style.\n"
                  "</Order89StyleReminder>")
    variants.append({
        "name": "R_final_nudge",
        "description": "Style reminder in XML tag right before <Order89FileContent>",
        "prompt": build_prompt(BASE_RULES, D_INTRO, r_reminder),
    })

    return variants


# ---------------------------------------------------------------------------
# Scoring (same as harness 59)
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
    calls_rwa: bool
    code_length: int
    prompt_length: int
    duration: float
    raw: str
    code: str

    @property
    def convention_score(self) -> float:
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
        s = 0.0
        if self.calls_real: s += 1.0
        if self.correct_sig: s += 1.0
        if self.has_try_catch: s += 1.0
        if self.calls_rwa: s += 1.0
        return s

    @property
    def total_score(self) -> float:
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

    print(f"\nParams: temp=0.7, top_p=0.8, top_k=20, repeat_penalty=1.05")
    print(f"Testing {len(variants)} variants, {runs} run(s) each.")
    print(f"Target: >80% docblocks + 100% API accuracy (resolveWithArgs + try/catch)")
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
                f"tot={s.total_score:.1f}/13.5",
                f"{duration:.1f}s",
            ]
            print(" | ".join(flags))

        all_scores[name] = scores

    # ---- Summary ----
    print("\n" + "=" * 120)
    print("SUMMARY — sorted by total score")
    print("=" * 120)

    header = (f"{'Variant':<20} {'Total':>6} {'Conv':>5} {'API':>4} "
              f"{'Fence%':>7} {'Doc%':>5} {'@par%':>6} {'@ret%':>6} "
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
        try_pct = sum(1 for s in scores if s.has_try_catch) / n
        rwa_pct = sum(1 for s in scores if s.calls_rwa) / n
        prompt_len = scores[0].prompt_length
        avg_len = sum(s.code_length for s in scores) / n
        avg_dur = sum(s.duration for s in scores) / n
        summary.append((name, avg_total, avg_conv, avg_api, fence_pct, doc_pct,
                         param_pct, ret_pct, try_pct, rwa_pct,
                         prompt_len, avg_len, avg_dur))

    summary.sort(key=lambda x: x[1], reverse=True)

    for row in summary:
        (name, avg_total, avg_conv, avg_api, fence_pct, doc_pct,
         param_pct, ret_pct, try_pct, rwa_pct,
         prompt_len, avg_len, avg_dur) = row
        print(f"{name:<20} {avg_total:>5.1f} {avg_conv:>5.1f} {avg_api:>4.1f} "
              f"{fence_pct*100:>6.0f}% {doc_pct*100:>4.0f}% {param_pct*100:>5.0f}% "
              f"{ret_pct*100:>5.0f}% "
              f"{try_pct*100:>4.0f}% {rwa_pct*100:>4.0f}% "
              f"{prompt_len:>7,} {avg_len:>7.0f} {avg_dur:>6.1f}")

    # ---- Sample outputs from top 3 ----
    print("\n" + "=" * 120)
    print("SAMPLE OUTPUTS (first run from top 3 variants)")
    print("=" * 120)

    for name, *_ in summary[:3]:
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

    # ---- Winner ----
    winner = summary[0]
    d0 = next((r for r in summary if r[0] == "D0_baseline"), None)
    e0 = next((r for r in summary if r[0] == "E0_baseline"), None)

    print(f"\n{'=' * 120}")
    print(f"WINNER: {winner[0]} (avg total {winner[1]:.1f}/13.5, doc={winner[5]*100:.0f}%)")
    print(f"{'=' * 120}")
    if d0 and e0:
        print(f"\nBaseline comparison:")
        print(f"  D0 baseline: total={d0[1]:.1f}, doc={d0[5]*100:.0f}%")
        print(f"  E0 baseline: total={e0[1]:.1f}, doc={e0[5]*100:.0f}%")
        print(f"  Winner:      total={winner[1]:.1f}, doc={winner[5]*100:.0f}% ({'+' if winner[5] > max(d0[5], e0[5]) else ''}{(winner[5] - max(d0[5], e0[5]))*100:.0f}pp vs best baseline)")


if __name__ == "__main__":
    main()
