# Design: Context Assembly (Diffs + Chunk Ring)

## Goal

Capture recent edits and recently visited code, then wire both into the FIM/NEP prompt as context
sections. The diff tracker provides edit history; the chunk ring provides cross-file context from
recently visited files, open buffers, and clipboard.

## Non-goals

- LSP-based context (go-to-definition, find-references) — chunk ring covers this
- Diagnostics in the prompt
- Chunk selection/filtering — all ring chunks are included, ring eviction handles quality

---

## 1. Diff Tracking

### Data model

```kotlin
data class StoredDiff(
    val filePath: String,
    val timestamp: Long,                    // System.currentTimeMillis() when finalized
    val beforeSnapshot: CharSequence,       // immutable text before the edit group
    val afterSnapshot: CharSequence,        // immutable text after the edit group
    val startLine: Int,                     // first line affected (for coalescing check)
    val endLine: Int                        // last line affected (for coalescing check)
)

data class PendingEdit(
    val filePath: String,
    val beforeSnapshot: CharSequence,
    val startLine: Int,
    val endLine: Int,
    val lastEditTime: Long
)

data class DiffEntry(
    val filePath: String,
    val original: String,
    val updated: String,
    val estimatedTokens: Int = 0
)
```

### DiffTracker service

A `@Service(Service.Level.PROJECT)` class implementing `DocumentListener` and `Disposable`.

**State**: `ArrayDeque<StoredDiff>` (max 10, configurable via `maxRecentDiffs` setting) +
`MutableMap<String, PendingEdit>` keyed by file path.

**Edit capture flow**:
1. `beforeDocumentChange` — capture `beforeSnapshot` if no pending edit exists for the file
2. `documentChanged` — coalesce if edit is within 8 lines of the pending group, otherwise finalize
   the pending edit and start a new one
3. Pause detection — an `Alarm` checks every 1s; any pending edit idle > 1s is finalized

**Constants**:
```kotlin
const val COALESCE_LINE_SPAN = 8          // edits within 8 lines coalesce
const val PAUSE_THRESHOLD_MS = 1_000L     // 1-second pause finalizes a group
const val DEFAULT_MAX_RECENT_DIFFS = 10   // max stored diffs
const val MAX_DIFF_CHARS = 20_000         // per-diff truncation limit
```

**Diff computation**: Uses `ComparisonManager.getInstance().compareLines()` to extract only changed
lines (plus context) from each snapshot pair, producing compact `original:`/`updated:` blocks.

**Self-edit suppression**: An `ignoreNextChange` flag prevents recording edits applied by
`FimSuggestionManager.acceptSuggestion()`.

**Thread safety**: All state mutations happen on EDT (DocumentListener callbacks). `getRecentDiffs()`
returns a snapshot copy for safe reading from pooled threads.

---

## 2. Chunk Ring

All extra file context comes from the existing `ChunksRingBuffer` service:

| Sweep AI source | Our equivalent |
|-----------------|----------------|
| Clipboard (1 chunk, 20 lines) | Chunk ring via `pickChunkFromText` |
| Open/visible buffers (3 chunks, 60 lines) | Chunk ring via `pickChunkFromFile` |
| Go-to-definition / Find-references (6+6 chunks) | Not used — chunk ring covers this |
| Diagnostics (50 items) | Not included |

**Ring size**: 16 chunks (`ringNChunks` setting), 64 lines each (`ringChunkSize`).
All chunks are included in the prompt — no filtering by recency or similarity at prompt time.

---

## 3. Prompt Format

Both context sources use the SweepAI `<|file_sep|>` format:

```
<|file_sep|>{chunk_1.filename}
{chunk_1.text}
...
<|file_sep|>{diff_1.filePath}.diff
original:
{diff_1.original}
updated:
{diff_1.updated}
...
<|file_sep|>original/{filePath}
{originalContent}
<|file_sep|>current/{filePath}
{windowedContent}
<|file_sep|>updated/{filePath}
```

Chunks appear before diffs in the prompt (more stable → better KV cache prefix).
Diffs appear before the original/current/updated sections (closer to generation point).

---

## 4. FimRequest Integration

```kotlin
data class FimRequest(
    val filePath: String,
    val currentContent: String,
    val originalContent: String = "",
    val cursorLine: Int,
    val windowedContent: String = "",
    val windowStartLine: Int = 0,
    val recentDiffs: List<DiffEntry> = emptyList(),
    val chunks: List<Chunk> = emptyList()
)
```

In `FimSuggestionManager.showSuggestion()`:
```kotlin
val recentDiffs = project.service<DiffTracker>().getRecentDiffs()
val chunks = project.service<ChunksRingBuffer>().getRingChunks().toList()
val request = buildFimRequest(..., recentDiffs = recentDiffs, chunks = chunks)
```

---

## 5. Settings

| Setting | Default | Role |
|---------|---------|------|
| `ringNChunks` | 16 | Max chunks collected and included |
| `ringChunkSize` | 64 | Lines per chunk |
| `maxRecentDiffs` | 10 | Max diffs stored and included |

---

## Files

| File | Role |
|------|------|
| `services/DiffTracker.kt` | Edit capture, coalescing, finalization |
| `completion/fim.kt` | `FimRequest`, `buildFimPrompt`, `truncateDiffText` |
| `fim/FimSuggestionManager.kt` | Wiring: fetch diffs + chunks, pass to request |
| `services/ChunksRingBuffer.kt` | Ring buffer (existing, unchanged) |
