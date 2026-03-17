# Design: Completion Whitespace Trimming

## Goal

Ensure FIM inline completions don't produce visual artifacts from leading/trailing whitespace
emitted by the model. Completions should be clean continuations from the cursor position, with no
doubled indentation, trailing blank lines, or trailing spaces.

## Non-Goals

- Changing how the model generates completions (that's a server/model concern)
- Modifying the prefix/suffix sent to `/infill` (context assembly is separate)
- Handling reindentation of lines 1+ (already handled by `reindentSuggestion()`)

## Problem

Harness tests (44–47) revealed that the `/infill` endpoint returns completions with whitespace
that can cause issues depending on cursor position:

| Cursor position | Observed | Problem |
|---|---|---|
| Mid-line (`WP_CLI::`) | No leading/trailing whitespace | No issue |
| Empty line between methods | 4 leading spaces on line 0 | Doubles indentation if cursor is already at column 4 |
| Start of indented line | 8 leading spaces on line 0 | Doubles indentation if cursor is at column 0 of an indented line |
| Blank line inside method | 8 leading spaces + occasional trailing `\n` + spaces | Doubled indent + trailing blank indented line |
| End of line before blank | Returns only `"\n"` | Caught by `isBlank()` filter — no issue |
| After EOF | No whitespace | No issue |

The core problem: **line 0 of the suggestion may start with indentation spaces that duplicate
whitespace already present before the cursor.** The model emits what it thinks belongs at that
position in the file, but the cursor is already past some or all of that indentation.

## Current Pipeline

```
response.content
  → shouldDiscardSuggestion()    # filters only, no modification
  → reindentSuggestion()         # normalizes line endings, reindents lines 1+, line 0 untouched
  → InlineCompletionGrayTextElement()
```

Line 0 passes through completely unmodified. Lines 1+ get reindented relative to `cursorLineIndent`.

## Chosen Approach: Trim in a New Pure Function

Add a `trimCompletion()` function that runs **before** `reindentSuggestion()` and handles three
concerns:

### 1. Strip leading whitespace from line 0 that overlaps cursor position

The cursor sits at a known column on a known line. The prefix sent to `/infill` includes everything
up to the cursor. If the model returns leading spaces on line 0, those spaces represent indentation
that's already covered by the cursor's position.

**Rule:** Remove leading whitespace from line 0 up to the cursor column's worth of indentation.

The `EditorSnapshot` already captures `cursorCol` (the column offset within the line). The
`buildContext()` function captures `nIndent` (the line's leading whitespace length). The relevant
quantity is `cursorCol` — if the cursor is at column 8 and line 0 starts with 8 spaces, strip them
all. If the cursor is at column 4 and line 0 starts with 8 spaces, strip 4.

More precisely: count leading whitespace characters (spaces/tabs) on line 0 of the suggestion. If
this count ≤ `cursorCol`, strip them all (the cursor is past this indentation). If this count >
`cursorCol`, strip only `cursorCol` characters (preserve the excess as intentional deeper
indentation).

This is conservative: it only strips whitespace that's already to the left of the cursor.

### 2. Strip trailing whitespace from the last line

If the completion ends with a partial line of only whitespace (the model started a new line and
emitted indentation but hit the token limit), strip it. This prevents a trailing ghost-text line of
just spaces.

**Rule:** If the last line of the suggestion is blank (whitespace-only), remove it. Also strip
trailing spaces from the new last line.

### 3. Strip trailing newlines

If the completion ends with one or more `\n` characters (after stripping trailing blank lines),
remove them. Trailing newlines at the end of a suggestion produce empty ghost-text lines.

**Rule:** Remove trailing `\n` characters from the suggestion.

## Function Signature

```kotlin
fun trimCompletion(suggestion: String, cursorCol: Int): String
```

Pure function. Takes the raw suggestion and the cursor column. Returns the trimmed suggestion.

## Pipeline After Change

```
response.content
  → shouldDiscardSuggestion()    # filters only, no modification
  → trimCompletion()             # NEW: strip leading indent overlap + trailing whitespace
  → reindentSuggestion()         # normalizes line endings, reindents lines 1+
  → InlineCompletionGrayTextElement()
```

`trimCompletion()` runs before `reindentSuggestion()` because:
- It modifies line 0, which `reindentSuggestion()` leaves alone
- Trailing whitespace removal should happen before reindentation to avoid reindenting blank lines

## Alternatives Considered

### A. Strip in reindentSuggestion()

Rejected: `reindentSuggestion()` has a clear single responsibility (indent style conversion). Adding
cursor-column-aware trimming would muddy its purpose and complicate its tests.

### B. Strip in the filter (shouldDiscardSuggestion)

Rejected: Filters decide keep/discard; they don't transform content. Mixing transformation into
filtering breaks the pipeline's conceptual model.

### C. Adjust the prefix to prevent the model from emitting leading whitespace

Rejected: The prefix already includes text up to the cursor. The model emits leading spaces because
it's predicting what belongs at that file position. Changing the prefix would break context for the
model. The whitespace is a presentation concern, not a generation concern.

### D. Use n_indent parameter to control model output

The `/infill` endpoint accepts `n_indent` which is already set. However, `n_indent` affects the
model's generation, not post-processing. It doesn't reliably prevent leading whitespace, and changing
it could affect completion quality. Keep it as-is for the model and trim on the client side.

## Trade-offs

- **Aggressive vs. conservative trimming:** We only strip leading whitespace up to `cursorCol`,
  never beyond. This means if the model intentionally indents deeper than the cursor (e.g., starting
  a nested block), that extra indentation is preserved. This is conservative and correct.

- **Trailing line removal:** Removing trailing blank lines means we might lose an intentional
  trailing newline. In practice, a completion that ends with `\n   ` (newline + indentation only)
  is always an artifact of token-limit truncation, not intentional content.

## Edge Cases

| Case | Behavior |
|---|---|
| Suggestion is only whitespace | Already caught by `isBlank()` filter before `trimCompletion()` |
| Suggestion has no leading whitespace | `trimCompletion()` is a no-op for line 0 |
| Single-line suggestion with leading spaces | Spaces stripped up to `cursorCol` |
| `cursorCol` is 0 | No leading whitespace stripped (cursor is at column 0) |
| Suggestion starts with tabs | Tab characters counted as 1 each for stripping purposes |
| Trailing line has mixed content + spaces | Only fully blank trailing lines are removed |
