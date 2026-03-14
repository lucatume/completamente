#!/usr/bin/env bash
# ============================================================================
# Harness: Test llama.cpp /infill endpoint request/response format
# ============================================================================
#
# Tests the /infill endpoint against a running llama.cpp server with a
# FIM-capable model. Exercises:
#
#   1. Basic infill (prefix + suffix only)
#   2. Infill with input_extra context
#   3. Response field filtering
#   4. Cache warming (n_predict=0)
#   5. Comparison with /completion using manual FIM tokens
#
# Prerequisites:
#   - llama.cpp server running on localhost:8012 (or set LLAMA_URL)
#   - Server loaded with a FIM-capable model (e.g., sweepai/sweep-next-edit-1.5B)
#   - curl and jq installed
#
# Usage:
#   chmod +x 28-harness-infill-endpoint-test.sh
#   ./28-harness-infill-endpoint-test.sh
#
# Expected output:
#   Each test prints the request, response, and key metrics to STDOUT.
#   A summary file is written to rhdp/29-output-infill-endpoint-test.txt
# ============================================================================

set -euo pipefail

LLAMA_URL="${LLAMA_URL:-http://localhost:8012}"
OUTPUT_FILE="$(dirname "$0")/29-output-infill-endpoint-test.txt"

# Colors for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

header() {
    echo -e "\n${BOLD}${CYAN}=== $1 ===${NC}"
    echo -e "\n=== $1 ===" >> "$OUTPUT_FILE"
}

# Clear output file
> "$OUTPUT_FILE"
echo "llama.cpp /infill endpoint test results" >> "$OUTPUT_FILE"
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
# Test 1: Basic infill (prefix + suffix)
# ============================================================================
header "Test 1: Basic infill (prefix + suffix)"

