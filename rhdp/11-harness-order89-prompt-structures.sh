#!/usr/bin/env bash
# Harness: Test different prompt structures for Order 89
#
# Purpose: Find the prompt format that makes `claude -p` return ONLY the
# replacement code with no markdown fences, explanations, or preamble.
#
# Each test sends a known snippet + instruction to claude and captures stdout.
# We then check whether the output is "clean" (no ``` fences, no prose).
#
# Usage: ./rhdp/11-harness-order89-prompt-structures.sh
# Output: rhdp/12-output-order89-prompt-structures.txt

set -euo pipefail

# Avoid "nested session" error when run from inside Claude Code
unset CLAUDECODE 2>/dev/null || true

OUTFILE="rhdp/12-output-order89-prompt-structures.txt"
: > "$OUTFILE"

SELECTED_TEXT='fun greet(name: String): String {
    return "Hello, " + name
}'

PROMPT_TEXT="Use string interpolation instead of concatenation"
FILE_PATH="src/main/kotlin/Example.kt"
LANGUAGE="kotlin"

log() {
    echo "$1" | tee -a "$OUTFILE"
}

run_test() {
    local label="$1"
    local prompt="$2"

    log ""
    log "================================================================"
    log "TEST: $label"
    log "================================================================"
    log "PROMPT SENT:"
    log "$prompt"
    log "----------------------------------------------------------------"
    log "RESPONSE:"

    local response
    response=$(claude -p --model sonnet "$prompt" 2>/dev/null) || {
        log "[ERROR: claude exited with code $?]"
        return
    }

    echo "$response" | tee -a "$OUTFILE"

    log "----------------------------------------------------------------"
    # Check for markdown fences
    if echo "$response" | grep -q '```'; then
        log "VERDICT: CONTAINS MARKDOWN FENCES"
    else
        log "VERDICT: CLEAN (no fences)"
    fi
    # Check for explanatory prose (lines that start with a capital letter and contain spaces, excluding code)
    local prose_lines
    prose_lines=$(echo "$response" | grep -cE '^[A-Z][a-z]+ ' || true)
    if [ "$prose_lines" -gt 0 ]; then
        log "WARNING: $prose_lines lines look like prose"
    fi
    log ""
}

log "Order 89 Prompt Structure Harness"
log "================================="
log "Date: $(date)"
log ""
log "Selected text:"
log "$SELECTED_TEXT"
log ""
log "User instruction: $PROMPT_TEXT"
log ""

# --- Test 1: Simple direct instruction ---
run_test "Simple direct" \
"Output ONLY code, no explanations, no markdown fences. Do not include any text before or after the code.

Replace the following $LANGUAGE code according to this instruction: $PROMPT_TEXT

$SELECTED_TEXT"

# --- Test 2: System-prompt style with delimiters ---
run_test "Delimited with role" \
"You are a code transformation tool. You receive code and an instruction. You output ONLY the transformed code. No markdown, no explanations, no backticks, no commentary. Output the raw code only.

<instruction>$PROMPT_TEXT</instruction>
<code language=\"$LANGUAGE\" file=\"$FILE_PATH\">
$SELECTED_TEXT
</code>

Output the transformed code and nothing else:"

# --- Test 3: Fill-in-the-middle style ---
run_test "FIM-style prefix/suffix" \
"You are a code-only output tool. Never use markdown. Never explain. Output only raw code.

The user selected this code in $FILE_PATH ($LANGUAGE):

$SELECTED_TEXT

The user wants: $PROMPT_TEXT

Replacement code (raw, no fences):"

# --- Test 4: JSON-schema enforced output ---
run_test "With --json-schema (if supported)" \
"Transform this $LANGUAGE code: $PROMPT_TEXT

$SELECTED_TEXT

Return only the replacement code. No markdown fences. No explanation."

# --- Test 5: Minimal instruction ---
run_test "Ultra-minimal" \
"$PROMPT_TEXT:

$SELECTED_TEXT"

# --- Test 6: Explicit negative constraints ---
run_test "Negative constraints" \
"Rules:
- Output ONLY the replacement code
- Do NOT wrap in markdown code fences
- Do NOT add any explanation before or after
- Do NOT add comments that weren't in the original
- Do NOT change anything beyond what the instruction asks

Language: $LANGUAGE
File: $FILE_PATH
Instruction: $PROMPT_TEXT

Code to transform:
$SELECTED_TEXT"

log ""
log "================================================================"
log "SUMMARY"
log "================================================================"
log "Review the results above to determine which prompt structure"
log "produces the cleanest code-only output."
