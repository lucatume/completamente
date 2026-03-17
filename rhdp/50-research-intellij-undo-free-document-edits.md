# Inserting Text Without Undo History in IntelliJ

**Question:** How can a plugin insert (or modify) text in an IntelliJ Document without the change
appearing in the undo history?

---

## Summary

There are three viable approaches, each with different semantics:

| Technique | Effect on Undo | Best For |
|-----------|---------------|----------|
| `CommandProcessor.runUndoTransparentAction()` | Merges with adjacent command; no separate undo step | Edits paired with a nearby undoable command |
| `UndoUtil.disableUndoIn(document) { … }` | Completely suppresses undo recording | True invisible edits (previews, status text) |
| Both combined | Satisfies the "must be in command/transparent action" rule + suppresses recording | Strongest guarantee |

**Recommended pattern for truly invisible edits:**

```kotlin
CommandProcessor.getInstance().runUndoTransparentAction {
    ApplicationManager.getApplication().runWriteAction {
        UndoUtil.disableUndoIn(document) {
            document.insertString(offset, text)
        }
    }
}
```

---

## Approach 1: `runUndoTransparentAction()`

### API

```kotlin
CommandProcessor.getInstance().runUndoTransparentAction {
    // document mutations here
}
```

From `CoreCommandProcessor.java`:

```java
@Override
public void runUndoTransparentAction(@NotNull Runnable action) {
    startUndoTransparentAction();
    try {
        action.run();
    } finally {
        finishUndoTransparentAction();
    }
}
```

### Semantics

The Javadoc states: *"Defines a scope which contains undoable actions, for which there won't be a
separate undo/redo step — they will be undone/redone along with 'adjacent' command."*

This does **not** fully prevent undo recording. It **merges** the change with the adjacent
(previous/next) command on the undo stack. If there is no adjacent command, the transparent
action's changes may still be undone — they just won't appear as a separate undo step.

### Usage in completamente

Order 89 uses this extensively for inserting/updating/removing status display text:

```kotlin
// Insert status text (Order89Action.kt:256-259)
ApplicationManager.getApplication().runWriteAction {
    CommandProcessor.getInstance().runUndoTransparentAction {
        editor.document.insertString(offset, statusText)
    }
}

// Update animated symbol (Order89Action.kt:305-316)
ApplicationManager.getApplication().runWriteAction {
    CommandProcessor.getInstance().runUndoTransparentAction {
        if (symbolRange.isValid) {
            editor.document.replaceString(
                symbolRange.startOffset, symbolRange.endOffset,
                symbols[symbolIndex].toString()
            )
        }
    }
}

// Remove status text (Order89Action.kt:229-235)
ApplicationManager.getApplication().runWriteAction {
    CommandProcessor.getInstance().runUndoTransparentAction {
        if (display.range.isValid) {
            editor.document.deleteString(display.range.startOffset, display.range.endOffset)
        }
    }
}
```

### Critical Ordering Rule

**Do NOT nest `runUndoTransparentAction` inside `WriteCommandAction`.** IntelliJ groups them
together in the undo history, making the transparent action visible on undo. The correct pattern:

```kotlin
// 1. First: undo-transparent cleanup
removeStatusDisplay(editor, session.statusDisplay)

// 2. Then: undoable user-facing change
WriteCommandAction.runWriteCommandAction(project, "Order 89", null, {
    editor.document.replaceString(start, end, result.output)
})
```

This is documented in Order89Action.kt lines 160-163:

> Remove status lines BEFORE the command so the deletion is truly undo-transparent. Nesting
> runUndoTransparentAction inside WriteCommandAction causes IntelliJ to record it in the same
> undo group, making status text reappear on undo.

### Limitation

Community reports indicate `runUndoTransparentAction` does not reliably prevent changes from
appearing in undo history when there is no adjacent command to merge with. For guaranteed
invisibility, combine with `UndoUtil.disableUndoIn()`.

---

## Approach 2: `UndoUtil.disableUndoIn()`

### API

From `platform/core-api/src/com/intellij/openapi/command/undo/UndoUtil.java`:

