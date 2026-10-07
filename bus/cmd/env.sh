# env - compose a profile and print the resulting environment.
cmd_env() {
  local _profile_default="default"
  while [ $# -gt 0 ]; do
    case "$1" in
      --profile) _profile_default="$2"; shift 2;;
      *) break;;
    esac
  done
  _load_profile "$_profile_default" || exit 2
  : "${MODULES:=prefix}"
  ( _compose "$MODULES"
    env | LC_ALL=C sort \
      | grep -E '^(PATH|LD_LIBRARY_PATH|LD_PRELOAD|GODEBUG|SSL_CERT[A-Z_]*|CURL_CA_BUNDLE|GIT_SSL_CAINFO|HTTP|HTTPS|NO_PROXY|http_proxy|https_proxy|no_proxy|PKG_ROOT|PREFIX|HOME|TMPDIR|ANTIGRAVITY_|AG_|LANG|LC_ALL|TZ)=' \
      | sed 's/^/  /' )
  exit 0
}
