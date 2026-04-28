#!/usr/bin/env bash
# Usage: fake-agent.sh --fixture <name> <prompt_file>
# Emits the contents of fixtures/<name> to STDOUT.
# Optional env vars:
#   FAKE_AGENT_DELAY_MS  - sleep before output (ms)
#   FAKE_AGENT_EXIT      - exit code (default 0)
#   FAKE_AGENT_RECORD_PROMPT_TO  - copy received prompt file here

set -eu

fixture=""
prompt_file=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --fixture) fixture="$2"; shift 2 ;;
        *) prompt_file="$1"; shift ;;
    esac
done

if [ -z "${fixture}" ]; then
    echo "fake-agent.sh: --fixture <name> is required" >&2
    exit 64
fi

script_dir="$(cd "$(dirname "$0")" && pwd)"
fixture_path="${script_dir}/fixtures/${fixture}"
if [ ! -f "${fixture_path}" ]; then
    echo "fake-agent.sh: fixture not found: ${fixture_path}" >&2
    exit 65
fi

if [ -n "${FAKE_AGENT_RECORD_PROMPT_TO:-}" ] && [ -n "${prompt_file:-}" ]; then
    cp "${prompt_file}" "${FAKE_AGENT_RECORD_PROMPT_TO}"
fi

if [ -n "${FAKE_AGENT_DELAY_MS:-}" ]; then
    sleep_seconds=$(awk "BEGIN { printf \"%.3f\", ${FAKE_AGENT_DELAY_MS}/1000 }")
    sleep "${sleep_seconds}"
fi

cat "${fixture_path}"

exit "${FAKE_AGENT_EXIT:-0}"
