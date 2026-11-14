#!/usr/bin/env python3
"""
engine_probe.py - Reports what the embedded Antigravity engine actually is.

Output (engine_info.json) is bundled into the APK as assets/runtime/engine_info.json
and read by the Android host, so the app can show the real engine version and the
real model catalogue instead of a hardcoded list.

Usage:
  python3 modules/engine/engine_probe.py --archive modules/core/pristine/language_server.tar.gz \
      --out build/engine_info.json
  python3 modules/engine/engine_probe.py --binary /path/to/language_server --no-patch-scan
"""
import argparse
import hashlib
import json
import os
import re
import struct
import sys
import tarfile
import tempfile
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MODULES_DIR = os.path.dirname(SCRIPT_DIR)
PROJECT_ROOT = os.path.dirname(MODULES_DIR)
sys.path.insert(0, SCRIPT_DIR)

import catalog  # noqa: E402

BUILD_TAG_RE = re.compile(rb"(go1\.\d+(?:\.\d+)?-\d{8}(?:-RC\d+)?|[A-Za-z0-9._-]+-RC\d{2})")


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def elf_info(path: str) -> dict:
    """Minimal ELF64 header read: class, endianness, type, machine, PIE."""
    with open(path, "rb") as handle:
        header = handle.read(64)
    if len(header) < 64 or header[:4] != b"\x7fELF":
        return {"valid": False, "reason": "not an ELF file"}
    ei_class = header[4]
    ei_data = header[5]
    e_type, e_machine = struct.unpack_from("<HH", header, 16)
    machine_name = {183: "aarch64", 62: "x86_64"}.get(e_machine, "unknown(%d)" % e_machine)
    return {
        "valid": ei_class == 2 and ei_data == 1,
        "class": "ELF64" if ei_class == 2 else "ELF32",
        "endianness": "little" if ei_data == 1 else "big",
        "type": {2: "ET_EXEC", 3: "ET_DYN"}.get(e_type, "unknown(%d)" % e_type),
        "machine": machine_name,
        "aarch64": e_machine == 183,
        "pie": e_type == 3,
    }


