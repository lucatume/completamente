# Order 89: Cursor-Based Cancellation

## Question

How can ESC-based cancellation of Order 89 be changed from "cancel the most recent" to "cancel the session whose line range contains the cursor", preventing accidental cancellations?

## Current Behavior

**ESC handler** (`Order89Action.kt:113-131`):
- Registered once when the first session starts, on the editor component.
- Uses `deque.pollFirst()` — pure LIFO, always cancels the most recently launched session.
- No awareness of cursor/caret position.
- Any ESC press in the editor cancels the latest Order 89, even if the user is editing far away.

**Session data** (`Order89Session`, line 52-56):
```kotlin
data class Order89Session(
    val process: Process,
    val future: Future<Order89Result>,
    val inlay: Inlay<*>?
)
```
No line range or offset information is stored.

**Selection/line info at launch time** (lines 70-72, 100-102):
- `selectionStart` / `selectionEnd` — document offsets captured before execution.
- `targetLine = document.getLineNumber(selectionStart)` — used for inlay placement.
- These values are used locally but not persisted into the session object.

## Findings

### 1. Inlay offset as a proxy for session location

Each session already has an `inlay: Inlay<*>?`. Inlays track their offset in the document as edits happen (they shift with insertions/deletions). We can use `inlay.offset` to determine which line the inlay sits on.

However, the inlay is placed at `getLineStartOffset(targetLine)` — only the **start** line. For cursor-based cancellation we need to know the full line range of the selection.

### 2. Storing the line range explicitly

The selection range (`selectionStart..selectionEnd`) defines which lines the Order 89 operates on. Converting to line numbers:
```kotlin
val startLine = document.getLineNumber(selectionStart)
val endLine = document.getLineNumber(selectionEnd)
```

These line numbers are stable as long as no other Order 89 completes and modifies the document. If a prior Order 89 inserts/removes lines above this range, the stored line numbers become stale.

**Mitigation**: Use document offsets (not line numbers) and convert to line numbers at ESC-press time. Offsets are also affected by prior edits, but IntelliJ's `RangeMarker` API tracks ranges through document changes automatically.

### 3. RangeMarker API

`Document.createRangeMarker(startOffset, endOffset)` returns a `RangeMarker` that:
- Automatically adjusts `startOffset`/`endOffset` as the document is edited.
- Has `isValid` to check if the range was destroyed by conflicting edits.
- Can be disposed when no longer needed.

This is the idiomatic IntelliJ way to track a "region of interest" across document mutations.

### 4. Caret position check

At ESC time:
```kotlin
val caretLine = editor.caretModel.logicalPosition.line
val sessionStartLine = document.getLineNumber(rangeMarker.startOffset)
val sessionEndLine = document.getLineNumber(rangeMarker.endOffset)
val cursorInRange = caretLine in sessionStartLine..sessionEndLine
```

This is O(n) over active sessions (n is tiny — rarely more than 2-3 concurrent Order 89s).

### 5. Edge case: cursor on the inlay line itself

The inlay is a block element placed **above** the target line (line of `selectionStart`). The inlay occupies visual space but doesn't change the document's logical line numbers. A user looking at the inlay and pressing ESC would have their cursor on the target line, which is already within the selection range. No special handling needed.

### 6. Edge case: no session at cursor

If the cursor isn't within any active session's range, ESC should do nothing (fall through to IntelliJ's default ESC behavior). This is the key improvement — no more accidental cancellations.

### 7. Edge case: overlapping sessions

Two Order 89 sessions could overlap if the user selects the same or overlapping ranges. In this case, pressing ESC on the overlapping line should cancel the **most recent** session at that position (LIFO within the matched set). This preserves intuitive behavior.

### 8. IntelliJ ESC propagation

When the custom ESC action is registered via `registerCustomShortcutSet`, it takes priority. If our handler decides not to cancel anything (cursor not in range), we need to let the ESC propagate to IntelliJ's default handlers (close popups, deselect, etc.).

The simplest approach: if no session matches, call `ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_ESCAPE)` to delegate, or simply don't consume the event. With `AnAction`, not consuming means not calling anything — but the custom shortcut registration intercepts the keystroke before IntelliJ's default handler sees it.

**Solution**: Keep the current `registerCustomShortcutSet` approach but explicitly invoke the original escape action when no session is found:
```kotlin
val originalEsc = ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_ESCAPE)
originalEsc?.actionPerformed(e)
```

## Sources

- `Order89Action.kt` lines 52-56, 65-131, 185-260
- IntelliJ Platform SDK: [RangeMarker](https://plugins.jetbrains.com/docs/intellij/documents.html#range-markers)
- IntelliJ Platform SDK: [EditorActionManager](https://plugins.jetbrains.com/docs/intellij/editor-basics.html)
