"""Relative resolver path patch.

Make the Go core read a relative resolver config instead of the
Android-unreachable `/etc/resolv.conf`.

Rules:
  * expect exactly 2 clean occurrences;
  * occurrences adjacent to `/`, `.` or `\\` are part of a longer path and
    are rejected (not patched, not counted);
  * idempotent: a patched file has 0 clean targets and 2 replacements;
  * fail-closed on any unexpected count.

`--selftest` runs pure in-memory checks (including the longer-path reject and
double-apply idempotency) and needs no binary.
"""

import sys

from patch_lib import cli as _cli
from patch_lib import module_base as base
from patch_lib.report import hex_list
from groups.resolv import signatures as sig
from groups.resolv.analyze import analyze
from groups.resolv.selftest import _selftest

GROUP_ID = "resolv"
GROUP = "resolv"
FILE = "resolv"
DESCRIPTION = "Relative resolver path; /etc/resolv.conf -> etc//resolv.conf."
TAG = "patch_resolv"
EXPECTED = sig.EXPECTED


def run(target_path, verify=False, dry_run=False, allow_missing=False, log=print):
    report = base.new_report(
        GROUP_ID, GROUP, FILE, DESCRIPTION,
        expected_counts={"clean": EXPECTED}, offsets={})

    data = base.read_target(target_path, report)
    if data is None:
        return False, report

    clean, rejected, repl = analyze(data)
    report["observed_counts"] = {
        "clean": len(clean), "rejected": len(rejected), "replacement": len(repl)}
    report["offsets"] = {
        "clean": hex_list(clean),
        "rejected": hex_list(rejected),
        "replacement": hex_list(repl),
    }

    if rejected:
        report["messages"].append(
            "rejected %d occurrence(s) adjacent to longer path" % len(rejected))

    if verify:
        ok = len(clean) == 0 and len(repl) == EXPECTED
        return base.run_verify(
            report, ok, TAG, log,
            detail=" (clean=%d replacement=%d)" % (len(clean), len(repl))), report

    if len(clean) == 0 and len(repl) == EXPECTED:
        return base.run_already(
            report, TAG, log,
            detail=" (replacement=%d)" % len(repl)), report

    if len(clean) == 0 and len(repl) == 0:
        if allow_missing:
            return base.run_missing(
                report, True, TAG, log, "no resolver signature",
                state="skipped"), report

    if len(clean) != EXPECTED:
        report["messages"].append(
            "unexpected clean count %d (expected %d)" % (len(clean), EXPECTED))
        log("[patch_resolv] ERROR: %s" % report["messages"][-1])
        return False, report

    for pos in clean:
        data[pos:pos + len(sig.REPLACEMENT)] = sig.REPLACEMENT

    report["changed"] = len(clean)
    report["state"] = "dry-run" if dry_run else "patched"
    if dry_run:
        log("[patch_resolv] dry-run: %d site(s) would be patched" % len(clean))
        return True, report

    base.commit_write(target_path, data, log, TAG,
                      "patched %d occurrence(s)" % len(clean))
    return True, report


if __name__ == "__main__":
    sys.exit(_cli.main(run, selftest=_selftest,
                       usage="Usage: python3 -m groups.resolv.module <binary> "
                             "[--verify|--dry-run|--allow-missing|--selftest]"))
