#!/usr/bin/env bash
#
# log.sh - logging primitives shared by every build stage.
#
# Sourced, never executed. log() prefixes a timestamp; die() is the single
# fail-closed exit path used across the pipeline.

log() { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { printf '\n[FATAL] %s\n' "$*" >&2; exit 1; }
