# log - diagnostics. Everything goes to stderr; stdout stays clean for the
# target (LSP/JSON-RPC runs on it).
_log()  { printf 'bus: %s\n' "$*" >&2; }
_warn() { printf 'bus: warn: %s\n' "$*" >&2; }

# Append key=value to GODEBUG without clobbering existing entries.
_godebug() { export GODEBUG="${GODEBUG:+$GODEBUG,}$1"; }
