"""Minimal ELF64-LE AArch64 header/section inspection."""

import struct

ELF_MAGIC = b"\x7fELF"
EM_AARCH64 = 183


def _valid_elf64(data, base):
    if base + 64 > len(data):
        return False
    if data[base:base + 4] != ELF_MAGIC or data[base + 4] != 2 or data[base + 5] != 1:
        return False
    e_type = struct.unpack_from("<H", data, base + 16)[0]
    e_machine = struct.unpack_from("<H", data, base + 18)[0]
    if e_machine != EM_AARCH64 or e_type not in (2, 3):
        return False
    e_shoff = struct.unpack_from("<Q", data, base + 40)[0]
    e_shentsize = struct.unpack_from("<H", data, base + 58)[0]
    e_shnum = struct.unpack_from("<H", data, base + 60)[0]
    e_shstrndx = struct.unpack_from("<H", data, base + 62)[0]
    if e_shoff == 0 or e_shentsize != 64 or e_shnum == 0 or e_shnum > 4096:
        return False
    if e_shstrndx >= e_shnum:
        return False
    table_end = base + e_shoff + e_shnum * e_shentsize
    if table_end > len(data):
        return False
    return True


def find_elf_headers(data):
    bases = []
    i = 0
    while True:
        i = data.find(ELF_MAGIC, i)
        if i < 0:
            break
        if _valid_elf64(data, i):
            bases.append(i)
        i += 4
    return bases


def parse_text_section(data, base):
    """Return (file_offset, size) of `.text` for the ELF at `base`, else None."""
    e_shoff = struct.unpack_from("<Q", data, base + 40)[0]
    e_shentsize = struct.unpack_from("<H", data, base + 58)[0]
    e_shnum = struct.unpack_from("<H", data, base + 60)[0]
    e_shstrndx = struct.unpack_from("<H", data, base + 62)[0]

    def sh_at(i):
        return base + e_shoff + i * e_shentsize

    sh = data[sh_at(e_shstrndx):sh_at(e_shstrndx + 1)]
    shstr_off, shstr_size = struct.unpack_from("<QQ", sh, 24)
    shstr = data[base + shstr_off:base + shstr_off + shstr_size]
    for i in range(e_shnum):
        s = data[sh_at(i):sh_at(i + 1)]
        sh_name = struct.unpack_from("<I", s, 0)[0]
        sh_offset, sh_size = struct.unpack_from("<QQ", s, 24)
        end = shstr.find(b"\x00", sh_name)
        if end == -1:
            continue
        if shstr[sh_name:end] == b".text":
            return base + sh_offset, sh_size
    return None
