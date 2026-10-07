# core - launch the patched language_server (Go/cgo, aarch64, glibc).
TARGET="${TARGET:-$BUS_DIR/../core/language_server}"
MODULES="${MODULES:-prefix glibc certs resolv net core}"
EXTRA_ARGS="${EXTRA_ARGS:-}"

PREFIX="${PREFIX:-/data/data/com.agy/files/usr}"
GLIBC_PREFIX="${GLIBC_PREFIX:-$PREFIX/glibc}"
USE_LOADER="${USE_LOADER:-1}"

# Termux's glibc reads resolver files from $GLIBC_PREFIX/etc, so cgo works
# with no root and no proot. Pure-Go (netdns=go) does not see them.
DNS_MODE="${DNS_MODE:-cgo}"
DNS_SERVERS="${DNS_SERVERS:-1.1.1.1 8.8.8.8}"

# App HTTP port; default tracks config/app.env PORT via APP_PORT (lib/paths.sh).
CORE_PORT="${CORE_PORT:-${APP_PORT:-45157}}"
WEB_BUNDLE_PATH="${WEB_BUNDLE_PATH:-$BUS_DIR/../web}"
CORE_DEPS="${CORE_DEPS:-prefix glibc certs resolv net}"
