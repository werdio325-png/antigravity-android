#!/usr/bin/env bash
#
# 00-dev.sh - configure edition and build the generated sources.
#
# Normal build: TARGET_RES=$BUILD_DIR/res (app res + gen.xml),
#               TARGET_MANIFEST=$APP_DIR/src/main/AndroidManifest.xml,
#               OUT_APK=dist/agy.apk.
# Dev build:    package=com.agy.dev, label/port from app.env (APP_NAME_DEV,
#               PORT_DEV), TARGET_MANIFEST=$BUILD_DIR/AndroidManifest.xml,
#               OUT_APK=dist/agy-dev.apk. Class names stay com.agy.* and are
#               NOT rewritten (v2 manifest already uses fully-qualified names).
#
# gen_android_config.py writes BuildConfig.java (into $GEN_DIR) and gen.xml
# (into $GEN_DIR/res/values), which are consumed by phase 06 (aapt -J R.java).

log "[0/9] configure build edition and generated sources"
if [ "$DEV_MODE" -eq 1 ]; then
    OUT_APK="$DIST_DIR/antigravity-dev.apk"
    log "  Configuring DEV edition: package=$PKG_DEV, label='$APP_NAME_DEV', port=$PORT_DEV"
elif [ "${REGION_BYPASS:-0}" -eq 1 ]; then
    OUT_APK="$DIST_DIR/antigravity-bypass.apk"
    log "  Configuring RELEASE edition with REGION BYPASS: $OUT_APK"
else
    OUT_APK="$DIST_DIR/antigravity.apk"
    log "  Configuring STANDARD RELEASE edition (without region bypass): $OUT_APK"
fi

[ -d "$RES_DIR" ] || die "res dir not found: $RES_DIR"

mkdir -p "$GEN_DIR" "$BUILD_DIR/res"
python3 "$GEN_ANDROID_CONFIG" "$CONFIG_APP_ENV" "$GEN_DIR" \
    || die "gen_android_config.py failed"
[ -f "$GEN_DIR/com/agy/BuildConfig.java" ] || die "BuildConfig.java not generated"
[ -f "$GEN_DIR/res/values/gen.xml" ]       || die "gen.xml not generated"

# $BUILD_DIR/res = copy of the app res tree plus the generated gen.xml.
cp -a "$RES_DIR/." "$BUILD_DIR/res/"
mkdir -p "$BUILD_DIR/res/values"
cp -f "$GEN_DIR/res/values/gen.xml" "$BUILD_DIR/res/values/gen.xml"

# gen.xml owns app_name / https_server_port / oauth_scheme. Drop the same keys
# from the copied strings.xml (if present) so aapt never sees a duplicate
# resource. Values still resolve through gen.xml.
STRINGS_COPY="$BUILD_DIR/res/values/strings.xml"
if [ -f "$STRINGS_COPY" ]; then
    sed -i -E '/<string name="(app_name|oauth_scheme)">/d; /<integer name="https_server_port">/d' "$STRINGS_COPY"
fi

TARGET_RES="$BUILD_DIR/res"
TARGET_MANIFEST="$MANIFEST_XML"

if [ "$DEV_MODE" -eq 1 ]; then
    GEN_XML="$BUILD_DIR/res/values/gen.xml"
    sed -i "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">$APP_NAME_DEV</string>|" "$GEN_XML"
    sed -i "s|<integer name=\"https_server_port\">.*</integer>|<integer name=\"https_server_port\">$PORT_DEV</integer>|" "$GEN_XML"

    TARGET_MANIFEST="$BUILD_DIR/AndroidManifest.xml"
    cp -f "$MANIFEST_XML" "$TARGET_MANIFEST"
    sed -i "s|package=\"$PKG\"|package=\"$PKG_DEV\"|g" "$TARGET_MANIFEST"
fi
