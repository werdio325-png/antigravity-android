"""Shared CLI dispatch for standalone group entry points."""

import json
import sys

from . import module_base


def main(run_fn, selftest=None, usage=None, as_json=False, argv=None):
    """Parse group argv, dispatch ``--selftest``, and call ``run_fn``.

    ``run_fn`` has the group ``run(target_path, ...)`` signature. Returns the
    process exit status.
    """
    if argv is None:
        argv = sys.argv[1:]
    if selftest is not None and "--selftest" in argv:
        return 0 if selftest() else 1
    if not argv:
        print(usage or "Usage: <module> <binary> [--verify|--dry-run|--allow-missing]")
        return 1
    opts = module_base.parse_group_argv(argv)
    ok, rep = run_fn(argv[0], **opts)
    if as_json:
        print(json.dumps(rep, indent=2))
    else:
        print(rep)
    return 0 if ok else 1
