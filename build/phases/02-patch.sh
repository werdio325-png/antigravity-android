#!/usr/bin/env bash
#
# 02-patch.sh - patch pipeline (fail-closed).
#
# --no-patch keeps the vanilla core (debug only).
# --dry-run runs the patch stage with --dry-run only and stops before packaging.

log "[2/9] patch pipeline (fail-closed)"
PATCH_EXTRA_FLAGS=()
if [ "${REGION_BYPASS:-0}" -eq 1 ]; then
    log "  region bypass enabled (applying eligibility & auth gates patch)"
else
    log "  standard release: region gates bypass disabled"
    PATCH_EXTRA_FLAGS+=(--skip-gates)
fi

if [ "$NO_PATCH" -eq 1 ]; then
    log "  --no-patch: keeping vanilla core (unpatched, debug only)"
elif [ "$DRY_RUN" -eq 1 ]; then
    python3 "$PATCHES_DIR/patch_runner.py" "$STAGED_CORE" "${PATCH_EXTRA_FLAGS[@]}" --dry-run
    log "--dry-run: stopping before packaging"
    exit 0
else
    python3 "$PATCHES_DIR/patch_runner.py" "$STAGED_CORE" "${PATCH_EXTRA_FLAGS[@]}"
    log "  verifying staged core"
    python3 "$PATCHES_DIR/patch_runner.py" "$STAGED_CORE" "${PATCH_EXTRA_FLAGS[@]}" --verify
fi
[ -f "$STAGED_CORE" ] || die "staging core missing after patch stage"
