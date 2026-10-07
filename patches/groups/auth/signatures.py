"""Literals for the OAuth success URL rewrite (verbatim from original).

    https://antigravity.google/auth-success?app=%s   (46 bytes)
    antigravity://auth-success?app=%s + '#' * 13     (46 bytes)

Length preserving. Exactly one target is expected. A `do_not_patch` list
guards every other auth string (OAuth endpoints, callback path, ...); the
patch asserts those strings are still intact after the rewrite.
"""

TARGET = b"https://antigravity.google/auth-success?app=%s"
PREFIX = b"antigravity://auth-success?app=%s"
REPLACEMENT = PREFIX + b"#" * (46 - len(PREFIX))
EXPECTED = 1

DO_NOT_PATCH = [
    b"oauth-callback",
    b"accounts.google.com/o/oauth2/auth",
    b"oauth2.googleapis.com/token",
    b"oauth2.googleapis.com/device",
]

assert len(TARGET) == 46
assert len(REPLACEMENT) == 46
