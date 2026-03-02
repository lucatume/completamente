# Discovery: Sweep AI's Approach to Next Edit Prediction

## Overview

Sweep frames next-edit prediction as **edit continuation** rather than text continuation. The
core insight: most developer edits are low-entropy and highly predictable. After adding a
parameter to a function, you almost certainly need to use it in the body. Sweep targets these
"high-uncertainty decision points" with a clean `original/current/updated` prompt format.

A critical design principle: **"Almost correct" suggestions are worse than no suggestion at
all.** When a model generates 90% correct code, the developer still has to manually fix the
10%, interrupting flow. This is why Sweep uses whitespace-agnostic exact-match accuracy as
their primary evaluation metric.

---

## 1. The original/current/updated Prompt Format

### Prompt Structure

The `build_prompt` function (in `run_model.py` on HuggingFace) constructs:

```
<|file_sep|>{context_file_path_1}
{context file 1 contents}

<|file_sep|>{context_file_path_2}
{context file 2 contents}

<|file_sep|>{changed_file}.diff
original:
{original code before a recent edit}
updated:
{code after the recent edit}

<|file_sep|>original/{file_path}
{contents of the file PRIOR to the most recent change}

<|file_sep|>current/{file_path}
{current state of the file being edited}

<|file_sep|>updated/{file_path}
{MODEL GENERATES THIS — the predicted next state}
```

### Function Signature

```python
def build_prompt(
    context_files: dict,       # {file_path: contents} for related files
    recent_diffs: list,        # [{"file_path": ..., "original": ..., "updated": ...}]
    file_path: str,            # path of the file being edited
    original_content: str,     # file contents BEFORE the most recent change
    current_content: str       # CURRENT file contents
) -> str:
```

### What Each Section Contains

| Section                        | Content                                | Purpose                                     |
|--------------------------------|----------------------------------------|---------------------------------------------|
| `<\|file_sep\|>{path}`        | Related file contents                  | Cross-file context (open tabs, definitions) |
| `<\|file_sep\|>{path}.diff`   | `original:` and `updated:` blocks      | Recent edit pattern showing developer intent |
| `<\|file_sep\|>original/{path}` | File before the most recent change   | Baseline for understanding what changed     |
| `<\|file_sep\|>current/{path}` | Current file state                    | What the file looks like right now          |
| `<\|file_sep\|>updated/{path}` | (model generates)                     | Predicted next state of the file            |

### Design Choices

**`<|file_sep|>` over region markers.** Sweep explicitly avoids Zed-style
`<|editable_region_start|>` / `<|editable_region_end|>` markers because they tokenize poorly
(7 tokens each) and models frequently misplace them.

**Fixed 21-line window.** The `original` and `current` sections use a fixed window of 10 lines
above and 10 lines below the edit location. Sweep tried AST-based boundaries but found fixed
windows train better because:

1. Function lengths vary wildly (5 to 30+ lines), causing training instability
2. The model had to learn both *what* to generate and *where* the boundary should end
3. Fixed windows eliminate boundary prediction, letting the model focus on content correctness

---

## 2. File State Tracking

### DocumentTracker (VSCode Extension)

The VSCode extension (`github.com/sweepai/vscode-nes`) uses a `DocumentTracker` class:

- Created on extension activation
- Listens to `onDidChangeTextDocument` events
- Maintains **original file content snapshots** so the extension knows the file state *before*
  recent edits
- The `InlineEditProvider` references the tracker to obtain `original_file_contents`

### API Request Schema

The `AutocompleteRequest` sends both states to the server:

```typescript
{
    file_path: string,
    file_contents: string,                // current state
    original_file_contents: string,       // original/baseline state
    cursor_position: number,              // byte offset
    recent_changes: string,               // unified diff snippets
    changes_above_cursor: boolean,
    file_chunks: FileChunk[],             // context from other files
    retrieval_chunks: RetrievalChunk[],   // definitions/references
    editor_diagnostics: EditorDiagnostic[],
    recent_user_actions: UserAction[],    // cursor moves, inserts, deletes, undo/redo
    privacy_mode_enabled: boolean,
}
```

### User Action History

The extension tracks granular user actions:
- Cursor movements
- Text insertions and deletions
- Undo/redo operations
- Each with timestamps and line/offset positions

### Context Gathering

The `buildInput` method assembles:

