#!/usr/bin/env bash
#
# 05b-env.sh - additive stage: bionic toolchain (env).
#
# Source is config/paths.env's ENV_DIST (env/dist/env) when any of its parts
# exist, else the loose ENV_DIR (env/{pkg,overlay,bootstrap}). Staged 1:1 into
# assets/env/{pkg,overlay,bootstrap}. Idempotent: STAGE_ASSETS is rebuilt per
# run and assets/env is removed before copying. env/ is NEVER modified.

log "[5b/9] assets/env (bionic toolchain)"
ENV_ASSETS="$STAGE_ASSETS/env"

# Resolve the source root (env/dist/env preferred), accepting either a real
# gzip bootstrap.tar.gz or a plain bootstrap.tar.
ENV_ROOT=""
if [ -d "$ENV_DIST/pkg" ] || [ -d "$ENV_DIST/overlay" ] \
        || [ -f "$ENV_DIST/bootstrap/bootstrap.tar.gz" ] \
        || [ -f "$ENV_DIST/bootstrap/bootstrap.tar" ]; then
    ENV_ROOT="$ENV_DIST"
    log "  source: env/dist/env"
elif [ -d "$ENV_DIR/pkg" ] || [ -d "$ENV_DIR/overlay" ] \
        || [ -f "$ENV_DIR/bootstrap/bootstrap.tar.gz" ] \
        || [ -f "$ENV_DIR/bootstrap/bootstrap.tar" ]; then
    ENV_ROOT="$ENV_DIR"
    log "  source: env/{pkg,overlay,bootstrap}"
else
    log "  env/ absent or empty, skipped"
fi

if [ -n "$ENV_ROOT" ]; then
    [ -d "$ENV_ROOT/pkg" ]     || die "env pkg missing: $ENV_ROOT/pkg"
    [ -d "$ENV_ROOT/overlay" ] || die "env overlay missing: $ENV_ROOT/overlay"

    # Never ship a plain .tar under a .gz name (or the reverse): verify by
    # content, not by extension, and gzip a plain tar at stage time.
    ENV_TGZ="$ENV_ROOT/bootstrap/bootstrap.tar.gz"
    ENV_TAR="$ENV_ROOT/bootstrap/bootstrap.tar"
    if [ -f "$ENV_TGZ" ]; then
        file -b "$ENV_TGZ" | grep -q '^gzip compressed' \
            || die "env bootstrap is not gzip despite .tar.gz name: $ENV_TGZ"
        BOOTSTRAP_SRC="$ENV_TGZ"
        BOOTSTRAP_GZ=1
    elif [ -f "$ENV_TAR" ] && file -b "$ENV_TAR" | grep -q 'tar archive'; then
        BOOTSTRAP_SRC="$ENV_TAR"
        BOOTSTRAP_GZ=0
    else
        die "env bootstrap missing: $ENV_ROOT/bootstrap/bootstrap.{tar.gz,tar}"
    fi

    rm -rf "$ENV_ASSETS"
    mkdir -p "$ENV_ASSETS/bootstrap"
    cp -a "$ENV_ROOT/pkg"     "$ENV_ASSETS/pkg"
    cp -a "$ENV_ROOT/overlay" "$ENV_ASSETS/overlay"
    BOOTSTRAP_STAGED="$ENV_ASSETS/bootstrap/bootstrap.tar.gz"
    if [ "$BOOTSTRAP_GZ" -eq 1 ]; then
        cp -f "$BOOTSTRAP_SRC" "$BOOTSTRAP_STAGED"
    else
        log "  source bootstrap is plain tar; gzip -9 -> bootstrap.tar.gz"
        gzip -9 -c "$BOOTSTRAP_SRC" > "$BOOTSTRAP_STAGED"
    fi
    # fail-closed: the staged asset must be a real gzip named .tar.gz
    file -b "$BOOTSTRAP_STAGED" | grep -q '^gzip compressed' \
        || die "staged bootstrap is not gzip: $BOOTSTRAP_STAGED"
    gzip -t "$BOOTSTRAP_STAGED" || die "staged bootstrap gzip integrity failed: $BOOTSTRAP_STAGED"

    # byte-for-byte invariant for the copyable trees
    for _tree in pkg overlay; do
        diff -r "$ENV_ROOT/$_tree" "$ENV_ASSETS/$_tree" >/dev/null 2>&1 \
            || die "invariant failed: assets/env/$_tree != $ENV_ROOT/$_tree"
    done
    log "  invariant ok: assets/env == $ENV_ROOT (pkg, overlay, bootstrap.tar.gz=gzip)"
fi
