"""Shared scaffolding for group modules.

Every group's ``run()`` follows the same shape: build a report, read the
target, verify or patch, then either write or report. These helpers keep the
per-group module short while preserving the exact original behavior.
"""

import os

from . import report as _report


def new_report(group_id, group, file, description, **extra):
    return _report.make_report(group_id, group, file, description, **extra)


def read_target(target_path, report):
    """Read the target into a bytearray.

    On a missing target, append the standard message to ``report`` and return
    None so the caller can fail closed.
    """
    if not os.path.exists(target_path):
        report["messages"].append("target not found: %s" % target_path)
        return None
    with open(target_path, "rb") as f:
        return bytearray(f.read())


def run_verify(report, ok, tag, log, detail=""):
    """Record a verify outcome and emit the standard log line."""
    report["state"] = "verified" if ok else "mismatch"
    report["messages"].append("verify " + ("OK" if ok else "FAIL"))
    log("[%s] verify: %s%s" % (tag, "OK" if ok else "FAIL", detail))
    return ok


def run_already(report, tag, log, message="already patched", log_message=None,
                detail=""):
    """Record the idempotent 'nothing to do' outcome."""
    report["state"] = "already"
    report["changed"] = 0
    report["messages"].append(message)
    if log_message is None:
        log_message = message
    log("[%s] %s%s" % (tag, log_message, detail))
    return True


def run_missing(report, allow_missing, tag, log, message, state=None):
    """Handle a completely absent signature.

    Returns True if the caller may continue (allow_missing), False to fail
    closed. Fail-closed appends the message and logs an error.
    """
    if allow_missing:
        if state is not None:
            report["state"] = state
        report["messages"].append("SKIP: " + message)
        log("[%s] SKIP: %s" % (tag, message))
        return True
    report["messages"].append(message)
    log("[%s] ERROR: %s" % (tag, message))
    return False


def commit_write(target_path, data, log, tag, message):
    with open(target_path, "wb") as f:
        f.write(data)
    log("[%s] %s" % (tag, message))


def parse_group_argv(argv):
    """Parse the flags shared by every standalone group entry point."""
    return {
        "verify": "--verify" in argv,
        "dry_run": "--dry-run" in argv,
        "allow_missing": "--allow-missing" in argv,
    }
