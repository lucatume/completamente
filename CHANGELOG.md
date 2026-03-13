<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed
- FIM ghost text gradient updated from neon pink→electric cyan to neon pink→violet.

### Fixed
- FIM suggestions now only appear in main editor windows (no popups, diffs, or tool windows).

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
