#!/usr/bin/env bash
# Harness: KV Cache Behavior in llama.cpp
#
# Tests two specific questions about KV cache behavior:
#   Q1: Does token ORDER matter? (prompt ABC cached → does BAC hit cache?)
#   Q2: Are "islands" cached? (prompt ABC cached → does XBC hit BC from cache?)
#
# Method: We use the /infill endpoint with cache_prompt:true and examine
# timings.prompt_n (tokens that needed NEW evaluation) vs tokens_cached
# (total tokens in KV cache after request). A cache hit means prompt_n is
# small relative to the total prompt size.
#
# Prerequisites:
#   - llama-server running on $SERVER (default http://localhost:8012)
#   - Model: Qwen3-Coder-30B-A3B or any FIM-capable model
#   - Server started with: --parallel 1 --cache-prompt (single slot for deterministic caching)
#
# Usage: bash rhdp/37-harness-kv-cache-behavior.sh [server_url]
#
# Output: Writes results to rhdp/38-output-kv-cache-behavior.txt

set -euo pipefail

SERVER="${1:-http://localhost:8012}"
OUTPUT="rhdp/38-output-kv-cache-behavior.txt"

# Check server is reachable
if ! curl -sf "$SERVER/health" > /dev/null 2>&1; then
    echo "ERROR: Server not reachable at $SERVER"
    echo "Start with: llama-server --model <path> --ctx-size 32768 --parallel 1 --cache-prompt --host 127.0.0.1 --port 8012"
    exit 1
fi

exec > >(tee "$OUTPUT") 2>&1

echo "KV Cache Behavior Test"
echo "Server: $SERVER"
echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo ""

# Helper: send an /infill request and extract cache-relevant metrics
infill() {
    local label="$1"
    local json="$2"
    echo "--- $label ---"
    echo "Request: $(echo "$json" | python3 -m json.tool 2>/dev/null || echo "$json")"
    local resp
    resp=$(curl -sf "$SERVER/infill" \
        -H "Content-Type: application/json" \
        -d "$json" 2>&1)

    # Extract key fields
    local prompt_n cached content truncated
    prompt_n=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('timings/prompt_n', d.get('timings',{}).get('prompt_n','?')))" 2>/dev/null || echo "?")
    cached=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tokens_cached','?'))" 2>/dev/null || echo "?")
    content=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(repr(d.get('content','?')))" 2>/dev/null || echo "?")

    echo "Response: prompt_n=$prompt_n, tokens_cached=$cached, content=$content"
    echo ""
}

# Helper: send a raw /completion request (for tests that need the assembled prompt visible)
completion() {
    local label="$1"
    local json="$2"
    echo "--- $label ---"
    echo "Request: $(echo "$json" | python3 -m json.tool 2>/dev/null || echo "$json")"
    local resp
    resp=$(curl -sf "$SERVER/completion" \
        -H "Content-Type: application/json" \
        -d "$json" 2>&1)

    local prompt_n cached content
    prompt_n=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('timings',{}).get('prompt_n','?'))" 2>/dev/null || echo "?")
    cached=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tokens_cached','?'))" 2>/dev/null || echo "?")
    content=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(repr(d.get('content','?')[:80]))" 2>/dev/null || echo "?")

    echo "Response: prompt_n=$prompt_n, tokens_cached=$cached, content=$content"
    echo ""
}

# ============================================================================
# We use /completion for precise control over token ordering.
# /infill wraps tokens server-side, making it harder to control exact order.
# The cache behavior is the same — it's about token position matching.
# ============================================================================

# Build substantial code blocks to make cache effects clearly visible.
# Each block is ~50-80 tokens — large enough that cache hits are obvious.

BLOCK_A='class UserService {
    private val users = mutableMapOf<Int, User>()

    fun getUser(id: Int): User? {
        return users[id]
    }

    fun createUser(name: String, email: String): User {
        val id = users.size + 1
        val user = User(id, name, email)
        users[id] = user
        return user
    }

    fun deleteUser(id: Int): Boolean {
        return users.remove(id) != null
    }
}'

