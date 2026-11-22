(function(){
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

/**
 * patches/mobile_bridge/composer.js - composer discovery & React-safe insertion.
 */
function isOwnBridgeElement(el) {
  if (!el || el.nodeType !== 1) return false;
  try {
    if (el.closest && el.closest('[data-ag-mobile-bridge="true"]')) return true;
  } catch (_) {}
  return false;
}

function isTerminalElement(el) {
  if (!el || el.nodeType !== 1) return false;
  try {
    if (el.classList && el.classList.contains('xterm-helper-textarea')) return true;
    if (el.closest && el.closest('.xterm, .xterm-screen, [data-terminal="true"]')) return true;
  } catch (_) {}
  return false;
}

function isEditableElement(el) {
  if (!el || el.nodeType !== 1) return false;
  if (isOwnBridgeElement(el) || isTerminalElement(el)) return false;
  var tag = (el.tagName || '').toUpperCase();
  if (tag === 'TEXTAREA') {
    return !el.disabled && !el.readOnly;
  }
  if (tag === 'INPUT') {
    if (el.disabled || el.readOnly) return false;
    var t = (el.type || 'text').toLowerCase();
    return t === 'text' || t === 'search' || t === 'url' || t === 'email';
  }
  if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') {
    return true;
  }
  if (el.getAttribute('role') === 'textbox' && el.getAttribute('aria-readonly') !== 'true') {
    return true;
  }
  return false;
}

function isElementVisible(el) {
  if (!el || !el.isConnected) return false;
  try {
    var style = global.getComputedStyle ? global.getComputedStyle(el) : null;
    if (style && (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0')) {
      return false;
    }
    var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
    if (!rect) return true;
    return rect.width > 8 && rect.height > 8;
  } catch (_) {
    return true;
  }
}

function scoreComposerCandidate(el) {
  var score = 0;
  var tag = (el.tagName || '').toUpperCase();
  if (tag === 'TEXTAREA') score += 30;
  else if (el.isContentEditable || el.getAttribute('contenteditable') === 'true') score += 35;
  else if (el.getAttribute('role') === 'textbox') score += 25;
  else score += 5;

  var attrs = (
    (el.getAttribute('placeholder') || '') + ' ' +
    (el.getAttribute('aria-label') || '') + ' ' +
    (el.getAttribute('data-testid') || '') + ' ' +
    (el.getAttribute('data-placeholder') || '') + ' ' +
    (el.className && typeof el.className === 'string' ? el.className : '')
  ).toLowerCase();

  if (/ask|message|prompt|chat|composer|cascade|agent|send/.test(attrs)) score += 40;
  if (/search|filter|find/.test(attrs)) score -= 25;

  try {
    var rect = el.getBoundingClientRect();
    if (rect.width >= 160) score += 15;
    if (rect.height >= 24) score += 10;
    if (global.innerHeight && rect.bottom > global.innerHeight * 0.45) score += 15;
  } catch (_) {}

  if (el === doc.activeElement) score += 50;
  if (el === state.lastEditable) score += 20;
  return score;
}

function findComposer() {
  if (!doc) return null;
  var active = doc.activeElement;
  if (isEditableElement(active) && isElementVisible(active)) {
    return active;
  }
  if (state.lastEditable && isEditableElement(state.lastEditable) && isElementVisible(state.lastEditable)) {
    return state.lastEditable;
  }
  var selector = 'textarea, [contenteditable="true"], [role="textbox"], input[type="text"], input:not([type])';
  var nodes = [];
  try {
    nodes = doc.querySelectorAll(selector);
  } catch (_) {
    return null;
  }
  var best = null;
  var bestScore = -9999;
  for (var i = 0; i < nodes.length; i++) {
    var el = nodes[i];
    if (!isEditableElement(el) || !isElementVisible(el)) continue;
    var s = scoreComposerCandidate(el);
    if (s > bestScore) {
      bestScore = s;
      best = el;
    }
  }
  return best;
}

function dispatchInputEvents(el, insertedText) {
  try {
    if (typeof global.InputEvent === 'function') {
      el.dispatchEvent(new InputEvent('input', {
        bubbles: true,
        cancelable: false,
        composed: true,
        inputType: 'insertText',
        data: insertedText
      }));
    } else {
      el.dispatchEvent(new Event('input', { bubbles: true, cancelable: false }));
    }
  } catch (_) {
    try {
      el.dispatchEvent(new Event('input', { bubbles: true, cancelable: false }));
    } catch (_) {}
  }
  try {
    el.dispatchEvent(new Event('change', { bubbles: true, cancelable: false }));
  } catch (_) {}
}

function setNativeControlValue(el, nextValue) {
  var tag = (el.tagName || '').toUpperCase();
  var proto = tag === 'TEXTAREA'
    ? (global.HTMLTextAreaElement && global.HTMLTextAreaElement.prototype)
    : (global.HTMLInputElement && global.HTMLInputElement.prototype);
  var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
  if (desc && typeof desc.set === 'function') {
    desc.set.call(el, nextValue);
  } else {
    el.value = nextValue;
  }
}

function insertIntoTextControl(el, text, separatorIfNonEmpty) {
  try { el.focus(); } catch (_) {}
  var current = typeof el.value === 'string' ? el.value : '';
  var start = typeof el.selectionStart === 'number' ? el.selectionStart : current.length;
  var end = typeof el.selectionEnd === 'number' ? el.selectionEnd : current.length;
  if (start < 0 || start > current.length) start = current.length;
  if (end < start || end > current.length) end = start;

  var insert = text;
  if (separatorIfNonEmpty && start > 0 && !/\s$/.test(current.slice(0, start))) {
    insert = separatorIfNonEmpty + insert;
  }
  var nextValue = current.slice(0, start) + insert + current.slice(end);
  setNativeControlValue(el, nextValue);
  var caret = start + insert.length;
  try {
    if (typeof el.setSelectionRange === 'function') {
      el.setSelectionRange(caret, caret);
    }
  } catch (_) {}
  dispatchInputEvents(el, insert);
  return true;
}

function insertIntoContentEditable(el, text, separatorIfNonEmpty) {
  try { el.focus(); } catch (_) {}
  var existing = (el.textContent || '');
  var insert = text;
  if (separatorIfNonEmpty && existing.length > 0 && !/\s$/.test(existing)) {
    insert = separatorIfNonEmpty + insert;
  }

  // Ensure selection is inside el before inserting.
  var sel = global.getSelection ? global.getSelection() : null;
  if (sel) {
    var inside = sel.rangeCount > 0 && el.contains(sel.anchorNode);
    if (!inside) {
      try {
        var endRange = doc.createRange();
        endRange.selectNodeContents(el);
        endRange.collapse(false);
        sel.removeAllRanges();
        sel.addRange(endRange);
      } catch (_) {}
    }
  }

  var beforeText = el.textContent || '';
  var execOk = false;
  try {
    if (typeof doc.execCommand === 'function') {
      execOk = doc.execCommand('insertText', false, insert);
    }
  } catch (_) {
    execOk = false;
  }

  if (execOk && (el.textContent || '') !== beforeText) {
    return true;
  }

  // Fallback for rich editors that ignore execCommand: dispatch beforeinput + Range insertion + input.
  try {
    if (typeof global.InputEvent === 'function') {
      var bi = new InputEvent('beforeinput', {
        bubbles: true,
        cancelable: true,
        composed: true,
        inputType: 'insertText',
        data: insert
      });
      if (!el.dispatchEvent(bi)) {
        // Editor handled beforeinput directly (e.g. Lexical / Slate / ProseMirror).
        return true;
      }
    }
  } catch (_) {}

  try {
    if (sel && sel.rangeCount > 0 && el.contains(sel.anchorNode)) {
      var range = sel.getRangeAt(0);
      range.deleteContents();
      var node = doc.createTextNode(insert);
      range.insertNode(node);
      range.setStartAfter(node);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
    } else {
      el.appendChild(doc.createTextNode(insert));
    }
    dispatchInputEvents(el, insert);
    return true;
  } catch (_) {
    return false;
  }
}

function insertTextIntoEditable(el, text, separatorIfNonEmpty) {
  if (!el || !text) return false;
  var tag = (el.tagName || '').toUpperCase();
  if (tag === 'TEXTAREA' || tag === 'INPUT') {
    return insertIntoTextControl(el, text, separatorIfNonEmpty);
  }
  return insertIntoContentEditable(el, text, separatorIfNonEmpty);
}

/**
 * patches/mobile_bridge/share.js - Android Share Sheet receiver & paste banner.
 */
function parseSharedPayload(raw) {
  if (!raw) return null;
  if (typeof raw === 'string') {
    var trimmed = raw.trim();
    if (!trimmed) return null;
    if (trimmed.charAt(0) === '{') {
      try {
        return JSON.parse(trimmed);
      } catch (_) {}
    }
    return { text: trimmed, subject: '', files: [] };
  }
  if (typeof raw === 'object') {
    return raw;
  }
  return null;
}

function formatSharedPayload(payload) {
  if (!payload || typeof payload !== 'object') return '';
  var parts = [];
  var subject = typeof payload.subject === 'string' ? payload.subject.trim() : '';
  var text = typeof payload.text === 'string' ? payload.text.trim() : '';

  if (subject && (!text || text.indexOf(subject) === -1)) {
    parts.push(subject);
  }
  if (text) {
    parts.push(text);
  }

  var files = Array.isArray(payload.files) ? payload.files : [];
  if (files.length > 0) {
    var fileLines = [];
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      if (!f || typeof f !== 'object') continue;
      var path = typeof f.path === 'string' ? f.path.trim() : '';
      var name = typeof f.name === 'string' ? f.name.trim() : '';
      var mime = typeof f.mime === 'string' ? f.mime.trim() : '';
      if (path) {
        var meta = name ? name + (mime ? ' (' + mime + ')' : '') : mime;
        fileLines.push(meta ? ('[Shared file: ' + meta + '] ' + path) : ('[Shared file] ' + path));
      } else if (name) {
        fileLines.push('[Shared file: ' + name + (mime ? ' (' + mime + ')' : '') + ']');
      }
    }
    if (fileLines.length > 0) {
      parts.push(fileLines.join('\n'));
    }
  }

  return parts.join('\n\n').trim();
}

function removeShareBanner() {
  if (!doc) return;
  var existing = doc.getElementById('ag-mb-share-banner');
  if (existing && existing.parentNode) {
    existing.parentNode.removeChild(existing);
  }
  if (typeof syncDrawerState === 'function') syncDrawerState();
}

function applyBufferedShare() {
  if (!state.pendingShare || !state.pendingShare.formatted) {
    removeShareBanner();
    return false;
  }
  var composer = findComposer();
  if (composer && insertTextIntoEditable(composer, state.pendingShare.formatted, '\n')) {
    state.pendingShare = null;
    removeShareBanner();
    if (typeof showBridgeToast === 'function') {
      showBridgeToast('Shared content pasted into chat');
    }
    return true;
  }
  // No composer yet: copy to clipboard as an immediate fallback while keeping banner ready.
  try {
    if (global.navigator && global.navigator.clipboard && typeof global.navigator.clipboard.writeText === 'function') {
      global.navigator.clipboard.writeText(state.pendingShare.formatted);
      if (typeof showBridgeToast === 'function') {
        showBridgeToast('Copied to clipboard — open a chat and tap to paste');
      }
      return false;
    }
  } catch (_) {}
  if (typeof showBridgeToast === 'function') {
    showBridgeToast('Open a chat input, then tap banner to paste');
  }
  return false;
}

function showShareBanner() {
  if (!doc || !doc.body) return;
  if (typeof ensureBridgeStyles === 'function') ensureBridgeStyles();

  var banner = doc.getElementById('ag-mb-share-banner');
  if (!banner) {
    banner = doc.createElement('div');
    banner.id = 'ag-mb-share-banner';
    banner.setAttribute('data-ag-mobile-bridge', 'true');
    banner.setAttribute('role', 'status');

    var actionBtn = doc.createElement('button');
    actionBtn.type = 'button';
    actionBtn.className = 'ag-mb-share-action';
    actionBtn.textContent = 'Shared content ready \u2014 tap to paste into chat';
    actionBtn.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      applyBufferedShare();
    });

    var closeBtn = doc.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'ag-mb-share-close';
    closeBtn.setAttribute('aria-label', 'Dismiss shared content');
    closeBtn.textContent = '\u00d7';
    closeBtn.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      state.pendingShare = null;
      removeShareBanner();
    });

    banner.appendChild(actionBtn);
    banner.appendChild(closeBtn);
    doc.body.appendChild(banner);
  }
  if (typeof syncDrawerState === 'function') syncDrawerState();
}

