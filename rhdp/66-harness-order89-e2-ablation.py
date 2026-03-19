#!/usr/bin/env python3
"""
Harness: Ablation study on the E2_repeat_x3 winner.

E2_repeat_x3 scored a perfect 13.0/13.5 with the "sole source of truth" message
placed in 4 spots: rules (x2), context intro (x1), pre-file reminder (x1).

Question: is it repetition or position that matters? What is the minimum guardrail
text needed to maintain perfect scores?

Ablation matrix:
  - Single placements: rules-only, intro-only, reminder-only
  - Pairs: rules+intro, rules+reminder, intro+reminder
  - Full E2 (baseline reproduction)
  - Terse versions: same positions but with shorter text

Uses: temp=0.7, top_p=0.8, top_k=20, repeat_penalty=1.05
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
# File content
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
# Base rules (without any guardrails)
# ---------------------------------------------------------------------------

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

# ---------------------------------------------------------------------------
# Guardrail texts — verbose and terse versions
# ---------------------------------------------------------------------------

# Verbose (as used in E2_repeat_x3)
RULES_GUARDRAIL_V = ("\n- The file under edit (<Order89FileContent>) is the sole source of truth for coding\n"
                     "  conventions. Match its documentation style, not the style of context files.\n"
                     "- Again: coding conventions come ONLY from the file under edit.\n"
                     "  The context files have been stripped of documentation and are not style references.")

RULES_GUARDRAIL_V_SINGLE = ("\n- The file under edit (<Order89FileContent>) is the sole source of truth for coding\n"
                            "  conventions. Match its documentation style, not the style of context files.")

INTRO_GUARDRAIL_V = ("The following files are referenced by the file under edit. Use them ONLY for\n"
                     "API signatures and method names. They are NOT style references.")

REMINDER_GUARDRAIL_V = ("REMINDER: The file under edit is the sole source of truth for coding conventions.\n"
                        "If it has documentation blocks on every function, include one on yours.")

# Terse
RULES_GUARDRAIL_T = "\n- Style conventions: match the file under edit only, not context files."

INTRO_GUARDRAIL_T = "API signatures only (not style references):"

REMINDER_GUARDRAIL_T = "REMINDER: Match the file's documentation style."

# Neutral intro (no guardrail)
INTRO_NEUTRAL = ("The following files are referenced by the file under edit. Use them to understand\n"
                 "the APIs and types available, so your generated code calls real methods with correct signatures.")


# ---------------------------------------------------------------------------
# Prompt builder
# ---------------------------------------------------------------------------

def build_prompt(rules_addition: str, context_intro: str, pre_file_reminder: str = "") -> str:
    reminder_block = ""
    if pre_file_reminder:
        reminder_block = f"\n{pre_file_reminder}\n"

    return textwrap.dedent(f"""\
        <Order89Prompt>
        You are a code transformation tool. You receive a file with a marked selection and an instruction.
        You output ONLY the code that replaces the selection.

        <Order89Rules>
        {BASE_RULES}{rules_addition}
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

    # ========== BASELINE ==========

    # E2 full reproduction (the winner)
    variants.append({
        "name": "E2_full",
        "description": "E2_repeat_x3 reproduction: rules(x2) + intro + reminder [BASELINE]",
        "prompt": build_prompt(RULES_GUARDRAIL_V, INTRO_GUARDRAIL_V, REMINDER_GUARDRAIL_V),
        "positions": "R2+I+M",
    })

    # ========== SINGLE PLACEMENTS ==========

    # Rules only (single statement)
    variants.append({
        "name": "rules_only",
        "description": "Guardrail in rules only (single statement)",
        "prompt": build_prompt(RULES_GUARDRAIL_V_SINGLE, INTRO_NEUTRAL, ""),
        "positions": "R1",
    })

    # Intro only
    variants.append({
        "name": "intro_only",
        "description": "Guardrail in context intro only",
        "prompt": build_prompt("", INTRO_GUARDRAIL_V, ""),
        "positions": "I",
    })

    # Reminder only
    variants.append({
        "name": "reminder_only",
        "description": "Guardrail in pre-file reminder only",
        "prompt": build_prompt("", INTRO_NEUTRAL, REMINDER_GUARDRAIL_V),
        "positions": "M",
    })

    # ========== PAIRS ==========

    # Rules + intro
    variants.append({
        "name": "rules_intro",
        "description": "Guardrail in rules (single) + context intro",
        "prompt": build_prompt(RULES_GUARDRAIL_V_SINGLE, INTRO_GUARDRAIL_V, ""),
        "positions": "R1+I",
    })

    # Rules + reminder
    variants.append({
        "name": "rules_reminder",
        "description": "Guardrail in rules (single) + pre-file reminder",
        "prompt": build_prompt(RULES_GUARDRAIL_V_SINGLE, INTRO_NEUTRAL, REMINDER_GUARDRAIL_V),
        "positions": "R1+M",
    })

    # Intro + reminder
    variants.append({
        "name": "intro_reminder",
        "description": "Guardrail in context intro + pre-file reminder",
        "prompt": build_prompt("", INTRO_GUARDRAIL_V, REMINDER_GUARDRAIL_V),
        "positions": "I+M",
    })

    # ========== TRIPLE (without double-rule) ==========

    # Rules(x1) + intro + reminder
    variants.append({
        "name": "triple_single_rule",
        "description": "Rules (single) + intro + reminder — like E2 but rules stated once",
        "prompt": build_prompt(RULES_GUARDRAIL_V_SINGLE, INTRO_GUARDRAIL_V, REMINDER_GUARDRAIL_V),
        "positions": "R1+I+M",
    })

    # ========== TERSE VERSIONS ==========

    # Terse single: reminder only (shortest possible)
    variants.append({
        "name": "terse_reminder",
        "description": "Terse guardrail in pre-file reminder only (minimal tokens)",
        "prompt": build_prompt("", INTRO_NEUTRAL, REMINDER_GUARDRAIL_T),
        "positions": "Mt",
    })

    # Terse pair: rules + reminder
    variants.append({
        "name": "terse_rules_reminder",
        "description": "Terse guardrail in rules + pre-file reminder",
        "prompt": build_prompt(RULES_GUARDRAIL_T, INTRO_NEUTRAL, REMINDER_GUARDRAIL_T),
        "positions": "Rt+Mt",
    })

    # Terse triple: all three positions
    variants.append({
        "name": "terse_triple",
        "description": "Terse guardrail in all three positions (rules + intro + reminder)",
        "prompt": build_prompt(RULES_GUARDRAIL_T, INTRO_GUARDRAIL_T, REMINDER_GUARDRAIL_T),
        "positions": "Rt+It+Mt",
    })

    # Terse pair: intro + reminder
    variants.append({
        "name": "terse_intro_reminder",
        "description": "Terse guardrail in context intro + pre-file reminder",
        "prompt": build_prompt("", INTRO_GUARDRAIL_T, REMINDER_GUARDRAIL_T),
        "positions": "It+Mt",
    })

    return variants


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

