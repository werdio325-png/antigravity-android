#!/usr/bin/env bash
# build.sh -- pack the env workspace into APK assets (host build).
#   build.sh bootstrap   bootstrap-aarch64.zip -> bootstrap.tar.gz (symlinks+modes)
#   build.sh assets      stage pkg/overlay/bootstrap into dist/env (1:1)
#   build.sh all
set -eo pipefail

ENV_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="${ASSETS:-$ENV_DIR/dist}"

log() { printf 'build: %s\n' "$*" >&2; }

build_bootstrap_tar() {
    local zip="$ENV_DIR/bootstrap/bootstrap-aarch64.zip"
    local out="$ENV_DIR/bootstrap/bootstrap.tar.gz"
    [ -f "$zip" ] || { log "missing $zip"; return 1; }
    python3 "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out"
    log "bootstrap.tar.gz: $(du -h "$out" | cut -f1)"
}

pack_assets() {
    [ -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" ] || build_bootstrap_tar
    rm -rf "$ASSETS"; mkdir -p "$ASSETS/env/bootstrap"
    cp -a "$ENV_DIR/pkg"     "$ASSETS/env/pkg"
    cp -a "$ENV_DIR/overlay" "$ASSETS/env/overlay"
    cp -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" "$ASSETS/env/bootstrap/"
    log "assets -> $ASSETS"
    find "$ASSETS" -maxdepth 3 | sed 's/^/  /' >&2
}

case "${1:-all}" in
    bootstrap) build_bootstrap_tar ;;
    assets)    pack_assets ;;
    all)       build_bootstrap_tar; pack_assets ;;
    *) printf 'usage: build.sh [bootstrap|assets|all]\n' >&2; exit 1 ;;
esac