BLOCK_B='class OrderService {
    private val orders = mutableListOf<Order>()

    fun placeOrder(userId: Int, items: List<Item>): Order {
        val total = items.sumOf { it.price * it.quantity }
        val order = Order(orders.size + 1, userId, items, total)
        orders.add(order)
        return order
    }

    fun getOrdersByUser(userId: Int): List<Order> {
        return orders.filter { it.userId == userId }
    }

    fun cancelOrder(orderId: Int): Boolean {
        return orders.removeIf { it.id == orderId }
    }
}'

BLOCK_C='class InventoryService {
    private val stock = mutableMapOf<String, Int>()

    fun addStock(itemCode: String, quantity: Int) {
        stock[itemCode] = (stock[itemCode] ?: 0) + quantity
    }

    fun removeStock(itemCode: String, quantity: Int): Boolean {
        val current = stock[itemCode] ?: return false
        if (current < quantity) return false
        stock[itemCode] = current - quantity
        return true
    }

    fun getStock(itemCode: String): Int {
        return stock[itemCode] ?: 0
    }
}'

BLOCK_X='class NotificationService {
    private val subscribers = mutableMapOf<String, MutableList<String>>()

    fun subscribe(topic: String, email: String) {
        subscribers.getOrPut(topic) { mutableListOf() }.add(email)
    }

    fun unsubscribe(topic: String, email: String) {
        subscribers[topic]?.remove(email)
    }

    fun notify(topic: String, message: String): Int {
        val emails = subscribers[topic] ?: return 0
        emails.forEach { sendEmail(it, message) }
        return emails.size
    }

    private fun sendEmail(to: String, body: String) { /* ... */ }
}'

# JSON-escape the blocks
escape_json() {
    python3 -c "import json,sys; print(json.dumps(sys.stdin.read()))" <<< "$1"
}

A_ESC=$(escape_json "$BLOCK_A")
B_ESC=$(escape_json "$BLOCK_B")
C_ESC=$(escape_json "$BLOCK_C")
X_ESC=$(escape_json "$BLOCK_X")

echo "========================================================================"
echo "TEST 1: BASELINE — Establish cache with prompt ABC"
echo ""
echo "Send prompt = A + B + C. This is the cold request that populates the KV"
echo "cache. All tokens must be evaluated from scratch."
echo "========================================================================"
echo ""

infill "1. Cold request: ABC (baseline)" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "TEST 2: SAME PROMPT — Verify warm cache hit"
echo ""
echo "Send the exact same prompt ABC again. If caching works, prompt_n should"
echo "be very small (only 1 new token for the generation seed)."
echo "========================================================================"
echo ""

infill "2. Warm request: ABC (identical repeat)" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "TEST 3: Q1 — REORDERED COMPONENTS (BAC instead of ABC)"
echo ""
echo "The cache has ABC. Now send BAC (swap A and B in input_extra)."
echo "If order matters: prompt_n ≈ total tokens (full re-evaluation)."
echo "If order doesn't matter: prompt_n should be small."
echo "========================================================================"
echo ""

# First re-prime with ABC to ensure clean state
infill "3a. Re-prime cache: ABC" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

infill "3b. Reordered: BAC (B is prefix, extra=[A, C])" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $B_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "UserService.kt", "text": $A_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "TEST 4: Q1 — REORDERED EXTRAS ONLY (ACB instead of ABC)"
echo ""
echo "The cache has BAC from test 3b. Now send ACB (same prefix A, but extras"
echo "swapped: [C, B] instead of [B, C])."
echo "This tests whether reordering ONLY the input_extra chunks matters."
echo "========================================================================"
echo ""

# Re-prime with ABC
infill "4a. Re-prime cache: ABC" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