```java
public static void disableUndoIn(@NotNull Document document, @NotNull Runnable runnable) {
    Boolean oldVal = document.getUserData(DONT_RECORD_UNDO);
    document.putUserData(DONT_RECORD_UNDO, Boolean.TRUE);
    try {
        runnable.run();
    } finally {
        document.putUserData(DONT_RECORD_UNDO, oldVal);
    }
}
```

This sets a `DONT_RECORD_UNDO` user data key on the document. The `DocumentUndoProvider` (a
`DocumentListener` that records changes into the undo system) checks this flag and **skips
recording** when it is set.

### Variants

```kotlin
// Scoped — preferred
UndoUtil.disableUndoIn(document) {
    document.insertString(offset, text)
}

// Permanent — disables undo for entire document lifetime
UndoUtil.disableUndoFor(document)

// Re-enable
UndoUtil.enableUndoFor(document)
```

### Semantics

This is the most robust approach for true undo suppression. The `DocumentUndoProvider` will not
record any changes made while the flag is set, regardless of the command context.

### Requirement

IntelliJ still requires that document modifications occur inside either
`CommandProcessor.executeCommand()` or `CommandProcessor.runUndoTransparentAction()`. Modifying
outside both throws:

```
IncorrectOperationException: Must not change document outside command or undo-transparent action
```

Therefore, combine with `runUndoTransparentAction`:

```kotlin
CommandProcessor.getInstance().runUndoTransparentAction {
    ApplicationManager.getApplication().runWriteAction {
        UndoUtil.disableUndoIn(document) {
            document.insertString(offset, text)
        }
    }
}
```

---

## Approach 3: `UndoManager.nonundoableActionPerformed()` (NOT Recommended)

```kotlin
val undoManager = UndoManager.getInstance(project)
val ref = DocumentReferenceManager.getInstance().create(document)
undoManager.nonundoableActionPerformed(ref, false)
```

This creates a `NonUndoableAction` on the undo stack whose `undo()` and `redo()` methods just log
errors. It **poisons** the undo stack — once encountered, the undo manager cannot undo past it.

**Do not use** for temporary insertions. This breaks undo for all prior changes to the document.

---

## Alternative: No Document Mutation (Inlay/Ghost Text)

IntelliJ's inline completion system never modifies the document for preview text. It uses
**Inlay hints** — visual elements rendered in the editor that exist outside the document model.
The gray "ghost text" is purely a rendering overlay. Document insertion only happens when the user
accepts the suggestion.

This is the approach used by `FimInlineCompletionProvider` in completamente — it returns
`InlineCompletionGrayTextElement` objects, not document edits.

For cases where you need actual document text (not just visual overlay), this approach does not
apply.

---

## Other APIs (Not Useful for Undo Suppression)

| API | What It Does | Undo Effect |
|-----|-------------|-------------|
| `DocumentEx.setInBulkUpdate()` | Batches `DocumentListener` events for performance | None — does not affect undo |
| `executeCommand(shouldRecordCommandForActiveDocument=false)` | Prevents recording in the *active document's* undo history | Only for commands affecting non-active documents |
| `DocumentEx` internal methods | Internal JetBrains APIs | Unstable; not public |

---

## Imports Required

```kotlin
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
```

---

## Sources

- [Documents | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/documents.html)
- [CommandProcessor.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/command/CommandProcessor.java)
- [UndoUtil.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-api/src/com/intellij/openapi/command/undo/UndoUtil.java)
- [UndoManagerImpl.java](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/openapi/command/impl/UndoManagerImpl.java)
- [NonUndoableAction.java](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-impl/src/com/intellij/openapi/command/impl/NonUndoableAction.java)
- [CoreCommandProcessor.java](https://github.com/JetBrains/intellij-community/blob/master/platform/core-impl/src/com/intellij/openapi/command/impl/CoreCommandProcessor.java)
- [JetBrains Forum: Manipulating a Document without undo-history](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206138859)
- [JetBrains Forum: To undo or not to undo?](https://intellij-support.jetbrains.com/hc/en-us/community/posts/206130289)
- completamente codebase: `Order89Action.kt`, `Order89StatusDisplayTest.kt`
