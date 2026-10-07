#!/data/data/com.agy/files/usr/bin/bash
# fetch.sh -- download with sha256 verification and cache.
# Requires curl, sha256sum, log, die.

# fetch URL SHA256 OUT
fetch() {
    local url="$1" sha="$2" out="$3" have
    if [ -f "$out" ] && [ -n "$sha" ]; then
        have="$(sha256sum "$out" | cut -d' ' -f1)"
        if [ "$have" = "$sha" ]; then log "cache hit: $(basename "$out")"; return 0; fi
    fi
    mkdir -p "$(dirname "$out")"
    log "fetch: $url"
    curl -fsSL --retry 3 --connect-timeout 20 --cacert "$PKG_CERT" -o "$out.part" "$url" \
        || { rm -f "$out.part"; die "download failed: $url"; }
    if [ -n "$sha" ]; then
        have="$(sha256sum "$out.part" | cut -d' ' -f1)"
        [ "$have" = "$sha" ] || { rm -f "$out.part"; die "sha256 mismatch $(basename "$out"): got $have want $sha"; }
    fi
    mv "$out.part" "$out"
}
