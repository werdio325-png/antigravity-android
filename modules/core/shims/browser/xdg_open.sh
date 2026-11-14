#!/system/bin/sh
# AGY URL opener shim (xdg-open / open / sensible-browser).
# Dispatches URLs to Android Activity Manager & CoreProcessSupervisor.

unset LD_PRELOAD
unset LD_LIBRARY_PATH

URL=""

# 1. Search for first argument starting with http:// or https://
for arg in "$@"; do
  case "$arg" in
    http://*|https://*)
      URL="$arg"
      break
      ;;
  esac
done

# 2. Fallback: search for first argument not starting with hyphen '-'
if [ -z "$URL" ]; then
  for arg in "$@"; do
    case "$arg" in
      -*|"")
        ;;
      *)
        URL="$arg"
        break
        ;;
    esac
  done
fi

[ -z "$URL" ] && exit 0

# Primary channel: output to stdout captured by CoreProcessSupervisor
echo "AGY_OPEN_URL=$URL"
echo "ANTIGRAVITY_OPEN_URL: $URL"

# Direct intent / broadcast fallback for terminal and standalone commands
if [ -x /system/bin/am ]; then
  /system/bin/am broadcast -a dev.continuum.antigravity.ACTION_OPEN_URL -p dev.continuum.antigravity --es url "$URL" >/dev/null 2>&1 || true
  /system/bin/am broadcast -a dev.continuum.antigravity.ACTION_OPEN_URL -p dev.continuum.antigravity.dev --es url "$URL" >/dev/null 2>&1 || true
  /system/bin/am start -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1 || true
fi

exit 0
