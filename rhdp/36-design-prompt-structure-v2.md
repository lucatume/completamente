# Design: Prompt Structure v2 — llama.vim-style `/infill` with 32k Context

## Goal

Design the optimal prompt structure for FIM completions using:
- The llama.cpp `/infill` endpoint (not `/completion` with manual FIM tokens)
- A 32k token context window (up from previous 8k)
- The llama.vim approach as the foundation
- `input_extra` for cross-file context (not baked into the prompt string)

## Status

**Core design settled.** All major decisions made. Ready for implementation planning.

---

## Context: What Changed

The previous FIM/NEP implementation used:
- `/completion` with manually assembled `<|file_sep|>` tokens (SweepAI format)
- 8k context window with ~7.5k prompt budget
- Original/current/updated NEP pattern (SweepAI-specific)

The new approach uses:
- `/infill` endpoint (server handles FIM token assembly)
- 32k context window (~30k prompt budget after reserving for output)
- Standard FIM (prefix/suffix/middle) — model-agnostic
- `input_extra` for structured cross-file context

## Key Constraint

The `/infill` endpoint assembles the final token sequence as:

```
[input_extra chunks as <|file_sep|> blocks]
<FIM_PRE>{input_prefix}<FIM_SUF>{input_suffix}<FIM_MID>{prompt}
```

We control:
- `input_prefix`: code before the cursor line (all lines above)
- `input_suffix`: code after the cursor position (rest of current line + lines below)
- `prompt`: the current line up to the cursor (the "middle" seed)
- `input_extra`: array of `{filename, text}` chunks for cross-file context

The server handles:
- FIM token wrapping (model-agnostic)
- Context budget allocation (3/4 prefix, 1/4 suffix for the main file)
- Extra context placement (before the FIM block)

---

## Open Questions

These questions will be resolved through discussion:

1. ~~**Model choice**: Which model(s) are we targeting?~~ → Decided: Qwen3-Coder-30B-A3B
2. ~~**Prefix/suffix sizing**: With 32k tokens, how much of the current file should we include?~~ → Decided: whole file when possible, 512/128 fallback
3. ~~**Extra context composition**: What goes into `input_extra`?~~ → Decided: structure files + ring chunks (no diffs)
4. ~~**Token budgeting**: Who manages the budget?~~ → Decided: client-side, naive estimation
5. ~~**Cache warming strategy**~~ → Decided: background job warms `input_extra` into KV cache
6. ~~**Speculative pre-fetching**~~ → Deferred to a later iteration
7. ~~**Output length**~~ → Decided: 128 tokens
8. ~~**Client-side caching**~~ → Deferred (typed-character reuse is the first optimization to add later)
9. ~~**Request lifecycle**~~ → Decided: cancel-and-replace, at most one completion in flight

---

## Decisions Made

### 1. Model: Qwen3-Coder-30B-A3B

- **Architecture**: Mixture-of-Experts (30B total, 3B active per token)
- **Context window**: 32,768 tokens
- **FIM support**: Native repo-level FIM with `<|repo_name|>`, `<|file_sep|>`, `<|fim_prefix|>`, `<|fim_suffix|>`, `<|fim_middle|>` tokens
- **Why**: Fast inference (only 3B active params), large context, native understanding of `input_extra` as `<|file_sep|>` blocks, strong code completion quality
- **Implication**: The `/infill` endpoint will use Pattern A (repo-level) since the model has `FIM_REPO` + `FIM_SEP` tokens. `input_extra` chunks get wrapped as `<|file_sep|>filename\ncontent` automatically.

### 2. KV Cache: Strictly prefix-based, `input_extra` is naturally cache-friendly

Empirically verified with harness 37 (see `39-research-kv-cache-behavior.md`):

- **KV cache matches from position 0 only** — no island caching, no out-of-order reuse
- **Reordering `input_extra` = full cache miss** (~99% re-evaluation)
- **Changing the first extra = full cache miss** (everything after invalidated)
- **Stable extras + changed prefix = partial hit** (extras cached, prefix re-evaluated)
- **Typing (prefix grows) = near-perfect cache** (93.6% hit, only new line tokens evaluated)
- **Appending new extra = partial hit** (prior extras cached, new chunk + shifted prefix re-evaluated)

**Critical insight**: `/infill` assembles tokens as `[input_extra] → [FIM_PRE + prefix] → [FIM_SUF + suffix] → [FIM_MID + prompt]`. Extras naturally form the cache prefix. This means:
- Most stable chunks must be **first** in `input_extra`
- Chunk ordering must be **consistent** across requests (sort by stable key like file path)
- Never prepend or insert into `input_extra` — always append new chunks
- Accept that `input_prefix`/`input_suffix` are re-evaluated every keystroke (~100-200 tokens)

### 3. Prefix/suffix: whole file when possible, 512/128 fallback

