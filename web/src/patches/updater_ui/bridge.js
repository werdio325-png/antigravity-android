/**
 * patches/updater_ui/bridge.js - native updater calls.
 *
 * All mutating calls carry the native bridge token that the host injects into
 * the trusted top-level document only.
 */
function updaterBridge() {
  return window.AndroidUpdate || null;
}

function updaterToken() {
  return window.__AG_NATIVE_BRIDGE_TOKEN__ || '';
}

function updaterCheck() {
  var bridge = updaterBridge();
  if (!bridge || typeof bridge.check !== 'function') {
    return updaterRender(localUpdaterError('The updater is not available in this build.'));
  }
  bridge.check(updaterToken());
  updaterRender(Object.assign({}, updaterState.last || {}, { state: 'checking', error: null }));
}

function updaterDownload() {
  var bridge = updaterBridge();
  if (bridge && typeof bridge.download === 'function') {
    bridge.download(updaterToken());
    updaterRender(Object.assign({}, updaterState.last || {}, { state: 'downloading' }));
  }
}

function updaterInstall() {
  var bridge = updaterBridge();
  if (bridge && typeof bridge.install === 'function') {
    bridge.install(updaterToken());
  }
}

function updaterPostpone() {
  var bridge = updaterBridge();
  if (bridge && typeof bridge.postpone === 'function') {
    bridge.postpone(updaterToken());
  }
  updaterSetOpen(false);
}

function updaterRefresh() {
  var bridge = updaterBridge();
  if (!bridge || typeof bridge.getState !== 'function') return;
  try {
    updaterRender(JSON.parse(bridge.getState()));
  } catch (_) { /* ignore malformed state */ }
}

function localUpdaterError(message) {
  return {
    state: 'error',
    error: message,
    installedVersion: (window.__APP_CONFIG__ && window.__APP_CONFIG__.appVersion) || 'unknown'
  };
}
