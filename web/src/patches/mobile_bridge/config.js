/**
 * patches/mobile_bridge/config.js - guard, state, and native bridge helpers.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 * Zero dependency on minified identifiers inside upstream main.js.
 */
'use strict';

if (window.__agMobileBridge && window.__agMobileBridge.__installed) {
  return;
}

var global = window;
var doc = global.document;

var STORAGE_KEYS = {
  desktopMode: 'ag_desktop_mode',
  extraKeys: 'ag_extra_keys_visible',
  pillTop: 'ag_mb_pill_top',
  previewPort: 'ag_mb_preview_port'
};

var PREVIEW_PRESETS = [3000, 5173, 8000, 8080];

var state = {
  installed: false,
  drawerOpen: false,
  lastEditable: null,
  lastTerminal: null,
  pendingShare: null,
  shareDeliveries: 0,
  extraKeyDispatches: 0,
  voiceDeliveries: 0,
  imagePastes: 0,
  pasteInterceptorInstalled: false,
  dictating: false,
  voiceInterim: '',
  voiceServiceActive: false,
  voiceServiceMuted: false,
  activeVoiceSockets: [],
  activeMicTracks: [],
  toastTimer: 0,
  uiMounted: false
};

function getAndroid() {
  try {
    return global.Android || global.AndroidBridge || null;
  } catch (_) {
    return null;
  }
}

function getNativeToken() {
  try {
    return typeof global.__AG_NATIVE_BRIDGE_TOKEN__ === 'string'
      ? global.__AG_NATIVE_BRIDGE_TOKEN__
      : '';
  } catch (_) {
    return '';
  }
}

function readStorage(key) {
  try {
    return global.localStorage ? global.localStorage.getItem(key) : null;
  } catch (_) {
    return null;
  }
}

function writeStorage(key, val) {
  try {
    if (global.localStorage) global.localStorage.setItem(key, String(val));
  } catch (_) {}
}

function getDesktopModeState() {
  var android = getAndroid();
  try {
    if (android && typeof android.isDesktopMode === 'function') {
      return !!android.isDesktopMode();
    }
  } catch (_) {}
  return readStorage(STORAGE_KEYS.desktopMode) === 'true';
}

function setDesktopModeState(enabled) {
  var next = !!enabled;
  writeStorage(STORAGE_KEYS.desktopMode, next);
  var android = getAndroid();
  try {
    if (android && typeof android.setDesktopMode === 'function') {
      android.setDesktopMode(next);
      return true;
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] setDesktopMode failed:', e); } catch (_) {}
  }
  return false;
}

function getExtraKeysState() {
  var android = getAndroid();
  try {
    if (android && typeof android.isExtraKeysVisible === 'function') {
      return !!android.isExtraKeysVisible();
    }
  } catch (_) {}
  return readStorage(STORAGE_KEYS.extraKeys) === 'true';
}

function setExtraKeysState(visible) {
  var next = !!visible;
  writeStorage(STORAGE_KEYS.extraKeys, next);
  var android = getAndroid();
  try {
    if (android && typeof android.setExtraKeysVisible === 'function') {
      android.setExtraKeysVisible(next);
      return true;
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] setExtraKeysVisible failed:', e); } catch (_) {}
  }
  return false;
}

function normalizeLocalPreviewUrl(raw) {
  var s = String(raw == null ? '' : raw).trim();
  if (!s) return '';
  if (/^\d{1,5}$/.test(s)) {
    var portNum = parseInt(s, 10);
    if (portNum < 1 || portNum > 65535) return '';
    return 'http://localhost:' + portNum;
  }
  if (/^(localhost|127\.0\.0\.1)(:\d{1,5})?(\/.*)?$/i.test(s)) {
    s = 'http://' + s;
  }
  try {
    var parsed = new URL(s);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return '';
    var host = (parsed.hostname || '').toLowerCase();
    if (host !== 'localhost' && host !== '127.0.0.1') return '';
    return parsed.toString();
  } catch (_) {
    return '';
  }
}

function triggerLocalPreview(rawUrl) {
  var url = normalizeLocalPreviewUrl(rawUrl);
  if (!url) return { ok: false, reason: 'invalid_url' };
  var android = getAndroid();
  try {
    if (android && typeof android.openLocalPreview === 'function') {
      var opened = android.openLocalPreview(getNativeToken(), url);
      if (opened === false) return { ok: false, reason: 'native_rejected' };
      return { ok: true, url: url };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] openLocalPreview failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}

function triggerRemotePortal() {
  var android = getAndroid();
  try {
    if (android && typeof android.openRemotePortal === 'function') {
      var opened = android.openRemotePortal(getNativeToken());
      if (opened === false) return { ok: false, reason: 'native_rejected' };
      return { ok: true, url: 'https://antigravity.google.com' };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] openRemotePortal failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}
