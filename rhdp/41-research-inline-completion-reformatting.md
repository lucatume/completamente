# Reformatting Inline Completion Ghost Text Before Acceptance

**Question:** Can IntelliJ's API reformat FIM inline completion suggestions (ghost text) before the
user accepts them, even when the suggestion is partial or syntactically incomplete?

---

## Current State in completamente

The FIM pipeline currently applies **no formatting** to suggestions. The raw text from llama.cpp is
emitted directly:

```kotlin
// FimInlineCompletionProvider.kt, line ~164
emit(InlineCompletionGrayTextElement(suggestion))
```

Indentation is handled server-side only: `nIndent` (leading whitespace count on the cursor line) is
sent to llama.cpp as `n_indent` in the `/infill` request. The server uses this hint to bias
generation, but the client does no post-processing.

---

## InlineCompletionElement API

All inline completion element types accept a plain `String`:

- `InlineCompletionGrayTextElement(text: String)` — gray ghost text
- `InlineCompletionColorTextElement(text: String, color: (Editor) -> Color)`
- `InlineCompletionTextElement(text: String, attrs: (Editor) -> TextAttributes)`

**There are no built-in formatting hooks.** The text passed to the constructor is rendered as-is.
Any reformatting must happen before constructing the element.

`InlineCompletionSuggestionUpdateManager` allows reacting to typing events (for overtyping), but
does not provide formatting callbacks.

---

## CodeStyleManager Capabilities

`CodeStyleManager` operates on PSI elements and `PsiFile` objects, not raw strings:

| Method | Works on raw strings? | Notes |
|--------|-----------------------|-------|
| `reformat(PsiElement)` | No | Modifies element in-place |
| `reformatText(PsiFile, start, end)` | No | Requires document-backed file |
| `adjustLineIndent(PsiFile, offset)` | No | Adjusts indent at offset |
| `getLineIndent(PsiFile, offset)` | No | Returns expected indent string |

All methods require a `PsiFile` backed by a document, but the document does **not** need to be the
real editor document — it can be an in-memory temporary file.

---

## Approaches

### Approach A: Temporary PsiFile + CodeStyleManager (Full Formatting)

Create a throwaway file containing prefix + suggestion + suffix, reformat the suggestion range,
extract the result.

```kotlin
// Pseudocode
val combined = prefix + suggestion + suffix
val tempFile = PsiFileFactory.getInstance(project)
    .createFileFromText("_completion.${ext}", language, combined)
CodeStyleManager.getInstance(project)
    .reformatText(tempFile, listOf(TextRange(prefix.length, prefix.length + suggestion.length)))
val reformatted = extractReformattedRange(tempFile, prefix.length, originalSuggestionLength)
```

