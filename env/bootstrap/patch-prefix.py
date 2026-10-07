#!/usr/bin/env python3
"""Length-preserving rewrite of the hardcoded Termux prefix in dpkg binaries.

The Termux bootstrap ships dpkg/dpkg-deb/... ELF files compiled with
/data/data/com.termux/files/usr baked in. Our app-private prefix is
/data/data/com.agy/files/usr, so every dpkg invocation tries to open
/data/data/com.termux/... and dies with EACCES before it can even read its
own config directory. dpkg has no --confdir option and neither --root nor
DPKG_ROOT move the compiled sysconfdir, so a shell wrapper cannot fix this.
The only robust fix is to patch the compiled strings in place.

The replacement must keep the byte length: `com.termux` (10 bytes) becomes
`com.agy////`? No -- it becomes `com.agy///` + the original `/files/...`
continuation, i.e. extra slashes collapse on the filesystem. Shell scripts
in the same family are plain text and get a normal substitution.

Idempotent: running it twice is a no-op.
"""
import os
import stat
import sys
import tempfile
import zipfile

TERMUX = b"com.termux"
APP = b"com.agy///"
assert len(TERMUX) == len(APP)

TEXT_OLD = b"/data/data/com.termux"
TEXT_NEW = b"/data/data/com.agy"


def is_dpkg(name):
    base = name.rsplit("/", 1)[-1]
    return base.startswith("dpkg")


def patch(name, data):
    if not is_dpkg(name):
        return data
    if data[:4] == b"\x7fELF":
        return data.replace(TERMUX, APP)
    if TEXT_OLD in data:
        return data.replace(TEXT_OLD, TEXT_NEW)
    return data


def main(path):
    tmp = tempfile.NamedTemporaryFile(
        dir=os.path.dirname(os.path.abspath(path)), delete=False
    )
    tmp.close()
    changed = 0
    try:
        with zipfile.ZipFile(path, "r") as zin, zipfile.ZipFile(
            tmp.name, "w", zipfile.ZIP_DEFLATED
        ) as zout:
            for info in zin.infolist():
                data = zin.read(info.filename)
                new = patch(info.filename, data)
                if new != data:
                    changed += 1
                zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                zi.compress_type = info.compress_type
                zi.external_attr = info.external_attr
                zi.internal_attr = info.internal_attr
                zi.create_system = info.create_system
                zi.flag_bits = info.flag_bits
                zout.writestr(zi, new)
        os.replace(tmp.name, path)
    finally:
        if os.path.exists(tmp.name):
            os.unlink(tmp.name)
    print("patched %d dpkg file(s) in %s" % (changed, path))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: patch-prefix.py <bootstrap.zip>")
    main(sys.argv[1])
