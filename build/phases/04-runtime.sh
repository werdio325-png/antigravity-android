#!/usr/bin/env bash
#
# 04-runtime.sh - assemble assets/runtime/{bin,lib,certs,seed}.

log "[4/9] assets/runtime/{bin,lib,certs,seed}"
mkdir -p "$RT/bin" "$RT/lib" "$RT/certs" "$RT/seed"
# bin: loader + patched core + tools/shim (app RuntimeManager reads these from bin/)
cp -f "$CORE_GLIBC/$GLIBC_LOADER" "$RT/bin/$LOADER_NAME"
cp -f "$STAGED_CORE" "$RT/bin/$CORE_NAME"
# tools/: skip start-antigravity (Termux-only launcher, hardcodes
# /data/data/com.termux/files/usr/bin/bash; unused by Java and bus).
for tool in "$TOOLS_SRC_DIR"/*; do
    case "$(basename "$tool")" in
        start-antigravity)
            log "  skipping tools/start-antigravity (Termux-only, unused)"
            continue
            ;;
    esac
    cp -f "$tool" "$RT/bin/"
done
# lib: glibc shared objects for the loader --library-path
for so in "${glibc_libs[@]}"; do
    cp -f "$so" "$RT/lib/$(basename "$so")"
done
cp -f "$CORE_GLIBC/$GLIBC_LOADER" "$RT/lib/$GLIBC_LOADER"
chmod 755 "$RT/lib/$GLIBC_LOADER"
# certs + seed
cp -f "$CERTS_DIR"/* "$RT/certs/"
cp -f "$SEED_DIR"/* "$RT/seed/"
chmod 755 "$RT/bin"/* 2>/dev/null || true
