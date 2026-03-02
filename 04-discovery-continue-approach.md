# Discovery: Continue.dev's Approach to Next Edit Prediction

## Overview

Continue.dev uses a multi-layered system with explicit **edit chains** (sequences of related
predictions), **edit clusters** (groups of rapid keystrokes), and **document history snapshots**.
Unlike Zed's implicit coalescing, Continue has more formal objects managing prediction lifecycle.

---

## 1. Edit State Tracking

### Layer 1: Document Content Cache (VSCode Side)

In `extensions/vscode/src/util/editLoggingUtils.ts`, a `documentContentCache` (`Map<string,
string>`) stores the **pre-edit content** for every open document. On every
`vscode.workspace.onDidChangeTextDocument`:

1. Pre-edit content is read from the cache
2. Each change is packaged into a `RangeInFileWithNextEditInfo` object (filepath, range,
   pre/post-edit contents, edit text, cursor positions, workspace directory)
3. Cache is updated to the new content
4. Edit actions are sent to the core via `this.core.invoke("files/smallEdit", editInfo)`

### Layer 2: EditAggregator (Core Side)

The `EditAggregator` singleton (`/core/nextEdit/context/aggregateEdits.ts`) maintains per-file
state:

```typescript
Map<string, FileState> where FileState = {
    activeClusters: ClusterState[],    // groups of related edits
    currentContent: string,            // latest file content
    priorComparisons: string[],        // previous finalized diffs
    processingQueue: Array<() => Promise<void>>
}
```

A `ClusterState` captures:

```typescript
{
    beforeState: string,              // content before the cluster began
    startRange / currentRange,        // line ranges of the cluster
    edits: RangeInFileWithNextEditInfo[],
    firstTimestamp / lastTimestamp,    // temporal bounds
    lastLine: number,
    firstEditBeforeCursor / lastEditAfterCursor
}
```

### Layer 3: DocumentHistoryTracker (Snapshots)

The `DocumentHistoryTracker` singleton (`/core/nextEdit/DocumentHistoryTracker.ts`) maintains
per-file LIFO stacks of `(content, AST)` pairs. A snapshot is pushed when an edit chain is
deleted. A `historyDiff` (diff between most recent snapshot and current file) provides the model
with longer-range context about how the file has changed since the last prediction cycle.

---

## 2. Edit Clustering — Grouping Keystrokes

### Configuration

```typescript
deltaT: 1.0,       // seconds — time threshold for new cluster
deltaL: 5,         // lines — spatial jump threshold
maxEdits: 500,     // max edits in a cluster
maxDuration: 120.0, // seconds — max cluster lifetime
contextSize: 5,    // number of prior comparisons to retain
contextLines: 3    // lines of context around each edit
```

### Finalization Rules

A cluster is finalized (producing a diff) when:

1. **Time gap** — More than `deltaT` (1 second) since the last edit
2. **Line jump** — New edit is more than `deltaL` (5 lines) from the cluster
3. **Max edits exceeded** — Cluster has 500+ edits
4. **Max duration exceeded** — Cluster has been open for 120+ seconds

On finalization, the `onComparisonFinalized` callback fires with a `BeforeAfterDiff`, which
calls `processSmallEdit()` to create a unified diff and push it into the model's context.

### In-Progress Diff Capture

`EditAggregator.getInProgressDiff()` can produce a diff for edits that **haven't been finalized
yet** (the user is still typing). This is included during prompt generation to give the model
the most recent context:

```typescript
const inProgressDiff = EditAggregator.getInstance().getInProgressDiff(helper.filepath);
if (inProgressDiff) {
    combinedDiffContext.push(inProgressDiff);
}
```

---

## 3. Edit Chains — The Session Concept

Continue's core session concept is the **edit chain**, managed by `NextEditProvider`:

- `currentEditChainId: string | null` — UUID identifying the current chain
- `previousCompletions: NextEditOutcome[]` — stack of completions in this chain
- `startChain()` — creates a new chain with a UUID
- `deleteChain()` — clears the chain, aborts prefetch, pushes file state into
  `DocumentHistoryTracker`

### Chain Lifecycle via SelectionChangeManager

The `SelectionChangeManager` coordinates cursor movement events to decide whether to **preserve
or delete** the edit chain:

| User Action                        | Chain Effect       |
|------------------------------------|--------------------|
| Just typed (within 2s window)      | Preserve chain     |
| Just accepted an edit              | Preserve chain     |
| Mid-jump to next edit location     | Preserve chain     |
| Cursor move without typing         | **Delete chain**   |

