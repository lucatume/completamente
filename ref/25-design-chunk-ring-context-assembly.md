# Design: Chunk Ring Context Assembly for FIM/NEP Prompts

## Overview

Wire the existing chunk ring (clipboard, open buffers, recently edited files) and recent diffs
into the FIM/NEP prompt sent to the llama.cpp server. The chunk ring replaces the separate
context sources used by the Sweep AI implementation (clipboard, recent buffers, LSP definitions,
LSP usages) with a single unified source.

---

## 1. Context Sources and Limits

### Chunk Ring (extra context files)

All extra file context comes from the existing `ChunksRingBuffer` service. This replaces the
Sweep AI implementation's separate sources:

| Sweep AI source             | Sweep limit | Our equivalent          |
|-----------------------------|-------------|-------------------------|
| Clipboard                   | 1 chunk, 20 lines | Chunk ring (via `pickChunkFromText`) |
| Open/visible editor buffers | 3 chunks, 60 lines each | Chunk ring (via `pickChunkFromFile`) |
| Go-to-definition (LSP)      | 6 chunks | Not used — chunk ring covers this |
| Find-references (LSP)       | 6 chunks | Not used — chunk ring covers this |
| Diagnostics                 | 50 items | Not included in prompt |

**Ring size limit: 16 chunks** (`ringNChunks` setting, already defaulting to 16).

All 16 chunks are included in the prompt. No filtering by recency, similarity, or source file is
applied — the ring's existing eviction and similarity-based deduplication handles quality.

### Recent Diffs

| Setting | Value | Description |
|---------|-------|-------------|
| Max number of diffs | **10** | Matches Sweep AI's `maxEditHistory = 10`. Already the default in `maxRecentDiffs`. |
| Max characters per diff | **20,000** | Per-diff truncation. Diffs exceeding this are truncated. **New limit to implement.** |

Diffs come from the existing `DiffTracker` service via `getRecentDiffs()`.

---

## 2. Prompt Format

The prompt follows the Sweep AI `<|file_sep|>` format, matching the model's training
distribution. The ordering is:

```
1. Chunk ring entries          ← extra context files (NEW)
2. Recent diffs                ← edit history (existing)
3. Original file content       ← before most recent change (existing)
4. Current file content        ← file as it is now (existing)
5. Updated file marker         ← model generates after this (existing)
```

### Full prompt template

```
<|file_sep|>{chunk_1.filename}
{chunk_1.text}
<|file_sep|>{chunk_2.filename}
{chunk_2.text}
...
<|file_sep|>{chunk_N.filename}
{chunk_N.text}
<|file_sep|>{diff_1.filePath}.diff
original:
{diff_1.original}
updated:
{diff_1.updated}
...
<|file_sep|>{diff_M.filePath}.diff
original:
{diff_M.original}
updated:
{diff_M.updated}
<|file_sep|>original/{filePath}
{originalContent}
<|file_sep|>current/{filePath}
{windowedContent}
<|file_sep|>updated/{filePath}
```

Where:
- `N` = up to 16 (from chunk ring)
- `M` = up to 10 (from diff tracker)
- Each diff's `original`/`updated` text is truncated to 20,000 characters max

---

## 3. Data Flow Changes

### Current flow

```
FimSuggestionManager.showSuggestion(editor)
  ├─ DiffTracker.getRecentDiffs()           → List<DiffEntry>
  ├─ buildFimRequest(filePath, content, cursorLine, recentDiffs)
  │     → FimRequest(filePath, content, originalContent, cursorLine, windowedContent, recentDiffs)
  ├─ buildFimPrompt(request)
  │     → diffs + original/{path} + current/{path} + updated/{path}
  └─ requestFimCompletion(serverUrl, request)
```

### New flow

```
FimSuggestionManager.showSuggestion(editor)
  ├─ DiffTracker.getRecentDiffs()           → List<DiffEntry>
  ├─ ChunksRingBuffer.getRingChunks()       → List<Chunk>          ← NEW
  ├─ buildFimRequest(filePath, content, cursorLine, recentDiffs, chunks)
  │     → FimRequest(..., chunks)
  ├─ buildFimPrompt(request)
  │     → chunks + diffs + original/{path} + current/{path} + updated/{path}
  └─ requestFimCompletion(serverUrl, request)
```

---

## 4. Code Changes

### 4.1 `FimRequest` data class (`completion/fim.kt`)

Add a `chunks` field:

