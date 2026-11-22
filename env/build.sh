#!/usr/bin/env bash
# build.sh -- pack the env workspace into APK assets (host build).
#   build.sh bootstrap   bootstrap-aarch64.zip -> bootstrap.tar.gz (symlinks+modes)
#   build.sh assets      stage pkg/overlay/bootstrap into dist/env (1:1)
#   build.sh all
set -eo pipefail

ENV_DIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="${ASSETS:-$ENV_DIR/dist}"

if python3 -c "import sys" >/dev/null 2>&1; then
    PYTHON="python3"
elif python -c "import sys" >/dev/null 2>&1; then
    PYTHON="python"
else
    printf 'build: python3 not found\n' >&2
    exit 1
fi

log() { printf 'build: %s\n' "$*" >&2; }

build_bootstrap_tar() {
    local zip="$ENV_DIR/bootstrap/bootstrap-aarch64.zip"
    local out="$ENV_DIR/bootstrap/bootstrap.tar.gz"
    [ -f "$zip" ] || { log "missing $zip"; return 1; }
    local extra_args=()
    for deb in "$ENV_DIR/packages"/*.deb; do
        [ -f "$deb" ] && extra_args+=("--extra-deb" "$deb")
    done
    "$PYTHON" -B "$ENV_DIR/scripts/pack_bootstrap.py" --zip "$zip" --out "$out" "${extra_args[@]}"
    log "bootstrap.tar.gz: $(du -h "$out" | cut -f1)"
}

pack_assets() {
    [ -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" ] || build_bootstrap_tar
    rm -rf "$ASSETS"; mkdir -p "$ASSETS/env/bootstrap"
    cp -a "$ENV_DIR/pkg"     "$ASSETS/env/pkg"
    cp -a "$ENV_DIR/overlay" "$ASSETS/env/overlay"
    cp -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" "$ASSETS/env/bootstrap/"
    [ -f "$ENV_DIR/seed-packages.txt" ] && cp -f "$ENV_DIR/seed-packages.txt" "$ASSETS/env/seed-packages.txt" || true
    # Strip any __pycache__/*.pyc and normalize CRLF -> LF on staged text/scripts
    "$PYTHON" -B "$ENV_DIR/scripts/sanitize_stage.py" "$ASSETS/env/pkg" "$ASSETS/env/overlay" \
        $( [ -f "$ASSETS/env/seed-packages.txt" ] && printf '%s' "$ASSETS/env/seed-packages.txt" )
    "$PYTHON" -B "$ENV_DIR/scripts/make_manifest.py" "$ASSETS/env"
    log "assets -> $ASSETS"
    find "$ASSETS" -maxdepth 3 | sed 's/^/  /' >&2
}

case "${1:-all}" in
    bootstrap) build_bootstrap_tar ;;
    assets)    pack_assets ;;
    all)       pack_assets ;;
    *) printf 'usage: build.sh [bootstrap|assets|all]\n' >&2; exit 1 ;;
esac
