# Discovery: IntelliJ APIs for Next Edit Prediction

## Overview

This document captures findings on which IntelliJ Platform APIs are available to implement
Next Edit Prediction (NEP) in a plugin, and their limitations compared to VSCode's approach.

---

## 1. VSCode's Approach (for reference)

Sweep uses VSCode's `InlineCompletionItemProvider` API. This API is flexible enough that Sweep
can return ghost text representing the diff between `current` and `updated` file contents. The
model returns the **full updated file**, and the extension diffs it client-side to produce
`InlineCompletionItem` objects.

---

## 2. IntelliJ's `InlineCompletionProvider` — Insert-at-Cursor Only

`InlineCompletionProvider` (added in IntelliJ 2023.2) is the closest equivalent to VSCode's
`InlineCompletionItemProvider`. It produces `InlineCompletionElement` objects that render as
ghost text at the caret position.

**Limitation:** It can only **insert** text at the cursor. It cannot:

- Replace existing text
- Delete text
- Make edits at positions other than the cursor
- Express multi-point edits

This makes it sufficient for FIM-style completions but **insufficient for full NEP**, where
the model may predict replacements, deletions, or edits before/after the cursor.

---

## 3. Lower-Level APIs for Full NEP Support

To support the full range of NEP edit types (insertions, replacements, deletions, multi-point
edits), a plugin must compose several lower-level APIs manually.

### 3.1 Inlay API (`InlayModel` + `EditorCustomElementRenderer`)

- Renders custom visual elements (ghost text) at **arbitrary** positions in the editor, not
  just the cursor.
- Used by many plugins to show inline suggestions.
- Can render added/inserted text as dimmed inlays at any offset in the document.
- Does **not** handle deletions or replacements visually on its own — must be combined with
  `RangeHighlighter`.

### 3.2 `RangeHighlighter` / `MarkupModel`

- Adds visual decorations to existing text ranges: strikethrough, color changes, background
  highlights.
- Useful for showing "this text will be replaced/deleted" as part of a NEP suggestion.
- Combined with Inlay API: strikethrough on old text + inlay with new text = visual
  replacement.

### 3.3 `Document` API (`replaceString`, `insertString`, `deleteString`)

- Low-level document mutation for **applying** the accepted edit.
- Called when the user presses Tab (or equivalent accept key).
- Can perform arbitrary modifications: insertions, replacements, deletions at any offset.

### 3.4 `TypedActionHandler` / `EditorActionHandler`

- Intercepts keystrokes to drive the accept/reject flow.
- Tab to accept the suggestion, Escape to dismiss.
- Must be carefully managed to avoid interfering with normal editor behavior when no
  suggestion is active.

---

## 4. Composition for Full NEP

A full NEP implementation in IntelliJ requires manually composing these APIs:

```
Model Response (full updated file)
        │
        ▼
  Diff current vs updated
        │
        ▼
  Classify edits (INLINE / BEFORE / MULTI)
        │
        ├─► Insertions:    Inlay (ghost text at target offset)
        ├─► Replacements:  RangeHighlighter (strikethrough old) + Inlay (ghost new)
        └─► Deletions:     RangeHighlighter (strikethrough)
        │
        ▼
  Action Handlers
        ├─► Tab:    Apply via Document API, clear decorations
        └─► Escape: Clear decorations, dismiss suggestion
```

This is significantly more work than using `InlineCompletionProvider`, but it is the only way
to support the full range of NEP edit types.

---

## 5. Pragmatic Middle Ground: Hybrid Approach

Rather than implementing the full manual composition from the start, a phased approach:

### Phase 1: Insert-only via `InlineCompletionProvider`

- Use the NEP prompt format (`original/current/updated`) to get predictions from the model.
- Diff `current` vs `updated` on the client side.
- If the edit is **INLINE** (starts at or after cursor, insertion only) → use
  `InlineCompletionProvider` to render it as standard ghost text.
- If the edit is BEFORE or MULTI → **discard it** (don't show anything).

This gets NEP working with zero new rendering code, at the cost of dropping non-insertion
predictions.

### Phase 2: Full NEP rendering

- Add the Inlay + RangeHighlighter composition for replacement and multi-point edits.
- Edits classified as BEFORE or MULTI are now rendered using the manual approach.
- `InlineCompletionProvider` continues to handle simple INLINE edits.

---

## 6. Summary

| API                          | Insert | Replace | Delete | Multi-edit | Complexity |
|------------------------------|--------|---------|--------|------------|------------|
| `InlineCompletionProvider`   | Yes    | No      | No     | No         | Low        |
| Inlay + RangeHighlighter     | Yes    | Yes     | Yes    | Yes        | High       |

The recommendation is to start with `InlineCompletionProvider` for INLINE edits (Phase 1),
then layer in the manual Inlay/RangeHighlighter approach for richer edit types (Phase 2).
