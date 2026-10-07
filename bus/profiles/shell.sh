# shell - drop into an interactive bash with the prefix environment applied.
PREFIX="${PREFIX:-/data/data/com.agy/files/usr}"
TARGET="${TARGET:-$PREFIX/bin/bash}"
MODULES="${MODULES:-prefix glibc certs resolv net}"
EXTRA_ARGS="${EXTRA_ARGS:---login}"

GLIBC_PREFIX="${GLIBC_PREFIX:-$PREFIX/glibc}"
USE_LOADER="${USE_LOADER:-0}"

DNS_MODE="${DNS_MODE:-go}"
EXTERNAL_STORAGE="${EXTERNAL_STORAGE:-/storage/emulated/0}"
