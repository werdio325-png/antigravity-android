#!/usr/bin/env python3
"""
upgrade_engine.py - Validates a newer official Antigravity engine for this fork.

The fork never downloads engine builds on its own: point this tool at an archive
you obtained from an official Google Antigravity distribution, and it will

  1. verify the archive really contains an aarch64 ELF64 PIE `language_server`;
  2. run every binary patch group against it in read-only mode and report which
     ones still match (the patch layer is signature based and fails closed);
  3. diff the model catalogue against the engine currently embedded;
  4. with --apply, install it into modules/core/pristine/ + spec files and
     recompute the patched-binary hashes that modules/runner checks at install.

Nothing here bypasses authentication, entitlement or regional checks.

Examples:
  python3 modules/engine/upgrade_engine.py --archive ~/language_server.tar.gz
  python3 modules/engine/upgrade_engine.py --archive new.tar.gz --core-version 2.12.0 --apply
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.dirname(SCRIPT_DIR)
PROJECT_ROOT = os.path.dirname(MODULES_DIR)
sys.path.insert(0, SCRIPT_DIR)
sys.path.insert(0, os.path.join(MODULES_DIR, "core", "unpack"))
sys.path.insert(0, os.path.join(MODULES_DIR, "patches"))

import catalog          # noqa: E402
import engine_probe     # noqa: E402
import extractor        # noqa: E402
import pipeline         # noqa: E402

CORE_SPEC = os.path.join(MODULES_DIR, "core", "spec", "checksums.json")
VERSION_SPEC = os.path.join(MODULES_DIR, "core", "spec", "version.env")
PRISTINE = os.path.join(MODULES_DIR, "core", "pristine", "language_server.tar.gz")
RUNNER = os.path.join(MODULES_DIR, "runner", "prepare_runtime.py")


def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_report(archive: str) -> dict:
    tmp = tempfile.mkdtemp(prefix="continuum_upgrade_")
    staged = os.path.join(tmp, "language_server")
    try:
        extractor.extract_core(archive, staged)
        elf = engine_probe.elf_info(staged)
        if not elf.get("aarch64") or not elf.get("pie"):
            raise SystemExit("[ERROR] %s is not an aarch64 PIE ET_DYN binary (%s)" % (staged, elf))

        with open(staged, "rb") as handle:
            data = handle.read()

        patch_report = engine_probe.patch_compatibility(data)

        new_models = catalog.extract_models(data)
        current = {}
        if os.path.isfile(PRISTINE):
            current_path = engine_probe.extract_from_archive(PRISTINE)
            with open(current_path, "rb") as handle:
                current = {m["id"]: m for m in catalog.extract_models(handle.read())}

        new_ids = {m["id"] for m in new_models}
        old_ids = set(current)
        return {
            "archive": os.path.abspath(archive),
            "archive_sha256": sha256(archive),
            "binary_sha256": sha256(staged),
            "binary_size": os.path.getsize(staged),
            "elf": elf,
            "patches": patch_report,
            "catalog": {"models": new_models, "count": len(new_models)},
            "catalog_delta": {
                "added": sorted(new_ids - old_ids),
                "removed": sorted(old_ids - new_ids),
            },
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def patched_hashes(archive: str) -> dict:
    """Hashes of the patched binary for both gate modes (used by prepare_runtime)."""
    tmp = tempfile.mkdtemp(prefix="continuum_patch_")
    staged = os.path.join(tmp, "language_server")
    try:
        extractor.extract_core(archive, staged)
        out = {}
        for mode, skip in (("full", False), ("no_bypass", True)):
            copy_path = os.path.join(tmp, mode)
            shutil.copyfile(staged, copy_path)
            ok = pipeline.run_pipeline(copy_path, dry_run=False, verify=False,
                                       allow_missing=True, skip_gates=skip)
            if not ok:
                raise SystemExit("[ERROR] patch pipeline failed in %s mode" % mode)
            out[mode] = sha256(copy_path)
        return out
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def apply_upgrade(archive: str, report: dict, core_version: str, build_tag: str) -> None:
    shutil.copyfile(archive, PRISTINE)

    with open(VERSION_SPEC, encoding="utf-8") as handle:
        spec = handle.read()
    spec = re.sub(r"CORE_VERSION=.*", "CORE_VERSION=%s" % core_version, spec)
    spec = re.sub(r"CORE_BUILD_TAG=.*", "CORE_BUILD_TAG=%s" % build_tag, spec)
    with open(VERSION_SPEC, "w", encoding="utf-8") as handle:
        handle.write(spec)

    with open(CORE_SPEC, "w", encoding="utf-8") as handle:
        json.dump({
            "archive": {"filename": "language_server.tar.gz", "sha256": report["archive_sha256"]},
            "binary": {
                "filename": "language_server",
                "sha256": report["binary_sha256"],
                "size": report["binary_size"],
            },
        }, handle, indent=2)
        handle.write("\n")

    hashes = patched_hashes(archive)
    with open(RUNNER, encoding="utf-8") as handle:
        runner = handle.read()
    runner = re.sub(r'TARGET_SHA_FULL = "[0-9a-f]{64}"',
                    'TARGET_SHA_FULL = "%s"' % hashes["full"], runner)
    runner = re.sub(r'TARGET_SHA_NO_BYPASS = "[0-9a-f]{64}"',
                    'TARGET_SHA_NO_BYPASS = "%s"' % hashes["no_bypass"], runner)
    with open(RUNNER, "w", encoding="utf-8") as handle:
        handle.write(runner)

    print("[OK] Installed engine %s (%s)" % (core_version, build_tag))
    print("     pristine archive : %s" % PRISTINE)
    print("     patched hashes   : full=%s no_bypass=%s" % (hashes["full"][:12], hashes["no_bypass"][:12]))


def main():
    parser = argparse.ArgumentParser(description="Validate/install a new official engine build.")
    parser.add_argument("--archive", required=True, help="new language_server.tar.gz")
    parser.add_argument("--core-version", help="engine version to record (e.g. 2.12.0)")
    parser.add_argument("--build-tag", help="Go build tag to record (auto-detected when omitted)")
    parser.add_argument("--apply", action="store_true", help="install after a clean compatibility check")
    parser.add_argument("--allow-patch-mismatch", action="store_true",
                        help="install even if some patch groups no longer match (review the report)")
    parser.add_argument("--report", help="write the compatibility report JSON here")
    args = parser.parse_args()

    if not os.path.isfile(args.archive):
        raise SystemExit("[ERROR] archive not found: %s" % args.archive)

    report = build_report(args.archive)
    tag = args.build_tag or "unknown"
    if not args.build_tag:
        version_spec = engine_probe.read_version_spec()
        tag = version_spec.get("CORE_BUILD_TAG", "unknown")

    print("[*] archive   : %s" % report["archive"])
    print("[*] sha256    : %s (%d bytes)" % (report["binary_sha256"], report["binary_size"]))
    print("[*] elf       : %s %s pie=%s" % (report["elf"]["machine"], report["elf"]["type"], report["elf"]["pie"]))
    print("[*] models    : %d  (+%d added, -%d removed)" % (
        report["catalog"]["count"], len(report["catalog_delta"]["added"]),
        len(report["catalog_delta"]["removed"])))
    if report["catalog_delta"]["added"]:
        print("    added     : %s" % ", ".join(report["catalog_delta"]["added"]))
    print("[*] patches   : %s" % ("compatible" if report["patches"]["compatible"]
                                  else "MISMATCH -> " + ", ".join(report["patches"]["failing_groups"])))

    if args.report:
        os.makedirs(os.path.dirname(os.path.abspath(args.report)), exist_ok=True)
        with open(args.report, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2, sort_keys=True)
            handle.write("\n")
        print("[OK] report -> %s" % args.report)

    if not args.apply:
        return
    if not args.core_version:
        raise SystemExit("[ERROR] --core-version is required with --apply")
    if not report["patches"]["compatible"] and not args.allow_patch_mismatch:
        print("[ERROR] refusing to install: patch groups no longer match.", file=sys.stderr)
        print("        Fix modules/patches/groups/** first, or re-run with --allow-patch-mismatch"
              " after reviewing the report.", file=sys.stderr)
        sys.exit(1)
    apply_upgrade(args.archive, report, args.core_version, tag)


if __name__ == "__main__":
    main()