| Source                    | Budget                                 |
|---------------------------|----------------------------------------|
| Recent visible buffers    | Visible ranges + focus lines, 60-line chunks |
| Recently viewed files     | Up to 5 files                          |
| Edit diff history         | Recent unified diffs                   |
| Diagnostics at cursor     | Up to 50 items                         |
| Definition chunks (LSP)   | Up to 6 locations, 9 lines above/below each |
| Usage/reference chunks    | Up to 6 reference locations            |
| Clipboard contents        | Max 20 lines                           |

### JetBrains-Specific: PSI Integration

In JetBrains IDEs, Sweep uses the **PSI (Program Structure Interface)** to fetch exact
definitions around the cursor. Performance: ~30ms cold, <1ms after cache hydration. This gave
a 3% improvement in acceptance rate with no additional latency.

---

## 3. Trigger Conditions and Suppression

### Primary Trigger

The `InlineEditProvider` implements VSCode's `InlineCompletionItemProvider`, triggered on every
keystroke/document change by the editor framework.

### Suppression Conditions

| Condition                                          | Effect   |
|----------------------------------------------------|----------|
| Inactive editor or unfocused window                | Suppress |
| Multi-line text selection                          | Suppress |
| Recent bulk edits (>200 chars or >8 lines in 1.5s)| Suppress |
| Read-only file                                     | Suppress |
| File exceeds size limits                           | Suppress |
| Snippet mode active                                | Suppress |
| File matches exclusion patterns (`.env`, certs)    | Suppress |
| Autocomplete is snoozed                            | Suppress |

### Debouncing

```typescript
INLINE_REQUEST_DEBOUNCE_MS = 300   // 300ms minimum between requests
```

The `waitForDebounce` method calculates elapsed time since the last request and delays
accordingly, respecting cancellation tokens.

### Stale Response Handling

Each request gets a unique ID. If a newer request arrives before the current one completes,
the system attempts **ghost text extension** — detecting what the user typed since the request
started and adjusting the suggestion. If extension fails validation, the response is discarded.

---

## 4. Edit Classification and Rendering

### Edit Classification

The `classifyEditDisplay` function determines how each predicted edit renders:

| Condition                                   | Display Mode |
|---------------------------------------------|-------------|
| Edit beyond 2-row buffer from cursor        | **JUMP**    |
| Edit before cursor with newlines            | **JUMP**    |
| Any edit starting before cursor             | **JUMP**    |
| Single newline at cursor boundary, <=1 line | **SUPPRESS** |
| Default (edit at/after cursor)              | **INLINE**  |

### INLINE Mode (Ghost Text)

- Standard `vscode.InlineCompletionItem` — dimmed ghost text at cursor
- Used when the predicted edit is at or just after the cursor
- Tab accepts via standard inline completion API

### JUMP Mode (Remote Diff Decoration)

The `JumpEditManager` renders a diff overlay at the remote location:

1. **Removal indicators** — Red highlight (`rgba(255, 90, 90, 0.22)`) showing deleted content
2. **Addition boxes** — Green-highlighted floating previews (`rgba(90, 210, 140, 0.22)`)
   rendered as **syntax-highlighted SVG images** using Shiki
3. **Navigation hints** — Ghost text like `"-> Edit at line X (Tab, Esc)"`

### Syntax Highlighting for Jump Edits

The `syntax-highlight-renderer.ts` module:
1. Detects dark/light theme from the user's VS Code settings
2. Tokenizes predicted code using Shiki's `codeToTokensBase()`
3. Generates SVG files with per-token coloring
4. Renders SVG as a `DecorationOptions` using `contentIconPath`
5. Caches SVGs by hash of (theme settings + content)

### Interaction

| Key             | Action                                               |
|-----------------|------------------------------------------------------|
| Tab             | Accept the edit (apply and reposition cursor)        |
| Alt+Tab / Alt+L | Alternative acceptance                               |
| Esc             | Dismiss suggestion                                   |
| Alt+Shift+Bksp  | Show last rejected suggestion (undo accidental reject) |

---

## 5. Diff Computation

### Model Output

The model generates the entire `updated/{file_path}` section — a complete rewrite of the
21-line window. **The model outputs a complete file fragment, not a diff.**

### Server-Side Diff

The server computes the diff between `current` content and `updated` output, returning:

