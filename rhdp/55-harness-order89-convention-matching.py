#!/usr/bin/env python3
"""
Harness: Test prompt variants for convention-matching quality in Order 89.

All variants use backtick fences (the winner from harnesses 51/53).
The goal is to find which *generic* (language-agnostic) prompt wording best
makes the model match the surrounding code's conventions:

  - Docblock presence and style (the test file has PHPDoc on every method)
  - Indentation level and style
  - Visibility keywords, return types, parameter style
  - Naming conventions

Scoring dimensions:
  1. Has docblock?  (the surrounding code always has one → should match)
  2. Uses @param/@return tags?  (surrounding code does → should match)
  3. Correct indentation?  (4-space, class-member level)
  4. Uses $this-> resolver pattern?  (consistent with sibling methods)
  5. No prose contamination
  6. Parseable from backtick fence

Test scenario: same as harnesses 51/53 — Container.php, empty cursor at
line 180, instruction "implement the makeWith method".

Server: llama.cpp at http://localhost:8012 (/completion endpoint)
"""

import json
import re
import sys
import time
import urllib.request
import urllib.error
import textwrap
from dataclasses import dataclass, field

SERVER_URL = "http://localhost:8012"
COMPLETION_ENDPOINT = f"{SERVER_URL}/completion"
RUNS_PER_VARIANT = 10
MAX_TOKENS = 1024

# ---------------------------------------------------------------------------
# File content (same as harnesses 51/53)
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
# Prompt variants — all use backtick fences, differ in convention-matching
# guidance
# ---------------------------------------------------------------------------

