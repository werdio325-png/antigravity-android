#!/usr/bin/env python3
"""
pack_certs.py - Copies SSL CA certificates into certs/.
"""
import sys
import os
import shutil

def pack_certs(certs_dir: str, dest_certs_dir: str) -> list:
    if not os.path.isdir(certs_dir):
        raise NotADirectoryError(f"Certs directory not found: {certs_dir}")
    os.makedirs(dest_certs_dir, exist_ok=True)
    
    copied = []
    for item in sorted(os.listdir(certs_dir)):
        src = os.path.join(certs_dir, item)
        if os.path.isfile(src):
            dst = os.path.join(dest_certs_dir, item)
            if item.endswith((".crt", ".pem")):
                with open(src, "rb") as rf:
                    raw = rf.read().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
                with open(dst, "wb") as wf:
                    wf.write(raw)
            else:
                shutil.copyfile(src, dst)
            copied.append(item)
            
    return copied

def main():
    if len(sys.argv) < 3 or sys.argv[1] in ("-h", "--help"):
        print("Usage: pack_certs.py <certs_dir> <dest_certs_dir>")
        sys.exit(1)
    
    try:
        certs = pack_certs(sys.argv[1], sys.argv[2])
        print(f"[OK] Packed {len(certs)} certificate files to {sys.argv[2]}")
        sys.exit(0)
    except Exception as e:
        print(f"[ERROR] Failed packing certs: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
