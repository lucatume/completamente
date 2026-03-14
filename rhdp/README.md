# Reference Documents

## Current Model & Prompt Composition

**Model**: SweepAI 1.5b FIM/NEP (fine-tuned for fill-in-the-middle and next-edit prediction)
**Context window**: 8192 tokens (7680 prompt budget after reserving 512 for output)
**Server**: llama.cpp with `cache_prompt: true`

### Prompt Format

Uses the SweepAI `<|file_sep|>` format with an `original/current/updated` structure for the file under edit, plus context sections for cross-file references, recent changes, and recently visited code.

### Prompt Sections (appearance order)

```
[header structure files]     ← surface-extracted API skeletons from file imports (most stable, KV cache prefix)
[window structure files]     ← surface-extracted from symbols used near the cursor (semi-stable)
[ring chunks]                ← 64-line snippets from recently visited files (semi-stable)
[recent diffs]               ← coalesced edit diffs from the current session (semi-stable)
[original window]            ← 60-line asymmetric window of file before latest change (stable within session)
[current window]             ← 60-line asymmetric window of file as-is (changes every keystroke)
[updated marker]             ← model generates from here
```

### Budget Allocation (priority order)

Sections consume the token budget in this order; later sections absorb pressure when budget is tight:

| Priority | Section | Limit | Typical tokens |
|----------|---------|-------|----------------|
| 1 (mandatory) | Original window | 60 lines | ~600 |
| 2 (mandatory) | Current window | 60 lines | ~600 |
| 3 | Recent diffs | up to 6 | ~50–300 |
| 4 | Header structure files | up to 16 | ~50–1200 |
| 5 | Window structure files | up to 8 | ~50–560 |
| 6 | Ring chunks | up to 6 | ~200–3600 |

### Key Design Decisions

- **Asymmetric window**: 75% above cursor / 25% below (45 lines above, 15 below). Code above establishes intent; code below is the prediction target.
- **Header-based definition resolution**: Imports/use statements in the first 16 lines are resolved via IntelliJ PSI. Each referenced project file is surface-extracted (public signatures only, no method bodies) via the Structure View API. Stable across keystrokes → good KV cache reuse.
- **Window-based symbol resolution**: A second pass scans the 60-line cursor window for references not already covered by header resolution. Provides targeted context for the code being edited right now.
- **Surface extraction**: ~70 tokens per file vs ~500 for full files. The model sees complete API contracts without consuming budget on implementation details.
- **Budget/prompt order separation**: Budget order reflects importance (file windows first). Prompt order reflects KV cache stability (structure files first) and attention proximity (current window closest to generation point).
- **Token estimation**: ~3.3 characters per token (conservative, overestimates slightly).

---

## Document Index

| # | File | Type | Description |
|---|------|------|-------------|
| 01 | 01-research-intellij-nep-api.md | research | IntelliJ platform APIs for inline completion and NEP |
| 02 | 02-research-sweep-approach.md | research | How SweepAI approaches FIM and NEP |
| 03 | 03-harness-download-sweepai-model.py | harness | Script to download the SweepAI 1.5b model |
| 04 | 04-harness-sweepai-llama-cpp-server.sh | harness | Script to start llama.cpp server with the SweepAI model |
| 05 | 05-design-context-assembly.md | design | Context assembly: diff tracking (DiffTracker) and chunk ring wiring into prompts |
| 06 | 06-research-sweepai-prompt-structure.md | research | Structure and format of SweepAI prompts |
| 07 | 07-research-llamacpp-prompt-caching-for-fim.md | research | llama.cpp prompt caching strategies for FIM completions |
| 08 | 08-design-server-management.md | design | Design for managed completion server lifecycle |
| 09 | 09-research-intellij-file-level-symbol-references.md | research | IntelliJ API for collecting project files referenced by symbols in a file |
| 10 | 10-design-prompt-composition.md | design | Prompt composition: token budgeting, asymmetric window, header+window symbol resolution, surface extraction |
| 11 | 11-harness-order89-prompt-structures.sh | harness | Test prompt structures for Order 89 claude -p command to find cleanest code-only output |
| 12 | 12-output-order89-prompt-structures.txt | output | Results from harness 11: prompt structure test outputs |
| 13 | 13-design-order89.md | design | Design for the Order 89 feature: modal-driven shell command text transformation |
| 14 | 14-plan-order89.md | plan | Step-by-step implementation plan for Order 89 (10 steps) |
| 15 | 15-research-99-neovim-claude-code.md | research | How ThePrimeagen's 99 Neovim plugin invokes Claude Code and comparison with Order 89 |
| 16 | 16-plan-order89-v2-tmpfile-prompt.md | plan | Plan for Order 89 v2: temp file prompt with `<Order89UserSelection>` tags, `--dangerously-skip-permissions` |
| 17 | 17-plan-order89-multiline-inlay-multi-session.md | plan | Plan for Order 89 multiline inlay hints and multi-session support |
| 18 | 18-research-order89-comment-stripping.md | research | How 99 constrains output and approaches for stripping non-code prose from Order 89 model responses |
| 19 | 19-design-order89-prompt-and-output-cleaning.md | design | Prompt v3 template with `<Order89Rules>` and layered output cleaning pipeline (extractCodeBlock → stripLeadingProse → stripTrailingProse) |
| 20 | 20-plan-order89-prompt-v3-output-cleaning.md | plan | 7-step implementation plan for prompt v3 and output cleaning pipeline in Order89Executor |
| 21 | 21-research-intellij-text-color-effects.md | research | Text color effects in IntelliJ: gradients, animated colors, and per-character styling via Graphics2D |
| 22 | 22-plan-enhanced-text-effects.md | plan | 9-step plan for gradient FIM/NEP ghost text and pulsing Order 89 status effects |
| 23 | 23-research-inline-completion-suppression.md | research | How InlineCompletionProvider works and how completamente suppresses Full Line and cloud completions |
| 24 | 24-research-order89-cursor-based-cancellation.md | research | Current ESC cancellation behavior, RangeMarker API, and cursor-position-based session matching |
| 25 | 25-design-order89-cursor-based-cancellation.md | design | Cursor-aware ESC cancellation using RangeMarker per session with ESC passthrough |
| 26 | 26-plan-order89-cursor-based-cancellation.md | plan | 5-step implementation plan: add RangeMarker to session, rewrite ESC handler, dispose on completion |
| 27 | 27-research-llamacpp-infill-endpoint.md | research | llama.cpp `/infill` endpoint API: request/response format, FIM token assembly, comparison with `/completion` |
| 28 | 28-harness-infill-endpoint-test.sh | harness | Test script exercising `/infill` endpoint: basic infill, extra context, field filtering, cache warming, comparison with `/completion` |
| 30 | 30-harness-fim-matrix-test.py | harness | FIM matrix test: 4 languages × 5 cursor positions × 3 file sizes × 4 complexity levels (240 cases) |
| 32 | 32-research-llama-vim-infill-usage.md | research | How llama.vim uses `/infill`: request shape, context assembly, ring buffer, cache warming, speculative pre-fetch, typed-char cache reuse |
| 33 | 33-harness-llama-vim-style-infill.sh | harness | Same 5 tests as harness 28 but using llama.vim request patterns: prompt field, id_slot, samplers, cache warming, speculative pre-fetch |
| 34 | 34-output-llama-vim-style-infill.txt | output | Results from harness 33: side-by-side comparison of simple vs llama.vim-style /infill requests |
