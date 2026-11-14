#!/system/bin/sh
# broadcast_receiver.sh - Translates Android Intent extras into an Antigravity event envelope.
# Usage: broadcast_receiver.sh <action> <extra_json_or_msg> [destination_fifo]

ACTION="${1:-dev.continuum.antigravity.ACTION_DEFAULT}"
PAYLOAD="${2:-{}}"
FIFO="${3:-/data/user/0/dev.continuum.antigravity/files/runtime/agy_bus.pipe}"

# Resolve safe path if targeting FUSE storage (/storage or /sdcard)
case "$FIFO" in
    /storage/*|/sdcard/*|/mnt/sdcard/*|/mnt/runtime/*)
        FIFO_NAME=$(basename "$FIFO")
        SAFE_DIR="${AGY_RUNTIME_DIR:-}"
        if [ -z "$SAFE_DIR" ] || [ ! -d "$SAFE_DIR" ]; then
            for candidate in \
                "/data/user/0/dev.continuum.antigravity/files/runtime" \
                "/data/user/0/dev.continuum.antigravity.dev/files/runtime" \
                "$TMPDIR" \
                "/tmp"; do
                if [ -n "$candidate" ]; then
                    mkdir -p "$candidate" 2>/dev/null || true
                    if [ -d "$candidate" ] && [ -w "$candidate" ]; then
                        SAFE_DIR="$candidate"
                        break
                    fi
                fi
            done
        fi
        [ -n "$SAFE_DIR" ] && FIFO="$SAFE_DIR/$FIFO_NAME"
        ;;
esac

# Format as JSON envelope
NOW_MS=$(date +%s%3N 2>/dev/null || date +%s)
JSON="{\"event\":\"android.intent.$ACTION\",\"timestamp\":$NOW_MS,\"source\":\"android:am\",\"data\":{\"raw\":\"$PAYLOAD\"}}"

if [ -p "$FIFO" ]; then
    # Non-blocking write to avoid hanging if no reader is open
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "
import os, sys
try:
    fd = os.open(sys.argv[1], os.O_WRONLY | os.O_NONBLOCK)
    os.write(fd, (sys.argv[2] + '\n').encode('utf-8'))
    os.close(fd)
except OSError:
    pass
" "$FIFO" "$JSON" >/dev/null 2>&1 || true
    elif command -v timeout >/dev/null 2>&1; then
        timeout 1s sh -c 'echo "$1" > "$2"' _ "$JSON" "$FIFO" 2>/dev/null || true
    elif [ -x /system/bin/toybox ]; then
        /system/bin/toybox timeout 1 sh -c 'echo "$1" > "$2"' _ "$JSON" "$FIFO" 2>/dev/null || true
    else
        ( echo "$JSON" > "$FIFO" ) &
        SUBPID=$!
        ( sleep 1; kill -9 $SUBPID 2>/dev/null ) &
    fi
else
    echo "$JSON"
fi
