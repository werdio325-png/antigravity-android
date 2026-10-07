#!/usr/bin/env bash
#
# flags.sh - command-line parsing for build.sh.
#
# Sourced, then parse_args "$@" is called before paths/config are loaded.
# Exports NO_PATCH / DRY_RUN / DEV_MODE so sourced phases see them.

parse_args() {
    NO_PATCH=0
    DRY_RUN=0
    DEV_MODE=0
    REGION_BYPASS=0
    for arg in "$@"; do
        case "$arg" in
            --dev)                    DEV_MODE=1 ;;
            --no-patch)               NO_PATCH=1 ;;
            --dry-run)                DRY_RUN=1 ;;
            --region-bypass|--bypass) REGION_BYPASS=1 ;;
            *) echo "unknown flag: $arg" >&2; exit 2 ;;
        esac
    done
    export NO_PATCH DRY_RUN DEV_MODE REGION_BYPASS
}