function deliverSharedPayload(rawPayload) {
  var payload = parseSharedPayload(rawPayload);
  var formatted = formatSharedPayload(payload);
  if (!formatted) {
    return { ok: false, reason: 'empty_payload' };
  }
  state.shareDeliveries++;

  var composer = findComposer();
  if (composer && insertTextIntoEditable(composer, formatted, '\n')) {
    state.pendingShare = null;
    removeShareBanner();
    if (typeof showBridgeToast === 'function') {
      showBridgeToast('Shared content inserted');
    }
    return { ok: true, inserted: true, buffered: false };
  }

  state.pendingShare = {
    payload: payload,
    formatted: formatted,
    receivedAt: Date.now()
  };
  if (doc && doc.body) {
    showShareBanner();
  } else if (doc) {
    doc.addEventListener('DOMContentLoaded', function onReady() {
      doc.removeEventListener('DOMContentLoaded', onReady);
      if (state.pendingShare) showShareBanner();
    });
  }
  return { ok: true, inserted: false, buffered: true };
}

/**
 * patches/mobile_bridge/extra_keys.js - Extra-Keys dispatcher for xterm & chat.
 */
var KEY_ALIASES = {
  ESC: 'Escape',
  ESCAPE: 'Escape',
  TAB: 'Tab',
  ENTER: 'Enter',
  RETURN: 'Enter',
  UP: 'ArrowUp',
  ARROWUP: 'ArrowUp',
  DOWN: 'ArrowDown',
  ARROWDOWN: 'ArrowDown',
  LEFT: 'ArrowLeft',
  ARROWLEFT: 'ArrowLeft',
  RIGHT: 'ArrowRight',
  ARROWRIGHT: 'ArrowRight',
  BACKSPACE: 'Backspace',
  DELETE: 'Delete',
  HOME: 'Home',
  END: 'End',
  PGUP: 'PageUp',
  PAGEUP: 'PageUp',
  PGDN: 'PageDown',
  PAGEDOWN: 'PageDown'
};

var SPECIAL_KEY_META = {
  Escape: { code: 'Escape', keyCode: 27, ansi: '\x1b' },
  Tab: { code: 'Tab', keyCode: 9, ansi: '\t' },
  Enter: { code: 'Enter', keyCode: 13, ansi: '\r' },
  Backspace: { code: 'Backspace', keyCode: 8, ansi: '\x7f' },
  Delete: { code: 'Delete', keyCode: 46, ansi: '\x1b[3~' },
  ArrowUp: { code: 'ArrowUp', keyCode: 38, ansi: '\x1b[A' },
  ArrowDown: { code: 'ArrowDown', keyCode: 40, ansi: '\x1b[B' },
  ArrowRight: { code: 'ArrowRight', keyCode: 39, ansi: '\x1b[C' },
  ArrowLeft: { code: 'ArrowLeft', keyCode: 37, ansi: '\x1b[D' },
  Home: { code: 'Home', keyCode: 36, ansi: '\x1b[H' },
  End: { code: 'End', keyCode: 35, ansi: '\x1b[F' },
  PageUp: { code: 'PageUp', keyCode: 33, ansi: '\x1b[5~' },
  PageDown: { code: 'PageDown', keyCode: 34, ansi: '\x1b[6~' }
};

var CHAR_CODE_MAP = {
  '/': { code: 'Slash', keyCode: 191 },
  '@': { code: 'Digit2', keyCode: 50, shiftKey: true },
  '|': { code: 'Backslash', keyCode: 220, shiftKey: true },
  '~': { code: 'Backquote', keyCode: 192, shiftKey: true },
  '-': { code: 'Minus', keyCode: 189 },
  '_': { code: 'Minus', keyCode: 189, shiftKey: true },
  '[': { code: 'BracketLeft', keyCode: 219 },
  ']': { code: 'BracketRight', keyCode: 221 },
  '\\': { code: 'Backslash', keyCode: 220 }
};

function normalizeExtraKeyName(rawKey) {
  var s = String(rawKey == null ? '' : rawKey);
  if (!s) return '';
  var upper = s.trim().toUpperCase();
  if (Object.prototype.hasOwnProperty.call(KEY_ALIASES, upper)) {
    return KEY_ALIASES[upper];
  }
  return s;
}

function resolveKeyMeta(key, ctrl) {
  if (Object.prototype.hasOwnProperty.call(SPECIAL_KEY_META, key)) {
    var sp = SPECIAL_KEY_META[key];
    return {
      key: key,
      code: sp.code,
      keyCode: sp.keyCode,
      shiftKey: false,
      ctrlKey: !!ctrl,
      ansi: sp.ansi,
      isPrintable: false
    };
  }

  var ch = key.length === 1 ? key : key.charAt(0);
  var upperCode = ch.toUpperCase().charCodeAt(0);
  var ctrlAnsi = null;
  if (ctrl) {
    if (upperCode >= 65 && upperCode <= 90) {
      // Ctrl+A..Z -> \x01..\x1a (Ctrl+C=\x03, Ctrl+D=\x04, Ctrl+L=\x0c, Ctrl+Z=\x1a)
      ctrlAnsi = String.fromCharCode(upperCode - 64);
    } else if (ch === '[' || ch === '3') {
      ctrlAnsi = '\x1b';
    } else if (ch === '\\' || ch === '4') {
      ctrlAnsi = '\x1c';
    } else if (ch === ']' || ch === '5') {
      ctrlAnsi = '\x1d';
    } else if (ch === '^' || ch === '6') {
      ctrlAnsi = '\x1e';
    } else if (ch === '_' || ch === '/' || ch === '7') {
      ctrlAnsi = '\x1f';
    }
  }

  var mapped = CHAR_CODE_MAP[ch];
  var code = mapped ? mapped.code : (upperCode >= 65 && upperCode <= 90 ? ('Key' + ch.toUpperCase()) : '');
  var keyCode = mapped ? mapped.keyCode : upperCode;
  var shiftKey = mapped ? !!mapped.shiftKey : (ch !== ch.toLowerCase() && ch === ch.toUpperCase());

  return {
    key: ch,
    code: code,
    keyCode: keyCode,
    shiftKey: shiftKey,
    ctrlKey: !!ctrl,
    ansi: ctrl ? (ctrlAnsi || ch) : ch,
    isPrintable: !ctrl && ch.length === 1
  };
}

function buildKeyboardEvent(type, meta) {
  var init = {
    key: meta.key,
    code: meta.code || '',
    keyCode: meta.keyCode || 0,
    which: meta.keyCode || 0,
    ctrlKey: !!meta.ctrlKey,
    shiftKey: !!meta.shiftKey,
    altKey: false,
    metaKey: false,
    bubbles: true,
    cancelable: true,
    composed: true
  };
  var ev = new KeyboardEvent(type, init);
  // Chromium WebView leaves legacy keyCode/which as 0 on synthetic KeyboardEvent unless defined.
  try {
    if (ev.keyCode !== meta.keyCode) {
      Object.defineProperty(ev, 'keyCode', { value: meta.keyCode });
    }
    if (ev.which !== meta.keyCode) {
      Object.defineProperty(ev, 'which', { value: meta.keyCode });
    }
  } catch (_) {}
  return ev;
}

