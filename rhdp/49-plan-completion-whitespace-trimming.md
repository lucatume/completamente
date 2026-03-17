# Plan: Completion Whitespace Trimming

Implements the design in `48-design-completion-whitespace-trimming.md`.

## Step 1: Add `trimCompletion()` pure function

**File:** `src/main/kotlin/com/github/lucatume/completamente/completion/trim.kt` (new)

```kotlin
fun trimCompletion(suggestion: String, cursorCol: Int): String
```

Logic:

1. If `suggestion` is empty, return it.
2. Normalize line endings (`\r\n` / `\r` → `\n`).
3. **Leading whitespace on line 0:** Count leading space/tab characters on the first line. Strip
   `min(leadingCount, cursorCol)` characters from the start.
4. **Trailing blank lines:** If the last line is blank (whitespace-only after trimming), remove it.
   Repeat until the last line has non-whitespace content or only one line remains.
5. **Trailing spaces on last line:** Strip trailing space/tab characters from the final line.
6. **Trailing newlines:** Remove any trailing `\n` characters.
7. If the result is empty after trimming, return empty string.

## Step 2: Add tests for `trimCompletion()`

**File:** `src/test/kotlin/com/github/lucatume/completamente/completion/trimTest.kt` (new)

Test cases grouped by concern:

**Leading whitespace stripping (line 0):**
- No leading whitespace → unchanged
- Leading spaces ≤ cursorCol → all stripped
- Leading spaces > cursorCol → only cursorCol characters stripped
- Leading tabs → each tab counts as 1 character
- Mixed spaces and tabs → stripped up to cursorCol count
- cursorCol = 0 → nothing stripped
- Single-line suggestion with leading spaces

**Trailing whitespace:**
- No trailing whitespace → unchanged
- Trailing blank line (spaces only) → removed
- Multiple trailing blank lines → all removed
- Trailing spaces on last content line → stripped
- Trailing `\n` characters → removed
- Trailing `\n` + spaces (indentation artifact) → removed

**Combined:**
- Leading spaces + trailing blank line → both handled
- Multi-line suggestion: line 0 trimmed, lines 1+ untouched (reindent handles those)
- Single `\n` suggestion → returns empty string
- Only whitespace → returns empty string

**Edge cases:**
- Empty string → empty string
- Single character → unchanged
- CRLF normalization before trimming

Target: ~30 tests.

## Step 3: Capture `cursorCol` in EditorSnapshot

**File:** `src/main/kotlin/com/github/lucatume/completamente/fim/FimInlineCompletionProvider.kt`

Check if `cursorCol` is already available in the snapshot. From the exploration, `buildContext()`
receives `col` (the cursor column). Verify that this value is accessible where `trimCompletion()`
will be called.

The `EditorSnapshot` data class (or the local variables in `getSuggestion()`) should already have
`cursorCol` from the `readAction` block. If it's not surfaced as a field, add it.

**Acceptance:** `cursorCol` is available at the call site for `trimCompletion()`.

## Step 4: Wire `trimCompletion()` into the provider

**File:** `src/main/kotlin/com/github/lucatume/completamente/fim/FimInlineCompletionProvider.kt`

Insert the call between the discard check and reindentation:

```kotlin
// Existing:
if (shouldDiscardSuggestion(suggestion, suffixText, prefixLastLine)) {
    return InlineCompletionSingleSuggestion.build {}
}

// NEW:
val trimmed = trimCompletion(suggestion, snapshot.cursorCol)
if (trimmed.isEmpty()) {
    return InlineCompletionSingleSuggestion.build {}
}

// Existing (update variable name):
val reindented = reindentSuggestion(trimmed, snapshot.cursorLineIndent, snapshot.indentStyle)
```

Note the second emptiness check after trimming — a suggestion that was only whitespace padding
should not produce a ghost-text element.

## Step 5: Run tests and verify

```bash
./gradlew test
```

All existing tests should pass unchanged. The new `trimTest.kt` tests should pass. Specifically
verify:
- `filtersTest.kt` — discard rules unaffected
- `reindentTest.kt` — reindentation unaffected (it receives already-trimmed input)
- `FimInlineCompletionProviderTest.kt` — integration smoke tests pass

## Step 6: Manual verification with harness

Re-run harness 44 (or a subset) with the plugin installed and verify that:
- Mid-line completions (`WP_CLI::`) display correctly (no change expected)
- Empty-line completions don't show doubled indentation
- No trailing blank ghost-text lines appear

This step is manual / visual — check the IDE's inline completion rendering.