```typescript
{
    completion: string,       // the suggested text
    start_index: number,      // byte offset where replacement begins
    end_index: number,        // byte offset where replacement ends
    confidence: number,       // 0-1 score
    autocomplete_id: string,
    elapsed_time_ms: number,
}
```

### Client-Side Diff (Jump Edits)

`JumpEditManager.getLineDiff()` performs character-level comparison to identify:
- Common prefix length
- Common suffix length
- Changed content regions

Used to render the red (removal) and green (addition) decorations.

### Response Normalization (Inline Edits)

Three passes:
1. **Prefix trimming** — remove already-typed text before cursor
2. **Suffix overlap removal** — trim completion text overlapping with document text after cursor
3. **No-op detection** — skip suggestions identical to current text

---

## 6. Session/Snapshot Concepts

### No Explicit Session Object

Sweep has no formal session object. State is implicitly managed through:

- **DocumentTracker** — maintains original file content snapshots per document
- **JumpEditManager** — maintains `PendingJumpEdit` state (completion, original/new lines,
  cursor origin, metrics)
- **Provider internals** — request IDs, cursor positions, document versions

### PendingJumpEdit State

```typescript
{
    completionResult,         // the API response
    documentUri,
    originalLines: string[],  // pre-edit document state
    newLines: string[],       // derived from applying the completion
    cursorOriginLine,         // to detect cursor movement
    metricsPayload
}
```

Automatically cleared when: document changes independently, active editor switches, or user
dismisses/accepts the suggestion.

### Edit and Selection History

- **Edit history** with bulk change detection (char/line thresholds over 1.5-second window)
- **Selection history** with 5-second lookback window
- **Recent user actions** (inserts, deletes, cursor moves, undo/redo) with timestamps

---

## 7. Performance and Caching

### KV Cache (Server-Side)

This is Sweep's performance differentiator. All published benchmarks use a cold cache:

| Metric     | Cold Cache   | Warm Cache |
|------------|-------------|------------|
| TTFT       | ~50-100ms   | ~10ms      |
| Decoding   | ~200-500ms  | ~50ms      |
| Total      | <500ms      | ~60ms      |

With warm cache, latency approaches theoretical limits (UI rendering ~10ms, network ~30ms),
enabling suggestions for **every keystroke**.

### Speculative Decoding

N-gram speculative decoding accelerates inference:
- Predicts multiple tokens at once using n-gram matching from input
- Naive linear search (~5ms overhead worst-case)
- Reduces decoding from ~3s (naive sequential) to sub-100ms

### Client-Side SVG Cache

Syntax-highlighted SVGs are cached by hash of (theme settings + content).

### No Client-Side Response Cache

The API client does **not** cache responses. The 300ms debounce is the primary mechanism
preventing excessive requests.

### Compression

Requests use **Brotli compression at quality 11** to minimize payload size.

### Infrastructure

- Moved from vLLM to TRT-LLM for production
- Geographically distributed datacenters (Oregon reduces west-coast latency from 143ms to 32ms)
- FP8 E4M3 quantization for production, Q8_0 GGUF for open-source distribution

---

## 8. Training Pipeline

### Phase 1: Data Generation
- ~80k FIM examples from 400 permissively-licensed GitHub repos (created in past 6 months to
  avoid contamination)
- **Syntax-Aware FIM (SAFIM)** — diff AST trees before/after commits, sample only from changed
  AST nodes
- Next-edit diffs generated by frontier LLM producing small related changes
- Upsampled for JetBrains language distribution: Java, Kotlin, Python, Ruby, C#

### Phase 2: SFT
- Full-parameter supervised fine-tuning via TRL on ~100k training samples
- 4 hours on 8x H100/H200 GPUs (Modal)
- LoRA/PEFT explicitly rejected — cannot learn basic pattern matching like identifying where
  the last change was made

### Phase 3: RL
- 2000 steps of RL to eliminate undesirable post-SFT behaviors
- Two reward functions:
  1. **Syntax Parsing Reward** — tree-sitter verifies generated code parses when merged back
  2. **Size Regularization Reward** — penalizes overly large diffs (excessive changes reduce
     acceptance rates)

### Base Model
- Qwen2 architecture, 1.5B and 0.5B parameter variants
- Apache 2.0 license
- Available on HuggingFace (GGUF Q8_0) and Ollama

