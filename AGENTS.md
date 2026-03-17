**Language:** Kotlin
**Platform:** IntelliJ IDEA (Community & Ultimate), 2024.3+
**JDK:** 21

## What is completamente?

completamente is an IntelliJ plugin that provides **fill-in-the-middle (FIM) inline completions** and **AI-powered code
transformations** using a local [llama.cpp](https://github.com/ggerganov/llama.cpp) server. It is designed around the
SweepAI 1.5b FIM model but works with any model served via llama.cpp's `/infill` endpoint.

The plugin draws inspiration from [llama.vim](https://github.com/ggml-org/llama.vim) but has diverged significantly into
its own architecture.

**Key design philosophy:** Pure functions for logic, data classes for information flow, IntelliJ services only where the
platform requires them.

---

## Features

### 1. FIM Inline Completions

Real-time, gray-text inline completions as you type — powered by a local llama.cpp server.

- Triggered automatically on keystrokes (can be toggled off)
- Context-aware: includes cross-file structure references and a ring buffer of recently-seen code chunks
- Token budget system prevents context overflow
- Quality filters discard echoed/redundant suggestions
- KV cache warming for faster follow-up completions
- Coroutine-based; respects cancellation so the IDE never blocks

### 2. Order 89 — Code Transformation

Select code, press `Opt+Cmd+8` (macOS) or `Ctrl+Alt+8`, type a natural-language instruction, and the selection is
replaced with transformed code via a configurable shell command (defaults to piping through `claude`).

- Animated status indicator while the transformation runs
- Automatic output cleaning: extracts code blocks, strips prose, reindents to match surrounding code
- ESC cancels in-flight requests
- Configurable command template in settings

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    IntelliJ IDE                     │
│                                                     │
│  ┌──────────────────┐    ┌───────────────────────┐  │
│  │  FimInlineComple- │    │   Order89Action       │  │
│  │  tionProvider     │    │   Order89Dialog        │  │
│  │  (inline suggest) │    │   Order89Executor      │  │
│  └────────┬─────────┘    └──────────┬────────────┘  │
│           │                         │                │
│  ┌────────▼──────────────────────┐  │  shell subprocess
│  │  Pure Completion Pipeline     │  │  (claude, etc.)
│  │  ┌────────┐ ┌──────────────┐  │  │                │
│  │  │context │ │compose       │  │  │                │
│  │  │ .kt    │ │ .kt          │  │  │                │
│  │  ├────────┤ ├──────────────┤  │  │                │
│  │  │budget  │ │structure     │  │  │                │
│  │  │ .kt    │ │ .kt (PSI)   │  │  │                │
│  │  ├────────┤ ├──────────────┤  │  │                │
│  │  │filters │ │chunk         │  │  │                │
│  │  │ .kt    │ │ .kt          │  │  │                │
│  │  └────────┘ └──────────────┘  │  │                │
│  └────────┬──────────────────────┘  │                │
│           │                         │                │
│  ┌────────▼─────────┐               │                │
│  │  InfillClient    │               │                │
│  │  (HTTP)          │               │                │
│  └────────┬─────────┘               │                │
└───────────┼─────────────────────────┼────────────────┘
            │                         │
            ▼                         ▼
     llama.cpp /infill          shell command
     (local server)             (e.g. claude)
```

### Package Map

| Package       | Purpose                                                                                                     |
|---------------|-------------------------------------------------------------------------------------------------------------|
| `completion/` | Pure functions: context building, request composition, budget allocation, quality filters, chunk management |
| `fim/`        | IntelliJ inline completion provider integration                                                             |
| `order89/`    | Code transformation action, dialog, and subprocess executor                                                 |
| `services/`   | Application/project-level services: settings persistence, ring buffer, cache warming, event listeners       |
| `settings/`   | Settings UI (Tools → completamente)                                                                         |
| `startup/`    | Project startup activity — registers event listeners                                                        |

---

## FIM Completion Pipeline

The inline completion flow is the core of the plugin:

1. **Trigger** — IntelliJ calls `FimInlineCompletionProvider.getSuggestion()` on keystrokes
2. **Snapshot** — All editor/document/PSI state captured in a single `readAction` (`EditorSnapshot`)
3. **Context** — `buildContext()` splits the file at cursor into prefix/suffix (windowed for files > 512+128 lines)
4. **Structure** — `buildStructureChunks()` resolves cross-file references via PSI and extracts public signatures
5. **Budget** — `allocateBudget()` packs file content, structure chunks, and ring chunks into the token budget
6. **Compose** — `composeInfillRequest()` assembles the final `InfillRequest` payload
7. **Send** — `InfillClient` POSTs to `llama.cpp /infill` endpoint
8. **Filter** — `shouldDiscardSuggestion()` checks for echoed/redundant completions
9. **Warm** — `CacheWarmingService` pre-warms KV cache for the next keystroke (debounced, fire-and-forget)

### Context Enrichment

**Structure extraction** (`structure.kt`) — Uses PSI reference resolution to find cross-file symbols, then
`surfaceExtract()` extracts public signatures via a language-agnostic heuristic (brace-depth tracking, skipping
strings/comments).

**Ring buffer** (`ChunksRingBuffer`) — Maintains a rotating set of code chunks from recently opened/saved/copied files.
Chunks are similarity-filtered to avoid redundancy (`chunkSim` = 2 × common lines / total lines; evict if > 0.5).

### Token Budget

- Total budget = `contextSize - nPredict - 20` (overhead for special tokens)
- File content gets first priority
- Remaining budget filled greedily: structure chunks first, then ring chunks
- Rough token estimate: `(charCount + 2) / 3`

### Quality Filters

Four discard rules in `filters.kt`:

1. Empty/whitespace-only suggestion
2. Single-line echo of text already after cursor
3. Combined prefix+suggestion matches next non-blank suffix line
4. Multi-line suggestion matches consecutive suffix lines

Auto-trigger suppression: no completions when > 8 characters exist after the cursor on the current line.

---

## Order 89 — Code Transformation

Flow: User selects text → `Opt+Cmd+8` / `Ctrl+Alt+8` → enters prompt → subprocess runs → output replaces selection.

**Key components:**

- `Order89Action` — Action handler, manages session lifecycle and animated status display
- `Order89Dialog` — Themed prompt input dialog
- `Order89Executor` — Builds a temp prompt file with annotated source, spawns shell subprocess, cleans output

**Output cleaning pipeline:**

1. Extract fenced code blocks (`` ```lang ... ``` ``)
2. Strip leading/trailing prose (text before/after code-like lines)
3. Reindent to match the original selection's indentation

---

## Services

| Service                | Scope       | Role                                                    |
|------------------------|-------------|---------------------------------------------------------|
| `SettingsState`        | Application | Persistent settings (stored in `completamente.xml`)     |
| `ChunksRingBuffer`     | Project     | Ring buffer of recently-seen code chunks                |
| `CacheWarmingService`  | Project     | Debounced KV cache warming (500ms delay, deduplication) |
| `FileOpenCloseService` | Project     | Listens for file open/close → feeds ring buffer         |
| `FileSaveService`      | Project     | Listens for file save → feeds ring buffer               |
| `ClipboardCopyService` | Project     | Listens for clipboard copy → feeds ring buffer          |

---

## Settings

Accessible at **Tools → completamente** in IDE preferences.

| Setting              | Default                             | Description                                    |
|----------------------|-------------------------------------|------------------------------------------------|
| Server URL           | `http://127.0.0.1:8012`             | llama.cpp server endpoint                      |
| Context size         | `32768`                             | Total token budget                             |
| Max predicted tokens | `128`                               | Tokens per completion                          |
| Auto-suggestions     | `true`                              | Enable/disable automatic inline completions    |
| Ring chunk count     | `16`                                | Number of chunks in ring buffer (0 = disabled) |
| Chunk size (lines)   | `64`                                | Lines per ring chunk                           |
| Max queued chunks    | `16`                                | Queue capacity                                 |
| Order 89 command     | `cat {{prompt_file}} \| claude ...` | Shell command template                         |

---

## Build & Test

```bash
# Build distributable plugin
./gradlew buildPlugin

# Run tests
./gradlew test

# Code coverage report
./gradlew koverHtmlReport
```

**Output:** `build/distributions/completamente-0.0.4-dev.zip`

**Install:** Settings → Plugins → Install Plugin from Disk...

### Dependencies

- **kotlinx-serialization** 1.10.0 — JSON serialization for HTTP payloads
- **IntelliJ Platform SDK** 2024.3.6 — IDE APIs and test framework
- **JUnit** 4.13.2 — Testing
- No external ML/LLM libraries — all inference delegated to llama.cpp

### Build Plugins

- `org.jetbrains.kotlin.jvm` — Kotlin compiler
- `org.jetbrains.intellij.platform` — IntelliJ plugin packaging
- `org.jetbrains.kotlin.plugin.serialization` — Serialization codegen
- `org.jetbrains.kotlinx.kover` — Code coverage
- `org.jetbrains.changelog` — Changelog management
- `org.jetbrains.qodana` — Static analysis

---

## Testing Conventions

- **No mocks** — tests use real instances and IntelliJ's test framework (`BasePlatformTestCase`)
- **No fake filesystems** — real test files in `src/test/testData/`
- Test data: inline via `myFixture.configureByText()` (≤15 lines) or fixture files (>15 lines)
- All code paths covered, including error/exception paths
- See `src/test/README.md` for full conventions and data generation details

### Test Coverage Map

| Test File                            | Covers                                                      |
|--------------------------------------|-------------------------------------------------------------|
| `contextTest.kt`                     | File splitting, windowing, boundary conditions (~40 tests)  |
| `filtersTest.kt`                     | Suggestion discarding, auto-trigger suppression (~50 tests) |
| `budgetTest.kt`                      | Token budget allocation                                     |
| `chunkTest.kt`                       | Chunk picking, similarity                                   |
| `composeTest.kt`                     | Request composition                                         |
| `structureTest.kt`                   | PSI reference resolution                                    |
| `Order89ExecutorTest.kt`             | Prompt generation, code cleaning, reindentation (~80 tests) |
| `FimInlineCompletionProviderTest.kt` | Integration tests                                           |
| `CacheWarmingServiceTest.kt`         | Service lifecycle                                           |
| `InfillClientTest.kt`                | HTTP client                                                 |
| `SettingsStateTest.kt`               | Settings persistence                                        |

---

## Error Handling & Resilience

- **Completion failures** → return empty suggestion; never block the IDE
- **HTTP timeouts** → calculated per-request from prompt/predict timing estimates
- **Cache warming failures** → silently ignored (optimization, not critical)
- **PSI access errors** → graceful degradation; empty structure chunks
- **Settings parse errors** → fall back to defaults

---

## Key Design Decisions

1. **Pure functions over services** — Core logic (context, filters, budget, composition) is implemented as stateless
   pure functions. Highly testable, no side effects.

2. **Single read action** — All editor/document/PSI access captured in one `readAction` call into an `EditorSnapshot`.
   No scattered read actions throughout the pipeline.

3. **Coroutine cancellation** — The completion provider checks `ensureActive()` at key points. HTTP requests run on
   `Dispatchers.IO` and respect cancellation via thread interrupt.

4. **Language-agnostic structure extraction** — `surfaceExtract()` uses brace-depth heuristics rather than
   language-specific PSI trees, making it work across languages supported by IntelliJ.

5. **Silent failure** — Completion providers must never crash or block the IDE. All failures result in empty
   suggestions.

---

## File Reference

| Functionality       | Key Files                                                                                         |
|---------------------|---------------------------------------------------------------------------------------------------|
| Entry point         | `plugin.xml`, `FimInlineCompletionProvider.kt`                                                    |
| Context building    | `context.kt`, `structure.kt`, `chunk.kt`                                                          |
| Request composition | `compose.kt`, `infill.kt`, `budget.kt`                                                            |
| Quality control     | `filters.kt`                                                                                      |
| HTTP                | `InfillClient.kt`                                                                                 |
| Cache warming       | `CacheWarmingService.kt`                                                                          |
| Ring buffer         | `ChunksRingBuffer.kt`, `FileOpenCloseService.kt`, `FileSaveService.kt`, `ClipboardCopyService.kt` |
| Settings            | `SettingsState.kt`, `Settings.kt`, `SettingsConfigurable.kt`                                      |
| Code transform      | `Order89Action.kt`, `Order89Dialog.kt`, `Order89Executor.kt`                                      |
| Startup             | `CompletamenteStartupActivity.kt`                                                                 |
