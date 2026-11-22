#!/usr/bin/env python3
"""
pack_binaries.py - Copies core loader, language_server executable, and shims into bin/.
"""
import sys
import os
import shutil
import stat

def make_exec(p: str):
    mode = os.stat(p).st_mode
    os.chmod(p, mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH | stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)

def copy_text_lf(src: str, dst: str):
    """Copy a text/shell script normalizing CRLF -> LF for Linux/Android execve safety."""
    with open(src, "rb") as f:
        data = f.read()
    data = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    with open(dst, "wb") as f:
        f.write(data)

def pack_binaries(core_bin_path: str, loader_path: str, shims_dir: str, dest_bin_dir: str) -> list:
    os.makedirs(dest_bin_dir, exist_ok=True)
    installed = []

    # 1. Staged core binary
    if not os.path.isfile(core_bin_path):
        raise FileNotFoundError(f"Core binary not found: {core_bin_path}")
    dest_core = os.path.join(dest_bin_dir, "liblanguage_server.so")
    shutil.copyfile(core_bin_path, dest_core)
    make_exec(dest_core)
    installed.append("liblanguage_server.so")

    # 2. Glibc dynamic loader (libloader.so for Android app, ld-linux-aarch64.so.1 for generic linux scripts)
    if not os.path.isfile(loader_path):
        raise FileNotFoundError(f"Loader not found: {loader_path}")
    dest_loader = os.path.join(dest_bin_dir, "libloader.so")
    shutil.copyfile(loader_path, dest_loader)
    make_exec(dest_loader)
    installed.append("libloader.so")

    dest_ld = os.path.join(dest_bin_dir, "ld-linux-aarch64.so.1")
    shutil.copyfile(loader_path, dest_ld)
    make_exec(dest_ld)
    installed.append("ld-linux-aarch64.so.1")

    # 3. Shims
    # Install xdg-open first from system or browser shims
    xdg_src = os.path.join(shims_dir, "system", "xdg_open.sh")
    if not os.path.isfile(xdg_src):
        xdg_src = os.path.join(shims_dir, "browser", "xdg_open.sh")
    if os.path.isfile(xdg_src):
        dst_xdg = os.path.join(dest_bin_dir, "xdg-open")
        copy_text_lf(xdg_src, dst_xdg)
        make_exec(dst_xdg)
        installed.append("xdg-open")

    # Browser shims (symlinks to xdg-open with fallback to compact delegate script)
    browser_dir = os.path.join(shims_dir, "browser")
    if os.path.isdir(browser_dir):
        browser_delegates = [
            ("chrome.sh", "chrome"),
            ("chromium.sh", "chromium"),
            ("gnome_open.sh", "gnome-open"),
            ("google_chrome.sh", "google-chrome"),
            ("open.sh", "open"),
            ("sensible_browser.sh", "sensible-browser"),
            ("x_www_browser.sh", "x-www-browser"),
        ]
        for src_name, dst_name in browser_delegates:
            dst_f = os.path.join(dest_bin_dir, dst_name)
            try:
                if os.path.lexists(dst_f):
                    os.unlink(dst_f)
                os.symlink("xdg-open", dst_f)
            except OSError:
                src_f = os.path.join(browser_dir, src_name)
                if os.path.isfile(src_f):
                    copy_text_lf(src_f, dst_f)
                else:
                    with open(dst_f, "w", encoding="utf-8", newline="\n") as f:
                        f.write('#!/system/bin/sh\nexec "$(dirname "$0")/xdg-open" "$@"\n')
                make_exec(dst_f)
            installed.append(dst_name)

    # System shims
    sys_dir = os.path.join(shims_dir, "system")
    if os.path.isdir(sys_dir):
        src_open_helper = os.path.join(sys_dir, "open_helper.sh")
        if os.path.isfile(src_open_helper):
            dst_f = os.path.join(dest_bin_dir, "open_helper")
            try:
                if os.path.lexists(dst_f):
                    os.unlink(dst_f)
                os.symlink("xdg-open", dst_f)
            except OSError:
                copy_text_lf(src_open_helper, dst_f)
                make_exec(dst_f)
            installed.append("open_helper")

    # Privilege shims
    priv_dir = os.path.join(shims_dir, "privilege")
    if os.path.isdir(priv_dir):
        src_rish = os.path.join(priv_dir, "rish.sh")
        if os.path.isfile(src_rish):
            dst_f = os.path.join(dest_bin_dir, "rish")
            copy_text_lf(src_rish, dst_f)
            make_exec(dst_f)
            installed.append("rish")
        
        src_dex = os.path.join(priv_dir, "rish_shizuku.dex")
        if os.path.isfile(src_dex):
            dst_dex = os.path.join(dest_bin_dir, "rish_shizuku.dex")
            shutil.copyfile(src_dex, dst_dex)
            installed.append("rish_shizuku.dex")

    return installed

def main():
    if len(sys.argv) < 5 or sys.argv[1] in ("-h", "--help"):
        print("Usage: pack_binaries.py <core_bin> <loader_path> <shims_dir> <dest_bin_dir>")
        sys.exit(1)
    
    try:
        items = pack_binaries(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
        print(f"[OK] Packed {len(items)} binaries/shims to {sys.argv[4]}")
        sys.exit(0)
    except Exception as e:
        print(f"[ERROR] Failed packing binaries: {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
