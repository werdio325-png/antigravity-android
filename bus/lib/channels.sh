# channels - channel naming, drop-in module loading, env composition and args.
#
# A module is modules/NN-name.sh. The channel name is the basename with the
# leading NN- stripped (00-prefix.sh -> prefix). Load order: modules/*.sh then
# elements/*.sh, lexical. Nothing is hardcoded: add a file and list its channel
# in a profile.

# ---- channel naming -------------------------------------------------------
# 00-prefix.sh -> prefix ; scan channel names without executing functions.
_mod_channel() { local b; b="$(basename "${1%.sh}")"; printf '%s' "${b##*-}"; }
# Normalize a user-supplied MOD argument to its channel name.
_norm_channel() { printf '%s' "${1##*-}"; }

# ---- load all modules, then elements (drop-in, lexical order) -------------
BUS_MODULES_ALL=()
BUS_CMD=()
for _f in "$MODULES_DIR"/*.sh; do
  [ -r "$_f" ] || continue
  # shellcheck disable=SC1090
  . "$_f"
  BUS_MODULES_ALL+=("$(_mod_channel "$_f")")
done
for _f in "$ELEMENTS_DIR"/*.sh; do
  [ -r "$_f" ] || continue
  # shellcheck disable=SC1090
  . "$_f"
  BUS_MODULES_ALL+=("$(_mod_channel "$_f")")
done

# Run name_up for every channel in a MODULES list, in order.
_compose() { # $1 = space separated channels
  local m fn rc=0
  for m in ${1:-}; do
    fn="${m}_up"
    if command -v "$fn" >/dev/null 2>&1; then
      "$fn" || rc=$?
    else
      _warn "module '$m' has no ${fn}()"
    fi
  done
  return $rc
}

# Collect args from every active channel's name_args.
_collect_args() { # $1 = channels ; prints one arg per line
  local m fn _l
  for m in ${1:-}; do
    fn="${m}_args"
    if command -v "$fn" >/dev/null 2>&1; then
      while IFS= read -r _l; do [ -n "$_l" ] && printf '%s\n' "$_l"; done < <("$fn")
    fi
  done
}
