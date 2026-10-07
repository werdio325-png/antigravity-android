#!/usr/bin/env bash
#
# 04b-ldcheck.sh - fail-closed dynamic-link check for the patched core.
#
# Every NEEDED of the patched core must resolve through the bundled loader
# against the bundled glibc libs. $PROJECT often lives on a noexec mount (e.g.
# /storage/emulated/0), so run the loader from a temp dir on an exec-capable
# filesystem; the libs/core are copied there for the probe.

log "[4b/9] fail-closed: ld-linux --list $CORE_NAME"

HOST_ARCH="$(uname -m)"
QEMU_CMD=""
if [ "$HOST_ARCH" != "aarch64" ] && [ "$HOST_ARCH" != "arm64" ]; then
    if command -v qemu-aarch64-static >/dev/null 2>&1; then
        QEMU_CMD="qemu-aarch64-static"
    elif command -v qemu-aarch64 >/dev/null 2>&1; then
        QEMU_CMD="qemu-aarch64"
    else
        log "  cross-building on $HOST_ARCH (skipping target ld-linux execution)"
        return 0 2>/dev/null || exit 0
    fi
fi

[ -f "$RT/bin/$LOADER_NAME" ] || die "glibc loader not staged: $RT/bin/$LOADER_NAME"
LDCHECK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agy-ldcheck.XXXXXX")" \
    || die "mktemp failed for ld-linux check"
trap 'rm -rf "$LDCHECK_DIR"' EXIT
cp -f "$RT/lib"/*.so* "$LDCHECK_DIR"/ 2>/dev/null || true
cp -f "$RT/bin/$CORE_NAME" "$LDCHECK_DIR/$CORE_NAME"
cp -f "$RT/bin/$LOADER_NAME" "$LDCHECK_DIR/$GLIBC_LOADER"
chmod 755 "$LDCHECK_DIR/$GLIBC_LOADER" "$LDCHECK_DIR/$CORE_NAME"
set +e
if [ -n "$QEMU_CMD" ]; then
    LDCHECK_OUT="$(env -u LD_PRELOAD "$QEMU_CMD" "$LDCHECK_DIR/$GLIBC_LOADER" --library-path "$LDCHECK_DIR" \
        --list "$LDCHECK_DIR/$CORE_NAME" 2>&1)"
else
    LDCHECK_OUT="$(env -u LD_PRELOAD "$LDCHECK_DIR/$GLIBC_LOADER" --library-path "$LDCHECK_DIR" \
        --list "$LDCHECK_DIR/$CORE_NAME" 2>&1)"
fi
LDCHECK_RC=$?
set -e
printf '%s\n' "$LDCHECK_OUT"
if [ "$LDCHECK_RC" -ne 0 ] || printf '%s\n' "$LDCHECK_OUT" | grep -v 'libtermux-exec' | grep -q 'not found'; then
    die "ld-linux --list failed for $CORE_NAME (rc=$LDCHECK_RC); unresolved NEEDED"
fi
log "  all NEEDED resolved for $CORE_NAME"
rm -rf "$LDCHECK_DIR"
trap - EXIT
