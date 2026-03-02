# Design: Document-Layer Diff Tracking

## Goal

Track recent edits across all open buffers and include them as diff context in FIM/NEP completion
requests. This gives the model visibility into what the developer has been doing recently, enabling
better next-edit predictions.

---

## 1. Data Model

### StoredDiff — A single finalized edit event

```kotlin
data class StoredDiff(
    val filePath: String,
    val timestamp: Long,                    // System.currentTimeMillis() when finalized
    val beforeSnapshot: CharSequence,       // immutable text before the edit group
    val afterSnapshot: CharSequence,        // immutable text after the edit group
    val startLine: Int,                     // first line affected (for coalescing check)
    val endLine: Int                        // last line affected (for coalescing check)
)
```

### PendingEdit — The currently-accumulating edit (not yet finalized)

```kotlin
data class PendingEdit(
    val filePath: String,
    val beforeSnapshot: CharSequence,       // text captured before the first edit in this group
    val startLine: Int,                     // lowest line touched so far
    val endLine: Int,                       // highest line touched so far
    val lastEditTime: Long                  // timestamp of the most recent edit in this group
)
```

### DiffEntry — The computed diff representation sent in the prompt

Following the format established in the harness test files (08 through 13), each diff uses
the `original:`/`updated:` block format under a `<|file_sep|>{filePath}.diff` header.

```kotlin
data class DiffEntry(
    val filePath: String,
    val original: String,                   // text snippet before the edit
    val updated: String                     // text snippet after the edit
)
```

---

## 2. DiffTracker — Project-level service

A new `@Service(Service.Level.PROJECT)` class that owns the rolling diff history.

```
File: src/main/kotlin/com/github/lucatume/completamente/services/DiffTracker.kt
```

### State

```kotlin
@Service(Service.Level.PROJECT)
class DiffTracker(private val project: Project) : DocumentListener, Disposable {
    private val storedDiffs: ArrayDeque<StoredDiff> = ArrayDeque()
    private val pendingEdits: MutableMap<String, PendingEdit> = mutableMapOf()  // keyed by filePath

    // Read from SettingsState; default 10
    private val maxDiffs: Int
        get() = SettingsState.getInstance().maxRecentDiffs
}
```

### Registration

Registered in `CompletamenteStartupActivity` alongside the existing `FimTypingListener`:

```kotlin
val diffTracker = project.service<DiffTracker>()
EditorFactory.getInstance().eventMulticaster.addDocumentListener(diffTracker, diffTracker)
```

---

## 3. Edit Capture Flow

### 3.1 beforeDocumentChange(event: DocumentEvent)

```
1. Resolve filePath from event.document → VirtualFile
2. If no PendingEdit exists for this filePath:
   a. Capture beforeSnapshot = document.immutableCharSequence
   b. Compute editLine = document.getLineNumber(event.offset)
   c. Create PendingEdit(filePath, beforeSnapshot, editLine, editLine, now)
3. If PendingEdit exists for this filePath:
   a. Do nothing here (coalescing check happens in documentChanged)
```

### 3.2 documentChanged(event: DocumentEvent)

```
1. Resolve filePath from event.document → VirtualFile
2. Compute editLine = document.getLineNumber(event.offset)
3. Look up existing PendingEdit for this filePath

4. If PendingEdit exists:
   a. Check coalescing:
      - sameFile = true (same filePath)
      - nearbyEdit = |editLine - pending.startLine| <= COALESCE_LINE_SPAN
                  OR |editLine - pending.endLine| <= COALESCE_LINE_SPAN

   b. If sameFile AND nearbyEdit:
      - Expand: pending.startLine = min(pending.startLine, editLine)
      - Expand: pending.endLine = max(pending.endLine, editLine)
      - Update: pending.lastEditTime = now
      (Edit is coalesced into the existing group — no new diff created)

   c. If NOT nearby (distant edit in same file) OR different file:
      - Finalize the existing PendingEdit (see §3.3)
      - Start a new PendingEdit as in beforeDocumentChange step 2

5. If no PendingEdit exists:
   - This shouldn't happen (beforeDocumentChange always runs first)
   - Defensive: create PendingEdit from current state
```

### 3.3 Finalizing a PendingEdit → StoredDiff

```
1. Capture afterSnapshot = document.immutableCharSequence
2. Create StoredDiff from pending + afterSnapshot
3. Push onto storedDiffs deque
4. If storedDiffs.size > maxDiffs: removeFirst() (drop oldest)
5. Remove the PendingEdit from pendingEdits map
```

