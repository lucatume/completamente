<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed
- Order 89 now shells out to a user-configured agentic CLI instead of calling a local
  llama.cpp `/completion` endpoint. The plugin writes the prompt + selection to a temp
  file, runs the configured command with its working directory set to the project root,
  and post-processes the CLI's STDOUT exactly as before. Tool selection, search, and
  context gathering are delegated to the agent.
- Order 89 now invokes the configured command via the user's login shell
  (`$SHELL -lc`, falling back to `/bin/sh`) instead of `ProcessBuilder` with a
  plugin-side tokenized argv. PATH and other init from `~/.zprofile`/`~/.bash_profile`
  are picked up, matching what users see in their terminal — fixes `pi: command not
  found` for IDE launches that didn't inherit a configured shell PATH (Spotlight, Dock,
  launchd). Quoting is now the shell's job; the plugin no longer ships a POSIX
  tokenizer.
- ESC cancellation now snapshots and signals the shell's descendants before terminating
  the shell wrapper itself, so commands that pipe (`pi … | tee log`) or background
  (`cmd &`) still get torn down cleanly within the 250 ms grace window.
- New single setting `Order 89 CLI command` with default
  `pi --tools read,grep,find,ls @"%%prompt_file%%" -p "Execute the instructions in the files"`.
  The placeholder `%%prompt_file%%` is substituted at runtime with the absolute path to
  the generated prompt file. Standard shell quoting applies — wrap paths in `"..."`.
- Order 89 prompts now reference files by escaped POSIX path rather than embedding
  structure-extracted context. The agent fetches what it needs through its own tools.
  Embedded file content for the file under edit is capped at 200k characters, with a
  50k window each side of the selection for larger files.
- Order 89 prompt explicitly instructs the agent to return the modified selection as a
  single fenced code block — never to edit files directly — so concurrent invocations
  on overlapping ranges cannot corrupt the buffer through line drift.
- ESC cancellation destroys the running CLI process (non-blocking, force-kills after a
  250 ms grace period) and always deletes the temp prompt file.

### Removed
- Order 89 settings: `order89ServerUrl`, `order89Temperature`, `order89TopP`,
  `order89TopK`, `order89RepeatPenalty`, `order89NPredict`, `order89ToolUsage`,
  `order89MaxToolRounds`. Replaced by the single `order89CliCommand` setting.
- In-plugin Order 89 tool pipeline (`Order89Tools`, `ToolTypes`, two-phase orchestration).
  The agentic CLI provides its own tools.

### Fixed
- Empty or truncated CLI output is surfaced as an error notification instead of silently
  replacing the selection with nothing. Output streams are bounded at 8 MB to avoid OOM
  on runaway agents.

## [0.0.4] - 2026-03-20

### Changed
- Order 89: replaced Claude Code shell-command backend with direct HTTP calls to a local
  llama.cpp `/completion` endpoint. The prompt structure has been optimized for
  Qwen3-Coder-30B-A3B-Instruct via extensive harness testing (rhdp/51–67).
- Order 89 now sends structure-extracted context (API signatures of referenced files) to
  improve generated code accuracy (correct method calls, try/catch patterns).
- Order 89 prompt includes a convention-matching reminder so generated code mirrors the
  documentation style of the file under edit (docblocks, type annotations, naming).
- Order 89 sampling parameters (temperature, top_p, top_k, repeat_penalty, n_predict)
  are now user-configurable in settings, defaulting to Qwen3-Coder recommended values.
- Order 89 server URL is now a separate setting (default: `http://127.0.0.1:8017`),
  independent of the FIM completions server.
- Order 89 status display uses editor-scheme colors instead of hardcoded neon pink/electric
  blue gradient; the "Executing..." line uses the hyperlink color, prompt lines use the
  default foreground.
- Order 89 dialog themed with editor-derived colors and wider padding.
- Order 89 keyboard shortcut changed to `Ctrl+Alt+8` (`Opt+Cmd+8` on macOS).
- FIM completions rewritten to use the llama.cpp `/infill` endpoint directly.
- FIM completions now trim leading/trailing whitespace and reindent suggestions to match
  project code style.

### Removed
- Order 89 shell command setting (`order89Command`) and `{{prompt_file}}` template mechanism.
- Order 89 temp file creation for prompt delivery.
- Claude Code dependency for Order 89.
- Previous FIM/NEP completion feature (replaced by `/infill`-based FIM).

### Fixed
- FIM suggestions now only appear in main editor windows (no popups, diffs, or tool windows).
- Order 89 status text no longer reappears on undo.
- Order 89 status display undo suppression hardened with `UndoUtil.disableUndoIn`.

## [0.0.3] - 2026-03-12

### Added
- Order 89: an in-editor command dialog inspired by [ThePrimeagen/99](https://github.com/ThePrimeagen/99).
  Pipe selected text through a configurable shell command without leaving the editor.
- Prompt dialog themed to match the editor's colors and font size.
- Animated status display with rotating symbol and soft-wrapped prompt preview
  inserted directly into the document text (undo-transparent).
- Multi-session support: run multiple Order 89 commands concurrently.
- ESC cancellation via cursor movement away from the active session.
- Keyboard shortcut: `Ctrl+Shift+8` (`Cmd+Shift+8` on macOS).

### Changed
- Status display evolved from inlay hints to document-text lines for better readability.

## [0.0.2]

### Added
- Next-edit prediction (NEP) with sub-edit TAB cycling.
- Diff tracking, chunk ring buffer, and cross-file definition context.
- Server management: auto-detect, start/stop, and settings UI.
- Editable server command textarea with `-hf` HuggingFace model loading.
- Server URL auto-locks when managed server is running.
- Token budgeting to fit prompts within the 8192-token context window.
- Jump edit styling and no-op InlineCompletionProvider.
- Window-based symbol resolution and header-based structure files for prompt composition.

### Changed
- Switched from llama.vim FIM-only approach to Sweep AI 1.5B FIM/NEP model.
- Replaced binary/model/context-size fields with single server command.
- DiffEntry stores pre-truncated text; token estimates pre-computed at collection time.
- Prompt composition v2: asymmetric window, revised budget ordering.

### Fixed
- Ghost text, DiffTracker EDT performance, diff ordering, and extractEdit edge cases.

## [0.0.1]

### Added
- Initial FIM inline completion with llama.cpp server.
- HTTP request layer for llama.cpp server communication.
- Suffix overlap stripping from server completions.
- Plugin settings UI.