BACKTICK_FENCE = re.compile(r"```[\w]*\n([\s\S]*?)\n\s*```")

def extract_code(raw): m = BACKTICK_FENCE.search(raw); return (m.group(1), True) if m else (raw.strip(), False)
def has_docblock(c): return bool(re.search(r'/\*\*[\s\S]*?\*/', c))
def has_param_tags(c): return '@param' in c
def has_return_tag(c): return '@return' in c
def has_throws_tag(c): return '@throws' in c
def has_visibility(c): return bool(re.search(r'\b(public|protected|private)\s+function\b', c))
def correct_indentation(c):
    for l in c.split('\n'):
        if l.strip() and (len(l) - len(l.lstrip())) % 4 != 0 and (len(l) - len(l.lstrip())) > 0: return False
    return True
def uses_resolver(c): return '$this->resolver' in c or '$this->builders' in c
def calls_real(c): return any(m in c for m in ['resolve(', 'resolveWithArgs(', 'getBuilder('])
def correct_sig(c): return bool(re.search(r'function\s+makeWith\s*\(\s*\$\w+\s*,\s*array\s+\$\w+', c))
def has_try_catch(c): return 'try {' in c or 'try{' in c
def calls_rwa(c): return 'resolveWithArgs(' in c
def has_prose(t):
    for l in t.strip().split("\n")[:3]:
        for p in [r"^Here('s| is)", r"^This (method|function)", r"^I('ve| have)", r"^Below", r"^Note:", r"^Let me"]:
            if re.match(p, l.strip(), re.IGNORECASE): return True
    return False
