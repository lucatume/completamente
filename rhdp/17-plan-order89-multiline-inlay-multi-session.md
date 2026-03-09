# Plan: Order 89 — Multi-line Inlay + Multiple Sessions

## Goal
Update Order 89 to (1) show the user's prompt text in the executing inlay and (2) support multiple concurrent sessions.

## Current State
- `Order89Action.kt` shows a single-line animated "★ Executing Order 89" block inlay
- Only one session runs at a time; ESC handler registered per-session via `registerCustomShortcutSet`
- `BlockGhostRenderer` in `GhostTextRenderer.kt:50-80` already demonstrates multi-line block inlays

## Changes (all in `Order89Action.kt`)

### Multi-line inlay
- `addExecutingInlay` gains a `prompt` parameter
- Renderer height: `editor.lineHeight * 2`
- Paint draws line 1 (animated status) and line 2 (truncated prompt) at `indentX`
- `truncatePrompt(prompt, maxLength=60)`: collapse newlines, truncate with "..."

### Multiple sessions
- `Order89Session` data class: `process`, `future`, `inlay`
- Per-editor `ConcurrentLinkedDeque<Order89Session>` stored via `Key` on editor `UserDataHolder`
- Shared ESC action registered once when first session starts; pops most recent session (LIFO)
- Each session removes itself on completion; ESC unregistered when stack empties

## Verification
- `./gradlew test` — existing tests pass
- Manual: trigger multiple Order 89s, verify independent inlays, ESC cancels most recent, completions apply correctly
