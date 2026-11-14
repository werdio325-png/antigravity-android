#!/usr/bin/env python3
"""Length-preserving rewrite of Termux's compiled prefix in dpkg binaries.

This legacy helper is optional and is not part of env/build.sh. It can only
patch binary package-id components no longer than ``com.termux`` (10 bytes);
longer ids require a proper ELF-aware relocation rather than an in-place edit.
The active application id must be supplied as an argument or via
AGY_APPLICATION_ID. No app id is assumed by this tool.
"""
import os
import re
import sys
import tempfile
import zipfile

TERMUX = b"com.termux"
TEXT_OLD = b"/data/data/com.termux"


def is_dpkg(name):
    base = name.rsplit("/", 1)[-1]
    return base.startswith("dpkg")


def target_prefix(application_id):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", application_id):
        raise ValueError(f"invalid Android application id: {application_id!r}")
    package = application_id.encode("ascii")
    if len(package) > len(TERMUX):
        raise ValueError(
            "in-place ELF rewrite cannot encode application id longer than "
            f"{len(TERMUX)} bytes: {application_id!r}"
        )
    # Padding with slashes preserves the compiled string length and collapses
    # harmlessly in Android's path resolver (for example dev.continuum.antigravity///files/usr).
    padded = package + b"/" * (len(TERMUX) - len(package))
    return b"/data/data/" + application_id.encode("ascii"), padded


def patch(name, data, application_id, text_prefix, binary_package):
    if not is_dpkg(name):
        return data
    if data[:4] == b"\x7fELF":
        return data.replace(TERMUX, binary_package)
    if TEXT_OLD in data:
        return data.replace(TEXT_OLD, text_prefix)
    return data


def main(path, application_id):
    text_prefix, binary_package = target_prefix(application_id)
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
                new = patch(
                    info.filename, data, application_id, text_prefix, binary_package
                )
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
    print("patched %d dpkg file(s) for %s in %s" % (changed, application_id, path))


if __name__ == "__main__":
    if len(sys.argv) not in (2, 3):
        sys.exit("usage: patch-prefix.py <bootstrap.zip> [application-id]")
    app_id = sys.argv[2] if len(sys.argv) == 3 else os.environ.get("AGY_APPLICATION_ID")
    if not app_id:
        sys.exit("set AGY_APPLICATION_ID or pass the target application-id")
    try:
        main(sys.argv[1], app_id)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        sys.exit(f"patch-prefix.py: {exc}")
