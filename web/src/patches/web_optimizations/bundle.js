/**
 * patches/web_optimizations.js - Targeted Android WebView optimizations (v3.2.0):
 * 1. Base UI fast-path (`window.BASE_UI_ANIMATIONS_DISABLED = true`).
 * 2. Passive `scroll` listeners + block desktop overlay-scrollbar listeners (`X4a`, `P$a`)
 *    without breaking touchmove/wheel `preventDefault()` on interactive controls.
 * 3. Targeted focus guard: suppress accidental soft-keyboard popup on initial boot or
 *    when closing menus/sheets/sidebar/model-selector, while preserving Quote/Edit/manual focus.
 * 4. Surgical lazy 48kHz `AudioContext`/`AudioWorklet` gate (`Azc`), leaving chime contexts (`r6b`/`s6b`) un-suspended.
 * 5. Targeted CSS optimizations for chat viewport, bottom sheets, dropdown menus, and GPUs.
 */
'use strict';

var global = window;
if (global.__AG_WEB_OPTIMIZATIONS__) return;
global.__AG_WEB_OPTIMIZATIONS__ = true;

// Built-in Base UI fast path (`zx` in main.js): skips MutationObserver on
// data-starting-style, skips getAnimations() layout flushes, and skips flushSync().
global.BASE_UI_ANIMATIONS_DISABLED = true;

var doc = global.document;
if (!doc) return;

var SCROLLBAR_DOC_EVENTS = {
  pointerdown: true,
  pointerup: true,
  pointercancel: true,
  contextmenu: true,
  dragstart: true,
  keydown: true,
  wheel: true,
  touchstart: true,
  touchmove: true,
  scroll: true
};

/**
 * 1. Event listener optimization:
 *    - Defaults unspecified `scroll` listeners to { passive: true } (never cancelable).
 *      (Leaves touchstart/touchmove/wheel untouched so color pickers & custom drags keep preventDefault().)
 *    - Blocks `main.js`'s desktop overlay-scrollbar document capture listeners (`X4a`, `P$a`).
 */
function installEventOptimizations(win, documentRef) {
  try {
    var proto = win.EventTarget && win.EventTarget.prototype;
    if (!proto || proto.__agPassivePatched) return;
    var origAdd = proto.addEventListener;
    if (typeof origAdd !== 'function') return;

    proto.addEventListener = function (type, listener, options) {
      if (listener && typeof listener === 'function') {
        if (this === documentRef && SCROLLBAR_DOC_EVENTS[type]) {
          try {
            var fnStr = Function.prototype.toString.call(listener);
            if (type === 'scroll' && fnStr.indexOf('.md-table-scroll') !== -1) {
              return;
            }
            if (fnStr.indexOf('case "dragstart":') !== -1 && fnStr.indexOf('deltaX') !== -1) {
              return;
            }
          } catch (_) {}
        }
        if (type === 'wheel' && this && this.getAttribute &&
            this.getAttribute('data-testid') === 'overlay-scrollbar') {
          return;
        }
      }

      if (type === 'scroll') {
        if (options === undefined || typeof options === 'boolean') {
          options = { capture: !!options, passive: true };
        } else if (typeof options === 'object' && options !== null && options.passive === undefined) {
          options = {
            capture: !!options.capture,
            once: !!options.once,
            passive: true,
            signal: options.signal
          };
        }
      }
      return origAdd.call(this, type, listener, options);
    };
    proto.__agPassivePatched = true;
  } catch (e) {}
}

/**
 * 2. Targeted Soft Keyboard Focus Guard:
 *    Prevents closing a menu, model selector, bottom sheet, or sidebar (`restoreFocus` / `finalFocus`),
 *    or initial startup auto-focus, from unexpectedly popping up the Android keyboard on the chat composer,
 *    while preserving intentional focuses (tapping the composer, Quote button, inline Edit, etc.).
 */
