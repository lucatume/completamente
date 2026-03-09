# Plan: Order 89 v2 — Temp File Prompt with `<Order89UserSelection>` Tag

## Summary

Rework Order 89 to:
1. Build the prompt in a temp file instead of piping via shell arguments.
2. Embed the user's selection within the file content using `<Order89UserSelection>` tags.
3. Switch to `--dangerously-skip-permissions`.
4. Simplify the command template — most placeholders go away since context is in the temp file.

## What Changes

The current approach assembles a shell command with `printf` piping shell-escaped placeholders.
The new approach writes a prompt file containing the full file content with the selection tagged
inline, then passes it to `claude` via stdin or `--prompt-file`. This eliminates shell escaping
issues for large file contents and makes the prompt clearer to the model.

### Prompt file format

```
<Order89Prompt>
Modify the code in the file according to the user instruction below.
Output ONLY the content that should replace the <Order89UserSelection> tags and their contents.
No markdown fences, no explanations, no text before or after the replacement code.
If the selection is empty (<Order89UserSelection></Order89UserSelection>), insert new code at that position.

Language: {{language}}
File: {{file_path}}

<Order89Instruction>
{{prompt}}
</Order89Instruction>

<Order89FileContent>
... file content before selection ...
<Order89UserSelection>
... selected text (or empty) ...
</Order89UserSelection>
... file content after selection ...
</Order89FileContent>
</Order89Prompt>
```

When no text is selected, the tag appears as `<Order89UserSelection></Order89UserSelection>` at the
cursor position, signalling "insert here".

---

## Steps

### Step 1: Add prompt file builder to `Order89Executor`

**File**: `src/main/kotlin/com/github/lucatume/completamente/order89/Order89Executor.kt`

- Add a `buildPromptFile(request: Order89Request): String` function that:
  1. Takes the `fileContent`, `selectionStart`, `selectionEnd` offsets, `prompt`, `language`, and
     `filePath`.
  2. Splits `fileContent` into three parts: before selection, selection, after selection.
  3. Assembles the prompt string with the `<Order89UserSelection>` tag wrapping the selection.
  4. Writes the result to a temp file (use `File.createTempFile("order89-", ".txt")`).
  5. Returns the temp file path.

- Update `Order89Request` data class:
  - Add `selectionStart: Int` and `selectionEnd: Int` fields.
  - Remove `selectedText` field (it's derived from `fileContent` + offsets now).
  - Remove `referencedFiles` field (no longer a separate placeholder).

- Update `buildCommand()`:
  - It now only needs to substitute `{{prompt_file}}` in the command template.
  - Remove all other placeholder substitutions (`{{prompt}}`, `{{selected_text}}`,
    `{{file_content}}`, `{{language}}`, `{{file_path}}`, `{{referenced_files}}`).
  - Remove `shellEscape()` — no longer needed since values go into a file, not shell args.

- Update `execute()`:
  - Call `buildPromptFile()` before building the command.
  - Pass the temp file path into the command via `{{prompt_file}}`.
  - Delete the temp file in a `finally` block after the process completes.

### Step 2: Update `Order89Action` to pass offsets

**File**: `src/main/kotlin/com/github/lucatume/completamente/order89/Order89Action.kt`

- Pass `selectionStart` and `selectionEnd` to `Order89Request` instead of `selectedText`.
- Remove the `referencedFiles` collection (no longer needed as a separate field — the file content
  with selection tags gives the model enough context).
- Remove the `ReadAction.compute` call for `collectReferencedFilePaths`.

### Step 3: Update the default command template

**File**: `src/main/kotlin/com/github/lucatume/completamente/services/SettingsState.kt`

Change `order89Command` default from the current `printf ... | claude ...` to:

```
claude --dangerously-skip-permissions --print --output-format text --prompt-file {{prompt_file}}
```

If `--prompt-file` is not a real claude flag, use stdin redirect instead:

```
claude --dangerously-skip-permissions --print --output-format text < {{prompt_file}}
```

**Check**: Verify which approach claude CLI supports. If neither `--prompt-file` nor stdin redirect
works cleanly with the command template, use `cat {{prompt_file}} | claude ...` as the default.

### Step 4: Update the settings UI "Reset to defaults"

**File**: `src/main/kotlin/com/github/lucatume/completamente/settings/SettingsConfigurable.kt`

The "Reset to defaults" button already reads from `SettingsState()`. No changes needed beyond
ensuring the new default in Step 3 is correct.

### Step 5: Update tests

**File**: `src/test/kotlin/com/github/lucatume/completamente/order89/Order89ExecutorTest.kt`

- Remove all `shellEscape` tests (function is removed).
- Remove placeholder substitution tests for the old placeholders.
- Add tests for `buildPromptFile()`:
  - Selection in the middle of file content → tags wrap the selection.
  - Empty selection (start == end) → `<Order89UserSelection></Order89UserSelection>` at cursor.
  - Selection at start of file.
  - Selection at end of file.
  - Selection spanning entire file.
- Add test for `buildCommand()` with the new `{{prompt_file}}` placeholder.
- Keep the `testExecuteWithEchoCommand` and `testExecuteWithFailingCommand` tests (update
  `makeRequest` signature).

### Step 6: Update `Order89DialogTest` if needed

**File**: `src/test/kotlin/com/github/lucatume/completamente/order89/Order89DialogTest.kt`

No changes expected — the dialog is unchanged.

### Step 7: Verify end-to-end

- Build the plugin and test manually:
  1. Select text → Order 89 → verify prompt file contains tagged selection.
  2. No selection (cursor only) → Order 89 → verify empty `<Order89UserSelection></Order89UserSelection>`.
  3. ESC during execution → verify temp file is cleaned up.
  4. Verify the replacement applies correctly.

---

## Files Modified (summary)

| File | Change |
|------|--------|
| `order89/Order89Executor.kt` | New `buildPromptFile()`, simplified `buildCommand()`, remove `shellEscape()`, temp file lifecycle |
| `order89/Order89Action.kt` | Pass offsets instead of `selectedText`, remove `referencedFiles` collection |
| `services/SettingsState.kt` | New default command with `--dangerously-skip-permissions` and `{{prompt_file}}` |
| `order89/Order89ExecutorTest.kt` | New prompt file tests, remove shell escape tests, update `makeRequest` |

## Files NOT Modified

| File | Reason |
|------|--------|
| `order89/Order89Dialog.kt` | Unchanged — still collects prompt text |
| `settings/SettingsConfigurable.kt` | Reset-to-defaults already reads from `SettingsState()` |
| `services/Settings.kt` | `order89Command` field unchanged |
