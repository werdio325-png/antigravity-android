# doctor - verify every layer a profile's target needs.
cmd_doctor() {
  local _profile_default="default"
  while [ $# -gt 0 ]; do
    case "$1" in
      --profile) _profile_default="$2"; shift 2;;
      *) break;;
    esac
  done
  _load_profile "$_profile_default" || exit 2
  : "${MODULES:=prefix}"
  # run module ups, then generic + per-module checks
  ( _compose "$MODULES"; _rc=0
    _ok(){ printf '  [ok] %s\n' "$1"; }
    _no(){ printf '  [--] %s\n' "$1"; _rc=1; }
    _opt(){ printf '  [..] %s\n' "$1"; }
    _is(){ command -v "$1" >/dev/null 2>&1; }
    echo "profile: $PROFILE"
    echo "target : ${TARGET:-<unset>}"
    echo "prefix : ${PREFIX:-<unset>}"
    echo "loader :"
    _ld="${LOADER:-${GLIBC_PREFIX:-${PREFIX:-}}/lib/ld-linux-aarch64.so.1}"
    if [ -x "$_ld" ]; then _ok "$_ld"; else _no "loader not executable: $_ld"; fi
    echo "libs:"
    [ -d "${TARGET_LIB_DIR:-${GLIBC_PREFIX:-${PREFIX:-}}/lib}" ] \
      && _ok "${TARGET_LIB_DIR:-${GLIBC_PREFIX:-$PREFIX}/lib}" \
      || _no "lib dir missing: ${TARGET_LIB_DIR:-?}"
    echo "tls:"
    if [ -n "${SSL_CERT_FILE:-}" ] && [ -r "${SSL_CERT_FILE:-}" ]; then _ok "$SSL_CERT_FILE"; else _no "no readable CA bundle (set CA_FILE)"; fi
    echo "dns:"
    _g="$(printf '%s' "${GODEBUG:-}" | tr ',' '\n' | grep -E '^netdns=' || true)"
    [ -n "$_g" ] && _ok "$_g" || _opt "netdns not set"
    echo "tools (target external deps):"
    for t in git ssh rg node; do
      p="$(_is "$t" && command -v "$t" 2>/dev/null)"
      [ -n "${p:-}" ] && _ok "$t -> $p" || _opt "$t not in PATH"
    done
    echo "modules (active channels):"
    for m in ${MODULES:-}; do
      _ch=""
      command -v "${m}_up"     >/dev/null 2>&1 && _ch="$_ch env"
      command -v "${m}_args"   >/dev/null 2>&1 && _ch="$_ch args"
      command -v "${m}_svc_up" >/dev/null 2>&1 && _ch="$_ch svc"
      printf '  %-8s ->%s\n' "$m" "$_ch"
    done
    echo "module doctors:"
    for m in ${MODULES:-}; do
      fn="${m}_doctor"
      command -v "$fn" >/dev/null 2>&1 && "$fn" || _opt "$m: no doctor"
    done
    exit $_rc )
}
