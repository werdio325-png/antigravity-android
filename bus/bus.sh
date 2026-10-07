#!/usr/bin/env bash
# bus.sh - modular shell bus for AGY (Antigravity on Android, Termux-like).
#
#   bus list                              list profiles, modules, elements
#   bus run [--profile P] [-- TARGET...]  compose env + args, exec the target
#   bus svc up|down|status [MOD...]       long-lived service lifecycle
#   bus env [--profile P]                 print the composed environment
#   bus doctor [--profile P]              verify every layer the target needs
#   bus endpoints                         print the endpoint registry
#
# Thin entry: load lib/*.sh (helpers), then cmd/*.sh (handlers), dispatch.
# A module is a drop-in modules/NN-name.sh. The channel name is the file
# basename with the leading NN- stripped (00-prefix.sh -> prefix). Contract:
#   name_up            (required) export env / edit PATH / append args
#   name_down          (optional) teardown for the launcher process
#   name_args          (optional) echo extra CLI args, one per line
#   name_svc_up|down|status (optional) daemon lifecycle
#   name_doctor        (optional) diagnostics for `bus doctor`
# Load order: modules/*.sh then elements/*.sh, lexical. Nothing is hardcoded:
# add a file and list its channel in a profile.
#
# Endpoints: a module publishes an address with `_endpoint NAME VALUE`; the
# registry is flushed to $SVC_DIR/endpoints (see: bus endpoints).
set -uo pipefail

_BUS_SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Helper order is dependency order; do not rely on the lexical glob.
# shellcheck source=lib/paths.sh
. "$_BUS_SELF/lib/paths.sh"
# shellcheck source=lib/log.sh
. "$_BUS_SELF/lib/log.sh"
# shellcheck source=lib/services.sh
. "$_BUS_SELF/lib/services.sh"
# shellcheck source=lib/endpoints.sh
. "$_BUS_SELF/lib/endpoints.sh"
# shellcheck source=lib/profile.sh
. "$_BUS_SELF/lib/profile.sh"
# shellcheck source=lib/channels.sh
. "$_BUS_SELF/lib/channels.sh"

for _c in "$BUS_DIR"/cmd/*.sh; do
  [ -r "$_c" ] || continue
  # shellcheck disable=SC1090
  . "$_c"
done
unset _c

_cmd="${1:-run}"; [ $# -gt 0 ] && shift

case "$_cmd" in
  list)      cmd_list "$@";;
  endpoints) cmd_endpoints "$@";;
  svc)       cmd_svc "$@";;
  env)       cmd_env "$@";;
  doctor)    cmd_doctor "$@";;
  run)       cmd_run "$@";;
  test|selftest) cmd_selftest "$@";;
  *)
    _log "usage: bus list | run [--profile P] [-- TARGET...] | svc up|down|status [MOD...] | env [--profile P] | doctor [--profile P] | endpoints | selftest"
    exit 1
    ;;
esac
