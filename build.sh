#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$PROJECT_DIR/app"
CORE_DIR="$PROJECT_DIR/core"
ROOTFS_DIR="$PROJECT_DIR/rootfs"
CONFIG_DIR="$PROJECT_DIR/config"
DIST_DIR="$PROJECT_DIR/dist"

TOOLS_DIR="${TOOLS_DIR:-$PROJECT_DIR/tools}"
BUILD_DIR="${BUILD_DIR:-/tmp/antigravity-build}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS_DIR/android.jar}"
R8_JAR="${R8_JAR:-$TOOLS_DIR/r8.jar}"
KEYSTORE="${KEYSTORE:-$TOOLS_DIR/debug.keystore}"

TARGET="${1:-all}"

echo "=== [1/7] Cleaning and preparing build directories ==="
rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR/gen $BUILD_DIR/obj $BUILD_DIR/apk $BUILD_DIR/assets $DIST_DIR

echo "=== [2/7] Packaging rootfs runtime archive ==="
python3 -c "
import zipfile, os
out_zip = '$BUILD_DIR/assets/rootfs.zip'
src_dir = '$ROOTFS_DIR'
with zipfile.ZipFile(out_zip, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src_dir):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src_dir)
            z.write(full, rel)
print(f'  Packed rootfs.zip ({os.path.getsize(out_zip):,} bytes)')
"

echo "=== [3/7] Generating R.java with AAPT ==="
aapt package -f -m     -J $BUILD_DIR/gen     -M $APP_DIR/src/main/AndroidManifest.xml     -S $APP_DIR/src/main/res     -I $ANDROID_JAR

echo "=== [4/7] Compiling Java classes ==="
javac -d $BUILD_DIR/obj     -cp $ANDROID_JAR     $BUILD_DIR/gen/com/antigravity/standalone/R.java     $APP_DIR/src/main/java/com/antigravity/standalone/*.java

echo "=== [5/7] Converting bytecode to classes.dex with D8 ==="
java -cp $R8_JAR com.android.tools.r8.D8     --output $BUILD_DIR/apk     --lib $ANDROID_JAR     --min-api 24     $BUILD_DIR/obj/com/antigravity/standalone/*.class

echo "=== [6/7] Packaging base APK with resources and assets ==="
aapt package -f     -M $APP_DIR/src/main/AndroidManifest.xml     -S $APP_DIR/src/main/res     -A $BUILD_DIR/assets     -I $ANDROID_JAR     -F $BUILD_DIR/unaligned_base.apk     $BUILD_DIR/apk

build_variant() {
    local variant="$1"
    local var_title="$2"
    local server_bin="$CORE_DIR/arm64-v8a/libserver-${variant}.so"
    local out_apk="$DIST_DIR/Antigravity-v2.11.0-${var_title}.apk"
    local docs_apk="/storage/emulated/0/Documents/Antigravity-v2.11.0-${var_title}.apk"

    echo "=== [7/7] Packaging variant [${var_title}] ($server_bin) ==="
    cp -f $BUILD_DIR/unaligned_base.apk $BUILD_DIR/unaligned_${variant}.apk

    python3 -c "
import zipfile, os
apk_path = '$BUILD_DIR/unaligned_${variant}.apk'
server_so = '$server_bin'

with zipfile.ZipFile(apk_path, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('$CORE_DIR/arm64-v8a/libandroid-shmem.so', 'lib/arm64-v8a/libandroid-shmem.so')
    z.write('$CORE_DIR/arm64-v8a/libldlinux.so', 'lib/arm64-v8a/libldlinux.so')
    if os.path.exists('$CORE_DIR/arm64-v8a/libqemu.so'):
        z.write('$CORE_DIR/arm64-v8a/libqemu.so', 'lib/arm64-v8a/libqemu.so')
        print('  + Bundled libqemu.so (ARMv8.0 transparent LSE user-mode translator)')
    with open(server_so, 'rb') as sf:
        sdata = bytearray(sf.read())
    offset = 0x6b76bf0
    if len(sdata) > offset + 4 and sdata[offset:offset+4] == b'\xfd\x7b\xbe\xa9':
        sdata[offset:offset+4] = b'\xc0\x03\x5f\xd6'
        print('  + Universal ARMv8.0 compatibility patch applied to libserver.so')
    z.writestr('lib/arm64-v8a/libserver.so', bytes(sdata))
print('  + Injected native libraries for ${var_title}')
"

    zipalign -f -p 4 $BUILD_DIR/unaligned_${variant}.apk $BUILD_DIR/aligned_${variant}.apk

    apksigner sign         --ks $KEYSTORE         --ks-key-alias androiddebugkey         --ks-pass pass:android         --key-pass pass:android         --out $out_apk         $BUILD_DIR/aligned_${variant}.apk

    cp -f $out_apk $docs_apk
    echo "  Собрано: $out_apk"
    echo "  В Documents: $docs_apk"
    apksigner verify $out_apk && echo "  Подпись ${var_title} валидна!"
}

case "$TARGET" in
    patched)
        build_variant "patched" "Patched"
        cp -f "$DIST_DIR/Antigravity-v2.11.0-Patched.apk" "$DIST_DIR/Antigravity-v2.11.0.apk"
        cp -f "$DIST_DIR/Antigravity-v2.11.0-Patched.apk" "/storage/emulated/0/Documents/Antigravity-Autonomous-v2.11.0.apk"
        ;;
    vanilla)
        build_variant "vanilla" "Vanilla"
        cp -f "$DIST_DIR/Antigravity-v2.11.0-Vanilla.apk" "$DIST_DIR/Antigravity-v2.11.0.apk"
        cp -f "$DIST_DIR/Antigravity-v2.11.0-Vanilla.apk" "/storage/emulated/0/Documents/Antigravity-Autonomous-v2.11.0.apk"
        ;;
    all|*)
        build_variant "patched" "Patched"
        build_variant "vanilla" "Vanilla"
        cp -f "$DIST_DIR/Antigravity-v2.11.0-Patched.apk" "$DIST_DIR/Antigravity-v2.11.0.apk"
        cp -f "$DIST_DIR/Antigravity-v2.11.0-Patched.apk" "/storage/emulated/0/Documents/Antigravity-Autonomous-v2.11.0.apk"
        ;;
esac

echo "=========================================================="
echo "ВСЕ ВЫБРАННЫЕ ВЕРСИИ УСПЕШНО СОБРАНЫ!"
ls -lh $DIST_DIR/Antigravity-*.apk
echo "=========================================================="
