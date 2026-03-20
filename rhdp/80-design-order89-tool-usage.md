# Design: Order 89 Tool Usage

## Goal

Add tool-calling capability to Order 89 so the model can search project files and the web before
generating code. The user controls whether tools are available and when.

## Non-Goals

- Building a general-purpose agent loop (we cap tool rounds to prevent runaway calls)
- Implementing tools beyond FileSearch and WebSearch
- Changing the Order 89 UI beyond what's needed for tool mode indication
- Streaming tool-call detection (we parse the complete response)

## Research Findings (harnesses 70–79)

| Finding | Source |
|---------|--------|
| **G_plaintext** tool format: 107 tokens, perfect 15/15 single + multi-call | harness 70, 72 |
| Model loops after receiving tool results if tools remain in the system prompt | harness 74, 76 |
| **V9_inline_no_tools** (two-phase): inject results as `<ReferenceCode>`, remove tools from system → 15/15 perfect, 527 tokens | harness 78 |
| Model legitimately requests multiple rounds of tool calls for complex prompts | harness 76, 78 |

## Setting: `order89ToolUsage`

An enum with three values, persisted in `SettingsState`:

| Value | Behavior |
|-------|----------|
| `off` (default) | No tools. Current behavior. Prompt never contains tool spec. |
| `manual` | Tools available only when the user's instruction starts with `/tools`. The prefix is stripped before it reaches the prompt. |
| `auto` | Tools always available. The model decides whether to call them. |

### `/tools` prefix handling

When `order89ToolUsage == manual`, the dialog's prompt text is checked:

- If it starts with `/tools` (case-insensitive, followed by whitespace or end-of-string), strip the
  prefix and enable tools for this request.
- Otherwise, proceed with no tools (same as `off`).

When `order89ToolUsage == auto`, the `/tools` prefix is ignored (stripped if present, tools are
always on regardless).

When `order89ToolUsage == off`, the `/tools` prefix is treated as literal text (not stripped).

## Two-Phase Execution Architecture

Based on the V9 finding: **the model must never see tool definitions and tool results in the same
prompt.** This means tool calling is a two-phase process managed by the client, not a single
conversation.

### Phase 1 — Tool-calling prompt (may repeat up to N rounds)

The system message includes the tool spec (G_plaintext format). The user message is the standard
Order 89 prompt. The model either:

- **Emits `<tool_call>` blocks** → client extracts all calls, executes them in parallel, appends
  results, and loops back for another round.
- **Emits a code block** → done, skip Phase 2.

This is a **raw `/completion` call** using the Qwen3 chat template manually assembled (same as
current Order 89).

### Phase 2 — Code generation prompt (single shot, no tools)

The system message does **not** contain tool definitions. Tool results are injected into the user
message as `<ReferenceCode>` blocks. The model produces the final code output.

```
<|im_start|>system
You are a code transformation tool. You receive a file with a marked selection and an instruction.
You output ONLY the code that replaces the selection.

<Order89Rules>
...same rules, no tool-calling rule...
</Order89Rules>
<|im_end|>

<|im_start|>user
<ReferenceCode source="FileSearch: processPayment">
src/.../PaymentGateway.kt:10:    fun processPayment(amount: BigDecimal, ...): PaymentResult
...
</ReferenceCode>

Language: kotlin
File: src/.../OrderService.kt

<Order89Instruction>
Call PaymentGateway.processPayment with the order total
</Order89Instruction>

REMINDER: Match the file's documentation style.

<Order89FileContent>
...<Order89UserSelection>...</Order89UserSelection>...
</Order89FileContent>
<|im_end|>

<|im_start|>assistant
```

### Round limit

The tool-calling loop is capped at **3 rounds** (configurable via `order89MaxToolRounds` setting,
default 3). If the model is still calling tools after 3 rounds, proceed to Phase 2 with whatever
results have been collected.

### Parallel tool execution

Within a single model response, multiple `<tool_call>` blocks may appear. All calls in one response
are executed **in parallel** (using coroutines or `CompletableFuture.allOf`). Results are collected
and the entire set is passed to the next round.

## Tool Spec Format

The G_plaintext winner, placed in the system message only during Phase 1:

