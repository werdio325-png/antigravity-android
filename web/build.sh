#!/usr/bin/env bash
#
# web/build.sh - concatenate web/src/** fragments into web/generated/**.
#
# Reads web/src.manifest. Each `bundle [iife] <out>` opens a bundle; the
# following `src/...` lines are concatenated in order into web/generated/<out>.
# IIFE bundles are wrapped as:
#     (function(){
#     <fragments>
#     })();
# Non-iife bundles are copied verbatim (fragments share plain scope). Blank
# lines are ignored; `#` and `;` start a comment. Idempotent: generated/ is
# rebuilt from scratch, then every file is `node --check`ed when node exists.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$SCRIPT_DIR/src.manifest"
GEN_DIR="$SCRIPT_DIR/generated"

die() { printf 'build.sh: %s\n' "$*" >&2; exit 1; }

[ -f "$MANIFEST" ] || die "manifest not found: $MANIFEST"

if [ -f "$SCRIPT_DIR/patch_main.py" ] && [ -f "$SCRIPT_DIR/main.js" ]; then
    python3 "$SCRIPT_DIR/patch_main.py" "$SCRIPT_DIR/main.js" || die "web/patch_main.py failed"
fi

rm -rf "$GEN_DIR"
mkdir -p "$GEN_DIR"

bundle_tmp="$(mktemp "${TMPDIR:-/tmp}/agy-web-bundle.XXXXXX")"
trap 'rm -f "$bundle_tmp"' EXIT

current_out=""
current_iife=0
have_bundle=0

bundle_names=()
bundle_sizes=()
src_count=0

emit_bundle() {
    [ "$have_bundle" -eq 1 ] || return 0
    local dest="$GEN_DIR/$current_out"
    mkdir -p "$(dirname "$dest")"
    if [ "$current_iife" -eq 1 ]; then
        {
            printf '(function(){\n'
            cat "$bundle_tmp"
            printf '\n})();\n'
        } > "$dest"
    else
        cat "$bundle_tmp" > "$dest"
    fi
    local size
    size=$(wc -c < "$dest" | tr -d ' ')
    bundle_names+=("$current_out")
    bundle_sizes+=("$size")
    : > "$bundle_tmp"
    current_out=""
    current_iife=0
    have_bundle=0
}

while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    # trim leading then trailing whitespace
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"

    [ -z "$line" ] && continue
    case "$line" in
        '#'*|';'*) continue ;;
    esac

    case "$line" in
        bundle\ *|bundle)
            emit_bundle
            read -r _ a b <<< "$line"
            if [ "${a:-}" = "iife" ]; then
                current_out="${b:-}"
                current_iife=1
            else
                current_out="${a:-}"
                current_iife=0
            fi
            [ -n "$current_out" ] || die "bundle line missing output: $line"
            have_bundle=1
            ;;
        src/*)
            [ "$have_bundle" -eq 1 ] || die "src line before any bundle: $line"
            src_path="$SCRIPT_DIR/$line"
            [ -f "$src_path" ] || die "missing source: $line"
            cat "$src_path" >> "$bundle_tmp"
            printf '\n' >> "$bundle_tmp"
            src_count=$((src_count + 1))
            ;;
        *)
            die "unrecognized manifest line: $line"
            ;;
    esac
done < "$MANIFEST"

emit_bundle

[ "${#bundle_names[@]}" -gt 0 ] || die "no bundles emitted"

printf 'web: generated %d bundle(s) from %d fragment(s)\n' \
    "${#bundle_names[@]}" "$src_count"
i=0
while [ "$i" -lt "${#bundle_names[@]}" ]; do
    printf '  %-42s %8s bytes\n' "${bundle_names[$i]}" "${bundle_sizes[$i]}"
    i=$((i + 1))
done

if command -v node >/dev/null 2>&1; then
    printf 'node --check:\n'
    i=0
    while [ "$i" -lt "${#bundle_names[@]}" ]; do
        dest="$GEN_DIR/${bundle_names[$i]}"
        if node --check "$dest"; then
            printf '  ok   %s\n' "${bundle_names[$i]}"
        else
            die "node --check failed: ${bundle_names[$i]}"
        fi
        i=$((i + 1))
    done
else
    printf 'node not found; skipped node --check\n'
fi

exit 0
