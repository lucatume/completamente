#!/usr/bin/env bash
# Harness: Test FIM completions at cursor positions likely to produce whitespace issues
#
# Tests 5 cursor positions in Controller.php where leading/trailing newlines
# or spaces may appear in completions:
#
#   A. Cursor at start of an empty line (between methods)
#   B. Cursor at end of a line, before a blank line
#   C. Cursor at start of an indented line (before existing code)
#   D. Cursor on a blank line inside a method body
#   E. Cursor after a newline at the very end of a block
#
# Each position gets 10 requests at temperature 0.0 (deterministic, but the
# model may still vary). The output report shows hex boundary analysis for
# every completion.
#
# Usage: bash rhdp/46-harness-whitespace-cursor-positions.sh
# Output: rhdp/47-output-whitespace-cursor-positions.txt
#
# Expects: llama.cpp server running on http://127.0.0.1:8012

set -euo pipefail

SERVER_URL="${LLAMA_SERVER_URL:-http://127.0.0.1:8012}"
RUNS_PER_POSITION=10
OUTPUT_FILE="rhdp/47-output-whitespace-cursor-positions.txt"

TMPDIR_BASE=$(mktemp -d)
trap 'rm -rf "$TMPDIR_BASE"' EXIT

# Check server health
if ! curl -sf "${SERVER_URL}/health" > /dev/null 2>&1; then
    echo "ERROR: llama.cpp server not reachable at ${SERVER_URL}"
    exit 1
fi

echo "Server OK at ${SERVER_URL}"
echo ""

# --- Helper: describe whitespace at boundaries of a file ---
describe_boundaries() {
    local file="$1"
    local size
    size=$(wc -c < "$file" | tr -d ' ')

    if [ "$size" -eq 0 ]; then
        echo "    (empty)"
        return
    fi

    local head_len=$(( size < 20 ? size : 20 ))
    local lead_hex
    lead_hex=$(head -c "$head_len" "$file" | xxd -p | sed 's/../& /g')
    local lead_ascii
    lead_ascii=$(head -c "$head_len" "$file" | cat -v | tr -d '\n')

    local tail_len=$(( size < 20 ? size : 20 ))
    local trail_hex
    trail_hex=$(tail -c "$tail_len" "$file" | xxd -p | sed 's/../& /g')
    local trail_ascii
    trail_ascii=$(tail -c "$tail_len" "$file" | cat -v | tr -d '\n')

    # Count leading newlines
    local leading_newlines=0
    local all_hex
    all_hex=$(xxd -p "$file" | tr -d '\n')
    local tmp_hex="$all_hex"
    while [ "${tmp_hex:0:2}" = "0a" ]; do
        leading_newlines=$(( leading_newlines + 1 ))
        tmp_hex="${tmp_hex:2}"
    done

    # Count leading spaces
    local leading_spaces=0
    while [ "${tmp_hex:0:2}" = "20" ]; do
        leading_spaces=$(( leading_spaces + 1 ))
        tmp_hex="${tmp_hex:2}"
    done

    # Count trailing whitespace (walk backwards)
    local trailing_newlines=0
    local trailing_spaces=0
    tmp_hex="$all_hex"
    while true; do
        local last2="${tmp_hex: -2}"
        if [ "$last2" = "0a" ]; then
            trailing_newlines=$(( trailing_newlines + 1 ))
            tmp_hex="${tmp_hex:0:${#tmp_hex}-2}"
        elif [ "$last2" = "20" ]; then
            trailing_spaces=$(( trailing_spaces + 1 ))
            tmp_hex="${tmp_hex:0:${#tmp_hex}-2}"
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
    echo "    leading spaces: ${leading_spaces} (after leading newlines)"
    echo "    trailing newlines: ${trailing_newlines}"
    echo "    trailing spaces: ${trailing_spaces}"
}

