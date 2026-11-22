#!/usr/bin/env python3
"""
sanitize_stage.py - Cleans staged env/ assets before manifest generation:
1. Removes any Python bytecode caches (__pycache__, *.pyc, *.pyo).
2. Normalizes Windows CRLF (\r\n) line endings to POSIX LF (\n) on all
   non-binary text/script/config files so Android execve/shebangs never fail
   with exit status 127 when built on Windows (core.autocrlf=true).
"""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

BINARY_PROBE_BYTES = 8192
SKIP_DIR_NAMES = frozenset(("__pycache__",))
SKIP_FILE_SUFFIXES = (".pyc", ".pyo")


def is_binary_bytes(data: bytes) -> bool:
    return b"\x00" in data[:BINARY_PROBE_BYTES]


def sanitize_tree(root: Path) -> tuple[int, int]:
    """Remove bytecode artifacts and normalize CRLF -> LF under root.

    Returns (removed_bytecode_count, normalized_text_count).
    """
    removed = 0
    normalized = 0
    if not root.exists():
        return removed, normalized

    if root.is_file():
        data = root.read_bytes()
        if not is_binary_bytes(data) and b"\r\n" in data:
            root.write_bytes(data.replace(b"\r\n", b"\n"))
            normalized += 1
        return removed, normalized

    for dirpath, dirnames, filenames in os.walk(root):
        # Prune and delete __pycache__ directories
        for d in list(dirnames):
            if d in SKIP_DIR_NAMES:
                shutil.rmtree(os.path.join(dirpath, d), ignore_errors=True)
                dirnames.remove(d)
                removed += 1

        for fn in filenames:
            full = Path(dirpath) / fn
            if full.is_symlink():
                continue
            if fn.endswith(SKIP_FILE_SUFFIXES):
                try:
                    full.unlink()
                    removed += 1
                except OSError:
                    pass
                continue
            try:
                data = full.read_bytes()
            except OSError:
                continue
            if is_binary_bytes(data):
                continue
            if b"\r\n" in data:
                full.write_bytes(data.replace(b"\r\n", b"\n"))
                normalized += 1

    return removed, normalized


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: sanitize_stage.py <path> [<path> ...]", file=sys.stderr)
        return 1

    total_removed = 0
    total_normalized = 0
    for arg in sys.argv[1:]:
        rem, norm = sanitize_tree(Path(arg))
        total_removed += rem
        total_normalized += norm

    print(f"[env.sanitize] removed={total_removed} bytecode artifact(s), normalized={total_normalized} CRLF file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
