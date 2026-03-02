# Discovery: Sweep AI VSCode Client — Context Assembly and Limits

## Overview

This document captures findings from analyzing the open-source Sweep VSCode extension
(`github.com/sweepai/vscode-nes`) to understand how the client assembles context before sending
requests to the inference server. The server side is proprietary, so this covers only client-side
logic.

---

## 1. Context Size Strategy

Sweep does **not count tokens**. There is no tokenizer, no `BYTES_PER_TOKEN_GUESS` heuristic
(like Zed uses), and no token budget cascade. Instead, each context source is capped by
structural limits (line counts, item counts, file size thresholds). The assumption is that these
fixed limits keep the total payload within the model's context window. The proprietary server may
perform additional truncation before building the prompt.

---

## 2. Context Limits by Source

### File Being Edited

| Mechanism | Limit | Description |
|-----------|-------|-------------|
| `isFileTooLarge()` | Max characters, max lines, max avg line length | Applied to both current document text and original (pre-edit) content. If either exceeds the thresholds, the request is skipped entirely. |
| `MAX_DIAGNOSTICS` | 50 | Editor diagnostics at cursor position. |

### Extra Input Files (Cross-File Context)

| Mechanism | Limit | Description |
|-----------|-------|-------------|
| `MAX_FILE_CHUNK_LINES` | 60 lines | Window size for visible buffer snapshots, built around a "focus line" using `buildLineWindow()`. |
| `config.maxContextFiles` | Configurable | Number of recent files included as context. |
| Recent buffers | 3 files max | Open file buffers (`.slice(0, 3)`). |
| `MAX_RETRIEVAL_CHUNKS` | 16 | Total retrieval context chunks from LSP. |
| `MAX_DEFINITION_CHUNKS` | 6 | Definition reference lookups via LSP. |
| `MAX_USAGE_CHUNKS` | 6 | Usage reference lookups via LSP. |
| `RETRIEVAL_CONTEXT_LINES_ABOVE` | 9 lines | Lines of context above each retrieval chunk. |
| `RETRIEVAL_CONTEXT_LINES_BELOW` | 9 lines | Lines of context below each retrieval chunk. |
| `MAX_CLIPBOARD_LINES` | 20 | Clipboard content included in request. |

### Recent Diffs (Edit History)

| Mechanism | Limit | Description |
|-----------|-------|-------------|
| `getEditDiffHistory()` | **No explicit limit** | The full edit diff history is retrieved and sent without truncation or slicing on the client side. Diffs span all tracked files, not just the file being edited. |

---

## 3. Edit Diff History

The edit diff history is gathered via `this.tracker.getEditDiffHistory()` in the `buildInput()`
method of `InlineEditProvider`. Key observations:

- **Cross-file**: Each diff record includes a `filepath`, and the history is not filtered to the
  current document. Edits from any tracked file in the workspace are included.
- **No client-side cap**: No `maxEditHistory` constant, no slicing, no truncation was found in
  the extension source code or blog posts.
- **Implicit bound**: The number of diffs is bounded only by the model's context window. The
  proprietary server may enforce its own limits.
- **Format**: Each diff is formatted as `File: {path}:\n{cleaned_diff}` in the `recent_changes`
  field of the API request.

---

## 4. Request Assembly Flow

```
InlineEditProvider.buildInput()
  ├─ recentChanges = tracker.getEditDiffHistory()     // all files, no limit
  ├─ userActions = tracker.getUserActions()            // uncapped
  ├─ fileChunks = visible buffers + recent files       // 60 lines each, 3 files max
  ├─ retrievalChunks = LSP definitions + usages        // 6+6, 9 lines context, 16 total
  ├─ diagnostics = editor diagnostics                  // 50 max
  └─ clipboard = clipboard content                     // 20 lines max
        │
        ▼
ApiClient.buildRequest()
  ├─ isFileTooLarge(currentContent)?                   // reject if too large
  ├─ isFileTooLarge(originalContent)?                  // reject if too large
  ├─ formatRecentChanges(recentChanges)                // format diffs
  ├─ truncateRetrievalChunks()                         // enforce line limits
  ├─ fuseAndDedupRetrievalSnippets()                   // remove duplicates
  └─ assemble AutocompleteRequest payload
        │
        ▼
POST to inference server (Brotli compressed)
```

---

## Sources

### Source Code
- [github.com/sweepai/vscode-nes](https://github.com/sweepai/vscode-nes)
  - `src/api/client.ts` — API client, request building, chunk limits
  - `src/api/schemas.ts` — Request/response Zod schemas
  - `src/editor/inline-edit-provider.ts` — Context assembly, buildInput()
  - `src/utils/text.ts` — `isFileTooLarge()`, UTF-8 offset utilities

### Blog Posts
- [Open sourcing a 1.5B parameter Next-Edit Autocomplete](https://blog.sweep.dev/posts/oss-next-edit)
- [Building next-edit autocomplete for JetBrains](https://blog.sweep.dev/posts/next-edit-jetbrains)
- [Autocomplete Context](https://blog.sweep.dev/posts/autocomplete-context)
