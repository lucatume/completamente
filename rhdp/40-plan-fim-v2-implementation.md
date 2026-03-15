# Plan: FIM v2 Implementation — llama.vim-style `/infill` with 32k Context

Implements the design in `36-design-prompt-structure-v2.md`.

## Execution Model

You are a coordinator, not an executor. For each step:

1. Dispatch a sub-agent to implement using TDD (see below).
2. Dispatch two independent reviewer sub-agents (they must not see each other's output). Each grades 1–5 on: correctness, completeness, style consistency, test coverage, performance, security. Reviewers must also flag any test smells (see below).
3. Repeat until both reviewers give 5, max 5 rounds. After 5 rounds, present the best attempt and move on.

Execute the entire plan without stopping for approval.

## TDD (Red-Green-Refactor)

For every change:

1. **Red** — Write a failing test for the desired behavior. Run it; confirm it fails for the right reason.
2. **Green** — Write the minimum code to pass the test. Nothing more.
3. **Refactor** — Clean up while keeping tests green.

Rules:
- One behavior, one test, one pass per cycle.
- Never write production code without a failing test that demands it.
- Run all tests after every green and refactor step.
- If a test is hard to write, simplify the interface, not the test.
- Checkpoint after each successful green or refactor.

## Test Smells (reviewer checklist)

| Smell | Signal | Fix |
|---|---|---|
| **Echo Testing** | Expected value is computed (`a + b`) instead of literal (`15`) | Hard-code expected values |
| **Constructor Drift** | Test constructor args don't match actual signature | Verify against source constructor |
| **Dodger Assertions** | Asserts only "no error" without checking content | Add content/value assertions |
| **Happy Path Bias** | No tests for empty input, nulls, errors, boundaries | Add specific edge/error cases |
| **Docblock Bloat** | Generic multi-line comments on every test | Max 1–3 lines: what regression does this prevent? |
| **Mock-Testing-Mocks** | Mock returns X, assert result is X, no logic between | Verify meaningful transformation exists or remove test |
| **Coverage Blind Spots** | High-risk files (auth, crypto, HTTP, payments) have no tests | List untested files, prioritize by risk |

## Codebase Conventions

- **Language**: Kotlin
- **Test framework**: JUnit 4 via IntelliJ Platform Test Framework
- **Test base class**: Extend `BasePlatformTestCase` (aliased as `BaseCompletionTest` for completion tests)
- **No mocks**: Use real instances, real test files
- **Code style**: Data classes for information, pure functions for transformation, classes only when required by IntelliJ's service system
- **Existing token estimator**: `estimateTokens(text)` in `completion/chunk.kt` → `(text.length + 2) / 3`
- **Package root**: `com.github.lucatume.completamente`
- **Build command**: `./gradlew test` (run from project root)

## Design Reference

Read `rhdp/36-design-prompt-structure-v2.md` before starting each step. Key decisions:

- **Endpoint**: `/infill` on llama.cpp server (Qwen3-Coder-30B-A3B)
- **Context**: 32k tokens, client-side budget management
- **Current file**: whole file if <10k tokens, else 512 prefix / 128 suffix window
- **`input_extra`**: structure files (sorted by path) → ring chunks (sorted by stable key), similarity >0.5 to cursor context evicted
- **Output**: `n_predict: 128`, `t_max_predict_ms: 250`
- **Lifecycle**: cancel-and-replace, one request in flight, background cache warming
- **Quality**: repeat detection, mid-line gating (>8 chars after cursor), whitespace-only line → empty prompt

---

## Step 1 — Settings: add FIM server configuration

**Files to edit**:
- `src/main/kotlin/.../services/Settings.kt`
- `src/main/kotlin/.../services/SettingsState.kt`
- `src/main/kotlin/.../settings/SettingsConfigurable.kt`
- `src/test/kotlin/.../services/SettingsStateTest.kt`
- `src/test/kotlin/.../settings/SettingsConfigurableTest.kt`

**What to add**:
- `serverUrl: String` (default `"http://127.0.0.1:8012"`)
- `contextSize: Int` (default `32768`)
- `nPredict: Int` (default `128`)
- `autoSuggestions: Boolean` (default `true`)

Add these to `Settings` data class, `SettingsState` persistent state, and the settings UI panel (new "FIM Completions" group above "Ring Buffer").

**Acceptance criteria**:
- Settings persist across IDE restarts
- Settings UI shows new fields with defaults
- `toSettings()` maps all new fields correctly
- Non-numeric input falls back to defaults

---

## Step 2 — Infill data model: request and response types

**Files to create**:
- `src/main/kotlin/.../completion/infill.kt`
- `src/test/kotlin/.../completion/infillTest.kt`

**What to build**:

Data classes for the `/infill` request and response:

```kotlin
@Serializable
data class InfillExtraChunk(
    val filename: String,
    val text: String
)

@Serializable
data class InfillRequest(
    @SerialName("id_slot") val idSlot: Int = 0,
    @SerialName("input_prefix") val inputPrefix: String,
    @SerialName("input_suffix") val inputSuffix: String,
    @SerialName("input_extra") val inputExtra: List<InfillExtraChunk> = emptyList(),
    val prompt: String = "",
    @SerialName("n_predict") val nPredict: Int = 128,
    @SerialName("n_indent") val nIndent: Int = 0,
    @SerialName("top_k") val topK: Int = 40,
    @SerialName("top_p") val topP: Double = 0.90,
    val samplers: List<String> = listOf("top_k", "top_p", "infill"),
    val stream: Boolean = false,
    @SerialName("cache_prompt") val cachePrompt: Boolean = true,
    @SerialName("t_max_prompt_ms") val tMaxPromptMs: Int = 500,
    @SerialName("t_max_predict_ms") val tMaxPredictMs: Int = 250,
    @SerialName("response_fields") val responseFields: List<String> = listOf(
        "content", "timings/prompt_n", "timings/predicted_n",
        "timings/predicted_ms", "truncated", "tokens_cached"
    ),
    val temperature: Double = 0.0
)

@Serializable
data class InfillResponse(
    val content: String = "",
    @SerialName("timings/prompt_n") val promptN: Int = 0,
    @SerialName("timings/predicted_n") val predictedN: Int = 0,
    @SerialName("timings/predicted_ms") val predictedMs: Double = 0.0,
    val truncated: Boolean = false,
    @SerialName("tokens_cached") val tokensCached: Int = 0
)
```

Also: a `buildCacheWarmingRequest(extra: List<InfillExtraChunk>): InfillRequest` function that creates the cache-warming variant (`n_predict: 0`, `t_max_prompt_ms: 1`, empty prefix/suffix/prompt, `samplers: []`, `response_fields: [""]`).

**Acceptance criteria**:
- `InfillRequest` serializes to JSON matching the llama.cpp `/infill` API exactly (field names, types)
- `InfillResponse` deserializes from the `response_fields`-filtered JSON format (flat `timings/prompt_n` keys)
- `buildCacheWarmingRequest` produces correct warming shape
- Round-trip serialization/deserialization tests pass
- Default values match the design doc

---

## Step 3 — Context assembly: prefix/suffix/prompt split

**Files to create**:
- `src/main/kotlin/.../completion/context.kt`
- `src/test/kotlin/.../completion/contextTest.kt`

**What to build**:

Pure functions to split file content at the cursor into `/infill` fields:

```kotlin
data class FileContext(
    val inputPrefix: String,   // all lines above cursor line
    val inputSuffix: String,   // rest of current line after cursor + all lines below
    val prompt: String,        // current line up to cursor (or "" if whitespace-only)
    val nIndent: Int           // indentation level of current line (or 0 if whitespace-only)
)

fun buildFileContext(
    fileContent: String,
    cursorLine: Int,      // 0-based line number
    cursorColumn: Int     // 0-based column in the line
): FileContext
```

Also: the windowed fallback when the file is too large:

```kotlin
data class WindowedFileContext(
    val inputPrefix: String,
    val inputSuffix: String,
    val prompt: String,
    val nIndent: Int,
    val windowStartLine: Int,
    val windowEndLine: Int
)

fun buildWindowedFileContext(
    fileContent: String,
    cursorLine: Int,
    cursorColumn: Int,
    prefixLines: Int = 512,
    suffixLines: Int = 128
): WindowedFileContext
```

And the decision function:

```kotlin
fun buildContext(
    fileContent: String,
    cursorLine: Int,
    cursorColumn: Int,
    maxFileTokens: Int = 10000,
    prefixLines: Int = 512,
    suffixLines: Int = 128
): FileContext
```

This calls `estimateTokens(fileContent)`. If under `maxFileTokens`, returns `buildFileContext`. Otherwise, returns `buildWindowedFileContext` (mapped to `FileContext`).

**Whitespace-only line handling**: When the current line is all whitespace, set `prompt = ""` and `nIndent = 0`.

**Acceptance criteria**:
- Correct split at various cursor positions: start of file, end of file, middle of line, start of line, end of line
- Empty file
- Single-line file
- Cursor on last line (no suffix lines below)
- Cursor on first line (no prefix lines above)
- Whitespace-only current line → prompt="" and nIndent=0
- Windowed fallback activates at the right threshold
- Window is asymmetric: 512 above, 128 below
- Window clamped at file boundaries (cursor near start/end)

---

## Step 4 — Token budgeting and `input_extra` assembly

**Files to create**:
- `src/main/kotlin/.../completion/budget.kt`
- `src/test/kotlin/.../completion/budgetTest.kt`

**What to build**:

Pure functions to allocate the token budget and assemble `input_extra`:

```kotlin
data class BudgetAllocation(
    val fileContext: FileContext,
    val structureChunks: List<InfillExtraChunk>,  // structure files that fit
    val ringChunks: List<InfillExtraChunk>,        // ring chunks that fit
    val totalEstimatedTokens: Int
)

fun allocateBudget(
    fileContext: FileContext,
    structureFiles: List<InfillExtraChunk>,  // pre-sorted by filename
    ringChunks: List<InfillExtraChunk>,       // pre-sorted by stable key
    contextSize: Int = 32768,
    nPredict: Int = 128,
    overheadTokens: Int = 20
): BudgetAllocation
```

Algorithm:
1. `promptBudget = contextSize - nPredict - overheadTokens`
2. `fileTokens = estimateTokens(fileContext.inputPrefix + fileContext.prompt + fileContext.inputSuffix)`
3. `remaining = promptBudget - fileTokens`
4. Add structure files one by one (sorted by filename) until budget exhausted or all added
5. Add ring chunks one by one (sorted by stable key) until budget exhausted or all added
6. Return `BudgetAllocation` with what fit

Also: the ring chunk similarity filter:

```kotlin
fun filterRingChunks(
    ringChunks: List<Chunk>,
    cursorContext: String,
    similarityThreshold: Double = 0.5
): List<InfillExtraChunk>
```

This converts `Chunk` objects to `InfillExtraChunk`, excluding any with `chunkSim > threshold` against the cursor context lines.

**Acceptance criteria**:
- Budget math is correct for various context sizes
- Structure files are added in order, stopped when budget hit
- Ring chunks fill remaining space
- A single huge structure file that exceeds remaining budget is skipped (try next)
- Empty inputs (no structure files, no ring chunks) produce valid allocation
- `filterRingChunks` removes chunks with similarity >0.5 to cursor context
- `filterRingChunks` keeps chunks with similarity <=0.5
- Chunks are sorted by filename/stable key in the output

---

## Step 5 — HTTP client for `/infill`

**Files to create**:
- `src/main/kotlin/.../completion/InfillClient.kt`
- `src/test/kotlin/.../completion/InfillClientTest.kt`

**What to build**:

A client that sends requests to the llama.cpp `/infill` endpoint:

```kotlin
class InfillClient(private val baseUrl: String) {
    fun sendCompletion(request: InfillRequest): InfillResponse
    fun sendCacheWarming(extra: List<InfillExtraChunk>)
    fun isServerReachable(): Boolean
}
```

Use `java.net.http.HttpClient` (available in Java 11+, project targets Java 21).
- POST JSON to `$baseUrl/infill`
- Serialize request with kotlinx.serialization
- Deserialize response with kotlinx.serialization (must handle flat `timings/prompt_n` keys)
- Timeout: 5 seconds for connection, `tMaxPredictMs + tMaxPromptMs + 1000` for request
- `sendCacheWarming` uses `buildCacheWarmingRequest` from Step 2

**Cancellation support**: The HTTP request must be cancellable from another thread. Use `HttpClient.sendAsync()` returning a `CompletableFuture`, or accept a `CancellationSignal` / check `Thread.interrupted()`.

**Acceptance criteria**:
- Serializes request correctly (verified by inspecting JSON output)
- Handles server-not-reachable gracefully (returns error, no crash)
- Handles malformed response gracefully
- Handles timeout gracefully
- Request can be cancelled mid-flight
- `isServerReachable` checks `/health` endpoint

**Note**: Full integration tests require a running server and should be marked with `@Ignore` or a custom annotation. Unit tests should verify serialization/deserialization with static JSON strings.

---

## Step 6 — Quality filters: repeat detection, auto-trigger gating

**Files to create**:
- `src/main/kotlin/.../completion/filters.kt`
- `src/test/kotlin/.../completion/filtersTest.kt`

**What to build**:

Pure functions for suggestion quality filtering:

```kotlin
// Returns true if the suggestion should be discarded
fun shouldDiscardSuggestion(
    suggestion: String,
    suffixText: String,       // text after cursor in the file
    prefixLastLine: String    // the typed portion of the current line
): Boolean

// Returns true if auto-trigger should be suppressed
fun shouldSuppressAutoTrigger(
    lineAfterCursor: String,  // text after cursor on the current line
    maxSuffixLength: Int = 8
): Boolean
```

`shouldDiscardSuggestion` checks:
1. Suggestion is empty or whitespace-only
2. First line of suggestion matches the start of `suffixText`
3. First line of suggestion + `prefixLastLine` matches the next non-empty line in `suffixText`
4. Multi-line suggestion matches consecutive lines in `suffixText`

`shouldSuppressAutoTrigger` returns true when `lineAfterCursor.length > maxSuffixLength`.

**Acceptance criteria**:
- Empty/whitespace suggestion → discard
- Suggestion echoes suffix → discard
- Suggestion + typed text matches next line → discard
- Multi-line echo → discard
- Legitimate suggestion passes all checks
- Auto-trigger suppressed at >8 chars after cursor
- Auto-trigger allowed at <=8 chars after cursor
- Edge cases: empty suffix, empty suggestion, single-character suffix

---

## Step 7 — Structure file resolution via PSI and Structure View API

**Files to create**:
- `src/main/kotlin/.../completion/structure.kt`
- `src/test/kotlin/.../completion/structureTest.kt`

**What to build**:

Functions to resolve referenced project files and surface-extract their public API:

```kotlin
// Resolve all project files referenced by symbols in the given PSI range
fun collectReferencedFiles(
    psiFile: PsiFile,
    startLine: Int,  // 0-based, inclusive
    endLine: Int     // 0-based, exclusive
): Set<VirtualFile>

// Surface-extract public signatures from a file via Structure View
fun surfaceExtract(
    project: Project,
    file: VirtualFile
): String?

// High-level: resolve and extract structure chunks for input_extra
fun buildStructureChunks(
    psiFile: PsiFile,
    wholeFile: Boolean,
    windowStartLine: Int = 0,
    windowEndLine: Int = 0,
    headerLines: Int = 32
): List<InfillExtraChunk>
```

`buildStructureChunks` behavior:
- **Whole file mode** (`wholeFile = true`): single-pass PSI walk over entire file, resolve all symbols, surface-extract each unique project file
- **Windowed mode** (`wholeFile = false`): resolve symbols from first `headerLines` lines AND from `windowStartLine..windowEndLine`, deduplicate, surface-extract each

Results sorted by file path for stable `input_extra` ordering.

Filter out: non-local files (stdlib, vendor/libraries), the current file itself, files where `surfaceExtract` returns null or empty.

**Acceptance criteria**:
- Resolves import references to project files
- Resolves symbol references within the scan range
- Skips library/SDK files
- Skips the current file
- Deduplicates by file path
- Surface extraction produces signatures without method bodies
- Empty file → empty list
- File with no project references → empty list
- Results sorted by file path
- Windowed mode scans both header and window regions

**Note**: These tests require IntelliJ's PSI infrastructure and must extend `BasePlatformTestCase`. Create test fixture files in `src/test/testData/` with known import structures.

---

## Step 8 — Request composition: assemble the full `/infill` request

**Files to create**:
- `src/main/kotlin/.../completion/compose.kt`
- `src/test/kotlin/.../completion/composeTest.kt`

**What to build**:

The top-level pure function that composes all pieces into a ready-to-send `InfillRequest`:

```kotlin
data class CompletionContext(
    val filePath: String,
    val fileContent: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val structureFiles: List<InfillExtraChunk>,
    val ringChunks: List<Chunk>,
    val settings: Settings
)

fun composeInfillRequest(ctx: CompletionContext): InfillRequest
```

This function orchestrates:
1. `buildContext()` → `FileContext` (whole file or windowed)
2. `filterRingChunks()` → filtered and converted ring chunks
3. `allocateBudget()` → what fits in the token budget
4. Assemble `InfillRequest` with all fields populated

**Acceptance criteria**:
- Whole file used when small enough
- Window fallback when file exceeds threshold
- Structure files sorted by path in `input_extra`
- Ring chunks after structure files in `input_extra`
- Ring chunks with >0.5 similarity to cursor context excluded
- Budget respected (total estimated tokens <= context size - nPredict - overhead)
- Whitespace-only current line → prompt="" and nIndent=0
- All request fields populated correctly (samplers, response_fields, etc.)

---

## Step 9 — Background cache warming service

**Files to create**:
- `src/main/kotlin/.../services/CacheWarmingService.kt`
- `src/test/kotlin/.../services/CacheWarmingServiceTest.kt`

**What to build**:

A project-level service that periodically warms the KV cache with the current `input_extra`:

```kotlin
@Service(Service.Level.PROJECT)
class CacheWarmingService(private val project: Project) : Disposable {
    // Call when input_extra content has changed (structure files resolved, ring buffer updated)
    fun scheduleWarmup(extra: List<InfillExtraChunk>)

    // Returns the most recently warmed extras (for comparison / cache hit detection)
    fun getLastWarmedExtras(): List<InfillExtraChunk>

    override fun dispose()
}
```

Behavior:
- Debounces warmup requests (if a new `scheduleWarmup` arrives within 500ms, cancel the previous)
- Sends the cache-warming request on a background thread (pooled executor)
- Stores the last-warmed extras for comparison
- Uses the `InfillClient` to send the request
- Respects `Disposable` lifecycle (cancel pending work on dispose)

**Acceptance criteria**:
- Debounces rapid calls (only last warmup fires)
- Sends correct cache-warming request shape
- Stores last-warmed extras
- Dispose cancels pending work
- Does not block the EDT

---

## Step 10 — `InlineCompletionProvider`: cancel-and-replace lifecycle

**Files to create**:
- `src/main/kotlin/.../fim/FimInlineCompletionProvider.kt`
- `src/test/kotlin/.../fim/FimInlineCompletionProviderTest.kt`

**What to build**:

The IntelliJ `InlineCompletionProvider` implementation:

```kotlin
class FimInlineCompletionProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("completamente.fim")

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion

    override fun isEnabled(event: InlineCompletionEvent): Boolean
}
```

Behavior:
- `isEnabled`: returns `false` if `autoSuggestions` setting is off, or if `shouldSuppressAutoTrigger` returns true for the current cursor position
- `getSuggestion`:
  1. Gather context: file content, cursor position, PSI file
  2. Build structure files via `buildStructureChunks` (on a background thread via `readAction`)
  3. Get ring chunks from `ChunksRingBuffer`
  4. Compose request via `composeInfillRequest`
  5. Send via `InfillClient`
  6. Apply `shouldDiscardSuggestion` filter
  7. Trigger `CacheWarmingService.scheduleWarmup` with the current `input_extra`
  8. Return `InlineCompletionSuggestion` with the completion text
- Cancellation: the `suspend` function uses coroutine cancellation. When IntelliJ calls `getSuggestion` again (new keystroke), the previous coroutine is cancelled, which should cancel the in-flight HTTP request.

**Acceptance criteria**:
- Provider ID is unique and stable
- `isEnabled` respects `autoSuggestions` setting
- `isEnabled` checks auto-trigger gating
- Suggestion is returned as `InlineCompletionSuggestion`
- Discarded suggestions result in empty suggestion
- Cancellation of previous request works (no leaked requests)
- Cache warming triggered after each completion

---

## Step 11 — Plugin wiring: plugin.xml, startup, settings UI

**Files to edit**:
- `src/main/resources/META-INF/plugin.xml`
- `src/main/kotlin/.../startup/CompletamenteStartupActivity.kt`
- `src/main/kotlin/.../settings/SettingsConfigurable.kt`

**What to add to plugin.xml**:
- `<projectService serviceImplementation="...services.CacheWarmingService"/>`
- `<inline.completion.provider implementation="...fim.FimInlineCompletionProvider"/>` (check current IntelliJ platform API for exact registration)

**What to add to startup**:
- Initialize `CacheWarmingService` with initial structure files (if available)
- No FIM-specific wiring needed beyond service registration — `InlineCompletionProvider` is invoked by the platform automatically

**What to add to settings UI**:
- "FIM Completions" group with: server URL text field, context size field, n_predict field, auto-suggestions checkbox
- Place above the existing "Ring Buffer" group

**Acceptance criteria**:
- Plugin loads without errors
- `InlineCompletionProvider` is registered and invoked by the platform on typing
- `CacheWarmingService` is available as a project service
- Settings UI shows all new fields
- Full `./gradlew build` passes

---

## Step Summary

| Step | What | Key files | Pure/Service |
|------|------|-----------|-------------|
| 1 | Settings | Settings.kt, SettingsState.kt | Edit existing |
| 2 | Infill data model | completion/infill.kt | Pure (data classes) |
| 3 | Context assembly | completion/context.kt | Pure (functions) |
| 4 | Token budgeting | completion/budget.kt | Pure (functions) |
| 5 | HTTP client | completion/InfillClient.kt | Service |
| 6 | Quality filters | completion/filters.kt | Pure (functions) |
| 7 | Structure resolution | completion/structure.kt | IntelliJ PSI |
| 8 | Request composition | completion/compose.kt | Pure (orchestration) |
| 9 | Cache warming | services/CacheWarmingService.kt | Service |
| 10 | InlineCompletionProvider | fim/FimInlineCompletionProvider.kt | IntelliJ API |
| 11 | Plugin wiring | plugin.xml, startup, settings UI | Integration |

Steps 1–6 are fully testable with pure functions and static data. Steps 7–11 require IntelliJ platform test infrastructure. Steps 1–4 and 6 can be implemented in parallel. Steps 5, 7 are independent. Steps 8–11 depend on earlier steps.
