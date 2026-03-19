#!/usr/bin/env python3
"""
Harness: Test how different context types improve Order 89 code generation quality.

Uses the winning prompt format from harnesses 51-56:
  - Backtick fences for output
  - negative_constraint style guide

Context variants:
  A. No context (baseline) — only the file under edit
  B. Full referenced files — complete source of Resolver.php, Factory.php, etc.
  C. Structure-extracted referenced files — signatures only, no method bodies
     (simulates IntelliJ Structure API / surface extraction)
  D. Windowed referenced files — N lines around each relevant symbol definition
     Tested with window sizes: 10, 20, 40, 80 lines

Test scenario: Container.php, cursor at line 180, "implement the makeWith method"

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
# Main file content (same as previous harnesses)
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
# Referenced file contents — full source
# ---------------------------------------------------------------------------

FULL_FILES = {
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
    {
        $this->resolveUnboundAsSingletons = $resolveUnboundAsSingletons;
    }

    public function bind($id, BuilderInterface $implementation)
    {
        unset($this->singletons[$id]);
        $this->bindings[$id] = $implementation;
    }

    public function singleton($id, BuilderInterface $implementation)
    {
        $this->singletons[$id] = true;
        $this->bindings[$id] = $implementation;
    }

    public function isBound($id)
    {
        return isset($this->bindings[$id]);
    }

    public function unbind($id)
    {
        unset($this->bindings[$id], $this->whenNeedsGive[$id], $this->singletons[$id]);
    }

    public function isSingleton($id)
    {
        return isset($this->singletons[$id]);
    }

    public function whenNeedsGive($id, $paramClass)
    {
        return isset($this->whenNeedsGive[$id][$paramClass]) ?
            $this->whenNeedsGive[$id][$paramClass]
            : $paramClass;
    }

    public function setWhenNeedsGive($whenClass, $needsClass, BuilderInterface $builder)
    {
        $this->whenNeedsGive[$whenClass][$needsClass] = $builder;
    }

    public function resolveWithArgs($id, ?array $afterBuildMethods = null, ...$buildArgs)
    {
        if (! is_string($id)) {
            return $id;
        }

        if (empty($afterBuildMethods) && empty($buildArgs)) {
            return $this->resolve($id);
        }
        return $this->cloneBuilder($id, $afterBuildMethods, ...$buildArgs)->build();
    }

    public function resolve($id, ?array $buildLine = null)
    {
        if ($buildLine !== null) {
            $this->buildLine = $buildLine;
        }

        if (! is_string($id)) {
            return $id;
        }

        if (!isset($this->bindings[$id])) {
            return $this->resolveUnbound($id);
        }

        if ($this->bindings[$id] instanceof BuilderInterface) {
            $built = $this->resolveBound($id);
        } else {
            $built = $this->bindings[$id];
        }

        return $built;
    }

    private function resolveUnbound($id)
    {
        $built = (new ClassBuilder($id, $this, $id))->build();

        if ($this->resolveUnboundAsSingletons) {
            $this->singletons[$id] = true;
            $this->bindings[$id] = $built;
        }

        return $built;
    }

    private function resolveBound($id)
    {
        $built = $this->bindings[$id]->build();
        if (isset($this->singletons[$id])) {
            $this->bindings[$id] = $built;
        }
        return $built;
    }

    private function cloneBuilder($id, ?array $afterBuildMethods = null, ...$buildArgs)
    {
        if (isset($this->bindings[$id]) && $this->bindings[$id] instanceof BuilderInterface) {
            $builder = clone $this->bindings[$id];
            if ($builder instanceof ReinitializableBuilderInterface) {
                $builder->reinit($afterBuildMethods, ...$buildArgs);
            }
        } else {
            $builder = new ClassBuilder($id, $this, $id, $afterBuildMethods, ...$buildArgs);
        }

        return $builder;
    }

    public function addToBuildLine($type, $parameterName)
    {
        $this->buildLine[] = trim("$type \$$parameterName");
    }

    public function getBuildLine()
    {
        return $this->buildLine;
    }

    public function buildLinePop()
    {
        array_pop($this->buildLine);
    }
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
    {
        $this->container = $container;
        $this->resolver = $resolver;
    }

    public function getBuilder($id, $implementation = null, ?array $afterBuildMethods = null, ...$buildArgs)
    {
        if ($implementation === null) {
            $implementation = $id;
        }
        if (is_string($implementation) && is_string($id)) {
            if (class_exists($implementation)) {
                return new ClassBuilder($id, $this->resolver, $implementation, $afterBuildMethods, ...$buildArgs);
            }
            return new ValueBuilder($implementation);
        }

        if ($implementation instanceof BuilderInterface) {
            return $implementation;
        }

        if ($implementation instanceof Closure) {
            return new ClosureBuilder($this->container, $implementation);
        }

        if (is_callable($implementation)) {
            return new CallableBuilder($this->container, $implementation);
        }

        return new ValueBuilder($implementation);
    }

    public function setContainer(Container $container)
    {
        $this->container = $container;
    }

    public function setResolver(Resolver $resolver)
    {
        $this->resolver = $resolver;
    }
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
    {
        $this->value = $value;
    }

    public static function of($value)
    {
        return $value instanceof self ? $value : new self($value);
    }

    public function build()
    {
        return $this->value;
    }
}''',

    "src/ContainerException.php": r'''<?php
namespace lucatume\DI52;

use Exception;
use Psr\Container\ContainerExceptionInterface;

class ContainerException extends Exception implements ContainerExceptionInterface
{
    public static function fromThrowable($id, $thrown, $maskThrowables, array $buildLine)
    {
        // ... builds a pretty error from a throwable
    }
}''',

    "src/NotFoundException.php": r'''<?php
namespace lucatume\DI52;

use Psr\Container\NotFoundExceptionInterface;

class NotFoundException extends ContainerException implements NotFoundExceptionInterface
{
}''',
}


# ---------------------------------------------------------------------------
# Structure-extracted versions (signatures only, no method bodies)
# Simulates IntelliJ Structure API / surface extraction
# ---------------------------------------------------------------------------

STRUCTURE_FILES = {
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
# Windowed file content — N lines around key symbols
# For Resolver.php the key symbols are: resolve(), resolveWithArgs(), cloneBuilder()
# (these are the methods Container.makeWith would plausibly call)
# For Factory.php the key symbol is: getBuilder()
# ---------------------------------------------------------------------------

def window_around_lines(source: str, center_lines: list[int], half_window: int) -> str:
    """Extract a window of lines around each center line, merge overlapping windows."""
    lines = source.split("\n")
    included = set()
    for center in center_lines:
        start = max(0, center - half_window)
        end = min(len(lines), center + half_window + 1)
        for i in range(start, end):
            included.add(i)

    # Always include the first few lines (namespace, use, class declaration)
    for i in range(min(8, len(lines))):
        included.add(i)

    result_lines = []
    prev = -2
    for i in sorted(included):
        if i > prev + 1:
            if result_lines:
                result_lines.append("    // ...")
        result_lines.append(lines[i])
        prev = i

    return "\n".join(result_lines)


# Resolver.php: resolve() starts ~line 75, resolveWithArgs() ~line 64, cloneBuilder() ~line 107
RESOLVER_FULL = FULL_FILES["src/Builders/Resolver.php"]
# Factory.php: getBuilder() starts ~line 20
FACTORY_FULL = FULL_FILES["src/Builders/Factory.php"]


def build_windowed_files(half_window: int) -> dict[str, str]:
    """Build windowed versions of referenced files."""
    return {
        "src/Builders/Resolver.php": window_around_lines(
            RESOLVER_FULL,
            # resolve(), resolveWithArgs(), cloneBuilder() — line numbers in the full source
            [64, 75, 107],
            half_window
        ),
        "src/Builders/Factory.php": window_around_lines(
            FACTORY_FULL,
            # getBuilder() line
            [20],
            half_window
        ),
        # Small files included in full
        "src/Builders/BuilderInterface.php": FULL_FILES["src/Builders/BuilderInterface.php"],
        "src/Builders/ValueBuilder.php": FULL_FILES["src/Builders/ValueBuilder.php"],
        "src/ContainerException.php": STRUCTURE_FILES["src/ContainerException.php"],
        "src/NotFoundException.php": STRUCTURE_FILES["src/NotFoundException.php"],
    }


# ---------------------------------------------------------------------------
# Prompt builder
# ---------------------------------------------------------------------------

def format_context_section(files: dict[str, str]) -> str:
    """Format referenced files into a context section."""
    parts = []
    for path, content in files.items():
        parts.append(f"<Order89ContextFile path=\"{path}\">\n{content.strip()}\n</Order89ContextFile>")
    return "\n\n".join(parts)


def build_prompt(context_files: dict[str, str] | None) -> str:
    """Build the full Order 89 prompt with optional context."""
    context_section = ""
    if context_files:
        context_section = f"""
