# Design: Order 89 Local Model (replacing Claude Code)

## Goal

Replace the Claude Code shell-command backend for Order 89 with a direct HTTP call to a local llama.cpp server, using the `/completion` endpoint and a prompt structure validated by harnesses 51–67.

## Non-goals

- Managing or auto-starting the llama.cpp server (user starts it themselves)
- Supporting multiple model backends or a Claude Code fallback
- Chat/instruct endpoint support (`/v1/chat/completions` tested worse — harness 62)

## Background

Order 89 currently pipes a prompt file to `claude --dangerously-skip-permissions --print` via a configurable shell command. Harnesses 51–67 validated that a local Qwen3-Coder-30B-A3B-Instruct model on llama.cpp produces equivalent or better results when:

1. The `/completion` endpoint is used (not `/v1/chat/completions`)
2. Sampling parameters match the model's recommendations (`temperature=0.7, top_p=0.8, top_k=20, repeat_penalty=1.05`)
3. The prompt includes structure-extracted context files with a pre-file reminder for convention matching
4. Output is wrapped in backtick fences for reliable extraction

## Approach

### Prompt structure

The winning prompt from harness 66 (`terse_reminder` variant):

```
<Order89Prompt>
You are a code transformation tool. You receive a file with a marked selection and an instruction.
You output ONLY the code that replaces the selection.

<Order89Rules>
- Wrap your code output in a fenced code block using triple backticks with the language identifier.
- Do NOT add documentation blocks, comments, or type annotations that the surrounding
  code does not already use. Conversely, if the surrounding code includes documentation
  blocks on every function, include one on yours in the same format.
- Preserve the indentation style, brace placement, and whitespace patterns of the
  surrounding code.
- Do NOT describe what you are about to do. Do NOT explain your reasoning.
- Do NOT include any text before or after the fenced code block.
- If the selection is empty (<Order89UserSelection></Order89UserSelection>),
  output code to insert at that position.
</Order89Rules>

<Order89Context>
The following files are referenced by the file under edit. Use them to understand
the APIs and types available, so your generated code calls real methods with correct signatures.

<Order89ContextFile path="{path}">
{surface-extracted content}
</Order89ContextFile>
...
</Order89Context>

Language: {language}
File: {filePath}

<Order89Instruction>
{userPrompt}
</Order89Instruction>

REMINDER: Match the file's documentation style.

<Order89FileContent>
{before}<Order89UserSelection>{selection}</Order89UserSelection>{after}
</Order89FileContent>
</Order89Prompt>
```

Key design decisions in this prompt, backed by harness data:

| Decision | Evidence | Harness |
|----------|----------|---------|
| Backtick fences for output | 100% extraction rate vs 0% for raw/XML | 51, 53 |
| Structure-extracted context (not full files) | Same API accuracy, 30% less prompt size | 57 |
| Pre-file reminder for convention matching | 100% docblocks with it, 30-50% without | 66 |
| Terse reminder (49 chars) sufficient | Same score as 429-char verbose version | 66 |
| Context before file, reminder between | Position matters more than repetition | 66 |
| `/completion` endpoint, not chat | Chat endpoint killed convention matching | 62 |

### Request body

```json
{
  "prompt": "<the prompt above>",
  "n_predict": 1024,
  "temperature": 0.7,
  "top_p": 0.8,
  "top_k": 20,
  "repeat_penalty": 1.05,
  "stop": ["</Order89Prompt>", "\n\n\n\n"],
  "cache_prompt": false
}
```

### Settings changes

**New settings** (in `SettingsState`):

| Setting | Default | Description |
|---------|---------|-------------|
| `order89ServerUrl` | `http://127.0.0.1:8017` | llama.cpp server URL for Order 89 |
| `order89Temperature` | `0.7` | Sampling temperature |
| `order89TopP` | `0.8` | Top-p (nucleus sampling) |
| `order89TopK` | `20` | Top-k sampling |
| `order89RepeatPenalty` | `1.05` | Repetition penalty |
| `order89NPredict` | `1024` | Max tokens to generate |

**Removed settings**:

| Setting | Reason |
|---------|--------|
| `order89Command` | Shell command no longer used; replaced by direct HTTP |

### Order89Executor changes

**Remove**:
- `buildPromptFile()` — no more temp file; prompt is built in-memory as a string
- `buildCommand()` — no more shell command template
- Shell process execution (`ProcessBuilder`, `Process`, reading stdout)
- Temp file creation and cleanup

