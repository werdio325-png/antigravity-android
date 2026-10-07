"""In-memory self-test for the OAuth success URL rewrite (no binary needed)."""

from groups.auth import signatures as sig
from groups.auth.analyze import analyze, apply_inplace

TARGET = sig.TARGET
REPLACEMENT = sig.REPLACEMENT
DO_NOT_PATCH = sig.DO_NOT_PATCH


def _selftest():
    sample = bytearray(b"xx" + TARGET + b"yy" + b" | ".join(DO_NOT_PATCH))
    targets, repl, missing = analyze(sample)
    assert targets and not repl and not missing, (targets, repl, missing)
    n = apply_inplace(sample)
    assert n == 1
    assert TARGET not in bytes(sample)
    assert sample.count(REPLACEMENT) == 1
    for s in DO_NOT_PATCH:
        assert s in bytes(sample), s
    n2 = apply_inplace(sample)
    assert n2 == 0
    assert sample.count(REPLACEMENT) == 1
    # guard violation must raise / be detected
    broken = bytearray(DO_NOT_PATCH[0] + TARGET)
    _t, _r, bad = analyze(bytes(broken))
    assert DO_NOT_PATCH[1] in bad
    print("[patch_auth] selftest OK")
    return True
