# tlx - profile for the env/ (bionic tools) layer.
#
# The APK ships assets/env/{pkg,overlay,bootstrap}; EnvInstaller unpacks them
# into $PREFIX before core start. Every path derives from $PREFIX; the default
# prefix is the only hardcoded value.
PREFIX="${PREFIX:-/data/data/com.agy/files/usr}"
TARGET="${TARGET:-$PREFIX/bin/tlx}"
MODULES="${MODULES:-prefix glibc certs resolv net pkg files}"
EXTRA_ARGS="${EXTRA_ARGS:-}"

GLIBC_PREFIX="${GLIBC_PREFIX:-$PREFIX/glibc}"
USE_LOADER="${USE_LOADER:-0}"

# pkg channel: wrapper, script and data all live under $PREFIX.
PKG_PREFIX="${PKG_PREFIX:-$PREFIX}"
PKG_ROOT="${PKG_ROOT:-$PREFIX/var/lib/pkg}"
PKG_BIN="${PKG_BIN:-$PREFIX/bin/pkg}"
PKG_LIBEXEC="${PKG_LIBEXEC:-$PREFIX/libexec/pkg}"

FILES_SHIZUKU="${FILES_SHIZUKU:-0}"
EXTERNAL_STORAGE="${EXTERNAL_STORAGE:-/storage/emulated/0}"
