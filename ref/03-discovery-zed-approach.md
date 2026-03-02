# Discovery: Zed's Approach to Edit Sessions and Snapshots

## Overview

Zed does **not** model edit sessions as discrete objects with start/end boundaries. Instead, it
maintains a **continuous, rolling state** per project. "Sessions" are emergent from proximity in
space (lines) and time (pauses). This document captures the key architectural decisions and their
implications for our IntelliJ NEP implementation.

---

## 1. Core Data Model

### ProjectState — The Persistent Container

Each open project gets a `ProjectState` that lives for the project's lifetime. It holds
everything the prediction system needs:

```rust
struct ProjectState {
    events: VecDeque<StoredEvent>,              // Rolling history of recent buffer changes (max 6)
    last_event: Option<LastEvent>,              // Currently-accumulating edit (not yet finalized)
    recent_paths: VecDeque<ProjectPath>,        // Recently active file paths
    registered_buffers: HashMap<EntityId, RegisteredBuffer>,  // All tracked buffers
    current_prediction: Option<CurrentEditPrediction>,
    pending_predictions: ArrayVec<PendingPrediction, 2>,      // Max 2 in-flight requests
    user_actions: VecDeque<UserActionRecord>,   // Rolling 16-item action history
    context: Entity<RelatedExcerptStore>,       // Related files from LSP
}
```

### TextBufferSnapshot — The Fundamental Primitive

Zed's CRDT-based buffer gives every buffer version a **cheaply cloneable, immutable snapshot**.
A `StoredEvent` pairs a diff with the old snapshot that produced it:

```rust
pub struct StoredEvent {
    pub event: Arc<zeta_prompt::Event>,       // The diff representation
    pub old_snapshot: TextBufferSnapshot,      // Buffer state BEFORE this change
}
```

```rust
struct RegisteredBuffer {
    file: Option<Arc<dyn File>>,
    snapshot: TextBufferSnapshot,              // Current known snapshot
    last_position: Option<Anchor>,            // Last cursor position
}
```

Every time a buffer is edited, `report_changes_for_buffer()` swaps the old snapshot for the new
one and records what changed:

```rust
old_snapshot = mem::replace(&mut registered_buffer.snapshot, new_snapshot.clone());
```

This swap-and-record pattern means Zed always has both the before and after state available for
any change, without ever copying the full buffer contents.

### Anchors Over Offsets

Predictions store edits as **anchor-based ranges** rather than raw byte offsets:

```rust
pub edits: Arc<[(Range<Anchor>, Arc<str>)]>   // Ranges in the original snapshot + replacement text
pub snapshot: BufferSnapshot                   // The snapshot these anchors reference
```

Anchors reference positions in a specific snapshot version and can be resolved against later
snapshots. This makes predictions resilient to concurrent edits — the user can keep typing while
a prediction is in flight, and the result can still be mapped to the correct positions.

An `interpolate()` method recalculates anchor positions against the current buffer state when
presenting predictions.

---

## 2. Edit Coalescing — Implicit Session Boundaries

Rather than explicit session start/stop, Zed groups edits by **spatial and temporal proximity**.
This is the closest thing to "session boundaries" in Zed's model.

### Constants

```rust
const EVENT_COUNT_MAX: usize = 6;                               // Max events in rolling history
const CHANGE_GROUPING_LINE_SPAN: u32 = 8;                       // Edits within 8 lines coalesce
const LAST_CHANGE_GROUPING_TIME: Duration = Duration::from_secs(1);  // 1-second pause boundary
```

### Coalescing Rules

When a new buffer edit arrives, the system checks the currently-accumulating `LastEvent`:

1. **Same buffer, within 8 lines** — The edits are **coalesced**. The `LastEvent` is updated
   with the new snapshot and expanded edit range, but no new event is pushed to the history.

2. **1-second pause within a coalesced group** — A `snapshot_after_last_editing_pause` is saved.
   The event can later be **split by pause** into two sub-events when constructing the prompt,
   giving the model finer-grained history.

3. **Different buffer or distant edit (>8 lines)** — The current `LastEvent` is **finalized**
   into a `StoredEvent` (computing the unified diff), pushed onto the events deque, and a new
   `LastEvent` begins.

