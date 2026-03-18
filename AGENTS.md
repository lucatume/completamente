# AGENTS.md

## Project Overview

Completamente is an IntelliJ Platform plugin (v0.0.4-dev) that provides Fill-In-the-Middle (FIM) inline code completions powered by a local llama.cpp server. Built with Kotlin 2.3.10 targeting Java 21, on IntelliJ Platform 2024.3.6 (IC) using Gradle 9.0.0 and the IntelliJ Platform Gradle Plugin 2.11.0. Serialization via kotlinx-serialization 1.10.0.

Ported from [llama.vim](https://github.com/ggml-org/llama.vim), designed for SweepAI 1.5B FIM but compatible with any FIM-capable model.

Two main features:
1. **FIM Completions** — Ghost text suggestions via the `/infill` endpoint on keystroke
2. **Order 89** — Code transformation action triggered by keyboard shortcut

## Project Structure

```
src/main/kotlin/com/github/lucatume/completamente/
├── completion/       # FIM context building, request composition, budget, reindent, trim
├── fim/              # FimInlineCompletionProvider (IntelliJ inline completion integration)
├── order89/          # Order 89 action, dialog, and executor
├── services/         # Plugin services: settings state, ring buffer, cache warming, clipboard/file listeners
├── settings/         # Settings UI configurable
└── startup/          # Project startup activity

src/test/kotlin/com/github/lucatume/completamente/
├── BaseCompletionTest.kt   # Base test class extending BasePlatformTestCase
├── completion/              # Unit tests for pure completion functions
├── fim/                     # FIM provider tests
├── order89/                 # Order 89 tests
└── services/                # Service tests

src/test/testData/completion/ # Test fixture files

rhdp/                        # Research docs, harness scripts, design documents
```

## Commands

- Build the plugin: `./gradlew buildPlugin`
- Run all tests: `./gradlew test`
- Full build (tests + build): `./gradlew build`
- Verify plugin compatibility: `./gradlew verifyPlugin`
- Clean build: `./gradlew clean build`

The plugin ZIP is output to `build/distributions/completamente-<version>.zip`.

## Testing

**Framework:** JUnit 4 + IntelliJ Platform Test Framework (`BasePlatformTestCase`)

**Run tests:** `./gradlew test`

**File naming:** `XxxTest.kt` (not `XxxTests`), e.g., `trimTest.kt`, `budgetTest.kt`

**Method naming:** `testXxx` (JUnit 4 convention, camelCase), e.g., `testLeadingSpacesEqualToCursorCol`

**Base class:** Tests that need IntelliJ fixtures extend `BaseCompletionTest` which extends `BasePlatformTestCase`:

```kotlin
class trimTest : BaseCompletionTest() {
    fun testNoLeadingWhitespaceUnchanged() {
        assertEquals("foo()", trimCompletion("foo()", 4))
    }
}
```

**Pure function tests** call the function directly with assertions. **Platform tests** use `myFixture.configureByText()` and the IntelliJ test harness.

**Test data fixtures** live in `src/test/testData/completion/`.

## Code Style

### Pure functions for logic, services for state

Core logic lives in top-level pure functions (`buildFileContext()`, `composeInfillRequest()`, `allocateBudget()`, `trimCompletion()`, `reindentSuggestion()`). Mutable state is confined to IntelliJ service components.

```kotlin
// Pure function — no side effects, easy to test
fun trimCompletion(suggestion: String, cursorCol: Int): String {
    if (suggestion.isEmpty()) return suggestion
    // ...
}
```

### Immutable DTOs + mutable state components

```kotlin
// Immutable DTO for passing around
data class Settings(val serverUrl: String = "http://127.0.0.1:8012", ...)

// Mutable IntelliJ PersistentStateComponent
@Service(Service.Level.APP)
@State(name = "...", storages = [Storage("completamente.xml")])
class SettingsState : PersistentStateComponent<SettingsState> {
    var serverUrl: String = "http://127.0.0.1:8012"
    fun toSettings(): Settings = Settings(serverUrl = serverUrl, ...)
}
```

### kotlinx-serialization (not Jackson)

```kotlin
val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
json.encodeToString<InfillRequest>(request)
```

### Java HttpClient (not Retrofit/OkHttp)

```kotlin
val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5)).build()
```

### Coroutines for async completion

`getSuggestion()` is a `suspend` function using `readAction {}` for PSI/editor access and `withContext(Dispatchers.IO)` for network calls.

### Naming conventions

- Classes: PascalCase (`InfillClient`, `Order89Action`)
- Functions/variables: camelCase (`buildFileContext`, `cursorCol`)
- Files: Match their primary class/function name
- Test classes: `XxxTest` suffix, method prefix `test`

## Git Workflow

**Commit messages:** Conventional Commits format:
```
feat: trim leading/trailing whitespace from FIM completions
fix: harden Order 89 status display undo suppression with UndoUtil.disableUndoIn
docs: add rhdp/50 research on undo-free IntelliJ document edits
style: replace Order 89 animation cyan with electric blue
refactor: remove FIM/NEP completion feature
chore: bump version to 0.0.4-dev
```

**Branch:** Development happens on `main`.

**CI:** GitHub Actions (`.github/workflows/build.yml`) runs on push to main and all PRs. Pipeline: Build → Test → Qodana inspection → Plugin verification.

## Boundaries

### Always do
- Run `./gradlew test` before committing
- Use Kotlin idioms (data classes, extension functions, null safety)
- Keep completion pipeline functions pure — side effects belong in services
- Follow existing naming conventions (PascalCase classes, camelCase functions, `XxxTest` test classes)
- Use kotlinx-serialization for JSON, not Jackson or Gson
- Use Java HttpClient for HTTP, not Retrofit or OkHttp

### Ask first
- Before modifying `plugin.xml` (service registrations, actions, extension points)
- Before changing the completion pipeline order (trim → reindent → filter)
- Before adding new IntelliJ platform dependencies
- Before modifying `SettingsState` (affects persisted user configuration)
- Before changing keyboard shortcuts or action registrations

### Never do
- Never commit secrets or API keys
- Never push directly to main without tests passing
- Never add heavyweight dependencies (OkHttp, Retrofit, Jackson) — this plugin stays lean
- Never block the EDT (Event Dispatch Thread) with network calls or long computations
- Never remove failing tests without understanding why they fail
