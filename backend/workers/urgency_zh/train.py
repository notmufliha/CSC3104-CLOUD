"""Trains the "urgency" (zh) worker. All the logic is in common_zh/train_zh.py (shared by the three ZH workers,
copied next to this file in the image, like text_norm.py).

Run:    python train.py           (~1 min; also run automatically by the Dockerfile)
"""
import os
import sys

# local runs: the shared files live in ../common_zh (in the image they sit next to this file)
sys.path.insert(1, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "common_zh"))
import train_zh  # noqa: E402

if __name__ == "__main__":
    train_zh.main("urgency")
