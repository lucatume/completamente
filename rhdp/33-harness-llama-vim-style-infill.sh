#!/usr/bin/env bash
# ============================================================================
# Harness: Test /infill endpoint using llama.vim request patterns
# ============================================================================
#
# Mirrors the 5 test cases from 28-harness-infill-endpoint-test.sh but sends
# requests exactly the way llama.vim does (per 32-research-llama-vim-infill-usage.md):
#
#   - `prompt` field carries the "middle" (current line up to cursor)
#   - `input_prefix` carries only the lines ABOVE the current line
#   - `input_suffix` carries rest-of-line + lines below
#   - `id_slot: 0` to pin KV cache
#   - `samplers: ["top_k", "top_p", "infill"]`
#   - `top_k: 40, top_p: 0.90`
#   - `t_max_prompt_ms` and `t_max_predict_ms` time-boxing
#   - `response_fields` to filter response payload
#   - `n_indent` indentation hint
#   - Cache warming via n_predict=0, samplers=[], response_fields=[""]
#
# Tests:
#   1. Basic infill — llama.vim style (prefix/prompt/suffix split)
#   2. Infill with input_extra — cache warm first, then infill
#   3. Response field filtering — llama.vim's exact field list
#   4. Cache warming — llama.vim's ring_update pattern
#   5. Speculative pre-fetch — first request (250ms cap) then speculative
#
# Comparison:
#   Each test is run TWICE — once the "simple" way (harness 28 style) and once
#   the llama.vim way — so results can be compared side-by-side.
#
# Prerequisites:
#   - llama.cpp server running on localhost:8012 (or set LLAMA_URL)
#   - curl and jq installed
#
# Usage:
#   bash rhdp/33-harness-llama-vim-style-infill.sh
#
# Output:
#   rhdp/34-output-llama-vim-style-infill.txt
# ============================================================================

set -euo pipefail

