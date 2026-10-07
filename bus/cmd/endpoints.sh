# endpoints - print the published endpoint registry.
cmd_endpoints() {
  if [ -s "$ENDPOINTS_FILE" ]; then
    printf 'endpoints (%s):\n' "$ENDPOINTS_FILE"; sed 's/^/  /' "$ENDPOINTS_FILE"
  else
    printf 'endpoints: (empty; %s)\n' "$ENDPOINTS_FILE"
  fi
}
