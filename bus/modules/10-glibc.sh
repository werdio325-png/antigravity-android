# 10-glibc - point the glibc loader + library path at the self-contained
# prefix that ships next to the core. Channel: glibc.
# profile vars: GLIBC_PREFIX, TARGET_LIB_DIR, USE_LOADER, LOADER
GLIBC_PREFIX="${GLIBC_PREFIX:-${PREFIX:-/data/data/com.agy/files/usr}/glibc}"
TARGET_LIB_DIR="${TARGET_LIB_DIR:-$GLIBC_PREFIX/lib}"
BUS_USE_LOADER="${USE_LOADER:-0}"

glibc_up() {
  if [ ! -d "$TARGET_LIB_DIR" ]; then
    _warn "glibc: $TARGET_LIB_DIR not found (set GLIBC_PREFIX)"
  else
    export LD_LIBRARY_PATH="$TARGET_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  fi
  if [ "$BUS_USE_LOADER" = 1 ]; then
    LOADER="${LOADER:-$GLIBC_PREFIX/lib/ld-linux-aarch64.so.1}"
    [ -x "$LOADER" ] || _warn "glibc: loader $LOADER not executable"
    BUS_CMD=("$LOADER")
  fi
  return 0
}
glibc_down() { :; }

glibc_doctor() {
  local rc=0 ld="${LOADER:-$GLIBC_PREFIX/lib/ld-linux-aarch64.so.1}"
  [ -x "$ld" ] && printf '  [ok] glibc loader %s\n' "$ld" || { printf '  [--] glibc loader not executable: %s\n' "$ld"; rc=1; }
  [ -d "$TARGET_LIB_DIR" ] && printf '  [ok] glibc libs %s\n' "$TARGET_LIB_DIR" || printf '  [--] glibc libs missing: %s\n' "$TARGET_LIB_DIR"
  return $rc
}
