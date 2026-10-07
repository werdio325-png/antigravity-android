"""Pure analysis for the resolver path rewrite."""

from groups.resolv import signatures as sig

TARGET = sig.TARGET
REPLACEMENT = sig.REPLACEMENT
ADJACENT_REJECT = sig.ADJACENT_REJECT


def analyze(data):
    """Return (clean_offsets, rejected_offsets, replacement_offsets)."""
    clean = []
    rejected = []
    pos = 0
    while True:
        pos = data.find(TARGET, pos)
        if pos < 0:
            break
        prev_b = data[pos - 1] if pos > 0 else None
        next_b = data[pos + len(TARGET)] if pos + len(TARGET) < len(data) else None
        adjacent = (prev_b is not None and bytes([prev_b]) in ADJACENT_REJECT) or (
            next_b is not None and bytes([next_b]) in ADJACENT_REJECT)
        (rejected if adjacent else clean).append(pos)
        pos += len(TARGET)
    repl = []
    pos = 0
    while True:
        pos = data.find(REPLACEMENT, pos)
        if pos < 0:
            break
        repl.append(pos)
        pos += len(REPLACEMENT)
    return clean, rejected, repl


def apply_inplace(data):
    """Patch a bytearray in place; return (clean_count, rejected_count)."""
    clean, rejected, _ = analyze(data)
    for pos in clean:
        data[pos:pos + len(REPLACEMENT)] = REPLACEMENT
    return len(clean), len(rejected)