def build_variants():
    variants = []

    # ---- A: Baseline — no convention-matching guidance ----
    variants.append({
        "name": "baseline_no_guidance",
        "description": "Backtick fences, no convention-matching guidance at all",
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

    # ---- B: Generic "match style" one-liner ----
    variants.append({
        "name": "match_style_oneliner",
        "description": "One-liner: 'Match the style and conventions of the surrounding code'",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection.

            <Order89Rules>
            - Wrap your code output in a fenced code block using triple backticks with the language identifier.
            - Match the style and conventions of the surrounding code.
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
            </Order89Prompt>"""),
    })

    # ---- C: Enumerated convention checklist ----
    variants.append({
        "name": "convention_checklist",
        "description": "Explicit checklist: docblocks, comments, types, naming, indentation",
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
            </Order89Rules>

            <Order89StyleGuide>
            Match the conventions of the surrounding code exactly:
            - If surrounding functions have documentation blocks, include one in the same format.
            - If surrounding functions omit documentation blocks, omit yours too.
            - Match the comment style (block comments, inline comments, or none).
            - Match type annotation style (explicit return types, parameter types, or none).
            - Match naming conventions (camelCase, snake_case, etc.).
            - Match indentation (spaces vs tabs, indent width).
            - Match brace placement and whitespace patterns.
            </Order89StyleGuide>

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

    # ---- D: "Blend in" framing ----
    variants.append({
        "name": "blend_in",
        "description": "'Your code should blend in seamlessly' + look at neighbors",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection.

            <Order89Rules>
            - Wrap your code output in a fenced code block using triple backticks with the language identifier.
            - Your code must blend in seamlessly with the surrounding code. A reader should not
              be able to tell that your code was generated — it should look like the same author
              wrote it. Study the functions immediately before and after the insertion point and
              replicate their documentation style, type annotations, naming, and formatting exactly.
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
            </Order89Prompt>"""),
    })

    # ---- E: Negative constraint — "do not add what isn't there" ----
    variants.append({
        "name": "negative_constraint",
        "description": "'Do not add documentation or annotations the surrounding code does not have'",
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

    # ---- F: Role-based — "you are the original author" ----
    variants.append({
        "name": "original_author",
        "description": "'You are the original author of this file, continuing your work'",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are the original author of this file. You are continuing your work by implementing
            a change at the marked position. Your output must be indistinguishable from the rest of
            the file in style, documentation, and formatting.

            <Order89Rules>
            - Wrap your code output in a fenced code block using triple backticks with the language identifier.
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
            </Order89Prompt>"""),
    })

    # ---- G: Mirror-neighbor — explicitly reference the adjacent functions ----
    variants.append({
        "name": "mirror_neighbor",
        "description": "Explicitly say: 'mirror the structure of the function directly above'",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. You receive a file with a marked selection and an instruction.
            You output ONLY the code that replaces the selection.

            <Order89Rules>
            - Wrap your code output in a fenced code block using triple backticks with the language identifier.
            - Mirror the structure of the function directly above the insertion point: if it has a
              documentation block, write one in the same format; if it has type annotations, use them;
              match its indentation, brace style, and whitespace.
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
            </Order89Prompt>"""),
    })

    # ---- H: Compact — minimal rules, heavy reliance on context ----
    variants.append({
        "name": "compact_context_only",
        "description": "Minimal rules: just 'output code matching the file style' + fences",
        "prompt": textwrap.dedent(f"""\
            <Order89Prompt>
            You are a code transformation tool. Output ONLY a fenced code block (triple backticks
            with language identifier) containing the replacement code. No prose. Match the file's
            existing style exactly.

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
# Extraction and scoring
# ---------------------------------------------------------------------------

BACKTICK_FENCE = re.compile(r"```[\w]*\n([\s\S]*?)\n\s*```")


def extract_code(raw: str) -> tuple[str, bool]:
    """Returns (extracted_code, was_fenced)."""
    m = BACKTICK_FENCE.search(raw)
    if m:
        return m.group(1), True
    return raw.strip(), False


def has_docblock(code: str) -> bool:
    """Check if the code contains a /** ... */ docblock."""
    return bool(re.search(r'/\*\*[\s\S]*?\*/', code))


def has_param_tags(code: str) -> bool:
    """Check for @param tags in docblocks."""
    return '@param' in code


def has_return_tag(code: str) -> bool:
    """Check for @return tags in docblocks."""
    return '@return' in code


def has_throws_tag(code: str) -> bool:
    """Check for @throws tags in docblocks."""
    return '@throws' in code


def correct_indentation(code: str) -> bool:
    """Check that non-blank lines use 4-space indentation at class-member level."""
    lines = code.split('\n')
    for line in lines:
        if line.strip() == '':
            continue
        # Class member level = 4 spaces, method body = 8 spaces
        leading = len(line) - len(line.lstrip())
        if leading > 0 and leading % 4 != 0:
            return False
    return True


def uses_resolver_pattern(code: str) -> bool:
    """Check if code uses $this->resolver or $this->builders (consistent with siblings)."""
    return '$this->resolver' in code or '$this->builders' in code


def has_visibility(code: str) -> bool:
    """Check if the function has a visibility keyword (public/protected/private)."""
    return bool(re.search(r'\b(public|protected|private)\s+function\b', code))


def has_prose(text: str) -> bool:
    """Check for conversational prose in the raw output."""
    prose_patterns = [
        r"^Here('s| is)", r"^This (method|function|code)",
        r"^I('ve| have| will)", r"^The (method|function)",
        r"^Below is", r"^Note:", r"^Let me",
    ]
    for line in text.strip().split("\n")[:3]:
        for p in prose_patterns:
            if re.match(p, line.strip(), re.IGNORECASE):
                return True
    return False


def looks_like_php(text: str) -> bool:
    indicators = ["function ", "public ", "$this->", "return ", "/**",
                  "@param", "array", "->"]
    hits = sum(1 for ind in indicators if ind in text)
    return hits >= 2


@dataclass
class ConventionScore:
    """Convention-matching score for a single run."""
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
    code_length: int
    duration: float
    raw: str
    code: str

    @property
    def convention_score(self) -> float:
        """
        Score how well the output matches the conventions of the surrounding code.
        The surrounding code (getVar, offsetGet, etc.) has:
          - /** docblocks on every method (weight 2)
          - @param tags (weight 1)
          - @return tags (weight 1)
          - @throws tags (weight 0.5)
          - public visibility keyword (weight 1)
          - 4-space indentation (weight 1)
          - $this->resolver usage (weight 1)
          - No prose (weight 1)
          - Fenced output (weight 1)
        Total possible: 9.5
        """
        s = 0.0
        if self.fenced:
            s += 1.0
        if self.is_php:
            s += 0.5
        if not self.prose:
            s += 1.0
        if self.has_docblock:
            s += 2.0
        if self.has_param:
            s += 1.0
        if self.has_return:
            s += 1.0
        if self.has_throws:
            s += 0.5
        if self.has_visibility:
            s += 1.0
        if self.correct_indent:
            s += 1.0
        if self.uses_resolver:
            s += 1.0
        return s

    @property
    def max_score(self) -> float:
        return 9.5


# ---------------------------------------------------------------------------
# Completion request
# ---------------------------------------------------------------------------

def complete(prompt: str) -> str | None:
    payload = json.dumps({
        "prompt": prompt,
        "n_predict": MAX_TOKENS,
        "temperature": 0.2,
        "top_p": 0.9,
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
    all_scores: dict[str, list[ConventionScore]] = {}

    print(f"\nTesting {len(variants)} prompt variants, {runs} run(s) each.")
    print(f"Convention target: docblock + @param + @return + @throws + public + 4-space indent + resolver pattern")
    print("=" * 100)

    for vi, v in enumerate(variants):
        name = v["name"]
        print(f"\n[{vi+1}/{len(variants)}] {name}")
        print(f"  {v['description']}")

        scores = []
        for ri in range(runs):
            print(f"  Run {ri+1}/{runs}...", end=" ", flush=True)
            t0 = time.time()
            raw = complete(v["prompt"])
            duration = time.time() - t0

            if raw is None:
                print(f"FAILED ({duration:.1f}s)")
                continue

            code, fenced = extract_code(raw)

            cs = ConventionScore(
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
                code_length=len(code),
                duration=duration,
                raw=raw,
                code=code,
            )
            scores.append(cs)

            flags = []
            flags.append(f"fence={'Y' if cs.fenced else 'N'}")
            flags.append(f"doc={'Y' if cs.has_docblock else 'N'}")
            flags.append(f"@param={'Y' if cs.has_param else 'N'}")
            flags.append(f"@return={'Y' if cs.has_return else 'N'}")
            flags.append(f"@throws={'Y' if cs.has_throws else 'N'}")
            flags.append(f"vis={'Y' if cs.has_visibility else 'N'}")
            flags.append(f"indent={'Y' if cs.correct_indent else 'N'}")
            flags.append(f"resolver={'Y' if cs.uses_resolver else 'N'}")
            flags.append(f"score={cs.convention_score:.1f}/{cs.max_score}")
            flags.append(f"{duration:.1f}s")
            print(" | ".join(flags))

        all_scores[name] = scores

    # ---- Summary table ----
    print("\n" + "=" * 100)
    print("SUMMARY — sorted by average convention score")
    print("=" * 100)

    header = (f"{'Variant':<25} {'Score':>6} {'Fence%':>7} {'Doc%':>5} {'@par%':>6} "
              f"{'@ret%':>6} {'@thr%':>6} {'Vis%':>5} {'Ind%':>5} {'Res%':>5} "
              f"{'Prose%':>7} {'AvgLen':>7} {'Avg(s)':>7}")
    print(header)
    print("-" * len(header))

    summary = []
    for name, scores in all_scores.items():
        if not scores:
            continue
        n = len(scores)
        avg_score = sum(s.convention_score for s in scores) / n
        fence_pct = sum(1 for s in scores if s.fenced) / n
        doc_pct = sum(1 for s in scores if s.has_docblock) / n
        param_pct = sum(1 for s in scores if s.has_param) / n
        ret_pct = sum(1 for s in scores if s.has_return) / n
        throws_pct = sum(1 for s in scores if s.has_throws) / n
        vis_pct = sum(1 for s in scores if s.has_visibility) / n
        ind_pct = sum(1 for s in scores if s.correct_indent) / n
        res_pct = sum(1 for s in scores if s.uses_resolver) / n
        prose_pct = sum(1 for s in scores if s.prose) / n
        avg_len = sum(s.code_length for s in scores) / n
        avg_dur = sum(s.duration for s in scores) / n
        summary.append((name, avg_score, fence_pct, doc_pct, param_pct, ret_pct,
                         throws_pct, vis_pct, ind_pct, res_pct, prose_pct, avg_len, avg_dur))

    summary.sort(key=lambda x: x[1], reverse=True)

    for (name, avg_score, fence_pct, doc_pct, param_pct, ret_pct,
         throws_pct, vis_pct, ind_pct, res_pct, prose_pct, avg_len, avg_dur) in summary:
        print(f"{name:<25} {avg_score:>5.1f} {fence_pct*100:>6.0f}% {doc_pct*100:>4.0f}% "
              f"{param_pct*100:>5.0f}% {ret_pct*100:>5.0f}% {throws_pct*100:>5.0f}% "
              f"{vis_pct*100:>4.0f}% {ind_pct*100:>4.0f}% {res_pct*100:>4.0f}% "
              f"{prose_pct*100:>6.0f}% {avg_len:>7.0f} {avg_dur:>6.1f}")

    # ---- Sample outputs from top 3 ----
    print("\n" + "=" * 100)
    print("SAMPLE OUTPUTS (first run from each variant, sorted by score)")
    print("=" * 100)

    for name, *_ in summary:
        scores = all_scores[name]
        if not scores:
            continue
        s = scores[0]
        print(f"\n--- {name} (score={s.convention_score:.1f}/{s.max_score}) ---")
        print(f"Extracted code ({s.code_length} chars):")
        for line in s.code[:800].split("\n"):
            print(f"  | {line}")
        if s.code_length > 800:
            print("  | ... (truncated)")

    # ---- Winner ----
    winner_name = summary[0][0]
    winner_avg = summary[0][1]
    print(f"\n{'=' * 100}")
    print(f"WINNER: {winner_name} (avg convention score {winner_avg:.1f}/{9.5})")
    print(f"{'=' * 100}")


if __name__ == "__main__":
    main()
