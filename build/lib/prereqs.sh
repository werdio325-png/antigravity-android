#!/usr/bin/env bash
#
# prereqs.sh - host tool + input existence checks and build-dir cleaning.
#
# check_tools() mirrors the original fail-closed checks 1:1 (same host tool
# list). clean_build() prepares the artifact subdirectories.

need() { command -v "$1" >/dev/null 2>&1 || die "missing host tool: $1"; }

check_tools() {
    for t in aapt javac java zipalign apksigner zip unzip python3 sha256sum gzip file mktemp; do
        need "$t"
    done

    [ -f "$CORE_SRC" ] || [ -f "${CORE_SRC}.tar.gz" ] || die "core not found: $CORE_SRC (or ${CORE_SRC}.tar.gz)"
    [ -f "$ANDROID_JAR" ]        || die "android.jar not found: $ANDROID_JAR"
    [ -f "$R8_JAR" ]             || die "r8.jar not found: $R8_JAR"
    [ -f "$KEYSTORE" ]           || die "keystore not found: $KEYSTORE"
    [ -f "$MANIFEST_XML" ]       || die "manifest not found: $MANIFEST_XML"
    [ -d "$WEB_DIR" ]            || die "web/ not found: $WEB_DIR"
    [ -f "$GEN_ANDROID_CONFIG" ] || die "gen_android_config.py not found: $GEN_ANDROID_CONFIG"
}

# NOTE: the original removed the whole $BUILD_DIR because it held only
# artifacts. In v2 the pipeline scripts live under $BUILD_DIR/{lib,phases,tools},
# so clean only the generated artifact paths.
clean_build() {
    log "clean build dir"
    rm -rf "$BUILD_DIR/staging" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" \
           "$BUILD_DIR/apk" "$BUILD_DIR/res" "$BUILD_DIR/AndroidManifest.xml" \
           "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
    mkdir -p "$BUILD_DIR/staging" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/dex" \
             "$STAGE_ASSETS" "$DIST_DIR"
}
