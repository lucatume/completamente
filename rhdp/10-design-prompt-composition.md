# Design: Prompt Composition

## Goal

Assemble a FIM/NEP prompt that fits the 8192-token context window, maximizes completion quality
through targeted cross-file context, and preserves KV cache stability across consecutive requests.

This design covers three concerns: token budgeting, prompt structure (asymmetric window + symbol
resolution), and the separation of budget priority from prompt appearance order.

## Non-goals

- Changing the context window size (stays at 8192 tokens) or output limit (stays at 512)
- Adding new context sources (diagnostics, usage chunks, user action tracking)
- Language-specific PSI handling — the approach works across all IntelliJ-supported languages

---

## 1. Token Estimation and Budget

### Estimation

Use a simple character-to-token ratio. For code, **1 token ~ 3.5 characters** is conservative
(slightly overestimates, which is the safe direction).

```kotlin
fun estimateTokens(text: String): Int = (text.length + 2) / 3  // ~3.3 chars/token, rounds up
```

Pre-compute and cache the estimate on each `Chunk` and `DiffEntry` when collected.

### Budget breakdown

```
Total context:     8192 tokens
Output (n_predict):  512
Overhead (markers):  100
Prompt budget:     7580 tokens
```

### Budget allocation order

Sections consume the budget in this priority. Earlier sections are guaranteed; later sections
absorb pressure when budget is tight.

| Priority | Section | Limit | Typical tokens |
|----------|---------|-------|----------------|
| 1 (mandatory) | Original window | 60 lines | ~600 |
| 2 (mandatory) | Current window | 60 lines | ~600 |
| 3 | Recent diffs | up to 6 | ~50–300 |
| 4 | Header structure files | up to 16 | ~50–1200 |
| 5 | Window structure files | up to 8 | ~50–560 |
| 6 | Ring chunks | up to 6 | ~200–3600 |

Ring chunks absorb the squeeze. Structure files, diffs, and file windows are never dropped.

### Assembly algorithm

```
1. Measure mandatory sections: original + current + diffs
2. remaining = 7580 − mandatory_total
3. If remaining <= 0: emit prompt with mandatory only
4. Add header structure files one at a time until budget exhausted
5. Add window structure files one at a time until budget exhausted
6. Add ring chunks one at a time until budget exhausted
7. Assemble prompt in appearance order (see §4)
```

---

## 2. Asymmetric Window

Allocate 75% of the 60-line window above the cursor and 25% below: **45 lines above, 15 below**.

```kotlin
val above = (maxLines * 3) / 4   // 45 for maxLines=60
val below = maxLines - above     // 15
val start = (focusLine - above).coerceAtLeast(0)
val end = (start + maxLines).coerceAtMost(lines.size)
val adjustedStart = (end - maxLines).coerceAtLeast(0)
```

**Rationale**: Code above the cursor establishes structure and intent (class declarations, preceding
methods, current method signature). Code below is the prediction target — the model generates it,
so showing it is less valuable. The 15 lines below provide enough trailing context for the
immediate scope.

---

## 3. Symbol Resolution

Two passes of PSI-based resolution provide cross-file context as surface-extracted API skeletons.

### Header-based resolution (primary, stable)

Scan PSI elements in the first 16 lines of the file (imports/use statements). For each reference:
1. Resolve via `ref.resolve()`
2. Filter out non-local files (vendor, stdlib) and the current file
3. Deduplicate by file path
4. Surface-extract using Structure View API — public signatures only, no method bodies

**Cap**: 16 structure files. A typical 150-line class becomes ~15 lines / ~70 tokens after
surface extraction — a 10x reduction vs full files.

| Approach | Tokens/file | 6 files | 12 files |
|----------|-------------|---------|----------|
| Full file | ~500 | ~3000 | ~6000 |
| ±9 line window | ~120 | ~700 | ~1400 |
| Surface extraction | ~70 | ~420 | ~840 |

**Why header-based**: Imports change rarely during a session → the structure section stays stable
across consecutive requests → maximizes KV cache prefix reuse.

### Window-based resolution (secondary, targeted)

After header resolution, scan the 60-line cursor window (excluding the first 16 lines already
covered) for references to project files not in the header set. Same PSI walk, same surface
extraction, same `Chunk` format.

**Cap**: 8 additional structure files. **Max total**: 24 (16 header + 8 window).

**Why**: Header imports declare *all* dependencies; window resolution highlights which are
*relevant right now*. Types used in the method body get their API surfaces included even if
they share the import list with 20 other types.

**KV cache impact**: Window structure files sit *after* the stable header prefix. They change
when the cursor crosses scope boundaries — modest invalidation, far less volatile than
per-keystroke changes.

### Data flow

```
FimSuggestionManager.showSuggestion()
  ├── buildFimRequest()                    // builds window, gets windowStartLine
  └── (pooled thread)
        ├── collectReferencedFilesFromHeader()   → headerPaths: Set<String>
        │     └── resolveStructureFiles()        → headerChunks: List<Chunk>
        ├── collectReferencedFilesFromWindow()   // excludes headerPaths
        │     └── resolveStructureFiles()        → windowChunks: List<Chunk>
        └── enrichedRequest = request.copy(
              structureFiles = headerChunks,
              windowStructureFiles = windowChunks
            )
```

