#!/usr/bin/env bash
#
# 01-stage-core.sh - copy the pristine core out of the vendor tree.

log "[1/9] stage core copy"
if [ -f "$CORE_SRC" ]; then
    cp -f "$CORE_SRC" "$STAGED_CORE"
elif [ -f "${CORE_SRC}.tar.gz" ]; then
    log "  unpacking core archive: ${CORE_SRC}.tar.gz"
    python3 -c "
import tarfile, sys
with tarfile.open(sys.argv[1], 'r:gz') as tar:
    tar.extract('language_server', path=sys.argv[2])
" "${CORE_SRC}.tar.gz" "$(dirname "$STAGED_CORE")"
    chmod +x "$STAGED_CORE"
else
    die "core binary or archive not found: $CORE_SRC"
fi
