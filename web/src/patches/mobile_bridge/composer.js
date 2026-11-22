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
