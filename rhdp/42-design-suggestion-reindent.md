# Design: IndentOptions-Aware Suggestion Reindentation

**Based on:** [41-research-inline-completion-reformatting.md](41-research-inline-completion-reformatting.md) — Approach D

---

## Goal

Reindent FIM inline completion suggestions before showing them as ghost text, so the gray text
already uses the project's correct indentation style (tabs vs spaces, indent width). The reindentation
must work with partial/incomplete code and add negligible latency to the completion pipeline.

## Non-Goals

- Full code reformatting (spacing around operators, brace placement, alignment). Only whitespace
  indentation is adjusted.
- Language-specific formatting rules. The reindenter is language-agnostic.
- Modifying the server response. `nIndent` continues to hint the server; this is a client-side
  safety net that normalizes whatever the server returns.

---

## Approach

A pure function `reindentSuggestion()` in the `completion/` package that:

1. Takes the raw suggestion string, the cursor line's leading whitespace, and the project's
   `IndentOptions` (tab size, use-tab flag, indent size).
2. Detects the model's indentation style from the suggestion itself (tabs vs spaces, indent width).
3. Converts each line's indentation from the model's style to the project's style, preserving
   relative indent levels within the suggestion.
4. Returns the reindented string, ready to emit as `InlineCompletionGrayTextElement`.

### Single-line suggestions

Single-line suggestions (no `\n`) are returned unchanged. The first line of any suggestion continues
from the cursor position — it has no leading whitespace to adjust.

### Multi-line suggestions

For suggestions with 2+ lines, lines after the first are reindented:

```
Line 0:  unchanged (continues from cursor, no leading whitespace to fix)
Line 1+: reindented to match project style
```

### Indentation detection

The model may generate indentation using a different style than the project (e.g., 2-space indent
when the project uses 4-space, or spaces when the project uses tabs). The reindenter:

1. **Detects model indent unit** — scans non-blank lines (index >= 1) for the smallest non-zero
   leading whitespace. If the model uses tabs, the unit is `\t`. If spaces, the unit is the GCD of
   all leading-space counts (falling back to the raw whitespace if GCD is ambiguous).
2. **Computes indent level** per line — `leading whitespace length / model indent unit length`.
3. **Rebuilds indentation** using project settings — `indent level * project indent string`.

The "project indent string" is:
- `\t` if `IndentOptions.USE_TAB_CHARACTER` is true
- `" ".repeat(IndentOptions.INDENT_SIZE)` otherwise

### Cursor-line indent as base level

The cursor line's existing indentation establishes the base level for line 1 of the suggestion.
Lines with deeper indentation in the model output get additional indent levels on top of this base.

Example: cursor line has 8 spaces indent, project uses 4-space indents, model generated with
2-space indents:

```
Model output:        →  Reindented:
"result = foo()\n"   →  "result = foo()\n"         (line 0, unchanged)
"  if bar:\n"        →  "        if bar:\n"         (line 1: base=8sp, +0 levels)
"    baz()\n"        →  "            baz()\n"       (line 2: base=8sp, +1 level)
```

Wait — line 1 has indent level 1 in the model (2 spaces = 1 unit of 2). But line 1 should be at
the *base* level (same as cursor line), not one level deeper. The model's line 1 indent represents
the base level of the generated block.

So the algorithm is:

1. Detect model indent unit from lines 1+ of the suggestion.
2. Find the **minimum indent level** among non-blank lines 1+ (this is the model's base level).
3. For each line 1+:
   - `relative level = line's indent level - model base level`
   - `new indent = cursor indent + relative level * project indent string`

This matches the existing `Order89Executor.reindentOutput()` logic (strip base indent, apply target
indent) but adds indent-unit normalization.

### Edge cases

| Case | Behavior |
|------|----------|
| Single-line suggestion | Return unchanged |
| All lines after first are blank | Return unchanged |
| Model uses tabs, project uses spaces | Convert tab indentation to spaces |
| Model uses spaces, project uses tabs | Convert space indentation to tabs |
| Mixed tabs/spaces in model output | Treat as-is (no normalization, just strip base + apply target) |
| Empty suggestion | Return unchanged |
| Model indent width matches project | Still normalize (ensures tabs/spaces match) |

### Where it plugs in

In `FimInlineCompletionProvider.getSuggestion()`, after quality filters pass and before emitting:

```kotlin
// Current (line ~164):
emit(InlineCompletionGrayTextElement(suggestion))

// New:
val reindented = reindentSuggestion(suggestion, cursorLineIndent, indentOptions)
emit(InlineCompletionGrayTextElement(reindented))
```

The `cursorLineIndent` (leading whitespace string of the cursor line) is already derivable from the
snapshot's `fileContent`, `lineStart`, and `offset`. The `IndentOptions` must be captured in the
`readAction` block (requires PSI access).

---

## Alternatives Considered

### Approach A: Temp PsiFile + CodeStyleManager

Full formatting fidelity but requires write actions, adds latency, and can mangle incomplete code.
Rejected as the default approach; could be added later as an opt-in enhancement.

### Approach C: Manual indent matching (no IndentOptions)

Simpler but blind to project settings. If the model happens to use the same indent style, it works.
If not (e.g., model uses 2-space, project uses tabs), the mismatch passes through unchanged.
Rejected because the whole point is to normalize indentation style.

---

## Trade-offs

- **Heuristic, not semantic:** Cannot detect that a closing brace should dedent. If the model gets
  relative indentation wrong, we propagate that error in the project's style. This is acceptable
  because the model usually gets relative levels right — the common failure mode is style mismatch
  (tabs vs spaces, indent width), not wrong nesting.

- **First line is never touched:** If the model returns a first line with unexpected leading
  whitespace, we don't fix it. This is by design: the first line continues from the cursor position,
  and any leading whitespace is part of the completion content, not indentation.

- **GCD heuristic for indent width detection:** May be wrong for unusual indentation patterns (e.g.,
  alignment to non-standard columns). Falls back gracefully to raw whitespace manipulation.
