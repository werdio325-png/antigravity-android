# AGY v2 — dead code to drop (do not port)

Compiled from the original audit. If a symbol is not listed, keep it.
When in doubt, keep it and note it here.

## Java (`app/`)
- `AndroidBridge`: `openExternal`, `postMessage`, `invoke`, `forceStopCascade`
- `ShizukuBridge`: `execOk`
- `NetworkMonitor`: `isRegistered` (keep `unregister` only if still used)
- `RuntimeManager`: `install`, `isInstalled`, `setCoreRunning`, `unregister`,
  `verifyManifest`, `EnvReadyListener` + callback path, `BRIDGED_TOOLS`, `coreRunning`
- `ThemeManager`: `getMode`, `cycle`, `getForegroundColor`
- `WebViewHost`: `load(String)`
- `SplashOverlay`: `getErrorHtml`, 4-arg `showError`
- `OAuthManager`: `CALLBACK_SCHEME`
- Duplicate resources: `res/values-night/bools.xml`,
  `res/values-night/styles.xml`, `res/values-night/themes.xml`,
  `res/values-night-v31/themes.xml` (byte-identical to base) — drop, inherit.

## Web (`web/`)
- `bridge.core.js`: electron polyfill (`window.electron.ipcRenderer/shell`),
  `onEngineReady`, `rpc`/`rpcUrl`, keepalive interval (buggy `noop`),
  `__agRpc`/`__agEndpoint` public API.
- `bridge.cache.js`: `CONFIG.l1`, duplicate `CONFIG.debug` key,
  `__AG_CACHE_CONFIG__` override path, `_internal`/`features`.
  Keep `__agCache` (may be used from devtools) but drop the dead override.

## patches (`patches/`)
- `patch_gates.py`: `_missing`, `verify_file`
- (`apply_inplace` in resolv/auth is test-only — keep.)

## bus (`bus/`)
- `profiles`: `NOFILE`, `GOMAXPROCS`, `GOMEMLIMIT`; `tlx.sh`: `BUS_BIN`
- `modules/70-files.sh`: write-only `AG_RISH`
- `bus.sh`: `_endpoints_reset` (unused)
- Empty dirs `bus/elements/`, `bus/run/`.
