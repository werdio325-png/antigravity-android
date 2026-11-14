#!/usr/bin/env bash
# start_core.sh - Launches the standalone Antigravity core with I/O bus integration.

set -euo pipefail

RUNNER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULES_DIR="$(cd "$RUNNER_DIR/.." && pwd)"

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
export HOME="${HOME:-$APP_BASE/files}"
export TMPDIR="${TMPDIR:-$APP_BASE/cache}"

RT="${1:-$HOME/standalone_runtime}"
PORT="${2:-45159}"
CSRF_TOKEN="test-csrf-token-12345"

LOADER="$RT/bin/libloader.so"
[ -f "$LOADER" ] || LOADER="$RT/bin/ld-linux-aarch64.so.1"
CORE="$RT/bin/liblanguage_server.so"
[ -f "$CORE" ] || CORE="$RT/bin/language_server"
LSE_COMPAT="$RT/lib/liblse_compat.so"

[ -f "$LOADER" ] || { echo "[ERROR] Loader missing: $LOADER"; exit 1; }
[ -f "$CORE" ]   || { echo "[ERROR] Core binary missing: $CORE"; exit 1; }

# Pre-flight check: protect reserved primary Antigravity ports
if [ "$PORT" -eq 45157 ] || [ "$PORT" -eq 45158 ]; then
    echo "[ERROR] Port $PORT is reserved for the primary Antigravity runtime. Standalone runner must use port 45159 or another port." >&2
    exit 1
fi

is_port_in_use() {
    local port="$1"
    (cat </dev/null >"/dev/tcp/127.0.0.1/$port") 2>/dev/null
}

PID_FILE="$RT/standalone.pid"
SPLITTER_PID_FILE="$RT/standalone.splitter_pid"
PGID_FILE="$RT/standalone.pgid"
META_FILE="$RT/standalone.json"
FIFO_FILE="$RT/core_stream.fifo"
LOG_FILE="$RT/core_output.log"

# Pre-flight check: ensure target port is free, or clean up zombie/stale processes
if is_port_in_use "$PORT"; then
    echo "[!] Pre-flight check: Port $PORT is currently occupied."

    # 1. Attempt graceful stop via existing PID file
    if [ -f "$PID_FILE" ]; then
        echo "[*] Found existing PID file. Attempting graceful stop..."
        bash "$RUNNER_DIR/stop_core.sh" "$RT" || true
    fi

    # 2. Check for orphaned standalone core processes listening on PORT
    if is_port_in_use "$PORT"; then
        echo "[*] Searching for orphaned standalone core processes on port $PORT..."
        for p in /proc/[0-9]*; do
            if [ -f "$p/cmdline" ]; then
                cmd=$(tr '\0' ' ' < "$p/cmdline" 2>/dev/null || true)
                # Safeguard: strictly match standalone core and port, never the primary IDE server
                if [[ "$cmd" == *"language_server"* ]] && [[ "$cmd" == *"antigravity-app-standalone"* ]] && [[ "$cmd" == *"$PORT"* ]]; then
                    orphan_pid=$(basename "$p")
                    echo "[*] Found orphaned core process PID $orphan_pid on port $PORT. Terminating..."
                    kill -15 "$orphan_pid" 2>/dev/null || true
                    sleep 0.5
                    kill -9 "$orphan_pid" 2>/dev/null || true
                fi
            fi
        done
    fi

    # 3. Wait up to 2 seconds for the port to become available
    PORT_FREED=0
    for ((k = 0; k < 20; k++)); do
        if ! is_port_in_use "$PORT"; then
            PORT_FREED=1
            echo "[OK] Port $PORT has been freed."
            break
        fi
        sleep 0.1
    done

    if [ "$PORT_FREED" -eq 0 ]; then
        echo "[ERROR] Port $PORT is still in use by another process. Cannot start core server." >&2
        exit 1
    fi
fi

export SSL_CERT_FILE="$RT/certs/ca-certificates.crt"
export PATH="$RT/bin:${PREFIX:-$HOME/usr}/bin:$PATH"
export SHELL="$RT/bin/bash"
export LANG="C.UTF-8"
export LC_ALL="C.UTF-8"
export GODEBUG="netdns=go"
export ANTIGRAVITY_VSCODE_HOST="1"
export BROWSER="$RT/bin/xdg-open"

# Bionic LD_LIBRARY_PATH for host python3/tools
export LD_LIBRARY_PATH="${PREFIX:-$HOME/usr}/lib"
unset LD_PRELOAD

CMD=(
    "$LOADER"
    --library-path "$RT/lib"
)

if [ -f "$LSE_COMPAT" ]; then
    CMD+=(--preload "$LSE_COMPAT")
fi

CMD+=(
    "$CORE"
    --standalone
    --override_ide_name "antigravity"
    --subclient_type "hub"
    --override_ide_version "2.11.0"
    --override_user_agent_name "antigravity"
    --https_server_port "$PORT"
    --csrf_token "$CSRF_TOKEN"
    --app_data_dir "antigravity-app-standalone"
    --enable_sidecars
)

echo "[*] Launching core server on port $PORT..."
echo "[*] Connecting stdout/stderr to modules/io stream splitter..."

# Enable job control (set -m) so background processes get their own process groups
set -m

# Setup named FIFO for clean stdout/stderr streaming without subshell PID leak
rm -f "$FIFO_FILE"
mkfifo "$FIFO_FILE"

# 1. Start stream_splitter interceptor in background reading from FIFO
python3 -u "$MODULES_DIR/io/interceptors/stream_splitter.py" < "$FIFO_FILE" > "$LOG_FILE" 2>&1 &
SPLITTER_PID=$!

# 2. Launch the core process directly with stdout and stderr redirected to FIFO
"${CMD[@]}" > "$FIFO_FILE" 2>&1 &
CORE_PID=$!

# Determine PGID of the core process
CORE_PGID=$(awk '{print $5}' "/proc/$CORE_PID/stat" 2>/dev/null || echo "$CORE_PID")

# Persist PIDs and metadata
echo "$CORE_PID" > "$PID_FILE"
echo "$SPLITTER_PID" > "$SPLITTER_PID_FILE"
echo "$CORE_PGID" > "$PGID_FILE"

cat <<EOF > "$META_FILE"
{
  "pid": $CORE_PID,
  "splitter_pid": $SPLITTER_PID,
  "pgid": $CORE_PGID,
  "port": $PORT,
  "log_file": "$LOG_FILE",
  "fifo_file": "$FIFO_FILE"
}
EOF

echo "[OK] Core process started with PID: $CORE_PID (PGID: $CORE_PGID)"
echo "[OK] Stream splitter started with PID: $SPLITTER_PID"
echo "[*] Log stream: $LOG_FILE"
