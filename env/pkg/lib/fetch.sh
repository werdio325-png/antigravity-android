#!/data/data/dev.continuum.antigravity/files/usr/bin/bash
# fetch.sh -- download with sha256 verification and cache.
# Requires curl, sha256sum, log, die.

# human_size BYTES
human_size() {
    local bytes="$1"
    if [ -z "$bytes" ] || [ "$bytes" -le 0 ] 2>/dev/null; then
        echo ""
    elif [ "$bytes" -ge 1048576 ]; then
        awk -v b="$bytes" 'BEGIN { printf "%.1f MB", b / 1048576 }'
    elif [ "$bytes" -ge 1024 ]; then
        awk -v b="$bytes" 'BEGIN { printf "%d kB", (b + 1023) / 1024 }'
    else
        printf '%d B' "$bytes"
    fi
}

# fetch URL SHA256 OUT [SIZE] [GET_INDEX]
fetch() {
    local url="$1" sha="$2" out="$3" size="${4:-}" idx="${5:-1}" have sz_str=""
    if [ -f "$out" ] && [ -n "$sha" ]; then
        have="$(sha256sum "$out" | cut -d' ' -f1)"
        if [ "$have" = "$sha" ]; then
            return 0
        fi
    fi
    mkdir -p "$(dirname "$out")"
    if [ -n "$size" ]; then
        sz_str=" [$(human_size "$size")]"
    fi
    printf 'Get:%s %s%s\n' "$idx" "$url" "$sz_str"
    curl -# -fL --retry 3 --connect-timeout 20 --cacert "$PKG_CERT" -o "$out.part" "$url" \
        || { rm -f "$out.part"; die "download failed: $url"; }
    if [ -n "$sha" ]; then
        have="$(sha256sum "$out.part" | cut -d' ' -f1)"
        [ "$have" = "$sha" ] || { rm -f "$out.part"; die "sha256 mismatch $(basename "$out"): got $have want $sha"; }
    fi
    mv "$out.part" "$out"
}
