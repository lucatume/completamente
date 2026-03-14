# How llama.vim Uses the `/infill` Endpoint

**Question**: How does the llama.vim plugin use the llama.cpp `/infill` endpoint, and what patterns can be reused in completamente?

**Source**: https://github.com/ggml-org/llama.vim — `autoload/llama.vim` (1888 lines, fetched 2026-03-14)

---

## Architecture Overview

llama.vim is a single-file VimScript plugin (~1900 lines) that provides FIM completions via llama.cpp's `/infill` endpoint and instruction-based editing via `/v1/chat/completions`. All HTTP is done through async `curl` processes with JSON piped to stdin (`--data @-`).

The plugin has three main subsystems:
1. **FIM completion** — ghost-text suggestions triggered on cursor movement
2. **Ring buffer** — background extra-context management via cache-warming requests
3. **Instruct editing** — selection-based transformations via chat completions (not relevant here)

---

## 1. The `/infill` Request Shape

### Main FIM request (line 796–825)

```vim
let l:request = {
    \ 'id_slot':          0,
    \ 'input_prefix':     l:prefix,       " n_prefix lines before cursor (default 256)
    \ 'input_suffix':     l:suffix,       " n_suffix lines after cursor (default 64)
    \ 'input_extra':      l:extra,        " ring buffer chunks
    \ 'prompt':           l:middle,       " the current line up to cursor
    \ 'n_predict':        128,            " max tokens
    \ 'stop':             [],             " custom stop strings
    \ 'n_indent':         l:indent,       " current line indentation level
    \ 'top_k':            40,
    \ 'top_p':            0.90,
    \ 'samplers':         ["top_k", "top_p", "infill"],
    \ 'stream':           v:false,
    \ 'cache_prompt':     v:true,
    \ 't_max_prompt_ms':  500,            " max prompt processing time
    \ 't_max_predict_ms': l:t_max_predict_ms,  " max generation time
    \ 'response_fields':  [              " only return these fields
    \   "content",
    \   "timings/prompt_n",  "timings/prompt_ms", "timings/prompt_per_token_ms",
    \   "timings/prompt_per_second",
    \   "timings/predicted_n", "timings/predicted_ms", "timings/predicted_per_token_ms",
    \   "timings/predicted_per_second",
    \   "truncated", "tokens_cached"
    \ ],
    \ }
```

### Key observations about the request

| Field | Value | Why |
|-------|-------|-----|
| `input_prefix` | 256 lines before cursor (joined with `\n`) | Large prefix for context; the model sees the "story so far" |
| `input_suffix` | 64 lines after cursor (joined with `\n`) | Asymmetric: 4:1 prefix-to-suffix ratio |
| `prompt` | Current line text up to cursor position | **This is the "middle" in FIM** — the partial line being typed |
| `input_extra` | Array of `{text, filename, time}` objects | Cross-file context from the ring buffer |
| `n_indent` | Integer | Hint to the server about current indentation |
| `samplers` | `["top_k", "top_p", "infill"]` | The `"infill"` sampler is critical — it tells the server to apply FIM-specific sampling |
| `id_slot` | `0` | Pins all FIM requests to slot 0 (allows multi-slot servers to dedicate a slot) |
| `t_max_predict_ms` | 250ms (first request), 1000ms (speculative) | **First request is fast (250ms cap)**, then speculative follow-ups get more time |
| `response_fields` | Filtered list | Reduces response payload — only content + timing metrics |
| `stream` | `false` | Non-streaming for FIM (streaming is used for instruct) |

### The `prompt` field vs `input_prefix`

This is a subtle but important distinction:
- `input_prefix` = all the lines *above* the current line
- `prompt` = the current line text *up to the cursor* (the "middle" being typed)
- `input_suffix` = the rest of the current line (after cursor) + lines below

The server assembles these as: `<prefix><middle><fim_suffix><suffix><fim_middle>` using the model's FIM tokens.

---

## 2. Context Assembly (`s:fim_ctx_local`)

The local context is split at the cursor position (lines 607–679):

```
lines_prefix = getline(pos_y - n_prefix, pos_y - 1)  " 256 lines above
line_cur_prefix = line text before cursor              " partial current line
line_cur_suffix = line text after cursor               " rest of current line
lines_suffix = getline(pos_y + 1, pos_y + n_suffix)   " 64 lines below

prefix = join(lines_prefix, "\n") + "\n"
middle = line_cur_prefix                               " sent as `prompt`
suffix = line_cur_suffix + "\n" + join(lines_suffix, "\n") + "\n"
```

### Special case: whitespace-only lines

