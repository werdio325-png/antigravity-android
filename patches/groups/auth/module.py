"""OAuth success URL patch.

Replace the OAuth success URL with an `antigravity://` deep link so Android can
return to the app from the Custom Tab.

Idempotent; `--verify`; `--selftest` (pure, no binary).
"""

import sys

from patch_lib import cli as _cli
from patch_lib import module_base as base
from patch_lib.report import hex_list
from groups.auth import signatures as sig
from groups.auth.analyze import analyze
from groups.auth.selftest import _selftest

GROUP_ID = "auth"
GROUP = "auth"
FILE = "auth"
DESCRIPTION = "OAuth success URL -> antigravity:// deep link."
TAG = "patch_auth"
EXPECTED = sig.EXPECTED
DO_NOT_PATCH = sig.DO_NOT_PATCH


def run(target_path, verify=False, dry_run=False, allow_missing=False, log=print):
    report = base.new_report(
        GROUP_ID, GROUP, FILE, DESCRIPTION,
        expected_counts={"target": EXPECTED},
        do_not_patch=[s.decode("latin1") for s in DO_NOT_PATCH],
        offsets={})

    data = base.read_target(target_path, report)
    if data is None:
        return False, report

    targets, repl, missing = analyze(data)
    report["observed_counts"] = {
        "target": len(targets), "replacement": len(repl), "guards_missing": len(missing)}
    report["offsets"] = {
        "target": hex_list(targets), "replacement": hex_list(repl)}

    if missing:
        msg = "do_not_patch string(s) missing: %r" % [m.decode("latin1") for m in missing]
        report["messages"].append(msg)
        log("[patch_auth] ERROR: %s" % msg)
        return False, report

    if verify:
        ok = len(targets) == 0 and len(repl) == EXPECTED
        return base.run_verify(
            report, ok, TAG, log,
            detail=" (target=%d replacement=%d)" % (len(targets), len(repl))), report

    if len(targets) == 0 and len(repl) == EXPECTED:
        return base.run_already(report, TAG, log), report

    if len(targets) == 0 and len(repl) == 0 and allow_missing:
        return base.run_missing(
            report, True, TAG, log, "auth-success signature not found",
            state="skipped"), report

    if len(targets) != EXPECTED:
        report["messages"].append(
            "unexpected target count %d (expected %d)" % (len(targets), EXPECTED))
        log("[patch_auth] ERROR: %s" % report["messages"][-1])
        return False, report

    for pos in targets:
        data[pos:pos + len(sig.REPLACEMENT)] = sig.REPLACEMENT

    # post-condition: guards intact
    post_missing = [s for s in DO_NOT_PATCH if s not in bytes(data)]
    if post_missing:
        report["messages"].append("post-patch guard violation: %r" % post_missing)
        log("[patch_auth] ERROR: %s" % report["messages"][-1])
        return False, report

    report["changed"] = len(targets)
    report["state"] = "dry-run" if dry_run else "patched"
    if dry_run:
        log("[patch_auth] dry-run: %d site(s) would be patched" % len(targets))
        return True, report

    base.commit_write(target_path, data, log, TAG,
                      "patched %d site(s)" % len(targets))
    return True, report


if __name__ == "__main__":
    sys.exit(_cli.main(run, selftest=_selftest,
                       usage="Usage: python3 -m groups.auth.module <binary> "
                             "[--verify|--dry-run|--allow-missing|--selftest]"))
