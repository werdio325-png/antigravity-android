#!/usr/bin/env bash
#
# 06-rjava.sh - aapt generates R.java into the shared generated-sources dir.
#
# $GEN_DIR already holds BuildConfig.java from phase 00; aapt adds R.java
# alongside it. TARGET_RES/TARGET_MANIFEST are set in phase 00 (dev-aware).

log "[6/9] aapt: generate R.java"
aapt package -f -m \
    -J "$GEN_DIR" \
    -M "$TARGET_MANIFEST" \
    -S "$TARGET_RES" \
    -I "$ANDROID_JAR"
