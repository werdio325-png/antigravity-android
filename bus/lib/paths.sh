# paths - locate the bus tree and its runtime directories.
# Sourced by bus.sh before anything else.
BUS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULES_DIR="${BUS_MODULES_DIR:-$BUS_DIR/modules}"
ELEMENTS_DIR="${BUS_ELEMENTS_DIR:-$BUS_DIR/elements}"
PROFILES_DIR="${BUS_PROFILES_DIR:-$BUS_DIR/profiles}"
SVC_DIR="${BUS_RUN_DIR:-$BUS_DIR/run}"
mkdir -p "$SVC_DIR" 2>/dev/null || true
ENDPOINTS_FILE="$SVC_DIR/endpoints"

# App HTTP port single source: config/app.env PORT. Read it verbatim, fall back
# to the frozen default so the bus runs without the source tree.
APP_ENV="$BUS_DIR/../config/app.env"
APP_PORT="$(sed -n 's/^PORT=//p' "$APP_ENV" 2>/dev/null | head -n1)"
APP_PORT="${APP_PORT:-45157}"