4. **Deque overflow** — When the deque exceeds `EVENT_COUNT_MAX` (6), the oldest event is
   dropped.

### User Action Tracking

Alongside events, Zed maintains a rolling 16-item `UserActionRecord` deque, classifying each
action as one of: `InsertChar`, `DeleteChar`, `InsertSelection`, `DeleteSelection`, or
`CursorMovement`. These give the model behavioral context — "the user has been deleting a lot"
vs "the user just moved to a new location."

---

## 3. Prediction Trigger and Throttle Pipeline

### Trigger Sources

- **Buffer edits** — `BufferEvent::Edited` fires `report_changes_for_buffer()`, then the
  `EditPredictionDelegate::refresh()` method is called.
- **Diagnostics updates** — Under the Zeta2 feature flag, LSP diagnostic changes trigger
  predictions at the closest diagnostic location (for "jump to fix" predictions).
- **Cursor movement** — Also triggers a refresh.

### Throttling

All prediction requests go through `queue_prediction_refresh()`:

```rust
const THROTTLE_TIMEOUT: Duration = Duration::from_millis(300);  // 300ms between requests
```

1. **Entity-based throttle** — If the last request was for the same buffer, wait up to 300ms
   before proceeding.
2. **Pending prediction cap** — Maximum 2 concurrent in-flight predictions (1 for Ollama). When
   a new request overflows the queue, the oldest pending prediction is cancelled.
3. **Anti-thrash** — `should_replace_prediction()` checks whether the new prediction's single
   edit is just a prefix extension of the current one. If so, the old prediction is kept to
   avoid visual flickering.

---

## 4. Prompt Construction

### What the Model Receives

The model gets three sections, with token budgets allocated in cascade (cursor excerpt is
mandatory; remaining budget goes to edit history, then related files):

#### Section 1: Related Files
LSP-resolved definitions and related code excerpts, formatted with file paths.

#### Section 2: Edit History
Recent `StoredEvent` diffs in reverse chronological order:

```
User edited "path/to/file.rs":
```diff
@@ -102,3 +102,3 @@
 context line
-old line
+new line
 context line
```
```

#### Section 3: Cursor Excerpt
Code around the cursor with three special markers:

```
```path/to/file.rs
...code before editable region...
<|editable_region_start|>
...code...
<|user_cursor_is_here|>
...more code...
<|editable_region_end|>
...code after editable region...
```
```

### Excerpt Construction (Three Phases)

1. **Symmetric expansion** (75% of budget) — Expand from cursor, alternating down then up, line
   by line.
2. **Syntax boundary expansion** — Expand to containing syntax nodes that fit within remaining
   tokens.
3. **Line-wise biased expansion** — Use leftover tokens expanding in the less-expanded
   direction.

Token budget: ~350 tokens for editable region, ~150 for surrounding context. Uses a
`BYTES_PER_TOKEN_GUESS = 3` heuristic.

### Prompt Format Variants (Zeta2)

Zed supports multiple prompt formats via a `ZetaFormat` enum:

| Format                       | Description                                  |
|------------------------------|----------------------------------------------|
| `V0112MiddleAtEnd`           | FIM-style with middle section at end         |
| `V0113Ordered`               | Sections in reading order                    |
| `V0114180EditableRegion`     | Default, 180-token editable region           |
| `V0120GitMergeMarkers`       | `<<<<<<< CURRENT` / `=======` / `>>>>>>> UPDATED` |
| `V0131GitMergeMarkersPrefix` | Git merge markers in prefix position         |

---

## 5. Model Output Processing

1. Extract text between `<|editable_region_start|>` and `<|editable_region_end|>` markers.
2. Remove `<|user_cursor_is_here|>` marker (recording its position for cursor placement).
3. Run `text_diff()` between old and new editable region text.
4. Trim common prefix/suffix to produce minimal edit operations.
5. Convert to **anchor-based edit ranges** referencing the original snapshot.

Speculative decoding (n-gram matching from input) keeps latency low: **p50 < 200ms, p90 <
500ms**.

---

## 6. Data Flow