REQUEST_1=$(cat <<'REQEOF'
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

echo "Request:"
echo "$REQUEST_1" | jq .
echo "Request:" >> "$OUTPUT_FILE"
echo "$REQUEST_1" | jq . >> "$OUTPUT_FILE"

echo -e "\nResponse:"
RESPONSE_1=$(curl -s -X POST "$LLAMA_URL/infill" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_1" 2>&1)

if echo "$RESPONSE_1" | jq . > /dev/null 2>&1; then
    echo "$RESPONSE_1" | jq '{content, stop, stop_type, stopping_word, tokens_cached, tokens_evaluated, truncated}'
    echo -e "\nGenerated content:"
    echo "$RESPONSE_1" | jq -r '.content'

    echo "Response:" >> "$OUTPUT_FILE"
    echo "$RESPONSE_1" | jq . >> "$OUTPUT_FILE"
else
    echo -e "${RED}Non-JSON response:${NC}"
    echo "$RESPONSE_1"
    echo "Response (non-JSON): $RESPONSE_1" >> "$OUTPUT_FILE"
fi

# ============================================================================
# Test 2: Infill with input_extra (cross-file context)
# ============================================================================
header "Test 2: Infill with input_extra (cross-file context)"

REQUEST_2=$(cat <<'REQEOF'
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

echo "Request:"
echo "$REQUEST_2" | jq .
echo "Request:" >> "$OUTPUT_FILE"
echo "$REQUEST_2" | jq . >> "$OUTPUT_FILE"

echo -e "\nResponse:"
RESPONSE_2=$(curl -s -X POST "$LLAMA_URL/infill" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_2" 2>&1)

if echo "$RESPONSE_2" | jq . > /dev/null 2>&1; then
    echo "$RESPONSE_2" | jq '{content, stop, stop_type, tokens_cached, tokens_evaluated, truncated}'
    echo -e "\nGenerated content:"
    echo "$RESPONSE_2" | jq -r '.content'
    echo "Response:" >> "$OUTPUT_FILE"
    echo "$RESPONSE_2" | jq . >> "$OUTPUT_FILE"
else
    echo -e "${RED}Non-JSON response:${NC}"
    echo "$RESPONSE_2"
    echo "Response (non-JSON): $RESPONSE_2" >> "$OUTPUT_FILE"
fi

# ============================================================================
# Test 3: Response field filtering
# ============================================================================
header "Test 3: Response field filtering"

REQUEST_3=$(cat <<'REQEOF'
{
  "input_prefix": "fun greet(name: String): String {\n    return ",
  "input_suffix": "\n}\n",
  "n_predict": 32,
  "temperature": 0.0,
  "stream": false,
  "cache_prompt": true,
  "response_fields": [
    "content",
    "timings/prompt_n",
    "timings/prompt_ms",
    "timings/prompt_per_second",
    "timings/predicted_n",
    "timings/predicted_ms",
    "timings/predicted_per_second",
    "tokens_cached",
    "truncated"
  ]
}
REQEOF
)

echo "Request:"
echo "$REQUEST_3" | jq .
echo "Request:" >> "$OUTPUT_FILE"
echo "$REQUEST_3" | jq . >> "$OUTPUT_FILE"

echo -e "\nResponse (filtered fields):"
RESPONSE_3=$(curl -s -X POST "$LLAMA_URL/infill" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_3" 2>&1)

if echo "$RESPONSE_3" | jq . > /dev/null 2>&1; then
    echo "$RESPONSE_3" | jq .
    echo "Response:" >> "$OUTPUT_FILE"
    echo "$RESPONSE_3" | jq . >> "$OUTPUT_FILE"
else
    echo -e "${RED}Non-JSON response:${NC}"
    echo "$RESPONSE_3"
    echo "Response (non-JSON): $RESPONSE_3" >> "$OUTPUT_FILE"
fi

# ============================================================================
# Test 4: Cache warming (n_predict=0)
# ============================================================================
header "Test 4: Cache warming (n_predict=0)"

REQUEST_4=$(cat <<'REQEOF'
{
  "input_prefix": "",
  "input_suffix": "",
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
  "n_predict": 0,
  "temperature": 0.0,
  "samplers": [],
  "stream": false,
  "cache_prompt": true,
  "t_max_predict_ms": 1
}
REQEOF
)

echo "Request (cache warming, no generation):"
echo "$REQUEST_4" | jq .
echo "Request:" >> "$OUTPUT_FILE"
echo "$REQUEST_4" | jq . >> "$OUTPUT_FILE"

echo -e "\nResponse:"
RESPONSE_4=$(curl -s -X POST "$LLAMA_URL/infill" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_4" 2>&1)

if echo "$RESPONSE_4" | jq . > /dev/null 2>&1; then
    echo "$RESPONSE_4" | jq '{content, tokens_cached, tokens_evaluated, truncated}'
    echo "Response:" >> "$OUTPUT_FILE"
    echo "$RESPONSE_4" | jq . >> "$OUTPUT_FILE"
else
    echo -e "${RED}Non-JSON response:${NC}"
    echo "$RESPONSE_4"
    echo "Response (non-JSON): $RESPONSE_4" >> "$OUTPUT_FILE"
fi

# ============================================================================
# Test 5: Compare /infill vs /completion with manual FIM tokens
# ============================================================================
header "Test 5: /infill vs /completion comparison"

# 5a: Using /infill
REQUEST_5A=$(cat <<'REQEOF'
{
  "input_prefix": "class Calculator:\n    def add(self, a, b):\n        ",
  "input_suffix": "\n    def subtract(self, a, b):\n        return a - b\n",
  "n_predict": 32,
  "temperature": 0.0,
  "stream": false,
  "cache_prompt": true
}
REQEOF
)

echo "5a. /infill request:"
echo "$REQUEST_5A" | jq .
echo "5a. /infill request:" >> "$OUTPUT_FILE"
echo "$REQUEST_5A" | jq . >> "$OUTPUT_FILE"

RESPONSE_5A=$(curl -s -X POST "$LLAMA_URL/infill" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_5A" 2>&1)

echo -e "\n5a. /infill response:"
if echo "$RESPONSE_5A" | jq . > /dev/null 2>&1; then
    echo "$RESPONSE_5A" | jq '{content, tokens_cached, tokens_evaluated}'
    CONTENT_5A=$(echo "$RESPONSE_5A" | jq -r '.content')
    echo "Content: $CONTENT_5A"
    echo "5a. /infill response:" >> "$OUTPUT_FILE"
    echo "$RESPONSE_5A" | jq . >> "$OUTPUT_FILE"
else
    echo "$RESPONSE_5A"
    echo "5a. /infill response (non-JSON): $RESPONSE_5A" >> "$OUTPUT_FILE"
fi

# 5b: Using /completion with manual FIM tokens (SweepAI style)
# Note: These are the actual tokens the SweepAI model expects
REQUEST_5B=$(cat <<'REQEOF'
{
  "prompt": "<|file_sep|>calculator.py\nclass Calculator:\n    def add(self, a, b):\n        <|fim_suffix|>\n    def subtract(self, a, b):\n        return a - b\n<|fim_middle|>",
  "n_predict": 32,
  "temperature": 0.0,
  "stop": ["<|file_sep|>", "</s>"],
  "stream": false,
  "cache_prompt": true
}
REQEOF
)

echo -e "\n5b. /completion with manual FIM tokens:"
echo "$REQUEST_5B" | jq .
echo "5b. /completion request:" >> "$OUTPUT_FILE"
echo "$REQUEST_5B" | jq . >> "$OUTPUT_FILE"

RESPONSE_5B=$(curl -s -X POST "$LLAMA_URL/completion" \
    -H "Content-Type: application/json" \
    -d "$REQUEST_5B" 2>&1)

echo -e "\n5b. /completion response:"
if echo "$RESPONSE_5B" | jq . > /dev/null 2>&1; then
    echo "$RESPONSE_5B" | jq '{content, tokens_cached, tokens_evaluated}'
    CONTENT_5B=$(echo "$RESPONSE_5B" | jq -r '.content')
    echo "Content: $CONTENT_5B"
    echo "5b. /completion response:" >> "$OUTPUT_FILE"
    echo "$RESPONSE_5B" | jq . >> "$OUTPUT_FILE"
else
    echo "$RESPONSE_5B"
    echo "5b. /completion response (non-JSON): $RESPONSE_5B" >> "$OUTPUT_FILE"
fi

# ============================================================================
# Summary
# ============================================================================
header "Summary"

cat <<'SUMMARY'
Test results:
  1. Basic infill       - Tests prefix/suffix with no extra context
  2. Extra context      - Tests input_extra with cross-file references
  3. Field filtering    - Tests response_fields for leaner responses
  4. Cache warming      - Tests n_predict=0 for KV cache pre-loading
  5. Infill vs Completion - Compares /infill (server-managed FIM) vs
                           /completion (client-managed FIM tokens)

Key observations to check:
  - Does /infill produce valid completions?
  - Does input_extra improve completion quality?
  - Does response_fields actually filter the response?
  - Does cache warming work (tokens_cached > 0 on subsequent calls)?
  - Do /infill and /completion produce similar results?
  - What FIM tokens does the server insert? (check tokens_evaluated counts)
SUMMARY

cat <<'SUMMARY' >> "$OUTPUT_FILE"
Test results:
  1. Basic infill       - Tests prefix/suffix with no extra context
  2. Extra context      - Tests input_extra with cross-file references
  3. Field filtering    - Tests response_fields for leaner responses
  4. Cache warming      - Tests n_predict=0 for KV cache pre-loading
  5. Infill vs Completion - Compares /infill (server-managed FIM) vs
                           /completion (client-managed FIM tokens)
SUMMARY

echo -e "\n${GREEN}Full output written to: $OUTPUT_FILE${NC}"
