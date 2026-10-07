# 40-net - outbound proxy passthrough and gRPC/ALPN tuning. Channel: net.
# No values are hardcoded; set them in the profile or environment.
# profile vars: PROXY_URL, NO_PROXY_LIST, SVC_ENFORCE_ALPN, GOTRACEBACK
net_up() {
  if [ -n "${PROXY_URL:-}" ]; then
    export HTTP_PROXY="$PROXY_URL"  HTTPS_PROXY="$PROXY_URL"
    export http_proxy="$PROXY_URL"  https_proxy="$PROXY_URL"
  fi
  if [ -n "${NO_PROXY_LIST:-}" ]; then
    export NO_PROXY="$NO_PROXY_LIST" no_proxy="$NO_PROXY_LIST"
  fi
  if [ "${SVC_ENFORCE_ALPN:-0}" = 1 ]; then export GRPC_ENFORCE_ALPN_ENABLED=true; fi
  export GOTRACEBACK="${GOTRACEBACK:-none}"
  return 0
}
net_down() { :; }

net_doctor() {
  if [ -n "${PROXY_URL:-}" ]; then printf '  [ok] proxy %s\n' "$PROXY_URL"; else printf '  [..] no proxy configured\n'; fi
  return 0
}