Chain deletion/creation implicitly determines when new predictions are triggered.

### Three-Case State Machine

The `ContinueCompletionProvider.provideInlineCompletionItems()` method runs a three-case state
machine:

1. **Case 1: Typing (no chain)** — Start a new chain, call `NextEditProvider` for a prediction
2. **Case 2: Jumping (chain exists, jump taken)** — Retrieve prediction from
   `JumpManager.completionAfterJump` (no model call)
3. **Case 3: Accepting (chain exists, no jump)** — Suggest a "jump" to the next predicted edit
   from the prefetch queue

---

## 4. Prompt Format

Continue uses **chat-style prompts** (system + user messages), NOT standard FIM. Two model
backends are supported:

### Mercury Coder Format

Template from `/core/nextEdit/templating/NextEditPromptEngine.ts`:

```
<|recently_viewed_code_snippets|>
{recently viewed snippets with filepath and content}
<|/recently_viewed_code_snippets|>

<|current_file_content|>
current_file_path: {filepath}
{file content with <|code_to_edit|>...<|/code_to_edit|> markers and <|cursor|> token}
<|/current_file_content|>

<|edit_diff_history|>
{unified diffs of recent edits}
<|/edit_diff_history|>
```

Special tokens:
- `<|code_to_edit|>` / `<|/code_to_edit|>` — editable region boundaries
- `<|cursor|>` — cursor position
- `<|IS_NEXT_EDIT!@#|>` — identifies this as a next-edit request

The model outputs revised code in markdown code blocks. Extraction strips the fences:

```typescript
extractCompletion(message: string): string {
    return message.slice(
        message.indexOf("```\n") + "```\n".length,
        message.lastIndexOf("\n```"),
    );
}
```

### Instinct Format

Uses Zed-style markers:

```
### Context:
{context snippets with <|context_file|> and <|snippet|> markers}

### User Edits:
{unified diffs: User edited file "filename"\n```diff\n...\n```}