function findActiveTerminalTextarea() {
  if (!doc) return null;
  var active = doc.activeElement;
  if (active && isTerminalElement(active)) {
    if ((active.tagName || '').toUpperCase() === 'TEXTAREA') return active;
    var nested = active.querySelector ? active.querySelector('.xterm-helper-textarea, textarea') : null;
    if (nested) return nested;
    var root = active.closest ? active.closest('.xterm') : null;
    if (root) {
      var helper = root.querySelector('.xterm-helper-textarea, textarea');
      if (helper) return helper;
    }
  }
  if (state.lastTerminal && state.lastTerminal.isConnected && isElementVisible(state.lastTerminal.closest ? (state.lastTerminal.closest('.xterm') || state.lastTerminal) : state.lastTerminal)) {
    // Only use remembered terminal if no non-terminal editable element is currently focused.
    if (!isEditableElement(active)) {
      return state.lastTerminal;
    }
  }
  return null;
}

function sendToTerminal(textarea, meta) {
  try { textarea.focus({ preventScroll: true }); } catch (_) {
    try { textarea.focus(); } catch (_) {}
  }

  var downEv = buildKeyboardEvent('keydown', meta);
  var notCancelled = textarea.dispatchEvent(downEv);

  // If xterm.js did not already consume the keydown (e.g. printable char or custom terminal),
  // feed the exact byte / ANSI sequence through the helper textarea input pipeline.
  if (notCancelled && meta.ansi) {
    if (meta.isPrintable) {
      try {
        textarea.dispatchEvent(buildKeyboardEvent('keypress', meta));
      } catch (_) {}
    }
    try {
      setNativeControlValue(textarea, meta.ansi);
      if (typeof global.InputEvent === 'function') {
        textarea.dispatchEvent(new InputEvent('input', {
          bubbles: true,
          cancelable: false,
          composed: true,
          inputType: 'insertText',
          data: meta.ansi
        }));
      } else {
        textarea.dispatchEvent(new Event('input', { bubbles: true }));
      }
      setNativeControlValue(textarea, '');
    } catch (_) {}
  }

  try {
    textarea.dispatchEvent(buildKeyboardEvent('keyup', meta));
  } catch (_) {}

  return { ok: true, target: 'terminal', key: meta.key, ctrl: meta.ctrlKey, ansi: meta.ansi };
}

function sendToChatOrDocument(meta) {
  var target = findComposer();
  if (!target) {
    target = (doc && doc.activeElement && !isOwnBridgeElement(doc.activeElement))
      ? doc.activeElement
      : (doc && (doc.body || doc.documentElement));
  }
  if (!target) {
    return { ok: false, reason: 'no_target' };
  }

  if (isEditableElement(target)) {
    try { target.focus(); } catch (_) {}
    var downEv = buildKeyboardEvent('keydown', meta);
    var notCancelled = target.dispatchEvent(downEv);
    if (notCancelled && meta.isPrintable) {
      insertTextIntoEditable(target, meta.key, '');
    }
    try { target.dispatchEvent(buildKeyboardEvent('keyup', meta)); } catch (_) {}
    if (meta.key === 'Escape' && notCancelled && doc) {
      try { doc.dispatchEvent(buildKeyboardEvent('keydown', meta)); } catch (_) {}
    }
    return { ok: true, target: 'composer', key: meta.key, ctrl: meta.ctrlKey };
  }

  var docDown = buildKeyboardEvent('keydown', meta);
  target.dispatchEvent(docDown);
  try { target.dispatchEvent(buildKeyboardEvent('keyup', meta)); } catch (_) {}
  return { ok: true, target: 'document', key: meta.key, ctrl: meta.ctrlKey };
}

function dispatchExtraKey(rawKey, ctrl) {
  var key = normalizeExtraKeyName(rawKey);
  if (!key) return { ok: false, reason: 'empty_key' };
  state.extraKeyDispatches++;

  var meta = resolveKeyMeta(key, !!ctrl);
  var terminalTextarea = findActiveTerminalTextarea();
  if (terminalTextarea) {
    return sendToTerminal(terminalTextarea, meta);
  }
  return sendToChatOrDocument(meta);
}

/**
 * patches/mobile_bridge/voice.js - native Android Voice Service & Dictation bridge.
 *
 * - Exposes window.__agDeliverVoiceText(text, isFinal) for Android SpeechRecognizer.
 * - Exposes window.__agVoiceServiceStateChanged({active, muted}) for VoiceForegroundService.
 * - Non-invasively observes window.WebSocket for /voice/ws sessions so Live Voice
 *   Call automatically starts/stops VoiceForegroundService (AudioFocus + notification).
 */
function isVoiceServiceActiveNative() {
  var android = getAndroid();
  try {
    if (android && typeof android.isVoiceServiceActive === 'function') {
      return !!android.isVoiceServiceActive();
    }
  } catch (_) {}
  return !!state.voiceServiceActive;
}

function isVoiceMutedNative() {
  var android = getAndroid();
  try {
    if (android && typeof android.isVoiceMuted === 'function') {
      return !!android.isVoiceMuted();
    }
  } catch (_) {}
  return !!state.voiceServiceMuted;
}

function isVoiceDictatingNative() {
  var android = getAndroid();
  try {
    if (android && typeof android.isVoiceDictating === 'function') {
      return !!android.isVoiceDictating();
    }
  } catch (_) {}
  return !!state.dictating;
}

