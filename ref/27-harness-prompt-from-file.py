"""
CLI harness that reads a prompt from a file and sends it to the llama-server.

Dependencies:
    pip install httpx

Usage:
    # First, start the server:
    #   ./07-harness-sweepai-llama-cpp-server.sh
    # Then run:
    python 27-harness-prompt-from-file.py <prompt-file>
"""

import sys

import httpx

import importlib
harness = importlib.import_module("08-harness-test-sweepai-model")

BASE_URL = harness.BASE_URL
TIMEOUT = harness.TIMEOUT


def main() -> int:
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <prompt-file>", file=sys.stderr)
        return 1

    prompt_file = sys.argv[1]

    try:
        with open(prompt_file) as f:
            prompt = f.read()
    except FileNotFoundError:
        print(f"Error: file not found: {prompt_file}", file=sys.stderr)
        return 1

    try:
        httpx.get(f"{BASE_URL}/health", timeout=5.0).raise_for_status()
    except (httpx.ConnectError, httpx.HTTPStatusError) as e:
        print(f"Error: cannot reach llama-server at {BASE_URL}: {e}", file=sys.stderr)
        print("Start it first with: ./07-harness-sweepai-llama-cpp-server.sh", file=sys.stderr)
        return 1

    payload = {
        "prompt": prompt,
        "n_predict": 512,
        "temperature": 0.0,
        "stop": ["<|file_sep|>", "</s>"],
        "stream": False,
    }
    resp = httpx.post(f"{BASE_URL}/completion", json=payload, timeout=TIMEOUT)
    resp.raise_for_status()
    print(resp.json()["content"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