LLAMA_URL="${LLAMA_URL:-http://localhost:8012}"
OUTPUT_FILE="$(dirname "$0")/34-output-llama-vim-style-infill.txt"

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[0;33m'
BOLD='\033[1m'
NC='\033[0m'

header() {
    echo -e "\n${BOLD}${CYAN}=== $1 ===${NC}"
    echo -e "\n=== $1 ===" >> "$OUTPUT_FILE"
}

subheader() {
    echo -e "\n${YELLOW}--- $1 ---${NC}"
    echo -e "\n--- $1 ---" >> "$OUTPUT_FILE"
}

send_request() {
    local endpoint="$1"
    local request_json="$2"
    local label="$3"

    echo "$label request:"
    echo "$request_json" | jq .
    echo "$label request:" >> "$OUTPUT_FILE"
    echo "$request_json" | jq . >> "$OUTPUT_FILE"

    local response
    response=$(curl -s -X POST "$LLAMA_URL/$endpoint" \
        -H "Content-Type: application/json" \
        -d "$request_json" 2>&1)

    echo -e "\n$label response:"
    if echo "$response" | jq . > /dev/null 2>&1; then
        echo "$response" | jq .
        echo -e "\nGenerated content:"
        echo "$response" | jq -r '.content // empty'
        echo "$label response:" >> "$OUTPUT_FILE"
        echo "$response" | jq . >> "$OUTPUT_FILE"
    else
        echo -e "${RED}Non-JSON response:${NC}"
        echo "$response"
        echo "$label response (non-JSON): $response" >> "$OUTPUT_FILE"
    fi

    # return response via global
    LAST_RESPONSE="$response"
}

# Clear output file
> "$OUTPUT_FILE"
echo "llama.vim-style /infill endpoint test" >> "$OUTPUT_FILE"
echo "Server: $LLAMA_URL" >> "$OUTPUT_FILE"
echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$OUTPUT_FILE"

# ---- Health check ----
echo -e "${BOLD}Checking server health...${NC}"
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$LLAMA_URL/health" 2>/dev/null || echo "000")
if [ "$HEALTH" != "200" ]; then
    echo -e "${RED}Server not reachable at $LLAMA_URL (HTTP $HEALTH)${NC}"
    echo "Start a llama.cpp server with a FIM-capable model, e.g.:"
    echo "  llama-server --host 127.0.0.1 --port 8012 -hf sweepai/sweep-next-edit-1.5B --ctx-size 8192"
    exit 1
fi
echo -e "${GREEN}Server healthy${NC}"

# ============================================================================
# Test 1: Basic infill — simple vs llama.vim style
# ============================================================================
# Code under test:
#   def fibonacci(n):
#       if n <= 1:
#           return n
#       |                    <-- cursor here
#
#   def main():
#       print(fibonacci(10))
#
# Simple style:  everything in input_prefix, rest in input_suffix
# llama.vim:     lines above → input_prefix
#                current line up to cursor → prompt (the "middle")
#                rest of line + lines below → input_suffix
# ============================================================================
header "Test 1: Basic infill — simple vs llama.vim split"

subheader "1a. Simple style (harness 28)"
REQUEST_1A=$(cat <<'REQEOF'
{
  "input_prefix": "def fibonacci(n):\n    if n <= 1:\n        return n\n    ",
  "input_suffix": "\n\ndef main():\n    print(fibonacci(10))\n",
  "n_predict": 128,
  "temperature": 0.0,
  "stream": false,
  "cache_prompt": true
}
REQEOF
)
send_request "infill" "$REQUEST_1A" "1a. Simple"

subheader "1b. llama.vim style (prefix/prompt/suffix split)"
# llama.vim splits:
#   input_prefix = lines above current line (joined with \n, trailing \n)
#   prompt       = current line text up to cursor ("    " — 4 spaces of indent)
#   input_suffix = rest of current line (\n) + lines below
REQUEST_1B=$(cat <<'REQEOF'
{
  "id_slot":          0,
  "input_prefix":     "def fibonacci(n):\n    if n <= 1:\n        return n\n",
  "input_suffix":     "\n\ndef main():\n    print(fibonacci(10))\n",
  "prompt":           "    ",
  "n_predict":        128,
  "n_indent":         4,
  "top_k":            40,
  "top_p":            0.90,
  "samplers":         ["top_k", "top_p", "infill"],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  500,
  "t_max_predict_ms": 250,
  "response_fields": [
    "content",
    "timings/prompt_n",
    "timings/prompt_ms",
    "timings/prompt_per_token_ms",
    "timings/prompt_per_second",
    "timings/predicted_n",
    "timings/predicted_ms",
    "timings/predicted_per_token_ms",
    "timings/predicted_per_second",
    "truncated",
    "tokens_cached"
  ]
}
REQEOF
)
send_request "infill" "$REQUEST_1B" "1b. llama.vim"

# ============================================================================
# Test 2: Infill with input_extra — cache warm first, then infill
# ============================================================================
# llama.vim's ring_update sends a cache-warming request with the extra context
# BEFORE the actual FIM request. This pre-populates the KV cache with the
# input_extra tokens so the real request gets a cache hit.
#
# Code under test:
#   from utils import calculate_area
#
#   def draw_circle(radius):
#       area = |                    <-- cursor here
#       print(f'Circle area: {area}')
#
# Extra context: utils.py with calculate_area
# ============================================================================
header "Test 2: Infill with input_extra — two-phase (warm + infill)"

subheader "2a. Simple style (harness 28)"
REQUEST_2A=$(cat <<'REQEOF'
{
  "input_prefix": "from utils import calculate_area\n\ndef draw_circle(radius):\n    area = ",
  "input_suffix": "\n    print(f'Circle area: {area}')\n",
  "input_extra": [
    {
      "filename": "utils.py",
      "text": "import math\n\ndef calculate_area(radius):\n    return math.pi * radius ** 2\n\ndef calculate_perimeter(radius):\n    return 2 * math.pi * radius\n"
    }
  ],
  "n_predict": 64,
  "temperature": 0.0,
  "stream": false,
  "cache_prompt": true
}
REQEOF
)
send_request "infill" "$REQUEST_2A" "2a. Simple"

subheader "2b. llama.vim cache-warming request (ring_update)"
# This is what ring_update sends — n_predict=0, no samplers, response_fields=[""]
# It sends the extra context to pre-fill the KV cache
REQUEST_2B_WARM=$(cat <<'REQEOF'
{
  "id_slot":          0,
  "input_prefix":     "",
  "input_suffix":     "",
  "input_extra": [
    {
      "filename": "utils.py",
      "text": "import math\n\ndef calculate_area(radius):\n    return math.pi * radius ** 2\n\ndef calculate_perimeter(radius):\n    return 2 * math.pi * radius\n"
    }
  ],
  "prompt":           "",
  "n_predict":        0,
  "temperature":      0.0,
  "samplers":         [],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  1,
  "t_max_predict_ms": 1,
  "response_fields":  [""]
}
REQEOF
)
send_request "infill" "$REQUEST_2B_WARM" "2b-warm. Cache warming"

subheader "2c. llama.vim FIM request (after cache warm)"
# Now the actual FIM request — the input_extra tokens should be cached
REQUEST_2C=$(cat <<'REQEOF'
{
  "id_slot":          0,
  "input_prefix":     "from utils import calculate_area\n\ndef draw_circle(radius):\n",
  "input_suffix":     "\n    print(f'Circle area: {area}')\n",
  "input_extra": [
    {
      "filename": "utils.py",
      "text": "import math\n\ndef calculate_area(radius):\n    return math.pi * radius ** 2\n\ndef calculate_perimeter(radius):\n    return 2 * math.pi * radius\n"
    }
  ],
  "prompt":           "    area = ",
  "n_predict":        64,
  "n_indent":         4,
  "top_k":            40,
  "top_p":            0.90,
  "samplers":         ["top_k", "top_p", "infill"],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  500,
  "t_max_predict_ms": 250,
  "response_fields": [
    "content",
    "timings/prompt_n",
    "timings/prompt_ms",
    "timings/prompt_per_second",
    "timings/predicted_n",
    "timings/predicted_ms",
    "timings/predicted_per_second",
    "truncated",
    "tokens_cached"
  ]
}
REQEOF
)
send_request "infill" "$REQUEST_2C" "2c. llama.vim (post-warm)"

# ============================================================================
# Test 3: Response field filtering — llama.vim's exact field list
# ============================================================================
# llama.vim always requests the same set of response_fields for FIM.
# Compare full response vs filtered response.
# ============================================================================
header "Test 3: Response field filtering — full vs llama.vim fields"

subheader "3a. Simple style — no field filtering"
REQUEST_3A=$(cat <<'REQEOF'
{
  "input_prefix": "fun greet(name: String): String {\n    return ",
  "input_suffix": "\n}\n",
  "n_predict": 32,
  "temperature": 0.0,
  "stream": false,
  "cache_prompt": true
}
REQEOF
)
send_request "infill" "$REQUEST_3A" "3a. Full response"

subheader "3b. llama.vim style — filtered fields"
REQUEST_3B=$(cat <<'REQEOF'
{
  "id_slot":          0,
  "input_prefix":     "fun greet(name: String): String {\n",
  "input_suffix":     "\n}\n",
  "prompt":           "    return ",
  "n_predict":        32,
  "n_indent":         4,
  "top_k":            40,
  "top_p":            0.90,
  "samplers":         ["top_k", "top_p", "infill"],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  500,
  "t_max_predict_ms": 250,
  "response_fields": [
    "content",
    "timings/prompt_n",
    "timings/prompt_ms",
    "timings/prompt_per_token_ms",
    "timings/prompt_per_second",
    "timings/predicted_n",
    "timings/predicted_ms",
    "timings/predicted_per_token_ms",
    "timings/predicted_per_second",
    "truncated",
    "tokens_cached"
  ]
}
REQEOF
)
send_request "infill" "$REQUEST_3B" "3b. Filtered"

# ============================================================================
# Test 4: Cache warming — llama.vim's ring_update pattern
# ============================================================================
# llama.vim sends cache-warming requests every ring_update_ms (1000ms) with:
#   - All current ring chunks as input_extra
#   - n_predict=0, samplers=[], response_fields=[""]
#   - t_max_prompt_ms=1, t_max_predict_ms=1
#
# Then the subsequent FIM request benefits from cached input_extra tokens.
#
# This test measures the cache benefit by:
#   4a. Warming with two extra context files
#   4b. Sending a FIM request that includes the same extra context
#   4c. Checking if tokens_cached is higher in 4b than without warming
# ============================================================================
header "Test 4: Cache warming — ring_update pattern"

subheader "4a. Cache warming request (llama.vim ring_update)"
REQUEST_4A=$(cat <<'REQEOF'
{
  "id_slot":          0,
  "input_prefix":     "",
  "input_suffix":     "",
  "input_extra": [
    {
      "filename": "config.py",
      "text": "DATABASE_URL = 'sqlite:///app.db'\nDEBUG = True\nSECRET_KEY = 'dev-key'\n"
    },
    {
      "filename": "models.py",
      "text": "from dataclasses import dataclass\n\n@dataclass\nclass User:\n    id: int\n    name: str\n    email: str\n"
    }
  ],
  "prompt":           "",
  "n_predict":        0,
  "temperature":      0.0,
  "samplers":         [],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  1,
  "t_max_predict_ms": 1,
  "response_fields":  [""]
}
REQEOF
)
send_request "infill" "$REQUEST_4A" "4a. Cache warm"

subheader "4b. FIM request after warming (same input_extra)"
REQUEST_4B=$(cat <<'REQEOF'
{
  "id_slot":          0,
  "input_prefix":     "from models import User\nfrom config import DATABASE_URL\n\ndef get_user(user_id: int) -> User:\n",
  "input_suffix":     "\n\ndef main():\n    user = get_user(1)\n    print(user)\n",
  "input_extra": [
    {
      "filename": "config.py",
      "text": "DATABASE_URL = 'sqlite:///app.db'\nDEBUG = True\nSECRET_KEY = 'dev-key'\n"
    },
    {
      "filename": "models.py",
      "text": "from dataclasses import dataclass\n\n@dataclass\nclass User:\n    id: int\n    name: str\n    email: str\n"
    }
  ],
  "prompt":           "    ",
  "n_predict":        64,
  "n_indent":         4,
  "top_k":            40,
  "top_p":            0.90,
  "samplers":         ["top_k", "top_p", "infill"],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  500,
  "t_max_predict_ms": 250,
  "response_fields": [
    "content",
    "timings/prompt_n",
    "timings/prompt_ms",
    "timings/prompt_per_second",
    "timings/predicted_n",
    "timings/predicted_ms",
    "timings/predicted_per_second",
    "truncated",
    "tokens_cached"
  ]
}
REQEOF
)
send_request "infill" "$REQUEST_4B" "4b. FIM post-warm"

subheader "4c. Same FIM without prior warming (no cache benefit)"
# Same request but on a different slot to ensure no cache hit
REQUEST_4C=$(cat <<'REQEOF'
{
  "id_slot":          1,
  "input_prefix":     "from models import User\nfrom config import DATABASE_URL\n\ndef get_user(user_id: int) -> User:\n",
  "input_suffix":     "\n\ndef main():\n    user = get_user(1)\n    print(user)\n",
  "input_extra": [
    {
      "filename": "config.py",
      "text": "DATABASE_URL = 'sqlite:///app.db'\nDEBUG = True\nSECRET_KEY = 'dev-key'\n"
    },
    {
      "filename": "models.py",
      "text": "from dataclasses import dataclass\n\n@dataclass\nclass User:\n    id: int\n    name: str\n    email: str\n"
    }
  ],
  "prompt":           "    ",
  "n_predict":        64,
  "n_indent":         4,
  "top_k":            40,
  "top_p":            0.90,
  "samplers":         ["top_k", "top_p", "infill"],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  500,
  "t_max_predict_ms": 250,
  "response_fields": [
    "content",
    "timings/prompt_n",
    "timings/prompt_ms",
    "timings/prompt_per_second",
    "timings/predicted_n",
    "timings/predicted_ms",
    "timings/predicted_per_second",
    "truncated",
    "tokens_cached"
  ]
}
REQEOF
)
send_request "infill" "$REQUEST_4C" "4c. FIM no-warm (slot 1)"

# ============================================================================
# Test 5: Speculative pre-fetch — two-tier timing
# ============================================================================
# llama.vim's speculative pattern:
#   5a. First request: t_max_predict_ms=250 (fast, user is waiting)
#   5b. Speculative follow-up: t_max_predict_ms=1000
#       Context is assembled AS IF the first suggestion was already accepted.
#       (prefix = original prefix + original prompt + first suggestion)
#
# Code:
#   class Calculator:
#       def add(self, a, b):
#           |                    <-- cursor here
#       def subtract(self, a, b):
#           return a - b
# ============================================================================
header "Test 5: Speculative pre-fetch — two-tier timing"

subheader "5a. First request (250ms cap, user waiting)"
REQUEST_5A=$(cat <<'REQEOF'
{
  "id_slot":          0,
  "input_prefix":     "class Calculator:\n    def add(self, a, b):\n",
  "input_suffix":     "\n    def subtract(self, a, b):\n        return a - b\n",
  "prompt":           "        ",
  "n_predict":        32,
  "n_indent":         8,
  "top_k":            40,
  "top_p":            0.90,
  "samplers":         ["top_k", "top_p", "infill"],
  "stream":           false,
  "cache_prompt":     true,
  "t_max_prompt_ms":  500,
  "t_max_predict_ms": 250,
  "response_fields": [
    "content",
    "timings/prompt_n",
    "timings/prompt_ms",
    "timings/prompt_per_second",
    "timings/predicted_n",
    "timings/predicted_ms",
    "timings/predicted_per_second",
    "truncated",
    "tokens_cached"
  ]
}
REQEOF
)
send_request "infill" "$REQUEST_5A" "5a. First (250ms)"

# Extract the content from 5a to build the speculative request
CONTENT_5A=""
if echo "$LAST_RESPONSE" | jq . > /dev/null 2>&1; then
    CONTENT_5A=$(echo "$LAST_RESPONSE" | jq -r '.content // empty')
fi

if [ -n "$CONTENT_5A" ]; then
    subheader "5b. Speculative follow-up (1000ms, as if 5a accepted)"
    echo "Content from 5a: '$CONTENT_5A'"
    echo "Content from 5a: '$CONTENT_5A'" >> "$OUTPUT_FILE"

    # Build speculative context as if the suggestion was already accepted.
    # llama.vim's fim_ctx_local with prev=[content_5a]:
    #   - If single-line: prefix stays, prompt = original_line + content_5a, suffix = ""
    #   - The prefix absorbs the original prompt + accepted content
    #
    # Original:  prefix="class Calculator:\n    def add(self, a, b):\n"
    #            prompt="        "
    # After accept: "        return a + b" was inserted
    # New context:  prefix includes "        return a + b\n"
    #               prompt is the new cursor position (end of accepted text)
    #               suffix remains the same

    # Build the speculative prefix: original prefix + original prompt + accepted content
    # For simplicity, construct it directly
    SPEC_PREFIX="class Calculator:\n    def add(self, a, b):\n        $(echo "$CONTENT_5A" | head -1)"

    # If multi-line, the last line becomes the new prompt
    CONTENT_LINES=$(echo "$CONTENT_5A" | wc -l)
    if [ "$CONTENT_LINES" -gt 1 ]; then
        SPEC_PROMPT=$(echo "$CONTENT_5A" | tail -1)
    else
        SPEC_PROMPT=""
    fi

    # Use jq to safely construct the JSON with the dynamic content
    REQUEST_5B=$(jq -n \
        --arg prefix "$SPEC_PREFIX" \
        --arg suffix "\n    def subtract(self, a, b):\n        return a - b\n" \
        --arg prompt "$SPEC_PROMPT" \
        '{
            "id_slot":          0,
            "input_prefix":     $prefix,
            "input_suffix":     $suffix,
            "prompt":           $prompt,
            "n_predict":        32,
            "n_indent":         8,
            "top_k":            40,
            "top_p":            0.90,
            "samplers":         ["top_k", "top_p", "infill"],
            "stream":           false,
            "cache_prompt":     true,
            "t_max_prompt_ms":  500,
            "t_max_predict_ms": 1000,
            "response_fields": [
                "content",
                "timings/prompt_n",
                "timings/prompt_ms",
                "timings/prompt_per_second",
                "timings/predicted_n",
                "timings/predicted_ms",
                "timings/predicted_per_second",
                "truncated",
                "tokens_cached"
            ]
        }')

    send_request "infill" "$REQUEST_5B" "5b. Speculative (1000ms)"
else
    echo -e "${RED}Skipping 5b — no content from 5a${NC}"
    echo "Skipping 5b — no content from 5a" >> "$OUTPUT_FILE"
fi

# ============================================================================
# Summary
# ============================================================================
header "Summary"

cat <<'SUMMARY'
Test results:
  1. Basic infill — simple (all in input_prefix) vs llama.vim (prefix/prompt/suffix split)
     Key question: Does the prompt field affect completion quality?

  2. Input_extra with cache warming — two-phase approach
     Key question: Does pre-warming the cache with ring_update improve prompt_ms?

  3. Response field filtering — full response vs llama.vim's filtered fields
     Key question: Which fields does llama.vim need and does filtering reduce payload?

  4. Cache warming ring_update pattern — warm vs cold
     Key question: Is tokens_cached higher after warming? Is prompt_ms lower?

  5. Speculative pre-fetch — two-tier timing (250ms then 1000ms)
     Key question: Does the speculative request get a cache hit (high tokens_cached)?
     Does the content form a coherent continuation?

llama.vim patterns applied:
  - id_slot: 0 (pinned KV cache)
  - samplers: ["top_k", "top_p", "infill"]
  - top_k: 40, top_p: 0.90
  - t_max_prompt_ms: 500, t_max_predict_ms: 250 (first) / 1000 (speculative)
  - response_fields: content + timings + truncated + tokens_cached
  - n_indent: indentation hint
  - prompt: current line up to cursor (the "middle")
  - Cache warming: n_predict=0, samplers=[], response_fields=[""]
SUMMARY

cat <<'SUMMARY' >> "$OUTPUT_FILE"
Test results:
  1. Basic infill — simple vs llama.vim prefix/prompt/suffix split
  2. Input_extra with cache warming — two-phase approach
  3. Response field filtering — full response vs filtered
  4. Cache warming ring_update pattern — warm vs cold
  5. Speculative pre-fetch — two-tier timing
SUMMARY

echo -e "\n${GREEN}Full output written to: $OUTPUT_FILE${NC}"
