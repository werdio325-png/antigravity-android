# 00-prefix - root filesystem layout for the AGY prefix.
# Channel: prefix. Exports PREFIX/HOME/TMPDIR/PATH/LD_LIBRARY_PATH/LD_PRELOAD
# and sources the shared prefix env fragment if present.
# profile vars: PREFIX, HOME, TMPDIR, LD_PRELOAD
PREFIX="${PREFIX:-/data/data/com.agy/files/usr}"
HOME="${HOME:-/data/data/com.agy/files}"
TMPDIR="${TMPDIR:-$PREFIX/tmp}"

prefix_up() {
  export PREFIX HOME TMPDIR
  mkdir -p "$TMPDIR" 2>/dev/null || true
  # prefix binaries win; keep the Android system dirs reachable
  case ":$PATH:" in
    *":$PREFIX/bin:"*) ;;
    *) PATH="$PREFIX/bin:$PATH";;
  esac
  export PATH
  # glibc libraries + (optional) preload shim live under the prefix
  export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  if [ -n "${LD_PRELOAD:-}" ]; then export LD_PRELOAD; fi
  # shared, byte-for-byte fragment shipped by the prefix
  local f="$PREFIX/etc/profile.d/00-env.sh"
  [ -r "$f" ] && . "$f"
  return 0
}
prefix_down() { :; }

prefix_doctor() {
  local rc=0
  [ -d "$PREFIX" ] && printf '  [ok] PREFIX=%s\n' "$PREFIX" || { printf '  [--] PREFIX missing: %s\n' "$PREFIX"; rc=1; }
  [ -w "$TMPDIR" ] 2>/dev/null && printf '  [ok] TMPDIR=%s\n' "$TMPDIR" || printf '  [..] TMPDIR not writable yet: %s\n' "$TMPDIR"
  return $rc
}
