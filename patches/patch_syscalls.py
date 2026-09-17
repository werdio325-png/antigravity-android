#!/usr/bin/env python3
"""
patches/patch_syscalls.py

Patch ARM64 Go runtime / syscalls in language_server to eliminate SIGSYS crashes
caused by disallowed syscalls under Android's seccomp filter:
1. faccessat2 (syscall 439 / 0x1b7) -> faccessat (syscall 48 / 0x30)
   In Go on Linux/ARM64, os/exec.LookPath calls os/exec.findExecutable, which calls
   syscall.Faccessat with flags=unix.AT_EACCESS (0x200). syscall.Faccessat calls
   syscall.faccessat2(dirfd, path, mode, flags), issuing Syscall6(0x1b7, ...).
   On Android, seccomp filters trap syscall 439 with SECCOMP_RET_TRAP, killing
   the process with SIGSYS before Go can receive -ENOSYS.
   Replacing 0x1b7 with 0x30 replaces the trapped syscall with standard faccessat (48),
   which is whitelisted in Android's seccomp policy, allowing xdg-open lookup and
   execution to succeed cleanly.
2. fchmodat2 (syscall 452 / 0x1c4) -> fchmodat (syscall 53 / 0x35)
   Similarly replaces modern fchmodat2 with legacy fchmodat to prevent seccomp violations.
3. Validates and ensures permissions on helper tools (xdg-open, open_helper).
"""

import os
import sys
import struct

# ARM64 instruction encodings (little-endian)
# MOVZ x0, #imm16: 0xd2800000 | (imm16 << 5) | Rd
MOV_X0_FACCESSAT2 = struct.pack("<I", 0xd2800000 | (439 << 5) | 0)   # 0xd28036e0: mov x0, #0x1b7
MOV_X0_FACCESSAT  = struct.pack("<I", 0xd2800000 | (48 << 5)  | 0)   # 0xd2800600: mov x0, #0x30

MOV_X0_FCHMODAT2  = struct.pack("<I", 0xd2800000 | (452 << 5) | 0)   # 0xd2803880: mov x0, #0x1c4
MOV_X0_FCHMODAT   = struct.pack("<I", 0xd2800000 | (53 << 5)  | 0)   # 0xd28006a0: mov x0, #0x35


def parse_elf_text_section(data):
    """
    Parse 64-bit ELF header and section table to find .text section file offset and size.
    """
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None, None
    ei_class = data[4]
    if ei_class != 2:  # 64-bit
        return None, None

    e_shoff = struct.unpack_from("<Q", data, 40)[0]
    e_shentsize = struct.unpack_from("<H", data, 58)[0]
    e_shnum = struct.unpack_from("<H", data, 60)[0]
    e_shstrndx = struct.unpack_from("<H", data, 62)[0]

    if e_shoff == 0 or e_shentsize < 64 or e_shnum == 0 or e_shstrndx >= e_shnum:
        return None, None

    shstr_header = data[e_shoff + e_shstrndx * e_shentsize: e_shoff + (e_shstrndx + 1) * e_shentsize]
    shstr_offset, shstr_size = struct.unpack_from("<QQ", shstr_header, 24)
    shstrtab = data[shstr_offset: shstr_offset + shstr_size]

    for i in range(e_shnum):
        sh = data[e_shoff + i * e_shentsize: e_shoff + (i + 1) * e_shentsize]
        sh_name, sh_type, sh_flags, sh_addr, sh_offset, sh_size = struct.unpack_from("<IIQQQQ", sh, 0)
        name_end = shstrtab.find(b"\x00", sh_name)
        if name_end != -1:
            sec_name = shstrtab[sh_name:name_end].decode("latin1", errors="ignore")
            if sec_name == ".text":
                return sh_offset, sh_size
    return None, None


