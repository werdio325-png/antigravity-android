#!/usr/bin/env bash
#
# build.sh - AGY v2 thin build orchestrator.
#
#   core/language_server (pristine)  -> build/staging/language_server
#   patches/patch_runner.py <staging> (fail-closed)             [skip with --no-patch]
#   staging + glibc -> assets/runtime/{bin,lib} (NO lib/arm64-v8a copy)
#   core/{glibc,tools,certs,seed} -> assets/runtime/{bin,lib,certs,seed}
#   assets/runtime/manifest.json  (sha256 of every bundled runtime file)
#   web/ (built via web/build.sh) -> assets/web (byte-for-byte invariant)
#   bus/ -> assets/bus (if present)
#   env/dist/env/ (or env/{pkg,overlay,bootstrap}) -> assets/env/{pkg,overlay,bootstrap}
#   gen_android_config + aapt (R.java) -> javac -> d8 -> aapt (-A assets) -> zip
#   -> zipalign -> apksigner -> dist/agy.apk -> apksigner verify
#
# Flags:
#   --region-bypass  build release with region/eligibility gate bypass (dist/antigravity-bypass.apk)
#   --dev            build dev edition (com.agy.dev, Antigravity Dev, port 45158, dist/antigravity-dev.apk)
#   --no-patch       debug: use the vanilla core, skip patch_runner.
#   --dry-run        run the patch stage with --dry-run only; build nothing.
#
# This file is only the orchestrator. Config is loaded by build/lib/paths.sh and
# every step lives in build/phases/*.sh, all SOURCED (not executed) in lexical
# order so die()/variables propagate.
#
# Paths contain spaces and Cyrillic: every expansion is quoted, LANG/LC_ALL
# are forced to UTF-8 so javac/d8 accept the paths.
#
set -euo pipefail

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# PROJECT root = this script's directory (paths may contain spaces).
PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PROJECT

. "$PROJECT/build/lib/log.sh"

. "$PROJECT/build/lib/flags.sh"
parse_args "$@"

. "$PROJECT/build/lib/paths.sh"
setup_paths

. "$PROJECT/build/lib/prereqs.sh"
check_tools

clean_build

# Run every phase in lexical order (00-dev, 01-stage-core, ... 09b-verify).
shopt -s nullglob
for p in "$BUILD_LIB_DIR"/../phases/*.sh; do
    . "$p"
done
shopt -u nullglob