**Replace with**:
- `buildPrompt(request: Order89Request): String` — builds the prompt string in-memory using the template above
- `buildRequestBody(prompt: String, settings: Settings): String` — serializes the JSON request body
- `execute(request: Order89Request): Future<Order89Result>` — sends HTTP POST to `/completion`, parses the `content` field from the JSON response

**Keep unchanged**:
- `extractCodeBlock()` — still needed to strip backtick fences from the response
- `stripLeadingProse()` / `stripTrailingProse()` / `cleanOutput()` — safety net in case the model outputs prose
- `reindentOutput()` — still needed to fix indentation after extraction
- `looksLikeCode()` — used by the prose strippers
- `detectBaseIndent()` — used by reindent

### Context assembly

`buildPrompt()` receives the `Order89Request` which must be extended to carry context information. The prompt builder will:

1. Call `collectReferencedFiles()` (from `completion/structure.kt`) to resolve symbol references from the file under edit
2. Call `surfaceExtract()` on each referenced file to get signatures-only content
3. Format each as `<Order89ContextFile path="...">...</Order89ContextFile>`
4. Insert into the `<Order89Context>` section of the prompt

Since `collectReferencedFiles()` requires PSI access (must run in a read action), the context collection happens in `Order89Action` before calling `Order89Executor.execute()`, and the extracted chunks are passed into the request.

### Order89Request changes

```kotlin
data class Order89Request(
    val prompt: String,           // user's instruction
    val filePath: String,
    val fileContent: String,
    val language: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val contextChunks: List<ContextChunk>,  // NEW: surface-extracted references
)

data class ContextChunk(
    val path: String,
    val content: String,
)
```

Removed fields:
- `commandTemplate` — no longer needed
- `workingDirectory` — was only used for shell process cwd

### Order89Action changes

Before calling `execute()`, the action must:

1. Use `readAction {}` to collect referenced files via `collectReferencedFiles(psiFile, startLine, endLine)` using the cursor's line range
2. Run `surfaceExtract(project, file)` on each referenced file
3. Build `ContextChunk` list from the results
4. Pass them into the `Order89Request`

### HTTP client

Use the existing `java.net.http.HttpClient` pattern (same as `InfillClient`):

```kotlin
val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()
```

The request is a `POST` to `{order89ServerUrl}/completion` with `Content-Type: application/json`. Response is parsed for the `content` field using kotlinx-serialization.

### Cancellation

The current cancellation kills the shell `Process`. With HTTP, cancellation will interrupt the `Future` and close the HTTP connection. The `HttpClient` supports this via `HttpRequest.Builder` and `CompletableFuture.cancel()`.

### Error handling

- Server unreachable: show notification with the configured URL, suggest checking if the server is running
- Non-200 response: show the status code and response body in the error notification
- Empty `content` field: treat as error, show "model returned empty response"
- Timeout: configurable via `HttpClient.connectTimeout` and request timeout

## Code to remove

| File/function | Reason |
|---------------|--------|
| `SettingsState.order89Command` | Shell command setting |
| `Order89Executor.buildPromptFile()` | Temp file prompt builder |
| `Order89Executor.buildCommand()` | Shell command template substitution |
| `Order89Executor.execute()` process logic | Shell process execution (`ProcessBuilder`, stdout reading) |
| Settings UI for `order89Command` | No longer needed |
| Tests for `buildPromptFile()` | Dead code |
| Tests for `buildCommand()` | Dead code |

## Alternatives considered

| Alternative | Rejected because |
|-------------|-----------------|
| Keep shell command as fallback | Adds complexity; local model is the replacement, not a supplement |
| Use `/v1/chat/completions` | 0% docblocks on chat endpoint (harness 62); `/completion` works with the prompt structure |
| Hardcode sampling parameters | Different models may need different values; exposing them costs nothing |
| Skip context files | 0% `resolveWithArgs()` without context (harness 57); API accuracy requires structure context |
| Verbose convention guardrails | 49-char terse reminder achieves same 100% score as 429-char version (harness 66) |

## Trade-offs

- **User must start the server manually**: acceptable for this iteration; reduces plugin complexity significantly
- **Single test scenario validated**: harnesses tested one PHP file with one instruction; different languages/scenarios may behave differently with this prompt structure, but the prompt is generic (no PHP-specific language)
- **Sampling parameters exposed**: more settings surface area, but users running local models expect tunability
- **Stochastic output at temp=0.7**: higher variance than temp=0.2, but produces docblocks (100% vs 0%); the `cleanOutput()` pipeline handles edge cases
