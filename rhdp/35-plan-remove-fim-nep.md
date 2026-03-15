# Plan: Remove FIM/NEP Completion Feature

Remove all source and test files implementing the SweepAI-based fill-in-the-middle and next-edit prediction features. **Keep** the Order 89 feature and the background fragment collection (ring buffer / chunks).

## Scope Summary

**Remove**: FIM completion engine, ghost text rendering, TAB/ESC handlers, typing listener, suggestion manager, inline completion suppressor, server manager, diff tracker, definitions/structure analysis, and all associated tests and settings.

**Keep**: Order 89 (action, dialog, executor, tests), background fragment collection (ChunksRingBuffer, chunk.kt, FileOpenCloseService, FileSaveService, ClipboardCopyService, chunkTest.kt), and the startup wiring for fragment collection.

## Steps

### Step 1 — Delete FIM source files

Delete the entire `fim/` package directory:

- `src/main/kotlin/.../fim/FimSuggestionManager.kt`
- `src/main/kotlin/.../fim/FimTypingListener.kt`
- `src/main/kotlin/.../fim/RequestFimAction.kt`
- `src/main/kotlin/.../fim/FimActionHandlers.kt`
- `src/main/kotlin/.../fim/FimActionHandlerRegistrar.kt`
- `src/main/kotlin/.../fim/GhostTextRenderer.kt`
- `src/main/kotlin/.../fim/NoOpInlineCompletionProvider.kt`

### Step 2 — Delete FIM completion logic

Delete FIM-only files from the `completion/` package:

- `src/main/kotlin/.../completion/fim.kt` — core FIM request/response logic
- `src/main/kotlin/.../completion/definitions.kt` — structure file collection (only used by FIM prompt)
- `src/main/kotlin/.../completion/structure.kt` — code structure extraction (only used by FIM prompt)

**Keep**: `src/main/kotlin/.../completion/chunk.kt` (used by background fragment collection).

### Step 3 — Delete FIM-only services

These services exist solely to support FIM completions:

- `src/main/kotlin/.../services/ServerManager.kt` — llama.cpp server lifecycle management
- `src/main/kotlin/.../services/DiffTracker.kt` — edit diff tracking for FIM context
- `src/main/kotlin/.../services/CursorMovementTracker.kt` — appears unused, remove as cleanup

**Keep**: ChunksRingBuffer, ClipboardCopyService, FileOpenCloseService, FileSaveService.

### Step 4 — Delete FIM test files

- `src/test/kotlin/.../completion/fimTest.kt`
- `src/test/kotlin/.../completion/EditKindFromHarnessTest.kt`
- `src/test/kotlin/.../completion/DefinitionsTest.kt`
- `src/test/kotlin/.../completion/StructureTest.kt`
- `src/test/kotlin/.../fim/FimTypingListenerTest.kt`
- `src/test/kotlin/.../fim/RequestFimActionTest.kt`
- `src/test/kotlin/.../services/DiffTrackerTest.kt`
- `src/test/kotlin/.../services/ServerManagerTest.kt`
- `src/test/kotlin/.../BaseCompletionTest.kt` — check if used only by FIM tests; if so, delete

**Keep**: `src/test/kotlin/.../completion/chunkTest.kt`, all `order89/` tests, `SettingsStateTest.kt`, `SettingsConfigurableTest.kt`.

### Step 5 — Update `plugin.xml`

Remove these entries from `src/main/resources/META-INF/plugin.xml`:

1. **Service**: `<projectService serviceImplementation="...fim.FimSuggestionManager"/>` — remove
2. **Service**: `<projectService serviceImplementation="...services.DiffTracker"/>` — remove
3. **Service**: `<applicationService serviceImplementation="...services.ServerManager"/>` — remove
4. **Inline completion provider**: the entire `<inline.completion.provider ...NoOpInlineCompletionProvider/>` block — remove
5. **Action**: the `RequestFimAction` action block (Alt+Backslash) — remove

**Keep**: `postStartupActivity`, `applicationConfigurable`, `notificationGroup`, and the `Order89Action` action block.

### Step 6 — Update `CompletamenteStartupActivity`

Remove from `execute()`:

- `DiffTracker` service retrieval and all references (snapshot on file open, document listener registration, finalize on file close)
- `FimActionHandlerRegistrar.register()` call
- `FimTypingListener` creation and registration
- `ServerManager.adoptExternalServer()` call

**Keep**: All `ChunksRingBuffer` / `handleFile` / `pickChunkFromFile` / `pickChunkFromText` wiring (file open/close/save/clipboard handlers). The `handleFile` private method stays as-is.

After edit, remove unused imports for FIM/DiffTracker/ServerManager.

### Step 7 — Update `Settings` and `SettingsState`

Remove FIM-specific settings fields from both `Settings` (data class) and `SettingsState` (persistent state):

