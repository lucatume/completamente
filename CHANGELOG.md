<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Order 89 tool usage: the model can now call `FileSearch` (project-wide grep) and
  `WebSearch` (stub) tools to gather context before generating code. Uses a two-phase
  architecture (Phase 1 with tools, Phase 2 without) validated at 15/15 accuracy in
  harness testing (rhdp/70–79).
- New `Tool usage` setting with three modes: OFF (default, no tools), MANUAL (tools
  enabled when prompt starts with `/tools`), AUTO (tools always available).
- New `Max tool rounds` setting (default: 3) to cap the tool-calling loop.
- Status display updates during tool execution: "Gathering info... (round N/M)",
  "Searching: \"query\"...", "Generating code...".

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
