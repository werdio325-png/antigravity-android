import os
import sys

def patch_token_storage(target_path):
    """
    Forces language_server to immediately bypass D-Bus and system keyring
    in 0 ms by making NewCompositeTokenStorage unconditionally take the fast
    file-based token storage branch ("Using file-based token storage because the caller requested it").

    Target: 0x62ed73c (tbz w6, #0, 0x62ed784) -> NOP (0x1f2003d5).
    """
    print(f"[patch_storage] Applying instant file-based token storage bypass to: {target_path}")
    if not os.path.exists(target_path):
        print(f"[-] [patch_storage] File not found: {target_path}")
        return False

    with open(target_path, "rb") as f:
        data = bytearray(f.read())

    # Signature:
    # 0x3902e3e6: strb w6, [sp, #0x8e]
    # 0xf90063e7: str  x7, [sp, #0xc8]
    # 0x36000246: tbz  w6, #0, +0x48 (0x62ed784)
    # 0x39017fe6: strb w6, [sp, #0x5f]
    sig = b"\xe6\xe3\x02\x39\xe7\x63\x00\xf9\x46\x02\x00\x36\xe6\x7f\x01\x39"
    patched_sig = b"\xe6\xe3\x02\x39\xe7\x63\x00\xf9\x1f\x20\x03\xd5\xe6\x7f\x01\x39"

    pos = data.find(sig)
    if pos == -1:
        pos = data.find(patched_sig)

    if pos == -1:
        print("[-] [patch_storage] Warning: Token storage signature not found.")
        return False

    # 1. NOP the tbz in constructor so caller logs "Using file-based token storage"
    data[pos:pos+len(patched_sig)] = patched_sig
    print(f"[patch_storage] NOPed constructor branch at offset {hex(pos+8)}")

    # 2. Patch useFileStorageOnly() at 0x62ed580:
    # 0x62ed734 - 0x62ed580 = 0x1b4
    target_func = pos - (0x62ed734 - 0x62ed580)
    # Replace function prologue with: mov w0, #1 (\x20\x00\x80\x52); ret (\xc0\x03\x5f\xd6)
    data[target_func:target_func+8] = b"\x20\x00\x80\x52\xc0\x03\x5f\xd6"
    print(f"[patch_storage] Patched useFileStorageOnly() at {hex(target_func)} to return true unconditionally!")

    with open(target_path, "wb") as f:
        f.write(data)

    print("[patch_storage] Successfully patched token storage for 0.0ms instant file-storage fast-path.")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 patch_storage.py <binary_path>")
        sys.exit(1)
    patch_token_storage(sys.argv[1])