function installFocusGuard(win, documentRef) {
  try {
    var htmlProto = win.HTMLElement && win.HTMLElement.prototype;
    if (!htmlProto || htmlProto.__agFocusGuarded) return;
    var origFocus = htmlProto.focus;
    if (typeof origFocus !== 'function') return;

    var hasAnyUserTap = false;
    var lastTapTarget = null;
    var lastPopupTapTime = 0;
    var lastPopupElement = null;

    var POPUP_SELECTOR =
      '[data-testid="model-selector-trigger"],' +
      '[data-testid="model-selector-panel"],' +
      '[data-testid="model-selector-item"],' +
      '[data-testid="model-selector-effort-group"],' +
      '[data-testid="model-selector-effort-option"],' +
      '[data-testid="bottom-sheet"],' +
      '[data-testid="bottom-sheet-backdrop"],' +
      '[data-testid="sidebar-toggle"],' +
      '[data-testid="sidebar-popover-backdrop"],' +
      '[role="navigation"], [role="menu"], [role="menuitem"],' +
      '[role="menuitemradio"], [role="menuitemcheckbox"],' +
      '[role="listbox"], [role="option"], [role="dialog"]';

    var INPUT_SELECTOR = '[data-lexical-editor="true"], [contenteditable="true"], textarea, input';

    var recordTap = function (ev) {
      if (ev && ev.target && !ev.__agTouchSynthetic) {
        hasAnyUserTap = true;
        lastTapTarget = ev.target;
        var el = ev.target.closest ? ev.target : ev.target.parentElement;
        if (el && el.closest && !el.closest(INPUT_SELECTOR)) {
          var popup = el.closest(POPUP_SELECTOR);
          if (popup) {
            lastPopupTapTime = Date.now();
            lastPopupElement = popup;
            return;
          }
        }
        lastPopupTapTime = 0;
        lastPopupElement = null;
      }
    };
    documentRef.addEventListener('pointerdown', recordTap, { capture: true, passive: true });
    documentRef.addEventListener('touchstart', recordTap, { capture: true, passive: true });

    htmlProto.focus = function (options) {
      try {
        var isEditor =
          this.isContentEditable ||
          (this.getAttribute && (
            this.getAttribute('contenteditable') === 'true' ||
            this.hasAttribute('data-lexical-editor')
          ));

        if (isEditor) {
          // Block initial boot auto-focus before any user interaction
          if (!hasAnyUserTap) {
            return;
          }
          // Block restoreFocus back to chat composer right after tapping inside/dismissing a popup/menu/sidebar
          if (lastPopupTapTime && (Date.now() - lastPopupTapTime) < 700) {
            var samePopupDialog = lastPopupElement && lastPopupElement.contains && lastPopupElement.contains(this);
            if (!samePopupDialog) {
              return;
            }
          }

          if (!options || typeof options !== 'object') {
            options = { preventScroll: true };
          } else if (options.preventScroll === undefined) {
            options = { preventScroll: true, focusVisible: options.focusVisible };
          }
        }
      } catch (_) {}
      return origFocus.call(this, options);
    };
    htmlProto.__agFocusGuarded = true;
  } catch (e) {}
}

/**
 * 3. Surgical Lazy Gate for 48kHz AudioContext & AudioWorklet (`Azc` in main.js):
 *    Only gates contexts created with { sampleRate: 48000 } (used at startup by streamingAudioFeature)
 *    or loading audioWorklet modules. Leaves normal chime contexts (`r6b`, `s6b`) completely un-suspended.
 */
function installLazyAudioGate(win) {
  try {
    var OrigAudioContext = win.AudioContext || win.webkitAudioContext;
    if (!OrigAudioContext || OrigAudioContext.__agLazyGated) return;

    var WrappedAudioContext = function (opts) {
      var ctx = opts !== undefined ? new OrigAudioContext(opts) : new OrigAudioContext();
      var isBootVoiceCtx = !!(opts && opts.sampleRate === 48000);
      try {
        if (isBootVoiceCtx && ctx.state === 'running' && typeof ctx.suspend === 'function') {
          ctx.suspend().catch(function () {});
        }
        if (isBootVoiceCtx && ctx.audioWorklet && typeof ctx.audioWorklet.addModule === 'function') {
          var origAddModule = ctx.audioWorklet.addModule.bind(ctx.audioWorklet);
          var pendingUrl = null;
          var pendingOpts = undefined;
          var loadedPromise = null;

          var ensureLoaded = function () {
            if (!loadedPromise && pendingUrl) {
              loadedPromise = origAddModule(pendingUrl, pendingOpts);
            }
            return loadedPromise || Promise.resolve();
          };

          ctx.audioWorklet.addModule = function (moduleUrl, workletOpts) {
            pendingUrl = moduleUrl;
            pendingOpts = workletOpts;
            return Promise.resolve();
          };

          if (typeof ctx.resume === 'function') {
            var origResume = ctx.resume.bind(ctx);
            ctx.resume = function () {
              return ensureLoaded().then(function () {
                return origResume();
              });
            };
          }
          if (typeof ctx.createMediaStreamSource === 'function') {
            var origCreateStream = ctx.createMediaStreamSource.bind(ctx);
            ctx.createMediaStreamSource = function (stream) {
              ensureLoaded();
              return origCreateStream(stream);
            };
          }
        }
      } catch (_) {}
      return ctx;
    };
    WrappedAudioContext.prototype = OrigAudioContext.prototype;
    WrappedAudioContext.__agLazyGated = true;
    win.AudioContext = WrappedAudioContext;
    if (win.webkitAudioContext) {
      win.webkitAudioContext = WrappedAudioContext;
    }
  } catch (e) {}
}

