#!/usr/bin/env python3
"""
Antigravity Core Web Interface Extractor & Patcher

Extracts the embedded web interface (React JS bundle, Tailwind CSS, HTML, symbols)
from the native language_server binary, and patches it back with modified files.
"""

import os
import sys
import io
import struct
import zipfile

ZIP_OFFSET = 0x875b66d
ZIP_TARGET_SIZE = 3247719  # 0x318e67 bytes
EOCD_SIGNATURE = b'PK\x05\x06'

def get_zip_info(binary_path):
    if not os.path.exists(binary_path):
        raise FileNotFoundError(f"Core binary not found: {binary_path}")

    with open(binary_path, "rb") as f:
        f.seek(ZIP_OFFSET)
        magic = f.read(4)
        if magic != b'PK\x03\x04':
            raise ValueError(f"Invalid ZIP magic at {hex(ZIP_OFFSET)}: {magic.hex()}")

        f.seek(ZIP_OFFSET)
        zip_bytes = f.read(ZIP_TARGET_SIZE)

    bio = io.BytesIO(zip_bytes)
    zf = zipfile.ZipFile(bio)
    return zf

def extract_web(binary_path, output_dir="web_ui"):
    print(f"[*] Extracting web interface from: {binary_path} (offset: {hex(ZIP_OFFSET)})...")
    zf = get_zip_info(binary_path)

    os.makedirs(output_dir, exist_ok=True)
    count = 0
    for member in zf.infolist():
        zf.extract(member, output_dir)
        count += 1

    print(f"[+] Successfully extracted {count} files to: {output_dir}/")
    print(f"    Key files:")
    print(f"    - {output_dir}/index.html (HTML root & theme/meta loaders)")
    print(f"    - {output_dir}/main.js (React Web UI bundle)")
    print(f"    - {output_dir}/compiled_tailwind.css (Styles)")
    print(f"    - {output_dir}/jetbox.css (Custom IDE styles)")

def pack_web_to_zip(web_dir):
    if not os.path.exists(web_dir):
        raise FileNotFoundError(f"Web UI directory not found: {web_dir}")

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for root, dirs, files in os.walk(web_dir):
            dirs.sort()
            files.sort()
            for f in files:
                filepath = os.path.join(root, f)
                arcname = os.path.relpath(filepath, web_dir)
                zf.write(filepath, arcname)

    data = buf.getvalue()
    raw_size = len(data)

    if raw_size > ZIP_TARGET_SIZE:
        raise ValueError(
            f"Repacked web interface is too large ({raw_size} > {ZIP_TARGET_SIZE} bytes)! "
            f"Exceeds available binary segment by {raw_size - ZIP_TARGET_SIZE} bytes."
        )

    diff = ZIP_TARGET_SIZE - raw_size
    # Pad using EOCD comment field
    if diff <= 65535:
        # Last 2 bytes in standard zip is the comment length (uint16)
        padded = data[:-2] + struct.pack("<H", diff) + (b"\x00" * diff)
    else:
        # Fallback if diff > 65535: pad with dummy asset
        pad_size = diff - 30 - len("agy_pad.bin") - 46 - len("agy_pad.bin")
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
            for root, dirs, files in os.walk(web_dir):
                dirs.sort()
                files.sort()
                for f in files:
                    filepath = os.path.join(root, f)
                    arcname = os.path.relpath(filepath, web_dir)
                    zf.write(filepath, arcname)
            zf.writestr("agy_pad.bin", b"\x00" * max(0, pad_size))
        data = buf.getvalue()
        diff = ZIP_TARGET_SIZE - len(data)
        padded = data[:-2] + struct.pack("<H", diff) + (b"\x00" * diff)

    if len(padded) != ZIP_TARGET_SIZE:
        raise RuntimeError(f"Padding mismatch: got {len(padded)}, expected {ZIP_TARGET_SIZE}")

    # Verify validity
    test_bio = io.BytesIO(padded)
    test_zf = zipfile.ZipFile(test_bio)
    test_zf.testzip()

    return padded

def patch_web(binary_path, web_dir="web_ui"):
    print(f"[*] Repacking and patching web interface from {web_dir}/ into: {binary_path}...")
    padded_zip = pack_web_to_zip(web_dir)

    with open(binary_path, "r+b") as f:
        f.seek(ZIP_OFFSET)
        existing_magic = f.read(4)
        if existing_magic != b'PK\x03\x04':
            raise ValueError(f"Target binary does not have ZIP header at {hex(ZIP_OFFSET)}!")

        f.seek(ZIP_OFFSET)
        f.write(padded_zip)

    print(f"[+] Successfully patched web interface at {hex(ZIP_OFFSET)} ({len(padded_zip)} bytes).")

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print("Usage:")
        print("  python3 patch_web.py extract [core_binary] [output_dir]")
        print("  python3 patch_web.py patch   [core_binary] [web_dir]")
        print("Default core_binary: core/language_server")
        print("Default web_dir:     web_ui")
        sys.exit(0)

    action = sys.argv[1]
    core_bin = sys.argv[2] if len(sys.argv) > 2 else "core/language_server"

    if action == "extract":
        out_dir = sys.argv[3] if len(sys.argv) > 3 else "web_ui"
        extract_web(core_bin, out_dir)
    elif action == "patch":
        w_dir = sys.argv[3] if len(sys.argv) > 3 else "web_ui"
        patch_web(core_bin, w_dir)
    else:
        print(f"Unknown action: {action}. Use 'extract' or 'patch'.")
        sys.exit(1)