def looks_like_php(t): return sum(1 for i in ["function ", "public ", "$this->", "return ", "/**", "@param"] if i in t) >= 2


@dataclass
class Score:
    fenced: bool; is_php: bool; prose: bool
    has_docblock: bool; has_param: bool; has_return: bool; has_throws: bool
    correct_indent: bool; uses_resolver: bool; has_visibility: bool
    calls_real: bool; correct_sig: bool; has_try_catch: bool; calls_rwa: bool
    code_length: int; prompt_length: int; guardrail_chars: int
    duration: float; raw: str; code: str

    @property
    def convention_score(self):
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
    def api_accuracy_score(self):
        s = 0.0
        if self.calls_real: s += 1.0
        if self.correct_sig: s += 1.0
        if self.has_try_catch: s += 1.0
        if self.calls_rwa: s += 1.0
        return s

    @property
    def total_score(self):
        return self.convention_score + self.api_accuracy_score


def complete(prompt):
    payload = json.dumps({
        "prompt": prompt, "n_predict": MAX_TOKENS,
        "temperature": 0.7, "top_p": 0.8, "top_k": 20, "repeat_penalty": 1.05,
        "stop": ["</Order89Prompt>", "\n\n\n\n"], "cache_prompt": False,
    }).encode()
    req = urllib.request.Request(COMPLETION_ENDPOINT, data=payload,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode()).get("content", "")
    except Exception as e:
        print(f"  ERROR: {e}", file=sys.stderr)
        return None


def count_guardrail_chars(prompt: str, base_prompt: str) -> int:
    """Approximate guardrail overhead by comparing to a hypothetical no-guardrail prompt."""
    return len(prompt) - len(base_prompt)


