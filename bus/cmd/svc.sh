# svc - long-lived service lifecycle: svc up|down|status [MOD...].
cmd_svc() {
  local _act="${1:-status}"; [ $# -gt 0 ] && shift
  local _rc=0 fn m
  if [ $# -gt 0 ]; then
    for m in "$@"; do
      m="$(_norm_channel "$m")"
      fn="${m}_svc_${_act}"
      command -v "$fn" >/dev/null 2>&1 || { _warn "no ${fn}"; _rc=1; continue; }
      "$fn" || _rc=$?
    done
  else
    for m in "${BUS_MODULES_ALL[@]}"; do
      fn="${m}_svc_${_act}"
      if command -v "$fn" >/dev/null 2>&1; then "$fn" || _rc=$?; fi
    done
  fi
  exit $_rc
}
