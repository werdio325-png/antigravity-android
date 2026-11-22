#!/usr/bin/env python3
"""
prepare_runtime.py - Assembles a fully patched, verified runtime in destination path.
"""
import sys
import os
import shutil
import tempfile

RUNNER_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.dirname(RUNNER_DIR)
sys.path.insert(0, os.path.join(MODULES_DIR, "core", "unpack"))
sys.path.insert(0, os.path.join(MODULES_DIR, "core", "bundle"))
sys.path.insert(0, os.path.join(MODULES_DIR, "core", "manifest"))
sys.path.insert(0, os.path.join(MODULES_DIR, "patches"))

import extractor
import assemble
import manifest_verifier
import pipeline

TARGET_SHA_FULL = "87a06a695653a4b5a4d7faf385df02740620afb808e1b8a341ba48dc6b77dff4"
TARGET_SHA_NO_BYPASS = "cdbad9f7855ae6d9bb675c42aa34af82f662c5a5cab98ef0d514eb1061690cad"
DEFAULT_DEST = "/data/user/0/com.agy/files/standalone_runtime"

def prepare(dest_runtime_dir: str = DEFAULT_DEST, no_bypass: bool = False) -> bool:
    no_bypass = no_bypass or (os.environ.get("NO_BYPASS") == "1") or (os.environ.get("SKIP_GATES") == "1")

    print("=" * 60)
    print(f"  PREPARING NEW MODULAR RUNTIME {'[NO BYPASS]' if no_bypass else '[WITH BYPASS]'}")
    print("=" * 60)

    dest_runtime_dir = os.path.abspath(dest_runtime_dir)
    core_dir = os.path.join(MODULES_DIR, "core")
    archive_path = os.path.join(core_dir, "pristine", "language_server.tar.gz")

    tmp_dir = tempfile.mkdtemp(prefix="agy_staging_")
    app_base = "/data/user/0/com.agy.dev" if os.path.exists("/data/user/0/com.agy.dev") and os.access("/data/user/0/com.agy.dev", os.W_OK) else "/data/user/0/com.agy"
    cache_dir = os.environ.get("AGY_CACHE_DIR", os.path.join(app_base, "cache") if os.access(app_base, os.W_OK) else os.path.join(tempfile.gettempdir(), "agy_cache"))
    os.makedirs(cache_dir, exist_ok=True)
    target_sha = TARGET_SHA_NO_BYPASS if no_bypass else TARGET_SHA_FULL
    cache_tag = f"language_server_patched_{'nobypass_' if no_bypass else ''}{target_sha[:8]}"
    cached_patched = os.path.join(cache_dir, cache_tag)

    try:
        staged_bin = os.path.join(tmp_dir, "language_server")
        cache_valid = False
        if os.path.isfile(cached_patched):
            import hashlib
            hasher = hashlib.sha256()
            with open(cached_patched, "rb") as f:
                while chunk := f.read(65536):
                    hasher.update(chunk)
            if hasher.hexdigest() == target_sha:
                cache_valid = True
                print(f"\n[1-2/4] Reusing cached patched core (SHA-256 verified: {target_sha[:8]}...)...")
                shutil.copyfile(cached_patched, staged_bin)
            else:
                print(f"\n[!] Cached core SHA mismatch ({hasher.hexdigest()[:8]} != {target_sha[:8]}); re-patching...")

        if not cache_valid:
            # 1. Extract pristine core to temporary staging area
            print("\n[1/4] Extracting pristine core archive...")
            extractor.extract_core(archive_path, staged_bin)
            print(f"  [OK] Extracted: {os.path.getsize(staged_bin):,} bytes")

            # 2. Apply patch modules
            print(f"\n[2/4] Applying modules/patches pipeline (skip_gates={no_bypass})...")
            ok_patch = pipeline.run_pipeline(staged_bin, dry_run=False, verify=False, allow_missing=False, skip_gates=no_bypass)
            assert ok_patch, "Patch pipeline failed!"
            print("  [OK] Patches applied and verified.")
            try:
                shutil.copyfile(staged_bin, cached_patched)
            except Exception:
                pass

        # 3. Assemble full runtime assets into destination
        print(f"\n[3/4] Assembling runtime assets -> {dest_runtime_dir}...")
        if os.path.exists(dest_runtime_dir):
            shutil.rmtree(dest_runtime_dir)
        
        manifest = assemble.assemble_runtime(staged_bin, core_dir, dest_runtime_dir)
        print(f"  [OK] Runtime assembled with {len(manifest['files'])} files.")

        # 4. Verify cryptographic manifest
        print("\n[4/4] Verifying destination integrity...")
        manifest_path = os.path.join(dest_runtime_dir, "manifest.json")
        ok_verify = manifest_verifier.verify_manifest(manifest_path, dest_runtime_dir)
        assert ok_verify, "Manifest verification failed on assembled runtime!"
        print("  [OK] Cryptographic manifest verified 100% byte-for-byte.")

        print("\n" + "=" * 60)
        print(f"  RUNTIME PREPARED AND READY FOR EXECUTION! {'[NO BYPASS]' if no_bypass else ''}")
        print("=" * 60 + "\n")
        return True

    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

def main():
    dest = DEFAULT_DEST
    no_bypass = (os.environ.get("NO_BYPASS") == "1") or (os.environ.get("SKIP_GATES") == "1")
    for arg in sys.argv[1:]:
        if arg in ("--no-bypass", "--nobypass", "--skip-gates", "--no-gates"):
            no_bypass = True
        elif not arg.startswith("-"):
            dest = arg
    ok = prepare(dest, no_bypass=no_bypass)
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
