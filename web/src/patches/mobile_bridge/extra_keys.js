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
