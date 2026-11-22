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