### User Excerpt:
{filepath}
{file content with <|editable_region_start|>...<|editable_region_end|> and <|user_cursor_is_here|>}
### Response:
```

### Editable Region Sizes

| Model          | Lines Above Cursor | Lines Below Cursor |
|----------------|--------------------|--------------------|
| Mercury Coder  | 0                  | 5                  |
| Instinct       | 1                  | 5                  |
| Full file diff | Token-budget-based (512 tokens, expand alternately above/below) |

---

## 5. Rendering in the Editor

### FIM Detection

The `checkFim()` function determines if the prediction is purely additive at the cursor:

```typescript
const { isFim, fimText } = checkFim(oldEditRangeSlice, newEditRangeSlice, relativeCursorPos);
```

### VSCode: Two Rendering Paths

**FIM case (insert at cursor):**
- Standard `vscode.InlineCompletionItem` — dimmed ghost text at the cursor
- Tab accepts via the standard inline completion API

**Non-FIM case (structural edit):**
- `NextEditWindowManager` computes character-level diffs via `myersCharDiff()`
- Renders an **SVG image** of the predicted code using `CodeRenderer` (syntax highlighting via
  Shiki)
- Creates a `TextEditorDecorationType` with the SVG as `contentIconPath`, positioned at the
  cursor's line
- **Deletion decorations** — characters to be deleted get a red background
  (`rgba(255, 0, 0, 0.5)`)
- **Tab and Esc** are reserved via `nextEditWindowActive` VSCode context
- Acceptance replaces `editableRegionStartLine..editableRegionEndLine` with predicted text

### JetBrains

- `NextEditService.kt` communicates with the core via `CoreMessenger` message passing
  (`nextEdit/predict`, `nextEdit/startChain`, `nextEdit/deleteChain`, etc.)
- `NextEditWindowManager.kt` and `NextEditWindowHandler.kt` handle rendering
- `NextEditJumpManager.kt` handles jump suggestions
- Uses IntelliJ's `InlineCompletionRequest` API

---

## 6. Diff Computation and Edit Application

### Two Diff Algorithms

1. **Unified diff** (`createPatch` from `diff` npm library) — for prompt context and cluster
   finalization. Standard `+`/`-` lines with `@@` hunks.

2. **Myers diff** (`myersDiff` from `/core/diff/myers.ts`) — for comparing old/new editable
   regions. Produces `DiffLine[]` arrays with types `"old"`, `"new"`, `"same"`. Also
   `myersCharDiff` for character-level rendering.

### Diff Grouping

`groupDiffLines()` groups consecutive changed lines into `DiffGroup` objects:
- The group containing the cursor renders immediately
- Other groups are enqueued into the `PrefetchQueue` for jump suggestions

### Edit Application

```typescript
// VSCode
editor.edit((editBuilder) => editBuilder.replace(editRange, text));
// Line deletions
editBuilder.delete(lineDeleteRange);
```

### Cursor Positioning

`calculateFinalCursorPosition()` uses Myers diff to find the last `"new"` line, then places
the cursor at its end, offset by the editable region's start line.

---

## 7. PrefetchQueue — Background Prediction Cache

`PrefetchQueue` (`/core/nextEdit/NextEditPrefetchQueue.ts`) was designed for multi-edit
predictions:

```typescript
{
    unprocessedQueue: RangeInFile[],     // locations needing predictions
    processedQueue: ProcessedItem[],     // pre-computed predictions ready to display
    prefetchLimit: 3                     // max prefetched items
}
```

The comments in the code indicate this was **partially abandoned** due to "subpar results, lack
of satisfactory next edit location suggestion algorithms and token cost/latency issues."

---

## 8. Throttling, Debouncing, and Anti-Thrash

### A. AutocompleteDebouncer

```typescript
// Default debounce delay: 350ms
await this.debouncer.delayAndShouldDebounce(options.debounceDelay);
```

UUID-based — each call generates a unique ID. If a newer request arrives before the timeout,
the older one is debounced.

### B. SelectionChangeManager Debouncing

```typescript
DEBOUNCE_DELAY = 50;        // ms between selection changes
PROCESSING_TIMEOUT = 500;   // ms max for handler execution
```

Events within 50ms are coalesced. 500ms timeout prevents deadlocks.

### C. Typing Session Detection

```typescript
TYPING_SESSION_TIMEOUT = 2000; // ms
```

`documentChanged()` sets `isTypingSession = true` on every keystroke. A 2-second timer resets
it, preventing chain deletion during active typing.

### D. EditAggregator Queue Throttling

```typescript
// When queue exceeds 50 items, drop intermediates — process only the latest
if (this.getProcessingQueueSize() > 50) {
    await this.processEdit(edits[edits.length - 1], timestamp);
    return;
}
```

Batches of 5 with `setTimeout(resolve, 0)` to yield to the event loop.

### E. Whitespace-Only Diff Filtering

```typescript
const isWhitespaceOnlyDiff =
    beforeContent.replace(/\s+/g, "") === afterContent.replace(/\s+/g, "");
```

### F. Diff Size Limits

Clusters whose diffs exceed `deltaL * 2` changed lines are silently dropped.

### G. Abort Signal Chain

- Each request gets an `AbortController`
- VSCode's `CancellationToken` wired to `abortController.abort()`
- `PrefetchQueue.abort()` aborts its controller and clears all queues
- Chain deletion calls `PrefetchQueue.abort()`

### H. Stale Edit Cleanup

```typescript
// prevEditLruCache expires edits older than 10 minutes
if (timestamp - prevEdits[0].timestamp >= 1000 * 60 * 10) {
    prevEditLruCache.clear();
}
```

---

## 9. Data Flow

```
User types / moves cursor
        │
        ▼
vscode.workspace.onDidChangeTextDocument
  └─ documentContentCache stores pre-edit content
  └─ Package RangeInFileWithNextEditInfo
  └─ core.invoke("files/smallEdit", editInfo)
        │
        ▼
EditAggregator.processEdits()
  ├─ Coalesce into ClusterState (5 lines, 1 sec, 500 edits max)
  └─ On finalization: compute unified diff → processSmallEdit()
        │
        ▼
NextEditProvider.addDiffToContext(diff)
  └─ Rolling buffer of last 5 diffs
        │
        ▼
ContinueCompletionProvider (VSCode inline completion trigger)
  ├─ Case 1 (typing): startChain() → NextEditProvider.provideInlineCompletionItems()
  ├─ Case 2 (jumping): retrieve from JumpManager
  └─ Case 3 (accepting): suggest jump from PrefetchQueue
        │
        ▼
NextEditProvider._initializeCompletionRequest()
  ├─ Debounce 350ms
  ├─ Collect: finalized diffs + in-progress diff + history diff
  └─ Build prompt (Mercury Coder or Instinct format)
        │
        ▼
