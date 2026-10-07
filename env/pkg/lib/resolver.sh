#!/data/data/com.agy/files/usr/bin/bash
# resolver.sh -- dependency parsing and install-order resolution.
# Requires index_lookup, index_provider (index.sh), deb_vercmp (version.sh).

# ver_satisfies VER OP WANT
ver_satisfies() {
    local c; c="$(deb_vercmp "$1" "$3")"
    case "$2" in
        ">=")     [ "$c" != "-1" ] ;;
        "<=")     [ "$c" != "1" ] ;;
        "="|"==") [ "$c" = "0" ] ;;
        ">>"|">") [ "$c" = "1" ] ;;
        "<<"|"<") [ "$c" = "-1" ] ;;
        *)        return 0 ;;
    esac
}

# choose_dep TERM -> package line (no repo prefix) or empty
choose_dep() {
    local term="$1" out="" alt nm con op ver line
    while IFS= read -r alt; do
        alt="$(printf '%s' "$alt" | sed 's/^ *//; s/ *$//')"
        [ -n "$alt" ] || continue
        nm="$(printf '%s' "$alt" | sed 's/(.*//; s/\[.*//; s/:.*//; s/[[:space:]]*$//')"
        con="$(printf '%s' "$alt" | sed -n 's/.*(\([^)]*\)).*/\1/p')"
        op="$(printf '%s' "$con" | awk '{print $1}')"
        ver="$(printf '%s' "$con" | awk '{print $2}')"
        [ -n "$nm" ] || continue
        line="$(index_lookup "$nm")"   ; line="${line#*	}"
        if [ -z "$line" ]; then
            line="$(index_provider "$nm")"; line="${line#*	}"
        fi
        [ -n "$line" ] || continue
        if [ -n "$ver" ]; then
            ver_satisfies "$(printf '%s' "$line" | cut -f2)" "$op" "$ver" || continue
        fi
        out="$line"; break
    done <<EOF
$(printf '%s' "$term" | tr '|' '\n')
EOF
    [ -n "$out" ] && printf '%s\n' "$out"
}

# resolve NAME... -> install order (deps first)
resolve() {
    local work="$PKG_ROOT/tmp/resolve.$$"
    rm -rf "$work"; mkdir -p "$work"
    local n
    for n in "$@"; do r_visit "$n" "$work"; done
    [ -f "$work/order" ] && cat "$work/order"
    rm -rf "$work"
}

r_visit() {
    local name="$1" work="$2" line deps term cand cname
    [ -f "$work/done/$name" ] && return 0
    [ -f "$work/doing/$name" ] && { warn "dependency cycle at: $name"; return 0; }
    line="$(index_lookup "$name")"; line="${line#*	}"
    [ -n "$line" ] || { warn "unknown package: $name"; return 0; }
    mkdir -p "$work/done" "$work/doing"
    : > "$work/doing/$name"
    deps="$(printf '%s' "$line" | cut -f5) $(printf '%s' "$line" | cut -f6)"
    while IFS= read -r term; do
        term="$(printf '%s' "$term" | sed 's/^ *//; s/ *$//')"
        [ -n "$term" ] || continue
        cand="$(choose_dep "$term")"
        if [ -z "$cand" ]; then warn "cannot resolve dependency: $term (for $name)"; continue; fi
        cname="$(printf '%s' "$cand" | cut -f1)"
        r_visit "$cname" "$work"
    done <<EOF
$(printf '%s' "$deps" | tr ',' '\n')
EOF
    rm -f "$work/doing/$name"; : > "$work/done/$name"
    printf '%s\n' "$name" >> "$work/order"
}
