"""AArch64 instruction encoding and Go pclntab function resolution."""

import struct

PCLN_MAGICS = (b"\xf1\xff\xff\xff", b"\xf2\xff\xff\xff", b"\xf0\xff\xff\xff")


def movz_x0(imm):
    return struct.pack("<I", 0xD2800000 | (imm << 5) | 0)


def find_pclntab(data, base, region_end):
    """Locate the Go pclntab belonging to the ELF at `base`."""
    for magic in PCLN_MAGICS:
        pos = base
        while True:
            pos = data.find(magic, pos, region_end)
            if pos < 0:
                break
            if pos + 72 <= len(data):
                try:
                    f = struct.unpack_from("<IBBBBQQQQQQQQ", data, pos)
                except struct.error:
                    f = None
                if f is not None:
                    min_lc, ptr_size, nfunc = f[3], f[4], f[5]
                    funcname_off, pcln_off = f[8], f[12]
                    ft = pos + funcname_off
                    pt = pos + pcln_off
                    if (
                        min_lc == 4
                        and ptr_size == 8
                        and 1 <= nfunc <= 1000000
                        and base <= ft < region_end
                        and base <= pt < region_end
                        and pt + nfunc * 8 <= len(data)
                    ):
                        return {"pcln_base": pos, "funcname_off": funcname_off,
                                "pcln_off": pcln_off, "nfunc": nfunc}
            pos += 4
    return None


def find_function(data, pcln, name):
    """Resolve a function name -> (entry_off, size) via the pclntab."""
    ft = pcln["pcln_base"] + pcln["funcname_off"]
    pt = pcln["pcln_base"] + pcln["pcln_off"]
    nfunc = pcln["nfunc"]
    for i in range(nfunc):
        entry_off, func_off = struct.unpack_from("<II", data, pt + i * 8)
        try:
            _entry, name_off = struct.unpack_from("<Ii", data, pt + func_off)
        except struct.error:
            continue
        if name_off < 0:
            continue
        start = ft + name_off
        if start >= len(data):
            continue
        end = data.find(b"\x00", start)
        if end == -1:
            continue
        if data[start:end].decode("latin1", "ignore") != name:
            continue
        if i + 1 < nfunc:
            next_entry = struct.unpack_from("<II", data, pt + (i + 1) * 8)[0]
            size = next_entry - entry_off
        else:
            size = 0
        return entry_off, size
    return None


def _patch_body(body, old_imm, new_imm):
    """Return (status, index, count_old, count_new). status in
    patch/already/ambiguous/empty."""
    old = movz_x0(old_imm)
    new = movz_x0(new_imm)
    c_old = body.count(old)
    c_new = body.count(new)
    if c_old == 1 and c_new == 0:
        return "patch", body.find(old), c_old, c_new
    if c_old == 0 and c_new >= 1:
        return "already", -1, c_old, c_new
    if c_old == 0 and c_new == 0:
        return "empty", -1, c_old, c_new
    return "ambiguous", -1, c_old, c_new