Model inference
        │
        ▼
checkFim() — is the edit purely additive at cursor?
  ├─ Yes (FIM): return InlineCompletionItem (ghost text)
  └─ No (structural): NextEditWindowManager (SVG + decorations)
        │
        ▼
User accepts (Tab) or dismisses (Esc)
  ├─ Accept: apply edit, suggest jump to next location
  └─ Dismiss: clear decorations, delete chain
```

---

## 10. Key Takeaways for Our Implementation

### What Continue Gets Right

1. **Explicit edit chains.** A UUID-tagged chain provides clear lifecycle management —
   easier to reason about than Zed's implicit coalescing.

2. **Cluster-based edit aggregation.** Grouping rapid keystrokes into logical units before
   computing diffs keeps the prompt context meaningful rather than noisy.

3. **In-progress diff capture.** Including the user's not-yet-finalized typing in the prompt
   gives the model the freshest possible context.

4. **FIM detection.** Automatically falling back to standard ghost text for simple insertions
   avoids the complexity of custom rendering when it isn't needed.

5. **Document history snapshots.** The LIFO stack of (content, AST) pairs per file provides
   longer-range context about how the file has evolved.

### What's Problematic

1. **PrefetchQueue partially abandoned.** Multi-edit jump suggestions didn't work well enough —
   suggests this is harder than it looks and should be deferred.

2. **SVG rendering for structural edits.** Generating syntax-highlighted SVG images is
   creative but fragile (theme detection, caching, sizing) — IntelliJ's Inlay API may be
   cleaner.

3. **Complex state machine.** Three cases + SelectionChangeManager + chain lifecycle + queue
   throttling creates many interacting states. Simpler is better for a first pass.

### Design Decisions to Consider

- **Explicit chain IDs** for lifecycle management (start/stop/preserve/delete)
- **Cluster aggregation** with configurable spatial and temporal thresholds
- **FIM fallback** — detect when the prediction is cursor-insert-only and use the simpler API
- **Rolling diff buffer** (5 recent diffs) for prompt context
- **350ms debounce** as the prediction trigger delay

---

## Sources

### Blog Posts
- [Next Edit Powered by Mercury Coder](https://blog.continue.dev/next-edit-powered-by-mercury-coder/)
- [Introducing Instinct](https://blog.continue.dev/instinct/)

### Documentation
- [Continue: Next Edit Docs](https://docs.continue.dev/ide-extensions/autocomplete/next-edit)

### Source Code (github.com/continuedev/continue)
- `/core/nextEdit/NextEditProvider.ts` — Main orchestrator
- `/core/nextEdit/providers/BaseNextEditProvider.ts` — Abstract base, editable region calculation
- `/core/nextEdit/providers/MercuryCoderNextEditProvider.ts` — Mercury prompt building
- `/core/nextEdit/providers/InstinctNextEditProvider.ts` — Instinct prompt building
- `/core/nextEdit/context/aggregateEdits.ts` — EditAggregator, cluster logic
- `/core/nextEdit/context/diffFormatting.ts` — Unified diff creation
- `/core/nextEdit/context/processSmallEdit.ts` — Cluster finalization to diff
- `/core/nextEdit/DocumentHistoryTracker.ts` — Per-file LIFO snapshots
- `/core/nextEdit/diff/diff.ts` — Myers diff, FIM detection, diff grouping
- `/core/nextEdit/templating/NextEditPromptEngine.ts` — Handlebars prompt templates
- `/core/nextEdit/NextEditPrefetchQueue.ts` — Background prediction queue (partially abandoned)
- `/core/nextEdit/constants.ts` — Special tokens, system prompts, window sizes
- `/core/autocomplete/util/AutocompleteDebouncer.ts` — UUID-based debounce
- `/extensions/vscode/src/autocomplete/completionProvider.ts` — VSCode entry point
- `/extensions/vscode/src/activation/NextEditWindowManager.ts` — SVG rendering, key reservation
- `/extensions/vscode/src/activation/SelectionChangeManager.ts` — Chain lifecycle management
- `/extensions/vscode/src/util/editLoggingUtils.ts` — Pre/post content caching

### GitHub Issues
- [Issue #4354: Add Next Edit Prediction](https://github.com/continuedev/continue/issues/4354)
- [Discussion #7308: Next Edit Prediction](https://github.com/continuedev/continue/discussions/7308)
- [Issue #8590: Instinct System Prompt](https://github.com/continuedev/continue/issues/8590)
