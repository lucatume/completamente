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

- **Test naming:** `XxxTest.kt` (not `XxxTests`), methods `testXxx` (JUnit 4 camelCase)
- **Commit messages:** Conventional Commits (`feat:`, `fix:`, `docs:`, `style:`, `refactor:`, `chore:`)
- **Development branch:** `main`

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

### Never do
- Never commit secrets or API keys
- Never push directly to main without tests passing
- Never add heavyweight dependencies — this plugin stays lean
- Never block the EDT
- Never remove failing tests without understanding why they fail
