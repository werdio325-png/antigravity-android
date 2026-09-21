import os
import sys

def patch_resolv_conf(target_path):
    """
    Патчит строку '/etc/resolv.conf' (16 байт) или '/sdcard/res.conf' (16 байт)
    на относительный путь 'res/resolv.conf\x00' (16 байт) или 'runtime/resolv.c\x00'.
    Поскольку рабочий каталог процесса (CWD) - это context.getFilesDir()
    (/data/user/0/com.antigravity.mobile/files), процесс всегда имеет 100% доступ
    к своим локальным файлам в filesDir!
    
    'res/resolv.conf\x00' = 4 + 11 + 1 = 16 байт.
    'etc/resolv.conf\x00' = 4 + 11 + 1 = 16 байт!
    Идеально: 'etc/resolv.conf\x00' ровно 16 байт!
    """
    with open(target_path, "rb") as f:
        data = bytearray(f.read())

    targets = [b"/etc/resolv.conf", b"/sdcard/res.conf", b"etc/resolv.conf\x00"]
    new_target = b"etc//resolv.conf"
    assert len(new_target) == 16

    count = 0
    for target in targets:
        pos = 0
        while True:
            pos = data.find(target, pos)
            if pos == -1:
                break
            data[pos:pos+len(new_target)] = new_target
            count += 1
            pos += len(new_target)

    with open(target_path, "wb") as f:
        f.write(data)

    print(f"[patch_resolv] Replaced {count} resolv.conf occurrences with {repr(new_target)}")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 patch_resolv.py <binary_path>")
        sys.exit(1)
    patch_resolv_conf(sys.argv[1])
