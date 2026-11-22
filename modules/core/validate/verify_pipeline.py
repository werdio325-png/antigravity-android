#!/usr/bin/env python3
"""
verify_pipeline.py - Comprehensive end-to-end verification pipeline for the new Core module.
Validates:
1. Declarative specifications and hashes
2. Unpack & extraction of pristine archive
3. ELF headers, 64-bit architecture (AArch64), and machine flags
4. Complete runtime directory assembly
5. Cryptographic manifest generation and verification
"""
import sys
import os
import tempfile
import shutil

CORE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(CORE_DIR, "unpack"))
sys.path.insert(0, os.path.join(CORE_DIR, "validate"))
sys.path.insert(0, os.path.join(CORE_DIR, "manifest"))
sys.path.insert(0, os.path.join(CORE_DIR, "bundle"))

import archive_detector
import extractor
import elf_header
import elf_arch
import elf_compat
import checksum_checker
import assemble
import manifest_verifier

def run_pipeline():
    print("=" * 60)
    print("  ANTIGRAVITY NEW CORE VERIFICATION PIPELINE (v2.34.0)")
    print("=" * 60)

    # 1. Check specs
    print("\n[Step 1/5] Verifying Core Specs and Archive Hash...")
    archive_path = os.path.join(CORE_DIR, "pristine", "language_server.tar.gz")
    assert os.path.isfile(archive_path), f"Missing archive: {archive_path}"
    
    expected_archive_sha = "6847c8e03731e801465a253cbb8c286ffa63c9ee4567dd7a8d5f26ccadaba390"
    checksum_checker.verify_checksum(archive_path, expected_archive_sha)
    print("  [OK] Pristine archive SHA-256 match confirmed.")

    # Detect archive format
    fmt = archive_detector.detect_type(archive_path)
    assert fmt == "tar_gz", f"Unexpected archive format: {fmt}"
    print(f"  [OK] Archive format detected: {fmt}")

    # 2. Extract into isolated sandbox
    tmp_dir = tempfile.mkdtemp(prefix="agy_core_val_")
    try:
        print("\n[Step 2/5] Extracting pristine language_server into clean sandbox...")
        staged_bin = os.path.join(tmp_dir, "staged", "language_server")
        extractor.extract_core(archive_path, staged_bin)
        size_bytes = os.path.getsize(staged_bin)
        print(f"  [OK] Extracted core binary: {size_bytes:,} bytes")

        # 3. ELF & Architecture Validation
        print("\n[Step 3/5] Validating ELF headers and AArch64 ABI...")
        elf_header.validate_elf_header(staged_bin)
        print("  [OK] ELF Magic: valid 64-bit Little-Endian")

        elf_arch.validate_aarch64(staged_bin)
        print("  [OK] Architecture: AArch64 (ARM64)")

        elf_type = elf_compat.validate_executable_type(staged_bin)
        print(f"  [OK] Executable Type: {elf_type}")

        # Validate loader
        loader_path = os.path.join(CORE_DIR, "glibc", "ld-linux-aarch64.so.1")
        elf_header.validate_elf_header(loader_path)
        elf_arch.validate_aarch64(loader_path)
        print("  [OK] Loader (ld-linux-aarch64.so.1): valid AArch64 dynamic linker")

        # 4. Assemble runtime
        print("\n[Step 4/5] Assembling full runtime package in sandbox...")
        rt_out = os.path.join(tmp_dir, "assets", "runtime")
        manifest = assemble.assemble_runtime(staged_bin, CORE_DIR, rt_out, dry_run=False)
        total_files = len(manifest["files"])
        print(f"  [OK] Assembled runtime with {total_files} tracked components.")

        # 5. Manifest verification
        print("\n[Step 5/5] Cryptographic verification against generated manifest.json...")
        manifest_file = os.path.join(rt_out, "manifest.json")
        ok = manifest_verifier.verify_manifest(manifest_file, rt_out)
        assert ok, "Manifest verification failed!"
        print("  [OK] All hashes match 100% byte-for-byte!")

        print("\n" + "=" * 60)
        print("  SUCCESS: NEW CORE MODULE FULLY VALIDATED AND READY!")
        print("=" * 60 + "\n")

    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)

if __name__ == "__main__":
    try:
        run_pipeline()
        sys.exit(0)
    except Exception as e:
        print(f"\n[FATAL] Pipeline failed: {e}", file=sys.stderr)
        sys.exit(1)