def find_func_in_pclntab(data, target_name):
    """
    Parse Go pclntab to locate the function's entry offset (relative to .text) and size.
    Supports Go 1.18 - 1.22 pclntab formats.
    """
    magics = [b"\xf1\xff\xff\xff", b"\xf2\xff\xff\xff", b"\xf0\xff\xff\xff"]
    for magic in magics:
        base = 0
        while True:
            base = data.find(magic, base)
            if base == -1:
                break
            try:
                fields = struct.unpack_from("<IBBBBQQQQQQQQ", data, base)
                minLC, ptrSize, nfunc = fields[3], fields[4], fields[5]
                funcnameOffset = fields[8]
                pclnOffset = fields[12]
                if minLC == 4 and ptrSize == 8 and 100 < nfunc < 500000:
                    funcnametab = base + funcnameOffset
                    pclntab = base + pclnOffset
                    for i in range(nfunc):
                        entryOff, funcOff = struct.unpack_from("<II", data, pclntab + i * 8)
                        f_entryOff, nameOff = struct.unpack_from("<Ii", data, pclntab + funcOff)
                        if nameOff < 0 or funcnametab + nameOff >= len(data):
                            continue
                        name_end = data.find(b"\x00", funcnametab + nameOff)
                        if name_end == -1:
                            continue
                        name = data[funcnametab + nameOff:name_end].decode("latin1", errors="ignore")
                        if name == target_name:
                            next_entryOff = struct.unpack_from("<II", data, pclntab + (i + 1) * 8)[0]
                            size = next_entryOff - entryOff
                            return entryOff, size
            except Exception:
                pass
            base += 4
    return None, None


