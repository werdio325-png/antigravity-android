"""Eligibility gate patch.

Strategy (adaptive, signature based, no fixed offsets):

  1. CLI gate   : `ldrb w1, [x0, #8]` (01 20 40 39) followed by `tbnz w1, #0, ...`
                  -> replace the load with `mov w1, #1` (21 00 80 52).
  2. Manager auth: `authclient.(*EnterpriseAuthValidator).Validate` gate
                  : `ldrb w3, [x0, #8]` (03 20 40 39) + `tbz w3, #0, ...`
                    + token setup
                  -> replace load+test with `mov w3, #1 ; strb w3, [x0, #8]`
                     (23 00 80 52 03 20 00 39).

Fail-closed: the number of raw + already-patched sites must equal the
expected counts (cli=2, manager=1). Any deviation aborts without writing.

Idempotent: a second run finds zero raw sites and the already-patched sites
and writes nothing.
"""

import sys

from patch_lib import cli as _cli
from patch_lib import module_base as base
from patch_lib.report import hex_list
from groups.gates import signatures as sig
from groups.gates.analyze import analyze

GROUP_ID = "eligibility"
GROUP = "eligibility"
FILE = "gates"
DESCRIPTION = "Force eligibility/auth gate bits (CLI + EnterpriseAuthValidator)."
EXPECTED_COUNTS = {"cli": 2, "manager": 1}
TAG = "patch_gates"


def run(target_path, verify=False, dry_run=False, allow_missing=False, log=print):
    """Apply / check the eligibility gate patch. Returns (ok, report)."""
    report = base.new_report(
        GROUP_ID, GROUP, FILE, DESCRIPTION,
        expected_counts=EXPECTED_COUNTS, offsets={})

    data = base.read_target(target_path, report)
    if data is None:
        return False, report

    st = analyze(data)
    summary = {
        "cli": len(st["raw_cli"]) + len(st["pat_cli"]),
        "manager": len(st["raw_mgr"]) + len(st["pat_mgr"]),
    }
    report["observed_counts"] = {
        "cli": {"raw": len(st["raw_cli"]), "patched": len(st["pat_cli"])},
        "manager": {"raw": len(st["raw_mgr"]), "patched": len(st["pat_mgr"])},
    }
    report["offsets"] = {
        "cli_raw": hex_list(st["raw_cli"]),
        "cli_patched": hex_list(st["pat_cli"]),
        "manager_raw": hex_list(st["raw_mgr"]),
        "manager_patched": hex_list(st["pat_mgr"]),
    }

    for shape in ("cli", "manager"):
        total = summary[shape]
        exp = EXPECTED_COUNTS[shape]
        if total == 0:
            msg = "missing %s gate signature (expected %d)" % (shape, exp)
            if not base.run_missing(report, allow_missing, TAG, log, msg):
                return False, report
        elif total != exp:
            report["messages"].append(
                "unexpected %s gate count %d (expected %d)" % (shape, total, exp)
            )
            log("[patch_gates] ERROR: %s" % report["messages"][-1])
            return False, report

    if verify:
        ok = (
            len(st["raw_cli"]) == 0
            and len(st["pat_cli"]) == EXPECTED_COUNTS["cli"]
            and len(st["raw_mgr"]) == 0
            and len(st["pat_mgr"]) == EXPECTED_COUNTS["manager"]
        )
        return base.run_verify(report, ok, TAG, log), report

    n_changes = len(st["raw_cli"]) + len(st["raw_mgr"])
    if n_changes == 0:
        return base.run_already(
            report, TAG, log,
            message="already patched, nothing to do",
            log_message="already patched",
            detail=" (cli=%d, manager=%d)" %
            (len(st["pat_cli"]), len(st["pat_mgr"]))), report

    for off in st["raw_cli"]:
        data[off:off + 4] = sig.REPL_CLI
    for off in st["raw_mgr"]:
        data[off:off + 8] = sig.REPL_MGR

    report["state"] = "dry-run" if dry_run else "patched"
    report["changed"] = n_changes
    report["messages"].append("would patch" if dry_run else "patched")
    if dry_run:
        log("[patch_gates] dry-run: %d site(s) would be patched" % n_changes)
        return True, report

    base.commit_write(
        target_path, data, log, TAG,
        "patched cli=%d manager=%d" % (len(st["raw_cli"]), len(st["raw_mgr"])))
    return True, report


if __name__ == "__main__":
    sys.exit(_cli.main(run))
