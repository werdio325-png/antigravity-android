"""Deterministic orchestrator for the Antigravity core patch groups.

Runs every patch group against a *staging* copy of the core (never the
original), records before/after sha256 and writes ``runtime.manifest.json``.

Groups (order matters for the manifest only; the byte ranges are disjoint):

    eligibility  -> groups/gates
    seccomp      -> groups/syscalls
    resolv       -> groups/resolv
    auth         -> groups/auth

Flags:
    --verify          check the staged binary is fully patched; write nothing.
    --dry-run         compute the patches and report; write nothing.
    --allow-missing   treat an absent signature as a skip instead of an error.
                      OFF by default => fail-closed.
    --manifest PATH   manifest output path (default <binary dir>/runtime.manifest.json).
    --no-manifest     do not write the manifest.

Exit status is non-zero if any group fails (or, with --verify, any group is
not verified).
"""

import json
import os
import sys
import time

from . import hash as _hash
from . import loader as _loader
from . import manifest as _manifest

GROUP_MODULES = [
    ("eligibility", "gates"),
    ("seccomp", "syscalls"),
    ("resolv", "resolv"),
    # The core's OAuth success URL is left intact so the browser shows the real
    # "authentication successful" page, which then deep-links to the app. The
    # app registers antigravity://oauth-success for that (see the manifest).
]

# Make ``import patch_lib`` / ``groups`` work when this file is run as a script
# (sys.path[0] is already the patches dir in that case, but be explicit).
PATCHES_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if PATCHES_DIR not in sys.path:
    sys.path.insert(0, PATCHES_DIR)


def run_pipeline(target_path, verify=False, dry_run=False, allow_missing=False,
                 manifest_path=None, write_manifest=True, skip_gates=False, log=print):
    if not os.path.exists(target_path):
        log("[runner] ERROR: target not found: %s" % target_path)
        return 1

    target_path = os.path.abspath(target_path)
    log("=== [runner] %s ===" % target_path)
    sha_before = _hash.sha256_file(target_path)
    size = os.path.getsize(target_path)
    with open(target_path, "rb") as f:
        blob = f.read()
    version = _manifest.extract_core_version(blob)
    log("[runner] size=%d sha256=%s" % (size, sha_before))
    log("[runner] core version: %s" % version)
    if skip_gates:
        log("[runner] region gates bypass disabled (skipping eligibility group)")

    active_groups = [
        (gid, name) for gid, name in GROUP_MODULES
        if not (skip_gates and gid == "eligibility")
    ]

    groups = []
    all_ok = True
    for gid, name in active_groups:
        mod = _loader.load_group(name)
        log("[runner] -> %s (%s)" % (gid, name))
        try:
            ok, rep = mod.run(target_path, verify=verify, dry_run=dry_run,
                              allow_missing=allow_missing, log=log)
        except Exception as exc:  # noqa: BLE001 - fail-closed
            ok, rep = False, {"id": gid, "group": gid, "file": name,
                              "state": "exception", "messages": [repr(exc)]}
            log("[runner] EXCEPTION in %s: %r" % (gid, exc))
        rep.setdefault("id", gid)
        rep.setdefault("group", gid)
        rep.setdefault("file", name)
        groups.append(rep)
        if not ok:
            all_ok = False
        log("[runner] <- %s: %s" % (gid, rep.get("state", "?")))

    sha_after = _hash.sha256_file(target_path)
    log("[runner] sha256 before=%s" % sha_before)
    log("[runner] sha256 after =%s" % sha_after)

    manifest = {
        "schema": 1,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "core": {
            "path": target_path,
            "version": version,
            "size": size,
            "sha256_initial": sha_before,
            "sha256_final": sha_after,
        },
        "mode": {"verify": verify, "dry_run": dry_run, "allow_missing": allow_missing},
        "groups": groups,
        "ok": all_ok,
    }

    if write_manifest and not verify:
        if manifest_path is None:
            manifest_path = os.path.join(os.path.dirname(target_path),
                                         "runtime.manifest.json")
        write = True
        if os.path.exists(manifest_path):
            try:
                with open(manifest_path, "r") as f:
                    old = json.load(f)
                if _manifest._stable(old) == _manifest._stable(manifest):
                    write = False
                    log("[runner] manifest unchanged: %s" % manifest_path)
            except Exception:
                write = True
        if write:
            tmp = manifest_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(manifest, f, indent=2, sort_keys=True)
            os.replace(tmp, manifest_path)
            log("[runner] wrote manifest: %s" % manifest_path)

    log("=== [runner] %s ===" % ("OK" if all_ok else "FAILED"))
    return 0 if all_ok else 1


def main(argv):
    if not argv or argv[0].startswith("-"):
        print(__doc__)
        return 2
    target = argv[0]
    rest = argv[1:]
    manifest_path = None
    if "--manifest" in rest:
        i = rest.index("--manifest")
        if i + 1 >= len(rest):
            print("--manifest requires a path")
            return 2
        manifest_path = rest[i + 1]
    return run_pipeline(
        target,
        verify="--verify" in rest,
        dry_run="--dry-run" in rest,
        allow_missing="--allow-missing" in rest,
        skip_gates="--skip-gates" in rest or "--no-gates" in rest,
        manifest_path=manifest_path,
        write_manifest="--no-manifest" not in rest,
    )
