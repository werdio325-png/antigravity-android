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