- **Default**: send the entire file content as `input_prefix` (above cursor) + `input_suffix` (below cursor)
- **Fallback**: when the file exceeds the current-file budget (~10k tokens, ~1000 lines), use a 512/128 asymmetric window (4:1 ratio, same as llama.vim)
- The cursor position splits the file: everything above the cursor line → `input_prefix`, current line up to cursor → `prompt`, rest of current line + everything below → `input_suffix`

### 4. Client-side token budgeting with naive estimation

The client manages the token budget — it decides what fits before sending the request. The server's built-in context budget allocation is a safety net, not the primary mechanism.

**Token estimation**: `estimateTokens(text) = (text.length + 2) / 3` (~3.3 chars/token, conservative — overestimates slightly, which is the safe direction for budget math).

**Budget breakdown**:
```
Total context:       32,768 tokens
Output (n_predict):     256 tokens (TBD — see open question)
Overhead (FIM tokens):  ~20 tokens
Prompt budget:      ~32,492 tokens
```

**Budget allocation order** (priority — earlier sections guaranteed, later sections absorb pressure):

| Priority | Section | Budget | Typical tokens |
|----------|---------|--------|----------------|
| 1 (mandatory) | Current file prefix | whole file or 512 lines | 500–5000 |
| 2 (mandatory) | Current file suffix | whole file or 128 lines | 100–1300 |
| 3 | Structure files (imports) | up to 16 files | 50–1200 |
| 4 | Structure files (cursor scope) | up to 8 files | 50–560 |
| 5 | Ring buffer chunks | fills remaining budget | 200–24000 |

**Assembly algorithm**:
```
1. Estimate tokens for current file (whole file)
2. If file_tokens > 10000: fall back to 512/128 window, re-estimate
3. remaining = 32492 - file_tokens
4. Add structure files (imports) one at a time, sorted by file path, until budget or cap
5. remaining -= structure_tokens
6. Add structure files (cursor scope) one at a time, until budget or cap
7. remaining -= scope_structure_tokens
8. Add ring chunks one at a time, sorted by stable key, until budget exhausted
9. Assemble: input_extra = [import_structures, scope_structures, ring_chunks] (stable order)
```

### 5. Output: 128 tokens (`n_predict: 128`)

Covers single-line and short multi-line completions (~2-5 lines of code). Combined with `t_max_predict_ms: 250` as a time-based cutoff for responsiveness.

Budget breakdown updated:
```
Total context:       32,768 tokens
Output (n_predict):     128 tokens
Overhead (FIM tokens):  ~20 tokens
Prompt budget:      ~32,620 tokens
```

### 6. Request lifecycle: cancel-and-replace, background cache warming

**Completion requests** (triggered by `InlineCompletionProvider` on each keystroke):
- At most **one completion request in flight** at any time
- Each new keystroke **cancels** the in-flight request and starts a new one
- Request includes `input_prefix`, `input_suffix`, `prompt`, and the current `input_extra` array
- Uses `t_max_predict_ms: 250` for responsiveness

**Cache warming** (background, independent of completion requests):
- A background job periodically sends a **warm-up request** to pre-load `input_extra` into the KV cache
- Warm-up request: `n_predict: 0`, `t_max_prompt_ms: 1`, same `input_extra` as the next completion would use
- Runs when `input_extra` content changes (new structure files resolved, ring buffer updated)
- Uses `id_slot: 0` to ensure warming and completion share the same KV cache slot

**What is NOT implemented (deferred optimizations)**:
- Client-side response caching (LRU hash map of context → response)
- Typed-character cache reuse (trim matching prefix from cached ghost text)
- Speculative pre-fetching (request what comes after the current suggestion)
- Multi-hash scroll tolerance
- Two-tier timing (250ms first / 1000ms speculative)

### 7. No diffs in `input_extra`

The previous design included recent edit diffs (`DiffTracker` → `DiffEntry` objects) formatted as `original:/updated:` blocks. These were needed for the SweepAI NEP model which predicted the *next edit* based on recent edit patterns.

With standard FIM (Qwen3-Coder via `/infill`), the model fills the gap between prefix and suffix — it doesn't need edit history. The `DiffTracker` service (already removed in the FIM/NEP cleanup) does not need to be rebuilt.

**`input_extra` composition is now**:
1. Structure files from imports (very stable → first in array → best cache prefix)
2. Ring buffer chunks from recently visited files (semi-stable → after structure files)

---

## Prompt Structure (Draft)

*(Will be refined as decisions are made)*

### Completion Request

```json
{
    "id_slot": 0,
    "input_prefix": "<all lines above cursor line>",
    "input_suffix": "<rest of current line after cursor + all lines below>",
    "input_extra": [
        {"filename": "ImportedClass.kt", "text": "<surface-extracted signatures>"},
        {"filename": "CursorScopeType.kt", "text": "<surface-extracted signatures>"},
        {"filename": "recently-visited.kt", "text": "<ring buffer chunk>"}
    ],
    "prompt": "<current line text up to cursor>",
    "n_predict": 128,
    "n_indent": "<current line indentation level>",
    "top_k": 40,
    "top_p": 0.90,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "t_max_prompt_ms": 500,
    "t_max_predict_ms": 250,
    "response_fields": ["content", "timings/prompt_n", "timings/predicted_n",
                         "timings/predicted_ms", "truncated", "tokens_cached"]
}
```

