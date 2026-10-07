# profile - load a profile by name into the current shell.
_load_profile() { # $1 = profile name; sets PROFILE and sources the file
  PROFILE="${1:-${BUS_PROFILE:-default}}"
  local pf="$PROFILES_DIR/$PROFILE.sh"
  [ -r "$pf" ] || { _log "no profile '$PROFILE'"; return 1; }
  # shellcheck disable=SC1090
  . "$pf"
  return 0
}
