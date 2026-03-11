# Design: Order 89 Cursor-Based Cancellation

## Goal

Replace the current "ESC cancels the most recent Order 89" behavior with "ESC cancels the Order 89 whose line range contains the cursor". If the cursor is not within any active Order 89's range, ESC does nothing (passes through to IntelliJ defaults).

## Non-Goals

- GUI for listing/managing active Order 89 sessions (a session panel, popup, etc.).
- Cancelling Order 89 sessions in other editors/tabs.
- Keyboard shortcut customization for the cancel action.

## Approach

### 1. Enrich `Order89Session` with a `RangeMarker`

Add a `RangeMarker` field to `Order89Session`:

```kotlin
data class Order89Session(
    val process: Process,
    val future: Future<Order89Result>,
    val inlay: Inlay<*>?,
    val range: RangeMarker
)
```

The `RangeMarker` is created from `(selectionStart, selectionEnd)` at launch time. It automatically adjusts as the document is edited by other sessions completing.

### 2. Cursor-aware ESC handler

On ESC press:

1. Get the caret's current logical line.
2. Iterate active sessions (from most recent to oldest).
3. For each session, convert its `range.startOffset` / `range.endOffset` to line numbers.
4. If the caret line falls within `[startLine, endLine]`, cancel that session and return.
5. If no session matches, delegate to IntelliJ's original ESC action.

### 3. ESC passthrough

The handler must not swallow ESC when no session matches. This is achieved by storing a reference to the original ESC action and invoking it as a fallback.

```kotlin
val originalEscape = ActionManager.getInstance().getAction(IdeActions.ACTION_EDITOR_ESCAPE)
// ... in the handler, if no match:
originalEscape?.actionPerformed(e)
```

### 4. RangeMarker lifecycle

- **Created**: When the Order 89 session starts, immediately after capturing `selectionStart`/`selectionEnd`.
- **Disposed**: In the `finally` block when the session completes or is cancelled, alongside inlay disposal.

### Alternatives Considered

**A. Store line numbers instead of RangeMarker**
Simpler but breaks when other Order 89 sessions complete and insert/remove lines. RangeMarker handles this automatically.

**B. Use inlay offset as the sole position indicator**
The inlay only marks the start line, not the full selection range. Multi-line selections would not be accurately represented.

**C. Use `EditorActionManager` to override the Escape handler globally**
More "correct" in IntelliJ terms but adds complexity (chaining handlers, per-editor state lookup). The current `registerCustomShortcutSet` approach works well and is simpler; we just need to add the fallback delegation.

**D. Cancel on cursor proximity (within N lines) instead of exact range**
Considered expanding the cancellation zone to include a few lines around the range. Rejected — the exact selection range is precise and predictable. Users can see the inlay and know where the range is.

## Trade-offs

- **RangeMarker overhead**: Negligible. IntelliJ uses RangeMarkers extensively; a few extra ones for Order 89 sessions are inconsequential.
- **Iteration on ESC**: O(n) where n = active sessions. In practice n <= 3-5. No concern.
- **ESC passthrough reliability**: Invoking the original escape action via `ActionManager` is standard practice. If IntelliJ changes internal action IDs this could break, but `IdeActions.ACTION_EDITOR_ESCAPE` is a stable API.
