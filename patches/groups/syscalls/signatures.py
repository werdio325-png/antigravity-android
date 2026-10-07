"""Constants and targets for the Android-seccomp syscall retarget patch.

Android's seccomp policy TRAPs `faccessat2` (439) and `fchmodat2` (452),
killing the process with SIGSYS before Go can fall back. Replace the
syscall-number constant inside the Go function body only:

    syscall.faccessat2 : MOVZ X0, #439 (0xd28036e0) -> #48 (0xd2800600)
    syscall.fchmodat2  : MOVZ X0, #452 (0xd2803880) -> #53 (0xd28006a0)
"""

FACCESSAT2, FACCESSAT = 439, 48
FCHMODAT2, FCHMODAT = 452, 53

KNOWN_FUNC = "runtime.memmove"
TARGETS = (
    ("syscall.faccessat2", FACCESSAT2, FACCESSAT),
    ("syscall.fchmodat2", FCHMODAT2, FCHMODAT),
)
