# Stripping Non-Code Content from Order 89 Model Output

## Question

The model sometimes returns explanatory prose, reasoning, or comments alongside the requested code.
How does the 99 Neovim plugin constrain its output, and how should Order 89 handle this?

## The Problem

Given the example in the user's request, the model returned output like:

```
Now I have enough Based on the container's API (particularly the `instance()` method ...

    /**
     * It should return a callable ...
     * @test
     */
    public function make_factory_should_return_a_callable()
    {
        ...
    }
```

The first paragraph is prose/reasoning — not code. The rest is valid PHP with docblock comments.
Order 89 currently has no way to distinguish "model commentary" from "code with comments".

## How 99 Constrains Output

The 99 Neovim plugin uses a **multi-layered constraint strategy**:

1. **Temp file redirection**: The model writes output to a temp file, not stdout. This separates
   conversational output (stdout) from the intended code (temp file).

2. **`<MustObey>` constraint tags**: High-priority XML-wrapped rules:
   ```
   <MustObey>
   NEVER alter any file other than TEMP_FILE.
   never provide the requested changes as conversational output. Return only the code.
   ONLY provide requested changes by writing the change to TEMP_FILE
   never attempt to read TEMP_FILE. It is purely for output.
   After writing TEMP_FILE once you should be done.
   </MustObey>
   ```

3. **Format-based parsing**: For structured outputs (e.g., quickfix entries), 99 parses each line
   with a strict regex and silently discards lines that don't match.

**Key insight**: 99's temp file approach structurally separates code from prose. The model's
conversational output goes to stdout (discarded), while the code goes to the temp file (read).
Order 89 reads stdout, so it gets everything mixed together.

## Current Order 89 Post-Processing

Order 89 currently applies two post-processing steps (`Order89Executor.kt`):

1. **`stripCodeFences()`**: Removes markdown triple-backtick fences wrapping the entire output.
2. **`reindentOutput()`**: Normalizes indentation to match the selection's original indent level.

There is **no filtering of prose/commentary** content.

## Analysis: Types of Non-Code Content

The model can return several kinds of non-code content:

| Type | Example | Can be mechanically detected? |
|------|---------|-------------------------------|
| **Leading prose** | "Based on the API, I'll write..." | Yes — appears before first code line |
| **Trailing prose** | "This should work because..." | Harder — could be a comment |
| **Interleaved prose** | Explanations between code blocks | Very hard — looks like comments |
| **Code comments** | `// This resolves the binding` | No — legitimate code |
| **Markdown fences** | ` ```php ... ``` ` | Yes — already handled |

The hardest case is distinguishing "model reasoning that looks like a comment" from "actual code
comments the user wants." Docblock comments (`/** ... */`, `# ...`) are valid code content.

## Approaches to Handle Non-Code Content

### Approach 1: Stronger Prompt Constraints (Low effort, moderate effectiveness)

Add more explicit constraints to the prompt, similar to 99's `<MustObey>`:

```
<Order89Rules>
Output ONLY code. No prose, no reasoning, no explanations.
Do NOT describe what you are about to do. Just output the code.
If the instruction asks for code with comments, include code comments.
Do NOT include any text that isn't valid in the target language.
</Order89Rules>
```

**Pros**: Simple, no code changes needed beyond the prompt template.
**Cons**: Models don't always obey. Larger models are better at following; smaller/cheaper models
often ignore these constraints.

### Approach 2: Leading Prose Detection and Stripping (Medium effort, good effectiveness)

Detect and strip non-code content that appears **before** the first line of actual code. This
handles the most common failure mode (the model "thinking out loud" before generating code).

**Heuristic**: Scan from the top of the output. Lines that don't look like code (no indentation
patterns, no language keywords, no brackets/braces, no comment syntax) are prose. Stop stripping
at the first line that looks like code.

A simpler variant: strip everything before the first line that matches common code patterns:
- Starts with whitespace + language keyword (`public`, `function`, `class`, `if`, `for`, `$`, etc.)
- Starts with a comment marker (`//`, `#`, `/*`, `/**`, `*`, `<!--`)
- Starts with `{`, `}`, `(`, `)`, `[`, `]`
- Is a blank line preceded by code-like lines

**Pros**: Handles the most common case (leading prose). Language-aware detection is possible since
`Order89Request` already carries `language`.
**Cons**: Heuristic — could strip legitimate output. Doesn't handle trailing or interleaved prose.

### Approach 3: Temp File Redirection (Like 99) (Higher effort, high effectiveness)

Change the command template to have the model write to a temp file:

```
cat {{prompt_file}} | claude --dangerously-skip-permissions --print -p "Write the output to {{output_file}}"
```

Read the output file instead of stdout. This structurally separates code from conversational text.

**Pros**: Structural solution — doesn't rely on heuristics. Proven by 99.
**Cons**: Requires changes to the command template, settings, and executor. Only works with models/
tools that support file writing (Claude Code does, but generic CLI tools might not). Breaks the
current "pipe and read stdout" model.

### Approach 4: Regex-Based Section Detection (Medium effort, moderate effectiveness)

After `stripCodeFences()`, apply a regex to detect and remove leading non-code sections:

```kotlin
private val LEADING_PROSE_REGEX = Regex("\\A((?:(?!^\\s*(?:[/{#*]|\\w+\\s*[({]|\\$|<)).[^\\n]*\\n)+)", RegexOption.MULTILINE)

fun stripLeadingProse(output: String): String {
    return output.replace(LEADING_PROSE_REGEX, "")
}
```

**Pros**: Pure function, easy to test, composable with existing pipeline.
**Cons**: Regex is fragile across languages. False positives possible.

### Approach 5: Markdown Code Block Extraction (Low effort, targeted)

If the model wraps the actual code in markdown fences (even after being told not to), extract the
content of the **last** or **largest** fenced block. The existing `stripCodeFences()` already
handles the case where the entire output is one fenced block. Extend it to handle:

```
Here's the code:

```php
// actual code here
```

This should work.
```

Extract the `php` block content.

**Pros**: Handles a common model behavior pattern. Well-defined extraction.
**Cons**: Only works when the model uses fences. Doesn't help with unfenced prose + code.

## Recommendation

**Layer the approaches** (like 99 does):

1. **Strengthen the prompt** (Approach 1) — first line of defense, costs nothing.
2. **Extend `stripCodeFences()`** (Approach 5) — handle embedded fenced blocks, not just wrapping fences.
3. **Add `stripLeadingProse()`** (Approach 2/4) — catch the common "thinking out loud" pattern.

The processing pipeline would be:

```
raw output
  → stripCodeFences()       // remove markdown wrapping
  → stripLeadingProse()     // remove pre-code commentary
  → reindentOutput()        // normalize indentation
```

This is pragmatic: it handles the most common failure modes without the architectural change of
temp file redirection. If these prove insufficient, Approach 3 (temp file) can be added later.

## Sources

- `/Users/lucatume/repos/99/lua/99/prompt-settings.lua` — 99 plugin constraint tags
- `/Users/lucatume/repos/99/lua/99/providers.lua` — 99 plugin temp file output strategy
- `/Users/lucatume/repos/completamente/src/main/kotlin/com/github/lucatume/completamente/order89/Order89Executor.kt` — current post-processing
- `/Users/lucatume/repos/completamente/rhdp/12-output-order89-prompt-structures.txt` — prompt structure test results
