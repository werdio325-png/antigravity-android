"""Pure ELF discovery for the syscall retarget patch."""

from patch_lib import elf


def analyze(data):
    """Return the list of valid AArch64 ELF bases in ``data``."""
    return elf.find_elf_headers(data)
