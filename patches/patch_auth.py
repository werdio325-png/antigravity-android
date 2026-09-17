import os
import sys

def patch_auth_success(target_path):
    """
    Заменяет 'https://antigravity.google/auth-success?app=%s' (46 байт)
    на Deep Link 'antigravity://auth-success?app=%s#############' (46 байт).
    Android перехватывает antigravity:// схему, мгновенно закрывает Chrome Custom Tab
    и возвращает пользователя в MainActivity Antigravity.
    """
    target = b"https://antigravity.google/auth-success?app=%s"
    prefix = b"antigravity://auth-success?app=%s"
    replacement = prefix + b"#" * (len(target) - len(prefix))
    assert len(target) == len(replacement) == 46

    with open(target_path, "rb") as f:
        data = bytearray(f.read())

    # Проверяем старый javascript: патч, если он уже был применен в staging
    old_js_patch = b"javascript:window.close()//"
    pos_js = data.find(old_js_patch)
    if pos_js != -1:
        data[pos_js:pos_js+46] = replacement
        with open(target_path, "wb") as f:
            f.write(data)
        print(f"[patch_auth] Replaced old JS patch with DeepLink at offset {hex(pos_js)}")
        return True

    pos = data.find(target)
    if pos == -1:
        if replacement in data:
            print("[patch_auth] Already patched with DeepLink.")
            return True
        print("[patch_auth] Warning: auth-success string not found.")
        return False

    data[pos:pos+len(replacement)] = replacement
    with open(target_path, "wb") as f:
        f.write(data)

    print(f"[patch_auth] Successfully replaced auth-success with DeepLink at offset {hex(pos)}")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 patch_auth.py <binary_path>")
        sys.exit(1)
    patch_auth_success(sys.argv[1])
