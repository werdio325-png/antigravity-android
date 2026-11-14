#!/usr/bin/env bash
# run_all.sh - Tests for the engine compatibility layer.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}=== [1/2] Syntax Validation ===${NC}"
python3 -m compileall -q "$MODULE_ROOT"
echo "  [OK] compileall: $MODULE_ROOT"

echo -e "\n${BLUE}=== [2/2] Running Python Unit Tests ===${NC}"
cd "$MODULE_ROOT"
python3 -m unittest discover -s tests -p 'test_*.py' -v

echo -e "\n${GREEN}=== ENGINE MODULE TESTS PASSED ===${NC}\n"