# --- Helper: run one position's tests ---
# Args: position_id, position_label, prefix, suffix, cursor_desc
run_position() {
    local pos_id="$1"
    local pos_label="$2"
    local prefix="$3"
    local suffix="$4"
    local cursor_desc="$5"

    local pos_dir="${TMPDIR_BASE}/${pos_id}"
    mkdir -p "$pos_dir"

    local request_body
    request_body=$(jq -n \
        --arg prefix "$prefix" \
        --arg suffix "$suffix" \
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
            "response_fields": ["content"],
            "temperature": 0.0,
            "stop": []
        }')

    echo "  Position ${pos_id}: ${pos_label}"
    echo "    Cursor: ${cursor_desc}"

    local success=0
    local fail=0

    for i in $(seq 1 $RUNS_PER_POSITION); do
        response=$(curl -s -X POST "${SERVER_URL}/infill" \
            -H "Content-Type: application/json" \
            -d "$request_body" \
            -w '\n__HTTP_CODE__%{http_code}' \
            --max-time 30 2>/dev/null) || { fail=$(( fail + 1 )); echo "    [$i] curl error"; continue; }

        http_code=$(printf '%s' "$response" | tail -1 | sed 's/.*__HTTP_CODE__//')
        body=$(printf '%s' "$response" | sed '/__HTTP_CODE__/d')

        if [ "$http_code" != "200" ]; then
            fail=$(( fail + 1 ))
            # Log first failure's details
            if [ "$fail" -eq 1 ]; then
                echo "    [$i] HTTP ${http_code}"
                printf '%s' "$body" | head -c 200
                echo ""
            fi
            continue
        fi

        content=$(printf '%s' "$body" | jq -j '.content // empty')
        if [ -n "$content" ]; then
            printf '%s' "$content" > "${pos_dir}/$(printf '%04d' "$i").txt"
            success=$(( success + 1 ))
        else
            fail=$(( fail + 1 ))
            if [ "$fail" -eq 1 ]; then
                echo "    [$i] empty content, raw: $(printf '%s' "$body" | head -c 200)"
            fi
        fi
    done

    echo "    Results: ${success} successes, ${fail} failures"
    # Write metadata
    printf '%s' "$success" > "${pos_dir}/success_count.txt"
    printf '%s' "$fail" > "${pos_dir}/fail_count.txt"
    printf '%s' "$pos_label" > "${pos_dir}/label.txt"
    printf '%s' "$cursor_desc" > "${pos_dir}/cursor_desc.txt"
}

# =========================================================================
# Define the 5 cursor positions
# =========================================================================
# Base file content (Controller.php) — we'll split it differently for each position.

# Position A: Cursor at start of an empty line between methods
# The cursor is on the blank line between unregister() and register().
# Prefix ends with "}\n\n" (end of unregister + blank line), suffix starts with "    public function register"

POSA_PREFIX='<?php
namespace Soloadventure\Commands;

use lucatume\DI52\ServiceProvider;
use Soloadventure\Contracts\Controller_Interface;

class Controller extends ServiceProvider implements Controller_Interface {
    public function unregister(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }

'

POSA_SUFFIX='    public function register(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }
}'

# Position B: Cursor at end of a line, before a blank line
# Cursor is right after the closing "}" of unregister(), before the blank line.
# Prefix ends with "    }", suffix starts with "\n\n    public function register"

POSB_PREFIX='<?php
namespace Soloadventure\Commands;

use lucatume\DI52\ServiceProvider;
use Soloadventure\Contracts\Controller_Interface;

class Controller extends ServiceProvider implements Controller_Interface {
    public function unregister(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }'

POSB_SUFFIX='

    public function register(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }
}'

# Position C: Cursor at start of an indented line (before existing code)
# Cursor is at column 0 of the "        if ( ! defined..." line inside register().
# Prefix ends with newline, suffix starts with "        if ( ! defined..."

POSC_PREFIX='<?php
namespace Soloadventure\Commands;

use lucatume\DI52\ServiceProvider;
use Soloadventure\Contracts\Controller_Interface;

class Controller extends ServiceProvider implements Controller_Interface {
    public function unregister(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }

    public function register(): void {
'

POSC_SUFFIX='        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }
}'

# Position D: Cursor on a blank line inside a method body
# There's a blank line between "return;" and "}" inside unregister().
# Prefix ends with "return;\n}\n\n", suffix starts with "    }\n"

POSD_PREFIX='<?php
namespace Soloadventure\Commands;

use lucatume\DI52\ServiceProvider;
use Soloadventure\Contracts\Controller_Interface;

class Controller extends ServiceProvider implements Controller_Interface {
    public function unregister(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }

'

POSD_SUFFIX='    }

