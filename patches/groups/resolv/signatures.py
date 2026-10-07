"""Literals for the resolver path rewrite (verbatim from original).

    /etc/resolv.conf   (16 bytes)  ->  etc//resolv.conf  (16 bytes)

Both strings are length 16, so the replacement is byte-for-byte in place and
non NUL-terminated neighbours stay valid (Go uses explicit lengths here).
"""

TARGET = b"/etc/resolv.conf"
REPLACEMENT = b"etc//resolv.conf"
EXPECTED = 2
ADJACENT_REJECT = b"/.\\"

assert len(TARGET) == 16
assert len(REPLACEMENT) == 16
