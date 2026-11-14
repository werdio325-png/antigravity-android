#!/usr/bin/env bash
# run.sh - run the ACP adapter protocol tests on a desktop JVM.
#
# These are protocol tests against tools/acp_test/mock_agent.py. They do not run
# Google's CLI and cannot attest to model entitlement (that needs a device and a
# real account); see docs/HYDROGEN_COMPATIBILITY_REPORT.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${TMPDIR:-/tmp}/continuum-acp-test"
JSON_JAR="$OUT/json.jar"
JSON_VERSION="20240303"
JSON_URL="https://repo1.maven.org/maven2/org/json/json/${JSON_VERSION}/json-${JSON_VERSION}.jar"

mkdir -p "$OUT/classes"

if [ ! -f "$JSON_JAR" ]; then
    echo "[*] Fetching org.json test dependency..."
    curl -fsSL -o "$JSON_JAR" "$JSON_URL"
fi

echo "[*] Compiling ACP adapter + harness..."
javac -d "$OUT/classes" \
    -cp "$JSON_JAR" \
    "$ROOT/app/src/main/java/dev/continuum/antigravity/acp/JsonRpcPeer.java" \
    "$ROOT/app/src/main/java/dev/continuum/antigravity/acp/AcpClient.java" \
    "$ROOT/app/src/main/java/dev/continuum/antigravity/acp/AcpModelCatalog.java" \
    "$ROOT/app/src/main/java/dev/continuum/antigravity/acp/CliRelease.java" \
    "$ROOT/tools/acp_test/AcpTestMain.java"

echo "[*] Running ACP tests..."
java -cp "$OUT/classes:$JSON_JAR" AcpTestMain "$ROOT/tools/acp_test/mock_agent.py"
