#!/usr/bin/env node
/**
 * web/tests/test_web_patches.mjs
 * Unit & behavioral verification for web/generated bundles:
 *  - patches/mobile_bridge.js (Share Sheet, Extra-Keys, Quick Controls)
 *  - bridge.core.js (https ?port= resolution, random CSRF fallback)
 *  - patches/touch_model_menu.js (MutationObserver submenu reaction)
 *  - patch_main.py (graceful degradation on upstream signature drift)
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const WEB_DIR = path.resolve(__dirname, '..');
const GEN_DIR = path.join(WEB_DIR, 'generated');

function createMockDom() {
  const listeners = new Map();
  let activeElement = null;
  const elementsById = new Map();
  const allElements = [];

  class MockEvent {
    constructor(type, init = {}) {
      this.type = type;
      this.bubbles = !!init.bubbles;
      this.cancelable = !!init.cancelable;
      this.composed = !!init.composed;
      this.defaultPrevented = false;
      this.target = null;
      this.currentTarget = null;
      Object.assign(this, init);
    }
    preventDefault() {
      if (this.cancelable) this.defaultPrevented = true;
    }
    stopPropagation() {}
    stopImmediatePropagation() {}
  }

  class MockKeyboardEvent extends MockEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.key = init.key || '';
      this.code = init.code || '';
      this.keyCode = init.keyCode || 0;
      this.which = init.which || 0;
      this.ctrlKey = !!init.ctrlKey;
      this.shiftKey = !!init.shiftKey;
      this.altKey = !!init.altKey;
      this.metaKey = !!init.metaKey;
    }
  }

  class MockInputEvent extends MockEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.inputType = init.inputType || '';
      this.data = init.data ?? null;
    }
  }

  class MockElement {
    constructor(tagName) {
      this.nodeType = 1;
      this.tagName = tagName.toUpperCase();
      this.attributes = new Map();
      this.children = [];
      this.parentNode = null;
      this.style = {};
      this._id = '';
      this._className = '';
      this._value = '';
      this._textContent = '';
      this._listeners = new Map();
      this.isConnected = false;
      this.disabled = false;
      this.readOnly = false;
      this.type = tagName.toUpperCase() === 'INPUT' ? 'text' : '';
      this.selectionStart = 0;
      this.selectionEnd = 0;
      this.dispatched = [];
      this.classList = {
        _set: new Set(),
        add: (...cls) => {
          cls.forEach((c) => this.classList._set.add(c));
          this._className = Array.from(this.classList._set).join(' ');
        },
        remove: (...cls) => {
          cls.forEach((c) => this.classList._set.delete(c));
          this._className = Array.from(this.classList._set).join(' ');
        },
        contains: (c) => this.classList._set.has(c)
      };
    }
    get id() { return this._id; }
    set id(v) {
      if (this._id) elementsById.delete(this._id);
      this._id = String(v);
      if (this._id) elementsById.set(this._id, this);
    }
    get className() { return this._className; }
    set className(v) {
      this._className = String(v);
      this.classList._set = new Set(this._className.split(/\s+/).filter(Boolean));
    }
    get value() { return this._value; }
    set value(v) {
      this._value = String(v);
      this.selectionStart = this._value.length;
      this.selectionEnd = this._value.length;
    }
    get textContent() {
      if (this._textContent) return this._textContent;
      return this.children.map((c) => c.textContent || '').join(' ');
    }
    set textContent(v) { this._textContent = String(v); }
    get isContentEditable() { return this.getAttribute('contenteditable') === 'true'; }
    setAttribute(k, v) { this.attributes.set(k, String(v)); }
    getAttribute(k) { return this.attributes.has(k) ? this.attributes.get(k) : null; }
    hasAttribute(k) { return this.attributes.has(k); }
    appendChild(child) {
      child.parentNode = this;
      this.children.push(child);
      markConnected(child, true);
      if (!allElements.includes(child)) allElements.push(child);
      return child;
    }
    removeChild(child) {
      const idx = this.children.indexOf(child);
      if (idx >= 0) this.children.splice(idx, 1);
      child.parentNode = null;
      markConnected(child, false);
      if (child._id) elementsById.delete(child._id);
      return child;
    }
    contains(other) {
      let cur = other;
      while (cur) {
        if (cur === this) return true;
        cur = cur.parentNode;
      }
      return false;
    }
    closest(selector) {
      const parts = selector.split(',').map((s) => s.trim());
      let cur = this;
      while (cur) {
        for (const p of parts) {
          if (matchesSimple(cur, p)) return cur;
        }
        cur = cur.parentNode;
      }
      return null;
    }
    querySelector(selector) {
      const list = this.querySelectorAll(selector);
      return list[0] || null;
    }
    querySelectorAll(selector) {
      const parts = selector.split(',').map((s) => s.trim());
      const out = [];
      const walk = (node) => {
        for (const ch of node.children) {
          if (parts.some((p) => matchesSimple(ch, p))) out.push(ch);
          walk(ch);
        }
      };
      walk(this);
      return out;
    }
    focus() {
      activeElement = this;
      const ev = new MockEvent('focusin', { bubbles: true });
      ev.target = this;
      document.dispatchEvent(ev);
    }
    setSelectionRange(s, e) {
      this.selectionStart = s;
      this.selectionEnd = e;
    }
    getBoundingClientRect() {
      return { top: 400, left: 10, right: 350, bottom: 520, width: 340, height: 120 };
    }
    addEventListener(type, fn) {
      if (!this._listeners.has(type)) this._listeners.set(type, []);
      this._listeners.get(type).push(fn);
    }
    removeEventListener(type, fn) {
      const arr = this._listeners.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx >= 0) arr.splice(idx, 1);
    }
    dispatchEvent(ev) {
      ev.target = ev.target || this;
      ev.currentTarget = this;
      this.dispatched.push(ev);
      const arr = this._listeners.get(ev.type) || [];
      for (const fn of [...arr]) fn(ev);
      return !ev.defaultPrevented;
    }
  }

  function markConnected(node, connected) {
    node.isConnected = connected;
    if (node._id) {
      if (connected) elementsById.set(node._id, node);
      else elementsById.delete(node._id);
    }
    for (const ch of node.children || []) markConnected(ch, connected);
  }

  function matchesSimple(el, sel) {
    if (!el || el.nodeType !== 1) return false;
    if (sel.startsWith('.')) return el.classList.contains(sel.slice(1));
    if (sel.startsWith('#')) return el.id === sel.slice(1);
    if (sel.startsWith('[')) {
      const m = sel.match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);
      if (!m) return false;
      return m[2] === undefined ? el.hasAttribute(m[1]) : el.getAttribute(m[1]) === m[2];
    }
    if (sel === 'textarea') return el.tagName === 'TEXTAREA';
    if (sel === 'input[type="text"]' || sel === 'input:not([type])') return el.tagName === 'INPUT';
    return el.tagName === sel.toUpperCase();
  }

  const head = new MockElement('head');
  const body = new MockElement('body');
  const documentElement = new MockElement('html');
  documentElement.isConnected = true;
  documentElement.appendChild(head);
  documentElement.appendChild(body);

  const document = {
    readyState: 'complete',
    head,
    body,
    documentElement,
    cookie: '',
    get activeElement() { return activeElement; },
    createElement(tag) { return new MockElement(tag); },
    createTextNode(txt) { return { nodeType: 3, textContent: txt }; },
    getElementById(id) { return elementsById.get(id) || null; },
    querySelector(sel) { return documentElement.querySelector(sel); },
    querySelectorAll(sel) { return documentElement.querySelectorAll(sel); },
    addEventListener(type, fn) {
      if (!listeners.has(type)) listeners.set(type, []);
      listeners.get(type).push(fn);
    },
    removeEventListener(type, fn) {
      const arr = listeners.get(type);
      if (!arr) return;
      const idx = arr.indexOf(fn);
      if (idx >= 0) arr.splice(idx, 1);
    },
    dispatchEvent(ev) {
      const arr = listeners.get(ev.type) || [];
      for (const fn of [...arr]) fn(ev);
      return !ev.defaultPrevented;
    }
  };

  const storage = new Map();
  const localStorage = {
    getItem: (k) => (storage.has(k) ? storage.get(k) : null),
    setItem: (k, v) => storage.set(k, String(v)),
    removeItem: (k) => storage.delete(k)
  };

  const windowObj = {
    document,
    localStorage,
    innerHeight: 800,
    innerWidth: 400,
    location: { search: '', href: 'https://127.0.0.1:45157/' },
    URL,
    URLSearchParams,
    Event: MockEvent,
    KeyboardEvent: MockKeyboardEvent,
    InputEvent: MockInputEvent,
    ClipboardEvent: MockEvent,
    DragEvent: MockEvent,
    File: globalThis.File,
    atob: globalThis.atob,
    Uint8Array: globalThis.Uint8Array,
    HTMLTextAreaElement: MockElement,
    HTMLInputElement: MockElement,
    navigator: { maxTouchPoints: 5 },
    console,
    setTimeout,
    clearTimeout,
    crypto: globalThis.crypto,
    getComputedStyle: () => ({ display: 'block', visibility: 'visible', opacity: '1' })
  };
  windowObj.window = windowObj;
  windowObj.self = windowObj;

  return { windowObj, document, body, MockElement, MockEvent, MockKeyboardEvent };
}

// ---------------------------------------------------------------------------
// 1. Test patches/mobile_bridge.js
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body } = createMockDom();
  const calls = {
    desktop: [],
    extraKeys: [],
    preview: [],
    remotePortal: [],
    voiceSvcStart: [],
    voiceSvcStop: [],
    voiceMute: [],
    dictateStart: [],
    dictateStop: [],
    clipboardReads: []
  };
  let desktopState = false;
  let extraKeysState = false;
  let voiceSvcActive = false;
  let voiceMuted = false;
  let voiceDictating = false;
  let mockClipboardImg = null;

  // Lightweight mock WebSocket so we can verify /voice/ws auto-service hook
  class MockWebSocket {
    constructor(url, protocols) {
      this.url = url;
      this.protocols = protocols;
      this.readyState = 0;
      this.closed = false;
      this._listeners = new Map();
    }
    addEventListener(type, fn) {
      if (!this._listeners.has(type)) this._listeners.set(type, []);
      this._listeners.get(type).push(fn);
    }
    emit(type) {
      if (type === 'open') this.readyState = 1;
      if (type === 'close') this.readyState = 3;
      for (const fn of (this._listeners.get(type) || [])) fn({ type, target: this });
    }
    close() {
      this.closed = true;
      this.emit('close');
    }
  }
  MockWebSocket.CONNECTING = 0;
  MockWebSocket.OPEN = 1;
  MockWebSocket.CLOSING = 2;
  MockWebSocket.CLOSED = 3;
  windowObj.WebSocket = MockWebSocket;

  windowObj.__AG_NATIVE_BRIDGE_TOKEN__ = 'tok-secret-123';
  windowObj.Android = {
    isDesktopMode: () => desktopState,
    setDesktopMode: (v) => { desktopState = !!v; calls.desktop.push(!!v); },
    isExtraKeysVisible: () => extraKeysState,
    setExtraKeysVisible: (v) => { extraKeysState = !!v; calls.extraKeys.push(!!v); },
    openLocalPreview: (tok, url) => { calls.preview.push({ tok, url }); return true; },
    openRemotePortal: (tok) => { calls.remotePortal.push(tok); return true; },
    startVoiceService: (tok) => { voiceSvcActive = true; calls.voiceSvcStart.push(tok); return true; },
    stopVoiceService: (tok) => { voiceSvcActive = false; voiceMuted = false; calls.voiceSvcStop.push(tok); return true; },
    setVoiceMuted: (tok, m) => { voiceMuted = !!m; calls.voiceMute.push({ tok, muted: !!m }); return true; },
    isVoiceServiceActive: () => voiceSvcActive,
    isVoiceMuted: () => voiceMuted,
    startVoiceDictation: (tok) => { voiceDictating = true; calls.dictateStart.push(tok); return true; },
    stopVoiceDictation: (tok) => { voiceDictating = false; calls.dictateStop.push(tok); return true; },
    isVoiceDictating: () => voiceDictating,
    hasClipboardImage: () => !!mockClipboardImg,
    readClipboardImage: (tok) => {
      calls.clipboardReads.push(tok);
      return JSON.stringify(mockClipboardImg || { ok: false, error: 'no_image' });
    }
  };

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'mobile_bridge.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  assert.equal(typeof windowObj.__agDeliverSharedPayload, 'function');
  assert.equal(typeof windowObj.__agExtraKey, 'function');
  assert.equal(document.getElementById('ag-mb-pill'), null, 'No floating Tools pill should clutter the chat/Swarm canvas');

  // Mount a native-like Settings -> Appearance screen and verify "Android Tools" card injects cleanly
  const settingsScreen = document.createElement('div');
  const appearanceSec = document.createElement('div');
  appearanceSec.className = 'space-y-2';
  const appearanceCard = document.createElement('div');
  appearanceCard.className = 'agy-card';
  appearanceCard.textContent = 'Appearance Theme Contrast Font';
  appearanceSec.appendChild(appearanceCard);
  settingsScreen.appendChild(appearanceSec);
  body.appendChild(settingsScreen);
  assert.equal(windowObj.__agMobileBridge.injectSettingsControls(), true, 'Android Tools should inject into Settings');
  assert.ok(document.getElementById('ag-mb-settings-section'), 'Android Tools section should exist inside Settings');

  // 1a. Share Sheet before composer exists -> buffers + shows banner
  const bufferedRes = windowObj.__agDeliverSharedPayload({
    subject: 'Bug report',
    text: 'Check this log',
    files: [{ name: 'crash.txt', path: '/data/user/0/com.agy/cache/shared/crash.txt', mime: 'text/plain' }]
  });
  assert.equal(bufferedRes.ok, true);
  assert.equal(bufferedRes.buffered, true);
  assert.ok(document.getElementById('ag-mb-share-banner'), 'Share banner should appear when composer missing');

  // Mount composer and tap banner action button -> inserts text and removes banner
  const composer = document.createElement('textarea');
  composer.setAttribute('placeholder', 'Ask anything...');
  body.appendChild(composer);
  const bannerBtn = document.getElementById('ag-mb-share-banner').children[0];
  bannerBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.ok(composer.value.includes('Bug report'));
  assert.ok(composer.value.includes('Check this log'));
  assert.ok(composer.value.includes('/data/user/0/com.agy/cache/shared/crash.txt'));
  assert.equal(document.getElementById('ag-mb-share-banner'), null);

  // 1b. Extra-Keys in chat composer: '/' and '@' insert cleanly
  composer.value = '';
  composer.focus();
  windowObj.__agExtraKey('/', false);
  windowObj.__agExtraKey('@', false);
  assert.equal(composer.value, '/@');
  assert.ok(composer.dispatched.some((e) => e.type === 'input'), 'input event must be dispatched for React');

  // 1c. Extra-Keys in xterm terminal
  const xtermWrap = document.createElement('div');
  xtermWrap.className = 'xterm';
  const xtermArea = document.createElement('textarea');
  xtermArea.className = 'xterm-helper-textarea';
  xtermWrap.appendChild(xtermArea);
  body.appendChild(xtermWrap);
  xtermArea.focus();

  const receivedInput = [];
  xtermArea.addEventListener('input', (e) => receivedInput.push(e.data));

  const ctrlC = windowObj.__agExtraKey('c', true);
  assert.equal(ctrlC.target, 'terminal');
  assert.equal(ctrlC.ansi, '\x03');
  const ctrlD = windowObj.__agExtraKey('d', true);
  assert.equal(ctrlD.ansi, '\x04');
  const ctrlZ = windowObj.__agExtraKey('z', true);
  assert.equal(ctrlZ.ansi, '\x1a');
  const ctrlL = windowObj.__agExtraKey('l', true);
  assert.equal(ctrlL.ansi, '\x0c');
  const esc = windowObj.__agExtraKey('ESC', false);
  assert.equal(esc.ansi, '\x1b');
  const tab = windowObj.__agExtraKey('TAB', false);
  assert.equal(tab.ansi, '\t');
  const up = windowObj.__agExtraKey('ArrowUp', false);
  assert.equal(up.ansi, '\x1b[A');
  assert.deepEqual(receivedInput, ['\x03', '\x04', '\x1a', '\x0c', '\x1b', '\t', '\x1b[A']);

  // Verify no duplicate input when xterm consumes keydown via preventDefault()
  receivedInput.length = 0;
  xtermArea.addEventListener('keydown', (e) => { if (e.key === 'ArrowDown') e.preventDefault(); });
  windowObj.__agExtraKey('ArrowDown', false);
  assert.equal(receivedInput.length, 0, 'Should not double-inject when xterm handles keydown');

  // 1d. Settings "Android Tools" toggles & Localhost Preview
  document.getElementById('ag-mb-toggle-desktop').dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  document.getElementById('ag-mb-toggle-extrakeys').dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.deepEqual(calls.desktop, [true]);
  assert.deepEqual(calls.extraKeys, [true]);

  const goodPrev = windowObj.__agMobileBridge.openLocalPreview('5173');
  assert.equal(goodPrev.ok, true);
  assert.deepEqual(calls.preview, [{ tok: 'tok-secret-123', url: 'http://localhost:5173' }]);

  const badPrev = windowObj.__agMobileBridge.openLocalPreview('https://evil.example.com/');
  assert.equal(badPrev.ok, false);
  assert.equal(calls.preview.length, 1);

  // 1e. Native hide callback syncs drawer switch state
  assert.equal(typeof windowObj.__agExtraKeysVisibilityChanged, 'function');
  extraKeysState = false;
  windowObj.__agExtraKeysVisibilityChanged(false);
  assert.equal(document.getElementById('ag-mb-toggle-extrakeys').getAttribute('aria-checked'), 'false');

  // 1f. PC Remote Control button invokes Android.openRemotePortal(token)
  const remoteBtn = document.getElementById('ag-mb-remote-portal');
  assert.ok(remoteBtn, 'PC Remote Control button should be rendered in Quick Controls drawer');
  remoteBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.deepEqual(calls.remotePortal, ['tok-secret-123']);

  // 1g. Native Voice Dictation button + __agDeliverVoiceText (interim + final)
  assert.equal(typeof windowObj.__agDeliverVoiceText, 'function');
  assert.equal(typeof windowObj.__agVoiceDictationStateChanged, 'function');
  const dictateBtn = document.getElementById('ag-mb-voice-dictate');
  assert.ok(dictateBtn, 'Voice Dictation button should be rendered in Quick Controls drawer');
  dictateBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.deepEqual(calls.dictateStart, ['tok-secret-123']);

  composer.value = 'Hello';
  composer.focus();
  const interimRes = windowObj.__agDeliverVoiceText('world', false);
  assert.equal(interimRes.interim, true);
  assert.equal(composer.value, 'Hello', 'Interim transcript should not spam composer');

  const finalRes = windowObj.__agDeliverVoiceText('from Android voice', true);
  assert.equal(finalRes.inserted, true);
  assert.equal(composer.value, 'Hello from Android voice');

  // 1h. Non-invasive WebSocket hook for /voice/ws starts/stops VoiceForegroundService
  const otherWs = new windowObj.WebSocket('wss://127.0.0.1:45157/connect-websocket');
  otherWs.emit('open');
  assert.equal(calls.voiceSvcStart.length, 0, 'Non-voice WebSocket must not trigger VoiceForegroundService');

  const voiceWs = new windowObj.WebSocket('wss://127.0.0.1:45157/voice/ws', ['jetski-voice-proto']);
  voiceWs.emit('open');
  assert.deepEqual(calls.voiceSvcStart, ['tok-secret-123']);
  assert.ok(document.getElementById('ag-mb-voice-status').textContent.includes('Active'));

  // Notification shade Mute & Stop actions update state and close /voice/ws cleanly
  voiceMuted = true;
  windowObj.__agVoiceServiceStateChanged({ active: true, muted: true });
  assert.ok(document.getElementById('ag-mb-voice-status').textContent.includes('Muted'));
  voiceSvcActive = false;
  voiceMuted = false;
  windowObj.__agVoiceServiceStateChanged({ active: false, muted: false });
  assert.equal(voiceWs.closed, true, 'Stop Voice from notification should close active /voice/ws');
  assert.ok(document.getElementById('ag-mb-voice-status').textContent.includes('Idle'));

  // 1i. Clipboard image paste (__agDeliverPastedImage, paste interceptor, no-double-paste, fallbacks)
  assert.equal(typeof windowObj.__agDeliverPastedImage, 'function');
  const tinyPngB64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';
  const pastedFiles = [];
  const droppedFiles = [];
  let acceptPaste = true;
  composer.addEventListener('paste', (ev) => {
    if (!acceptPaste) return;
    const items = Array.from(ev.clipboardData?.items || []);
    const fileItem = items.find((it) => it.kind === 'file');
    if (fileItem) {
      ev.preventDefault();
      pastedFiles.push(fileItem.getAsFile());
    }
  });
  composer.addEventListener('drop', (ev) => {
    const files = Array.from(ev.dataTransfer?.files || []);
    if (files.length > 0) {
      ev.preventDefault();
      droppedFiles.push(files[0]);
    }
  });

  // Direct delivery (IME commitContent / OnReceiveContentListener)
  composer.focus();
  const imgRes = windowObj.__agDeliverPastedImage(tinyPngB64, 'image/png', 'screenshot.png');
  assert.equal(imgRes.ok, true);
  assert.equal(imgRes.method, 'paste');
  assert.equal(pastedFiles.length, 1);
  assert.equal(pastedFiles[0].name, 'screenshot.png');
  assert.equal(pastedFiles[0].type, 'image/png');
  assert.equal(droppedFiles.length, 0, 'Must not double-attach via drop when paste was consumed');

  // Empty WebView paste event falls back to Android.readClipboardImage(token) without re-entry loop
  mockClipboardImg = { ok: true, base64: tinyPngB64, mime: 'image/jpeg', name: 'clip.jpg' };
  const emptyPaste = new windowObj.Event('paste', { bubbles: true, cancelable: true });
  emptyPaste.clipboardData = { files: [], items: [], getData: () => '' };
  emptyPaste.target = composer;
  document.dispatchEvent(emptyPaste);
  assert.equal(emptyPaste.defaultPrevented, true, 'Empty paste should be intercepted when Android clipboard has image');
  assert.deepEqual(calls.clipboardReads, ['tok-secret-123']);
  assert.equal(pastedFiles.length, 2);
  assert.equal(pastedFiles[1].name, 'clip.jpg');

  // Native WebView paste that already carries an image must NOT trigger duplicate Android read
  const nativeImgPaste = new windowObj.Event('paste', { bubbles: true, cancelable: true });
  nativeImgPaste.clipboardData = { files: [{ type: 'image/png' }], items: [], getData: () => '' };
  nativeImgPaste.target = composer;
  document.dispatchEvent(nativeImgPaste);
  assert.equal(calls.clipboardReads.length, 1, 'Should not read Android clipboard if WebView already supplied image');

  // When composer ignores paste, fallback attaches once via drop
  acceptPaste = false;
  const dropRes = windowObj.__agDeliverPastedImage(tinyPngB64, 'image/webp', 'fallback.webp');
  assert.equal(dropRes.ok, true);
  assert.equal(dropRes.method, 'drop');
  assert.equal(droppedFiles.length, 1);
  assert.equal(droppedFiles[0].name, 'fallback.webp');

  // Quick Controls "Paste Image" button reads native clipboard and attaches
  acceptPaste = true;
  const pasteImgBtn = document.getElementById('ag-mb-paste-image');
  assert.ok(pasteImgBtn, 'Paste Image button should be rendered in Quick Controls');
  pasteImgBtn.dispatchEvent(new windowObj.Event('click', { bubbles: true, cancelable: true }));
  assert.equal(calls.clipboardReads.length, 2);
  assert.equal(pastedFiles.length, 3);

  // 1j. Confirm programmatic drawer fallback also works cleanly when Settings is not mounted
  settingsScreen.parentNode.removeChild(settingsScreen);
  assert.equal(document.getElementById('ag-mb-settings-section'), null);
  windowObj.__agMobileBridge.openDrawer();
  assert.ok(document.getElementById('ag-mb-drawer').classList.contains('ag-mb-open'));
  windowObj.__agMobileBridge.closeDrawer();
  assert.equal(document.getElementById('ag-mb-drawer').classList.contains('ag-mb-open'), false);
  assert.equal(document.getElementById('ag-mb-pill'), null, 'Still zero floating pill after programmatic drawer');

  console.log('test mobile_bridge.js: OK');
}

// ---------------------------------------------------------------------------
// 2. Test bridge.core.js (https ?port=, random CSRF fallback, /voice/health retry)
// ---------------------------------------------------------------------------
{
  const { windowObj } = createMockDom();
  windowObj.location = { search: '?port=45123', href: 'https://127.0.0.1:45123/?port=45123' };
  let vhAttempts = 0;
  windowObj.fetch = async (url) => {
    if (String(url).includes('/voice/health')) {
      vhAttempts++;
      if (vhAttempts === 1) throw new Error('ECONNREFUSED');
      if (vhAttempts === 2) return { status: 503, ok: false };
      return { status: 200, ok: true };
    }
    return { status: 200, ok: true };
  };
  const code = fs.readFileSync(path.join(GEN_DIR, 'bridge.core.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  assert.equal(windowObj.__AG_ENDPOINTS__.core_http, 'https://127.0.0.1:45123');
  assert.equal(windowObj.__agBridgeCore.baseOrigin(), 'https://127.0.0.1:45123');

  const csrf1 = windowObj.__APP_CONFIG__.csrfToken;
  assert.ok(csrf1 && csrf1.length >= 16, 'CSRF fallback should be non-empty random token');
  assert.notEqual(csrf1, 'agy-standalone-token', 'Hardcoded CSRF fallback must be removed');
  assert.equal(windowObj.__agBridgeCore.syncCsrf(), csrf1, 'Fallback CSRF should remain stable within session');

  const vhResp = await windowObj.fetch('/voice/health');
  assert.equal(vhResp.status, 200, '/voice/health should retry transient cold-boot failures');
  assert.equal(vhAttempts, 3);

  console.log('test bridge.core.js: OK');
}

// ---------------------------------------------------------------------------
// 3. Test touch_model_menu.js (MutationObserver reaction & cleanup)
// ---------------------------------------------------------------------------
{
  const { windowObj, document, body } = createMockDom();
  const observers = [];
  windowObj.MutationObserver = class MockMutationObserver {
    constructor(cb) {
      this.cb = cb;
      this.disconnected = false;
      observers.push(this);
    }
    observe() {}
    disconnect() { this.disconnected = true; }
  };
  windowObj.PointerEvent = windowObj.Event;
  windowObj.MouseEvent = windowObj.Event;

  const row = document.createElement('div');
  row.setAttribute('role', 'menuitem');
  const group = document.createElement('div');
  group.setAttribute('data-testid', 'model-selector-effort-group');
  row.appendChild(group);
  body.appendChild(row);

  const code = fs.readFileSync(path.join(GEN_DIR, 'patches', 'touch_model_menu.js'), 'utf8');
  vm.runInNewContext(code, windowObj);

  // Tap row -> starts openSubmenu observer
  row.dispatchEvent(new windowObj.Event('pointerdown', { bubbles: true, cancelable: true, button: 0 }));
  assert.ok(observers.length >= 2, 'openSubmenu should attach a MutationObserver');
  const openObs = observers[observers.length - 1];

  // Base UI highlights the row immediately -> observer triggers ArrowRight immediately
  row.setAttribute('data-highlighted', '');
  openObs.cb([]);
  assert.ok(row.dispatched.some((e) => e.type === 'keydown' && e.key === 'ArrowRight'), 'MutationObserver should send ArrowRight immediately on highlight');

  // Base UI mounts effort options -> observer cleans up and disconnects
  const opt = document.createElement('div');
  opt.setAttribute('data-testid', 'model-selector-effort-option');
  body.appendChild(opt);
  openObs.cb([]);
  assert.equal(openObs.disconnected, true, 'MutationObserver should disconnect once options open');

  console.log('test touch_model_menu.js: OK');
}

// ---------------------------------------------------------------------------
// 4. Test patch_main.py graceful fallback on changed upstream signatures
// ---------------------------------------------------------------------------
{
  const tmpFile = path.join(os.tmpdir(), `agy-drift-main-${Date.now()}.js`);
  const dummySrc = 'const completelyChangedUpstream = () => "hello";\n';
  fs.writeFileSync(tmpFile, dummySrc, 'utf8');
  try {
    const py = process.platform === 'win32' ? 'python' : 'python3';
    execFileSync(py, [path.join(WEB_DIR, 'patch_main.py'), tmpFile], { stdio: 'pipe' });
    assert.equal(fs.readFileSync(tmpFile, 'utf8'), dummySrc, 'Unmatched main.js must remain untouched');
  } finally {
    try { fs.unlinkSync(tmpFile); } catch (_) {}
  }
  console.log('test patch_main.py graceful fallback: OK');
}
