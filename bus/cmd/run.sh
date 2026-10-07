# run - compose a profile, apply its modules, then exec the target.
# Never let anything pollute stdout: LSP/JSON-RPC runs on it.
cmd_run() {
  local PROFILE="${BUS_PROFILE:-default}" pf
  local -a _cli ARGS _final ACTIVE
  while [ $# -gt 0 ]; do
    case "$1" in
      --profile) PROFILE="$2"; shift 2;;
      --) shift; break;;
      *) break;;
    esac
  done
  pf="$PROFILES_DIR/$PROFILE.sh"
  [ -r "$pf" ] || { _log "no profile '$PROFILE'"; exit 2; }

  _cli=("$@")
  BUS_CMD=()

  # shellcheck disable=SC1090
  . "$pf"
  : "${MODULES:=prefix}"
  : "${TARGET:=}"
  ARGS=()
  if [ ${#_cli[@]} -gt 0 ]; then
    TARGET="${_cli[0]}"
    if [ ${#_cli[@]} -gt 1 ]; then ARGS=("${_cli[@]:1}"); else ARGS=(); fi
  fi
  [ -n "$TARGET" ] || { _log "profile '$PROFILE' sets no TARGET and none was passed"; exit 2; }

  ACTIVE=()
  _cleanup() {
    local rc=$? i
    for ((i=${#ACTIVE[@]}-1; i>=0; i--)); do "${ACTIVE[i]}_down" >/dev/null 2>&1 || true; done
    return $rc
  }
  trap '_cleanup; exit $?' EXIT INT TERM HUP

  local m fn
  for m in $MODULES; do
    fn="${m}_up"
    command -v "$fn" >/dev/null 2>&1 || { _log "module '$m' missing ${fn}()"; exit 3; }
    ACTIVE+=("$m")
    "$fn" || { _log "module '$m' failed"; exit 4; }
  done

  # channel: args - modules inject target flags, then EXTRA_ARGS, then CLI
  _final=()
  local _l a
  while IFS= read -r _l; do [ -n "$_l" ] && _final+=("$_l"); done < <(_collect_args "$MODULES")
  for a in ${EXTRA_ARGS:-}; do _final+=("$a"); done
  if [ ${#ARGS[@]} -gt 0 ]; then for a in "${ARGS[@]}"; do _final+=("$a"); done; fi

  if [ ${#BUS_CMD[@]} -gt 0 ]; then
    exec "${BUS_CMD[@]}" "$TARGET" "${_final[@]}"
  else
    exec "$TARGET" "${_final[@]}"
  fi
}