**Pros:**
- Full formatting fidelity (indentation, spacing, brace placement)
- Context-aware: surrounding code provides correct nesting depth
- Handles incomplete code reasonably (formatter's `isIncomplete()` block model)

**Cons:**
- Requires `WriteCommandAction` / write action (modifies PSI tree)
- Adds latency to the completion pipeline (PSI creation + formatting)
- Reformatting may change text length, requiring range tracking
- Must use `\n` line separators for `createFileFromText()`

### Approach B: getLineIndent Queries (Indentation Only)

Query `CodeStyleManager.getLineIndent()` for each line position to compute expected indentation.

```kotlin
val expectedIndent = CodeStyleManager.getInstance(project).getLineIndent(psiFile, lineOffset)
if (expectedIndent != null) {
    reformattedLine = expectedIndent + line.trimStart()
}
```

**Pros:**
- Lighter weight than full reformatting
- Only fixes indentation (less risk of unwanted changes)

**Cons:**
- Still requires a PsiFile with the suggestion inserted (needs surrounding context)
- Returns null when indent cannot be determined
- Does not fix spacing, alignment, or brace style

### Approach C: Manual Indentation Adjustment (Simplest)

Use the cursor line's indentation plus the project's `IndentOptions` to re-indent suggestion lines.
Similar to Order89Executor's existing `reindentOutput()` logic.

```kotlin
// Detect base indent from suggestion, apply cursor indent
val baseIndent = detectBaseIndent(suggestion)
val lines = suggestion.split("\n")
lines.mapIndexed { i, line ->
    when {
        line.isBlank() -> line
        i == 0 -> line  // first line continues from cursor
        else -> cursorIndent + line.removePrefix(baseIndent)
    }
}.joinToString("\n")
```

**Pros:**
- Zero PSI overhead, no write actions needed
- Negligible latency
- Already proven in Order89Executor (`reindentOutput()` at lines 132-144)

**Cons:**
- Cannot handle indent increases/decreases within the suggestion (e.g., entering a block)
- No awareness of code style settings (tabs vs spaces, indent size)
- Does not fix non-indentation formatting

### Approach D: IndentOptions-Aware Heuristic (Recommended Middle Ground)

Combine cursor-line indentation with the project's `CommonCodeStyleSettings.IndentOptions` to
re-indent using proper tab/space settings. This is what JetBrains' own Full Line Completion does
via `MultilinePostProcessor.processMultilineSuggestion(suggestion, startIndent, indentOptions)`.

```kotlin
val indentOptions = CodeStyle.getIndentOptions(psiFile)
val startIndent = currentLineLeadingWhitespace
// For each line, compute relative indent change from model output,
// then reapply using project indent settings
```

**Pros:**
- Respects project code style (tab size, tabs vs spaces)
- No PSI file creation needed
- Low latency
- Proven by JetBrains' own implementation

**Cons:**
- Cannot detect indent-level changes from code structure (e.g., closing brace should dedent)
- Heuristic-based, not semantically aware

---

## What Other Plugins Do

| Plugin | Reformats before display? | Approach |
|--------|---------------------------|----------|
| **JetBrains Full Line Completion** | Yes | `MultilinePostProcessor` + `IndentsTextFormatter` + per-language formatters |
| **GitHub Copilot** | No / inadequately | Known unresolved indentation issues since 2022 |
| **Tabnine** | No evidence | Uses standard `InlineCompletionProvider` API |

JetBrains Full Line Completion's key classes:
- `MultilinePostProcessor.processMultilineSuggestion(suggestion, startIndent, indentOptions)`
- `IndentsTextFormatter` — adjusts indentation using code style settings
- `PsiCodeFormatter.format(PsiFile, PsiElement, offset)` — returns `FormatResult`

---

## Handling Partial / Incomplete Code

The IntelliJ formatter uses a block model where `FormattingBlock.isIncomplete()` signals an
unfinished construct (e.g., missing closing brace). This affects child indentation calculations.

- **Approach A** (temp PsiFile): handles incomplete code well when surrounding context is included,
  because the formatter sees the full nesting structure
- **Approaches C/D** (heuristic): cannot detect structural incompleteness, but for indentation-only
  fixes this is usually acceptable — the model typically generates indentation relative to its
  context, and we just need to align the base level

For partial suggestions that don't form valid syntax, the full formatter may produce unexpected
results (e.g., collapsing lines, removing tokens it considers errors). Heuristic approaches are
actually **safer** for incomplete code because they only adjust whitespace.

---

## Recommendation

**Start with Approach D** (IndentOptions-aware heuristic), evolving from the existing
`Order89Executor.reindentOutput()` pattern:

1. Get `IndentOptions` from the file's code style
2. Detect the model's base indentation from the suggestion
3. Normalize to project settings (tabs ↔ spaces, indent width)
4. Apply cursor-line indent as the base level
5. Preserve relative indent changes within the suggestion

This gives good results with minimal latency and no PSI overhead. It's the same strategy JetBrains
uses in their `MultilinePostProcessor`. If indentation problems persist for specific languages,
Approach A (temp PsiFile reformat) can be added as a language-specific enhancement later.

---

## Key Files in completamente

| File | Relevance |
|------|-----------|
| `fim/FimInlineCompletionProvider.kt:~164` | Where suggestion is emitted (insertion point for formatting) |
| `completion/context.kt:93-97` | Where `nIndent` is computed |
| `completion/compose.kt:68` | Where `nIndent` flows into request |
| `order89/Order89Executor.kt:76-144` | Existing `reindentOutput()` logic to adapt |
| `order89/Order89Action.kt:171` | `CodeStyleManager.reformatText()` usage reference |

## Sources

- [IntelliJ Platform SDK — Code Formatting](https://plugins.jetbrains.com/docs/intellij/code-formatting.html)
- [IntelliJ Platform SDK — Modifying the PSI](https://plugins.jetbrains.com/docs/intellij/modifying-psi.html)
- [CodeStyleManager.java source (JetBrains GitHub)](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/psi/codeStyle/CodeStyleManager.java)
- [GitHub Copilot indentation issues](https://github.com/orgs/community/discussions/11724)
- [Full Line Code Completion docs](https://www.jetbrains.com/help/idea/full-line-code-completion.html)
