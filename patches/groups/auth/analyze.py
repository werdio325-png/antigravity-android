"""Pure analysis for the OAuth success URL rewrite."""

from groups.auth import signatures as sig

TARGET = sig.TARGET
REPLACEMENT = sig.REPLACEMENT
DO_NOT_PATCH = sig.DO_NOT_PATCH


def analyze(data):
    """Return (target_offsets, replacement_offsets, missing_guards)."""
    targets = []
    pos = 0
    while True:
        pos = data.find(TARGET, pos)
        if pos < 0:
            break
        targets.append(pos)
        pos += len(TARGET)
    repl = []
    pos = 0
    while True:
        pos = data.find(REPLACEMENT, pos)
        if pos < 0:
            break
        repl.append(pos)
        pos += len(REPLACEMENT)
    missing = [s for s in DO_NOT_PATCH if s not in data]
    return targets, repl, missing


def apply_inplace(data):
    targets, _repl, missing = analyze(data)
    assert not missing, "do_not_patch string(s) already missing: %r" % missing
    for pos in targets:
        data[pos:pos + len(REPLACEMENT)] = REPLACEMENT
    return len(targets)
