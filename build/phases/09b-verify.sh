#!/usr/bin/env bash
#
# 09b-verify.sh - verify the signature and the packaged bootstrap asset.

log "[9b/9] apksigner verify + bootstrap check"
apksigner verify --verbose "$OUT_APK"

# fail-closed: the packaged bootstrap asset is a real gzip named .tar.gz
python3 - "$OUT_APK" <<'PY'
import gzip, sys, zipfile
apk = sys.argv[1]
with zipfile.ZipFile(apk) as z:
    names = [n for n in z.namelist() if n.startswith("assets/env/bootstrap/")]
    print("[verify] apk bootstrap entries:", names)
    if names != ["assets/env/bootstrap/bootstrap.tar.gz"]:
        sys.exit("expected exactly assets/env/bootstrap/bootstrap.tar.gz, got %r" % names)
    data = z.read(names[0])
    if data[:2] != b"\x1f\x8b":
        sys.exit("packaged bootstrap is not gzip (magic=%r)" % data[:2])
    gzip.decompress(data)
    print("[verify] packaged bootstrap: gzip magic ok, gunzip ok, %d bytes" % len(data))
PY

log "BUILD OK"
ls -l "$OUT_APK"
du -h "$OUT_APK"
