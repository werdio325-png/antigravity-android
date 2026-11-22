/**
 * patches/mobile_bridge/controls.js - resilient Quick Controls pill & drawer.
 *
 * Provides one-tap access to Desktop Mode, Extra-Keys Bar, and Localhost
 * Preview independent of main.js internals, so upstream UI changes never lock
 * the user out of mobile controls.
 */
function ensureBridgeStyles() {
  if (!doc || doc.getElementById('ag-mb-styles')) return;
  var style = doc.createElement('style');
  style.id = 'ag-mb-styles';
  style.textContent = [
    '#ag-mb-pill {',
    '  position: fixed; right: 10px; bottom: 76px; z-index: 2147483000;',
    '  display: inline-flex; align-items: center; gap: 5px;',
    '  height: 36px; padding: 0 11px; border-radius: 999px;',
    '  border: 1px solid rgba(148,163,184,0.32);',
    '  background: rgba(15,23,42,0.78); color: #e2e8f0;',
    '  backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px);',
    '  font: 600 12px/1 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;',
    '  box-shadow: 0 4px 14px rgba(0,0,0,0.28); cursor: pointer;',
    '  opacity: 0.72; transition: opacity 0.16s ease, transform 0.16s ease;',
    '  touch-action: none; user-select: none; -webkit-user-select: none;',
    '}',
    '#ag-mb-pill:active, #ag-mb-pill[aria-expanded="true"] { opacity: 1; }',
    'html.light #ag-mb-pill {',
    '  background: rgba(255,255,255,0.88); color: #0f172a;',
    '  border-color: rgba(15,23,42,0.16); box-shadow: 0 4px 14px rgba(15,23,42,0.14);',
    '}',
    '#ag-mb-drawer {',
    '  position: fixed; right: 10px; bottom: 120px; z-index: 2147483001;',
    '  width: min(296px, calc(100vw - 20px)); border-radius: 14px; padding: 12px;',
    '  border: 1px solid rgba(148,163,184,0.28);',
    '  background: rgba(15,23,42,0.95); color: #f1f5f9;',
    '  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);',
    '  box-shadow: 0 14px 36px rgba(0,0,0,0.42);',
    '  font: 13px/1.35 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;',
    '  display: none;',
    '}',
    '#ag-mb-drawer.ag-mb-open { display: block; }',
    'html.light #ag-mb-drawer {',
    '  background: rgba(255,255,255,0.97); color: #0f172a;',
    '  border-color: rgba(15,23,42,0.14); box-shadow: 0 14px 36px rgba(15,23,42,0.18);',
    '}',
    '.ag-mb-hdr { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }',
    '.ag-mb-title { font-size: 13px; font-weight: 700; letter-spacing: 0.01em; }',
    '.ag-mb-close {',
    '  width: 28px; height: 28px; border: 0; border-radius: 8px; cursor: pointer;',
    '  background: transparent; color: inherit; font-size: 18px; line-height: 1;',
    '}',
    '.ag-mb-row {',
    '  display: flex; align-items: center; justify-content: space-between; gap: 10px;',
    '  padding: 8px 0; border-top: 1px solid rgba(148,163,184,0.16);',
    '}',
    '.ag-mb-row-label { display: flex; flex-direction: column; gap: 2px; }',
    '.ag-mb-row-title { font-weight: 600; font-size: 13px; }',
    '.ag-mb-row-sub { font-size: 11px; opacity: 0.72; }',
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
    '.ag-mb-section { padding-top: 9px; margin-top: 2px; border-top: 1px solid rgba(148,163,184,0.16); }',
    '.ag-mb-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 7px; }',
    '.ag-mb-chip {',
    '  height: 30px; padding: 0 10px; border-radius: 8px; cursor: pointer;',
    '  border: 1px solid rgba(148,163,184,0.28); background: rgba(56,189,248,0.12);',
    '  color: inherit; font: 600 12px/1 monospace;',
    '}',
    '.ag-mb-custom { display: flex; gap: 6px; margin-top: 8px; }',
    '.ag-mb-input {',
    '  flex: 1; min-width: 0; height: 32px; padding: 0 9px; border-radius: 8px;',
    '  border: 1px solid rgba(148,163,184,0.3); background: rgba(148,163,184,0.1);',
    '  color: inherit; font: 12px/1 monospace;',
    '}',
    '.ag-mb-btn {',
    '  height: 32px; padding: 0 11px; border-radius: 8px; border: 0; cursor: pointer;',
    '  background: #38bdf8; color: #082f49; font-weight: 700; font-size: 12px;',
    '}',
    'html.light .ag-mb-btn { background: #0284c7; color: #ffffff; }',
    '.ag-mb-btn-secondary {',
    '  height: 32px; padding: 0 10px; border-radius: 8px; cursor: pointer;',
    '  border: 1px solid rgba(148,163,184,0.32); background: rgba(148,163,184,0.14);',
    '  color: inherit; font-weight: 600; font-size: 12px;',
    '}',
    '.ag-mb-btn[data-active="true"] { background: #ef4444; color: #ffffff; }',
    '.ag-mb-voice-row { display: flex; gap: 6px; margin-top: 8px; }',
    '.ag-mb-voice-status { font-size: 11px; opacity: 0.78; margin-top: 6px; }',
    '#ag-mb-voice-interim { font-size: 11px; opacity: 0.85; margin-top: 4px; font-style: italic; display: none; }',
    '#ag-mb-voice-interim.ag-mb-visible { display: block; }',
    '#ag-mb-drawer-share { margin-top: 8px; width: 100%; display: none; }',
    '#ag-mb-drawer-share.ag-mb-visible { display: block; }',
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

function setDrawerOpen(open) {
  state.drawerOpen = !!open;
  if (!doc) return;
  var drawer = doc.getElementById('ag-mb-drawer');
  var pill = doc.getElementById('ag-mb-pill');
  if (drawer) {
    if (state.drawerOpen) {
      syncDrawerState();
      drawer.classList.add('ag-mb-open');
    } else {
      drawer.classList.remove('ag-mb-open');
    }
  }
  if (pill) {
    pill.setAttribute('aria-expanded', state.drawerOpen ? 'true' : 'false');
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

function attachPillDrag(pill) {
  var savedTop = parseFloat(readStorage(STORAGE_KEYS.pillTop));
  if (!isNaN(savedTop) && savedTop >= 24 && savedTop <= (global.innerHeight || 900) - 60) {
    pill.style.top = savedTop + 'px';
    pill.style.bottom = 'auto';
  }

  var dragging = false;
  var moved = false;
  var startY = 0;
  var startTop = 0;

  pill.addEventListener('pointerdown', function (ev) {
    if (ev.button && ev.button !== 0) return;
    dragging = true;
    moved = false;
    startY = ev.clientY;
    var rect = pill.getBoundingClientRect();
    startTop = rect.top;
    try { pill.setPointerCapture(ev.pointerId); } catch (_) {}
  });

  pill.addEventListener('pointermove', function (ev) {
    if (!dragging) return;
    var dy = ev.clientY - startY;
    if (!moved && Math.abs(dy) < 6) return;
    moved = true;
    var maxTop = Math.max(48, (global.innerHeight || 640) - 52);
    var nextTop = Math.max(24, Math.min(maxTop, startTop + dy));
    pill.style.top = nextTop + 'px';
    pill.style.bottom = 'auto';
  });

  function endDrag(ev) {
    if (!dragging) return;
    dragging = false;
    try { pill.releasePointerCapture(ev.pointerId); } catch (_) {}
    if (moved) {
      var rect = pill.getBoundingClientRect();
      writeStorage(STORAGE_KEYS.pillTop, Math.round(rect.top));
      pill.__agJustDragged = true;
      setTimeout(function () { pill.__agJustDragged = false; }, 120);
    }
  }

  pill.addEventListener('pointerup', endDrag);
  pill.addEventListener('pointercancel', endDrag);
}

function mountQuickControls() {
  if (state.uiMounted || !doc || !doc.body) return;
  state.uiMounted = true;
  ensureBridgeStyles();

  var pill = doc.createElement('button');
  pill.id = 'ag-mb-pill';
  pill.type = 'button';
  pill.setAttribute('data-ag-mobile-bridge', 'true');
  pill.setAttribute('aria-label', 'Mobile quick controls');
  pill.setAttribute('aria-expanded', 'false');
  pill.textContent = '\u2699 Tools';
  pill.addEventListener('click', function (ev) {
    ev.preventDefault();
    ev.stopPropagation();
    if (pill.__agJustDragged) return;
    setDrawerOpen(!state.drawerOpen);
  });
  attachPillDrag(pill);

  var drawer = doc.createElement('div');
  drawer.id = 'ag-mb-drawer';
  drawer.setAttribute('data-ag-mobile-bridge', 'true');
  drawer.setAttribute('role', 'dialog');
  drawer.setAttribute('aria-label', 'Mobile Quick Controls');

  var header = doc.createElement('div');
  header.className = 'ag-mb-hdr';
  var title = doc.createElement('span');
  title.className = 'ag-mb-title';
  title.textContent = 'Mobile Controls';
  var closeBtn = doc.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'ag-mb-close';
  closeBtn.setAttribute('aria-label', 'Close mobile controls');
  closeBtn.textContent = '\u00d7';
  closeBtn.addEventListener('click', function () { setDrawerOpen(false); });
  header.appendChild(title);
  header.appendChild(closeBtn);
  drawer.appendChild(header);

  drawer.appendChild(createToggleRow(
    'ag-mb-toggle-desktop',
    'Desktop Mode',
    'Desktop viewport and user agent',
    function (next) {
      setDesktopModeState(next);
      showBridgeToast(next ? 'Desktop Mode enabled' : 'Desktop Mode disabled');
    }
  ));

  drawer.appendChild(createToggleRow(
    'ag-mb-toggle-extrakeys',
    'Extra-Keys Bar',
    'ESC, TAB, CTRL, arrows & symbols',
    function (next) {
      setExtraKeysState(next);
      showBridgeToast(next ? 'Extra-Keys Bar shown' : 'Extra-Keys Bar hidden');
    }
  ));

  var previewSec = doc.createElement('div');
  previewSec.className = 'ag-mb-section';
  var previewLabel = doc.createElement('div');
  previewLabel.className = 'ag-mb-row-title';
  previewLabel.textContent = 'Localhost Preview';
  var previewSub = doc.createElement('div');
  previewSub.className = 'ag-mb-row-sub';
  previewSub.textContent = 'Open isolated preview for local dev servers';
  previewSec.appendChild(previewLabel);
  previewSec.appendChild(previewSub);

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
        setDrawerOpen(false);
      } else {
        showBridgeToast('Preview bridge unavailable');
      }
    });
    chips.appendChild(chip);
  }
  previewSec.appendChild(chips);

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
      setDrawerOpen(false);
    } else if (res.reason === 'invalid_url') {
      showBridgeToast('Enter a localhost port or URL');
    } else {
      showBridgeToast('Preview bridge unavailable');
    }
  });
  customRow.appendChild(portInput);
  customRow.appendChild(openBtn);
  previewSec.appendChild(customRow);
  drawer.appendChild(previewSec);

  var remoteSec = doc.createElement('div');
  remoteSec.className = 'ag-mb-section';
  var remoteLabel = doc.createElement('div');
  remoteLabel.className = 'ag-mb-row-title';
  remoteLabel.textContent = 'PC Remote Control';
  var remoteSub = doc.createElement('div');
  remoteSub.className = 'ag-mb-row-sub';
  remoteSub.textContent = 'Control desktop & cloud sessions (antigravity.google.com)';
  var remoteBtn = doc.createElement('button');
  remoteBtn.id = 'ag-mb-remote-portal';
  remoteBtn.type = 'button';
  remoteBtn.className = 'ag-mb-btn';
  remoteBtn.style.marginTop = '8px';
  remoteBtn.style.width = '100%';
  remoteBtn.textContent = 'Open PC Remote Control';
  remoteBtn.addEventListener('click', function () {
    var res = triggerRemotePortal();
    if (res.ok) {
      setDrawerOpen(false);
    } else {
      showBridgeToast('Remote Control bridge unavailable');
    }
  });
  remoteSec.appendChild(remoteLabel);
  remoteSec.appendChild(remoteSub);
  remoteSec.appendChild(remoteBtn);
  drawer.appendChild(remoteSec);

  var voiceSec = doc.createElement('div');
  voiceSec.className = 'ag-mb-section';
  var voiceLabel = doc.createElement('div');
  voiceLabel.className = 'ag-mb-row-title';
  voiceLabel.textContent = 'Voice';
  var voiceRow = doc.createElement('div');
  voiceRow.className = 'ag-mb-voice-row';

  var dictateBtn = doc.createElement('button');
  dictateBtn.id = 'ag-mb-voice-dictate';
  dictateBtn.type = 'button';
  dictateBtn.className = 'ag-mb-btn';
  dictateBtn.style.flex = '1';
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

  voiceRow.appendChild(dictateBtn);
  voiceRow.appendChild(muteBtn);
  voiceRow.appendChild(stopSvcBtn);
  voiceSec.appendChild(voiceLabel);
  voiceSec.appendChild(voiceRow);
  voiceSec.appendChild(interimEl);
  voiceSec.appendChild(voiceStatus);
  drawer.appendChild(voiceSec);

  var clipSec = doc.createElement('div');
  clipSec.className = 'ag-mb-section';
  var pasteImgBtn = doc.createElement('button');
  pasteImgBtn.id = 'ag-mb-paste-image';
  pasteImgBtn.type = 'button';
  pasteImgBtn.className = 'ag-mb-btn-secondary';
  pasteImgBtn.style.width = '100%';
  pasteImgBtn.textContent = '\ud83d\uddbc Paste Image from Clipboard';
  pasteImgBtn.addEventListener('click', function () {
    setDrawerOpen(false);
    pasteImageFromNativeClipboard();
  });
  clipSec.appendChild(pasteImgBtn);
  drawer.appendChild(clipSec);

  var pasteBtn = doc.createElement('button');
  pasteBtn.id = 'ag-mb-drawer-share';
  pasteBtn.type = 'button';
  pasteBtn.className = 'ag-mb-btn';
  pasteBtn.textContent = 'Paste Shared Content into Chat';
  pasteBtn.addEventListener('click', function () {
    setDrawerOpen(false);
    applyBufferedShare();
  });
  drawer.appendChild(pasteBtn);

  doc.body.appendChild(pill);
  doc.body.appendChild(drawer);

  // Close drawer when tapping outside.
  doc.addEventListener('pointerdown', function (ev) {
    if (!state.drawerOpen) return;
    var target = ev.target;
    if (target && isOwnBridgeElement(target)) return;
    setDrawerOpen(false);
  }, true);

  if (state.pendingShare && state.pendingShare.formatted) {
    showShareBanner();
  }
  syncDrawerState();
}
