#!/usr/bin/env bash
# ==============================================================================
# TEMPLATE: Create a new custom Input endpoint in 3 easy steps!
# 1. Read or receive data from your custom source (sensor, webhook, timer, MQTT).
# 2. Format it as an Event Envelope JSON (event, timestamp, source, data).
# 3. Output to the AGY bus FIFO pipe or stdout.
# ==============================================================================

FIFO_PATH="${AGY_BUS_FIFO:-/data/user/0/dev.continuum.antigravity/files/runtime/agy_bus.pipe}"

# Resolve safe path if targeting FUSE storage (/storage or /sdcard)
case "$FIFO_PATH" in
    /storage/*|/sdcard/*|/mnt/sdcard/*|/mnt/runtime/*)
        FIFO_NAME=$(basename "$FIFO_PATH")
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
        [ -n "$SAFE_DIR" ] && FIFO_PATH="$SAFE_DIR/$FIFO_NAME"
        ;;
esac

# Step 1: Obtain your custom input
INPUT_DATA="$1"
[ -z "$INPUT_DATA" ] && INPUT_DATA="default_payload"

# Step 2: Build JSON envelope
TIMESTAMP=$(date +%s%3N 2>/dev/null || date +%s)
JSON_EVENT="{\"event\":\"custom.my_event\",\"timestamp\":$TIMESTAMP,\"source\":\"input:custom\",\"data\":{\"value\":\"$INPUT_DATA\"}}"

# Step 3: Send to bus safely (non-blocking write)
if [ -p "$FIFO_PATH" ]; then
    if command -v python3 >/dev/null 2>&1; then
        python3 -c "
import os, sys
try:
    fd = os.open(sys.argv[1], os.O_WRONLY | os.O_NONBLOCK)
    os.write(fd, (sys.argv[2] + '\n').encode('utf-8'))
    os.close(fd)
except OSError:
    pass
" "$FIFO_PATH" "$JSON_EVENT" >/dev/null 2>&1 || true
    elif command -v timeout >/dev/null 2>&1; then
        timeout 1s sh -c 'echo "$1" > "$2"' _ "$JSON_EVENT" "$FIFO_PATH" 2>/dev/null || true
    else
        echo "$JSON_EVENT" > "$FIFO_PATH" 2>/dev/null || true
    fi
else
    echo "$JSON_EVENT"
fi
