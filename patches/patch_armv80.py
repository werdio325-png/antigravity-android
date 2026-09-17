import os
import sys
import struct

def patch_armv80(target_path):
    """
    Патч совместимости с процессорами ARMv8.0 (Snapdragon 660, 820/821, 652/653,
    Exynos 8890, Cortex-A53/A57/A72/A73 и другими чипами без инструкций ARMv8.1-A LSE и ARMv8.3-A RCpc).

    Включает:
    1. Переключение Go runtime атомиков на LL/SC (arm64HasATOMICS = 0, cpu.ARM64.HasATOMICS = 0).
    2. Обход Google fail-fast проверки LSE в .init_array (0x6df4750 -> RET).
    3. Патч инициализатора google_find_phdr (0x6e59408) с заменой SWP на LDR/STR.
    4. Патч __cxa_guard_acquire (CAS) в cxa_guard.cpp.
    5. Конвертацию всех инструкций LDAPR (ARMv8.3-A RCpc) в стандартные ARMv8.0 LDAR.
    """
    print(f"[patch_armv80] Applying ARMv8.0 compatibility patch to: {target_path}")
    if not os.path.exists(target_path):
        print(f"[-] [patch_armv80] File not found: {target_path}")
        return False

    with open(target_path, "rb") as f:
        data = bytearray(f.read())

    patches_applied = 0

    # 1. runtime.schedinit (arm64HasATOMICS = 0)
    # Исходная инструкция: strb w0, [x27, #0x8ef] (0x3923bf60) -> strb wzr (0x3923bf7f)
    ORIG_SCHED = b"\x60\xbf\x23\x39"
    PATCH_SCHED = b"\x7f\xbf\x23\x39"
    idx = data.find(ORIG_SCHED)
    if idx != -1:
        data[idx:idx + 4] = PATCH_SCHED
        patches_applied += 1
        print(f"[patch_armv80] [1/6] Patched runtime.schedinit at 0x{idx:x}")
    elif data.find(PATCH_SCHED) != -1:
        print("[patch_armv80] [1/6] runtime.schedinit is already patched.")

    # 2. internal/cpu.getMIDR (cpu.ARM64.HasATOMICS = 0)
    # Исходная инструкция: strb w0, [x27, #0xf47] (0x393d1f60) -> strb wzr (0x393d1f7f)
    ORIG_CPU = b"\x60\x1f\x3d\x39"
    PATCH_CPU = b"\x7f\x1f\x3d\x39"
    idx_cpu = data.find(ORIG_CPU)
    if idx_cpu != -1:
        data[idx_cpu:idx_cpu + 4] = PATCH_CPU
        patches_applied += 1
        print(f"[patch_armv80] [2/6] Patched internal/cpu at 0x{idx_cpu:x}")
    elif data.find(PATCH_CPU) != -1:
        print("[patch_armv80] [2/6] internal/cpu is already patched.")

    # 3. Google ELF .init_array fail-fast check (0x6df4750)
    PATCH_RET = b"\xc0\x03\x5f\xd6"
    if len(data) > 0x6df4754:
        if data[0x6df4750:0x6df4754] != PATCH_RET:
            data[0x6df4750:0x6df4754] = PATCH_RET
            patches_applied += 1
            print(f"[patch_armv80] [3/6] Bypassed Google sigill-fail-fast at 0x6df4750 with RET")
        else:
            print("[patch_armv80] [3/6] Google sigill-fail-fast is already bypassed.")

    # 4. google_find_phdr constructor (0x6e59400)
    # Замена LSE SWP на стандартный LDR/STR (20 байт с 0x6e59408)
    ORIG_PHDR = bytes.fromhex("290080520801e9f8480000b4c0035fd61f2003d5")
    PATCH_PHDR = bytes.fromhex("090140f9490000b4c0035fd629008052090100f9")
    if len(data) > 0x6e59420:
        if data[0x6e59408:0x6e5941c] == ORIG_PHDR:
            data[0x6e59408:0x6e5941c] = PATCH_PHDR
            patches_applied += 1
            print(f"[patch_armv80] [4/6] Patched google_find_phdr at 0x6e59408")
        elif data[0x6e59408:0x6e5941c] == PATCH_PHDR:
            print("[patch_armv80] [4/6] google_find_phdr is already patched.")

    # 5. __cxa_guard_acquire CAS instructions in cxa_guard.cpp
    cxa_patches = [
        (0x6ef11a0, bytes.fromhex("15fdf708"), bytes.fromhex("15010039")),
        (0x6ef121c, bytes.fromhex("36fde808"), bytes.fromhex("36010039")),
        (0x6ef1258, bytes.fromhex("35fdf708"), bytes.fromhex("35010039")),
    ]
    cxa_count = 0
    for off, orig, pat in cxa_patches:
        if len(data) > off + 4:
            if data[off:off+4] == orig:
                data[off:off+4] = pat
                cxa_count += 1
    if cxa_count > 0:
        patches_applied += cxa_count
        print(f"[patch_armv80] [5/6] Patched {cxa_count} cxa_guard CAS instructions")
    else:
        print("[patch_armv80] [5/6] cxa_guard CAS instructions already patched or skipped.")

    # 6. Конвертация всех инструкций LDAPR (ARMv8.3-A RCpc) в LDAR (ARMv8.0)
    # Сканируем диапазон исполняемых секций: 0x046db000 .. 0x06f00000
    ldapr_converted = 0
    scan_start = 0x046db000
    scan_end = min(len(data), 0x06f00000)
    for i in range(scan_start, scan_end - 3, 4):
        w = struct.unpack_from("<I", data, i)[0]
        # LDAPR format: (w & 0x3fbfc000) == 0x38bfc000
        if (w & 0x3fbfc000) == 0x38bfc000:
            ldar = (w & 0xc00003ff) | 0x08dffc00
            struct.pack_into("<I", data, i, ldar)
            ldapr_converted += 1

    if ldapr_converted > 0:
        patches_applied += ldapr_converted
        print(f"[patch_armv80] [6/7] Converted {ldapr_converted} LDAPR instructions to standard ARMv8.0 LDAR")
    else:
        print("[patch_armv80] [6/7] LDAPR instructions already converted.")

    # 7. Защита SIGILL от перехвата Go runtime во всех копиях runtime.rt_sigaction
    ORIG_RTSIG = bytes.fromhex(
        "e00740f9e10b40f9e20f40f9e31340f9"
        "c81080d2010000d4e02b00b9c0035fd6"
    )
    PATCH_RTSIG = bytes.fromhex(
        "e08740a91f1000f180000054e28f41a9"
        "c81080d2010000d4ff2b00b9c0035fd6"
    )
    rtsig_idx = 0
    rtsig_count = 0
    while True:
        rtsig_idx = data.find(ORIG_RTSIG, rtsig_idx)
        if rtsig_idx == -1:
            break
        data[rtsig_idx:rtsig_idx + len(PATCH_RTSIG)] = PATCH_RTSIG
        rtsig_count += 1
        patches_applied += 1
        print(f"[patch_armv80] [7/7] Protected SIGILL in runtime.rt_sigaction at 0x{rtsig_idx:x}")
        rtsig_idx += len(PATCH_RTSIG)

    if rtsig_count == 0:
        if data.find(PATCH_RTSIG) != -1:
            print("[patch_armv80] [7/8] runtime.rt_sigaction instances already patched.")

    # 8. Защита SIGILL от перехвата через runtime.sigaction (CGO / _cgo_sigaction)
    # Встраиваем 12-байтовый фильтр (cmp w0, #4; b.ne 0x46faaf0; ret) в неиспользуемое выравнивание 0x46faae4
    # и перенаправляем все 6 вызовов BL 0x46faaf0 на 0x46faae4.
    GATE_SIGACTION = bytes.fromhex("1f10007141000054c0035fd6")
    CALLERS_SIGACTION = [
        (0x47314bc, bytes.fromhex("8d25ff97"), bytes.fromhex("8a25ff97")),
        (0x47314f0, bytes.fromhex("8025ff97"), bytes.fromhex("7d25ff97")),
        (0x473151c, bytes.fromhex("7525ff97"), bytes.fromhex("7225ff97")),
        (0x474baf8, bytes.fromhex("febbfe97"), bytes.fromhex("fbbbfe97")),
        (0x474bd38, bytes.fromhex("6ebbfe97"), bytes.fromhex("6bbbfe97")),
        (0x474bec0, bytes.fromhex("0cbbfe97"), bytes.fromhex("09bbfe97")),
    ]
    if len(data) > 0x46faaf0:
        if data[0x46faae4:0x46faae4 + 12] != GATE_SIGACTION:
            data[0x46faae4:0x46faae4 + 12] = GATE_SIGACTION
            patches_applied += 1
            print("[patch_armv80] [8/8] Installed SIGILL gate at runtime.sigaction (0x46faae4)")
        else:
            print("[patch_armv80] [8/8] runtime.sigaction gate already installed.")
        callers_patched = 0
        for addr, orig_bl, new_bl in CALLERS_SIGACTION:
            if data[addr:addr + 4] == orig_bl:
                data[addr:addr + 4] = new_bl
                callers_patched += 1
        if callers_patched > 0:
            patches_applied += callers_patched
            print(f"[patch_armv80] [8/8] Redirected {callers_patched} callers to runtime.sigaction gate")
        else:
            print("[patch_armv80] [8/8] All callers already redirected.")

    if patches_applied > 0:
        with open(target_path, "wb") as f:
            f.write(data)
        print(f"[patch_armv80] Successfully applied {patches_applied} ARMv8.0 modifications.")
    else:
        print("[patch_armv80] No pending modifications needed.")

    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 patch_armv80.py <binary_path>")
        sys.exit(1)
    patch_armv80(sys.argv[1])
