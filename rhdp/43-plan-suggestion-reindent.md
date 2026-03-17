# Plan: Implement IndentOptions-Aware Suggestion Reindentation

**Implements:** [42-design-suggestion-reindent.md](42-design-suggestion-reindent.md)

---

## Step 1: Create `reindent.kt` with the pure reindentation function

**File:** `src/main/kotlin/com/github/lucatume/completamente/completion/reindent.kt`

Create a new file in the `completion/` package with:

```kotlin
package com.github.lucatume.completamente.completion

data class IndentStyle(
    val useTabs: Boolean,
    val indentSize: Int
)

fun reindentSuggestion(
    suggestion: String,
    cursorLineIndent: String,
    projectStyle: IndentStyle
): String
```

The function:

1. If `suggestion` contains no `\n`, return it unchanged.
2. Split into lines. Line 0 is always unchanged.
3. Collect non-blank lines from index 1+ and detect the model's indent unit:
   - Extract leading whitespace from each non-blank line.
   - If any line starts with `\t`, model uses tabs → unit width = 1 tab character.
   - Otherwise, compute GCD of all leading-space counts. If GCD is 0 or 1 and the minimum
     non-zero leading spaces > 1, use the minimum. This handles common cases (2-space, 4-space).
4. Find the minimum indent level among non-blank lines 1+ (model base level).
5. For each line 1+:
   - If blank, keep as-is.
   - Compute `relative level = (line indent level) - (model base level)`. Clamp to >= 0.
   - Build new indent: `cursorLineIndent + projectIndentUnit.repeat(relativeLevel)`
   - Replace leading whitespace with the new indent.
6. Rejoin and return.

The `projectIndentUnit` is `"\t"` if `projectStyle.useTabs` else `" ".repeat(projectStyle.indentSize)`.

**Acceptance:** Function compiles, is a pure function with no IntelliJ dependencies (only uses
`IndentStyle` data class), can be tested in isolation.

---

## Step 2: Create `reindentTest.kt` with comprehensive tests

**File:** `src/test/kotlin/com/github/lucatume/completamente/completion/reindentTest.kt`

Test cases (following project convention — no mocks, real instances):

### Basic cases
- Single-line suggestion → returned unchanged
- Empty suggestion → returned unchanged
- Two-line suggestion, model matches project style → only base indent applied

### Style conversion
- Model uses 2-space indent, project uses 4-space → widths doubled
- Model uses 4-space indent, project uses 2-space → widths halved
- Model uses spaces, project uses tabs → spaces converted to tabs
- Model uses tabs, project uses spaces → tabs converted to spaces

### Relative indent preservation
- Suggestion with nested indentation (3 levels) → relative levels preserved in project style
- Suggestion with dedent (e.g., closing brace) → dedent preserved

### Cursor indent integration
- Cursor line has 8 spaces, suggestion at model base level → lines get 8-space base
- Cursor line has 2 tabs, suggestion with 1 relative level → lines get 2 tabs + 1 tab

### Edge cases
- All lines after first are blank → returned unchanged
- Mixed indentation in model output → falls back to raw whitespace (strip base, apply cursor)
- Line 0 with leading whitespace → not modified
- Model indent unit = 1 space → treated as 1-space indent
- Very deep nesting (10+ levels) → handled correctly
- Suggestion ending with newline → trailing newline preserved

**Acceptance:** All tests pass with `./gradlew test`.

---

## Step 3: Capture `IndentStyle` in the `EditorSnapshot`

**File:** `src/main/kotlin/com/github/lucatume/completamente/fim/FimInlineCompletionProvider.kt`

Inside the `readAction` block (line ~83), capture `IndentOptions` from the PSI file:

```kotlin
import com.intellij.psi.codeStyle.CodeStyle

// Inside readAction, after existing snapshot fields:
val indentOptions = CodeStyle.getIndentOptions(psiFile)
val indentStyle = IndentStyle(
    useTabs = indentOptions.USE_TAB_CHARACTER,
    indentSize = indentOptions.INDENT_SIZE
)
```

Add `indentStyle: IndentStyle` to the `EditorSnapshot` data class.

Also capture `cursorLineIndent`:

```kotlin
val currentLine = fileContent.substring(lineStart, offset + (fileContent.substring(offset)
    .indexOfFirst { it == '\n' }.let { if (it < 0) fileContent.length - offset else it }))
val cursorLineIndent = currentLine.takeWhile { it == ' ' || it == '\t' }
```

Actually, simpler: derive it from `fileContent` and `lineStart`:

```kotlin
val lineEnd = fileContent.indexOf('\n', lineStart).let { if (it < 0) fileContent.length else it }
val currentLineText = fileContent.substring(lineStart, lineEnd)
val cursorLineIndent = currentLineText.takeWhile { it == ' ' || it == '\t' }
```

Add `cursorLineIndent: String` to the `EditorSnapshot` data class.

**Acceptance:** Snapshot captures both fields without errors. Existing tests still pass.

---

## Step 4: Wire `reindentSuggestion()` into the suggestion emission

**File:** `src/main/kotlin/com/github/lucatume/completamente/fim/FimInlineCompletionProvider.kt`

After the quality filter check (~line 152) and before emitting (~line 164), add:

```kotlin
val reindented = reindentSuggestion(suggestion, snapshot.cursorLineIndent, snapshot.indentStyle)

return InlineCompletionSingleSuggestion.build {
    emit(InlineCompletionGrayTextElement(reindented))
}
```

**Acceptance:** Plugin builds, completions are shown with correct indentation style.

---

## Step 5: Add integration test for reindentation in the provider

**File:** `src/test/kotlin/com/github/lucatume/completamente/fim/FimInlineCompletionProviderTest.kt`

Add a test (if the test infrastructure supports it) that verifies the provider applies reindentation
to suggestions before emitting them. If full provider testing is too heavy, verify via the unit
tests in Step 2 — the wiring in Step 4 is straightforward enough that unit test coverage of
`reindentSuggestion()` plus a build verification is sufficient.

**Acceptance:** `./gradlew test` passes. `./gradlew buildPlugin` succeeds.

---

## Step 6: Verify with manual testing

1. Start a llama.cpp server with the SweepAI model.
2. Open a project that uses 4-space indentation.
3. Type inside a nested block and observe that multi-line suggestions use 4-space indents.
4. Switch to a project using tabs → suggestions should use tabs.
5. Test at various nesting depths to verify relative levels are preserved.

**Acceptance:** Ghost text indentation matches the project's code style in all tested scenarios.

---

## File Summary

| Step | File | Action |
|------|------|--------|
| 1 | `completion/reindent.kt` | Create — pure reindentation function |
| 2 | `completion/reindentTest.kt` | Create — unit tests |
| 3 | `fim/FimInlineCompletionProvider.kt` | Edit — capture IndentStyle + cursorLineIndent in snapshot |
| 4 | `fim/FimInlineCompletionProvider.kt` | Edit — call reindentSuggestion before emit |
| 5 | `fim/FimInlineCompletionProviderTest.kt` | Edit — integration test (optional) |
| 6 | Manual | Verify with live server |
