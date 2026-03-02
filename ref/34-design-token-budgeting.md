# Design: Token Budgeting for Prompt Assembly

## Goal

Ensure the FIM prompt fits within the model's 8192-token context window by budgeting tokens across prompt sections. Prioritize the sections that are most critical for completion quality: the current/original file windows are mandatory; diffs, definitions, and ring chunks are added only if space allows.

## Non-goals

- Exact token counting (a character-based estimate is sufficient)
- Changing the model's context size
- Changing the prompt format or section ordering

## Token estimation

Use a simple character-to-token ratio. For code, **1 token ≈ 3.5 characters** is a reasonable estimate. This is conservative (slightly overestimates tokens), which is the safe direction.

```kotlin
fun estimateTokens(text: String): Int = (text.length + 2) / 3  // ~3.3 chars/token, rounds up
```

Pre-compute and cache the estimate for each piece of context when it is collected, not at prompt assembly time.

## Budget allocation

Total budget: **8192 tokens**

| Section | Priority | Budget | Notes |
|---------|----------|--------|-------|
| Output (n_predict) | Reserved | 512 | Model completion tokens |
| Overhead | Reserved | 100 | `<\|file_sep\|>` markers, section labels |
| Original + Current windows | Mandatory | measured | Always included; ~600 tokens typical |
| Recent diffs | Mandatory | measured | Always included; already truncated at 20K chars each |
| Definition chunks | Fill | remaining | Added one at a time until budget exhausted |
| Ring chunks | Fill | remaining | Added one at a time until budget exhausted |

**Prompt budget = 8192 − 512 (output) − 100 (overhead) = 7580 tokens**

## Assembly algorithm

```
1. Compute mandatory sections:
   a. original window tokens  (pre-estimated or estimated at assembly)
   b. current window tokens   (estimated at assembly — changes every request)
   c. diff tokens             (pre-estimated when diffs are collected)

2. mandatory_total = original + current + diffs

3. remaining = 7580 − mandatory_total

4. If remaining <= 0: skip all optional sections, emit prompt with mandatory only

5. Add definition chunks (most stable, best for KV cache):
   for each chunk in definitionChunks:
       if chunk.estimatedTokens <= remaining:
           add chunk
           remaining -= chunk.estimatedTokens
       else: break

6. Add ring chunks:
   for each chunk in ringChunks:
       if chunk.estimatedTokens <= remaining:
           add chunk
           remaining -= chunk.estimatedTokens
       else: break

7. Assemble prompt in order:
   [definition chunks] → [ring chunks] → [diffs] → [original] → [current] → [updated marker]
```

## Where to cache token estimates

### DiffEntry

Add an `estimatedTokens` field. Compute it in `computeDiffEntries()` when diffs are materialized:

```kotlin
data class DiffEntry(
    val filePath: String,
    val original: String,
    val updated: String,
    val estimatedTokens: Int = 0
)
```

In `computeDiffEntries()`, after computing `original` and `updated`:
```kotlin
// Estimate tokens for the full diff section:
// "<|file_sep|>{filePath}.diff\noriginal:\n{original}\nupdated:\n{updated}"
val diffText = "$filePath.diff\noriginal:\n$original\nupdated:\n$updated"
val estimatedTokens = estimateTokens(diffText)
```

### Chunk

Add an `estimatedTokens` field. Compute it when chunks are created:

```kotlin
data class Chunk(
    val text: String,
    val time: Long,
    val filename: String,
    val estimatedTokens: Int = 0
)
```

When creating a Chunk (in `definitions.kt`, `ChunksRingBuffer`, etc.):
```kotlin
// Estimate tokens for the chunk section:
// "<|file_sep|>{filename}\n{text}"
val estimatedTokens = estimateTokens("$filename\n$text")
```

### Original/current windows

These are computed fresh each request (current content changes on every keystroke). Estimate at assembly time — this is just a string length division, negligible cost.

## Changes to buildFimPrompt

`buildFimPrompt` currently accepts a `FimRequest` and unconditionally includes all sections. Change it to respect the budget:

```kotlin
fun buildFimPrompt(request: FimRequest, tokenBudget: Int = 7580): String {
    val parts = mutableListOf<String>()
    var remaining = tokenBudget

    // 1. Measure mandatory sections
    val originalSection = buildOriginalSection(request)
    val currentSection = buildCurrentSection(request)
    val diffSections = buildDiffSections(request)

    val mandatoryTokens = estimateTokens(originalSection) +
                          estimateTokens(currentSection) +
                          diffSections.sumOf { it.estimatedTokens }
    remaining -= mandatoryTokens

    // 2. Fill definition chunks
    for (chunk in request.definitionChunks) {
        if (chunk.estimatedTokens > remaining) break
        parts.add("<|file_sep|>${chunk.filename}")
        parts.add(chunk.text)
        remaining -= chunk.estimatedTokens
    }

    // 3. Fill ring chunks
    for (chunk in request.chunks) {
        if (chunk.estimatedTokens > remaining) break
        parts.add("<|file_sep|>${chunk.filename}")
        parts.add(chunk.text)
        remaining -= chunk.estimatedTokens
    }

    // 4. Add mandatory sections (always at the end for KV cache stability)
    for (diff in request.recentDiffs) {
        parts.add("<|file_sep|>${diff.filePath}.diff")
        parts.add("original:")
        parts.add(truncateDiffText(diff.original))
        parts.add("updated:")
        parts.add(truncateDiffText(diff.updated))
    }

    parts.add(originalSection)
    parts.add(currentSection)
    parts.add("<|file_sep|>updated/${request.filePath}")

    return parts.joinToString("\n")
}
```

## Prompt ordering rationale

```
[definition chunks]   ← most stable (changes when imports change)
[ring chunks]         ← semi-stable (changes when user navigates files)
[recent diffs]        ← semi-stable (changes when user pauses editing)
[original window]     ← stable within a session (snapshot from file open)
[current window]      ← changes every keystroke
[updated marker]      ← fixed
```

This ordering maximizes llama.cpp KV cache prefix reuse. The most stable sections sit at the front of the prompt, so the KV cache stays valid as the user types.

Note: this moves definition chunks and ring chunks **before** diffs, which is a change from the current order. The current order is: definitions → ring → diffs → original → current. The new order keeps definitions and ring before diffs, which is the same relative order but now with explicit budgeting.

## Files to modify

| File | Change |
|------|--------|
| `fim.kt` | Add `estimateTokens()`, modify `buildFimPrompt()` to accept budget and gate optional sections |
| `DiffTracker.kt` | Add `estimatedTokens` to `DiffEntry`, compute in `computeDiffEntries()` |
| `ChunksRingBuffer.kt` | Add `estimatedTokens` to `Chunk`, compute when chunks are created |
| `definitions.kt` | Compute `estimatedTokens` when creating definition `Chunk`s |

## What this does NOT change

- The `FimRequest` data class stays the same (it already carries all sections)
- The prompt format stays the same (`<|file_sep|>` markers, section labels)
- The windowing logic stays the same (60-line window around cursor)
- The diff truncation stays the same (20K char limit per diff)
- The `n_predict = 512` stays the same

## Edge cases

- **All budget consumed by mandatory sections**: Definition chunks and ring chunks are silently dropped. This happens with many large diffs — acceptable, since diffs are higher-signal for NEP.
- **Single chunk exceeds remaining budget**: Skip it, try the next (it might be smaller). Or: stop entirely (simpler, and chunks are roughly similar size). Stopping is the simpler choice.
- **Empty prompt sections**: If there are no diffs, no definitions, and no ring chunks, the prompt is just original + current + marker. This already works today.
