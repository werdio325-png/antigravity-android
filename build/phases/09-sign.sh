#!/usr/bin/env bash
#
# 09-sign.sh - zipalign and sign the APK with the debug keystore.

log "[9/9] zipalign + sign"
zipalign -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$OUT_APK" \
    "$BUILD_DIR/aligned.apk"