def ensure_tools_executable():
    """
    Ensure bundled xdg-open and open_helper tools have executable permissions.
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.abspath(os.path.join(script_dir, ".."))
    tool_dirs = [
        os.path.join(project_root, "runtime", "src", "tools"),
        os.path.join(project_root, "staging", "build", "apk", "assets", "runtime", "tools"),
    ]
    for tdir in tool_dirs:
        if os.path.isdir(tdir):
            for tool_name in ["xdg-open", "open_helper", "sensible-browser", "x-www-browser"]:
                tool_path = os.path.join(tdir, tool_name)
                if os.path.exists(tool_path):
                    try:
                        current_mode = os.stat(tool_path).st_mode
                        os.chmod(tool_path, current_mode | 0o755)
                    except Exception as e:
                        print(f"[patch_syscalls] Note: chmod on {tool_path}: {e}")


def patch_syscalls(target_path):
    """
    Main patching function:
    Replaces faccessat2 (0x1b7 / 439) with faccessat (0x30 / 48) in syscall.faccessat2.
    Also patches fchmodat2 (0x1c4 / 452) to fchmodat (0x35 / 53) in syscall.fchmodat2.
    """
    if not os.path.exists(target_path):
        print(f"[-] [patch_syscalls] File not found: {target_path}")
        return False

    with open(target_path, "rb") as f:
        data = bytearray(f.read())

    patches_applied = 0
    text_off, text_sz = parse_elf_text_section(data)
    if text_off is None:
        text_off = 0x46e2000
        text_sz = len(data) - text_off
        print(f"[patch_syscalls] Warning: Using default .text offset 0x{text_off:x}")

    print(f"[patch_syscalls] Inspecting binary for faccessat2 seccomp violations...")

    # --- 1. Patch syscall.faccessat2 (439 -> 48) ---
    faccessat2_patched = False
    entry_off, size = find_func_in_pclntab(data, "syscall.faccessat2")
    if entry_off is not None:
        func_start = text_off + entry_off
        func_end = func_start + size
        print(f"[patch_syscalls] Located syscall.faccessat2 via pclntab at 0x{func_start:x} (size: {size} bytes)")
        idx = data.find(MOV_X0_FACCESSAT2, func_start, func_end)
        if idx != -1:
            data[idx:idx + 4] = MOV_X0_FACCESSAT
            patches_applied += 1
            faccessat2_patched = True
            print(f"[patch_syscalls] Successfully patched faccessat2 (0x1b7 -> 0x30) at file offset 0x{idx:x}")
        else:
            if data.find(MOV_X0_FACCESSAT, func_start, func_end) != -1:
                print(f"[patch_syscalls] syscall.faccessat2 is already patched (0x30).")
                faccessat2_patched = True

    # Fallback pattern scan for faccessat2
    if not faccessat2_patched:
        # Pattern in syscall.faccessat2: mov x5, xzr (e5031faa); mov x6, xzr (e6031faa); mov x0, #0x1b7 (e03680d2)
        pattern = b"\xe5\x03\x1f\xaa\xe6\x03\x1f\xaa" + MOV_X0_FACCESSAT2
        idx = data.find(pattern, text_off, text_off + text_sz)
        if idx != -1:
            target_idx = idx + 8
            data[target_idx:target_idx + 4] = MOV_X0_FACCESSAT
            patches_applied += 1
            faccessat2_patched = True
            print(f"[patch_syscalls] Patched faccessat2 via signature match at 0x{target_idx:x}")
        else:
            patched_pattern = b"\xe5\x03\x1f\xaa\xe6\x03\x1f\xaa" + MOV_X0_FACCESSAT
            if data.find(patched_pattern, text_off, text_off + text_sz) != -1:
                print(f"[patch_syscalls] syscall.faccessat2 already matches patched signature.")
                faccessat2_patched = True
            else:
                print(f"[-] [patch_syscalls] ERROR: Could not locate syscall.faccessat2 signature in .text!")

    # --- 2. Patch syscall.fchmodat2 (452 -> 53) if present ---
    fchmod_entry, fchmod_sz = find_func_in_pclntab(data, "syscall.fchmodat2")
    if fchmod_entry is not None:
        fchmod_start = text_off + fchmod_entry
        fchmod_end = fchmod_start + fchmod_sz
        idx = data.find(MOV_X0_FCHMODAT2, fchmod_start, fchmod_end)
        if idx != -1:
            data[idx:idx + 4] = MOV_X0_FCHMODAT
            patches_applied += 1
            print(f"[patch_syscalls] Successfully patched fchmodat2 (0x1c4 -> 0x35) at file offset 0x{idx:x}")
        elif data.find(MOV_X0_FCHMODAT, fchmod_start, fchmod_end) != -1:
            print(f"[patch_syscalls] syscall.fchmodat2 is already patched (0x35).")

    # --- 3. Ensure helper tools permissions ---
    ensure_tools_executable()

    if patches_applied > 0:
        with open(target_path, "wb") as f:
            f.write(data)
        print(f"[patch_syscalls] Applied {patches_applied} syscall patch(es) to {target_path}")
    else:
        if faccessat2_patched:
            print(f"[patch_syscalls] Binary already up to date, no changes written.")
        else:
            return False

    return True


def verify_syscall_patch(target_path):
    """
    Verification helper:
    Asserts that faccessat2 (0x1b7) is NOT present in syscall.faccessat2,
    and that faccessat (0x30) IS present.
    """
    if not os.path.exists(target_path):
        print(f"[-] Target file {target_path} not found for verification.")
        return False

    with open(target_path, "rb") as f:
        data = f.read()

    text_off, text_sz = parse_elf_text_section(data)
    entry_off, size = find_func_in_pclntab(data, "syscall.faccessat2")
    if entry_off is not None:
        func_start = (text_off or 0x46e2000) + entry_off
        func_bytes = data[func_start:func_start + size]
        has_439 = MOV_X0_FACCESSAT2 in func_bytes
        has_48 = MOV_X0_FACCESSAT in func_bytes
        print(f"[verify] syscall.faccessat2 has 0x1b7: {has_439}, has 0x30: {has_48}")
        if has_48 and not has_439:
            print("[verify] SUCCESS: faccessat2 is properly redirected to faccessat (0x30)!")
            return True
        else:
            print("[verify] FAILURE: faccessat2 verification failed.")
            return False
    else:
        # Check signature
        patched_pattern = b"\xe5\x03\x1f\xaa\xe6\x03\x1f\xaa" + MOV_X0_FACCESSAT
        if patched_pattern in data:
            print("[verify] SUCCESS: Patched signature verified in binary.")
            return True
        print("[verify] FAILURE: Could not verify patch.")
        return False


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 patch_syscalls.py <binary_path> [--verify]")
        sys.exit(1)
    target = sys.argv[1]
    ok = patch_syscalls(target)
    if "--verify" in sys.argv:
        ok = ok and verify_syscall_patch(target)
    sys.exit(0 if ok else 1)
