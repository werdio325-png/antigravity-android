#!/usr/bin/env bash
#
# paths.sh - single place that loads config/*.env and derives every path/var
# used by the phases.
#
# setup_paths() is called by build.sh AFTER PROJECT is set and AFTER
# parse_args(). Config files are sourced under `set -a` so every KEY=VALUE is
# exported to the phase files.
#
# The original pipeline hardcoded core/, tools/ and the Android SDK paths.
# v2 keeps immutable heavy inputs outside the tree (config/paths.env points at
# $AGY_HOME) and only owns the light web assets.

# load_app_env - read a plain KEY=VALUE file without handing it to the shell.
# app.env may contain unquoted values with spaces (e.g. APP_NAME_DEV=Antigravity
# Dev), which `source` would try to execute. Value = everything after the first
# '='; surrounding matching quotes are stripped. All keys are exported.
load_app_env() {
    local file="$1" line key value
    [ -f "$file" ] || { printf '\n[FATAL] env file not found: %s\n' "$file" >&2; exit 1; }
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|\#*) continue ;;
            *=*) ;;
            *) continue ;;
        esac
        key="${line%%=*}"
        value="${line#*=}"
        key="$(printf '%s' "$key" | tr -d '[:space:]')"
        [ -n "$key" ] || continue
        case "$value" in
            \"*\") value="${value#\"}"; value="${value%\"}" ;;
            \'*\') value="${value#\'}"; value="${value%\'}" ;;
        esac
        export "$key=$value"
    done < "$file"
}

setup_paths() {
    local cfg="$PROJECT/config"
    [ -d "$cfg" ] || { printf '\n[FATAL] config dir not found: %s\n' "$cfg" >&2; exit 1; }

    # paths.env / tools.env are clean shell (paths.env relies on $AGY_HOME
    # expansion), so source them under `set -a`. app.env is parsed tolerantly.
    set -a
    # shellcheck disable=SC1090
    . "$cfg/paths.env"
    . "$cfg/tools.env"
    set +a
    load_app_env "$cfg/app.env"

    CONFIG_DIR="$cfg"
    CONFIG_APP_ENV="$cfg/app.env"

    # Logical project dirs.
    CORE_DIR="$PROJECT/core"
    APP_DIR="$PROJECT/app"
    WEB_DIR="$PROJECT/web"
    BUS_DIR="$PROJECT/bus"
    PATCHES_DIR="$PROJECT/patches"
    TOOLS_DIR="$PROJECT/tools"
    BUILD_DIR="$PROJECT/build"
    DIST_DIR="$PROJECT/dist"

    # Build scripts live under $BUILD_DIR too, so clean_build() must never
    # remove the whole dir (see prereqs.sh).
    BUILD_LIB_DIR="$BUILD_DIR/lib"
    PHASES_DIR="$BUILD_DIR/phases"
    BUILD_TOOLS_DIR="$BUILD_DIR/tools"
    GEN_ANDROID_CONFIG="$BUILD_TOOLS_DIR/gen_android_config.py"

    # App source layout (v2).
    MANIFEST_XML="$APP_DIR/src/main/AndroidManifest.xml"
    RES_DIR="$APP_DIR/src/main/res"
    JAVA_DIR="$APP_DIR/src/main/java"

    # Heavy immutable inputs come from config/paths.env, referenced in place.
    # CORE_SRC / CORE_GLIBC preserve the original variable names.
    CORE_SRC="$CORE_BIN"
    CORE_GLIBC="$GLIBC_DIR"

    # Runtime packaging constants.
    GLIBC_LOADER="ld-linux-aarch64.so.1"
    LOADER_NAME="libloader.so"
    CORE_NAME="liblanguage_server.so"
    MIN_API="${MIN_API:-24}"

    # Artifact paths.
    STAGE_APK="$BUILD_DIR/apk"
    STAGE_ASSETS="$STAGE_APK/assets"
    RT="$STAGE_ASSETS/runtime"
    STAGED_CORE="$BUILD_DIR/staging/language_server"
    GEN_DIR="$BUILD_DIR/gen"
    OBJ_DIR="$BUILD_DIR/obj"
    DEX_DIR="$BUILD_DIR/dex"

    # Defaults; phase 00 finalizes depending on --dev.
    TARGET_MANIFEST="$MANIFEST_XML"
    TARGET_RES="$RES_DIR"
    OUT_APK="$DIST_DIR/agy.apk"

    export CONFIG_DIR CONFIG_APP_ENV \
        CORE_DIR APP_DIR WEB_DIR BUS_DIR PATCHES_DIR TOOLS_DIR BUILD_DIR DIST_DIR \
        BUILD_LIB_DIR PHASES_DIR BUILD_TOOLS_DIR GEN_ANDROID_CONFIG \
        MANIFEST_XML RES_DIR JAVA_DIR CORE_SRC CORE_GLIBC \
        GLIBC_LOADER LOADER_NAME CORE_NAME MIN_API \
        STAGE_APK STAGE_ASSETS RT STAGED_CORE GEN_DIR OBJ_DIR DEX_DIR \
        TARGET_MANIFEST TARGET_RES OUT_APK
}
