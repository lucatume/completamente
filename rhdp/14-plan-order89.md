# Plan: Implement Order 89

Implementation plan for the Order 89 feature as described in `13-design-order89.md`.

Based on harness results (`12-output-order89-prompt-structures.txt`), the **"Simple direct"** prompt structure is used as the default — it's the shortest and produced clean output.

---

## Step 1: Add Order 89 settings fields

### Files to modify

**`src/main/kotlin/com/github/lucatume/completamente/services/Settings.kt`**

Add field:
```kotlin
val order89Command: String = "claude -p --model sonnet --output-format text 'Output ONLY code, no explanations, no markdown fences. Do not include any text before or after the code.\n\nReplace the following {{language}} code according to this instruction: {{prompt}}\n\n{{selected_text}}'"
```

**`src/main/kotlin/com/github/lucatume/completamente/services/SettingsState.kt`**

Add field:
```kotlin
// Order 89
var order89Command: String = Settings().order89Command
```

Update `toSettings()` to include `order89Command`.

### Checkpoint
Settings compile. Default value round-trips through `SettingsState`.

---

## Step 2: Add Order 89 settings UI

### File to modify

**`src/main/kotlin/com/github/lucatume/completamente/settings/SettingsConfigurable.kt`**

1. Add `private var order89Command = ""` field.
2. Add `private var order89CommandArea: JBTextArea? = null` field.
3. In `createComponent()`, add a new `group("Order 89")` block after the existing groups, containing:
   - A `JBTextArea` (3 rows, word-wrap) for the command template, identical in structure to the existing `serverCommandArea`.
   - A comment listing available placeholders: `{{prompt}}`, `{{selected_text}}`, `{{file_path}}`, `{{file_content}}`, `{{language}}`, `{{referenced_files}}`.
4. Update `loadFromState()` to load `order89Command` from state.
5. Update `isModified()` to check `order89CommandArea`.
6. Update `apply()` / `applyToState()` to save `order89Command`.
7. Update `reset()` to restore `order89CommandArea`.

### Checkpoint
Open Settings > Tools > completamente. The "Order 89" group appears with a command template field pre-filled with the default. Editing and applying persists the value.

---

## Step 3: Create `Order89Dialog.kt`

### New file

**`src/main/kotlin/com/github/lucatume/completamente/order89/Order89Dialog.kt`**

A custom `DialogWrapper` subclass:

- Constructor takes the parent editor's `Component` (for centering).
- Title: "Order 89", centered.
- No OK/Cancel buttons — override `createActions()` to return empty array.
- `createCenterPanel()` returns a `JBTextArea` (word-wrap on, no horizontal scrollbar) inside a `JBScrollPane`, sized ~400x120.
- Key bindings on the text area:
  - **ENTER** (without Shift): calls `close(DialogWrapper.OK_EXIT_CODE)`.
  - **Shift+ENTER**: inserts newline (default `JTextArea` behavior, no override needed).
- Override `createContentPaneBorder()` to remove extra padding if desired.
- `setCrossClosesWindow(false)` — clicking outside does not close.
- ESC closes via built-in `DialogWrapper` behavior (`CANCEL_EXIT_CODE`).
- Expose `val promptText: String` that returns the text area content after close.

### Checkpoint
Instantiate the dialog from a scratch action, verify: modal blocks editor, ESC dismisses, ENTER submits, Shift+ENTER adds newline, clicking outside does nothing.

---

## Step 4: Create `Order89Executor.kt`

### New file

**`src/main/kotlin/com/github/lucatume/completamente/order89/Order89Executor.kt`**

A data class + pure functions approach:

```kotlin
data class Order89Request(
    val commandTemplate: String,
    val prompt: String,
    val selectedText: String,
    val filePath: String,
    val fileContent: String,
    val language: String,
    val referencedFiles: List<String>,
    val workingDirectory: String
)

data class Order89Result(
    val success: Boolean,
    val output: String,    // stdout on success
    val error: String,     // stderr + exit code on failure
    val exitCode: Int
)
```

