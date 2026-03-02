"""
Download the sweepai/sweep-next-edit-1.5B GGUF model if not already cached.

Dependencies:
    pip install huggingface-hub

Usage:
    python 06-harness-download-sweepai-model.py

Prints the local model path on success.
"""

import sys

from huggingface_hub import hf_hub_download

MODEL_REPO = "sweepai/sweep-next-edit-1.5B"
MODEL_FILENAME = "sweep-next-edit-1.5b.q8_0.v2.gguf"


def download_model() -> str:
    """Download the model from Hugging Face Hub, returning the local path.

    Uses the huggingface_hub cache so the 1.54 GB file is only downloaded once.
    """
    return hf_hub_download(
        repo_id=MODEL_REPO,
        filename=MODEL_FILENAME,
        repo_type="model",
    )


if __name__ == "__main__":
    try:
        model_path = download_model()
    except Exception as e:
        print(f"Error downloading model: {e}", file=sys.stderr)
        sys.exit(1)
    print(model_path)