When the current line is all whitespace (line 620–628), the plugin treats the cursor as at position 0:
```vim
if match(l:line_cur, '^\s*$') >= 0
    let l:indent = 0
    let l:line_cur_prefix = ""
    let l:line_cur_suffix = ""
endif
```
This prevents sending meaningless whitespace as the "middle" — the model will generate from scratch for that line.

### Speculative context with previous completions

When called with a `prev` argument (a previously generated completion), the context is assembled *as if that completion was already inserted* (lines 630–651). This powers the speculative follow-up: after displaying suggestion A, immediately request what comes *after* A.

---

## 3. Ring Buffer (Extra Context)

### How chunks are gathered

Chunks are collected from five events (line 340–348):
1. **Yank** (`TextYankPost`) — yanked text
2. **Enter buffer** (`BufEnter`) — lines around cursor in the new buffer
3. **Leave buffer** (`BufLeave`) — lines around cursor in the old buffer
4. **Save file** (`BufWritePost`) — lines around cursor
5. **FIM request** (line 877–890) — lines from the broader scope around cursor (within `ring_scope` = 1024 lines)

Each chunk is `ring_chunk_size / 2` lines (32 lines by default), randomly sampled from a window.

### Chunk deduplication and eviction

- Exact duplicates are rejected (lines 456–475)
- Chunks with similarity > 0.9 (Jaccard-like on word tokens) are evicted from the queue (lines 478–499)
- During FIM requests, chunks with similarity > 0.5 to the *current cursor context* are evicted (lines 787–792) — this prevents the model from just repeating existing nearby code

### The `input_extra` format

Each chunk is sent as:
```json
{
  "text": "chunk content as string\n",
  "filename": "path/to/file",
  "time": [seconds, microseconds]
}
```

The server uses `filename` to wrap chunks in `<|file_sep|>filename\n` tokens.

### Cache-warming request (ring_update)

Every `ring_update_ms` (1000ms), one queued chunk is promoted to the active ring, and a **cache-warming request** is sent (lines 527–598):

```vim
let l:request = {
    \ 'id_slot':          0,
    \ 'input_prefix':     "",
    \ 'input_suffix':     "",
    \ 'input_extra':      l:extra,     " all ring chunks
    \ 'prompt':           "",
    \ 'n_predict':        0,           " generate nothing
    \ 'temperature':      0.0,
    \ 'samplers':         [],          " no sampling
    \ 'stream':           v:false,
    \ 'cache_prompt':     v:true,      " cache the prompt tokens
    \ 't_max_prompt_ms':  1,           " minimal time
    \ 't_max_predict_ms': 1,
    \ 'response_fields':  [""]         " empty response
    \ }
```

**Purpose**: Pre-process the extra context into the KV cache so that the next FIM request (which will include these same `input_extra` chunks) gets a cache hit on the prefix. This is the key to llama.vim's low-latency feel — the `input_extra` tokens are already cached when the actual FIM fires.

**Critical detail**: The cache-warming request uses `response_fields: [""]` and `n_predict: 0` — it wants *no output at all*, just the side effect of populating the KV cache.

---

## 4. Caching and Hash-Based Lookup

### Cache key construction

The cache key is a SHA-256 hash of `prefix + middle + 'Î' + suffix` (line 753). The `Î` character acts as a separator between the "before cursor" and "after cursor" contexts.

### Multiple hashes for scroll tolerance

The plugin generates up to 4 hashes by progressively trimming lines from the prefix (lines 755–763):
```vim
for i in range(3)
    let l:prefix_trim = substitute(l:prefix_trim, '^[^\n]*\n', '', '')
    call add(l:hashes, sha256(l:prefix_trim . l:middle . 'Î' . l:suffix))
endfor
```
This means: if you scroll down 1–3 lines from where a completion was generated, the cache can still be hit (the trimmed prefix matches a stored hash).

### Fuzzy cache matching (typing continuation)

When no exact hash match exists, the plugin looks back up to 128 characters (lines 976–1007):
```vim
for i in range(128)
    let l:removed = l:pm[-(1 + i):]
    let l:ctx_new = l:pm[:-(2 + i)] . 'Î' . l:suffix
    let l:hash_new = sha256(l:ctx_new)
    let l:response_cached = s:cache_get(l:hash_new)
    if l:response_cached != v:null
        " check if typed chars match the beginning of the cached completion
        if l:response['content'][0:i] !=# l:removed
            continue
        endif
        " trim the matching prefix from the cached content
        let l:response['content'] = l:response['content'][i + 1:]
    endif
endfor
```

**This is extremely clever**: if the user types 5 characters that match the first 5 characters of a cached completion, the plugin reuses the cached result with those 5 characters trimmed off — no server round-trip needed.

### LRU eviction

The cache holds up to `max_cache_keys` (250) entries with LRU eviction (lines 135–165).

---

## 5. Speculative Pre-fetching