```
You have two tools you may call to gather information before writing code:

1. FileSearch — Finds files in the project containing a string. Returns file:line pairs.
   Case-insensitive by default.
   Parameters: query (required, string), case_sensitive (optional, boolean, default false),
   path (optional, string, file or directory to search recursively)

2. WebSearch — Searches the web.
   Parameters: query (required, string)

To use a tool, respond with:
<tool_call>
{"name": "<tool-name>", "arguments": {<args>}}
</tool_call>

When you have gathered the information you need, produce your code output as specified in the rules.
```

An additional rule is appended to `<Order89Rules>` during Phase 1 only:

```
- If you need information from project files or the web to correctly implement the instruction,
  call the appropriate tool FIRST. Once you have the information, produce the code.
```

## Tool Implementations

### FileSearch

Executes a project-wide grep using IntelliJ's `FindInProjectUtil` or `PsiSearchHelper`.

**Input:**

```json
{"name": "FileSearch", "arguments": {"query": "processPayment", "case_sensitive": true, "path": "src/gateway"}}
```

**Execution:**
- Runs inside `runReadAction {}` on a pooled thread
- Searches the project content roots (or the specified path subtree)
- Case-insensitive by default (`case_sensitive: false`)
- Returns up to 20 matches, formatted as `file:line: <line content>`

**Output (string):**

```
src/gateway/PaymentGateway.kt:10:    fun processPayment(amount: BigDecimal, currency: String = "USD"): PaymentResult
src/gateway/PaymentGateway.kt:14:data class PaymentResult(val transactionId: String, val success: Boolean, val errorMessage: String? = null)
```

### WebSearch

**Deferred.** For now, returns a fixed message: `"WebSearch is not yet implemented."` This keeps the
tool spec honest (the model can attempt it) without requiring an external API integration. When
implemented, it will call a search API and return a summary of results.

## Prompt Assembly

### `Order89Executor.buildPrompt()` changes

The current `buildPrompt()` builds a raw text prompt (no chat template). For tool usage, we need the
Qwen3 chat template with `<|im_start|>`/`<|im_end|>` tokens. This is a significant change.

**Approach:** Add a new method `buildChatPrompt()` that produces the chat-template-wrapped prompt.
Keep the existing `buildPrompt()` for the `off` path (no regression risk). The `buildChatPrompt()`
method takes an additional parameter for collected tool results:

```kotlin
fun buildChatPrompt(
    request: Order89Request,
    toolResults: List<ToolResult> = emptyList(),
    includeTools: Boolean = true
): String
```

- `includeTools = true` → Phase 1 prompt (tool spec in system message, tool-calling rule)
- `includeTools = false` → Phase 2 prompt (no tool spec, results as `<ReferenceCode>` blocks)

### `buildRequestBody()` changes

When tools are active, the stop sequences change:

- Phase 1 stops: `["<|im_end|>", "<|im_start|>"]` (let the model finish its turn)
- Phase 2 stops: same as Phase 1 (or keep current `["</Order89Prompt>", "\n\n\n\n"]`)

Since we're now using the chat template, the stop sequences should be `<|im_end|>` for both phases.

## Response Parsing

### Tool call extraction

```kotlin
data class ToolCall(val name: String, val arguments: Map<String, Any>)

fun extractToolCalls(response: String): List<ToolCall>
```

Regex: `<tool_call>\s*(\{.*?\})\s*</tool_call>` with DOTALL. Parse each JSON match.
If no `<tool_call>` tags found, the response is treated as code output.

### Decision logic

```
response = complete(phase1Prompt)

if (extractToolCalls(response).isNotEmpty()) {
    // Execute tools, collect results, loop (up to maxRounds)
} else {
    // Response is code output — extract and clean
}
```

## Execution Flow

