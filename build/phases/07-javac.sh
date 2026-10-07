#!/usr/bin/env bash
#
# 07-javac.sh - compile app sources plus generated sources (UTF-8).

log "[7/9] javac (UTF-8)"
mapfile -d '' JAVA_SOURCES < <(find "$JAVA_DIR" -name '*.java' -print0)
[ "${#JAVA_SOURCES[@]}" -gt 0 ] || die "no java sources under $JAVA_DIR"
mapfile -d '' GEN_SOURCES < <(find "$GEN_DIR" -name '*.java' -print0)
[ "${#GEN_SOURCES[@]}" -gt 0 ] || die "no generated sources under $GEN_DIR"
javac -encoding UTF-8 -J-Dfile.encoding=UTF-8 -J-Dsun.jnu.encoding=UTF-8 \
    -d "$OBJ_DIR" \
    -cp "$ANDROID_JAR" \
    "${GEN_SOURCES[@]}" \
    "${JAVA_SOURCES[@]}"