### Cache Warming Request (background)

```json
{
    "id_slot": 0,
    "input_prefix": "",
    "input_suffix": "",
    "input_extra": [<same chunks as completion would use>],
    "prompt": "",
    "n_predict": 0,
    "temperature": 0.0,
    "samplers": [],
    "stream": false,
    "cache_prompt": true,
    "t_max_prompt_ms": 1,
    "t_max_predict_ms": 1,
    "response_fields": [""]
}
```
```

### `input_extra` Composition

```
[structure files — sorted by file path]  ← stable, good for KV cache prefix
[ring buffer chunks — sorted by key]     ← semi-stable
```

**Structure file resolution** depends on how much of the current file is sent:

- **Whole file fits** (< ~10k tokens): resolve all symbols referenced anywhere in the file. Since the model sees the entire file, every reference is relevant. Single-pass PSI walk over the full file.
- **Windowed fallback** (512/128): resolve symbols from two sources:
  - First 32 lines (headers/imports) — very stable, good for KV cache
  - Symbols within the 512/128 window — targeted context for the code being edited

In both cases, referenced project files are **surface-extracted** via the Structure View API — public signatures only, no method bodies. This gives ~70 tokens per file vs ~500 for full files.

**Ring buffer chunks**: 16 chunks × 64 lines (existing `ChunksRingBuffer` settings). Before including in `input_extra`, chunks with >0.5 similarity to the current cursor context are excluded to prevent echo-back suggestions.

No diffs — standard FIM doesn't need edit history.

### `input_prefix` / `input_suffix` / `prompt` Split

```
input_prefix = all lines above the cursor line (up to N lines)
prompt       = current line text up to cursor position
input_suffix = rest of current line + lines below (up to M lines)
```

---

## Quality Filters

### Repeat/duplicate detection

Before displaying a suggestion, discard it if:
1. First line is empty and remaining lines match existing file content below cursor
2. First line matches the suffix (model echoed what's already there)
3. First line + typed text matches the next non-empty line below
4. Multi-line suggestion matches existing lines below cursor
5. Suggestion is whitespace-only → suppress entirely

### Auto-trigger gating

Suppress auto-triggered FIM when there are **>8 characters after the cursor** on the current line. Editing in the middle of a line rarely benefits from completion — the suffix context is too constraining and suggestions tend to be wrong.

### Whitespace-only line handling

When the current line is all whitespace, treat `prompt` as `""` and `n_indent` as `0`. Don't send meaningless spaces as the FIM middle seed — let the model generate from scratch for that line.

---

## Trade-offs

1. **Whole-file prefix/suffix vs. windowed**: Sending the whole file gives the model maximum context but costs more tokens and more re-evaluation on each keystroke. The 10k token threshold (~1000 lines) is a pragmatic cutoff — files larger than this are rare and would starve `input_extra`.

2. **Surface extraction is a distribution shift**: The model was trained on full `<|file_sep|>` sections. Signatures without bodies look like abstract methods/interfaces — valid syntax but not what the model saw most during training. Validated in previous work (harness testing) as acceptable.

3. **No client-side caching**: Every keystroke triggers a server round-trip. This is simple but means latency is entirely server-dependent. The background cache warming mitigates this by keeping `input_extra` pre-loaded in the KV cache.

4. **Ring chunk similarity eviction**: Removes potentially useful context to prevent echo-back. A chunk that's 51% similar to the cursor context might still contain relevant code. The 0.5 threshold is a heuristic borrowed from llama.vim.

## Alternatives Considered

1. **`/completion` with manual FIM tokens** (previous approach): Full control over token ordering but model-specific, no `input_extra` support, manual FIM token assembly. Rejected in favor of `/infill` for model portability and server-side FIM handling.

2. **NEP original/current/updated pattern**: Requires SweepAI-specific model, doesn't map to `/infill`. Rejected — standard FIM is model-agnostic and sufficient.

3. **Client-side token budgeting by server**: Let the server handle truncation via its built-in context allocation. Rejected — client-side budgeting gives predictable behavior and avoids silent truncation surprises.

4. **Large `n_predict` with time cutoff only**: Set `n_predict: 512` and rely on `t_max_predict_ms: 250` to limit output. Rejected — explicit 128 token limit is more predictable and avoids wasted KV cache space.

5. **Diffs in `input_extra`**: Include recent edit history for the model to infer editing patterns. Rejected — standard FIM doesn't benefit from edit trajectories; that's an NEP concern.