```kotlin
data class FimRequest(
    val filePath: String,
    val currentContent: String,
    val originalContent: String = "",
    val cursorLine: Int,
    val windowedContent: String = "",
    val windowStartLine: Int = 0,
    val recentDiffs: List<DiffEntry> = emptyList(),
    val chunks: List<Chunk> = emptyList()           // NEW
)
```

### 4.2 `buildFimRequest()` function (`completion/fim.kt`)

Add `chunks` parameter:

```kotlin
fun buildFimRequest(
    filePath: String,
    currentContent: String,
    cursorLine: Int,
    originalContent: String = "",
    recentDiffs: List<DiffEntry> = emptyList(),
    chunks: List<Chunk> = emptyList()                // NEW
): FimRequest {
    val lines = currentContent.lines()
    val (windowedContent, windowStartLine) = buildLineWindowWithStart(lines, cursorLine)
    return FimRequest(
        filePath = filePath,
        currentContent = currentContent,
        originalContent = originalContent,
        cursorLine = cursorLine,
        windowedContent = windowedContent,
        windowStartLine = windowStartLine,
        recentDiffs = recentDiffs,
        chunks = chunks                              // NEW
    )
}
```

### 4.3 `buildFimPrompt()` function (`completion/fim.kt`)

Add chunk rendering before diffs:

```kotlin
fun buildFimPrompt(request: FimRequest): String {
    val windowedContent = if (request.windowedContent.isNotEmpty()) {
        request.windowedContent
    } else {
        buildLineWindow(request.currentContent.lines(), request.cursorLine)
    }

    val parts = mutableListOf<String>()

    // Chunk ring entries (extra context files)
    for (chunk in request.chunks) {
        parts.add("<|file_sep|>${chunk.filename}")
        parts.add(chunk.text)
    }

    // Recent diffs
    for (diff in request.recentDiffs) {
        parts.add("<|file_sep|>${diff.filePath}.diff")
        parts.add("original:")
        parts.add(diff.original)
        parts.add("updated:")
        parts.add(diff.updated)
    }

    parts.add("<|file_sep|>original/${request.filePath}")
    parts.add(request.originalContent)
    parts.add("<|file_sep|>current/${request.filePath}")
    parts.add(windowedContent)
    parts.add("<|file_sep|>updated/${request.filePath}")

    return parts.joinToString("\n")
}
```

### 4.4 Per-diff truncation (`completion/fim.kt`)

Add a constant and truncation helper:

```kotlin
const val MAX_DIFF_CHARS = 20_000

fun truncateDiffText(text: String): String {
    if (text.length <= MAX_DIFF_CHARS) return text
    return text.substring(0, MAX_DIFF_CHARS) + "\n...[truncated]"
}
```

Apply in `buildFimPrompt` when rendering diffs:

```kotlin
parts.add("original:")
parts.add(truncateDiffText(diff.original))
parts.add("updated:")
parts.add(truncateDiffText(diff.updated))
```

### 4.5 `FimSuggestionManager.showSuggestion()` (`fim/FimSuggestionManager.kt`)

Fetch chunks from the ring buffer and pass them through:

```kotlin
fun showSuggestion(editor: Editor) {
    // ... existing code ...

    val diffTracker = project.service<DiffTracker>()
    val recentDiffs = diffTracker.getRecentDiffs()

    val chunksRingBuffer = project.service<ChunksRingBuffer>()    // NEW
    val chunks = chunksRingBuffer.getRingChunks().toList()         // NEW

    val request = buildFimRequest(
        filePath = filePath,
        currentContent = text,
        cursorLine = cursorLine,
        recentDiffs = recentDiffs,
        chunks = chunks                                            // NEW
    )

    // ... rest unchanged ...
}
```

---

## 5. Settings

No new settings are required. Existing settings already match the target:

| Setting | Default | Role |
|---------|---------|------|
| `ringNChunks` | 16 | Max chunks collected and included in prompt |
| `ringChunkSize` | 64 | Lines per chunk when picking |
| `maxQueuedChunks` | 16 | Queue size for pending chunks |
| `maxRecentDiffs` | 10 | Max diffs stored and included in prompt |

---

## 6. What Is NOT Included

- **LSP-based context** (go-to-definition, find-references): not implemented, chunk ring covers this
- **Diagnostics**: not included in the prompt
- **Clipboard-specific handling**: clipboard chunks go through the same chunk ring as all other sources, no synthetic filename like Sweep's `"clipboard.txt"`
- **Chunk selection/filtering**: all ring chunks are included, no similarity scoring or recency filtering at prompt time
- **Token counting**: no tokenizer or token budget, matching Sweep's structural-limits approach
