# 30-resolv - resolver files for a glibc Go binary on Android.
#
# Android ships no /etc/resolv.conf or NSS. Termux's patched glibc reads them
# from $GLIBC_PREFIX/etc, so seed them there. DNS_MODE selects the resolver:
#   go   pure-Go (GODEBUG=netdns=go)
#   cgo  glibc resolver, uses the seeded files
# Channel: resolv.
# profile vars: DNS_MODE, GLIBC_PREFIX, DNS_SERVERS
resolv_up() {
  case "${DNS_MODE:-go}" in
    go)  _godebug "netdns=go" ;;
    cgo) _godebug "netdns=cgo"; _resolv_seed ;;
    *)   _warn "resolv: unknown DNS_MODE '${DNS_MODE:-}'" ;;
  esac
  return 0
}
resolv_down() { :; }

_resolv_seed() {
  local etc="${GLIBC_PREFIX:-$PREFIX}/etc"
  mkdir -p "$etc" 2>/dev/null || { _warn "resolv: cannot create $etc"; return 0; }
  if [ ! -s "$etc/resolv.conf" ]; then
    if [ -s "${PREFIX:-}/etc/resolv.conf" ]; then
      cp "${PREFIX}/etc/resolv.conf" "$etc/resolv.conf" 2>/dev/null
    else
      local ns
      for ns in ${DNS_SERVERS:-1.1.1.1 8.8.8.8}; do printf 'nameserver %s\n' "$ns"; done >"$etc/resolv.conf" 2>/dev/null
    fi
    _log "resolv: seeded $etc/resolv.conf"
  fi
  [ -s "$etc/nsswitch.conf" ] || printf 'hosts: files dns\n' >"$etc/nsswitch.conf" 2>/dev/null
  [ -s "$etc/hosts" ]         || printf '127.0.0.1 localhost\n::1 ip6-localhost\n' >"$etc/hosts" 2>/dev/null
  return 0
}

resolv_doctor() {
  local g; g="$(printf '%s' "${GODEBUG:-}" | tr ',' '\n' | grep -E '^netdns=' || true)"
  printf '  [ok] %s\n' "${g:-netdns=<unset>}"
  case "${g:-}" in
    *cgo*)
      local rc="${GLIBC_PREFIX:-$PREFIX}/etc/resolv.conf"
      [ -s "$rc" ] && printf '  [ok] %s\n' "$rc" || printf '  [--] cgo mode but $rc missing\n'
      ;;
  esac
  return 0
}
