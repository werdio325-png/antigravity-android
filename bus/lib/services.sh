# services - long-lived daemon lifecycle under $SVC_DIR (pid files + logs).

# Start a daemon detached from this process, record its pid under $SVC_DIR.
_spawn() { # $1=name $2..=command
  local name="$1"; shift
  [ $# -gt 0 ] || { _warn "spawn $name: empty command"; return 1; }
  local pidfile="$SVC_DIR/$name.pid" log="$SVC_DIR/$name.log"
  if _svc_alive "$name"; then _log "svc $name already running ($(cat "$pidfile"))"; return 0; fi
  if command -v setsid >/dev/null 2>&1; then
    setsid "$@" >>"$log" 2>&1 & echo $! >"$pidfile"
  else
    nohup "$@" >>"$log" 2>&1 & echo $! >"$pidfile"
  fi
  sleep 0.3
  if _svc_alive "$name"; then
    _log "svc $name up (pid $(cat "$pidfile"))"
  else
    _warn "svc $name failed, see $log"; return 1
  fi
}

_svc_pid()   { [ -f "$SVC_DIR/$1.pid" ] && cat "$SVC_DIR/$1.pid"; }
_svc_alive() { local p; p="$(_svc_pid "$1")"; [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }
_svc_stop()  { # $1=name
  local p; p="$(_svc_pid "$1")" || return 0
  [ -n "$p" ] && kill "$p" 2>/dev/null && _log "svc $1 stopped"
  rm -f "$SVC_DIR/$1.pid"
}
