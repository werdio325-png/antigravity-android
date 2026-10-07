# 50-core - launch the language_server core. Channel: core.
#
# Composes the target CLI through the args channel and runs it as a
# long-lived service that publishes its HTTP endpoint.
#
# profile vars: CORE_BIN, CORE_PORT, WEB_BUNDLE_PATH, CORE_DEPS, CORE_EXTRA_ARGS
# CORE_PORT default tracks config/app.env PORT via APP_PORT (lib/paths.sh).
CORE_BIN="${CORE_BIN:-${TARGET:-}}"
CORE_PORT="${CORE_PORT:-${APP_PORT:-45157}}"
CORE_DEPS="${CORE_DEPS:-prefix glibc certs resolv net}"

core_up() {
  # nothing to export: the core is driven entirely by its args channel
  return 0
}
core_down() { :; }

core_args() {
  printf '%s\n' "-http_server_port=${CORE_PORT:-${APP_PORT:-45157}}"
  [ -n "${WEB_BUNDLE_PATH:-}" ] && printf '%s\n' "-web_bundle_path=$WEB_BUNDLE_PATH"
  [ -n "${WEB_CSRF:-}" ]        && printf '%s\n' "-csrf_token=$WEB_CSRF"
  local a; for a in ${CORE_EXTRA_ARGS:-}; do printf '%s\n' "$a"; done
  return 0
}

core_svc_up() {
  local bin="${CORE_BIN:-${TARGET:-}}"
  [ -n "$bin" ] || { _warn "core: no CORE_BIN/TARGET set"; return 1; }
  # bring up the layers the core depends on (loader, libs, certs, dns)
  _compose "$CORE_DEPS" || _warn "core: dependency composition reported errors"
  local -a cmd=()
  local x
  for x in "${BUS_CMD[@]}"; do cmd+=("$x"); done
  cmd+=("$bin")
  while IFS= read -r x; do [ -n "$x" ] && cmd+=("$x"); done < <(core_args)
  _endpoint core_http "http://127.0.0.1:${CORE_PORT:-${APP_PORT:-45157}}"
  _endpoint core_bin  "$bin"
  _spawn core "${cmd[@]}"
}

core_svc_down() { _svc_stop core; }

core_svc_status() {
  if _svc_alive core; then
    _log "core: up (pid $(_svc_pid core), :${CORE_PORT:-${APP_PORT:-45157}})"
  else
    _log "core: down"; return 1
  fi
}

core_doctor() {
  local bin="${CORE_BIN:-${TARGET:-}}" rc=0
  if [ -n "$bin" ] && [ -r "$bin" ]; then printf '  [ok] core binary %s\n' "$bin"; else printf '  [..] core binary not set/readable: %s\n' "${bin:-<unset>}"; fi
  [ -n "${CORE_PORT:-}" ] && printf '  [ok] core port %s\n' "$CORE_PORT" || rc=1
  [ -n "${WEB_BUNDLE_PATH:-}" ] && printf '  [ok] web bundle %s\n' "$WEB_BUNDLE_PATH" || printf '  [..] web bundle not set\n'
  return $rc
}