<Order89Context>
The following files are referenced by the file under edit. Use them to understand
the APIs and types available, so your generated code calls real methods with correct signatures.

{format_context_section(context_files)}
</Order89Context>
"""

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
        {context_section}
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

    # A: No context (baseline)
    variants.append({
        "name": "A_no_context",
        "description": "No context — only the file under edit",
        "prompt": build_prompt(None),
    })

    # B: Full referenced files
    variants.append({
        "name": "B_full_files",
        "description": "Full source of all referenced files",
        "prompt": build_prompt(FULL_FILES),
    })

    # C: Structure-extracted files
    variants.append({
        "name": "C_structure",
        "description": "Structure-extracted referenced files (signatures only, no bodies)",
        "prompt": build_prompt(STRUCTURE_FILES),
    })

    # D: Windowed files at different sizes
    for half_win in [5, 10, 20, 40]:
        total_win = half_win * 2
        windowed = build_windowed_files(half_win)
        variants.append({
            "name": f"D_window_{total_win}",
            "description": f"Windowed referenced files ({total_win}-line window around key symbols)",
            "prompt": build_prompt(windowed),
        })

    return variants


# ---------------------------------------------------------------------------
# Scoring (same convention checks as harness 55)
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
    """Check if the code calls methods that actually exist on Resolver/Factory."""
    real_methods = [
        'resolve(', 'resolveWithArgs(', 'cloneBuilder(',
        'getBuilder(', 'isBound(', 'bind(', 'singleton(',
    ]
    return any(m in code for m in real_methods)

def has_correct_signature(code: str) -> bool:
    """Check if makeWith has a plausible signature: $id + array parameter."""
    return bool(re.search(r'function\s+makeWith\s*\(\s*\$\w+\s*,\s*array\s+\$\w+', code))

def has_try_catch(code: str) -> bool:
    """Check if the code wraps in try/catch like the sibling get() method."""
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
    code_length: int
    prompt_length: int
    duration: float
    raw: str
    code: str

    @property
    def convention_score(self) -> float:
        """Convention matching (same as harness 55): max 9.5"""
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
        """API accuracy — does the code call real methods with correct signatures? Max 3.0"""
        s = 0.0
        if self.calls_real: s += 1.0
        if self.correct_sig: s += 1.0
        if self.has_try_catch: s += 1.0
        return s

    @property
    def total_score(self) -> float:
        """Combined: convention (max 9.5) + api accuracy (max 3.0) = max 12.5"""
        return self.convention_score + self.api_accuracy_score


# ---------------------------------------------------------------------------
# Completion
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
    all_scores: dict[str, list[Score]] = {}

    print(f"\nTesting {len(variants)} context variants, {runs} run(s) each.")
    print("=" * 110)

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
                f"real_api={'Y' if s.calls_real else 'N'}",
                f"sig={'Y' if s.correct_sig else 'N'}",
                f"try={'Y' if s.has_try_catch else 'N'}",
                f"conv={s.convention_score:.1f}",
                f"api={s.api_accuracy_score:.1f}",
                f"total={s.total_score:.1f}/12.5",
                f"{duration:.1f}s",
            ]
            print(" | ".join(flags))

        all_scores[name] = scores

    # ---- Summary ----
    print("\n" + "=" * 110)
    print("SUMMARY — sorted by total score (convention + API accuracy)")
    print("=" * 110)

    header = (f"{'Variant':<20} {'Total':>6} {'Conv':>5} {'API':>4} {'Fence%':>7} "
              f"{'Doc%':>5} {'Real%':>6} {'Sig%':>5} {'Try%':>5} "
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
        real_pct = sum(1 for s in scores if s.calls_real) / n
        sig_pct = sum(1 for s in scores if s.correct_sig) / n
        try_pct = sum(1 for s in scores if s.has_try_catch) / n
        prompt_len = scores[0].prompt_length
        avg_len = sum(s.code_length for s in scores) / n
        avg_dur = sum(s.duration for s in scores) / n
        summary.append((name, avg_total, avg_conv, avg_api, fence_pct, doc_pct,
                         real_pct, sig_pct, try_pct, prompt_len, avg_len, avg_dur))

    summary.sort(key=lambda x: x[1], reverse=True)

    for row in summary:
        (name, avg_total, avg_conv, avg_api, fence_pct, doc_pct,
         real_pct, sig_pct, try_pct, prompt_len, avg_len, avg_dur) = row
        print(f"{name:<20} {avg_total:>5.1f} {avg_conv:>5.1f} {avg_api:>4.1f} "
              f"{fence_pct*100:>6.0f}% {doc_pct*100:>4.0f}% {real_pct*100:>5.0f}% "
              f"{sig_pct*100:>4.0f}% {try_pct*100:>4.0f}% "
              f"{prompt_len:>7,} {avg_len:>7.0f} {avg_dur:>6.1f}")

    # ---- Sample outputs from top 3 ----
    print("\n" + "=" * 110)
    print("SAMPLE OUTPUTS (first run from top variants)")
    print("=" * 110)

    for name, *_ in summary[:4]:
        scores = all_scores[name]
        if not scores:
            continue
        s = scores[0]
        print(f"\n--- {name} (total={s.total_score:.1f}/12.5, conv={s.convention_score:.1f}, api={s.api_accuracy_score:.1f}) ---")
        print(f"Extracted code ({s.code_length} chars):")
        for line in s.code[:1000].split("\n"):
            print(f"  | {line}")
        if s.code_length > 1000:
            print("  | ... (truncated)")

    # ---- Context efficiency ----
    print("\n" + "=" * 110)
    print("CONTEXT EFFICIENCY (score per 1K prompt chars)")
    print("=" * 110)
    print(f"{'Variant':<20} {'Total':>6} {'Prompt(K)':>10} {'Score/K':>8}")
    print("-" * 50)
    for row in summary:
        name, avg_total, *_, prompt_len, _, _ = row
        prompt_k = prompt_len / 1000
        efficiency = avg_total / prompt_k if prompt_k > 0 else 0
        print(f"{name:<20} {avg_total:>5.1f} {prompt_k:>9.1f} {efficiency:>7.2f}")

    winner_name = summary[0][0]
    winner_total = summary[0][1]
    print(f"\n{'=' * 110}")
    print(f"WINNER: {winner_name} (avg total score {winner_total:.1f}/12.5)")
    print(f"{'=' * 110}")


if __name__ == "__main__":
    main()
