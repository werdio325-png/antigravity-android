#!/usr/bin/env bash
# notify_tray.sh - Dispatches an Antigravity event to the Android system notification tray.
# Reads JSON event envelope from stdin.

read -r RAW_JSON || exit 0
[ -z "$RAW_JSON" ] && exit 0

EVENT_NAME=$(echo "$RAW_JSON" | grep -o '"event"[[:space:]]*:[[:space:]]*"[^"]*' | head -n 1 | cut -d'"' -f4)
MESSAGE=$(echo "$RAW_JSON" | grep -o '"message"[[:space:]]*:[[:space:]]*"[^"]*' | head -n 1 | cut -d'"' -f4)
[ -z "$MESSAGE" ] && MESSAGE=$(echo "$RAW_JSON" | grep -o '"url"[[:space:]]*:[[:space:]]*"[^"]*' | head -n 1 | cut -d'"' -f4)
[ -z "$MESSAGE" ] && MESSAGE="Event received: $EVENT_NAME"

# Check if agy-notify exists
if command -v agy-notify >/dev/null 2>&1; then
    agy-notify post -t "Antigravity: $EVENT_NAME" -m "$MESSAGE" >/dev/null 2>&1 || true
elif [ -x /system/bin/am ]; then
    /system/bin/am broadcast -a dev.continuum.antigravity.ACTION_NOTIFY --es title "Antigravity" --es message "$MESSAGE" >/dev/null 2>&1 || true
else
    echo "[NOTIFY] $EVENT_NAME: $MESSAGE"
fi
exit 0
