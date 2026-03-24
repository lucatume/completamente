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
| 35 | 35-plan-remove-fim-nep.md | plan | Complete removal plan for FIM/NEP source and test files, preserving Order 89 and fragment collection |
| 36 | 36-design-prompt-structure-v2.md | design | Prompt structure v2: llama.vim-style `/infill` with 32k context, iterative design via Q&A |
| 37 | 37-harness-kv-cache-behavior.sh | harness | Tests KV cache: token order sensitivity, island caching, prefix growth, chunk reordering |
| 38 | 38-output-kv-cache-behavior.txt | output | Results from harness 37 (pending execution) |
| 39 | 39-research-kv-cache-behavior.md | research | How llama.cpp KV cache works: strictly prefix-based, order matters, no island caching |
| 40 | 40-plan-fim-v2-implementation.md | plan | 11-step implementation plan for FIM v2: llama.vim-style `/infill`, 32k context, TDD with reviewer loop |
| 41 | 41-research-inline-completion-reformatting.md | research | Reformatting ghost text before acceptance: IntelliJ APIs, approaches (temp PsiFile, getLineIndent, heuristic), what JetBrains Full Line Completion does |
| 42 | 42-design-suggestion-reindent.md | design | IndentOptions-aware suggestion reindentation: detect model indent style, normalize to project settings, preserve relative levels |
| 43 | 43-plan-suggestion-reindent.md | plan | 6-step implementation plan: pure reindent function, tests, snapshot capture, wiring into provider |
| 44 | 44-harness-controller-completion.sh | harness | 100x FIM completion test for Controller.php at WP_CLI:: cursor position |
| 45 | 45-output-controller-completion.txt | output | Results from harness 44: completion distribution for WP_CLI:: |
| 46 | 46-harness-whitespace-cursor-positions.sh | harness | 5 cursor positions × 10 runs testing leading/trailing newlines and spaces in completions |
| 47 | 47-output-whitespace-cursor-positions.txt | output | Results from harness 46: whitespace boundary analysis per cursor position |
| 48 | 48-design-completion-whitespace-trimming.md | design | trimCompletion() pure function: strip leading indent overlap on line 0 + trailing whitespace |
| 49 | 49-plan-completion-whitespace-trimming.md | plan | 6-step implementation plan: trim.kt, ~30 tests, wire into provider pipeline |
| 50 | 50-research-intellij-undo-free-document-edits.md | research | How to insert/modify text in an IntelliJ Document without it appearing in the undo history |
| 51 | 51-harness-order89-prompt-formats.py | harness | Test 8 prompt format variants for Order 89 against local llama.cpp to find best fencing/extraction strategy |
| 52 | 52-output-order89-prompt-formats.txt | output | Results from harness 51: 10 runs per variant, prompt format comparison for Order 89 local model fallback |
| 53 | 53-harness-order89-xml-output-tags.py | harness | Test <Order89OutputCode> XML tags vs backtick fences for Order 89 output extraction |
| 54 | 54-output-order89-xml-output-tags.txt | output | Results from harness 53: XML tags vs backtick fences comparison (10 runs each) |
| 55 | 55-harness-order89-convention-matching.py | harness | Test 8 generic prompt wordings for convention-matching quality (docblocks, types, naming, indent) |
| 56 | 56-output-order89-convention-matching.txt | output | Results from harness 55: convention-matching scores for 8 prompt variants (10 runs each) |
| 57 | 57-harness-order89-context-quality.py | harness | Test 7 context variants (none, full files, structure, windowed) for Order 89 code quality |
| 58 | 58-output-order89-context-quality.txt | output | Results from harness 57: context type vs code quality (10 runs each) |
| 59 | 59-harness-order89-structure-convention-fix.py | harness | Test 8 prompt variations to fix convention regression when structure context is provided |
| 60 | 60-output-order89-structure-convention-fix.txt | output | Results from harness 59: none of 7 structure prompt fixes (incl. strong negative) recovered docblocks |
| 61 | 61-harness-order89-chat-endpoint.py | harness | Test Order 89 prompts via /v1/chat/completions instruct endpoint (Qwen 3 A30B) |
| 62 | 62-output-order89-chat-endpoint.txt | output | Results from harness 61: chat endpoint worse than /completion — 0% docblocks across all variants |
| 63 | 63-output-order89-recommended-params.txt | output | Results from harness 59 re-run with Qwen3-Coder recommended params (temp=0.7, top_p=0.8, top_k=20) — D_structure_view wins 12.3/13.5 |
| 64 | 64-harness-order89-de-refinement.py | harness | Refine D/E prompt variants with repetition, neighbor-anchoring, final-nudge guardrails |
| 65 | 65-output-order89-de-refinement.txt | output | Results from harness 64: E2_repeat_x3 achieves perfect 13.0/13.5 with 100% docblocks + 100% API accuracy |
| 66 | 66-harness-order89-e2-ablation.py | harness | Ablation study: position vs repetition for E2 guardrails (12 variants, single/pair/triple, verbose/terse) |
| 67 | 67-output-order89-e2-ablation.txt | output | Results from harness 66: pre-file reminder is the key position — terse_reminder (49 chars) achieves perfect 13.0 |
| 68 | 68-design-order89-local-model.md | design | Replace Claude Code with local llama.cpp model for Order 89: prompt structure, HTTP client, settings, context assembly |
| 69 | 69-plan-order89-local-model.md | plan | 7-step implementation plan for Order 89 local model: settings, UI, executor rewrite, action update, tests, cleanup |
| 70 | 70-harness-tool-format-comparison.py | harness | Test 8 tool specification formats (native Qwen3, XML variants, JSON, markdown, plaintext) for token efficiency and correctness |
| 71 | 71-output-tool-format-comparison.txt | output | Results from harness 70: G_plaintext (107 tok, 3.00/3) and F_json_compact (110 tok, 3.00/3) best balance of quality and terseness |
| 72 | 72-harness-tool-format-multi-call.py | harness | Test multi-tool call capability: 2-call and 3-call prompts across top 4 formats |
| 73 | 73-output-tool-format-multi-call.txt | output | Results from harness 72: G_plaintext perfect 15/15 multi-call, F_json_compact 12/15, C_xml_terse 7/15 |
| 74 | 74-harness-order89-tool-integration.py | harness | Test tool calling within Order 89 prompts: Phase 1 (tool decision) and Phase 2 (code after result) |
| 75 | 75-output-order89-tool-integration.txt | output | Results from harness 74: Phase 1 68% tool calling, Phase 2 20% — model loops calling more tools |
| 76 | 76-harness-order89-tool-loop-fix.py | harness | Test 7 prompt structure variants to break the tool-calling loop after receiving results |
| 77 | 77-output-order89-tool-loop-fix.txt | output | Results from harness 76: V6_two_system best (7/15), T3/T5 loop across all variants — model needs more context |
| 78 | 78-harness-order89-tool-rich-results.py | harness | Test rich tool results + multi-turn + no-tools variants for complete Order 89 tool integration |
| 79 | 79-output-order89-tool-rich-results.txt | output | Results from harness 78: V9_inline_no_tools perfect 15/15 at 527 tokens, V8_multi_round also 15/15 |
| 80 | 80-design-order89-tool-usage.md | design | Order 89 tool usage: setting (off/manual/auto), two-phase execution, FileSearch/WebSearch, parallel calls |
| 81 | 81-harness-dash-api-exploration.py | harness | Test Dash HTTP API pipeline: health, docsets/list, search, load_url content for PHP/WordPress/Laravel/React/JS/NodeJS |
| 82 | 82-output-dash-api-exploration.txt | output | Results from harness 81: full API flow demonstrated across 6 docsets, cross-docset search, HTML content retrieval |
| 83 | 83-harness-docsearch-kotlin-prototype.kt | harness | Kotlin prototype of DocSearch tool: Dash API query → top 2 results → strip HTML → formatted text for model |
| 83 | 83-harness-docsearch-kotlin-prototype-runner.py | harness | Python runner for Kotlin prototype (no standalone kotlinc available); validates same DocSearch logic |
| 84 | 84-output-docsearch-kotlin-prototype.txt | output | Results from harness 83: all 8 test cases work; HTML nav/sidebar noise needs smarter stripping |
| 85 | 85-design-order89-docsearch.md | design | Replace WebSearch stub with DocSearch (Dash API): pipeline, HTML stripping, platform aliases, tool spec update |
