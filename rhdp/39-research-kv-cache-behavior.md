# Research: KV Cache Behavior in llama.cpp Server

## Question

How does the KV cache work in llama.cpp, and specifically:
1. Does token order matter? (prompt ABC cached → does BAC hit cache?)
2. Are "islands" of content cached? (prompt ABC cached → does XBC hit cache for BC?)

## Findings

### The KV cache is strictly prefix-based (by default)

The function `get_common_prefix()` in `server-common.cpp` compares tokens **sequentially from position 0** and returns the index of the first mismatch:

```cpp
for (size_t i = 0; i < max_idx; ++i) {
    if (tokens[i] == b.tokens[i]) continue;
    return i;  // first mismatch = end of common prefix
}
```

Everything after the first mismatch is **discarded and recomputed**. There is no island matching by default.

### Answer to Q1: Order absolutely matters

If request 1 produces tokens `[A, B, C]` and request 2 produces `[B, A, C]`, the common prefix is **zero** — the very first token differs. With default settings (`cache_prompt: true`, no `--cache-reuse`), **nothing is reused**. The entire prompt is re-evaluated from scratch.

This has a critical implication: **reordering `input_extra` chunks between requests kills the cache**. Even if the same chunks are present, putting them in a different order means different tokens at each position.

### Answer to Q2: No island caching (by default)

If the cache has `[A, B, C]` and the new prompt is `[X, B, C]`, the common prefix is **zero** (A ≠ X at position 0). The B and C tokens are not reused even though they're identical — they appear at different positions after the prefix breaks.

**The entire suffix after the first mismatch is re-evaluated.**

### The `--cache-reuse N` escape hatch

