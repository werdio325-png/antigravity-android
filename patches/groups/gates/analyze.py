"""Pure signature analysis for the eligibility gate patch."""

from groups.gates import signatures as sig


def analyze(data):
    """Return a dict with raw/patched site offsets. Pure, no IO."""
    raw_cli = [m.start() for m in sig.SIG_CLI.finditer(data)]
    raw_mgr = [m.start() for m in sig.SIG_MGR.finditer(data)]
    pat_cli = [m.start() for m in sig.PATCHED_CLI.finditer(data)]
    pat_mgr = [m.start() for m in sig.PATCHED_MGR.finditer(data)]
    return {
        "raw_cli": raw_cli,
        "raw_mgr": raw_mgr,
        "pat_cli": pat_cli,
        "pat_mgr": pat_mgr,
    }
