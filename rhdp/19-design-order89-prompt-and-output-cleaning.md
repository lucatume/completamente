# Design: Order 89 Prompt Strategy and Output Cleaning

## Goal

Redesign the Order 89 prompt template and output post-processing pipeline to reliably produce
code-only output, even when the model includes prose, reasoning, or markdown alongside the code.

## Non-Goals

- Changing the execution model (stdout capture stays; no temp file redirection for now).
- Supporting multiple output files or structured JSON responses.
- Language-specific parsers or AST-based validation of output.

---

## Problem

The current prompt asks the model to output only replacement code:

```
Output ONLY the content that should replace the <Order89UserSelection> tags and their contents.
No markdown fences, no explanations, no text before or after the replacement code.
```

This works for simple transformations but fails when the task is complex (e.g., "write tests for
this method"). The model often returns leading prose ("Based on the API, I'll write...") followed
by the actual code. The current pipeline only strips wrapping markdown fences — it has no defense
against unfenced prose.

---

## Chosen Approach: Layered Prompt + Post-Processing

Two lines of defense, applied in sequence:

1. **Prompt constraints** — tell the model what to do (and not do) more forcefully.
2. **Output cleaning pipeline** — mechanically strip what the model should not have included.

Neither layer alone is sufficient. The prompt reduces the frequency of non-code output; the
pipeline handles the cases where the model ignores the prompt.

---

## Part 1: Prompt Template

### Current Prompt (v2)

```
<Order89Prompt>
Modify the code in the file according to the user instruction below.
Output ONLY the content that should replace the <Order89UserSelection> tags and their contents.
No markdown fences, no explanations, no text before or after the replacement code.
If the selection is empty, insert new code at that position.

Language: {language}
File: {filePath}

<Order89Instruction>
{prompt}
</Order89Instruction>

<Order89FileContent>
{before}<Order89UserSelection>{selection}</Order89UserSelection>{after}
</Order89FileContent>
</Order89Prompt>
```

### New Prompt (v3)

```
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

Language: {language}
File: {filePath}

<Order89Instruction>
{prompt}
</Order89Instruction>

<Order89FileContent>
{before}<Order89UserSelection>{selection}</Order89UserSelection>{after}
</Order89FileContent>
</Order89Prompt>
```

### What Changed and Why

| Change | Rationale |
|--------|-----------|
| Added role declaration ("You are a code transformation tool") | Models follow instructions better with a clear role. Confirmed in harness 11: the "Delimited with role" structure produced clean output. |
| Moved constraints into `<Order89Rules>` block | Mirrors 99's `<MustObey>` pattern. XML-tagged rules get stronger attention from the model than inline sentences. |
| Negative constraints ("Do NOT...") | Research doc 18 notes these are effective. Explicit negatives ("Do NOT describe", "Do NOT wrap") address specific failure modes. |
| "valid in the target language" clause | Distinguishes code comments (wanted) from prose (unwanted). A docblock `/** @test */` is valid PHP; "Based on the API..." is not. |
| "Preserve the indentation style" | Reduces need for aggressive reindenting in post-processing. |
| Kept `<Order89FileContent>` structure unchanged | The file-with-selection structure works well. No reason to change it. |

### Why Not Temp File Redirection

The 99 plugin's temp file approach structurally solves the problem — the model writes code to a
file and conversational output goes to stdout (discarded). This is elegant but:

1. Ties Order 89 to tools that support file writing (Claude Code does; generic CLIs may not).
2. Requires a second placeholder (`{{output_file}}`) and changes to the executor.
3. The default command template becomes harder to understand for users.

Temp file redirection remains a viable upgrade path if the prompt + cleaning approach proves
insufficient, but it is not needed for v3.

---

## Part 2: Output Cleaning Pipeline

### Current Pipeline

```
raw output → stripCodeFences() → reindentOutput()
```

### New Pipeline

```
raw output → extractCodeBlock() → stripLeadingProse() → stripTrailingProse() → reindentOutput()
```

### Step 1: `extractCodeBlock()` (replaces `stripCodeFences()`)

Handles three cases, tried in order:

1. **Whole-output fence**: The entire output is a single fenced block (current behavior).
   ```
   ```kotlin
   code here
   ```
   ```
   Extract inner content.

2. **Embedded fence**: Output contains prose + a single fenced block + optional trailing prose.
   ```
   Here's the code:

   ```kotlin
   code here
   ```

   This should work.
   ```
   Extract the fenced block content.

3. **Multiple fenced blocks**: Output contains multiple fenced blocks.
   Concatenate their contents with a blank line separator. This handles models that split
   code across multiple blocks with interleaved explanations.

4. **No fences**: Return output unchanged (pass to next step).

**Implementation sketch:**

```kotlin
private val FENCED_BLOCK = Regex("```[^\\n]*\\n([\\s\\S]*?)\\n\\s*```")

fun extractCodeBlock(output: String): String {
    val matches = FENCED_BLOCK.findAll(output).toList()
    if (matches.isEmpty()) return output
    return matches.joinToString("\n\n") { it.groupValues[1] }
}
```

This is strictly more capable than the current `stripCodeFences()` and replaces it.

### Step 2: `stripLeadingProse()`

Removes lines from the start of the output that look like natural language prose rather than code.
Stops at the first line that looks like code.

**A line looks like code if it matches any of:**

- Starts with whitespace (indented code)
- Starts with a comment marker: `//`, `#`, `/*`, `/**`, ` *`, `*`, `<!--`, `--`, `%`, `{-`
- Starts with a bracket or brace: `{`, `}`, `(`, `)`, `[`, `]`, `<`, `>`
- Starts with a language keyword or common code pattern (configurable per-language, but a
  universal set covers most cases): `public`, `private`, `protected`, `function`, `class`,
  `interface`, `def`, `fn`, `let`, `const`, `var`, `val`, `if`, `for`, `while`, `return`,
  `import`, `use`, `package`, `namespace`, `@`, `$`, `<`
- Contains an identifier followed by `(` or `[` (function/method calls, array access)
- Contains dot-chained identifiers (`foo.bar`, `this.method`)
- Contains assignment patterns (`identifier =`, `identifier +=`, etc.)

**A line looks like prose if:**

- It does not match any code pattern above
- It contains sentence-like structure (words separated by spaces, no special syntax)

**Algorithm:**

```
scan from line 0:
  skip blank lines
  if line looks like code → stop, keep this line and all remaining
  if line looks like prose → strip it
```

Leading blank lines are stripped along with prose. This avoids inserting empty lines before
the replacement code when the model precedes its output with blank lines.

**Implementation sketch:**

```kotlin
private val CODE_LINE_PATTERN = Regex(
    """^\s*([{}\[\]()@$<>]|//|/\*|\*|#|<!--|--|%|\{-|""" +
    """public\b|private\b|protected\b|function\b|class\b|interface\b|""" +
    """def\b|fn\b|let\b|const\b|var\b|val\b|if\b|for\b|while\b|""" +
    """return\b|import\b|use\b|package\b|namespace\b|""" +
    """abstract\b|static\b|final\b|override\b|""" +
    """struct\b|enum\b|trait\b|impl\b|type\b|module\b)"""
)

private val INDENTED_LINE = Regex("""^[\t ]+\S""")

private val IDENTIFIER_CODE_PATTERN = Regex(
    """[a-zA-Z_]\w*\s*[(\[]|[a-zA-Z_]\w*\.[a-zA-Z_]|[a-zA-Z_]\w*\s*[+\-*/]?=\s"""
)

fun looksLikeCode(line: String): Boolean {
    return CODE_LINE_PATTERN.containsMatchIn(line) ||
        INDENTED_LINE.containsMatchIn(line) ||
        IDENTIFIER_CODE_PATTERN.containsMatchIn(line)
}

fun stripLeadingProse(output: String): String {
    val lines = output.split("\n")
    val firstCodeLine = lines.indexOfFirst { it.isNotBlank() && looksLikeCode(it) }
    if (firstCodeLine <= 0) return output
    return lines.drop(firstCodeLine).joinToString("\n")
}
```

**Why `IDENTIFIER_CODE_PATTERN`**: The `CODE_LINE_PATTERN` and `INDENTED_LINE` regexes miss
common code patterns like function calls (`println(x)`, `assertEquals(...)`) and assignments
(`x = 1`, `result += 2`). These patterns don't start with a keyword or bracket and may not
be indented. The `IDENTIFIER_CODE_PATTERN` catches these cases, reducing false positives where
valid code lines are misclassified as prose and stripped.

**Trade-off**: This is heuristic. It could strip a legitimate first line that happens to look
like prose (e.g., a Python string literal without quotes shown). This is acceptable because:
- The failure mode (stripping a valid line) is visible and undoable.
- The success mode (removing "Now I have enough Based on...") is far more common.
- The code-line pattern list is deliberately broad to minimize false positives.

### Step 3: `stripTrailingProse()`

Same logic as leading prose, but scanning from the end. Removes trailing lines that look like
prose (e.g., "This should work because...").

**Algorithm:**

```
scan from last line backward:
  if line is blank → mark as pending
  if line looks like code → stop, keep this line and all above
  if line looks like prose → strip it
```

**Implementation sketch:**

```kotlin
fun stripTrailingProse(output: String): String {
    val lines = output.split("\n")
    val lastCodeLine = lines.indexOfLast { line ->
        line.isNotBlank() && looksLikeCode(line)
    }
    if (lastCodeLine < 0 || lastCodeLine == lines.lastIndex) return output
    return lines.take(lastCodeLine + 1).joinToString("\n")
}
```

### Step 4: `reindentOutput()` (unchanged)

The existing reindentation logic is kept as-is. The prompt's new "Preserve the indentation style"
instruction should reduce how often reindenting is needed, but the safety net remains.

---

## Pipeline Integration

In `Order89Executor.execute()`, the post-processing changes from:

```kotlin
val output = if (exitCode == 0) stripCodeFences(rawOutput) else rawOutput
```

to:

```kotlin
val output = if (exitCode == 0) cleanOutput(rawOutput) else rawOutput
```

Where `cleanOutput` is:

```kotlin
fun cleanOutput(raw: String): String {
    return extractCodeBlock(raw)
        .let { stripLeadingProse(it) }
        .let { stripTrailingProse(it) }
}
```

Reindenting remains in `Order89Action` (where it currently lives), applied after `cleanOutput`.

---

## Alternatives Considered

### 1. Temp file redirection (99-style)

The model writes code to a file; stdout is discarded. Structural solution, but ties the feature
to tools that support file writing and complicates the default command template. Deferred as a
future upgrade.

### 2. JSON schema / structured output

Ask the model to return `{"code": "..."}` and parse it. Claude supports `--output-format json`
but structured output is not universally supported by all CLI tools. Also requires escaping
within JSON, which models sometimes get wrong (especially with code containing quotes/newlines).

### 3. Language-specific AST validation

Parse the output with a language-specific parser and reject non-parseable content. Far too
heavy for this use case — requires parser infrastructure for every supported language, and
partial/invalid code is a legitimate output (e.g., a code snippet that relies on surrounding
context).

### 4. Sentinel markers

Ask the model to wrap output in custom markers (`<CODE>...</CODE>`) and extract between them.
Similar to markdown fences but custom. Risk: the model might include the markers in the code
itself, or forget them entirely. The layered approach (try fences first, then heuristic
stripping) is more robust.

---

## Compromises

1. **Heuristic prose detection is imperfect.** The `CODE_LINE_PATTERN` regex is broad but not
   exhaustive. A line like `This function creates a factory` could be code (variable assignment
   missing its prefix) or prose. We err on the side of keeping ambiguous lines rather than
   stripping them — the code-line pattern is permissive.

2. **Multiple fenced blocks are concatenated.** If the model puts different code sections in
   separate fences, we join them with blank lines. This may not match the original structure
   perfectly, but it's better than taking only the first or last block.

3. **No per-language keyword lists.** The `CODE_LINE_PATTERN` uses a universal keyword set that
   covers common languages (Java, Kotlin, PHP, Python, Rust, Go, JS/TS, C/C++). Uncommon
   languages with unusual syntax may not be detected. This is acceptable for v3 — per-language
   lists can be added later if needed.

---

## Sources

- `rhdp/18-research-order89-comment-stripping.md` — research on the problem and approaches
- `rhdp/12-output-order89-prompt-structures.txt` — harness results for prompt structures
- `rhdp/13-design-order89.md` — original Order 89 design
- `rhdp/15-research-99-neovim-claude-code.md` — 99 plugin constraint strategy
- `Order89Executor.kt` — current implementation
