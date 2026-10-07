#!/usr/bin/env bash
#
# 07b-d8.sh - dex the compiled classes with the bundled R8/D8.

log "[7/9] d8"
mapfile -d '' CLASS_FILES < <(find "$OBJ_DIR" -name '*.class' -print0)
java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --output "$DEX_DIR" \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_API" \
    "${CLASS_FILES[@]}"
[ -f "$DEX_DIR/classes.dex" ] || die "d8 produced no classes.dex"
