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

def run_pipeline(staging_core_path, enable_armv80=False, enable_bypass_region=False, web_dir=None):
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

    # 4.1 Token storage fast path patch (bypass D-Bus hangs unconditionally)
    print("[Pipeline] 4.1 Applying token storage instant bypass patch...")
    from patch_storage import patch_token_storage
    patch_token_storage(staging_core_path)

    # 4.2 Touch UI & model effort selector patch
    print("[Pipeline] 4.2 Applying touch UI & model effort selector patch...")
    from patch_touch import patch_touch_ui
    patch_touch_ui(web_dir=web_dir, core_binary_path=staging_core_path)

    # 4.3 Offline projects & discussions cache patch
    print("[Pipeline] 4.3 Applying offline cache & conversations persistence patch...")
    from patch_cache import patch_cache_pipeline
    patch_cache_pipeline(web_dir=web_dir)

    # 5. Опциональный патч ARMv8.0
    if enable_armv80:
        print("[Pipeline] 5. Applying ARMv8.0 compatibility patch (--armv8.0)...")
        from patch_armv80 import patch_armv80
        patch_armv80(staging_core_path)

    # 6. Опциональный патч Web UI
    if web_dir and os.path.isdir(web_dir):
        print(f"[Pipeline] 6. Applying Web UI patch from: {web_dir}...")
        from patch_web import patch_web
        patch_web(staging_core_path, web_dir)

    final_hash = sha256(staging_core_path)
    print(f"[Pipeline] Final SHA256: {final_hash}")
    print("=== [Patch Pipeline] Completed successfully! ===")
    return final_hash

if __name__ == "__main__":
    if len(sys.argv) < 2 or "--help" in sys.argv or "-h" in sys.argv:
        print("Usage: python3 patch_runner.py <staging_binary_path> [options]")
        print("Options:")
        print("  --no-bypass-region Disable Google regional eligibility patch")
        print("  --no-armv8.0       Disable ARMv8.0 compatibility patch")
        print("  --patch-web        Enable repacking web interface from web_ui/")
        print("  --web-dir <dir>    Custom web directory to repack into core")
        sys.exit(0 if ("--help" in sys.argv or "-h" in sys.argv) else 1)
    target = sys.argv[1]
    is_armv80 = "--no-armv8.0" not in sys.argv
    is_bypass_region = "--no-bypass-region" not in sys.argv
    
    web_directory = None
    if "--patch-web" in sys.argv:
        web_directory = "web_ui"
    for i, arg in enumerate(sys.argv):
        if arg == "--web-dir" and i + 1 < len(sys.argv):
            web_directory = sys.argv[i + 1]
            break

    run_pipeline(target, enable_armv80=is_armv80, enable_bypass_region=is_bypass_region, web_dir=web_directory)
