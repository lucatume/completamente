# llama.cpp Prompt Caching for FIM Completions

**Question:** What llama.cpp prompt caching approach best fits the completamente plugin's FIM prompt structure, and what server flags should we use when starting a managed server?

## Prompt Structure Recap

The plugin builds prompts in this order:

1. Definition chunks (cross-file symbol references, variable)
2. Ring buffer chunks (recently visited code snippets, variable)
3. Recent diffs (file changes, variable)
4. Original file content (60-line window around cursor)
5. Current file content (60-line window around cursor)
6. `<|file_sep|>updated/{path}` marker (model fills in)

Everything changes frequently as the user types and moves the cursor. There is no fixed "system prompt" prefix.

## llama-server Caching Mechanisms

### 1. `cache_prompt` (request-level, default enabled)

When `cache_prompt: true` is sent in the request body (or `--cache-prompt` is set on the server, which is the default), the server retains the KV cache from the previous request in each slot. On the next request, only the suffix that differs from the cached prefix is processed.

**How it helps FIM:** Between consecutive keystrokes, the definition chunks and ring buffer chunks at the top of the prompt are often identical. The server skips reprocessing this shared prefix. Only the changed diffs, file windows, and trailing tokens need processing.

**Example:** A 2000-token prompt where 1500 tokens are unchanged from the previous request → only 500 tokens are processed.

### 2. `--cache-reuse N` (server flag)

Sets the minimum chunk size for attempting KV cache reuse via KV shifting. When the prompt prefix changes but a later portion matches, the server can shift the KV cache to align with the new prompt.

**How it helps FIM:** If a new ring buffer chunk is prepended (shifting the entire prompt), KV shifting can still reuse the matching suffix. The default behavior is usually sufficient; explicit tuning is rarely needed.

### 3. `--slot-save-path PATH` (persistent KV cache)

Saves slot KV caches to disk. Allows restoring cached state across server restarts.

**How it helps FIM:** Not very useful for a managed server that lives as long as the IDE session. The in-memory cache is sufficient. Skip this.

### 4. `-np N` (parallel slots)

Creates N independent slots, each with its own KV cache. Context is divided: `-c` / `-np` tokens per slot.

**How it helps FIM:** The plugin makes sequential requests (one at a time). A single slot (`-np 1`) is optimal — it gets the full context window and every request benefits from the previous request's cache.

## Recommended Server Configuration

For a managed server started by the plugin:

```
llama-server \
    --model <model-path> \
    --ctx-size 8192 \
    --host 127.0.0.1 \
    --port <port> \
    --parallel 1 \
    --cache-prompt \
    --temp 0.0
```

**Flags explained:**
- `--ctx-size 8192`: Matches SweepAI model's training context length.
- `--parallel 1`: Single slot for maximum cache reuse. The plugin doesn't need concurrent requests.
- `--cache-prompt`: Explicitly enable prompt caching (default, but worth being explicit).
- `--temp 0.0`: Greedy decoding for deterministic completions.

**Not needed:**
- `--slot-save-path`: No benefit for ephemeral managed server.
- `--cache-reuse`: Default behavior is sufficient.
- `--spm-infill` / `/infill` endpoint: The plugin uses `/completion` with a custom prompt format, not the built-in FIM infill endpoint.

## Adding `cache_prompt` to Requests

The plugin should send `cache_prompt: true` in the request body. This is currently not included in `CompletionRequestBody`. Adding it ensures the server caches the prompt KV state between requests.

```kotlin
@Serializable
data class CompletionRequestBody(
    val prompt: String,
    @SerialName("n_predict") val nPredict: Int,
    val temperature: Double,
    val stop: List<String>,
    val stream: Boolean,
    @SerialName("cache_prompt") val cachePrompt: Boolean = true
)
```

## Measured Performance (Harness #31)

Harness `31-harness-prompt-cache-effectiveness.py` simulated 6 editing steps (user adding a method line-by-line) with 2 definition chunks, 1 ring chunk, and growing diffs. Results from the `/completion` response fields:

- `timings.cache_n` = tokens reused from KV cache
- `timings.prompt_n` = tokens that needed new evaluation
- `tokens_evaluated` = total prompt tokens (cache_n + prompt_n)

### Current prompt order (defs → rings → diffs → original → current → updated)

| Edit | Total tokens | Cached | New eval | Cache % |
|------|-------------|--------|----------|---------|
| 0 (cold) | 266 | 259 | 7 | 97.4% |
| 1 | 288 | 287 | 1 | 99.7% |
| 2 | 298 | 297 | 1 | 99.7% |
| 3 | 322 | 321 | 1 | 99.7% |
| 4 | 332 | 331 | 1 | 99.7% |
| 5 | 346 | 345 | 1 | 99.7% |

**Average cache hit (edits 1-5): 99.7%** — only 1 token re-evaluated per request.

### Alternative: stable-first order (defs → rings → original → current → diffs → updated)

| Edit | Total tokens | Cached | New eval | Cache % |
|------|-------------|--------|----------|---------|
| 0 | 266 | 265 | 1 | 99.6% |
| 1 | 288 | 259 | 29 | 89.9% |
| 2 | 298 | 261 | 37 | 87.6% |
| 3 | 322 | 268 | 54 | 83.2% |
| 4 | 332 | 280 | 52 | 84.3% |
| 5 | 346 | 285 | 61 | 82.4% |

**Average cache hit (edits 1-5): 85.5%** — 29-61 tokens re-evaluated per request.

### Why the current order wins

The prompt is a **growing prefix**: definition chunks and ring chunks are stable, and when the user types, both the diffs and the `current` file window grow by appending to the end. The server caches the entire previous prompt and only evaluates the newly appended tokens.

Moving diffs after the file windows (stable-first) breaks this: when the `current` window content changes (because the user typed more code), it invalidates everything after it — including the diffs and the `updated` marker.

### Conclusion

**Keep the current prompt order.** It achieves near-perfect (99.7%) cache reuse during sequential editing. The only action needed is adding `cache_prompt: true` to the request body to ensure this behavior is explicit.

## Sources

- [llama.cpp server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [KV cache reuse tutorial](https://github.com/ggml-org/llama.cpp/discussions/13606)
- [cache_prompt discussion](https://github.com/ggml-org/llama.cpp/discussions/10311)
- [Multiple prefix reuse with slots](https://github.com/ggml-org/llama.cpp/discussions/15530)