- `serverUrl` — remove
- `autoSuggestions` — remove
- `maxRecentDiffs` — remove
- `serverCommand` — remove

**Keep**: `ringNChunks`, `ringChunkSize`, `maxQueuedChunks` (fragment collection), and `order89Command`.

Update `toSettings()` in `SettingsState` accordingly.

### Step 8 — Update `SettingsConfigurable`

Remove from the settings UI:

- The entire **"FIM Suggestions"** group (server URL, auto suggestions checkbox, recent diffs field)
- The entire **"Server Management"** group (server command textarea, status label, start/stop button, view logs link)
- All fields, methods, and imports related to server management: `serverUrlField`, `statusLabel`, `serverButton`, `viewLogsLink`, `serverCommandArea`, `refreshServerStatus()`, `updateStatusUI()`, `onServerButtonClick()`
- Remove `ServerManager` import

**Keep**: **"Ring Buffer (Extra Context)"** group and **"Order 89"** group with their fields and logic.

Update `loadFromState()`, `applyToState()`, `isModified()`, `apply()`, `reset()` to remove references to deleted fields.

### Step 9 — Update `SettingsStateTest` and `SettingsConfigurableTest`

Review and update these tests to remove assertions about deleted settings fields (serverUrl, autoSuggestions, maxRecentDiffs, serverCommand). Keep assertions about ring buffer and order89Command settings.

### Step 10 — Delete test data files (if any)

Check for test resource/data files under `src/test/` that were generated for FIM tests (e.g., harness output files, fixture files). Delete any that are no longer referenced.

### Step 11 — Build verification

Run a full build to ensure:
- No compilation errors from missing imports or references
- All remaining tests pass
- The plugin can be assembled without errors

```bash
./gradlew clean build
```

## File inventory

### Files to DELETE (17 source + ~9 test)

| File | Reason |
|------|--------|
| `src/main/.../fim/FimSuggestionManager.kt` | FIM suggestion lifecycle |
| `src/main/.../fim/FimTypingListener.kt` | FIM auto-trigger |
| `src/main/.../fim/RequestFimAction.kt` | FIM manual trigger |
| `src/main/.../fim/FimActionHandlers.kt` | FIM TAB/ESC handlers |
| `src/main/.../fim/FimActionHandlerRegistrar.kt` | FIM handler registration |
| `src/main/.../fim/GhostTextRenderer.kt` | FIM ghost text rendering |
| `src/main/.../fim/NoOpInlineCompletionProvider.kt` | Suppresses built-in completions for FIM |
| `src/main/.../completion/fim.kt` | FIM request/response logic |
| `src/main/.../completion/definitions.kt` | Structure file collection for FIM |
| `src/main/.../completion/structure.kt` | Code structure extraction for FIM |
| `src/main/.../services/ServerManager.kt` | llama.cpp server management |
| `src/main/.../services/DiffTracker.kt` | Edit diff tracking for FIM |
| `src/main/.../services/CursorMovementTracker.kt` | Unused |
| `src/test/.../completion/fimTest.kt` | Tests FIM logic |
| `src/test/.../completion/EditKindFromHarnessTest.kt` | Tests FIM edit kinds |
| `src/test/.../completion/DefinitionsTest.kt` | Tests definitions (FIM-only) |
| `src/test/.../completion/StructureTest.kt` | Tests structure (FIM-only) |
| `src/test/.../fim/FimTypingListenerTest.kt` | Tests FIM typing listener |
| `src/test/.../fim/RequestFimActionTest.kt` | Tests FIM action |
| `src/test/.../services/DiffTrackerTest.kt` | Tests DiffTracker |
| `src/test/.../services/ServerManagerTest.kt` | Tests ServerManager |
| `src/test/.../BaseCompletionTest.kt` | Check if FIM-only; delete if so |

### Files to EDIT (4)

| File | Changes |
|------|---------|
| `plugin.xml` | Remove FIM services, action, inline completion provider |
| `CompletamenteStartupActivity.kt` | Remove FIM/DiffTracker/ServerManager wiring |
| `Settings.kt` + `SettingsState.kt` | Remove FIM settings fields |
| `SettingsConfigurable.kt` | Remove FIM and Server Management UI groups |

### Files to KEEP untouched

| File | Reason |
|------|--------|
| `completion/chunk.kt` | Background fragment collection |
| `services/ChunksRingBuffer.kt` | Ring buffer service |
| `services/FileOpenCloseService.kt` | File events for fragments |
| `services/FileSaveService.kt` | Save events for fragments |
| `services/ClipboardCopyService.kt` | Clipboard events for fragments |
| `order89/Order89Action.kt` | Order 89 feature |
| `order89/Order89Dialog.kt` | Order 89 feature |
| `order89/Order89Executor.kt` | Order 89 feature |
| All `order89/*Test.kt` | Order 89 tests |
| `completion/chunkTest.kt` | Fragment collection tests |