---

## 9. Data Flow

```
User types / moves cursor
        │
        ▼
vscode.onDidChangeTextDocument
  └─ DocumentTracker captures original content snapshot
        │
        ▼
InlineEditProvider (VSCode inline completion trigger)
  ├─ Check suppression conditions
  ├─ Debounce 300ms
  └─ buildInput()
        │
        ▼
Context assembly
  ├─ original_file_contents (from DocumentTracker)
  ├─ file_contents (current buffer)
  ├─ recent_changes (unified diffs)
  ├─ recent_user_actions
  ├─ file_chunks (visible buffers, recent files)
  ├─ retrieval_chunks (definitions, references via LSP/PSI)
  ├─ editor_diagnostics
  └─ clipboard
        │
        ▼
API request (Brotli compressed)
  └─ Server builds original/current/updated prompt
  └─ Model generates updated/{file_path}
  └─ Server diffs current vs updated → (start_index, end_index, completion)
        │
        ▼
classifyEditDisplay()
  ├─ INLINE: InlineCompletionItem (ghost text at cursor)
  ├─ JUMP: JumpEditManager (SVG diff decoration at remote location)
  └─ SUPPRESS: discard
        │
        ▼
User accepts (Tab) or dismisses (Esc)
  ├─ Accept: apply replacement, adjust queued suggestions, trigger next
  └─ Dismiss: clear decorations
```

---

## 10. Key Takeaways for Our Implementation

### What Sweep Gets Right

1. **Clean prompt format.** `<|file_sep|>` with `original/current/updated` paths is elegant,
   tokenizer-friendly, and avoids the marker-misplacement problems of region-based formats.

2. **Fixed window sizes.** 10 lines above + 10 lines below is simpler than AST-based or
   token-budget-based region sizing, and trains better.

3. **Server-side diffing.** The server computes `(start_index, end_index, completion)` so the
   client doesn't need to run diff algorithms — just apply a replacement.

4. **"Almost correct is worse than nothing."** Exact-match accuracy as the primary metric keeps
   the bar high and prevents annoying suggestions.

5. **KV cache optimization.** Warm-cache performance (~60ms total) enables suggestions per
   keystroke, fundamentally changing the latency/quality tradeoff.

6. **PSI integration for JetBrains.** Using the IDE's code index for definition lookup is
   cheap and effective — we should do this.

### Challenges

1. **No open-source server.** The prompt format and model are open, but the inference server
   with KV cache optimization, diffing, and context assembly is proprietary.

2. **Fixed window may miss context.** 21 lines is enough for most edits but can miss larger
   refactoring context. Zed's token-budget approach is more adaptive.

3. **No explicit session management.** Like Zed, Sweep relies on implicit state rather than
   formal session objects. Continue's explicit chains may be easier to debug.

### Design Decisions to Consider

- **`original/current/updated` prompt format** as the primary prompt structure
- **Fixed-window excerpt sizing** (simpler) vs token-budget (more adaptive) — start with fixed
- **Server-side diffing** to keep the client simple
- **Edit classification** (INLINE/JUMP/SUPPRESS) as a clean rendering strategy
- **PSI-based context gathering** for JetBrains definition/reference lookup

---

## Sources

### Blog Posts
- [Open sourcing a 1.5B parameter Next-Edit Autocomplete](https://blog.sweep.dev/posts/oss-next-edit)
- [Building next-edit autocomplete for JetBrains](https://blog.sweep.dev/posts/next-edit-jetbrains)
- [Autocomplete Context](https://blog.sweep.dev/posts/autocomplete-context)

### Documentation
- [Sweep Docs: Next Edit / Tab Autocomplete](https://docs.sweep.dev/autocomplete)

### Model & Dataset
- [sweepai/sweep-next-edit-1.5B on HuggingFace](https://huggingface.co/sweepai/sweep-next-edit-1.5B)
- [sweepai/sweep-next-edit-0.5B on HuggingFace](https://huggingface.co/sweepai/sweep-next-edit-0.5B)
- [run_model.py](https://huggingface.co/sweepai/sweep-next-edit-1.5B/blob/main/run_model.py)

### Source Code
- [github.com/sweepai/vscode-nes](https://github.com/sweepai/vscode-nes)

### Community
- [Show HN discussion](https://news.ycombinator.com/item?id=46713106)
