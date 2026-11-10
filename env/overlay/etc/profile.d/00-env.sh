# 00-env.sh -- core environment of the tlx prefix.
export HOME="${HOME:-$(dirname "$PREFIX")}"
export TMPDIR="$PREFIX/tmp"
export PATH="$PREFIX/bin:/system/bin:${PATH:-}"
export LD_LIBRARY_PATH="$PREFIX/lib"
if [ -f "$PREFIX/lib/liblse_compat.so" ]; then
    export LD_PRELOAD="$PREFIX/lib/liblse_compat.so:$PREFIX/lib/libtermux-exec.so"
else
    export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so"
fi
export TERMUX__PREFIX="$PREFIX"
export TERMUX__ROOTFS="/"
export TERMUX_APP__DATA_DIR="$(dirname "$PREFIX")"
# Bridges set the package id for core-launched tools; derive it for direct shells.
if [ -z "${AGY_APPLICATION_ID:-}" ]; then
    _agy_pkg_dir="$(dirname "$(dirname "$PREFIX")")"
    AGY_APPLICATION_ID="$(basename "$_agy_pkg_dir")"
    unset _agy_pkg_dir
fi
export AGY_APPLICATION_ID
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"
export GIT_CONFIG_NOSYSTEM=1
export GIT_ATTR_NOSYSTEM=1
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"
export PAGER="less"
export OPENSSL_CONF="$PREFIX/etc/tls/openssl.cnf"
export LANG="${LANG:-C.UTF-8}"
export TZ="${TZ:-UTC}"
# CPU & toolchain compatibility: baseline ARMv8.0-A with dynamic outline atomics
export CFLAGS="-march=armv8-a -moutline-atomics ${CFLAGS:-}"
export CXXFLAGS="-march=armv8-a -moutline-atomics ${CXXFLAGS:-}"
mkdir -p "$TMPDIR" "$HOME" 2>/dev/null || true
