#!/bin/bash

# FIM Request Testing Harness Runner
# This script runs the complete test harness to capture and validate FIM requests
# Generated corpus helps validate the completamente Kotlin plugin implementation

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Starting FIM Request Testing Harness..."
echo "========================================"
echo ""

# Start the mock server in the background
echo "[1/4] Starting mock infill server..."
python3 "$SCRIPT_DIR/mock_infill_server.py" &
SERVER_PID=$!
echo "      Server PID: $SERVER_PID"

# Wait for server to start
sleep 2

# Run the Vim test script
echo "[2/4] Running Vim test script..."
if vim -u NONE -N -S "$SCRIPT_DIR/test_requests.vim" 2>/dev/null; then
    echo "      Vim test script completed successfully"
else
    echo "      Warning: Vim test script exited with status $?"
fi

# Stop the server
echo "[3/4] Stopping mock infill server..."
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true
echo "      Server stopped"

echo "[4/4] Test results ready"
echo ""
echo "========================================"
echo "Test Results:"
echo "========================================"
echo ""

# Display corpus location and summary
CORPUS_FILE="$SCRIPT_DIR/fim_requests_corpus.json"
LOG_FILE="$SCRIPT_DIR/mock_infill_server.log"

if [ -f "$CORPUS_FILE" ]; then
    echo "✓ Corpus file: $CORPUS_FILE"
    CORPUS_LINES=$(python3 -c "import json; data=json.load(open('$CORPUS_FILE')); print(len(data))" 2>/dev/null || echo "?")
    echo "  Contains $CORPUS_LINES test cases"
    echo ""
else
    echo "✗ Corpus file not found at $CORPUS_FILE"
fi

if [ -f "$LOG_FILE" ]; then
    echo "✓ Server log file: $LOG_FILE"
    LOG_LINES=$(wc -l < "$LOG_FILE")
    echo "  Contains $LOG_LINES log entries"
    echo ""
else
    echo "✗ Server log file not found at $LOG_FILE"
fi

echo "Next steps:"
echo "  1. View the corpus:"
echo "     cat $CORPUS_FILE | python3 -m json.tool"
echo ""
echo "  2. View the server logs:"
echo "     cat $LOG_FILE | python3 -m json.tool"
echo ""
echo "  3. Run the Kotlin tests:"
echo "     cd /Users/lucatume/repos/completamente"
echo "     ./gradlew test --tests '*TypeScriptLocalContextTest*'"
echo ""
