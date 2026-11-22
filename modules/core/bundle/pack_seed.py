#!/usr/bin/env python3
"""
pack_seed.py - Recursively copies default state, seed protobuf configurations,
agent guidelines, and bundled skills into seed/.
"""
import sys
import os
import shutil


def pack_seed(seed_dir: str, dest_seed_dir: str) -> list:
    if not os.path.isdir(seed_dir):
        raise NotADirectoryError(f"Seed directory not found: {seed_dir}")
    os.makedirs(dest_seed_dir, exist_ok=True)

    copied = []
    for root, dirs, filenames in os.walk(seed_dir):
        dirs.sort()
        rel_root = os.path.relpath(root, seed_dir)
        for fn in sorted(filenames):
            src = os.path.join(root, fn)
            if not os.path.isfile(src):
                continue
            rel_path = fn if rel_root == "." else os.path.join(rel_root, fn)
            dst = os.path.join(dest_seed_dir, rel_path)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if fn.endswith((".md", ".pbtxt", ".txt", ".json", ".sh", ".env")):
                with open(src, "rb") as rf:
                    raw = rf.read().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
                with open(dst, "wb") as wf:
                    wf.write(raw)
            else:
                shutil.copyfile(src, dst)
            copied.append(rel_path.replace(os.sep, "/"))

    return copied


def main():
    if len(sys.argv) < 3 or sys.argv[1] in ("-h", "--help"):
        print("Usage: pack_seed.py <seed_dir> <dest_seed_dir>")
        sys.exit(1)

    try:
        seeds = pack_seed(sys.argv[1], sys.argv[2])
        print(f"[OK] Packed {len(seeds)} seed files to {sys.argv[2]}")
        sys.exit(0)
    except Exception as e:
        print(f"[ERROR] Failed packing seeds: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
