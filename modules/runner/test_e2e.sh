#!/usr/bin/env bash
# test_e2e.sh - Comprehensive end-to-end integration test of Core + Patches + I/O.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
HOME_DIR="${HOME:-$APP_BASE/files}"
RT="${1:-$HOME_DIR/standalone_runtime}"
PORT="${2:-45159}"

cleanup() {
    bash "$SCRIPT_DIR/stop_core.sh" "$RT" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== STEP 1: Preparing and Assembling Patched Runtime ===${NC}"
python3 "$SCRIPT_DIR/prepare_runtime.py" "$RT"

echo -e "\n${BLUE}=== STEP 2: Starting Standalone Core Server with I/O Bus ===${NC}"
bash "$SCRIPT_DIR/start_core.sh" "$RT" "$PORT"

echo -e "\n${BLUE}=== STEP 3: Probing HTTP / LSP Server on Port $PORT ===${NC}"
if python3 "$SCRIPT_DIR/probe_server.py" "$PORT" 40 "$RT/standalone.pid"; then
    echo -e "${GREEN}[SUCCESS] Standalone Core is UP and accepting requests!${NC}"
else
    echo -e "${RED}[FAILURE] Server failed to respond! Dumping log:${NC}"
    cat "$RT/core_output.log" 2>/dev/null || true
    bash "$SCRIPT_DIR/stop_core.sh" "$RT"
    exit 1
fi

echo -e "\n${BLUE}=== STEP 4: Inspecting Live Core Log Stream ===${NC}"
head -n 25 "$RT/core_output.log"

echo -e "\n${BLUE}=== STEP 5: Gracefully Stopping Test Server ===${NC}"
bash "$SCRIPT_DIR/stop_core.sh" "$RT"

# Disarm trap after clean test completion
trap - EXIT INT TERM

echo -e "\n${GREEN}============================================================${NC}"
echo -e "${GREEN}  END-TO-END VALIDATION PASSED (Core + Patches + I/O OK!)   ${NC}"
echo -e "${GREEN}============================================================${NC}\n"
