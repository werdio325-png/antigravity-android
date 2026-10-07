#!/data/data/com.agy/files/usr/bin/bash
# deb.sh -- read .deb (ar archive) + extract members. No `ar` dependency.
# Uses dd/tail/head for ar, tar for payloads, and xz/gzip/zstd/bzip2 by suffix.

# ar_members FILE -> member names
ar_members() {
    local file="$1" off=8 hdr name size data
    while :; do
        hdr="$(dd if="$file" bs=1 skip="$off" count=60 2>/dev/null)"
        [ "${#hdr}" -ge 58 ] || break
        name="$(printf '%s' "$hdr" | cut -c1-16 | sed 's/[[:space:]]*$//; s:/$::')"
        size="$(printf '%s' "$hdr" | cut -c49-58 | sed 's/ //g')"
        [ -n "$name" ] && [ -n "$size" ] || break
        printf '%s\n' "$name"
        data=$((off + 60))
        off=$((data + size + (size % 2)))
    done
}

# ar_member FILE NAME -> raw member bytes on stdout
ar_member() {
    local file="$1" want="$2" off=8 hdr name size data
    while :; do
        hdr="$(dd if="$file" bs=1 skip="$off" count=60 2>/dev/null)"
        [ "${#hdr}" -ge 58 ] || return 1
        name="$(printf '%s' "$hdr" | cut -c1-16 | sed 's/[[:space:]]*$//; s:/$::')"
        size="$(printf '%s' "$hdr" | cut -c49-58 | sed 's/ //g')"
        [ -n "$size" ] || return 1
        data=$((off + 60))
        if [ "$name" = "$want" ]; then
            tail -c +$((data + 1)) "$file" | head -c "$size"
            return 0
        fi
        off=$((data + size + (size % 2)))
    done
}

deb_decode() { # NAME  (reads stdin)
    case "$1" in
        *.xz)  xz -dc ;;
        *.gz)  gzip -dc ;;
        *.zst) zstd -dc ;;
        *.bz2) bzip2 -dc ;;
        *)     cat ;;
    esac
}

deb_member_name() { # FILE control|data
    ar_members "$1" | grep -E "^$2\.tar\." | head -n 1
}

deb_field() { # FILE FIELD -> value (authoritative control)
    local f="$1" field="$2" m tmp val
    m="$(deb_member_name "$f" control)"
    [ -n "$m" ] || return 1
    tmp="$(mktemp -d)"
    ar_member "$f" "$m" | deb_decode "$m" | tar -x -C "$tmp" 2>/dev/null
    val="$(awk -v k="$field" 'index($0, k":") == 1 { sub(/^[^:]*: /, ""); print; exit }' "$tmp/control")"
    rm -rf "$tmp"
    printf '%s\n' "$val"
}

deb_extract_data() { # FILE DEST [STRIP]
    local m; m="$(deb_member_name "$1" data)"
    [ -n "$m" ] || return 1
    ar_member "$1" "$m" | deb_decode "$m" | tar -x --strip-components="${3:-6}" -C "$2" 2>/dev/null
}

deb_files() { # FILE -> relative paths under the stripped data prefix
    local m strip="${PKG_DEB_STRIP:-6}"
    m="$(deb_member_name "$1" data)"
    [ -n "$m" ] || return 1
    # Strip the leading N path components by position instead of matching a
    # hardcoded prefix string. The data prefix is the foreign (Termux) prefix
    # but has the same depth as ours, and the rewrite pass repoints every
    # occurrence of the foreign prefix -- including this file -- so matching a
    # literal here would break after installation.
    ar_member "$1" "$m" | deb_decode "$m" \
        | tar -t 2>/dev/null \
        | awk -v s="$strip" -F/ '{
              n = NF
              if (n > 0 && $n == "") n--
              if (n <= s) next
              out = ""
              for (i = s + 1; i <= n; i++) out = out (i > s + 1 ? "/" : "") $i
              print out
          }' \
        | grep -v '^$' | sort -u
}