def read_version_spec(project_root: str = PROJECT_ROOT) -> dict:
    spec = os.path.join(project_root, "modules", "core", "spec", "version.env")
    out = {}
    if os.path.isfile(spec):
        with open(spec, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                out[key.strip()] = value.strip()
    return out


def extract_from_archive(archive: str) -> str:
    """Extract language_server from a .tar.gz into a temp dir; return its path."""
    tmp = tempfile.mkdtemp(prefix="continuum_engine_")
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar.getmembers():
            if member.isfile() and os.path.basename(member.name) == "language_server":
                member.name = "language_server"
                tar.extract(member, tmp)
                return os.path.join(tmp, "language_server")
    raise SystemExit("[ERROR] language_server not found in %s" % archive)


def patch_compatibility(binary_bytes: bytes, project_root: str = PROJECT_ROOT) -> dict:
    """
    Read-only scan of the binary patch groups against this engine build.

    The fork's patch modules are signature based and fail closed; this tells the
    user which groups still match a new official engine build before shipping it.
    """
    patches_dir = os.path.join(project_root, "modules", "patches")
    manifest_path = os.path.join(patches_dir, "spec", "patch_manifest.json")
    groups = []
    with open(manifest_path, encoding="utf-8") as handle:
        spec = json.load(handle)

    sys.path.insert(0, patches_dir)
    import pipeline  # noqa: F401  (sets up engine/ and group module paths)

    module_map = {
        "gates": ["cli_gate", "enterprise_auth"],
        "syscalls": ["faccessat", "fchmodat"],
        "sigill": ["sigill_handler"],
        "resolv": ["resolv_redirect"],
        "failfast": ["crash_suppressor"],
        "pty_shell": ["pty_shell"],
    }
    # Every patch group implements the same (ok, info) apply() contract; a dry
    # run reports signature matches without touching the binary.
    mutable = bytearray(binary_bytes)
    for group in spec.get("groups", []):
        results = []
        for mod_name in module_map.get(group["name"], []):
            try:
                module = getattr(pipeline, mod_name)
                ok, info = module.apply(mutable, verify=False, dry_run=True, allow_missing=True)
                status = info.get("status", "unknown")
                matched = ok and status not in ("skipped_missing", "missing_signature")
                results.append({
                    "module": mod_name,
                    "status": status,
                    "expected": info.get("expected"),
                    "raw": info.get("raw_count"),
                    "patched": info.get("patched_count"),
                    "ok": matched,
                })
            except Exception as exc:  # a missing signature is data, not a crash
                results.append({"module": mod_name, "error": str(exc), "ok": False})
        groups.append({
            "group": group["name"],
            "description": group.get("description", ""),
            "ok": all(r.get("ok") for r in results),
            "modules": results,
        })

    failing = [g["group"] for g in groups if not g["ok"]]
    return {
        "compatible": not failing,
        "failing_groups": failing,
        "groups": groups,
    }


def probe(archive: str = None, binary: str = None, patch_scan: bool = True) -> dict:
    if not archive and not binary:
        archive = os.path.join(PROJECT_ROOT, "modules", "core", "pristine", "language_server.tar.gz")
    if archive and not os.path.isfile(archive):
        raise SystemExit("[ERROR] archive not found: %s" % archive)

    binary_path = binary or extract_from_archive(archive)
    size = os.path.getsize(binary_path)
    with open(binary_path, "rb") as handle:
        data = handle.read()

    spec = read_version_spec()
    build_tags = {}
    for match in BUILD_TAG_RE.finditer(data):
        tag = match.group(1).decode("ascii", "ignore")
        build_tags[tag] = build_tags.get(tag, 0) + 1
    build_tag = max(build_tags, key=build_tags.get) if build_tags else "unknown"

    models = catalog.extract_models(data)
    families = sorted({m["family"] for m in models})

    info = {
        "schema": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "engine": {
            "name": spec.get("CORE_BIN_NAME", "language_server"),
            "version": spec.get("CORE_VERSION", "unknown"),
            "build_tag": build_tag,
            "sha256": sha256_file(binary_path),
            "size": size,
            "elf": elf_info(binary_path),
        },
        "catalog": {
            "source": "engine-binary",
            "availability": "server-decided",
            "generated_from_sha256": sha256_file(binary_path),
            "families": families,
            "model_count": len(models),
            "models": models,
        },
    }
    if patch_scan:
        info["patches"] = patch_compatibility(data)
    return info


def main():
    parser = argparse.ArgumentParser(description="Inspect the embedded Antigravity engine.")
    parser.add_argument("--archive", help="engine archive (tar.gz with language_server)")
    parser.add_argument("--binary", help="extracted language_server binary")
    parser.add_argument("--out", help="write JSON here (default: stdout)")
    parser.add_argument("--no-patch-scan", action="store_true", help="skip patch compatibility scan")
    parser.add_argument("--require-compatible", action="store_true",
                        help="exit 1 if any patch group no longer matches this engine build")
    args = parser.parse_args()

    info = probe(args.archive, args.binary, patch_scan=not args.no_patch_scan)
    text = json.dumps(info, indent=2, sort_keys=True) + "\n"
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text)
        print("[OK] engine_info.json -> %s" % args.out)
        print("     engine   : %s (%s)" % (info["engine"]["version"], info["engine"]["build_tag"]))
        print("     models   : %d (%s)" % (info["catalog"]["model_count"], ", ".join(info["catalog"]["families"])))
        if "patches" in info:
            print("     patches  : %s" % ("compatible" if info["patches"]["compatible"]
                                          else "MISMATCH " + ", ".join(info["patches"]["failing_groups"])))
    else:
        sys.stdout.write(text)

    if args.require_compatible and "patches" in info and not info["patches"]["compatible"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
