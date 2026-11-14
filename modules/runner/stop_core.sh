#!/usr/bin/env bash
# stop_core.sh - Gracefully terminates the standalone test core server with PID recycling protection and process group cleanup.

set -euo pipefail

# Dynamic APP_ID detection (dev.continuum.antigravity vs dev.continuum.antigravity.dev)
if [ -n "${AGY_APPLICATION_ID:-}" ]; then
    APP_ID="$AGY_APPLICATION_ID"
elif [ -n "${HOME:-}" ] && [[ "$HOME" =~ /data/user/0/([^/]+) ]]; then
    APP_ID="${BASH_REMATCH[1]}"
elif [ -d "/data/user/0/dev.continuum.antigravity.dev" ]; then
    APP_ID="dev.continuum.antigravity.dev"
else
    APP_ID="dev.continuum.antigravity"
fi

APP_BASE="/data/user/0/$APP_ID"
RT="${1:-$APP_BASE/files/standalone_runtime}"

PID_FILE="$RT/standalone.pid"
SPLITTER_PID_FILE="$RT/standalone.splitter_pid"
PGID_FILE="$RT/standalone.pgid"
META_FILE="$RT/standalone.json"
FIFO_FILE="$RT/core_stream.fifo"

cleanup_stale_files() {
    rm -f "$PID_FILE" "$SPLITTER_PID_FILE" "$PGID_FILE" "$META_FILE" "$FIFO_FILE"
}

PID=""
if [ -f "$PID_FILE" ]; then
    PID=$(head -n 1 "$PID_FILE" | tr -d '[:space:]' || true)
fi

SPLITTER_PID=""
if [ -f "$SPLITTER_PID_FILE" ]; then
    SPLITTER_PID=$(head -n 1 "$SPLITTER_PID_FILE" | tr -d '[:space:]' || true)
fi

PGID=""
if [ -f "$PGID_FILE" ]; then
    PGID=$(head -n 1 "$PGID_FILE" | tr -d '[:space:]' || true)
fi

if [ -z "$PID" ]; then
    echo "[*] No PID found in $PID_FILE."
    cleanup_stale_files
    exit 0
fi

if ! kill -0 "$PID" 2>/dev/null; then
    echo "[*] Process $PID is not running."
    if [ -n "$SPLITTER_PID" ] && kill -0 "$SPLITTER_PID" 2>/dev/null; then
        kill -15 "$SPLITTER_PID" 2>/dev/null || true
    fi
    cleanup_stale_files
    exit 0
fi

# PID Recycling Protection: verify that /proc/$PID/cmdline actually contains "language_server"
IS_LANGUAGE_SERVER=0
CMDLINE=""
if [ -f "/proc/$PID/cmdline" ]; then
    CMDLINE=$(tr '\0' ' ' < "/proc/$PID/cmdline" 2>/dev/null || true)
    if [[ "$CMDLINE" == *"language_server"* ]]; then
        IS_LANGUAGE_SERVER=1
    fi
fi

if [ "$IS_LANGUAGE_SERVER" -ne 1 ]; then
    echo "[WARNING] Process $PID is active but does not appear to be language_server (cmdline: '${CMDLINE:-unknown}')." >&2
    echo "[WARNING] Aborting kill to protect against PID recycling hazard!" >&2
    cleanup_stale_files
    exit 0
fi

# Fallback PGID detection if not recorded in file
if [ -z "$PGID" ] && [ -f "/proc/$PID/stat" ]; then
    PGID=$(awk '{print $5}' "/proc/$PID/stat" 2>/dev/null || true)
fi

echo "[*] Terminating standalone core process (PID: $PID, PGID: ${PGID:-$PID})..."

# Phase 1: Graceful termination (SIGTERM / kill -15)
# Terminate process group if PGID is valid and > 1
if [ -n "$PGID" ] && [ "$PGID" -gt 1 ]; then
    kill -15 "-$PGID" 2>/dev/null || true
fi
kill -15 "$PID" 2>/dev/null || true

# Terminate stream_splitter interceptor
if [ -n "$SPLITTER_PID" ] && kill -0 "$SPLITTER_PID" 2>/dev/null; then
    kill -15 "$SPLITTER_PID" 2>/dev/null || true
fi

# Phase 2: Polling loop (checks kill -0 every 0.1s up to 5.0s = 50 iterations)
TERMINATED=0
for ((i = 0; i < 50; i++)); do
    if ! kill -0 "$PID" 2>/dev/null; then
        TERMINATED=1
        break
    fi
    sleep 0.1
done

# Phase 3: Escalation to SIGKILL (kill -9) if process is still alive after 5 seconds
if [ "$TERMINATED" -eq 0 ]; then
    echo "[!] Process $PID did not exit within 5s; sending SIGKILL (-9)..."
    if [ -n "$PGID" ] && [ "$PGID" -gt 1 ]; then
        kill -9 "-$PGID" 2>/dev/null || true
    fi
    kill -9 "$PID" 2>/dev/null || true
    if [ -n "$SPLITTER_PID" ] && kill -0 "$SPLITTER_PID" 2>/dev/null; then
        kill -9 "$SPLITTER_PID" 2>/dev/null || true
    fi

    # Poll up to 1 second for SIGKILL to complete
    for ((i = 0; i < 10; i++)); do
        if ! kill -0 "$PID" 2>/dev/null; then
            TERMINATED=1
            break
        fi
        sleep 0.1
    done
fi

# Ensure splitter process is cleaned up
if [ -n "$SPLITTER_PID" ] && kill -0 "$SPLITTER_PID" 2>/dev/null; then
    kill -9 "$SPLITTER_PID" 2>/dev/null || true
fi

cleanup_stale_files

if [ "$TERMINATED" -eq 1 ]; then
    echo "[OK] Core process terminated."
else
    echo "[ERROR] Failed to terminate core process $PID!" >&2
    exit 1
fi
