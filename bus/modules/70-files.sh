# 70-files - external storage access toggle (MANAGE_EXTERNAL_STORAGE +
# Shizuku/rish). Channel: files.
#
# On Android the WebView can reach shared storage only through the app or a
# root/Shizuku shell. When FILES_SHIZUKU=1, expose the `rish` shim on PATH.
# profile vars: FILES_SHIZUKU, RISH_BIN, EXTERNAL_STORAGE
EXTERNAL_STORAGE="${EXTERNAL_STORAGE:-/storage/emulated/0}"
RISH_BIN="${RISH_BIN:-$PREFIX/bin/rish}"

files_up() {
  export EXTERNAL_STORAGE
  if [ "${FILES_SHIZUKU:-0}" = 1 ]; then
    if [ -x "$RISH_BIN" ]; then
      case ":$PATH:" in *":$(dirname "$RISH_BIN"):"*) ;; *) PATH="$(dirname "$RISH_BIN"):$PATH";; esac
      export PATH
      _endpoint files_rish "$RISH_BIN"
      _log "files: Shizuku rish enabled ($RISH_BIN)"
    else
      _warn "files: FILES_SHIZUKU=1 but rish not found: $RISH_BIN"
    fi
  fi
  _endpoint files_root "$EXTERNAL_STORAGE"
  return 0
}
files_down() { :; }

files_doctor() {
  printf '  [..] external storage root %s\n' "${EXTERNAL_STORAGE:-<unset>}"
  if [ "${FILES_SHIZUKU:-0}" = 1 ]; then
    [ -x "${RISH_BIN:-}" ] && printf '  [ok] rish %s\n' "$RISH_BIN" || printf '  [--] FILES_SHIZUKU=1 but rish missing\n'
  else
    printf '  [..] Shizuku disabled (set FILES_SHIZUKU=1 to enable)\n'
  fi
  return 0
}
