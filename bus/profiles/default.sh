# default - generic prefix, sane network defaults. Override anything from the
# environment or copy this file.
TARGET="${TARGET:-}"
MODULES="${MODULES:-prefix glibc certs resolv net}"
EXTRA_ARGS="${EXTRA_ARGS:-}"

PREFIX="${PREFIX:-/data/data/com.agy/files/usr}"
GLIBC_PREFIX="${GLIBC_PREFIX:-$PREFIX/glibc}"
USE_LOADER="${USE_LOADER:-0}"

DNS_MODE="${DNS_MODE:-go}"
DNS_SERVERS="${DNS_SERVERS:-1.1.1.1 8.8.8.8}"
