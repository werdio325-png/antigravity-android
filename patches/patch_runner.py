#!/usr/bin/env python3
"""Thin entry point for the Antigravity core patch pipeline.

All logic lives in patch_lib; this shim only forwards argv.

    python3 patches/patch_runner.py <binary> [--verify|--dry-run|--allow-missing|--manifest P|--no-manifest]
"""

import sys

from patch_lib.runner import main


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