infill "4b. Swapped extras: ACB (same prefix A, extras=[C, B])" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "InventoryService.kt", "text": $C_ESC},
        {"filename": "OrderService.kt", "text": $B_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "TEST 5: Q2 — PREFIX CHANGE, SUFFIX SAME (XBC instead of ABC)"
echo ""
echo "The cache has ACB from test 4b. Re-prime with ABC, then send XBC."
echo "X replaces A at the start. B and C are unchanged."
echo "If islands are cached: prompt_n ≈ tokens(X) only."
echo "If strictly prefix: prompt_n ≈ tokens(X + B + C) (full re-eval)."
echo "========================================================================"
echo ""

# Re-prime with ABC
infill "5a. Re-prime cache: ABC" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

infill "5b. Changed prefix: XBC (X replaces A, extras=[B, C] unchanged)" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $X_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "TEST 6: Q2 — MIDDLE CHANGE (AXC instead of ABC)"
echo ""
echo "Re-prime with ABC, then send AXC. Only the middle chunk changes."
echo "The prefix (A via extra) is identical. The suffix (C via extra) is same."
echo "If islands cached: prompt_n ≈ tokens(X) + FIM tokens."
echo "If strictly prefix: prompt_n ≈ tokens(X + C) + prefix/FIM overhead."
echo "========================================================================"
echo ""

# Re-prime with ABC
infill "6a. Re-prime cache: ABC" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

infill "6b. Middle swap: AXC (extras=[X, C], prefix=A)" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "NotificationService.kt", "text": $X_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "TEST 7: APPENDING — ABC then ABCD (add new extra chunk)"
echo ""
echo "Start with ABC, then add a new chunk D at the end of extras."
echo "Since extras come BEFORE the prefix in token assembly, adding a new"
echo "extra chunk shifts the prefix tokens. This tests whether appending"
echo "to input_extra preserves the cache for the existing extras."
echo "========================================================================"
echo ""

# Re-prime with ABC
infill "7a. Re-prime cache: ABC" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

infill "7b. Appended: ABCD (extras=[B, C, X], prefix=A)" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $A_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC},
        {"filename": "InventoryService.kt", "text": $C_ESC},
        {"filename": "NotificationService.kt", "text": $X_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "TEST 8: TYPING SIMULATION — prefix grows by one line"
echo ""
echo "Simulates the user typing: same extras, prefix grows by one line."
echo "This is the most common real-world pattern. The prefix is an append-"
echo "only growth, so the common prefix should be maximal."
echo "========================================================================"
echo ""

PREFIX_SHORT='class UserService {
    private val users = mutableMapOf<Int, User>()

    fun getUser(id: Int): User? {
        return users[id]
    }

    fun createUser(name: String, email: String): User {
        val id = users.size + 1'

PREFIX_LONG='class UserService {
    private val users = mutableMapOf<Int, User>()

    fun getUser(id: Int): User? {
        return users[id]
    }

    fun createUser(name: String, email: String): User {
        val id = users.size + 1
        val user = User(id, name, email)'

PS_ESC=$(escape_json "$PREFIX_SHORT")
PL_ESC=$(escape_json "$PREFIX_LONG")

infill "8a. Typing baseline: short prefix + extras" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $PS_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

infill "8b. Typing +1 line: longer prefix, same extras" "$(cat <<ENDJSON
{
    "id_slot": 0,
    "input_prefix": $PL_ESC,
    "input_suffix": "",
    "input_extra": [
        {"filename": "OrderService.kt", "text": $B_ESC}
    ],
    "prompt": "",
    "n_predict": 1,
    "temperature": 0.0,
    "samplers": ["top_k", "top_p", "infill"],
    "stream": false,
    "cache_prompt": true,
    "response_fields": ["content", "timings/prompt_n", "tokens_cached", "truncated"]
}
ENDJSON
)"

echo "========================================================================"
echo "SUMMARY"
echo "========================================================================"
echo ""
echo "Expected results based on llama.cpp KV cache architecture:"
echo ""
echo "  Test 2 (identical repeat): prompt_n ≈ 1 (full cache hit)"
echo "  Test 3 (BAC vs ABC):       prompt_n ≈ total (order matters, prefix breaks)"
echo "  Test 4 (ACB vs ABC):       prompt_n ≈ tokens(C+B+prefix) (extras reordered, only repo header cached)"
echo "  Test 5 (XBC vs ABC):       prompt_n ≈ total (first extra changed, prefix breaks at start)"
echo "  Test 6 (AXC vs ABC):       prompt_n ≈ tokens(X+C+prefix) (middle extra changed, B's cache lost)"
echo "  Test 7 (ABCD vs ABC):      prompt_n ≈ tokens(X+prefix) (B,C cached as prefix, X+A appended)"
echo "  Test 8 (typing +1 line):   prompt_n ≈ tokens(new line) (prefix grows, near-perfect cache)"
echo ""
echo "KEY INSIGHT: KV cache is strictly prefix-based by default."
echo "  - Reordering components = cache miss"
echo "  - Changing early components = everything after is re-evaluated"
echo "  - Appending to the end = near-perfect cache hit"
echo "  - Most stable content should come FIRST in the token sequence"
