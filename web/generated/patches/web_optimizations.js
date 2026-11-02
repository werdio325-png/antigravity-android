(function(){
/**
 * patches/web_optimizations/taps.js - kill the 300ms double-tap delay.
 */
'use strict';

function installTaps(doc) {
  try {
    var style = doc.createElement('style');
    style.setAttribute('data-agy', 'web-optimizations');
    style.textContent =
      'button,a,[role="button"],input,select,textarea{touch-action:manipulation;}';
    (doc.head || doc.documentElement).appendChild(style);
  } catch (e) {}
}

/**
 * patches/web_optimizations/viewport.js - keep --agy-vvh honest while the
 * soft keyboard opens.
 */
function installViewport(global, doc) {
  try {
    if (global.visualViewport) {
      global.visualViewport.addEventListener('resize', function () {
        var vv = global.visualViewport;
        if (vv && vv.height && doc.documentElement) {
          doc.documentElement.style.setProperty(
            '--agy-vvh', Math.round(vv.height) + 'px');
        }
      }, { passive: true });
    }
  } catch (e) {}
}

/**
 * patches/web_optimizations/gpu.js - GPU-accelerate scroll/animation surfaces
 * and isolate layout for lists.
 */

/**
 * patches/web_optimizations/bidi.js - plaintext unicode-bidi support.
 */
function installBidi(doc) {
  try {
    if (!doc.querySelector('style[data-agy="bidi-fix"]')) {
      var bidiStyle = doc.createElement('style');
      bidiStyle.setAttribute('data-agy', 'bidi-fix');
      bidiStyle.textContent =
        '[role="listitem"] p, [role="listitem"] li, .message-row p, .message-row li, .markdown p, .markdown li, article p, article li {' +
        '  unicode-bidi: plaintext !important;' +
        '  text-align: start !important;' +
        '}' +
        '[role="listitem"] pre, .message-row pre, .markdown pre, code {' +
        '  unicode-bidi: isolate !important;' +
        '  direction: ltr !important;' +
        '  text-align: left !important;' +
        '}';
      (doc.head || doc.documentElement).appendChild(bidiStyle);
    }
  } catch (e) {}
}

function installGpu(doc) {
  try {
    if (!doc.querySelector('style[data-agy="gpu-acceleration"]')) {
      var gpu = doc.createElement('style');
      gpu.setAttribute('data-agy', 'gpu-acceleration');
      // Promote ONLY the real scroll containers. Never inline <code>/<pre>:
      // per-element layers explode on Mali and saturate RenderThread.
      gpu.textContent =
        'main, [data-testid="chat-container"], .scrollable {' +
        ' transform: translateZ(0);' +
        ' -webkit-overflow-scrolling: touch; }' +
        '[role="listitem"], .message-row, .artifact-viewer {' +
        ' contain: layout style; }' +
        '*{ -webkit-tap-highlight-color: transparent; }';
      (doc.head || doc.documentElement).appendChild(gpu);
    }
  } catch (e) {}
}

/**
 * patches/web_optimizations/public-api.js - install and expose.
 */
var global = window;

if (global.__AG_WEB_OPTIMIZATIONS__) return;
global.__AG_WEB_OPTIMIZATIONS__ = true;

var doc = global.document;
if (!doc) return;

installTaps(doc);
installViewport(global, doc);
installGpu(doc);
installBidi(doc);


global.__agWebOptimizations = { installed: true };


})();
