#!/usr/bin/env python3
"""Build and optimize bootstrap.tar.gz from bootstrap-aarch64.zip.

Unpacks bootstrap-aarch64.zip, strips non-essential documentation/man pages,
updates SYMLINKS.txt, applies symlinks and packs a clean bootstrap.tar.gz.
"""

import argparse
import os
import shutil
import sys
import tarfile
import tempfile
import zipfile

TRIM_PREFIXES = (
    "share/man/",
    "share/doc/",
    "share/info/",
    "libexec/installed-tests/",
)


def pack_bootstrap(zip_path, out_tar_gz, trim=True):
    if not os.path.isfile(zip_path):
        raise FileNotFoundError(f"Input zip not found: {zip_path}")

    tmp = tempfile.mkdtemp(prefix="bootstrap_build_")
    try:
        dest = os.path.abspath(tmp)
        total_files = 0
        pruned_files = 0
        pruned_bytes = 0

        with zipfile.ZipFile(zip_path, "r") as z:
            sl_raw = z.read("SYMLINKS.txt").decode("utf-8", errors="replace")

            for info in z.infolist():
                name = info.filename.lstrip("./")
                if not name or name.endswith("/"):
                    continue
                if name == "SYMLINKS.txt":
                    continue

                total_files += 1
                if trim and name.startswith(TRIM_PREFIXES):
                    pruned_files += 1
                    pruned_bytes += info.file_size
                    continue

                target_file = os.path.normpath(os.path.join(dest, name))
                if not target_file.startswith(dest + os.sep):
                    continue

                os.makedirs(os.path.dirname(target_file), exist_ok=True)
                with z.open(info) as src, open(target_file, "wb") as dst:
                    shutil.copyfileobj(src, dst)

                mode = (info.external_attr >> 16) & 0o7777
                if mode:
                    os.chmod(target_file, mode)

        # Process symlinks
        new_sl_lines = []
        total_links = 0
        pruned_links = 0

        for line in sl_raw.splitlines():
            if "←" not in line:
                continue
            target, link_name = line.split("←", 1)
            rel_name = link_name.lstrip("./")
            total_links += 1

            if trim and rel_name.startswith(TRIM_PREFIXES):
                pruned_links += 1
                continue

            link_path = os.path.normpath(os.path.join(dest, rel_name))
            if not link_path.startswith(dest + os.sep):
                continue

            os.makedirs(os.path.dirname(link_path), exist_ok=True)
            if os.path.lexists(link_path):
                os.unlink(link_path)
            os.symlink(target, link_path)
            new_sl_lines.append(f"{target}←{link_name}")

        # Write clean SYMLINKS.txt
        sl_out_path = os.path.join(dest, "SYMLINKS.txt")
        with open(sl_out_path, "w", encoding="utf-8") as f:
            f.write("\n".join(new_sl_lines) + "\n")

        print(f"[pack_bootstrap] Extracted: {total_files - pruned_files} files "
              f"(pruned {pruned_files} doc/man files, ~{pruned_bytes / (1024 * 1024):.2f} MB)")
        print(f"[pack_bootstrap] Symlinks: {total_links - pruned_links} preserved "
              f"(pruned {pruned_links} man-page links)")

        # Create tar.gz archive
        os.makedirs(os.path.dirname(os.path.abspath(out_tar_gz)), exist_ok=True)
        tmp_tar = out_tar_gz + ".tmp"
        if os.path.exists(tmp_tar):
            os.unlink(tmp_tar)

        with tarfile.open(tmp_tar, "w:gz", compresslevel=9) as tar:
            tar.add(dest, arcname=".")

        os.replace(tmp_tar, out_tar_gz)
        out_size_mb = os.path.getsize(out_tar_gz) / (1024 * 1024)
        print(f"[pack_bootstrap] Successfully wrote: {out_tar_gz} ({out_size_mb:.2f} MB)")

    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description="Pack & trim bootstrap archive")
    parser.add_argument("--zip", required=True, help="Path to bootstrap-aarch64.zip")
    parser.add_argument("--out", required=True, help="Path to output bootstrap.tar.gz")
    parser.add_argument("--no-trim", action="store_true", help="Do not prune doc/man files")
    args = parser.parse_args()

    pack_bootstrap(args.zip, args.out, trim=not args.no_trim)


if __name__ == "__main__":
    main()
