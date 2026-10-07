"""Byte signatures for the eligibility gate patch (verbatim from original).

HARD RULE: never touch the `w2` shape (channelz `(*ChannelTrace).clear`).
Both anchors are register specific (w1 / w3), so the w2 site cannot match.
"""

import re

# --- CLI gate -------------------------------------------------------------
# ldrb w1, [x0, #8] ; tbnz w1, #0 ; orr x1, xzr, x1 ; strb w1, [x0, #8]
_CLI_TAIL = rb"\xe1\x03\x40\xb2\x01\x20\x00\x39"
SIG_CLI = re.compile(rb"(\x01\x20\x40\x39)(.{3}\x37)" + _CLI_TAIL, re.S)
PATCHED_CLI = re.compile(rb"(\x21\x00\x80\x52)(.{3}\x37)" + _CLI_TAIL, re.S)
REPL_CLI = b"\x21\x00\x80\x52"  # mov w1, #1
# note: the tail (`orr x1,xzr,x1 ; strb w1,[x0,#8]`) makes the already-patched
# signature specific; `21 00 80 52` alone has unrelated matches in the binary.

# --- Manager auth gate ----------------------------------------------------
# ldrb w3,[x0,#8] ; tbz w3,#0,... ; token setup (ldp/ldr + ... + stp)
_ARM64_TBZ_W3_BIT0 = rb"[\x03\x23\x43\x63\x83\xa3\xc3\xe3]..\x36"
_ARM64_TOKEN_SETUP = rb"(?:....){1,2}\x03\x10\x06\xa9"
SIG_MGR = re.compile(rb"(\x03\x20\x40\x39" + _ARM64_TBZ_W3_BIT0 + _ARM64_TOKEN_SETUP + rb")", re.S)
# mov w3,#1 ; strb w3,[x0,#8] ; token setup
PATCHED_MGR = re.compile(rb"(\x23\x00\x80\x52\x03\x20\x00\x39" + _ARM64_TOKEN_SETUP + rb")", re.S)
REPL_MGR = b"\x23\x00\x80\x52\x03\x20\x00\x39"  # mov w3,#1 ; strb w3,[x0,#8]
