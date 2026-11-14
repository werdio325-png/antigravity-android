#!/system/bin/sh
# broadcast_emitter.sh - Dispatches external Android broadcast for an incoming event.
# Reads JSON event envelope from stdin.

read -r RAW_JSON || exit 0
[ -z "$RAW_JSON" ] && exit 0

URL=$(echo "$RAW_JSON" | grep -o '"url"[[:space:]]*:[[:space:]]*"[^"]*' | head -n 1 | cut -d'"' -f4)

if [ -n "$URL" ] && [ -x /system/bin/am ]; then
    /system/bin/am broadcast -a dev.continuum.antigravity.ACTION_OPEN_URL -p dev.continuum.antigravity --es url "$URL" >/dev/null 2>&1 || true
    /system/bin/am broadcast -a dev.continuum.antigravity.ACTION_OPEN_URL -p dev.continuum.antigravity.dev --es url "$URL" >/dev/null 2>&1 || true
fi
exit 0