After displaying a suggestion, llama.vim immediately fires a *speculative* FIM request (line 1013–1015):

```vim
if s:fim_hint_shown
    call llama#fim(l:pos_x, l:pos_y, v:true, s:fim_data['content'], v:true)
endif
```

This calls `llama#fim` with `prev` set to the *currently displayed suggestion*. The context assembly treats `prev` as if it were already accepted, so the server generates what would come *after* the current suggestion. The result is cached.

If the user accepts the suggestion (Tab) and the cursor ends up at the predicted position, the *next* suggestion is already in the cache — instant display with no latency.

### Two-tier prediction timing

- First request: `t_max_predict_ms = 250` (fast, user is waiting)
- Speculative follow-up: `t_max_predict_ms = 1000` (background, can take longer)

---

## 6. Duplicate/Repeat Detection

Before displaying a suggestion, the plugin checks for several degenerate cases (lines 1093–1148):

1. **Empty first line + remaining lines match file** → discard
2. **First line matches the suffix** → discard
3. **First line + typed text matches the next non-empty line** → discard
4. **Multi-line suggestion matches existing lines below** → discard
5. **Only whitespace** → mark as non-acceptable

These heuristics prevent the model from just echoing back code that's already in the file.

---

## 7. Auto-trigger Gating

FIM is **not** triggered when (line 737–739):
```vim
if a:is_auto && len(l:ctx_local['line_cur_suffix']) > g:llama_config.max_line_suffix
    return
endif
```

Default `max_line_suffix = 8`. If there are more than 8 characters after the cursor on the current line, auto-FIM is suppressed. This prevents suggestions when editing in the middle of a line.

---

## 8. Request Debouncing

If a FIM request is already in flight, new requests are debounced with a 100ms timer (lines 714–722):
```vim
if s:current_job_fim != v:null
    let s:timer_fim = timer_start(100, {-> llama#fim(...)})
    return
endif
```

Only one FIM request can be active at a time. New cursor movements queue a retry after 100ms.

---

## Summary: Key Patterns for completamente

| Pattern | llama.vim approach | Relevance to completamente |
|---------|-------------------|---------------------------|
| **Prefix/suffix split** | 256 prefix + 64 suffix lines, asymmetric 4:1 | Already using 45/15 asymmetric window — could test larger prefix |
| **`prompt` field** | Current line up to cursor sent separately | Maps to our "middle" — ensure this is sent correctly |
| **Samplers** | `["top_k", "top_p", "infill"]` | Must include `"infill"` sampler for FIM-specific behavior |
| **`id_slot`** | Fixed to 0 for all FIM | Pin to a slot for dedicated KV cache |
| **Cache warming** | `n_predict=0` with `input_extra` to pre-fill KV cache | Already researched — should implement for structure files |
| **Speculative pre-fetch** | After display, immediately request "what comes after this suggestion" | High-value optimization — pre-cache the next completion |
| **Typed-character cache reuse** | Check if typed chars match cached completion prefix, trim and reuse | Eliminates round-trips as user types matching characters |
| **Multi-hash scroll tolerance** | 4 hashes with progressive prefix trimming | Allows cache hits after small scrolls |
| **Repeat detection** | Discard suggestions that echo existing file content | Essential for usable suggestions |
| **Auto-trigger gating** | Skip if > 8 chars after cursor | Already have similar logic |
| **Time-boxed generation** | 250ms first request, 1000ms speculative | Two-tier approach is elegant |
| **`response_fields`** | Filter to content + timings only | Reduces payload — good practice |
| **Ring buffer similarity eviction** | Evict chunks similar to current context (>0.5) before FIM | Prevents repetitive suggestions |
| **`t_max_prompt_ms`** | Set to 500ms for FIM, 1ms for cache warming | Controls prompt processing budget |
| **Chunk collection events** | Yank, buffer enter/leave, save, cursor scope | Maps to IntelliJ editor events |

### Most impactful patterns not yet in completamente

1. **Speculative pre-fetching** — after showing a suggestion, immediately request what comes after it
2. **Typed-character cache matching** — reuse cached completions as the user types matching characters
3. **Two-tier timing** — fast first response (250ms), longer speculative (1000ms)
4. **`"infill"` sampler** — explicitly requesting FIM-specific sampling
5. **Cache-warming with `input_extra`** — background pre-caching of context chunks

---

## Source Reference

- Full source: `/tmp/llama.vim` (fetched from GitHub, 1888 lines)
- GitHub: https://github.com/ggml-org/llama.vim
- FIM request assembly: lines 700–891
- Cache warming: lines 527–598
- Cache lookup with typed-char matching: lines 953–1017
- Repeat detection: lines 1093–1148
- Ring buffer management: lines 424–598
