# 20-certs - trust store for the glibc/Go core. Channel: certs.
# profile vars: CA_FILE, CA_DIR, CURL_CA_BUNDLE, GIT_SSL_CAINFO
certs_up() {
  local candidates="\
${CA_FILE:-} \
${GLIBC_PREFIX:-$PREFIX}/etc/tls/cert.pem \
${PREFIX:-}/etc/tls/cert.pem \
/etc/ssl/certs/ca-certificates.crt \
/etc/ssl/cert.pem \
/etc/pki/tls/certs/ca-bundle.crt"
  local c
  for c in $candidates; do
    if [ -n "$c" ] && [ -r "$c" ]; then export SSL_CERT_FILE="$c"; break; fi
  done
  [ -n "${SSL_CERT_FILE:-}" ] || _warn "certs: no CA bundle found; set CA_FILE"
  export CURL_CA_BUNDLE="${CURL_CA_BUNDLE:-${SSL_CERT_FILE:-}}"
  export GIT_SSL_CAINFO="${GIT_SSL_CAINFO:-${SSL_CERT_FILE:-}}"
  if [ -n "${CA_DIR:-}" ] && [ -d "$CA_DIR" ]; then export SSL_CERT_DIR="$CA_DIR"; fi
  return 0
}
certs_down() { :; }

certs_doctor() {
  if [ -n "${SSL_CERT_FILE:-}" ] && [ -r "${SSL_CERT_FILE:-}" ]; then
    printf '  [ok] CA bundle %s\n' "$SSL_CERT_FILE"; return 0
  fi
  printf '  [--] no readable CA bundle (set CA_FILE)\n'; return 1
}
