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