### 3.4 Temporal boundary: pause detection

A scheduled check (via `Alarm`) runs every 1 second:

```
For each PendingEdit in pendingEdits:
    If (now - pending.lastEditTime) > PAUSE_THRESHOLD_MS:
        Finalize the PendingEdit → StoredDiff
```

This ensures that when the user stops typing, the accumulated edit group is finalized
even if no new edit arrives to trigger the coalescing check.

---

## 4. Constants

```kotlin
const val COALESCE_LINE_SPAN = 8          // edits within 8 lines coalesce (from Zed)
const val PAUSE_THRESHOLD_MS = 1_000L     // 1-second pause finalizes a group (from Zed)
const val DEFAULT_MAX_RECENT_DIFFS = 10   // max stored diffs (from Sweep)
```

---

## 5. Diff Computation

When building the FIM prompt, compute diffs lazily from `StoredDiff` entries.

### Function: computeDiffEntries()

```kotlin
fun computeDiffEntries(diffs: List<StoredDiff>): List<DiffEntry>
```

For each `StoredDiff`, extract the changed region as `original:`/`updated:` snippet pairs.
Two approaches, from simplest to most precise:

**Approach A — Full snapshot as snippet (simplest):**
Store the entire `beforeSnapshot` and `afterSnapshot` as the original/updated strings.
Simple but may send large blocks when only a few lines changed.

**Approach B — Line-level diff extraction (recommended):**
Use `ComparisonManager.getInstance().compareLines()` to identify changed line ranges, then
extract only the changed lines (plus a few lines of context) from each snapshot. This
produces compact snippets matching what the harness tests use — short, focused blocks like:

```
original:
    result = a + b
updated:
    total = a + b
```

### Where it plugs in — Prompt format

Following the format from harness files 08–13 (`build_prompt()` in
`08-harness-test-sweepai-model.py`), each diff gets its own `<|file_sep|>` entry with a
`.diff` suffix, placed **between** context files and the original/current/updated sections:

```
<|file_sep|>{contextFilePath}              — context files (existing)
{contextContent}
<|file_sep|>{diffFilePath}.diff            — recent diffs (NEW)
original:
{original snippet}
updated:
{updated snippet}
<|file_sep|>{diffFilePath2}.diff           — another diff (NEW)
original:
{original snippet}
updated:
{updated snippet}
<|file_sep|>original/{filePath}            — file before most recent change (existing)
{originalContent}
<|file_sep|>current/{filePath}             — current file state (existing)
{windowedContent}
<|file_sep|>updated/{filePath}             — model generates this (existing)
```

Multiple diffs for the same file each get their own `<|file_sep|>{path}.diff` entry,
in reverse chronological order (most recent first).

### Data class changes

The `FimRequest` data class gains a new field:

```kotlin
data class FimRequest(
    val filePath: String,
    val currentContent: String,
    val originalContent: String = "",
    val cursorLine: Int,
    val windowedContent: String = "",
    val windowStartLine: Int = 0,
    val recentDiffs: List<DiffEntry> = emptyList()   // NEW
)
```

### buildFimPrompt() changes

```kotlin
fun buildFimPrompt(request: FimRequest): String {
    val parts = mutableListOf<String>()

    // 1. Context files (existing, unchanged)
    // ...

    // 2. Recent diffs — NEW
    for (diff in request.recentDiffs) {
        parts.add("<|file_sep|>${diff.filePath}.diff")
        parts.add("original:")
        parts.add(diff.original)
        parts.add("updated:")
        parts.add(diff.updated)
    }

    // 3. Original/current/updated (existing, unchanged)
    parts.add("<|file_sep|>original/${request.filePath}")
    parts.add(request.originalContent)
    parts.add("<|file_sep|>current/${request.filePath}")
    parts.add(windowedContent)
    parts.add("<|file_sep|>updated/${request.filePath}")

    return parts.joinToString("\n")
}
```

---

## 6. Integration Points

### 6.1 FimSuggestionManager.showSuggestion()

Before building the request, fetch recent diffs:

```kotlin
val diffTracker = project.service<DiffTracker>()
val recentDiffs = diffTracker.getRecentDiffs()    // returns List<DiffEntry>

val request = buildFimRequest(
    filePath = filePath,
    currentContent = text,
    cursorLine = cursorLine,
    recentDiffs = recentDiffs                      // pass diffs into request
)
```

