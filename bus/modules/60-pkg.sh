# 60-pkg - package-manager channel for the tlx prefix. Channel: pkg.
#
# EnvInstaller unpacks the bundled toolchain into the app prefix: wrapper
# $PREFIX/bin/pkg, script $PREFIX/libexec/pkg/pkg, data under
# $PREFIX/var/lib/pkg. Every path derives from $PREFIX; the default prefix is
# the only hardcoded value.
# profile vars: PKG_PREFIX, PKG_ROOT, PKG_BIN, PKG_LIBEXEC
PKG_PREFIX="${PKG_PREFIX:-${PREFIX:-/data/data/com.agy/files/usr}}"
PKG_ROOT="${PKG_ROOT:-$PKG_PREFIX/var/lib/pkg}"
PKG_BIN="${PKG_BIN:-$PKG_PREFIX/bin/pkg}"
PKG_LIBEXEC="${PKG_LIBEXEC:-$PKG_PREFIX/libexec/pkg}"

pkg_up() {
  export PKG_ROOT
  if [ -x "$PKG_BIN" ]; then
    case ":$PATH:" in *":$(dirname "$PKG_BIN"):"*) ;; *) PATH="$(dirname "$PKG_BIN"):$PATH";; esac
    export PATH
    _endpoint pkg "$PKG_BIN"
  else
    _endpoint pkg "pending:$PKG_BIN"
  fi
  return 0
}
pkg_down() { :; }

pkg_svc_status() {
  _log "pkg: ${PKG_BIN:-<unset>} (root ${PKG_ROOT:-<unset>})"
  [ -x "$PKG_BIN" ]
}

pkg_doctor() {
  if [ -x "$PKG_BIN" ]; then
    printf '  [ok] pkg %s\n' "$PKG_BIN"
  else
    printf '  [..] pkg not installed yet: %s\n' "${PKG_BIN:-<unset>}"
  fi
  if [ -d "$PKG_LIBEXEC" ]; then
    printf '  [ok] pkg libexec %s\n' "$PKG_LIBEXEC"
  else
    printf '  [..] pkg libexec missing: %s\n' "${PKG_LIBEXEC:-<unset>}"
  fi
  return 0
}
