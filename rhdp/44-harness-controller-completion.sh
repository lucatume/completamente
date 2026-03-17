#!/usr/bin/env bash
# Harness: Test FIM completions for Controller.php at WP_CLI:: cursor position
#
# Sends 100 /infill requests to the local llama.cpp server and collects results.
# The cursor is positioned after "WP_CLI::" on line 25 of Controller.php.
# Each raw completion is saved to a temp file to avoid bash array issues with
# multiline content containing leading/trailing newlines or whitespace.
#
# The output report includes a whitespace analysis section that hex-dumps the
# first and last bytes of each completion, making leading/trailing newlines
# and spaces explicitly visible.
#
# Usage: bash rhdp/44-harness-controller-completion.sh
# Output: rhdp/45-output-controller-completion.txt
#
# Expects: llama.cpp server running on http://127.0.0.1:8012

set -euo pipefail

SERVER_URL="${LLAMA_SERVER_URL:-http://127.0.0.1:8012}"
RUNS=30
OUTPUT_FILE="rhdp/45-output-controller-completion.txt"

# Temp directory for individual completion files
TMPDIR_COMP=$(mktemp -d)
trap 'rm -rf "$TMPDIR_COMP" "${TMPDIR_UNIQ:-}"' EXIT

# Check server health
if ! curl -sf "${SERVER_URL}/health" > /dev/null 2>&1; then
    echo "ERROR: llama.cpp server not reachable at ${SERVER_URL}"
    exit 1
fi

echo "Server OK at ${SERVER_URL}"
echo "Running ${RUNS} completion requests..."
echo ""

# The file content split at the cursor position (after "WP_CLI::")
INPUT_PREFIX='<?php
/**
 * Main controller for WP-CLI commands.
 *
 * @package Soloadventure\Commands;
 */

namespace Soloadventure\Commands;

use lucatume\DI52\ServiceProvider;
use Soloadventure\Contracts\Controller_Interface;

/**
 * Class Controller.
 *
 * @package Soloadventure\Commands;
 */
class Controller extends ServiceProvider implements Controller_Interface {
    public function unregister(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }

        WP_CLI::'

INPUT_SUFFIX='
    }

    public function register(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }
}'

# Build the JSON request body
REQUEST_BODY=$(jq -n \
    --arg prefix "$INPUT_PREFIX" \
    --arg suffix "$INPUT_SUFFIX" \
    '{
        "id_slot": 0,
        "input_prefix": $prefix,
        "input_suffix": $suffix,
        "input_extra": [],
        "prompt": "",
        "n_predict": 128,
        "n_indent": 0,
        "top_k": 40,
        "top_p": 0.90,
        "samplers": ["top_k", "top_p", "infill"],
        "stream": false,
        "cache_prompt": true,
        "t_max_prompt_ms": 5000,
        "t_max_predict_ms": 5000,
        "response_fields": ["content", "timings/prompt_n", "timings/predicted_n", "timings/predicted_ms", "truncated", "tokens_cached"],
        "temperature": 0.0,
        "stop": []
    }')

# Collect completions into individual files
success=0
fail=0

for i in $(seq 1 $RUNS); do
    response=$(curl -sf -X POST "${SERVER_URL}/infill" \
        -H "Content-Type: application/json" \
        -d "$REQUEST_BODY" \
        --max-time 30 2>/dev/null) || { ((fail++)); echo "  [$i] FAILED"; continue; }

    # Use jq -j (no trailing newline) to get the exact content bytes
    content=$(printf '%s' "$response" | jq -j '.content // empty')
    if [ -n "$content" ]; then
        # Write raw content to a numbered file — preserves all whitespace exactly
        printf '%s' "$content" > "${TMPDIR_COMP}/$(printf '%04d' "$i").txt"
        ((success++))
        if (( i % 10 == 0 )); then
            echo "  [$i/$RUNS] $success successes, $fail failures"
        fi
    else
        ((fail++))
        echo "  [$i] empty response"
    fi
done

echo ""
echo "Done: $success successes, $fail failures out of $RUNS requests"
echo ""

