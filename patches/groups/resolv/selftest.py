"""In-memory self-test for the resolver path rewrite (no binary needed)."""

from groups.resolv import signatures as sig
from groups.resolv.analyze import analyze, apply_inplace

TARGET = sig.TARGET
REPLACEMENT = sig.REPLACEMENT


def _selftest():
    sample = (b"prefix " + TARGET + b"hostLookup" + b"x" + TARGET +
              b"tail" + b"/etc/resolv.conf.bak")
    data = bytearray(sample)
    clean, rejected, repl = analyze(data)
    assert len(clean) == 2, clean
    assert len(rejected) == 1, rejected  # the ".bak" longer path
    assert len(repl) == 0
    n_clean, n_rej = apply_inplace(data)
    assert n_clean == 2 and n_rej == 1
    assert data.count(REPLACEMENT) == 2
    assert data.count(TARGET) == 1  # only the rejected longer path survives
    # idempotent
    n2_clean, _ = apply_inplace(data)
    assert n2_clean == 0
    assert data.count(REPLACEMENT) == 2
    print("[patch_resolv] selftest OK")
    return True