### 6.2 DiffTracker ignores its own edits

When `FimSuggestionManager.acceptSuggestion()` applies an edit via `WriteCommandAction`, the
`DiffTracker` should **not** record it as a user edit. Use a boolean flag:

```kotlin
// In DiffTracker
var ignoreNextChange: Boolean = false

override fun beforeDocumentChange(event: DocumentEvent) {
    if (ignoreNextChange) return
    // ...
}

override fun documentChanged(event: DocumentEvent) {
    if (ignoreNextChange) {
        ignoreNextChange = false
        return
    }
    // ...
}
```

Set `ignoreNextChange = true` in `applyEdit()` before the `replaceString` call.

### 6.3 File open/close

When a file is opened, register its initial content as the baseline — but don't create a diff.
When a file is closed, finalize any pending edit for that file and clean up.

Hook into the existing `FileOpenCloseService`:

```kotlin
fileOpenCloseService.onFileOpened { event ->
    diffTracker.registerFileOpened(event.file)
}
fileOpenCloseService.onFileClosed { event ->
    diffTracker.finalizePendingEdit(event.file.path)
}
```

---

## 7. Settings

### SettingsState — add field

```kotlin
// In SettingsState.kt
var maxRecentDiffs: Int = 10
```

### Settings — add field

```kotlin
// In Settings.kt
data class Settings(
    // ... existing fields ...
    val maxRecentDiffs: Int = 10
)
```

### SettingsConfigurable — add UI row

Add to the "FIM Suggestions" group:

```kotlin
row("Recent diffs:") {
    textField()
        .bindText(::maxRecentDiffs)
        .comment("Max number of recent edit diffs sent as context (0 to disable)")
}
```

---

## 8. File Structure

New files:
```
src/main/kotlin/com/github/lucatume/completamente/services/DiffTracker.kt
```

Modified files:
```
src/main/kotlin/com/github/lucatume/completamente/completion/fim.kt          — FimRequest + buildFimPrompt
src/main/kotlin/com/github/lucatume/completamente/fim/FimSuggestionManager.kt — pass diffs to request
src/main/kotlin/com/github/lucatume/completamente/services/SettingsState.kt   — maxRecentDiffs field
src/main/kotlin/com/github/lucatume/completamente/services/Settings.kt        — maxRecentDiffs field
src/main/kotlin/com/github/lucatume/completamente/settings/SettingsConfigurable.kt — UI row
src/main/kotlin/com/github/lucatume/completamente/startup/CompletamenteStartupActivity.kt — register DiffTracker
```

Test files:
```
src/test/kotlin/com/github/lucatume/completamente/services/DiffTrackerTest.kt
```

---

## 9. Lifecycle & Thread Safety

- `DiffTracker` implements `Disposable`. On dispose: cancel the pause-detection alarm, clear all
  state.
- `DocumentListener` callbacks run on the EDT (write thread). All state mutations happen on the
  EDT, so no synchronization is needed for `storedDiffs` and `pendingEdits`.
- `getRecentDiffs()` is called from `showSuggestion()` which starts on EDT then moves to a pooled
  thread. The method should return a **copy** of the list (snapshot) to avoid concurrent
  modification when the pooled thread reads it while EDT mutates it.

---

## 10. Summary of Zed/Sweep concepts mapped to this design

| Concept | Zed | This Design |
|---|---|---|
| Snapshot | CRDT TextBufferSnapshot (O(1) clone) | `document.immutableCharSequence` (cheap copy) |
| Anchor | CRDT Anchor | Not needed (diffs are text-based) |
| Event coalescing | 8-line spatial + 1s temporal | Same: `COALESCE_LINE_SPAN=8`, `PAUSE_THRESHOLD_MS=1000` |
| Rolling history | `VecDeque<StoredEvent>` max 6 | `ArrayDeque<StoredDiff>` max 10 (configurable) |
| Diff format | Unified diff in prompt | `original:`/`updated:` blocks (Sweep format, per harness 08–13) |
| Prompt structure | Custom sections | `<\|file_sep\|>{path}.diff` entries between context and original/current/updated |
| Pause detection | `snapshot_after_last_editing_pause` | `Alarm` checking `lastEditTime` every 1s |
| Ignore own edits | N/A (no auto-apply) | `ignoreNextChange` flag |