# --- Helper: describe whitespace at boundaries of a string stored in a file ---
# Prints a human-readable description of leading/trailing bytes.
describe_boundaries() {
    local file="$1"
    local size
    size=$(wc -c < "$file" | tr -d ' ')

    if [ "$size" -eq 0 ]; then
        echo "    (empty)"
        return
    fi

    # Leading bytes (up to 20)
    local head_len=$(( size < 20 ? size : 20 ))
    local lead_hex
    lead_hex=$(head -c "$head_len" "$file" | xxd -p | sed 's/../& /g')
    local lead_ascii
    lead_ascii=$(head -c "$head_len" "$file" | cat -v | tr -d '\n')

    # Trailing bytes (up to 20)
    local tail_len=$(( size < 20 ? size : 20 ))
    local trail_hex
    trail_hex=$(tail -c "$tail_len" "$file" | xxd -p | sed 's/../& /g')
    local trail_ascii
    trail_ascii=$(tail -c "$tail_len" "$file" | cat -v | tr -d '\n')

    # Count specific whitespace characters
    local leading_newlines=0
    local trailing_newlines=0
    local trailing_spaces=0

    # Count leading newlines
    local raw
    raw=$(cat "$file")
    local stripped="${raw#"${raw%%[!$'\n']*}"}"
    leading_newlines=$(( ${#raw} - ${#stripped} ))

    # Count trailing newlines
    # Use xxd to check the last bytes
    local last_bytes
    last_bytes=$(tail -c 10 "$file" | xxd -p)
    trailing_newlines=0
    trailing_spaces=0
    # Walk backwards through the hex
    local hex_clean="${last_bytes}"
    while true; do
        local last2="${hex_clean: -2}"
        if [ "$last2" = "0a" ]; then
            ((trailing_newlines++))
            hex_clean="${hex_clean:0:${#hex_clean}-2}"
        elif [ "$last2" = "20" ]; then
            ((trailing_spaces++))
            hex_clean="${hex_clean:0:${#hex_clean}-2}"
        else
            break
        fi
    done

    echo "    size: ${size} bytes"
    echo "    leading ${head_len} bytes (hex): ${lead_hex}"
    echo "    leading ${head_len} bytes (vis): ${lead_ascii}"
    echo "    trailing ${tail_len} bytes (hex): ${trail_hex}"
    echo "    trailing ${tail_len} bytes (vis): ${trail_ascii}"
    echo "    leading newlines: ${leading_newlines}"
    echo "    trailing newlines: ${trailing_newlines}"
    echo "    trailing spaces: ${trailing_spaces}"
}

# --- Build unique completions using file-based deduplication ---
# Each unique hash gets a directory: TMPDIR_UNIQ/<hash>/
#   - sample.txt  = first file with this content
#   - count.txt   = number of times seen
TMPDIR_UNIQ=$(mktemp -d)

for f in "${TMPDIR_COMP}"/*.txt; do
    [ -f "$f" ] || continue
    h=$(md5 -q "$f" 2>/dev/null || md5sum "$f" | cut -d' ' -f1)
    if [ -d "${TMPDIR_UNIQ}/${h}" ]; then
        # Increment count
        prev=$(cat "${TMPDIR_UNIQ}/${h}/count.txt")
        echo $(( prev + 1 )) > "${TMPDIR_UNIQ}/${h}/count.txt"
    else
        mkdir -p "${TMPDIR_UNIQ}/${h}"
        cp "$f" "${TMPDIR_UNIQ}/${h}/sample.txt"
        echo 1 > "${TMPDIR_UNIQ}/${h}/count.txt"
    fi
done

unique_count=$(ls -d "${TMPDIR_UNIQ}"/*/ 2>/dev/null | wc -l | tr -d ' ')

# --- Write output report ---
{
    echo "# Controller.php FIM Completion Results"
    echo ""
    echo "Cursor position: after 'WP_CLI::' on line 25"
    echo "Server: ${SERVER_URL}"
    echo "Runs: ${RUNS}"
    echo "Successes: ${success}"
    echo "Failures: ${fail}"
    echo "Temperature: 0.0"
    echo ""
    echo "## Whitespace Analysis"
    echo ""
    echo "Each unique completion is shown with its raw boundary bytes to make"
    echo "leading/trailing newlines and spaces explicitly visible."
    echo ""

    echo "### Unique completions: ${unique_count}"
    echo ""

    idx=0
    for hdir in "${TMPDIR_UNIQ}"/*/; do
        [ -d "$hdir" ] || continue
        idx=$(( idx + 1 ))
        count=$(cat "${hdir}/count.txt")
        sample="${hdir}/sample.txt"
        echo "---"
        echo "### Completion #${idx} (seen ${count} times)"
        echo ""
        echo "**Boundary analysis:**"
        echo '```'
        describe_boundaries "$sample"
        echo '```'
        echo ""
        echo "**Raw content (between markers):**"
        echo '```php'
        printf 'WP_CLI::'
        cat "$sample"
        printf '\n'
        echo '```'
        echo ""
    done

    echo "---"
    echo ""
    echo "## Raw Completions (all ${success})"
    echo ""
    idx=0
    for f in "${TMPDIR_COMP}"/*.txt; do
        [ -f "$f" ] || continue
        idx=$(( idx + 1 ))
        echo "### Run ${idx}"
        echo '```'
        cat "$f"
        printf '\n'
        echo '```'
        echo ""
    done

} > "$OUTPUT_FILE"

echo "Results written to ${OUTPUT_FILE}"
echo ""

# Quick summary to stdout
echo "=== UNIQUE COMPLETIONS ==="
for hdir in "${TMPDIR_UNIQ}"/*/; do
    [ -d "$hdir" ] || continue
    count=$(cat "${hdir}/count.txt")
    sample="${hdir}/sample.txt"
    echo ""
    echo "[${count}x]"
    echo "  boundaries:"
    describe_boundaries "$sample"
    echo "  content: WP_CLI::$(cat "$sample")"
done

rm -rf "$TMPDIR_UNIQ"
