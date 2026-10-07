# endpoints - registry of published addresses, flushed to $ENDPOINTS_FILE.

# _endpoint NAME VALUE publishes/replaces an entry in $ENDPOINTS_FILE.
_endpoint() {
  local name="$1" value="${2:-}" tmp
  [ -n "$name" ] || { _warn "endpoint: empty name"; return 0; }
  [ -n "$value" ] || { _warn "endpoint $name: empty value"; return 0; }
  tmp="$ENDPOINTS_FILE.$$"
  if [ -f "$ENDPOINTS_FILE" ]; then
    grep -v "^${name}=" "$ENDPOINTS_FILE" >"$tmp" 2>/dev/null || : >"$tmp"
  else
    : >"$tmp"
  fi
  printf '%s=%s\n' "$name" "$value" >>"$tmp"
  mv "$tmp" "$ENDPOINTS_FILE" 2>/dev/null || rm -f "$tmp"
  _log "endpoint $name -> $value"
}
