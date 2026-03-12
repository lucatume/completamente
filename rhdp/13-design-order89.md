# Design: Order 89

## Goal

Add an action called **Order 89** that lets the user select text (or place the cursor), type a free-form instruction in a modal, and have an external shell command transform the selection using that instruction. The default command uses `claude -p` (Claude Code CLI in print mode).

## Non-goals

- Streaming partial output into the editor while the command runs.
- Multiple concurrent Order 89 invocations on the same editor.
- Undo grouping beyond what IntelliJ provides by default (each replacement is a single undoable action).

---

## User Flow

1. User places cursor or selects text in the editor.
2. User presses **Ctrl+Shift+8** (macOS: **Cmd+Shift+8**) or invokes the action from the menu/command palette.
3. A modal dialog titled **"Order 89"** appears, centered over the editor area.
   - Focus moves to the modal's text area.
   - The modal is **not** dismissed by clicking outside.
   - **ESC** dismisses the modal (no action taken).
   - **ENTER** submits; **Shift+ENTER** inserts a newline.
   - The text area wraps automatically.
4. On submit:
   - If the text area is empty → close modal, do nothing.
   - Otherwise → close modal and begin execution:
     a. An inlay hint reading "⟳ Executing Order 89..." appears in grey above the target line (first line of selection, or cursor line).
     b. The configured shell command runs asynchronously with placeholders substituted.
     c. The user can press **ESC** (or a keybinding TBD via the action) to cancel the running process. On cancel, the process is destroyed, the inlay is removed, and the original text is left unchanged.
     d. On success (exit code 0): the command's stdout replaces the selected text, inlay is removed.
     e. On failure (non-zero exit): the original text is left unchanged, inlay is removed, and a balloon notification shows the error (stderr + exit code).

---

## Command Configuration

### Settings UI

A new group **"Order 89"** is added to the existing `SettingsConfigurable` panel, containing:

| Field | Type | Description |
|-------|------|-------------|
| Command template | `JBTextArea` (3 rows, word-wrap) | Shell command with placeholders. |

### Placeholders

| Placeholder | Value |
|-------------|-------|
| `{{prompt}}` | The text the user typed in the modal (shell-escaped). |
| `{{selected_text}}` | The selected text, or empty string if cursor-only (shell-escaped). |
| `{{file_path}}` | Absolute path of the file being edited. |
| `{{file_content}}` | Full content of the file being edited (shell-escaped). |
| `{{language}}` | IntelliJ language ID of the file (e.g. `Kotlin`, `Java`, `Python`). |
| `{{referenced_files}}` | Newline-separated list of absolute paths of project files referenced by imports/use statements in the current file (resolved via PSI, same mechanism as header structure resolution). Paths only, no content. |

Placeholders are substituted literally. Values that are embedded in shell strings (prompt, selected_text, file_content) must be properly escaped for the shell to avoid injection.

### Default Command

```
claude -p --model sonnet --output-format text "You are a code transformation tool. You receive code and an instruction. You output ONLY the transformed code. No markdown, no explanations, no backticks, no commentary. Output the raw code only.\n\n<instruction>{{prompt}}</instruction>\n<code language=\"{{language}}\" file=\"{{file_path}}\">{{selected_text}}</code>\n\nOutput the transformed code and nothing else:"
```

> **Note**: The harness at `rhdp/11-harness-order89-prompt-structures.sh` tests six prompt structures. Run it manually (`./rhdp/11-harness-order89-prompt-structures.sh`) to determine which structure produces the cleanest code-only output, then update the default accordingly.

### Working Directory

The command runs with CWD set to the **project root** (`project.basePath`).

### Shell

The command is executed via the system shell:
- macOS/Linux: `/bin/sh -c "<command>"`
- Windows: `cmd.exe /c "<command>"`

---

## Architecture

### New Files

| File | Purpose |
|------|---------|
| `order89/Order89Action.kt` | `AnAction` — entry point, reads selection, shows modal, orchestrates execution. |
| `order89/Order89Dialog.kt` | Custom `DialogWrapper` — the modal UI. |
| `order89/Order89Executor.kt` | Runs the shell command asynchronously, handles cancellation. |
| `order89/Order89InlayProvider.kt` | Manages the "Executing..." inlay hint lifecycle. |
| `order89/Order89Settings.kt` | Data class for Order 89 settings (command template). |

All files under `src/main/kotlin/com/github/lucatume/completamente/order89/`.

### Modified Files

| File | Change |
|------|--------|
| `services/Settings.kt` | Add `order89Command: String` field with default value. |
| `services/SettingsState.kt` | Add `order89Command` persistent field. |
| `settings/SettingsConfigurable.kt` | Add "Order 89" settings group with the command template text area. |
| `plugin.xml` | Register the action with keyboard shortcut `ctrl shift 8` / `meta shift 8`. |

### Key Design Decisions

#### Modal: Custom `DialogWrapper`

IntelliJ's `DialogWrapper` is used because:
- It supports modal behavior (blocks editor interaction).
- It can be configured to **not** close on outside click (`setCrossClosesWindow(false)`, override `createActions()` to remove default buttons).
- ESC dismissal is built-in.
- Custom key bindings (ENTER to submit, Shift+ENTER for newline) are wired via `JTextArea` key listeners.

The dialog has **no OK/Cancel buttons** — submission is entirely keyboard-driven (ENTER). The title "Order 89" is shown centered.

#### Inlay Hint

An `InlineInlayRenderer` / block inlay is added via `editor.inlayModel.addBlockElement()` above the target line. It renders grey italic text "⟳ Executing Order 89...". A reference to the inlay `Disposable` is kept so it can be removed on completion/cancellation.

#### Async Execution

The command runs on a pooled thread (`ApplicationManager.getApplication().executeOnPooledThread`). A `Process` reference is stored so cancellation can call `process.destroyForcibly()`. The result is applied back on the EDT via `WriteCommandAction`.

#### Text Replacement

On success, the replacement is performed inside a `WriteCommandAction.runWriteCommandAction` block so it's a single undoable operation. The replacement uses the document offsets captured at invocation time. If the document has been modified between invocation and completion (unlikely since the inlay signals activity), the operation is aborted with a warning notification.

#### Cancellation

While the command is running, the Order 89 action listens for ESC. This is implemented by registering a temporary `AnAction` on the editor's action map that:
1. Calls `process.destroyForcibly()`.
2. Removes the inlay.
3. Unregisters itself.

#### Shell Escaping

Placeholder values embedded in the command string are escaped using a utility function that:
- On Unix: wraps the value in single quotes, escaping internal single quotes as `'\''`.
- On Windows: escapes with `^` for special characters.

This prevents shell injection from file content or user input.

#### Referenced Files Resolution

Uses the same PSI-based import resolution already implemented for header structure files in the FIM completion system. The existing logic (resolve imports → find project files) is extracted into a reusable utility if not already factored out.

---

## Compromises & Trade-offs

1. **No streaming**: The entire command output is captured and applied at once. Streaming would add significant complexity (partial document updates, cursor management) for marginal UX benefit on fast commands.

2. **Single command template**: No per-language or per-project command overrides. Users who need different commands for different languages can use `{{language}}` in their shell script logic.

3. **Shell escaping vs. stdin**: Passing large values (file_content, selected_text) as shell arguments has size limits. An alternative would be passing them via stdin or temp files. For v1, shell escaping is simpler. If users hit argument-length limits, a follow-up can add stdin/tempfile support.

4. **No output post-processing**: The command's stdout is used as-is. If the command wraps output in markdown fences, the user must fix their command template. The harness helps find a prompt that avoids this.