Functions:

1. **`buildCommand(request: Order89Request): String`**
   - Substitutes placeholders in `commandTemplate`:
     - `{{prompt}}` → `shellEscape(request.prompt)`
     - `{{selected_text}}` → `shellEscape(request.selectedText)`
     - `{{file_path}}` → `request.filePath`
     - `{{file_content}}` → `shellEscape(request.fileContent)`
     - `{{language}}` → `request.language`
     - `{{referenced_files}}` → `request.referencedFiles.joinToString("\n")`
   - Returns the final command string.

2. **`shellEscape(value: String): String`**
   - Unix: replaces `'` with `'\''`, wraps in single quotes.
   - Windows: basic `^` escaping for cmd metacharacters.

3. **`execute(request: Order89Request): Pair<Process, Future<Order89Result>>`**
   - Builds the command via `buildCommand()`.
   - Starts a `Process` via `ProcessBuilder(listOf("/bin/sh", "-c", command))` (or `cmd.exe /c` on Windows).
   - Sets working directory to `request.workingDirectory`.
   - Returns the `Process` (for cancellation) and a `Future<Order89Result>` that reads stdout/stderr and waits for exit.

### Checkpoint
Unit-test `buildCommand()` with known inputs. Verify shell escaping handles single quotes, newlines, and special characters. Test `execute()` with `echo "hello"` as the command template.

---

## Step 5: Collect referenced file paths utility

### File to modify or extract from

**`src/main/kotlin/com/github/lucatume/completamente/completion/definitions.kt`**

The existing `collectReferencedFilesFromHeader()` already resolves imports → `List<PsiFile>`. For Order 89 we only need paths, not `PsiFile` objects. Add a convenience function:

```kotlin
fun collectReferencedFilePaths(
    project: Project,
    psiFile: PsiFile,
    currentFilePath: String
): List<String> {
    return collectReferencedFilesFromHeader(project, psiFile, currentFilePath)
        .mapNotNull { it.virtualFile?.path }
}
```

### Checkpoint
Function returns the expected list of paths for a file with known imports.

---

## Step 6: Create `Order89Action.kt`

### New file

**`src/main/kotlin/com/github/lucatume/completamente/order89/Order89Action.kt`**

An `AnAction` subclass that orchestrates the full flow:

**`actionPerformed(e: AnActionEvent)`:**

1. Get `editor`, `project`, `psiFile` from the event. If any is null, return.
2. Capture selection state:
   - `selectedText = editor.selectionModel.selectedText ?: ""`
   - `selectionStart = editor.selectionModel.selectionStart`
   - `selectionEnd = editor.selectionModel.selectionEnd`
   - `targetLine` = the logical line of `selectionStart`.
3. Show `Order89Dialog`. If result is `CANCEL_EXIT_CODE` or prompt is blank, return.
4. Read settings: `order89Command` from `SettingsState.getInstance()`.
   - If command is blank, show a balloon notification: "Order 89 command not configured" and return.
5. Build `Order89Request`:
   - `prompt` = dialog prompt text.
   - `selectedText` = captured selection.
   - `filePath` = `psiFile.virtualFile.path`.
   - `fileContent` = `editor.document.text`.
   - `language` = `psiFile.language.id`.
   - `referencedFiles` = `collectReferencedFilePaths(project, psiFile, filePath)` (run on read action).
   - `workingDirectory` = `project.basePath ?: "."`.
6. Add the inlay hint above `targetLine` (see Step 7).
7. Register the ESC cancellation handler (see Step 8).
8. Call `Order89Executor.execute(request)` — get `(process, future)`.
9. On a pooled thread, wait for `future`. When done:
   - On EDT: remove inlay, unregister ESC handler.
   - If cancelled (future cancelled / process destroyed): do nothing.
   - If `result.success`: `WriteCommandAction.runWriteCommandAction(project)` to replace `document.replaceString(selectionStart, selectionEnd, result.output)`.
   - If `!result.success`: show balloon notification with `result.error`.