function startNativeVoiceService() {
  var android = getAndroid();
  try {
    if (android && typeof android.startVoiceService === 'function') {
      var res = android.startVoiceService(getNativeToken());
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.voiceServiceActive = true;
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] startVoiceService failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}

function stopNativeVoiceService() {
  var android = getAndroid();
  try {
    if (android && typeof android.stopVoiceService === 'function') {
      var res = android.stopVoiceService(getNativeToken());
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.voiceServiceActive = false;
      state.voiceServiceMuted = false;
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] stopVoiceService failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}

function setNativeVoiceMuted(muted) {
  var next = !!muted;
  applyMicTracksMute(next);
  var android = getAndroid();
  try {
    if (android && typeof android.setVoiceMuted === 'function') {
      var res = android.setVoiceMuted(getNativeToken(), next);
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.voiceServiceMuted = next;
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true, muted: next };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] setVoiceMuted failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  state.voiceServiceMuted = next;
  if (typeof syncDrawerState === 'function') syncDrawerState();
  return { ok: false, reason: 'bridge_unavailable' };
}

function startNativeVoiceDictation() {
  var android = getAndroid();
  try {
    if (android && typeof android.startVoiceDictation === 'function') {
      var res = android.startVoiceDictation(getNativeToken());
      if (res === false) return { ok: false, reason: 'native_rejected' };
      state.dictating = true;
      state.voiceInterim = '';
      if (typeof syncDrawerState === 'function') syncDrawerState();
      if (typeof showBridgeToast === 'function') showBridgeToast('Listening\u2026');
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] startVoiceDictation failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
  return { ok: false, reason: 'bridge_unavailable' };
}

function stopNativeVoiceDictation() {
  var android = getAndroid();
  state.dictating = false;
  state.voiceInterim = '';
  try {
    if (android && typeof android.stopVoiceDictation === 'function') {
      android.stopVoiceDictation(getNativeToken());
      if (typeof syncDrawerState === 'function') syncDrawerState();
      return { ok: true };
    }
  } catch (e) {
    try { console.warn('[agMobileBridge] stopVoiceDictation failed:', e); } catch (_) {}
    if (typeof syncDrawerState === 'function') syncDrawerState();
    return { ok: false, reason: 'bridge_error' };
  }
  if (typeof syncDrawerState === 'function') syncDrawerState();
  return { ok: false, reason: 'bridge_unavailable' };
}

function applyMicTracksMute(muted) {
  var alive = [];
  for (var i = 0; i < state.activeMicTracks.length; i++) {
    var tr = state.activeMicTracks[i];
    if (!tr || tr.readyState === 'ended') continue;
    try { tr.enabled = !muted; } catch (_) {}
    alive.push(tr);
  }
  state.activeMicTracks = alive;
}

function deliverVoiceText(text, isFinal) {
  var str = typeof text === 'string' ? text.trim() : '';
  var finalFlag = isFinal !== false;

  if (!finalFlag) {
    state.dictating = true;
    state.voiceInterim = str;
    if (typeof syncDrawerState === 'function') syncDrawerState();
    if (str && typeof showBridgeToast === 'function') {
      showBridgeToast('\ud83c\udfa4 ' + (str.length > 64 ? str.slice(0, 61) + '\u2026' : str));
    }
    return { ok: true, interim: true, text: str };
  }

  state.dictating = false;
  state.voiceInterim = '';
  if (typeof syncDrawerState === 'function') syncDrawerState();

  if (!str) {
    return { ok: false, reason: 'empty_transcript' };
  }

  state.voiceDeliveries++;
  var composer = findComposer();
  if (composer && insertTextIntoEditable(composer, str, ' ')) {
    if (typeof showBridgeToast === 'function') {
      showBridgeToast('Voice text inserted');
    }
    return { ok: true, inserted: true, text: str };
  }

  // No composer mounted yet: reuse Share Sheet buffer banner so text is never lost.
  return deliverSharedPayload({ text: str });
}

function onVoiceDictationStateChanged(payload) {
  var info = payload;
  if (typeof payload === 'string') {
    try { info = JSON.parse(payload); } catch (_) { info = null; }
  }
  if (!info || typeof info !== 'object') return;
  state.dictating = !!info.listening;
  if (!state.dictating) {
    state.voiceInterim = '';
  }
  if (typeof syncDrawerState === 'function') syncDrawerState();
  if (info.error && typeof showBridgeToast === 'function') {
    showBridgeToast('Voice dictation: ' + String(info.error));
  }
}

function onVoiceServiceStateChanged(payload) {
  var info = payload;
  if (typeof payload === 'string') {
    try { info = JSON.parse(payload); } catch (_) { info = null; }
  }
  if (!info || typeof info !== 'object') return;

  var active = !!info.active;
  var muted = !!info.muted;
  var wasActive = state.voiceServiceActive;
  state.voiceServiceActive = active;
  state.voiceServiceMuted = active ? muted : false;

  applyMicTracksMute(state.voiceServiceMuted);

  // If the user tapped "Stop Voice" in the Android notification shade, close active /voice/ws sockets.
  if (!active && wasActive && state.activeVoiceSockets.length > 0) {
    var sockets = state.activeVoiceSockets.slice();
    state.activeVoiceSockets = [];
    for (var i = 0; i < sockets.length; i++) {
      try {
        if (sockets[i] && sockets[i].readyState <= 1) {
          sockets[i].close(1000, 'stopped_from_notification');
        }
      } catch (_) {}
    }
  }

  if (typeof syncDrawerState === 'function') syncDrawerState();
  try {
    if (typeof global.CustomEvent === 'function') {
      global.dispatchEvent(new CustomEvent('ag-voice-service-state', {
        detail: { active: state.voiceServiceActive, muted: state.voiceServiceMuted }
      }));
    }
  } catch (_) {}
}

function isVoiceWebSocketUrl(url) {
  if (!url) return false;
  return /\/voice\/ws(?:[?#]|$)/i.test(String(url));
}

function trackVoiceSocket(ws) {
  if (!ws) return;
  var cleaned = false;
  function onOpen() {
    if (cleaned) return;
    if (state.activeVoiceSockets.indexOf(ws) === -1) {
      state.activeVoiceSockets.push(ws);
    }
    if (state.activeVoiceSockets.length === 1) {
      startNativeVoiceService();
    }
  }
  function onDone() {
    if (cleaned) return;
    cleaned = true;
    var idx = state.activeVoiceSockets.indexOf(ws);
    if (idx >= 0) state.activeVoiceSockets.splice(idx, 1);
    if (state.activeVoiceSockets.length === 0 && state.voiceServiceActive) {
      stopNativeVoiceService();
    }
  }
  try {
    ws.addEventListener('open', onOpen);
    ws.addEventListener('close', onDone);
    ws.addEventListener('error', onDone);
  } catch (_) {}
}

function installVoiceWebSocketHook() {
  var NativeWS = global.WebSocket;
  if (typeof NativeWS !== 'function' || NativeWS.__agVoiceWrapped) return;

  function WrappedWebSocket(url, protocols) {
    var ws;
    if (typeof Reflect === 'object' && typeof Reflect.construct === 'function') {
      ws = arguments.length > 1
        ? Reflect.construct(NativeWS, [url, protocols], new.target || WrappedWebSocket)
        : Reflect.construct(NativeWS, [url], new.target || WrappedWebSocket);
    } else {
      ws = arguments.length > 1 ? new NativeWS(url, protocols) : new NativeWS(url);
    }
    if (isVoiceWebSocketUrl(url)) {
      trackVoiceSocket(ws);
    }
    return ws;
  }

  WrappedWebSocket.prototype = NativeWS.prototype;
  WrappedWebSocket.CONNECTING = NativeWS.CONNECTING;
  WrappedWebSocket.OPEN = NativeWS.OPEN;
  WrappedWebSocket.CLOSING = NativeWS.CLOSING;
  WrappedWebSocket.CLOSED = NativeWS.CLOSED;
  WrappedWebSocket.__agVoiceWrapped = true;

  try {
    global.WebSocket = WrappedWebSocket;
  } catch (_) {}
}

function installMicTrackObserver() {
  try {
    var md = global.navigator && global.navigator.mediaDevices;
    if (!md || typeof md.getUserMedia !== 'function' || md.getUserMedia.__agVoiceWrapped) return;
    var origGum = md.getUserMedia.bind(md);
    var wrappedGum = function (constraints) {
      var promise = origGum(constraints);
      if (promise && typeof promise.then === 'function' && constraints && constraints.audio) {
        promise.then(function (stream) {
          if (!stream || typeof stream.getAudioTracks !== 'function') return;
          var tracks = stream.getAudioTracks();
          for (var i = 0; i < tracks.length; i++) {
            var tr = tracks[i];
            if (!tr) continue;
            if (state.voiceServiceMuted) {
              try { tr.enabled = false; } catch (_) {}
            }
            if (state.activeMicTracks.indexOf(tr) === -1) {
              state.activeMicTracks.push(tr);
            }
            try {
              tr.addEventListener('ended', function (ev) {
                var idx = state.activeMicTracks.indexOf(ev.target);
                if (idx >= 0) state.activeMicTracks.splice(idx, 1);
              });
            } catch (_) {}
          }
        }, function () {});
      }
      return promise;
    };
    wrappedGum.__agVoiceWrapped = true;
    md.getUserMedia = wrappedGum;
  } catch (_) {}
}

/**
 * patches/mobile_bridge/clipboard_image.js - Android clipboard & IME image paste.
 *
 * Handles screenshots/images pasted via:
 *   1. IME commitContent / OnReceiveContentListener -> window.__agDeliverPastedImage(base64, mime, filename)
 *   2. Android ClipboardManager fallback on empty WebView `paste` events
 *   3. One-tap "Paste Image" button in Quick Controls
 *
 * Targets v2.34.0's verified attachment paths cleanly without double-attaching:
 *   - Primary: ClipboardEvent('paste') on active Lexical composer (PASTE_COMMAND checks clipboardData.items[kind==='file'])
 *   - Fallback 1: DragEvent('drop') on composer / [data-media-drop-scope] (window drop listener)
 *   - Fallback 2: <input type="file"> FileList setter + change event
 */
var MIME_TO_EXT = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/jpg': 'jpg',
  'image/webp': 'webp',
  'image/gif': 'gif',
  'image/bmp': 'bmp',
  'image/avif': 'avif',
  'image/heic': 'heic',
  'image/heif': 'heif'
};

function sanitizeImageMime(rawMime) {
  var m = typeof rawMime === 'string' ? rawMime.trim().toLowerCase() : '';
  if (!m || m.indexOf('image/') !== 0 || m === 'image/*') return 'image/png';
  var semi = m.indexOf(';');
  if (semi > 0) m = m.slice(0, semi).trim();
  return m || 'image/png';
}

function sanitizeImageFilename(rawName, mime) {
  var ext = MIME_TO_EXT[mime] || 'png';
  var name = typeof rawName === 'string' ? rawName.trim() : '';
  if (!name) return 'pasted-' + Date.now() + '.' + ext;
  name = name.replace(/[/\\:*?"<>|]+/g, '_');
  if (name.indexOf('.') === -1) name += '.' + ext;
  return name;
}

function base64ToUint8Array(rawBase64) {
  var b64 = String(rawBase64 || '').trim();
  if (!b64) return null;
  var comma = b64.indexOf(',');
  if (b64.indexOf('data:') === 0 && comma !== -1) {
    b64 = b64.slice(comma + 1);
  }
  b64 = b64.replace(/\s+/g, '');
  if (!b64) return null;
  try {
    var bin = global.atob(b64);
    var len = bin.length;
    var out = new Uint8Array(len);
    for (var i = 0; i < len; i++) {
      out[i] = bin.charCodeAt(i);
    }
    return out;
  } catch (_) {
    return null;
  }
}

function buildDataTransferWithFile(file) {
  if (typeof global.DataTransfer === 'function') {
    try {
      var dt = new global.DataTransfer();
      dt.items.add(file);
      return dt;
    } catch (_) {}
  }
  // Minimal DataTransfer fallback for environments lacking full DataTransfer constructor.
  var fakeItem = {
    kind: 'file',
    type: file.type,
    getAsFile: function () { return file; }
  };
  return {
    types: ['Files'],
    files: [file],
    items: [fakeItem],
    dropEffect: 'copy',
    getData: function () { return ''; },
    setData: function () {}
  };
}

function createSyntheticClipboardPasteEvent(dt) {
  var ev;
  if (typeof global.ClipboardEvent === 'function') {
    try {
      ev = new global.ClipboardEvent('paste', {
        bubbles: true,
        cancelable: true,
        composed: true,
        clipboardData: dt
      });
    } catch (_) {
      ev = new global.ClipboardEvent('paste', {
        bubbles: true,
        cancelable: true,
        composed: true
      });
    }
  } else {
    ev = new global.Event('paste', { bubbles: true, cancelable: true, composed: true });
  }
  // Some Android WebView / Chromium builds ignore constructor clipboardData; force-attach dt.
  try {
    var hasFiles = ev.clipboardData && ev.clipboardData.files && ev.clipboardData.files.length > 0;
    var hasItems = ev.clipboardData && ev.clipboardData.items && ev.clipboardData.items.length > 0;
    if (!hasFiles && !hasItems) {
      Object.defineProperty(ev, 'clipboardData', {
        value: dt,
        configurable: true
      });
    }
  } catch (_) {}
  ev.__agSyntheticPaste = true;
  return ev;
}

function createSyntheticDropEvent(dt) {
  var ev;
  var Ctor = typeof global.DragEvent === 'function' ? global.DragEvent : global.Event;
  try {
    ev = new Ctor('drop', {
      bubbles: true,
      cancelable: true,
      composed: true,
      dataTransfer: dt
    });
  } catch (_) {
    ev = new global.Event('drop', { bubbles: true, cancelable: true, composed: true });
  }
  try {
    var hasFiles = ev.dataTransfer && ev.dataTransfer.files && ev.dataTransfer.files.length > 0;
    if (!hasFiles) {
      Object.defineProperty(ev, 'dataTransfer', {
        value: dt,
        configurable: true
      });
    }
  } catch (_) {}
  ev.__agSyntheticDrop = true;
  return ev;
}

function findMediaFileInput() {
  if (!doc) return null;
  var inputs = [];
  try {
    inputs = doc.querySelectorAll('input[type="file"]');
  } catch (_) {
    return null;
  }
  var fallback = null;
  for (var i = 0; i < inputs.length; i++) {
    var inp = inputs[i];
    if (!inp || inp.disabled || isOwnBridgeElement(inp)) continue;
    var accept = (inp.getAttribute('accept') || '').toLowerCase();
    if (!accept || accept.indexOf('image') !== -1 || accept.indexOf('*/*') !== -1) {
      return inp;
    }
    if (!fallback) fallback = inp;
  }
  return fallback;
}

function deliverPastedImage(rawBase64, rawMime, rawFilename) {
  // Also accept object payload {base64, mime, name} if called that way.
  if (rawBase64 && typeof rawBase64 === 'object') {
    var obj = rawBase64;
    rawBase64 = obj.base64;
    rawMime = obj.mime || obj.mimeType;
    rawFilename = obj.name || obj.filename;
  }

  var mime = sanitizeImageMime(rawMime);
  var filename = sanitizeImageFilename(rawFilename, mime);
  var bytes = base64ToUint8Array(rawBase64);
  if (!bytes || bytes.length === 0) {
    return { ok: false, reason: 'invalid_base64' };
  }

  var FileCtor = global.File;
  var file;
  if (typeof FileCtor === 'function') {
    file = new FileCtor([bytes], filename, { type: mime, lastModified: Date.now() });
  } else {
    file = { name: filename, type: mime, size: bytes.length, bytes: bytes };
  }

  var dt = buildDataTransferWithFile(file);
  var composer = findComposer();

  // 1. Primary: synthetic ClipboardEvent('paste') on focused composer (Lexical PASTE_COMMAND).
  if (composer) {
    try { composer.focus(); } catch (_) {}
    var pasteEv = createSyntheticClipboardPasteEvent(dt);
    composer.dispatchEvent(pasteEv);
    if (pasteEv.defaultPrevented) {
      state.imagePastes++;
      if (typeof showBridgeToast === 'function') {
        showBridgeToast('Image pasted: ' + filename);
      }
      return { ok: true, method: 'paste', name: filename, mime: mime, size: bytes.length };
    }
  }

  // 2. Fallback 1: synthetic DragEvent('drop') targeting composer or [data-media-drop-scope].
  var dropTarget = composer;
  if (!dropTarget && doc) {
    try {
      dropTarget = doc.querySelector('[data-media-drop-scope]') || doc.body;
    } catch (_) {
      dropTarget = doc.body;
    }
  }
  if (dropTarget) {
    var dropEv = createSyntheticDropEvent(dt);
    dropTarget.dispatchEvent(dropEv);
    if (!dropEv.defaultPrevented && dropTarget !== global && typeof global.dispatchEvent === 'function') {
      // If mock/detached target didn't bubble to window, dispatch directly on window only when not prevented.
      try {
        if (!dropTarget.isConnected) {
          global.dispatchEvent(dropEv);
        }
      } catch (_) {}
    }
    if (dropEv.defaultPrevented) {
      state.imagePastes++;
      if (typeof showBridgeToast === 'function') {
        showBridgeToast('Image attached: ' + filename);
      }
      return { ok: true, method: 'drop', name: filename, mime: mime, size: bytes.length };
    }
  }

  // 3. Fallback 2: hidden <input type="file"> upload input.
  var fileInput = findMediaFileInput();
  if (fileInput) {
    try {
      try {
        fileInput.files = dt.files;
      } catch (_) {
        Object.defineProperty(fileInput, 'files', { value: dt.files, configurable: true });
      }
      fileInput.dispatchEvent(new global.Event('change', { bubbles: true }));
      state.imagePastes++;
      if (typeof showBridgeToast === 'function') {
        showBridgeToast('Image attached: ' + filename);
      }
      return { ok: true, method: 'file_input', name: filename, mime: mime, size: bytes.length };
    } catch (_) {}
  }

  if (typeof showBridgeToast === 'function') {
    showBridgeToast('Open a chat to paste image');
  }
  return { ok: false, reason: 'no_target', name: filename, mime: mime };
}

function readNativeClipboardImage() {
  var android = getAndroid();
  if (!android || typeof android.readClipboardImage !== 'function') {
    return { ok: false, reason: 'bridge_unavailable' };
  }
  try {
    var raw = android.readClipboardImage(getNativeToken());
    if (!raw) return { ok: false, reason: 'empty' };
    var parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!parsed || !parsed.ok || !parsed.base64) {
      return { ok: false, reason: (parsed && parsed.error) || 'no_image' };
    }
    return parsed;
  } catch (e) {
    try { console.warn('[agMobileBridge] readClipboardImage failed:', e); } catch (_) {}
    return { ok: false, reason: 'bridge_error' };
  }
}

function hasNativeClipboardImage() {
  var android = getAndroid();
  try {
    if (android && typeof android.hasClipboardImage === 'function') {
      return !!android.hasClipboardImage();
    }
  } catch (_) {}
  return false;
}

function pasteImageFromNativeClipboard() {
  var img = readNativeClipboardImage();
  if (!img.ok) {
    if (typeof showBridgeToast === 'function') {
      showBridgeToast(img.reason === 'bridge_unavailable'
        ? 'Clipboard image bridge unavailable'
        : 'No image in clipboard');
    }
    return img;
  }
  return deliverPastedImage(img.base64, img.mime, img.name);
}

function clipboardEventHasImage(ev) {
  var cd = ev && ev.clipboardData;
  if (!cd) return false;
  var files = cd.files;
  if (files && files.length > 0) {
    for (var i = 0; i < files.length; i++) {
      if (files[i] && typeof files[i].type === 'string' && files[i].type.indexOf('image/') === 0) {
        return true;
      }
    }
  }
  var items = cd.items;
  if (items && items.length > 0) {
    for (var j = 0; j < items.length; j++) {
      var it = items[j];
      if (it && it.kind === 'file' && typeof it.type === 'string' && it.type.indexOf('image/') === 0) {
        return true;
      }
    }
  }
  return false;
}

function clipboardEventHasText(ev) {
  var cd = ev && ev.clipboardData;
  if (!cd || typeof cd.getData !== 'function') return false;
  try {
    if (cd.getData('text/plain')) return true;
    if (cd.getData('text/html')) return true;
  } catch (_) {}
  return false;
}

function installClipboardImageInterceptor() {
  if (!doc || state.pasteInterceptorInstalled) return;
  state.pasteInterceptorInstalled = true;

  doc.addEventListener('paste', function (ev) {
    if (!ev || ev.__agSyntheticPaste) return;
    if (ev.target && isOwnBridgeElement(ev.target)) return;
    if (ev.target && isTerminalElement(ev.target)) return;
    // WebView already supplied an image — let normal handling run untouched.
    if (clipboardEventHasImage(ev)) return;

    var hasText = clipboardEventHasText(ev);
    var nativeHasImg = hasNativeClipboardImage();
    // If WebView delivered text and native clipboard does not hold an image, let normal text paste run.
    if (hasText && !nativeHasImg) return;

    var android = getAndroid();
    if (!android || typeof android.readClipboardImage !== 'function') return;

    var img = readNativeClipboardImage();
    if (!img.ok) return;

    ev.preventDefault();
    ev.stopImmediatePropagation();
    deliverPastedImage(img.base64, img.mime, img.name);
  }, true);
}

/**
 * patches/mobile_bridge/controls.js - native Settings "Android Tools" section.
 *
 * Keeps the chat and Swarm screens 100% clean (zero floating pill overlay!).
 * Automatically injects a native-styled "Android Tools" card into Settings
 * (Appearance / Application) via MutationObserver, providing resilient access
 * to Desktop Mode, Extra-Keys Bar, Localhost Preview, PC Remote Control,
 * Voice Dictation / Voice Service, and Clipboard Image Paste independent of
 * upstream main.js internals.
 */
var settingsObserver = null;
var settingsScanTimer = 0;

function ensureBridgeStyles() {
  if (!doc || doc.getElementById('ag-mb-styles')) return;
  var style = doc.createElement('style');
  style.id = 'ag-mb-styles';
  style.textContent = [
    '#ag-mb-settings-section {',
    '  margin-top: 20px;',
    '  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;',
    '}',
    '.ag-mb-sec-heading {',
    '  font-size: 13px; font-weight: 600; opacity: 0.9; margin-bottom: 8px;',
    '}',
    '.ag-mb-card {',
    '  border-radius: 12px; overflow: hidden;',
    '  border: 1px solid rgba(148,163,184,0.22);',
    '  background: rgba(15,23,42,0.22);',
    '}',
    'html.light .ag-mb-card {',
    '  border-color: rgba(15,23,42,0.12); background: rgba(15,23,42,0.02);',
    '}',
    '.ag-mb-row {',
    '  display: flex; align-items: center; justify-content: space-between; gap: 12px;',
    '  padding: 12px 14px; border-top: 1px solid rgba(148,163,184,0.14);',
    '}',
    '.ag-mb-row:first-child { border-top: 0; }',
    '.ag-mb-row-col {',
    '  display: flex; flex-direction: column; gap: 6px;',
    '  padding: 12px 14px; border-top: 1px solid rgba(148,163,184,0.14);',
    '}',
    '.ag-mb-row-label { display: flex; flex-direction: column; gap: 2px; min-width: 0; }',
    '.ag-mb-row-title { font-weight: 600; font-size: 13px; }',
    '.ag-mb-row-sub { font-size: 12px; opacity: 0.7; }',
    '.ag-mb-toggle {',
    '  position: relative; width: 42px; height: 24px; flex: 0 0 auto; border: 0;',
    '  border-radius: 999px; background: rgba(148,163,184,0.38); cursor: pointer;',
    '  padding: 0; transition: background 0.16s ease;',
    '}',
    '.ag-mb-toggle[aria-checked="true"] { background: #38bdf8; }',
    'html.light .ag-mb-toggle[aria-checked="true"] { background: #0284c7; }',
    '.ag-mb-toggle::after {',
    '  content: ""; position: absolute; top: 3px; left: 3px; width: 18px; height: 18px;',
    '  border-radius: 50%; background: #ffffff; transition: transform 0.16s ease;',
    '}',
    '.ag-mb-toggle[aria-checked="true"]::after { transform: translateX(18px); }',
    '.ag-mb-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 2px; }',
    '.ag-mb-chip {',
    '  height: 30px; padding: 0 10px; border-radius: 8px; cursor: pointer;',
    '  border: 1px solid rgba(148,163,184,0.28); background: rgba(56,189,248,0.12);',
    '  color: inherit; font: 600 12px/1 monospace;',
    '}',
    '.ag-mb-custom { display: flex; gap: 6px; margin-top: 4px; }',
    '.ag-mb-input {',
    '  flex: 1; min-width: 0; height: 34px; padding: 0 10px; border-radius: 8px;',
    '  border: 1px solid rgba(148,163,184,0.3); background: rgba(148,163,184,0.08);',
    '  color: inherit; font: 12px/1 monospace;',
    '}',
    '.ag-mb-btn {',
    '  height: 34px; padding: 0 12px; border-radius: 8px; border: 0; cursor: pointer;',
    '  background: #38bdf8; color: #082f49; font-weight: 700; font-size: 12px;',
    '}',
    'html.light .ag-mb-btn { background: #0284c7; color: #ffffff; }',
    '.ag-mb-btn-secondary {',
    '  height: 34px; padding: 0 12px; border-radius: 8px; cursor: pointer;',
    '  border: 1px solid rgba(148,163,184,0.32); background: rgba(148,163,184,0.12);',
    '  color: inherit; font-weight: 600; font-size: 12px;',
    '}',
    '.ag-mb-btn[data-active="true"] { background: #ef4444; color: #ffffff; }',
    '.ag-mb-voice-row { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 2px; }',
    '.ag-mb-voice-status { font-size: 12px; opacity: 0.74; }',
    '#ag-mb-voice-interim { font-size: 12px; opacity: 0.85; font-style: italic; display: none; }',
    '#ag-mb-voice-interim.ag-mb-visible { display: block; }',
    '#ag-mb-drawer-share { width: 100%; display: none; }',
    '#ag-mb-drawer-share.ag-mb-visible { display: block; }',
    '#ag-mb-drawer {',
    '  position: fixed; left: 50%; top: 50%; transform: translate(-50%, -50%);',
    '  z-index: 2147483001; width: min(320px, calc(100vw - 24px));',
    '  max-height: calc(100vh - 64px); overflow-y: auto; overscroll-behavior: contain;',
    '  border-radius: 14px; padding: 14px;',
    '  border: 1px solid rgba(148,163,184,0.28);',
    '  background: rgba(15,23,42,0.97); color: #f1f5f9;',
    '  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);',
    '  box-shadow: 0 18px 42px rgba(0,0,0,0.48);',
    '  font: 13px/1.35 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;',
    '  display: none;',
    '}',
    '#ag-mb-drawer.ag-mb-open { display: block; }',
    'html.light #ag-mb-drawer {',
    '  background: rgba(255,255,255,0.98); color: #0f172a;',
    '  border-color: rgba(15,23,42,0.14); box-shadow: 0 18px 42px rgba(15,23,42,0.2);',
    '}',
    '.ag-mb-hdr { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }',
    '.ag-mb-title { font-size: 14px; font-weight: 700; }',
    '.ag-mb-close {',
    '  width: 28px; height: 28px; border: 0; border-radius: 8px; cursor: pointer;',
    '  background: transparent; color: inherit; font-size: 18px; line-height: 1;',
    '}',
    '#ag-mb-share-banner {',
    '  position: fixed; left: 10px; right: 10px; top: max(10px, env(safe-area-inset-top));',
    '  z-index: 2147483002; display: flex; align-items: center; gap: 8px;',
    '  padding: 8px 10px; border-radius: 12px;',
    '  border: 1px solid rgba(56,189,248,0.45); background: rgba(15,23,42,0.95); color: #f8fafc;',
    '  box-shadow: 0 8px 24px rgba(0,0,0,0.34);',
    '  font: 600 12px/1.3 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;',
    '}',
    'html.light #ag-mb-share-banner { background: rgba(255,255,255,0.98); color: #0f172a; border-color: rgba(2,132,199,0.4); }',
    '.ag-mb-share-action {',
    '  flex: 1; text-align: left; border: 0; background: transparent; color: inherit;',
    '  font: inherit; padding: 4px 2px; cursor: pointer;',
    '}',
    '.ag-mb-share-close {',
    '  width: 28px; height: 28px; flex: 0 0 auto; border: 0; border-radius: 8px;',
    '  background: rgba(148,163,184,0.18); color: inherit; font-size: 17px; cursor: pointer;',
    '}',
    '#ag-mb-toast {',
    '  position: fixed; left: 50%; bottom: 26px; transform: translateX(-50%);',
    '  z-index: 2147483003; padding: 8px 14px; border-radius: 999px;',
    '  background: rgba(15,23,42,0.92); color: #f8fafc; border: 1px solid rgba(148,163,184,0.28);',
    '  font: 600 12px/1.2 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;',
    '  box-shadow: 0 6px 20px rgba(0,0,0,0.3); pointer-events: none; max-width: calc(100vw - 32px);',
    '  text-align: center;',
    '}',
    'html.light #ag-mb-toast { background: rgba(15,23,42,0.88); color: #ffffff; }'
  ].join('\n');
  (doc.head || doc.documentElement).appendChild(style);
}

function showBridgeToast(message) {
  if (!doc || !doc.body || !message) return;
  ensureBridgeStyles();
  var toast = doc.getElementById('ag-mb-toast');
  if (!toast) {
    toast = doc.createElement('div');
    toast.id = 'ag-mb-toast';
    toast.setAttribute('data-ag-mobile-bridge', 'true');
    toast.setAttribute('role', 'status');
    doc.body.appendChild(toast);
  }
  toast.textContent = String(message);
  toast.style.display = 'block';
  if (state.toastTimer) clearTimeout(state.toastTimer);
  state.toastTimer = setTimeout(function () {
    if (toast) toast.style.display = 'none';
    state.toastTimer = 0;
  }, 2400);
}

function syncDrawerState() {
  if (!doc) return;
  var desktopBtn = doc.getElementById('ag-mb-toggle-desktop');
  if (desktopBtn) {
    desktopBtn.setAttribute('aria-checked', getDesktopModeState() ? 'true' : 'false');
  }
  var extraBtn = doc.getElementById('ag-mb-toggle-extrakeys');
  if (extraBtn) {
    extraBtn.setAttribute('aria-checked', getExtraKeysState() ? 'true' : 'false');
  }
  var shareBtn = doc.getElementById('ag-mb-drawer-share');
  if (shareBtn) {
    if (state.pendingShare && state.pendingShare.formatted) {
      shareBtn.classList.add('ag-mb-visible');
    } else {
      shareBtn.classList.remove('ag-mb-visible');
    }
  }
  var dictateBtn = doc.getElementById('ag-mb-voice-dictate');
  if (dictateBtn) {
    dictateBtn.setAttribute('data-active', state.dictating ? 'true' : 'false');
    dictateBtn.textContent = state.dictating ? 'Stop Dictation' : '\ud83c\udfa4 Voice Dictation';
  }
  var interimEl = doc.getElementById('ag-mb-voice-interim');
  if (interimEl) {
    if (state.voiceInterim) {
      interimEl.textContent = '\u201c' + state.voiceInterim + '\u201d';
      interimEl.classList.add('ag-mb-visible');
    } else {
      interimEl.textContent = '';
      interimEl.classList.remove('ag-mb-visible');
    }
  }
  var voiceStatus = doc.getElementById('ag-mb-voice-status');
  var voiceActive = state.voiceServiceActive;
  if (voiceStatus) {
    voiceStatus.textContent = voiceActive
      ? ('Voice service: ' + (state.voiceServiceMuted ? 'Active (Muted)' : 'Active'))
      : 'Voice service: Idle';
  }
  var muteBtn = doc.getElementById('ag-mb-voice-mute');
  if (muteBtn) {
    muteBtn.style.display = voiceActive ? 'inline-block' : 'none';
    muteBtn.textContent = state.voiceServiceMuted ? 'Unmute' : 'Mute';
  }
  var stopSvcBtn = doc.getElementById('ag-mb-voice-stop');
  if (stopSvcBtn) {
    stopSvcBtn.style.display = voiceActive ? 'inline-block' : 'none';
  }
}

function createToggleRow(id, title, subtitle, onToggle) {
  var row = doc.createElement('div');
  row.className = 'ag-mb-row';

  var textCol = doc.createElement('div');
  textCol.className = 'ag-mb-row-label';
  var t = doc.createElement('span');
  t.className = 'ag-mb-row-title';
  t.textContent = title;
  var s = doc.createElement('span');
  s.className = 'ag-mb-row-sub';
  s.textContent = subtitle;
  textCol.appendChild(t);
  textCol.appendChild(s);

  var btn = doc.createElement('button');
  btn.id = id;
  btn.type = 'button';
  btn.className = 'ag-mb-toggle';
  btn.setAttribute('role', 'switch');
  btn.setAttribute('aria-label', title);
  btn.setAttribute('aria-checked', 'false');
  btn.addEventListener('click', function (ev) {
    ev.preventDefault();
    ev.stopPropagation();
    var next = btn.getAttribute('aria-checked') !== 'true';
    onToggle(next);
    syncDrawerState();
  });

  row.appendChild(textCol);
  row.appendChild(btn);
  return row;
}

function buildAndroidToolsCard(onActionDone) {
  var card = doc.createElement('div');
  card.className = 'agy-card bg-transparent rounded-xl border border-border overflow-hidden divide-y divide-border ag-mb-card';

  card.appendChild(createToggleRow(
    'ag-mb-toggle-desktop',
    'Desktop Mode',
    'Use desktop viewport and user agent',
    function (next) {
      setDesktopModeState(next);
      showBridgeToast(next ? 'Desktop Mode enabled' : 'Desktop Mode disabled');
    }
  ));

  card.appendChild(createToggleRow(
    'ag-mb-toggle-extrakeys',
    'Extra-Keys Bar',
    'ESC, TAB, CTRL, arrows & symbols above keyboard',
    function (next) {
      setExtraKeysState(next);
      showBridgeToast(next ? 'Extra-Keys Bar shown' : 'Extra-Keys Bar hidden');
    }
  ));

  // Localhost Preview
  var previewRow = doc.createElement('div');
  previewRow.className = 'ag-mb-row-col';
  var previewLabel = doc.createElement('div');
  previewLabel.className = 'ag-mb-row-title';
  previewLabel.textContent = 'Localhost Preview';
  var previewSub = doc.createElement('div');
  previewSub.className = 'ag-mb-row-sub';
  previewSub.textContent = 'Open isolated preview for local dev servers';
  previewRow.appendChild(previewLabel);
  previewRow.appendChild(previewSub);

  var chips = doc.createElement('div');
  chips.className = 'ag-mb-chips';
  for (var i = 0; i < PREVIEW_PRESETS.length; i++) {
    var port = PREVIEW_PRESETS[i];
    var chip = doc.createElement('button');
    chip.type = 'button';
    chip.className = 'ag-mb-chip';
    chip.textContent = ':' + port;
    chip.setAttribute('data-port', String(port));
    chip.addEventListener('click', function (ev) {
      var p = ev.currentTarget.getAttribute('data-port');
      writeStorage(STORAGE_KEYS.previewPort, p);
      var res = triggerLocalPreview('http://localhost:' + p);
      if (res.ok) {
        if (onActionDone) onActionDone();
      } else {
        showBridgeToast('Preview bridge unavailable');
      }
    });
    chips.appendChild(chip);
  }
  previewRow.appendChild(chips);

  var customRow = doc.createElement('div');
  customRow.className = 'ag-mb-custom';
  var portInput = doc.createElement('input');
  portInput.id = 'ag-mb-preview-input';
  portInput.className = 'ag-mb-input';
  portInput.type = 'text';
  portInput.placeholder = 'port or localhost:3000';
  portInput.value = readStorage(STORAGE_KEYS.previewPort) || '3000';

  var openBtn = doc.createElement('button');
  openBtn.type = 'button';
  openBtn.className = 'ag-mb-btn';
  openBtn.textContent = 'Open';
  openBtn.addEventListener('click', function () {
    var raw = (portInput.value || '').trim();
    var res = triggerLocalPreview(raw);
    if (res.ok) {
      writeStorage(STORAGE_KEYS.previewPort, raw);
      if (onActionDone) onActionDone();
    } else if (res.reason === 'invalid_url') {
      showBridgeToast('Enter a localhost port or URL');
    } else {
      showBridgeToast('Preview bridge unavailable');
    }
  });
  customRow.appendChild(portInput);
  customRow.appendChild(openBtn);
  previewRow.appendChild(customRow);
  card.appendChild(previewRow);

  // PC Remote Control
  var remoteRow = doc.createElement('div');
  remoteRow.className = 'ag-mb-row';
  var remoteText = doc.createElement('div');
  remoteText.className = 'ag-mb-row-label';
  var remoteTitle = doc.createElement('span');
  remoteTitle.className = 'ag-mb-row-title';
  remoteTitle.textContent = 'PC Remote Control';
  var remoteSub = doc.createElement('span');
  remoteSub.className = 'ag-mb-row-sub';
  remoteSub.textContent = 'Open antigravity.google.com dashboard';
  remoteText.appendChild(remoteTitle);
  remoteText.appendChild(remoteSub);

  var remoteBtn = doc.createElement('button');
  remoteBtn.id = 'ag-mb-remote-portal';
  remoteBtn.type = 'button';
  remoteBtn.className = 'ag-mb-btn';
  remoteBtn.textContent = 'Open';
  remoteBtn.addEventListener('click', function () {
    var res = triggerRemotePortal();
    if (res.ok) {
      if (onActionDone) onActionDone();
    } else {
      showBridgeToast('Remote Control bridge unavailable');
    }
  });
  remoteRow.appendChild(remoteText);
  remoteRow.appendChild(remoteBtn);
  card.appendChild(remoteRow);

  // Voice Dictation & Voice Service
  var voiceRowWrap = doc.createElement('div');
  voiceRowWrap.className = 'ag-mb-row-col';
  var voiceTitle = doc.createElement('div');
  voiceTitle.className = 'ag-mb-row-title';
  voiceTitle.textContent = 'Android Voice';
  var voiceBtns = doc.createElement('div');
  voiceBtns.className = 'ag-mb-voice-row';

  var dictateBtn = doc.createElement('button');
  dictateBtn.id = 'ag-mb-voice-dictate';
  dictateBtn.type = 'button';
  dictateBtn.className = 'ag-mb-btn';
  dictateBtn.textContent = '\ud83c\udfa4 Voice Dictation';
  dictateBtn.addEventListener('click', function () {
    if (state.dictating) {
      stopNativeVoiceDictation();
    } else {
      var res = startNativeVoiceDictation();
      if (!res.ok) {
        showBridgeToast('Voice dictation unavailable');
      }
    }
  });

  var muteBtn = doc.createElement('button');
  muteBtn.id = 'ag-mb-voice-mute';
  muteBtn.type = 'button';
  muteBtn.className = 'ag-mb-btn-secondary';
  muteBtn.style.display = 'none';
  muteBtn.textContent = 'Mute';
  muteBtn.addEventListener('click', function () {
    setNativeVoiceMuted(!state.voiceServiceMuted);
  });

  var stopSvcBtn = doc.createElement('button');
  stopSvcBtn.id = 'ag-mb-voice-stop';
  stopSvcBtn.type = 'button';
  stopSvcBtn.className = 'ag-mb-btn-secondary';
  stopSvcBtn.style.display = 'none';
  stopSvcBtn.textContent = 'Stop';
  stopSvcBtn.addEventListener('click', function () {
    stopNativeVoiceService();
    onVoiceServiceStateChanged({ active: false, muted: false });
  });

  var interimEl = doc.createElement('div');
  interimEl.id = 'ag-mb-voice-interim';

  var voiceStatus = doc.createElement('div');
  voiceStatus.id = 'ag-mb-voice-status';
  voiceStatus.className = 'ag-mb-voice-status';
  voiceStatus.textContent = 'Voice service: Idle';

  voiceBtns.appendChild(dictateBtn);
  voiceBtns.appendChild(muteBtn);
  voiceBtns.appendChild(stopSvcBtn);
  voiceRowWrap.appendChild(voiceTitle);
  voiceRowWrap.appendChild(voiceBtns);
  voiceRowWrap.appendChild(interimEl);
  voiceRowWrap.appendChild(voiceStatus);
  card.appendChild(voiceRowWrap);

  // Clipboard Image Paste
  var clipRow = doc.createElement('div');
  clipRow.className = 'ag-mb-row';
  var clipText = doc.createElement('div');
  clipText.className = 'ag-mb-row-label';
  var clipTitle = doc.createElement('span');
  clipTitle.className = 'ag-mb-row-title';
  clipTitle.textContent = 'Clipboard Image';
  var clipSub = doc.createElement('span');
  clipSub.className = 'ag-mb-row-sub';
  clipSub.textContent = 'Attach screenshot from Android clipboard';
  clipText.appendChild(clipTitle);
  clipText.appendChild(clipSub);

  var pasteImgBtn = doc.createElement('button');
  pasteImgBtn.id = 'ag-mb-paste-image';
  pasteImgBtn.type = 'button';
  pasteImgBtn.className = 'ag-mb-btn-secondary';
  pasteImgBtn.textContent = 'Paste Image';
  pasteImgBtn.addEventListener('click', function () {
    if (onActionDone) onActionDone();
    pasteImageFromNativeClipboard();
  });
  clipRow.appendChild(clipText);
  clipRow.appendChild(pasteImgBtn);
  card.appendChild(clipRow);

  // Buffered Share Sheet action (only visible when buffered)
  var shareWrap = doc.createElement('div');
  shareWrap.className = 'ag-mb-row-col';
  shareWrap.style.paddingTop = '0';
  shareWrap.style.paddingBottom = '0';
  shareWrap.style.borderTop = '0';
  var pasteBtn = doc.createElement('button');
  pasteBtn.id = 'ag-mb-drawer-share';
  pasteBtn.type = 'button';
  pasteBtn.className = 'ag-mb-btn';
  pasteBtn.style.margin = '10px 0';
  pasteBtn.textContent = 'Paste Shared Content into Chat';
  pasteBtn.addEventListener('click', function () {
    if (onActionDone) onActionDone();
    applyBufferedShare();
  });
  shareWrap.appendChild(pasteBtn);
  card.appendChild(shareWrap);

  return card;
}

function findSettingsInjectionHost() {
  if (!doc) return null;
  var cards = [];
  try {
    cards = doc.querySelectorAll('.agy-card');
  } catch (_) {
    return null;
  }
  if (!cards || cards.length === 0) return null;

  var preferredHost = null;
  for (var i = 0; i < cards.length; i++) {
    var card = cards[i];
    if (!card || isOwnBridgeElement(card)) continue;
    var section = card.parentNode;
    var screen = section && section.parentNode ? section.parentNode : null;
    if (!screen || screen === doc.body || screen === doc.documentElement) continue;

    var txt = ((card.textContent || '') + ' ' + (section.textContent || '') + ' ' + (screen.textContent || '')).slice(0, 800);
    if (/\b(Appearance|Application)\b/.test(txt) && /\b(Theme|Contrast|Font|General|Remote Control|Updates|Desktop Mode)\b/.test(txt)) {
      preferredHost = screen;
      break;
    }
  }
  return preferredHost;
}

function tryInjectSettingsSection() {
  if (!doc || !doc.body) return false;
  var existing = doc.getElementById('ag-mb-settings-section');
  if (existing && existing.isConnected) {
    syncDrawerState();
    return true;
  }

  var host = findSettingsInjectionHost();
  if (!host) return false;

  // Close programmatic modal if open so element IDs stay unique.
  setDrawerOpen(false);
  var modal = doc.getElementById('ag-mb-drawer');
  if (modal && modal.parentNode) {
    modal.parentNode.removeChild(modal);
  }

  ensureBridgeStyles();
  var section = doc.createElement('div');
  section.id = 'ag-mb-settings-section';
  section.className = 'space-y-2';
  section.setAttribute('data-ag-mobile-bridge', 'true');

  var heading = doc.createElement('div');
  heading.className = 'ag-mb-sec-heading text-sm font-medium text-foreground';
  heading.textContent = 'Android Tools';

  section.appendChild(heading);
  section.appendChild(buildAndroidToolsCard(null));
  host.appendChild(section);
  syncDrawerState();
  return true;
}

function scheduleSettingsScan() {
  if (settingsScanTimer) return;
  settingsScanTimer = setTimeout(function () {
    settingsScanTimer = 0;
    tryInjectSettingsSection();
  }, 100);
}

function setDrawerOpen(open) {
  state.drawerOpen = !!open;
  if (!doc || !doc.body) return;

  var drawer = doc.getElementById('ag-mb-drawer');
  if (!state.drawerOpen) {
    if (drawer) drawer.classList.remove('ag-mb-open');
    return;
  }

  // If Settings section is currently mounted, remove it temporarily only if programmatic drawer opens;
  // usually openDrawer() is only called programmatically when Settings is closed.
  var existingSec = doc.getElementById('ag-mb-settings-section');
  if (existingSec && existingSec.parentNode) {
    existingSec.parentNode.removeChild(existingSec);
  }

  ensureBridgeStyles();
  if (!drawer) {
    drawer = doc.createElement('div');
    drawer.id = 'ag-mb-drawer';
    drawer.setAttribute('data-ag-mobile-bridge', 'true');
    drawer.setAttribute('role', 'dialog');
    drawer.setAttribute('aria-label', 'Android Tools');

    var header = doc.createElement('div');
    header.className = 'ag-mb-hdr';
    var title = doc.createElement('span');
    title.className = 'ag-mb-title';
    title.textContent = 'Android Tools';
    var closeBtn = doc.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'ag-mb-close';
    closeBtn.setAttribute('aria-label', 'Close Android Tools');
    closeBtn.textContent = '\u00d7';
    closeBtn.addEventListener('click', function () { setDrawerOpen(false); });
    header.appendChild(title);
    header.appendChild(closeBtn);
    drawer.appendChild(header);
    drawer.appendChild(buildAndroidToolsCard(function () { setDrawerOpen(false); }));
    doc.body.appendChild(drawer);
  }
  syncDrawerState();
  drawer.classList.add('ag-mb-open');
}

function mountQuickControls() {
  if (state.uiMounted || !doc || !doc.body) return;
  state.uiMounted = true;
  ensureBridgeStyles();

  // No floating pill on the chat/Swarm canvas!
  // Watch for Settings (Appearance / Application) to inject the native Android Tools card.
  tryInjectSettingsSection();
  if (typeof global.MutationObserver === 'function') {
    var root = doc.getElementById('root') || doc.body;
    if (root) {
      try {
        settingsObserver = new global.MutationObserver(scheduleSettingsScan);
        settingsObserver.observe(root, { childList: true, subtree: true });
      } catch (_) {}
    }
  }

  // Close programmatic modal when tapping outside.
  doc.addEventListener('pointerdown', function (ev) {
    if (!state.drawerOpen) return;
    var target = ev.target;
    if (target && isOwnBridgeElement(target)) return;
    setDrawerOpen(false);
  }, true);

  if (state.pendingShare && state.pendingShare.formatted) {
    showShareBanner();
  }
}

/**
 * patches/mobile_bridge/public-api.js - wire focus tracking, globals & UI mount.
 */
function installFocusTracking() {
  if (!doc) return;
  doc.addEventListener('focusin', function (ev) {
    var el = ev.target;
    if (!el || isOwnBridgeElement(el)) return;
    if (isTerminalElement(el)) {
      if ((el.tagName || '').toUpperCase() === 'TEXTAREA') {
        state.lastTerminal = el;
      } else if (el.querySelector) {
        var helper = el.querySelector('.xterm-helper-textarea, textarea');
        if (helper) state.lastTerminal = helper;
      }
      return;
    }
    if (isEditableElement(el)) {
      state.lastEditable = el;
    }
  }, true);
}

function onExtraKeysVisibilityChanged(visible) {
  writeStorage(STORAGE_KEYS.extraKeys, !!visible);
  syncDrawerState();
}

function installMobileBridge() {
  if (state.installed) return;
  state.installed = true;
  state.voiceServiceActive = isVoiceServiceActiveNative();
  state.voiceServiceMuted = isVoiceMutedNative();
  state.dictating = isVoiceDictatingNative();
  installVoiceWebSocketHook();
  installMicTrackObserver();
  installClipboardImageInterceptor();
  installFocusTracking();
  mountQuickControls();
  // Drain any payload stashed by native before mobile_bridge.js executed.
  if (global.__AG_PENDING_SHARED_PAYLOAD__) {
    var stashed = global.__AG_PENDING_SHARED_PAYLOAD__;
    global.__AG_PENDING_SHARED_PAYLOAD__ = null;
    deliverSharedPayload(stashed);
  }
}

// Install WebSocket/mic hooks immediately so early voice connections are observed.
installVoiceWebSocketHook();
installMicTrackObserver();

// Expose top-level native callback hooks immediately (before main.js / onUiReady).
global.__agDeliverSharedPayload = deliverSharedPayload;
global.__agExtraKey = dispatchExtraKey;
global.__agExtraKeysVisibilityChanged = onExtraKeysVisibilityChanged;
global.__agDeliverVoiceText = deliverVoiceText;
global.__agVoiceDictationStateChanged = onVoiceDictationStateChanged;
global.__agVoiceServiceStateChanged = onVoiceServiceStateChanged;
global.__agDeliverPastedImage = deliverPastedImage;

global.__agMobileBridge = {
  __installed: true,
  deliverSharedPayload: deliverSharedPayload,
  dispatchExtraKey: dispatchExtraKey,
  deliverVoiceText: deliverVoiceText,
  deliverPastedImage: deliverPastedImage,
  pasteImageFromClipboard: pasteImageFromNativeClipboard,
  onVoiceDictationStateChanged: onVoiceDictationStateChanged,
  onVoiceServiceStateChanged: onVoiceServiceStateChanged,
  applyBufferedShare: applyBufferedShare,
  openDrawer: function () { setDrawerOpen(true); },
  closeDrawer: function () { setDrawerOpen(false); },
  toggleDrawer: function () { setDrawerOpen(!state.drawerOpen); },
  injectSettingsControls: tryInjectSettingsSection,
  isDesktopMode: getDesktopModeState,
  setDesktopMode: setDesktopModeState,
  isExtraKeysVisible: getExtraKeysState,
  setExtraKeysVisible: setExtraKeysState,
  openLocalPreview: triggerLocalPreview,
  openRemotePortal: triggerRemotePortal,
  startVoiceDictation: startNativeVoiceDictation,
  stopVoiceDictation: stopNativeVoiceDictation,
  startVoiceService: startNativeVoiceService,
  stopVoiceService: stopNativeVoiceService,
  setVoiceMuted: setNativeVoiceMuted,
  isVoiceServiceActive: isVoiceServiceActiveNative,
  probe: function () {
    return {
      installed: state.installed,
      uiMounted: state.uiMounted,
      drawerOpen: state.drawerOpen,
      hasPendingShare: !!(state.pendingShare && state.pendingShare.formatted),
      shareDeliveries: state.shareDeliveries,
      extraKeyDispatches: state.extraKeyDispatches,
      voiceDeliveries: state.voiceDeliveries,
      imagePastes: state.imagePastes,
      dictating: state.dictating,
      voiceServiceActive: state.voiceServiceActive || isVoiceServiceActiveNative(),
      voiceServiceMuted: state.voiceServiceMuted,
      activeVoiceSockets: state.activeVoiceSockets.length,
      desktopMode: getDesktopModeState(),
      extraKeysVisible: getExtraKeysState(),
      hasComposer: !!findComposer(),
      hasAndroid: !!getAndroid()
    };
  }
};

if (doc && doc.readyState === 'loading') {
  doc.addEventListener('DOMContentLoaded', installMobileBridge);
} else {
  installMobileBridge();
}


})();
