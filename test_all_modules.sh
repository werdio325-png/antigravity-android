#!/usr/bin/env bash
# test_all_modules.sh - Master test runner for the complete Antigravity v2 modular project.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "\n${BLUE}================================================================${NC}"
echo -e "${BLUE}  ANTIGRAVITY V2 - FULL MODULAR TEST SUITE (Level 999999+1)     ${NC}"
echo -e "${BLUE}================================================================${NC}\n"

echo -e "${BLUE}>>> [1/7] Testing modules/core...${NC}"
bash "$PROJECT_ROOT/modules/core/tests/run_all.sh"

echo -e "\n${BLUE}>>> [2/7] Testing modules/patches...${NC}"
bash "$PROJECT_ROOT/modules/patches/tests/run_all.sh"

echo -e "\n${BLUE}>>> [3/7] Testing modules/io...${NC}"
bash "$PROJECT_ROOT/modules/io/tests/run_all.sh"

echo -e "\n${BLUE}>>> [4/7] Testing modules/env...${NC}"
bash "$PROJECT_ROOT/modules/env/tests/run_all.sh"

echo -e "\n${BLUE}>>> [5/7] Testing modules/builder...${NC}"
bash "$PROJECT_ROOT/modules/builder/tests/run_all.sh"

echo -e "\n${BLUE}>>> [6/7] Testing modules/engine...${NC}"
bash "$PROJECT_ROOT/modules/engine/tests/run_all.sh"

echo -e "\n${BLUE}>>> [7/7] Running Live E2E Server & Runtime Test...${NC}"
bash "$PROJECT_ROOT/modules/runner/test_e2e.sh"

echo -e "\n${GREEN}================================================================${NC}"
echo -e "${GREEN}  ALL MODULES IN ANTIGRAVITY V2 FULLY VERIFIED AND PASSING!     ${NC}"
echo -e "${GREEN}================================================================${NC}\n"
