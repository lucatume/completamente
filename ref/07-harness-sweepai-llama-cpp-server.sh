#!/usr/bin/env bash
#
# Download (if needed) and start llama-server with the sweepai/sweep-next-edit-1.5B model.
#
# Parameters are derived from the reference run_model.py:
#   n_ctx  = 8192
#   temp   = 0.0  (greedy decoding)
#
# Usage:
#   ./07-harness-sweepai-llama-cpp-server.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/.venv"

if [ -d "$VENV_DIR" ]; then
    # shellcheck disable=SC1091
    source "$VENV_DIR/bin/activate"
fi

echo "Ensuring model is downloaded..."
MODEL_PATH="$(python3 "$SCRIPT_DIR/06-harness-download-sweepai-model.py")"

if [ ! -f "$MODEL_PATH" ]; then
    echo "Error: download script did not return a valid model path: $MODEL_PATH" >&2
    exit 1
fi

HOST="${HOST:-127.0.0.1}"
PORT="${PORT:-8017}"

echo "Starting llama-server on ${HOST}:${PORT} with sweep-next-edit-1.5B..."
exec llama-server \
    --model "$MODEL_PATH" \
    --ctx-size 8192 \
    --temp 0.0 \
    --host "$HOST" \
    --port "$PORT"
