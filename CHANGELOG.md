<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Initial FIM inline completion with llama.cpp server.
- Next-edit prediction (NEP) with sub-edit TAB cycling.
- Diff tracking, chunk ring buffer, and cross-file definition context.
- Server management: auto-detect, start/stop, and settings UI.
- Editable server command textarea with `-hf` HuggingFace model loading.
- Server URL auto-locks when managed server is running.
- Token budgeting to fit prompts within the 8192-token context window.
- Jump edit styling and no-op InlineCompletionProvider.

### Changed
- Replaced binary/model/context-size fields with single server command.
- DiffEntry stores pre-truncated text; token estimates pre-computed at collection time.

### Fixed
- Ghost text, DiffTracker EDT performance, diff ordering, and extractEdit edge cases.
