#!/usr/bin/env bash
#
# 08-package.sh - aapt package the unaligned APK and inject classes.dex.

log "[8/9] aapt: package unaligned.apk + zip native libs"
aapt package -f \
    -M "$TARGET_MANIFEST" \
    -S "$TARGET_RES" \
    -I "$ANDROID_JAR" \
    -A "$STAGE_ASSETS" \
    -F "$BUILD_DIR/unaligned.apk"

# classes.dex at the apk root
( cd "$DEX_DIR" && zip -q -u "$BUILD_DIR/unaligned.apk" classes.dex )

# aapt (legacy) strips a trailing ".gz" from asset names and stores the
# decompressed payload (bootstrap.tar.gz -> bootstrap.tar). Re-add the real
# gzip asset and drop the mangled entry so the shipped name and format always
# agree (never a plain .tar under a .gz name, or the reverse).
if [ -f "$ENV_ASSETS/bootstrap/bootstrap.tar.gz" ]; then
    python3 - "$BUILD_DIR/unaligned.apk" "$ENV_ASSETS/bootstrap/bootstrap.tar.gz" <<'PY'
import sys, os, zipfile, shutil

apk_path = sys.argv[1]
gz_path = sys.argv[2]
target_entry = "assets/env/bootstrap/bootstrap.tar.gz"
bad_entry = "assets/env/bootstrap/bootstrap.tar"
tmp_apk = apk_path + ".tmp"

with zipfile.ZipFile(apk_path, "r") as zin, zipfile.ZipFile(tmp_apk, "w") as zout:
    for item in zin.infolist():
        if item.filename in (bad_entry, target_entry):
            continue
        zout.writestr(item, zin.read(item.filename))
    if os.path.exists(gz_path):
        zout.write(gz_path, target_entry, compress_type=zipfile.ZIP_STORED)

shutil.move(tmp_apk, apk_path)
PY
fi
# No lib/ tree: the only native payload is assets/runtime/** (extracted by
# RuntimeManager at runtime). aapt already packaged it via -A "$STAGE_ASSETS".