def main():
    runs = int(sys.argv[1]) if len(sys.argv) > 1 else RUNS_PER_VARIANT

    print(f"Checking llama.cpp server at {SERVER_URL}...")
    try:
        with urllib.request.urlopen(f"{SERVER_URL}/health", timeout=5) as resp:
            print(f"  Server status: {json.loads(resp.read().decode()).get('status', '?')}")
    except Exception as e:
        print(f"  ERROR: {e}"); sys.exit(1)

    variants = build_variants()
    # Build a no-guardrail baseline for measuring overhead
    no_guardrail_prompt = build_prompt("", INTRO_NEUTRAL, "")
    no_guardrail_len = len(no_guardrail_prompt)

    all_scores: dict[str, list[Score]] = {}

    print(f"\nAblation study: {len(variants)} variants, {runs} run(s) each.")
    print(f"Positions: R=rules, I=intro, M=reminder (pre-file), t=terse, 1/2=count")
    print("=" * 130)

    for vi, v in enumerate(variants):
        name = v["name"]
        prompt = v["prompt"]
        prompt_len = len(prompt)
        guardrail_chars = prompt_len - no_guardrail_len
        positions = v["positions"]
        print(f"\n[{vi+1}/{len(variants)}] {name} [{positions}] (guardrail: {guardrail_chars} chars)")
        print(f"  {v['description']}")

        scores = []
        for ri in range(runs):
            print(f"  Run {ri+1}/{runs}...", end=" ", flush=True)
            t0 = time.time()
            raw = complete(prompt)
            duration = time.time() - t0

            if raw is None:
                print(f"FAILED ({duration:.1f}s)"); continue

            code, fenced = extract_code(raw)
            s = Score(
                fenced=fenced, is_php=looks_like_php(code), prose=has_prose(raw),
                has_docblock=has_docblock(code), has_param=has_param_tags(code),
                has_return=has_return_tag(code), has_throws=has_throws_tag(code),
                correct_indent=correct_indentation(code), uses_resolver=uses_resolver(code),
                has_visibility=has_visibility(code), calls_real=calls_real(code),
                correct_sig=correct_sig(code), has_try_catch=has_try_catch(code),
                calls_rwa=calls_rwa(code), code_length=len(code),
                prompt_length=prompt_len, guardrail_chars=guardrail_chars,
                duration=duration, raw=raw, code=code,
            )
            scores.append(s)
            flags = [f"doc={'Y' if s.has_docblock else 'N'}", f"try={'Y' if s.has_try_catch else 'N'}",
                     f"rwa={'Y' if s.calls_rwa else 'N'}", f"tot={s.total_score:.1f}", f"{duration:.1f}s"]
            print(" | ".join(flags))

        all_scores[name] = scores

    # ---- Summary ----
    print("\n" + "=" * 130)
    print("SUMMARY — sorted by total score, then by guardrail chars (ascending)")
    print("=" * 130)

    header = (f"{'Variant':<22} {'Pos':<10} {'Total':>6} {'Doc%':>5} {'Try%':>5} {'RWA%':>5} "
              f"{'Fence%':>7} {'Conv':>5} {'API':>4} "
              f"{'Guard':>6} {'Avg(s)':>7}")
    print(header)
    print("-" * len(header))

    summary = []
    for name, scores in all_scores.items():
        if not scores: continue
        n = len(scores)
        v = next(v for v in variants if v["name"] == name)
        avg_total = sum(s.total_score for s in scores) / n
        avg_conv = sum(s.convention_score for s in scores) / n
        avg_api = sum(s.api_accuracy_score for s in scores) / n
        doc_pct = sum(1 for s in scores if s.has_docblock) / n
        try_pct = sum(1 for s in scores if s.has_try_catch) / n
        rwa_pct = sum(1 for s in scores if s.calls_rwa) / n
        fence_pct = sum(1 for s in scores if s.fenced) / n
        guardrail = scores[0].guardrail_chars
        avg_dur = sum(s.duration for s in scores) / n
        summary.append((name, v["positions"], avg_total, doc_pct, try_pct, rwa_pct,
                         fence_pct, avg_conv, avg_api, guardrail, avg_dur))

    # Sort by total desc, then guardrail chars asc
    summary.sort(key=lambda x: (-x[2], x[9]))

    for row in summary:
        (name, pos, avg_total, doc_pct, try_pct, rwa_pct,
         fence_pct, avg_conv, avg_api, guardrail, avg_dur) = row
        perfect = " ***" if doc_pct == 1.0 and try_pct == 1.0 and rwa_pct == 1.0 and fence_pct == 1.0 else ""
        print(f"{name:<22} {pos:<10} {avg_total:>5.1f} {doc_pct*100:>4.0f}% {try_pct*100:>4.0f}% "
              f"{rwa_pct*100:>4.0f}% {fence_pct*100:>6.0f}% {avg_conv:>5.1f} {avg_api:>4.1f} "
              f"{guardrail:>5} {avg_dur:>6.1f}{perfect}")

    # ---- Efficiency analysis ----
    print("\n" + "=" * 130)
    print("EFFICIENCY — perfect variants sorted by guardrail chars (fewer = better)")
    print("=" * 130)

    perfect_variants = [r for r in summary if r[3] == 1.0 and r[4] == 1.0 and r[5] == 1.0 and r[6] == 1.0]
    if perfect_variants:
        print(f"\n{'Variant':<22} {'Positions':<10} {'Guard chars':>12} {'Avg(s)':>7}")
        print("-" * 55)
        for row in sorted(perfect_variants, key=lambda x: x[9]):
            print(f"{row[0]:<22} {row[1]:<10} {row[9]:>12} {row[10]:>6.1f}")
        best = sorted(perfect_variants, key=lambda x: x[9])[0]
        print(f"\nMost efficient perfect variant: {best[0]} ({best[9]} guardrail chars, positions: {best[1]})")
    else:
        print("\nNo variant achieved a perfect score in this run.")
        top = summary[0]
        print(f"Best: {top[0]} (total={top[2]:.1f}, doc={top[3]*100:.0f}%, guardrail={top[9]} chars)")

    # ---- Position analysis ----
    print("\n" + "=" * 130)
    print("POSITION ANALYSIS — doc% by which positions have a guardrail")
    print("=" * 130)
    print(f"\n{'Has Rules':>10} {'Has Intro':>10} {'Has Remind':>11} {'Doc%':>6} {'Variants'}")
    print("-" * 60)

    for row in summary:
        pos = row[1]
        has_r = 'R' in pos
        has_i = 'I' in pos
        has_m = 'M' in pos
        print(f"{'yes' if has_r else 'no':>10} {'yes' if has_i else 'no':>10} "
              f"{'yes' if has_m else 'no':>11} {row[3]*100:>5.0f}% {row[0]}")


if __name__ == "__main__":
    main()
