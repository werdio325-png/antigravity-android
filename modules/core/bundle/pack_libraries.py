#!/usr/bin/env python3
"""
pack_libraries.py - Copies glibc shared libraries and compatibility shims into lib/.
"""
import sys
import os
import shutil
import stat

def pack_libraries(glibc_dir: str, dest_lib_dir: str) -> list:
    if not os.path.isdir(glibc_dir):
        raise NotADirectoryError(f"Glibc directory not found: {glibc_dir}")
    os.makedirs(dest_lib_dir, exist_ok=True)
    
    copied = []
    for item in sorted(os.listdir(glibc_dir)):
        if ".so" in item:
            src = os.path.join(glibc_dir, item)
            dst = os.path.join(dest_lib_dir, item)
            shutil.copyfile(src, dst)
            mode = os.stat(dst).st_mode
            os.chmod(dst, mode | stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
            copied.append(item)
            
    # Copy glibc/etc network and name service configs (hosts, resolv.conf, nsswitch.conf)
    etc_src = os.path.join(glibc_dir, "etc")
    if os.path.isdir(etc_src):
        dest_etc = os.path.abspath(os.path.join(dest_lib_dir, "..", "etc"))
        os.makedirs(dest_etc, exist_ok=True)
        for fname in sorted(os.listdir(etc_src)):
            s_file = os.path.join(etc_src, fname)
            if os.path.isfile(s_file):
                d_file = os.path.join(dest_etc, fname)
                with open(s_file, "rb") as rf:
                    raw = rf.read().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
                with open(d_file, "wb") as wf:
                    wf.write(raw)
                mode = os.stat(d_file).st_mode
                os.chmod(d_file, mode | stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)
                copied.append(f"etc/{fname}")

    return copied

def main():
    if len(sys.argv) < 3 or sys.argv[1] in ("-h", "--help"):
        print("Usage: pack_libraries.py <glibc_dir> <dest_lib_dir>")
        sys.exit(1)
    
    try:
        libs = pack_libraries(sys.argv[1], sys.argv[2])
        print(f"[OK] Packed {len(libs)} shared libraries to {sys.argv[2]}")
        sys.exit(0)
    except Exception as e:
        print(f"[ERROR] Failed packing libraries: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