```
User types / moves cursor
        │
        ▼
BufferEvent::Edited (or cursor move)
        │
        ▼
report_changes_for_buffer()
  ├─ Swap old/new TextBufferSnapshot
  ├─ Coalesce nearby edits (8 lines, 1-sec pause)
  └─ Record UserActionRecord
        │
        ▼
EditPredictionDelegate::refresh()
  └─ Check enablement, account status
        │
        ▼
queue_prediction_refresh()
  ├─ Throttle 300ms per entity
  └─ Max 2 pending predictions
        │
        ▼
request_prediction()
  ├─ Collect events (with pause splitting)
  ├─ Snapshot current buffer
  ├─ Gather related files from LSP
  └─ Build ZetaPromptInput
        │
        ▼
Model inference (zeta1 / zeta2 / ollama)
  ├─ Format prompt with special tokens
  └─ HTTP request to inference server
        │
        ▼
parse_edits()
  ├─ Diff model output vs original excerpt
  └─ Produce anchor-based edit ranges
        │
        ▼
CurrentEditPrediction stored in ProjectState
  └─ should_replace_prediction() to avoid UI thrash
        │
        ▼
EditPredictionDelegate::suggest()
  ├─ Find closest edit to cursor
  ├─ Group adjacent edits
  └─ Return EditPrediction::Local or EditPrediction::Jump
        │
        ▼
Editor renders as ghost text / diff popover
```

---

## 7. Key Takeaways for Our Implementation

### What Zed Gets Right

1. **Snapshots as the unit of truth.** Immutable, versioned snapshots make before/after diff
   computation trivial and enable predictions to survive concurrent edits via anchor resolution.

2. **Implicit session boundaries via coalescing.** The 8-line / 1-second heuristics are simple
   but effective at grouping related edits without requiring explicit session management.

3. **Rolling, bounded history.** 6 events + 16 user actions is enough context for the model
   without unbounded memory growth.

4. **Throttle + anti-thrash.** 300ms throttle and prefix-extension detection prevent both
   request spam and visual flickering.

5. **Anchor-based edits.** Predictions remain valid as the user continues typing, avoiding the
   "stale prediction" problem.

### Challenges for IntelliJ

- IntelliJ's `Document` is not CRDT-based and doesn't have cheap immutable snapshots. We'd need
  to manually capture document text at key points.
- IntelliJ doesn't have a built-in anchor system that survives arbitrary edits. We'd need to use
  `RangeMarker` objects (which IntelliJ does provide) as our anchor equivalent.
- The `DocumentListener` API gives us `beforeDocumentChange` and `documentChanged` events, which
  map well to Zed's `report_changes_for_buffer()` pattern.

### Design Decisions to Adopt

- **Coalescing by spatial proximity** (configurable line span) and **temporal proximity** (pause
  detection) rather than explicit session objects.
- **Rolling bounded event history** rather than unbounded logs.
- **Throttle per buffer** to prevent request spam.
- **Anti-thrash heuristic** — don't replace a displayed prediction with one that's just a
  prefix extension of it.

---

## Sources

### Blog Posts
- [Zed: Edit Prediction Launch Post](https://zed.dev/blog/edit-prediction)
- [Zed: Choose Your Edit Prediction Provider](https://zed.dev/blog/edit-prediction-providers)

### Documentation
- [Zed: Edit Prediction Docs](https://zed.dev/docs/ai/edit-prediction)

### Source Code (github.com/zed-industries/zed)
- `crates/edit_prediction/src/edit_prediction.rs` — Core: ProjectState, StoredEvent, coalescing, throttle
- `crates/edit_prediction/src/prediction.rs` — EditPrediction struct with anchor-based edits
- `crates/edit_prediction/src/cursor_excerpt.rs` — Three-phase editable region construction
- `crates/edit_prediction/src/zeta1.rs` — Zeta1 prompt construction and response parsing
- `crates/edit_prediction/src/zeta2.rs` — Zeta2 with multiple ZetaFormat variations
- `crates/zeta_prompt/src/zeta_prompt.rs` — ZetaPromptInput, Event enum, token budgets
- `crates/edit_prediction/src/udiff.rs` — Unified diff parsing/application
- `crates/edit_prediction/src/zed_edit_prediction_delegate.rs` — Editor-to-store bridge

### Model & Dataset
- [zed-industries/zeta on HuggingFace](https://huggingface.co/zed-industries/zeta)
- [zed-industries/zeta dataset on HuggingFace](https://huggingface.co/datasets/zed-industries/zeta)