    public function register(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }
}'

# Position E: Cursor after a newline at end of file (after closing brace)
# Prefix is the entire file + newline, suffix is empty.

POSE_PREFIX='<?php
namespace Soloadventure\Commands;

use lucatume\DI52\ServiceProvider;
use Soloadventure\Contracts\Controller_Interface;

class Controller extends ServiceProvider implements Controller_Interface {
    public function unregister(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }

    public function register(): void {
        if ( ! defined( '"'"'WP_CLI'"'"' ) || ! WP_CLI ) {
            return;
        }
    }
}
'

POSE_SUFFIX=''

# =========================================================================
# Run all positions
# =========================================================================
echo "Running ${RUNS_PER_POSITION} completions per position (5 positions)..."
echo ""

run_position "A" "Empty line between methods" "$POSA_PREFIX" "$POSA_SUFFIX" \
    "Start of blank line between unregister() and register()"

run_position "B" "End of line before blank line" "$POSB_PREFIX" "$POSB_SUFFIX" \
    "After closing } of unregister(), before blank line"

run_position "C" "Start of indented line" "$POSC_PREFIX" "$POSC_SUFFIX" \
    "Column 0 of 'if ( ! defined...' line inside register()"

run_position "D" "Blank line inside method body" "$POSD_PREFIX" "$POSD_SUFFIX" \
    "Blank line between if-block and closing } of unregister()"

run_position "E" "After newline at end of file" "$POSE_PREFIX" "$POSE_SUFFIX" \
    "After final newline, past closing }"

echo ""
echo "All positions complete."
echo ""

# =========================================================================
# Write output report
# =========================================================================
{
    echo "# Whitespace at Cursor Position Boundaries"
    echo ""
    echo "Tests 5 cursor positions in Controller.php where completions may"
    echo "include leading/trailing newlines or spaces."
    echo ""
    echo "Server: ${SERVER_URL}"
    echo "Runs per position: ${RUNS_PER_POSITION}"
    echo "Temperature: 0.0"
    echo ""

    for pos_id in A B C D E; do
        pos_dir="${TMPDIR_BASE}/${pos_id}"
        label=$(cat "${pos_dir}/label.txt")
        cursor_desc=$(cat "${pos_dir}/cursor_desc.txt")
        success_count=$(cat "${pos_dir}/success_count.txt")
        fail_count=$(cat "${pos_dir}/fail_count.txt")

        echo "---"
        echo ""
        echo "## Position ${pos_id}: ${label}"
        echo ""
        echo "**Cursor:** ${cursor_desc}"
        echo "**Results:** ${success_count} successes, ${fail_count} failures"
        echo ""

        # Deduplicate using hash directories
        dedup_dir="${TMPDIR_BASE}/dedup_${pos_id}"
        mkdir -p "$dedup_dir"

        for f in "${pos_dir}"/*.txt; do
            [ -f "$f" ] || continue
            fname=$(basename "$f")
            # Skip metadata files
            case "$fname" in
                success_count.txt|fail_count.txt|label.txt|cursor_desc.txt) continue ;;
            esac
            h=$(md5 -q "$f" 2>/dev/null || md5sum "$f" | cut -d' ' -f1)
            if [ -d "${dedup_dir}/${h}" ]; then
                prev=$(cat "${dedup_dir}/${h}/count.txt")
                echo $(( prev + 1 )) > "${dedup_dir}/${h}/count.txt"
            else
                mkdir -p "${dedup_dir}/${h}"
                cp "$f" "${dedup_dir}/${h}/sample.txt"
                echo 1 > "${dedup_dir}/${h}/count.txt"
            fi
        done

        unique_count=0
        for _d in "${dedup_dir}"/*/; do [ -d "$_d" ] && unique_count=$(( unique_count + 1 )); done
        echo "### Unique completions: ${unique_count}"
        echo ""

        if [ "$unique_count" -eq 0 ]; then
            echo "(no completions returned)"
            echo ""
            continue
        fi

        idx=0
        for hdir in "${dedup_dir}"/*/; do
            [ -d "$hdir" ] || continue
            idx=$(( idx + 1 ))
            count=$(cat "${hdir}/count.txt")
            sample="${hdir}/sample.txt"

            echo "#### Completion ${pos_id}.${idx} (seen ${count} times)"
            echo ""
            echo '```'
            describe_boundaries "$sample"
            echo '```'
            echo ""
            echo "**Content (prefixed with cursor context):**"
            echo '```php'
            printf '|CURSOR|'
            cat "$sample"
            printf '\n'
            echo '```'
            echo ""
        done
    done

    echo "---"
    echo ""
    echo "## Summary Table"
    echo ""
    echo "| Position | Label | Leading NL | Leading SP | Trailing NL | Trailing SP |"
    echo "|----------|-------|------------|------------|-------------|-------------|"

    for pos_id in A B C D E; do
        pos_dir="${TMPDIR_BASE}/${pos_id}"
        dedup_dir="${TMPDIR_BASE}/dedup_${pos_id}"
        label=$(cat "${pos_dir}/label.txt")

        # Check if any dedup dirs exist for this position
        has_data=0
        for _d in "${dedup_dir}"/*/; do [ -d "$_d" ] && has_data=1 && break; done

        if [ "$has_data" -eq 0 ]; then
            echo "| ${pos_id} | ${label} | n/a | n/a | n/a | n/a |"
            continue
        fi

        # Collect min/max across all unique completions
        min_lnl=999; max_lnl=0
        min_lsp=999; max_lsp=0
        min_tnl=999; max_tnl=0
        min_tsp=999; max_tsp=0

        for hdir in "${dedup_dir}"/*/; do
            [ -d "$hdir" ] || continue
            sample="${hdir}/sample.txt"
            size=$(wc -c < "$sample" | tr -d ' ')
            [ "$size" -eq 0 ] && continue

            all_hex=$(xxd -p "$sample" | tr -d '\n')

            # Leading newlines
            lnl=0; tmp="$all_hex"
            while [ "${tmp:0:2}" = "0a" ]; do lnl=$(( lnl + 1 )); tmp="${tmp:2}"; done
            # Leading spaces (after newlines)
            lsp=0
            while [ "${tmp:0:2}" = "20" ]; do lsp=$(( lsp + 1 )); tmp="${tmp:2}"; done
            # Trailing
            tnl=0; tsp=0; tmp="$all_hex"
            while true; do
                last2="${tmp: -2}"
                if [ "$last2" = "0a" ]; then tnl=$(( tnl + 1 )); tmp="${tmp:0:${#tmp}-2}"
                elif [ "$last2" = "20" ]; then tsp=$(( tsp + 1 )); tmp="${tmp:0:${#tmp}-2}"
                else break; fi
            done

            [ "$lnl" -lt "$min_lnl" ] && min_lnl=$lnl
            [ "$lnl" -gt "$max_lnl" ] && max_lnl=$lnl
            [ "$lsp" -lt "$min_lsp" ] && min_lsp=$lsp
            [ "$lsp" -gt "$max_lsp" ] && max_lsp=$lsp
            [ "$tnl" -lt "$min_tnl" ] && min_tnl=$tnl
            [ "$tnl" -gt "$max_tnl" ] && max_tnl=$tnl
            [ "$tsp" -lt "$min_tsp" ] && min_tsp=$tsp
            [ "$tsp" -gt "$max_tsp" ] && max_tsp=$tsp
        done

        fmt_range() {
            if [ "$1" -eq "$2" ]; then echo "$1"
            else echo "${1}-${2}"; fi
        }

        echo "| ${pos_id} | ${label} | $(fmt_range $min_lnl $max_lnl) | $(fmt_range $min_lsp $max_lsp) | $(fmt_range $min_tnl $max_tnl) | $(fmt_range $min_tsp $max_tsp) |"
    done

    echo ""

} > "$OUTPUT_FILE"

echo "Results written to ${OUTPUT_FILE}"
echo ""

# Quick stdout summary
echo "=== SUMMARY ==="
tail -8 "$OUTPUT_FILE"
