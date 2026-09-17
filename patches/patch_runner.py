import os
import sys
import subprocess
import hashlib

def sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def run_pipeline(staging_core_path, enable_armv80=False, enable_bypass_region=False):
    print(f"=== [Patch Pipeline] Starting on: {staging_core_path} ===")
    if not os.path.exists(staging_core_path):
        print(f"Error: {staging_core_path} not found!")
        sys.exit(1)

    initial_hash = sha256(staging_core_path)
    print(f"[Pipeline] Initial SHA256: {initial_hash}")

    # 1. Снятие regional / eligibility gates (опционально по флагу)
    if enable_bypass_region:
        print("[Pipeline] 1. Applying eligibility screen patch (--bypass-region)...")
        from patch_gates import patch_eligibility
        patch_eligibility(staging_core_path)
    else:
        print("[Pipeline] 1. Regional eligibility patch skipped (use --bypass-region to enable).")

    # 2. DNS resolver patch (/etc/resolv.conf -> /sdcard/res.conf)
    print("[Pipeline] 2. Applying DNS resolver patch...")
    from patch_resolv import patch_resolv_conf
    patch_resolv_conf(staging_core_path)

    # 3. Android seccomp syscall bypass (faccessat2 -> faccessat)
    print("[Pipeline] 3. Applying seccomp syscall bypass patch...")
    from patch_syscalls import patch_syscalls
    patch_syscalls(staging_core_path)

    # 4. Auth success auto-close tab patch
    print("[Pipeline] 4. Applying auth-success auto-close patch...")
    from patch_auth import patch_auth_success
    patch_auth_success(staging_core_path)

    # 5. Опциональный патч ARMv8.0
    if enable_armv80:
        print("[Pipeline] 5. Applying ARMv8.0 compatibility patch (--armv8.0)...")
        from patch_armv80 import patch_armv80
        patch_armv80(staging_core_path)

    final_hash = sha256(staging_core_path)
    print(f"[Pipeline] Final SHA256: {final_hash}")
    print("=== [Patch Pipeline] Completed successfully! ===")
    return final_hash

if __name__ == "__main__":
    if len(sys.argv) < 2 or "--help" in sys.argv or "-h" in sys.argv:
        print("Usage: python3 patch_runner.py <staging_binary_path> [options]")
        print("Options:")
        print("  --bypass-region    Apply patch to bypass Google regional eligibility gates")
        print("  --armv8.0          Apply compatibility patch for ARMv8.0 CPUs without LSE")
        sys.exit(0 if ("--help" in sys.argv or "-h" in sys.argv) else 1)
    target = sys.argv[1]
    is_armv80 = "--armv8.0" in sys.argv
    is_bypass_region = ("--bypass-region" in sys.argv) or ("--unblock-region" in sys.argv)
    run_pipeline(target, enable_armv80=is_armv80, enable_bypass_region=is_bypass_region)