The `--cache-reuse N` server flag (introduced in PR #9866) enables **non-contiguous chunk reuse** after the prefix match:

- After the common prefix is found, the server scans remaining cached tokens for contiguous matching chunks of at least N tokens
- Matched chunks are **shifted** to their new positions via `seq_rm` + `seq_add` (adjusting RoPE positional encoding)
- The matching is **sequential** — reused chunks must appear in the same relative order

Example from the PR:
```
cached:  aaaaabbbbbcccccccdddddeeeeeexffggggghhhhhhhxxxxxxxxx
new:     aaaaaccccccceeeeeeffhhhhhhhyyyyyyyy

--cache-reuse 0: reuses only "aaaaa" (5 tokens — prefix only)
--cache-reuse 1: reuses "aaaaaccccccceeeeeeffhhhhhhh" (27 tokens — chunks of 1+)
--cache-reuse 3: reuses "aaaaaccccccceeeeee" (18 tokens — chunks of 3+)
```

**However**: `--cache-reuse` has overhead (scanning + KV shifting), and the shifted chunks have adjusted positional encoding which may slightly affect attention quality. For FIM completions where the prompt changes rapidly, the benefit may not justify the cost.

### How `/infill` assembles tokens (affects cache layout)

The server's `format_prompt_infill` builds the final token sequence as:

```
<|repo_name|>reponame
<|file_sep|>chunk_0_filename    ← input_extra[0]
chunk_0_text
<|file_sep|>chunk_1_filename    ← input_extra[1]
chunk_1_text
...
<|file_sep|>current_filename    ← the "current file" separator
<|fim_prefix|>{input_prefix}<|fim_suffix|>{input_suffix}<|fim_middle|>{prompt}
```

**Key insight**: `input_extra` chunks come **before** the current file's prefix/suffix. This means:
- Stable extra chunks at the start = good cache prefix
- Changing an early extra chunk = invalidates everything after it
- The current file's prefix/suffix always comes last = always re-evaluated when extras change

### Multi-slot behavior

Each slot has its own independent KV cache, scoped by `seq_id`. With `--parallel 1`, there's one slot — all requests share one cache. With `--parallel N`, pinning FIM to `id_slot: 0` ensures consistent cache reuse.

### Slot selection with `cache_prompt: true`

When a request arrives, the server scores idle slots by:
```
similarity = get_common_prefix(new_tokens) / new_tokens.size()
```
If `similarity > slot_prompt_similarity` (default 0.10), that slot is preferred. Otherwise, LRU eviction. With `id_slot: 0` pinning, this scoring is bypassed.

## Implications for Prompt Design

### Most stable content MUST come first

Since the cache is prefix-based, the token sequence should be ordered by stability:

```
[most stable content]           ← cached across many requests
[semi-stable content]           ← cached across several requests
[frequently changing content]   ← re-evaluated each request
```

For FIM with `/infill`:

| Position | Content | Stability |
|----------|---------|-----------|
| First in `input_extra` | Structure files from imports | Very stable (change when imports change) |
| Middle of `input_extra` | Ring buffer chunks | Semi-stable (change on buffer switch) |
| Last in `input_extra` | Recent diffs | Semi-stable (change on edit pause) |
| `input_prefix` | Lines above cursor | Changes on every keystroke (grows) |
| `input_suffix` | Lines below cursor | Changes on every keystroke (shrinks) |
| `prompt` | Current line to cursor | Changes on every keystroke |

### Keep `input_extra` order consistent between requests

If the same chunks are present, they MUST be in the same order. Sorting by a stable key (e.g., file path) ensures order consistency even when chunks are added/removed.

### Append-only growth is the best case

When the user types, the prefix grows by appending tokens. Since `input_extra` stays the same and the prefix is an append-only extension, the common prefix is maximal — only the newly typed tokens need evaluation.

### Avoid prepending or inserting into `input_extra`

Adding a new chunk at the **beginning** of `input_extra` shifts all subsequent tokens, breaking the entire cache. New chunks should be **appended** to the end of the extra list.

## Empirical Results (Harness 37)

Tested against Qwen3-Coder-30B-A3B on llama.cpp server with `--parallel 1 --cache-prompt`.

### Raw results

| Test | Description | prompt_n | Total | Cache hit % |
|------|-------------|----------|-------|-------------|
| 1 | Cold ABC | 370 | 370 | 0% (cold) |
| 2 | Identical ABC repeat | 1 | 370 | 99.7% |
| 3b | BAC vs ABC (full reorder) | 364 | 369 | 1.4% |
| 4b | ACB vs ABC (extras swapped only) | 365 | 370 | 1.4% |
| 5b | XBC vs ABC (prefix changed, extras same) | 132 | 401 | 67% |
| 6b | AXC vs ABC (first extra changed) | 375 | 380 | 1.3% |
| 7b | ABCD vs ABC (extra appended) | 242 | 507 | 52% |
| 8b | Typing +1 line (same extras, prefix grows) | 13 | 203 | 93.6% |

### What the results confirm

1. **Identical prompts are cached perfectly** (Test 2: 1 token re-evaluated)
2. **Reordering kills the cache** (Tests 3b, 4b: ~99% re-evaluation)
3. **Changing the first extra kills everything after it** (Test 6b: 375/380 re-evaluated)
4. **Typing is the best case** (Test 8b: only 13 new tokens for a full line)
5. **Strictly prefix-based** — no evidence of island caching

### Surprise: `input_extra` is naturally cache-friendly

Test 5b was expected to be a full cache miss (changed `input_prefix`, same extras). Instead, only 132/401 tokens needed re-evaluation. This is because `/infill` assembles the token sequence as:

```
[input_extra chunks] → [FIM_PRE + input_prefix] → [FIM_SUF + input_suffix] → [FIM_MID + prompt]
```

The extras come **before** the prefix/suffix in the token sequence. So when only the `input_prefix` changes (e.g., user types a new line), the extras form a stable cached prefix. Only the changed prefix/suffix/prompt tokens need re-evaluation.

**This is the critical architectural insight**: `input_extra` stability directly translates to KV cache efficiency. The plugin should:
- Keep `input_extra` chunks stable and in consistent order across requests
- Put the most stable chunks first in the `input_extra` array
- Accept that `input_prefix`/`input_suffix` will always be re-evaluated (they change every keystroke)
- Avoid adding/removing/reordering `input_extra` chunks frequently

### Appending new extras is partially cache-friendly

Test 7b shows that appending a new extra chunk preserves the cache for all preceding extras (B+C cached, only new X + shifted prefix re-evaluated: 242/507 = 52% miss). This is better than reordering (which gives ~99% miss) but still means the prefix/suffix tokens after the new chunk must be re-evaluated since their positions shifted.

## Sources

- [llama.cpp `server-common.cpp` — `get_common_prefix()`](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/server-common.cpp)
- [llama.cpp `server.cpp` — slot selection and cache logic](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/server.cpp)
- [PR #9866 — KV cache reuse with shifting](https://github.com/ggml-org/llama.cpp/pull/9866)
- [llama.cpp server README — cache_prompt documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