```
User enters prompt in dialog
          │
          ▼
    ┌─────────────┐
    │ Tools        │──── off ──────────────────────────────┐
    │ enabled?     │                                       │
    └─────┬───────┘                                       │
     yes (auto)                                           │
     or /tools (manual)                                   │
          │                                               │
          ▼                                               │
    ┌─────────────┐                                       │
    │ Phase 1     │◄──────────────────────┐               │
    │ (with tools)│                       │               │
    └─────┬───────┘                       │               │
          │                               │               │
          ▼                               │               │
    ┌─────────────┐     has tool    ┌─────┴─────┐         │
    │ Parse       │────  calls  ───│ Execute    │         │
    │ response    │                │ tools (//) │         │
    └─────┬───────┘                └────────────┘         │
          │ no tool calls                                 │
          │ or round limit                                │
          ▼                                               │
    ┌─────────────┐                                       │
    │ Has results? │── no ────────────────────────────┐   │
    └─────┬───────┘                                   │   │
     yes  │                                           │   │
          ▼                                           │   │
    ┌─────────────┐                                   │   │
    │ Phase 2     │                                   │   │
    │ (no tools,  │                                   │   │
    │  results as │                                   │   │
    │  context)   │                                   │   │
    └─────┬───────┘                                   │   │
          │                                           │   │
          ▼                                           ▼   ▼
    ┌─────────────┐                             ┌─────────────┐
    │ Clean &     │                             │ Current     │
    │ insert      │                             │ single-shot │
    │ output      │                             │ path        │
    └─────────────┘                             └─────────────┘
```

## Status Display Updates

When tools are active, the status line should reflect the current phase:

- Phase 1: `✦ Gathering info... (round 1/3)`
- Phase 1 executing tools: `✦ Searching: "processPayment"...`
- Phase 2: `✦ Generating code...`

This reuses the existing `insertStatusLines` mechanism but updates the text between rounds via the
existing undo-transparent document edit pattern.

## Data Types

```kotlin
data class ToolCall(
    val name: String,
    val arguments: Map<String, JsonElement>
)

data class ToolResult(
    val call: ToolCall,
    val output: String
)

enum class ToolUsageMode {
    OFF, MANUAL, AUTO
}
```

## Files Changed

| File | Change |
|------|--------|
| `Settings.kt` | Add `order89ToolUsage: ToolUsageMode = OFF`, `order89MaxToolRounds: Int = 3` |
| `SettingsState.kt` | Add `order89ToolUsage: String = "off"`, `order89MaxToolRounds: Int = 3`, map in `toSettings()` |
| `SettingsConfigurable.kt` | Add combo box for tool usage mode, text field for max rounds |
| `Order89Executor.kt` | Add `buildChatPrompt()`, `extractToolCalls()`, `ToolCall`, `ToolResult` data classes. Refactor `execute()` to support tool loop |
| `Order89Action.kt` | Parse `/tools` prefix, pass tool mode to executor, update status display between rounds |
| `Order89ToolExecutor.kt` (new) | `FileSearchTool` and `WebSearchTool` implementations, parallel execution |

## Alternatives Considered

### Multi-turn conversation (rejected)

Harness 76 showed that multi-turn (assistant→tool_call, user→tool_response) causes the model to
loop. Even V6 (dropping tools in a second system message) only achieved 12/15. The two-phase
approach (V9) is simpler and achieved 15/15.

### Tool results as Order89ContextFile (rejected)

V7 in harness 76 (injecting results as `<Order89ContextFile>`) scored only 1/15. The model
confuses tool results with the existing context file format. The `<ReferenceCode>` tag is distinct
enough to avoid this.

### Always-on tools (rejected as default)

Setting the default to `auto` would add tool spec tokens to every request and risk unexpected tool
calls on simple prompts. The `off` default preserves current behavior. Users opt in explicitly.

## Trade-offs

- **Two HTTP calls minimum when tools are used** — Phase 1 + Phase 2. This adds latency but ensures
  clean separation. The harness data shows this is necessary for reliable code output.
- **FileSearch runs in `runReadAction`** — this briefly blocks PSI access but is fast for grep-style
  searches. Acceptable since Order 89 already collects context via `runReadAction`.
- **WebSearch is deferred** — keeps the design lean. The model will see "not implemented" and fall
  back to its training knowledge, which is acceptable for now.
- **Round limit of 3** — balances thoroughness against latency. Harness 78 showed that 2 rounds
  suffice for all tested scenarios, so 3 provides headroom.