/**
 * 5. CSS Performance, Instant Menus/Drawers/Sheets & Scroll Smoothness:
 *    IMPORTANT: Do NOT apply `transform: translateZ(0)` or `overscroll-behavior-y: contain`
 *    to `.overflow-y-auto`! In Settings (`TNb`), every Settings screen renders `G_`
 *    (`<div class="w-full bg-transparent h-full overflow-y-auto">`) inside an `auto`-height
 *    wrapper inside `TNb`'s actual scroll container (`<div class="flex h-full overflow-auto">`).
 *    Putting `overscroll-behavior-y: contain; transform: translateZ(0)` on `.overflow-y-auto`
 *    turns `G_` into a scroll trap that blocks scroll chaining up to `TNb`'s `.overflow-auto`!
 */
function installStyles(documentRef) {
  try {
    var existing = documentRef.querySelectorAll(
      'style[data-agy="web-optimizations"], style[data-agy="gpu-acceleration"]'
    );
    for (var i = 0; i < existing.length; i++) {
      if (existing[i].parentNode) existing[i].parentNode.removeChild(existing[i]);
    }

    var style = documentRef.createElement('style');
    style.setAttribute('data-agy', 'web-optimizations');
    style.textContent = [
      /* Instant tap response & no tap highlight flash */
      '*, *::before, *::after { -webkit-tap-highlight-color: transparent; }',
      'button, a, [role="button"], [role="menuitem"], [role="menuitemradio"], [role="option"], [role="tab"], input, select, textarea {',
      '  touch-action: manipulation;',
      '}',

      /* Chat viewport smooth scroll (no translateZ(0) so position:fixed descendants are not trapped) */
      '[data-testid="autoscroll-viewport"] {',
      '  -webkit-overflow-scrolling: touch;',
      '  overscroll-behavior-y: contain;',
      '  touch-action: pan-y;',
      '  scroll-behavior: auto !important;',
      '}',
      /* Scrollable diff/code/artifact panels allow both horizontal and vertical finger panning */
      '.jetski-scrollable-element {',
      '  -webkit-overflow-scrolling: touch;',
      '  touch-action: pan-x pan-y;',
      '  scroll-behavior: auto !important;',
      '}',

      /* Touch scrolling & pan-y for mobile menus/lists */
      '[role="listbox"], [role="menu"], [data-testid="model-selector-panel"] .overflow-y-auto {',
      '  -webkit-overflow-scrolling: touch;',
      '  touch-action: pan-y;',
      '}',

      /* Disable custom JS desktop overlay scrollbar DOM & restore native GPU compositor scrollbar */
      '[data-testid="overlay-scrollbar"], [data-testid="overlay-scrollbar-thumb"] {',
      '  display: none !important;',
      '  opacity: 0 !important;',
      '  pointer-events: none !important;',
      '  animation: none !important;',
      '  transition: none !important;',
      '}',
      'html.overlay-scrollbars [data-testid="autoscroll-viewport"], html.overlay-scrollbars .jetski-scrollable-element {',
      '  scrollbar-width: thin;',
      '}',

      /* Keep sticky user prompts pinned on long replies, but drop the 28px alpha gradient bleed */
      '[data-testid="autoscroll-viewport"] [role="article"][aria-label="User message"]::after {',
      '  display: none !important;',
      '  content: none !important;',
      '  background: none !important;',
      '}',

      /* Style containment on turns/code without contain:layout so position:fixed popups stay anchored */
      '[data-testid="autoscroll-viewport"] > div > .scroll-mt-4,',
      '[data-testid="autoscroll-viewport"] [role="article"][aria-label="Agent response"],',
      '.artifact-viewer, .artifact-card {',
      '  contain: style;',
      '}',
      'pre, .code-block, .md-table-scroll {',
      '  contain: style;',
      '  overscroll-behavior-x: contain;',
      '}',

      /* === INSTANT MENUS, BOTTOM SHEETS & SIDEBAR DRAWER (without breaking .transition-all controls) === */
      '.transition-\\[height\\], .transition-\\[width\\],',
      '[style*="transition: width 500ms"], [style*="transition: padding-left 500ms"], [style*="transition: padding-right 500ms"] {',
      '  transition: none !important;',
      '}',

      /* Base UI Bottom Sheets (EK / .aux-drawer-popup), Dropdown Menus (.animate-slideIn), and Overview Panels */
      '.aux-drawer-backdrop, [data-testid="bottom-sheet-backdrop"] {',
      '  transition: none !important;',
      '  animation: none !important;',
      '  will-change: opacity;',
      '  backface-visibility: hidden;',
      '}',
      '.aux-drawer-popup, [data-testid="bottom-sheet"] {',
      '  transition: none !important;',
      '  animation: none !important;',
      '  box-shadow: 0 -2px 10px rgba(0, 0, 0, 0.22) !important;',
      '  will-change: transform;',
      '  contain: layout paint style;',
      '  backface-visibility: hidden;',
      '}',
      '.animate-slideIn, .animate-overview-panel {',
      '  transition: none !important;',
      '  transition-duration: 0ms !important;',
      '  animation: none !important;',
      '  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25) !important;',
      '}',
      '.aux-drawer-popup[data-starting-style], .aux-drawer-backdrop[data-starting-style],',
      '.animate-slideIn[data-starting-style], .animate-overview-panel[data-starting-style] {',
      '  opacity: 1 !important;',
      '  transform: none !important;',
      '}',
      '.aux-drawer-popup[data-ending-style], .aux-drawer-backdrop[data-ending-style],',
      '.animate-slideIn[data-ending-style], .animate-overview-panel[data-ending-style] {',
      '  opacity: 0 !important;',
      '  pointer-events: none !important;',
      '  transition: none !important;',
      '}',

      /* Left Sidebar Drawer ([role="dialog"] + [data-testid="sidebar-popover-backdrop"]) */
      '[data-testid="sidebar-popover-backdrop"] {',
      '  transition: none !important;',
      '}',
      'div[role="dialog"][aria-modal="true"]:not([aria-label="Settings"]) {',
      '  transition: none !important;',
      '  box-shadow: none !important;',
      '}',
      '.animate-in.slide-in-from-left {',
      '  animation-duration: 0ms !important;',
      '}',

      /* Remove expensive offscreen alpha mask-image gradients on menu & sidebar scroll lists */
      '[role="navigation"] [style*="mask-image"],',
      '[role="dialog"] [style*="mask-image"],',
      '[role="menu"] [style*="mask-image"],',
      '[role="listbox"] [style*="mask-image"],',
      '[data-testid="model-selector-panel"] [style*="mask-image"],',
      '[data-testid="bottom-sheet"] [style*="mask-image"] {',
      '  -webkit-mask-image: none !important;',
      '  mask-image: none !important;',
      '}',

      /* Kill expensive multi-pass Gaussian blur shaders on mobile tiled GPUs */
      '.backdrop-blur-sm, .backdrop-blur, .backdrop-blur-md, .backdrop-blur-lg, [class*="backdrop-blur"] {',
      '  -webkit-backdrop-filter: none !important;',
      '  backdrop-filter: none !important;',
      '}',

      /* Remove Prism syntax-highlighting text-shadow on every code token (jetbox.css:35,240) */
      'code[class*="language-"], pre[class*="language-"], code[class*="language-"] *, pre[class*="language-"] * {',
      '  text-shadow: none !important;',
      '}'
    ].join('\n');

    (documentRef.head || documentRef.documentElement).appendChild(style);
  } catch (e) {}
}

installEventOptimizations(global, doc);
installFocusGuard(global, doc);
installLazyAudioGate(global);
installStyles(doc);

global.__agWebOptimizations = {
  installed: true,
  version: '3.2.0'
};
