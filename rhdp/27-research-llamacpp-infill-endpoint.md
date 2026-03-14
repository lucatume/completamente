# llama.cpp Server `/infill` Endpoint for Code Completions

## Question

How does the llama.cpp server's `/infill` endpoint work, and how does it compare to the current
approach of manually assembling FIM tokens and sending them to `/completion`?

## Current Approach in completamente

The plugin currently uses **`/completion`** with a manually assembled prompt (`fim.kt`):

- `buildFimPrompt()` constructs the full token sequence with `<|file_sep|>` markers
- The SweepAI model's special tokens are embedded directly in the prompt string
- Request body: `{ prompt, n_predict, temperature, stop, stream, cache_prompt }`
- The server sees an opaque string; all FIM structure is baked into the prompt

## The `/infill` Endpoint

`POST /infill` is a dedicated endpoint for fill-in-the-middle completions. It shares the same
internal handler as `/completion` but **automatically wraps inputs in FIM tokens** based on the
model's GGUF metadata.

### Infill-Specific Request Fields

| Field | Type | Required | Description |
|---|---|---|---|
| `input_prefix` | string | **Yes** | Code before the cursor/gap |
| `input_suffix` | string | **Yes** | Code after the cursor/gap |
| `input_extra` | array | No | Additional context from other files. Array of `{"filename": string, "text": string}` |
| `prompt` | string | No | Inserted after `FIM_MID` token. Partial "middle" completion seed. Usually empty |

### Shared Fields (same as `/completion`)

All `/completion` parameters are accepted. Key ones:

| Field | Type | Default | Description |
|---|---|---|---|
| `n_predict` | int | -1 (unlimited) | Max tokens to generate |
| `temperature` | float | 0.8 | Sampling randomness |
| `top_k` | int | 40 | Top-K sampling |
| `top_p` | float | 0.95 | Nucleus sampling |
| `stop` | string[] | [] | Stop sequences |
| `stream` | bool | varies | SSE streaming |
| `cache_prompt` | bool | true | Reuse KV cache |
| `n_indent` | int | 0 | Minimum line indentation for generated text |
| `samplers` | string[] | (default chain) | Ordered sampler list. Supports `"infill"` sampler |
| `t_max_predict_ms` | int | 0 | Time limit for prediction (triggers after newline) |
| `t_max_prompt_ms` | int | 0 | Time limit for prompt processing |
| `response_fields` | string[] | all | Filter response to specific fields |
| `return_tokens` | bool | false | Include raw token IDs |

### FIM Token Assembly (server-side)

The server's `format_prompt_infill` function builds the token sequence automatically:

**Pattern A -- Repo-level (model has `FIM_REPO` + `FIM_SEP` tokens):**

```
<FIM_REP>reponame
<FIM_SEP>{chunk_0_filename}
{chunk_0_text}
<FIM_SEP>{chunk_1_filename}
{chunk_1_text}
...
<FIM_SEP>current_filename
<FIM_PRE>{input_prefix}<FIM_SUF>{input_suffix}<FIM_MID>{prompt}
```

**Pattern B -- Fallback (no repo tokens):**

```
{input_extra as plain text with "--- snippet ---" separators}
<FIM_PRE>{input_prefix}<FIM_SUF>{input_suffix}<FIM_MID>{prompt}
```

**SPM mode** (`--spm-infill` flag): Suffix-Prefix-Middle order instead of PSM.

### Context Budget Allocation (server-side)

The server allocates context budget automatically:

- **Prefix**: up to `3/4 * n_batch` tokens (takes from end, i.e., closest to cursor)
- **Suffix**: up to `1/4 * n_batch - 2 - len(prompt)` tokens (takes from start)
- **Extra context**: `min(n_ctx - n_batch - 2*n_predict, len(extra_tokens))` tokens (takes from end)
- BOS token prepended if model requires it

### Response Format

Identical to `/completion`:

```json
{
  "content": "the generated infill text",
  "stop": true,
  "stop_type": "eos",
  "stopping_word": "",
  "tokens_cached": 142,
  "tokens_evaluated": 150,
  "truncated": false,
  "timings": {
    "prompt_n": 150,
    "prompt_ms": 45.2,
    "prompt_per_token_ms": 0.3,
    "prompt_per_second": 3318.5,
    "predicted_n": 23,
    "predicted_ms": 120.5,
    "predicted_per_token_ms": 5.2,
    "predicted_per_second": 190.8
  }
}
```

Streaming (SSE) is also supported with `stream: true`.

## Comparison: `/completion` (current) vs `/infill`

| Aspect | `/completion` (current) | `/infill` |
|---|---|---|
| FIM token handling | Manual in `buildFimPrompt()` | Automatic by server |
| Extra file context | Embedded in prompt string | Structured `input_extra` array |
| Context budgeting | Manual token counting in Kotlin | Server-side allocation |
| Model portability | Hardcoded to SweepAI tokens | Works with any FIM model |
| Prompt caching control | Full control over token order | Server decides order |
| SweepAI NEP pattern | Fully supported (original/current/updated) | **Not directly supported** |

## Key Finding: Compatibility with SweepAI NEP Pattern

The `/infill` endpoint is designed for **standard FIM** (prefix + suffix -> middle). The SweepAI
model uses a **non-standard NEP pattern** where the prompt contains:

1. The original file content (before edits)
2. The current file content (after edits)
3. An `updated/` marker where the model generates the next edit

This structure doesn't map cleanly to `input_prefix` + `input_suffix`. The "suffix" in the SweepAI
sense is not "code after the cursor" but rather "the original version of the file". The plugin would
need to either:

- Pack the entire NEP context into `input_prefix` and leave `input_suffix` empty (losing the
  server's context budget logic)
- Restructure the prompt to use standard FIM (losing NEP capability)
- Continue using `/completion` for the SweepAI model

For **standard FIM models** (CodeLlama, CodeGemma, Qwen2.5 Coder, etc.), the `/infill` endpoint
would be the cleaner approach.

## llama.vim Reference

llama.vim uses `/infill` with these parameters:

```json
{
  "id_slot": 0,
  "input_prefix": "<prefix text>",
  "input_suffix": "<suffix text>",
  "input_extra": [{"filename": "path", "text": "content"}, ...],
  "prompt": "",
  "n_predict": 128,
  "stop": [],
  "n_indent": 4,
  "top_k": 40,
  "top_p": 0.90,
  "samplers": ["top_k", "top_p", "infill"],
  "stream": false,
  "cache_prompt": true,
  "t_max_prompt_ms": 500,
  "t_max_predict_ms": 1000,
  "response_fields": ["content", "timings/prompt_n", "timings/prompt_ms", ...]
}
```

It also sends a **cache-warming request** with `n_predict: 0` to pre-load extra context.

## Sources

- [llama.cpp server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [server-common.cpp `format_prompt_infill`](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/server-common.cpp)
- [llama.vim source](https://github.com/ggml-org/llama.vim)
- [GitHub Discussion #6708 -- FIM/Infill changes](https://github.com/ggml-org/llama.cpp/discussions/6708)
- [GitHub Issue #7102 -- /infill for CodeQwen](https://github.com/ggml-org/llama.cpp/issues/7102)
