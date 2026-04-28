# AGENTS.md

## What This Is

IntelliJ plugin providing FIM inline completions powered by a local llama.cpp server, plus an Order 89 code-transformation command that shells out to a user-configured agentic CLI. Ported from [llama.vim](https://github.com/ggml-org/llama.vim), Order 89 inspired by [ThePrimeagen/99](https://github.com/ThePrimeagen/99). FIM prompts are tuned for Qwen3-Coder-30B-A3B-Instruct but work with any FIM-capable model.

## Commands

- Build: `./gradlew buildPlugin`
- Test: `./gradlew test`
- Full build: `./gradlew build`
- Clean build: `./gradlew clean build`

## Design Principles

- **Pure functions for logic, services for state.** Core logic is top-level pure functions or object singleton methods. Mutable state is confined to IntelliJ service components.
- **Immutable DTOs, mutable state components.** Data classes for passing around, `PersistentStateComponent` for persistence.
- **Coroutines for FIM, pooled threads for Order 89.** FIM uses `suspend` + `readAction {}` + `withContext(Dispatchers.IO)`. Order 89 dispatches via `ApplicationManager.getApplication().executeOnPooledThread { ... }` off-EDT.
- **Never block the EDT** with network calls or long computations.
- **Lean dependencies.** kotlinx-serialization for JSON (not Jackson/Gson). Java HttpClient for HTTP (not Retrofit/OkHttp). No heavyweight libraries.

## Conventions

- **Test naming:** `XxxTest.kt` (not `XxxTests`), methods `testXxx` (JUnit 4 camelCase). UI tests use `XxxUiTest.kt` and live under `src/uiTest/`.
- **Commit messages:** Conventional Commits (`feat:`, `fix:`, `docs:`, `style:`, `refactor:`, `chore:`)
- **Development branch:** `main`

## UI tests

End-to-end UI tests live under `src/uiTest/` and run via Remote Robot against a sandboxed IDE. They exist so an agent can drive the IDE, exercise plugin features, and read structured failure artefacts.

### Running

- Boot the IDE in one terminal: `./gradlew runIdeForUiTests` (long-running; downloads and launches IntelliJ IDEA Community 2024.3.6 with `robot-server-plugin` on port 8082).
- Run all UI tests in another terminal: `./gradlew uiTest`
- Run a single UI test: `./gradlew uiTest --tests "*SettingsDialogUiTest*"`
- UI tests are **on-demand** — not run as part of `./gradlew test` or `./gradlew check`.

### Artefacts

After a UI test run, `build/reports/uiTest/`:

- `summary.json` — pass/fail per test with paths to artefacts. **Read this first.**
- `<TestClass>/<testMethod>/screenshot.png` — IDE screenshot at moment of failure.
- `<TestClass>/<testMethod>/hierarchy.html` — Remote Robot component tree dump (XPath-friendly).
- `<TestClass>/<testMethod>/idea.log.tail` — last 500 lines of the sandbox IDE log.
- `<TestClass>/<testMethod>/failure.txt` — exception message and stack trace.
- `<TestClass>/<testMethod>/events.jsonl` — robot actions issued during the test, one JSON per line.

### Adding a new test

1. Extend `BaseCompletamenteUiTest` (under `src/uiTest/kotlin/com/github/lucatume/completamente/uitest/`).
2. Stage backend behaviour with `stageInfill(...)` / `stageInfillError(...)` / `useFakeAgentFixture(...)`.
3. Drive the IDE via `robot.runJs(...)` or `robot.callJs<T>(...)`. Note: plugin classes (e.g. `SettingsState`) live in a child classloader and are only reachable via `java.lang.Class.forName(fqn, true, pluginCl)` where `pluginCl` is the plugin's `pluginClassLoader`. Platform classes work as `com.intellij.…` directly.
4. Editor reads/writes need EDT (`runInEdt = true`). Polling loops cannot run on EDT — split into an EDT trigger phase and a non-EDT poll phase that wraps reads in `runReadAction`.
5. Drop fixtures under `src/uiTest/resources/fake-agent/fixtures/` (canned STDOUT for Order 89 stand-in) or stage llama responses inline via `stageInfill`.

## Boundaries

### Always do
- Run `./gradlew test` before committing
- Keep completion pipeline functions pure — side effects belong in services
- Follow existing naming conventions

### Ask first
- Before modifying `plugin.xml`
- Before changing the completion pipeline order or the Order 89 output pipeline (`cleanOutput`, `extractCodeBlock`, `stripLeadingProse`, `stripTrailingProse`, `matchTrailingNewlines`, `looksLikeCode`)
- Before changing the Order 89 process pipeline (build prompt → temp file → `ProcessBuilder` → cleaned STDOUT → editor splice) or the `%%prompt_file%%` substitution contract
- Before adding new IntelliJ platform dependencies
- Before modifying `SettingsState`
- Before changing keyboard shortcuts or action registrations
- Before changing `BaseCompletamenteUiTest` lifecycle, the fake-llama or fake-agent CLI fixture format, or the `summary.json` schema (agents depend on it)

### Never do
- Never commit secrets or API keys
- Never push directly to main without tests passing
- Never add heavyweight dependencies — this plugin stays lean
- Never block the EDT
- Never remove failing tests without understanding why they fail
