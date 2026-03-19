# Plan: Order 89 Local Model Implementation

Implements the design in `68-design-order89-local-model.md`. Replaces the Claude Code shell-command backend with direct HTTP calls to a local llama.cpp `/completion` endpoint.

## Execution workflow

You are a coordinator, not an executor. For each implementation step:

1. Dispatch a sub-agent to do the work (using your same Claude Code skills).
2. Dispatch two independent reviewer sub-agents (they must not see each other's output) to grade the result 1–5 on: correctness, completeness, code style consistency, test coverage, performance, and security.
3. Repeat 1–2 until both reviewers give a 5, up to 5 rounds. If not at 5 after 5 rounds, present the best attempt and move on.

Execute the entire plan without stopping for approval.

## Test review instructions

At each step, tests should be reviewed for the following smells. For each instance found, cite file, line(s), and a brief explanation of the problem and how to fix it.

### Smells to detect

1. **Echo Testing** — Expected value is calculated rather than a hard-coded literal. The test re-runs production logic in the assertion, so if the logic is wrong, the test is wrong too. Fix: Use hard-coded expected values.

2. **Constructor Drift** — Tests instantiate a class with outdated or incorrect arguments that no longer match the actual constructor signature. Fix: Compare every test-side constructor call against the actual constructor signature.

3. **Dodger Assertions** — Tests only assert that the result is not an error without checking the actual content. Fix: Add content assertions that verify returned data matches expected values.

4. **Happy Path Bias** — The test file lacks tests for edge cases and error paths: empty inputs, null values, error responses, malformed data, boundary values. Fix: Flag test files that have no error-path coverage and suggest specific scenarios.

5. **Docblock Bloat** — Every test method has a lengthy docblock with generic boilerplate. Fix: Trim to 1-3 lines answering: what regression does this test prevent?

6. **Mock-Testing-Mocks** — A test mocks a dependency to return X, then asserts the code returns X with no meaningful transformation between. Fix: Verify meaningful work happens between mock setup and assertion.

7. **Coverage Blind Spots** — High-risk source files (HTTP clients, etc.) have no corresponding test file. Fix: List untested source files prioritized by risk.

**Guiding principle:** For every test, ask: "If I mutate one line of production code, does this test actually fail?" If the answer is no, the test has a gap.

---

## Steps

### Step 1: Update `SettingsState` and `Settings`

**Files to modify:**
- `src/main/kotlin/com/github/lucatume/completamente/services/SettingsState.kt`
- `src/main/kotlin/com/github/lucatume/completamente/services/Settings.kt`

**Changes:**
1. Remove the `order89Command` field from `SettingsState`
2. Add new fields to `SettingsState` with defaults:
   - `order89ServerUrl: String = "http://127.0.0.1:8017"`
   - `order89Temperature: Double = 0.7`
   - `order89TopP: Double = 0.8`
   - `order89TopK: Int = 20`
   - `order89RepeatPenalty: Double = 1.05`
   - `order89NPredict: Int = 1024`
3. Update the `Settings` data class to mirror these fields (remove `order89Command`, add the new ones)
4. Update `toSettings()` to map the new fields

**Acceptance criteria:**
- `SettingsState` compiles with no references to `order89Command`
- `Settings` data class has all 6 new fields with correct types and defaults
- `./gradlew test` passes (some Order89 tests may fail due to downstream changes — that's expected)

---

### Step 2: Update `SettingsConfigurable` UI

**Files to modify:**
- `src/main/kotlin/com/github/lucatume/completamente/settings/SettingsConfigurable.kt`

**Changes:**
1. Remove the `order89CommandArea` text area and its "Command template:" row
2. Remove the "Reset" button for the command template
3. Add UI fields for the new Order 89 settings:
   - `order89ServerUrl` — text field (full row width)
   - `order89Temperature` — number field
   - `order89TopP` — number field
   - `order89TopK` — integer field
   - `order89RepeatPenalty` — number field
   - `order89NPredict` — integer field
4. Update `loadFromState()`, `isModified()`, `apply()`, and `reset()` to handle the new fields
5. Group the new fields under the existing "Order 89" panel

**Acceptance criteria:**
- No references to `order89Command` or `order89CommandArea` remain in this file
- All new fields are wired to `SettingsState` read/write
- `./gradlew build` compiles

---

### Step 3: Rewrite `Order89Executor` — prompt builder and HTTP client

**Files to modify:**
- `src/main/kotlin/com/github/lucatume/completamente/order89/Order89Executor.kt`

**Changes:**

1. Update `Order89Request` data class:
   - Remove `commandTemplate` and `workingDirectory` fields
   - Add `contextChunks: List<ContextChunk>` field
   - Add `ContextChunk` data class: `data class ContextChunk(val path: String, val content: String)`

2. Update `Order89Result` data class:
   - Remove `exitCode` field (no shell process anymore)
   - Keep `success: Boolean` and `output: String`

3. Remove methods:
   - `buildPromptFile()` — replaced by `buildPrompt()`
   - `buildCommand()` — no longer needed
   - `execute()` current implementation (shell process)

4. Add new methods:
   - `buildPrompt(request: Order89Request): String` — builds the prompt string using the winning template from harness 66:
     ```
     <Order89Prompt>
     You are a code transformation tool...
     <Order89Rules>...</Order89Rules>
     <Order89Context>
     {context intro}
     {formatted context chunks}
     </Order89Context>
     Language: {language}
     File: {filePath}
     <Order89Instruction>{prompt}</Order89Instruction>
     REMINDER: Match the file's documentation style.
     <Order89FileContent>
     {before}<Order89UserSelection>{selection}</Order89UserSelection>{after}
     </Order89FileContent>
     </Order89Prompt>
     ```
   - `buildRequestBody(prompt: String, settings: Settings): String` — serializes JSON request body using kotlinx-serialization:
     ```json
     {
       "prompt": "...",
       "n_predict": settings.order89NPredict,
       "temperature": settings.order89Temperature,
       "top_p": settings.order89TopP,
       "top_k": settings.order89TopK,
       "repeat_penalty": settings.order89RepeatPenalty,
       "stop": ["</Order89Prompt>", "\n\n\n\n"],
       "cache_prompt": false
     }
     ```
   - `execute(request: Order89Request, settings: Settings): Future<Order89Result>` — sends HTTP POST to `{settings.order89ServerUrl}/completion`, reads `content` field from JSON response, runs through `cleanOutput()`, returns `Order89Result`

5. Keep unchanged:
   - `extractCodeBlock()`, `stripLeadingProse()`, `stripTrailingProse()`, `cleanOutput()`
   - `reindentOutput()`, `detectBaseIndent()`, `looksLikeCode()`
   - All regex constants (`FENCED_BLOCK`, `CODE_LINE_PATTERN`, etc.)

6. HTTP client: use `java.net.http.HttpClient` with 5-second connect timeout, same pattern as the existing `InfillClient`

**Acceptance criteria:**
- `Order89Executor` compiles with no references to shell processes, temp files, or `commandTemplate`
- `buildPrompt()` produces a prompt matching the template from design doc
- `buildRequestBody()` produces valid JSON with all sampling parameters
- `execute()` returns `Future<Order89Result>` (not `Pair<Process, Future<...>>`)
- `cleanOutput()` pipeline is untouched

---

### Step 4: Update `Order89Action` — context assembly and new executor API

**Files to modify:**
- `src/main/kotlin/com/github/lucatume/completamente/order89/Order89Action.kt`

**Changes:**

1. In `actionPerformed()`:
   - Remove reading `order89Command` from settings
   - Remove the command-empty validation check
   - Add context collection before calling executor:
     - Use `readAction {}` to call `collectReferencedFiles(psiFile, startLine, endLine)` where `startLine`/`endLine` cover the visible or selection range
     - Call `surfaceExtract(project, file)` on each referenced file
     - Build `List<ContextChunk>` from results
   - Build `Order89Request` with `contextChunks` instead of `commandTemplate`/`workingDirectory`
   - Call `Order89Executor.execute(request, settings)` with the new signature (returns `Future<Order89Result>`, not `Pair<Process, Future<...>>`)

2. Update `Order89Session` data class:
   - Remove `process: Process` field (no shell process to cancel)
   - Add cancellation via `Future.cancel(true)`

3. Update cancellation in `Order89EscAction`:
   - Replace `session.process.destroyForcibly()` with `session.future.cancel(true)`

4. Update completion handling:
   - Remove `exitCode` checks from result handling
   - Use `result.success` / `result.output` as before

5. Imports:
   - Add imports for `collectReferencedFiles`, `surfaceExtract` from `completion/structure.kt`
   - Add import for `ContextChunk`
   - Remove `Process` import if no longer needed

**Acceptance criteria:**
- No references to `order89Command`, `Process`, `commandTemplate`, `workingDirectory`
- Context chunks are collected via PSI read action and passed to executor
- Cancellation works via `Future.cancel()` instead of process kill
- `./gradlew build` compiles

---

### Step 5: Update `plugin.xml` action description

**Files to modify:**
- `src/main/resources/META-INF/plugin.xml`

**Changes:**
1. Update the Order 89 action description from `"Transform selected text using a configured shell command"` to `"Transform selected text using a local llama.cpp model"`

**Acceptance criteria:**
- Description reflects the new backend
- No functional changes to action registration or shortcuts

---

### Step 6: Update tests — remove dead tests, update constructor calls

**Files to modify:**
- `src/test/kotlin/com/github/lucatume/completamente/order89/Order89Action.kt` (this is `Order89ExecutorTest`)

**Changes:**

1. Remove tests for deleted methods:
   - All `testBuildPromptFile*` tests
   - All `testBuildCommand*` tests
   - All `testExecute*` tests (these tested shell process execution)

2. Add tests for new methods:
   - `testBuildPrompt*` — verify the prompt string contains expected sections (`<Order89Prompt>`, `<Order89Rules>`, `<Order89Context>`, `<Order89Instruction>`, `<Order89FileContent>`, `<Order89UserSelection>`, the REMINDER line)
   - `testBuildPromptEmptySelection` — verify empty selection produces `<Order89UserSelection></Order89UserSelection>`
   - `testBuildPromptWithContextChunks` — verify context chunks are formatted as `<Order89ContextFile path="...">...</Order89ContextFile>`
   - `testBuildPromptNoContextChunks` — verify prompt works with empty context list (the `<Order89Context>` section should still be present but empty, or omitted — follow the implementation)
   - `testBuildRequestBody` — verify JSON output contains all sampling parameters with correct field names and values

3. Update `Order89Request` constructor calls in remaining tests:
   - Remove `commandTemplate` and `workingDirectory` arguments
   - Add `contextChunks = emptyList()` (or appropriate test data)

4. Keep all existing tests for methods that are unchanged:
   - `extractCodeBlock`, `stripLeadingProse`, `stripTrailingProse`, `cleanOutput`
   - `reindentOutput`, `detectBaseIndent`, `looksLikeCode`
   - `truncatePrompt`, `formatPromptLines`

**Acceptance criteria:**
- `./gradlew test` passes with 0 failures
- No tests reference `buildPromptFile`, `buildCommand`, or shell execution
- All `Order89Request` constructor calls match the new signature
- New tests for `buildPrompt` and `buildRequestBody` have hard-coded expected values (no echo testing)

---

### Step 7: Full build verification and cleanup

**Actions:**
1. Run `./gradlew clean build` — must pass with 0 errors, 0 test failures
2. Run `./gradlew verifyPlugin` — must pass
3. Search the entire codebase for stale references:
   - `order89Command` — should appear nowhere except git history
   - `{{prompt_file}}` — should appear nowhere
   - `buildPromptFile` — should appear nowhere
   - `buildCommand` (in Order89 context) — should appear nowhere
   - `ProcessBuilder` in Order89 files — should appear nowhere
4. Verify no temp file creation remains in Order89 code (`File.createTempFile` in order89 package)

**Acceptance criteria:**
- `./gradlew clean build` exits 0
- `./gradlew verifyPlugin` exits 0
- Grep for stale references returns 0 matches
- No temp file logic in order89 package