---

## 4. Prompt Appearance Order

Budget allocation order and prompt appearance order are deliberately different.

```
[header structure files]     ← most stable, best KV cache prefix
[window structure files]     ← semi-stable (changes when cursor moves across scopes)
[ring chunks]                ← semi-stable
[recent diffs]               ← semi-stable
[original window]            ← stable within session
[current window]             ← changes every keystroke, closest to generation point
[updated marker]             ← model generates from here
```

**Why separate from budget order**:
- **Budget order** reflects importance: the file being edited must always be present.
- **Prompt order** reflects KV cache stability (stable sections first) and attention proximity
  (current window closest to generation point, where transformer attention is strongest).

---

## 5. Token Budget Walkthrough

### Typical case

```
Budget: 7680 tokens

Step 1 — Original window (mandatory)          -600  → 7080 remaining
Step 2 — Current window (mandatory)           -600  → 6480 remaining
Step 3 — 1 recent diff                         -50  → 6430 remaining
Step 4 — 5 header structure files (surface)   -200  → 6230 remaining
Step 5 — 3 window structure files             -210  → 6020 remaining
Step 6 — 6 ring chunks (64 lines each)       -3600  → 2420 remaining
Markers + separators                          -100  → 2320 remaining

Total used: ~5360    Unused: ~2320
```

### Worst case (many diffs, many imports)

```
Step 1 — Original window                      -600  → 7080 remaining
Step 2 — Current window                       -600  → 6480 remaining
Step 3 — 6 large diffs                       -1800  → 4680 remaining
Step 4 — 16 header structure files           -1200  → 3480 remaining
Step 5 — 8 window structure files             -560  → 2920 remaining
Step 6 — 6 ring chunks → only 4 fit         -2400  → 520 remaining
Markers                                       -100  → 420 remaining

Total used: ~7260    Unused: ~420
```

---

## 6. Prompt Example

PHP `OrderService` with cursor on line 65 inside `processOrder`:

```
<|file_sep|>/app/Models/Order.php
class Order extends Model
{
    protected $fillable = ['customer_id', 'subtotal', 'total', 'status', 'cancelled_at'];
    public function customer(): BelongsTo
    public function items(): HasMany
    public function scopePending($query)
    public function markShipped(): void
}

<|file_sep|>/app/Models/Customer.php
class Customer extends Model
{
    public int $id;
    public string $name;
    public string $email;
    public function orders(): HasMany
}

<|file_sep|>/app/Repositories/OrderRepository.php
class OrderRepository
{
    public function find(int $id): ?Order
    public function save(Order $order): void
    public function findByCustomer(int $customerId): Collection
}

<|file_sep|>/app/Http/Controllers/OrderController.php
    public function store(Request $request)
    {
        $service = app(OrderService::class);
        ...
    }

<|file_sep|>OrderService.php.diff
original:
        $total = $this->calculateTotal($subtotal);
updated:
        $total = $this->calculateTotal($subtotal, $discount);

<|file_sep|>original/OrderService.php
{45-line asymmetric window of file before latest change}

<|file_sep|>current/OrderService.php
{45-line asymmetric window of current file state}

<|file_sep|>updated/OrderService.php
```

---

## Trade-offs

1. **Surface extraction is a distribution shift.** The model was trained on full `<|file_sep|>`
   sections. Signatures without bodies look like abstract methods/interfaces — valid syntax but
   not what the model saw during training. Validated with test harness before shipping.

2. **The model may not see its own class name.** With the asymmetric window, the class declaration
   may be outside the 60-line window when the cursor is deep in the file. A future enhancement
   could include a one-line class header.

3. **Ring chunks at lowest priority.** When many diffs and structure files consume the budget,
   ring chunks may be partially excluded. Acceptable: structure files (stable API contracts) are
   higher-signal than random 64-line snippets.

4. **Window structure files add modest KV cache churn.** They change on scope boundaries but sit
   after the stable header prefix, so only the tail invalidates. Combined PSI time stays < 10ms.

## Alternatives considered

- **±9 line windows instead of surface extraction**: Less info per token, may catch method bodies
- **Full referenced files**: ~500 tokens/file, blows budget with 12+ references
- **Cursor-based definition resolution**: Unstable (changes every keystroke), breaks KV cache
- **Single merged header+window pass**: Loses ability to prioritize header over window in budget
- **Regex-based symbol extraction from window**: False positives, misses qualified references

---

## Files to modify

| File | Change |
|------|--------|
| `completion/fim.kt` | `estimateTokens()`, asymmetric window, budget-gated `buildFimPrompt`, `FimRequest` fields (`structureFiles`, `windowStructureFiles`) |
| `completion/definitions.kt` | `collectReferencedFilesFromHeader()`, `collectReferencedFilesFromWindow()`, surface extraction via Structure View API |
| `completion/structure.kt` | `MAX_WINDOW_STRUCTURE_FILES = 8` constant |
| `fim/FimSuggestionManager.kt` | Two-pass resolution, pass both structure file lists to enriched request |
| `DiffTracker.kt` | `estimatedTokens` field on `DiffEntry` |
| `ChunksRingBuffer.kt` | `estimatedTokens` field on `Chunk` |