**`update(e: AnActionEvent)`:**
- Enable only when an editor is available: `e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null`.

### Checkpoint
Trigger the action with a selection, verify the full flow: modal → inlay → command runs → text replaced.

---

## Step 7: Inlay hint management

### Inline in `Order89Action.kt` (no separate file needed)

Use `editor.inlayModel.addBlockElement()` to insert an inlay above `targetLine`:

```kotlin
fun addExecutingInlay(editor: Editor, offset: Int): Inlay<*>? {
    val renderer = object : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int = 0
        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            g.color = JBColor.GRAY
            g.font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
            g.drawString("⟳ Executing Order 89...", targetRegion.x, targetRegion.y + editor.ascent)
        }
        override fun calcHeightInPixels(inlay: Inlay<*>): Int = editor.lineHeight
    }
    return editor.inlayModel.addBlockElement(offset, true, false, 0, renderer)
}
```

The offset is `document.getLineStartOffset(targetLine)`.

Store the returned `Inlay<*>?` and call `Disposer.dispose(inlay)` when the command completes or is cancelled.

### Checkpoint
Inlay appears as grey italic text above the target line. It disappears when the command finishes.

---

## Step 8: ESC cancellation handler

### Inline in `Order89Action.kt`

While the command is running, register a temporary key listener or `TypedActionHandler` that intercepts ESC:

```kotlin
val escAction = object : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        process.destroyForcibly()
        future.cancel(true)
        // Inlay removal and cleanup happen in the future completion handler
    }
}
escAction.registerCustomShortcutSet(
    CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
    editor.component
)
```

Unregister on completion: `escAction.unregisterCustomShortcutSet(editor.component)`.

### Checkpoint
Press ESC while a long-running command is executing. Verify the process is killed, original text is preserved, and inlay is removed.

---

## Step 9: Register the action in `plugin.xml`

### File to modify

**`src/main/resources/META-INF/plugin.xml`**

Add inside `<actions>`:

```xml
<action id="com.github.lucatume.completamente.order89.Order89Action"
        class="com.github.lucatume.completamente.order89.Order89Action"
        text="Order 89"
        description="Transform selected text using a configured shell command">
    <keyboard-shortcut keymap="$default" first-keystroke="ctrl shift 8"/>
    <keyboard-shortcut keymap="Mac OS X" first-keystroke="meta shift 8"/>
    <keyboard-shortcut keymap="Mac OS X 10.5+" first-keystroke="meta shift 8"/>
</action>
```

### Checkpoint
The action appears in Settings > Keymap. The shortcut `Ctrl+Shift+8` / `Cmd+Shift+8` triggers it.

---

## Step 10: Manual integration test

1. Open a Kotlin file in the IDE.
2. Select a code block.
3. Press `Cmd+Shift+8` (or `Ctrl+Shift+8`).
4. Type "convert to use string templates" in the modal.
5. Press ENTER.
6. Verify: inlay appears, `claude` runs, output replaces selection, inlay disappears.
7. Test ESC cancellation mid-flight.
8. Test with empty prompt (should do nothing).
9. Test with cursor only (no selection) — should pass empty `{{selected_text}}`.
10. Test error case: set command to `exit 1` in settings, verify balloon notification.
11. Undo (`Cmd+Z`) — verify the replacement is undone as a single operation.

---

## Implementation order summary

| Step | What | Files |
|------|------|-------|
| 1 | Settings fields | `Settings.kt`, `SettingsState.kt` |
| 2 | Settings UI | `SettingsConfigurable.kt` |
| 3 | Dialog | `order89/Order89Dialog.kt` (new) |
| 4 | Executor | `order89/Order89Executor.kt` (new) |
| 5 | Referenced files utility | `completion/definitions.kt` |
| 6 | Action (orchestrator) | `order89/Order89Action.kt` (new) |
| 7 | Inlay hint | Inline in `Order89Action.kt` |
| 8 | ESC cancellation | Inline in `Order89Action.kt` |
| 9 | Plugin registration | `plugin.xml` |
| 10 | Manual testing | — |
