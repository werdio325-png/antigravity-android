#!/usr/bin/env bash
#
# 03-glibc-libs.sh - collect glibc shared objects for the bundled loader.

log "[3/9] collect glibc libs (assets/runtime only; no lib/arm64-v8a duplicate)"
# RuntimeManager runs the core from getFilesDir()/runtime/bin and resolves the
# glibc stack through --library-path getFilesDir()/runtime/lib. Nothing execs
# from nativeLibraryDir, so the core + glibc are NOT staged under lib/.
shopt -s nullglob
glibc_libs=("$CORE_GLIBC"/*.so*)
shopt -u nullglob
[ "${#glibc_libs[@]}" -gt 0 ] || die "no glibc libs found in $CORE_GLIBC"
