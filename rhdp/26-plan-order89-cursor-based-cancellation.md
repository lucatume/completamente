# Plan: Order 89 Cursor-Based Cancellation

Implementation plan for cursor-based ESC cancellation. All changes are in `Order89Action.kt`.

## Steps

### Step 1: Add `RangeMarker` to `Order89Session`

**File**: `Order89Action.kt`, line 52-56

Add a `range: RangeMarker` field:

```kotlin
data class Order89Session(
    val process: Process,
    val future: Future<Order89Result>,
    val inlay: Inlay<*>?,
    val range: RangeMarker
)
```

Add import:
```kotlin
import com.intellij.openapi.editor.RangeMarker
```

### Step 2: Create `RangeMarker` at session launch

**File**: `Order89Action.kt`, around line 104-106

After capturing `selectionStart`/`selectionEnd` and before creating the session, create a range marker:

```kotlin
val rangeMarker = editor.document.createRangeMarker(selectionStart, selectionEnd)
```

Pass it to the session constructor:
```kotlin
val session = Order89Session(process, future, inlay, rangeMarker)
```

### Step 3: Rewrite the ESC handler to be cursor-aware

**File**: `Order89Action.kt`, lines 113-131

Replace the current `pollFirst()` logic with cursor-based matching:

```kotlin
if (wasEmpty) {
    val escAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val deque = editor.getUserData(SESSIONS_KEY) ?: return
            val doc = editor.document
            val caretLine = editor.caretModel.logicalPosition.line

            // Find the most recent session whose range contains the caret line.
            val match = deque.firstOrNull { s ->
                s.range.isValid &&
                    caretLine >= doc.getLineNumber(s.range.startOffset) &&
                    caretLine <= doc.getLineNumber(s.range.endOffset)
            }

            if (match != null) {
                deque.remove(match)
                match.process.destroyForcibly()
                match.future.cancel(true)
                match.inlay?.dispose()
                match.range.dispose()
                if (deque.isEmpty()) {
                    unregisterEsc(editor)
                }
            } else {
                // No session at cursor — let ESC pass through.
                val originalEsc = ActionManager.getInstance()
                    .getAction(IdeActions.ACTION_EDITOR_ESCAPE)
                originalEsc?.actionPerformed(e)
            }
        }
    }
    escAction.registerCustomShortcutSet(
        CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
        editor.component
    )
    editor.putUserData(ESC_ACTION_KEY, escAction)
}
```

Add imports:
```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
```

### Step 4: Dispose `RangeMarker` in the completion `finally` block

**File**: `Order89Action.kt`, lines 163-172

Add `session.range.dispose()` alongside the inlay disposal:

```kotlin
finally {
    ApplicationManager.getApplication().invokeLater {
        session.inlay?.dispose()
        session.range.dispose()
        val deque = editor.getUserData(SESSIONS_KEY)
        deque?.remove(session)
        if (deque?.isEmpty() == true) {
            unregisterEsc(editor)
        }
    }
}
```

### Step 5: Verify edge cases

Test manually:

1. **Single session, cursor on its lines**: ESC cancels it.
2. **Single session, cursor elsewhere**: ESC does nothing (passes through to IDE).
3. **Two sessions on different lines**: ESC cancels only the one under cursor.
4. **Two overlapping sessions**: ESC cancels the most recent (due to `firstOrNull` on a LIFO deque).
5. **Session completes while another is running**: Completed session's range marker is disposed; remaining session can still be cancelled by cursor.
6. **Cursor on the inlay line (above the selection start)**: The inlay is a block element at `getLineStartOffset(targetLine)` where `targetLine = getLineNumber(selectionStart)`. The caret on that line means `caretLine == startLine`, which is in range.

## Acceptance Criteria

- ESC only cancels an Order 89 when the cursor is on a line within its selection range.
- ESC with cursor outside all active Order 89 ranges passes through to IntelliJ default behavior.
- Multiple concurrent Order 89 sessions on different line ranges can be individually cancelled.
- RangeMarkers are disposed on both completion and cancellation (no leaks).
