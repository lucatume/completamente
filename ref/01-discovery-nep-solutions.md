# Discovery: Next Edit Prediction (NEP) Solutions

## Overview

This document captures findings from analyzing how existing code completion tools implement
**Next Edit Prediction** — a technique where the model predicts the *next version of the file*
rather than simply completing text at the cursor (FIM). The model sees `original → current` and
produces `updated`, from which the extension extracts a precise edit.

---

## 1. Sweep (VSCode Extension)

### Plugin Architecture

Sweep implements NEP through an `InlineCompletionItemProvider` that watches document changes
and sends requests to its own API server.

### Triggering Conditions

- Listens on `onDidChangeTextDocument` events.
- Critical precondition: the provider checks `currentContent === originalContent`. If the file
  has **not** been modified since it was opened/last saved, **no request is made**.
- This means pure cursor movement or scrolling does not trigger a request — only actual edits.

### API Request Format

The plugin builds a request with these fields:

| Field                      | Description                                                   |
|----------------------------|---------------------------------------------------------------|
| `repo_name`                | Workspace folder name                                         |
| `file_path`                | Path to the file being edited                                 |
| `file_contents`            | Current full text of the document buffer                      |
| `original_file_contents`   | File state before the latest edit (last save / open state)    |
| `cursor_position`          | Byte offset of the cursor                                     |
| `recent_changes`           | Formatted unified diffs of recent edits                       |
| `file_chunks`              | Up to 3 recently viewed file buffers (cross-file context)     |
| `retrieval_chunks`         | Definitions/references found via language server               |
| `editor_diagnostics`       | Current linting/compiler errors                               |
| `recent_user_actions`      | Recent user action history                                    |
| `use_bytes`                | Whether cursor_position is a byte offset                      |

### Concrete Example

**Scenario:** User creates a new file `utils.js` and types `function isEven(`.

**Request body:**

```json
{
  "repo_name": "my-project",
  "file_path": "src/utils.js",
  "file_contents": "function isEven(",
  "original_file_contents": "",
  "cursor_position": 17,
  "recent_changes": "File: src/utils.js:\n@@ -0,0 +1 @@\n+function isEven(",
  "file_chunks": [],
  "retrieval_chunks": [],
  "editor_diagnostics": [],
  "recent_user_actions": [],
  "use_bytes": true
}
```

- `original_file_contents` is `""` — brand new file, was empty when opened.
- `file_contents` is `"function isEven("` — the buffer right now.
- `cursor_position` is `17` — byte offset at the end of the typed text.
- `recent_changes` contains the unified diff of typing so far.

### Server-Side Prompt Construction

The server transforms the request into a prompt using `<|file_sep|>` tokens:

```
<|file_sep|>original/src/utils.js

<|file_sep|>current/src/utils.js
function isEven(
<|file_sep|>updated/src/utils.js
```

- **`original/`** block: The file before edits (empty for a new file).
- **`current/`** block: The file as it is now in the buffer.
- **`updated/`** block: The model generates after this separator — predicting the full updated file.

### Model Output

The model generates the predicted updated file:

```javascript
function isEven(n) {
  return n % 2 === 0;
}
```

### Response Processing

1. **Diff extraction:** The extension diffs `current` against `updated`:
   ```diff
   -function isEven(
   +function isEven(n) {
   +  return n % 2 === 0;
   +}
   ```

2. **Edit classification:** The edit starts at/after the cursor position → classified as
   **INLINE** (ghost text). Other classifications include BEFORE (edit before cursor) and
   MULTI (multiple disjoint edits).

3. **Rendering:** The completion appears as dimmed ghost text:
   ```
   function isEven(║n) {
                      return n % 2 === 0;
                    }
   ```
   where `║` is the cursor. The user presses **Tab** to accept.

### Key Insight: FIM Through NEP

This example demonstrates how NEP naturally handles FIM (Fill-in-the-Middle) scenarios.
When `original_file_contents` is empty and `file_contents` contains partial code, the model
sees "empty file became partial code" and predicts the completed version. No special FIM tokens
(`<|fim_prefix|>`, `<|fim_suffix|>`, `<|fim_middle|>`) are needed — the `original → current →
updated` framing already encodes the intent.

---

## 2. How NEP Differs from Traditional FIM

| Aspect              | FIM (Fill-in-the-Middle)                    | NEP (Next Edit Prediction)                       |
|---------------------|---------------------------------------------|--------------------------------------------------|
| **Input**           | Prefix + suffix around cursor               | Original file + current file + cursor position   |
| **Output**          | Text to insert at cursor                    | Full updated file                                |
| **Edit types**      | Insertions only                             | Insertions, replacements, deletions, multi-edits |
| **Context**         | Single cursor location                      | Full edit history (diffs, recent changes)         |
| **Token format**    | `<\|fim_prefix\|>`, `<\|fim_middle\|>`, etc | `<\|file_sep\|>original/`, `current/`, `updated/`|
| **Post-processing** | Insert output at cursor                     | Diff current vs updated, classify, render        |

---

## 3. Edit Classification Strategy

After diffing `current` against `updated`, Sweep classifies the edit based on its position
relative to the cursor:

- **INLINE**: Edit starts at or after the cursor → rendered as ghost text (standard inline
  completion). This is the most common case and covers traditional FIM scenarios.
- **BEFORE**: Edit is entirely before the cursor → rendered as a suggested change
  (may require a different UI treatment like a code action or decorations).
- **MULTI**: Multiple disjoint edit regions → rendered as a multi-point edit suggestion.

---

## 4. Document Change Tracking

Sweep records every `onDidChangeTextDocument` event individually (no coalescing). This gives
the model fine-grained edit history but can result in verbose `recent_changes` for rapid
typing. The tracker maintains:

- No explicit `maxEditHistory` constant was found in the VSCode extension source code or blog
  posts. The number of recent diffs is implicitly bounded by the model's context window — context
  files, diffs, original/current content, and generated output all compete for token budget.
- Each diff is formatted as a unified diff with file path header.
- The `original_file_contents` is updated on file save (not on every keystroke).

---

## 5. Implications for completamente

To add NEP support to this IntelliJ plugin (which currently uses FIM via llama.cpp):

1. **Prompt format change**: Instead of sending `<|fim_prefix|>...<|fim_suffix|>...`, build
   prompts with the `original/current/updated` file separator format.
2. **Document tracking**: Track `original_file_contents` (snapshot on open/save) and compute
   diffs from edit events.
3. **Response handling**: Diff the model's `updated` output against `current` to extract edits,
   then classify and render appropriately.
4. **Model requirement**: The model must be trained on the NEP format (e.g., Sweep's fine-tuned
   models). Standard FIM models (like those served by llama.cpp with `--fim-*` flags) will not
   understand the `<|file_sep|>` prompt structure without fine-tuning.
5. **Hybrid approach**: It may be possible to support both FIM and NEP modes, selecting based
   on model capabilities and edit context.
