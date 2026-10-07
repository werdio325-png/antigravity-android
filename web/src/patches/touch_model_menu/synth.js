/**
 * patches/touch_model_menu/synth.js - synthetic pointer/mouse/key sequences.
 */
function dispatchPointerSequence(el) {
  var rect;
  try { rect = el.getBoundingClientRect(); } catch (_) { rect = null; }
  var cx = rect ? rect.left + rect.width / 2 : 0;
  var cy = rect ? rect.top + rect.height / 2 : 0;

  var pointerInit = {
    bubbles: true, cancelable: true, composed: true,
    clientX: cx, clientY: cy, pointerId: 1, pointerType: 'touch',
    isPrimary: true, button: 0, buttons: 0
  };
  var mouseInit = {
    bubbles: true, cancelable: true, composed: true,
    clientX: cx, clientY: cy, button: 0, buttons: 0
  };

  function fire(make) {
    try {
      var ev = make();
      ev.__agTouchSynthetic = true;
      el.dispatchEvent(ev);
    } catch (_) { /* PointerEvent may be unavailable */ }
  }

  fire(function () { return new PointerEvent('pointerover', pointerInit); });
  fire(function () { return new PointerEvent('pointerenter', pointerInit); });
  fire(function () { return new PointerEvent('pointermove', pointerInit); });
  fire(function () { return new MouseEvent('mouseover', mouseInit); });
  fire(function () { return new MouseEvent('mouseenter', mouseInit); });
  fire(function () { return new MouseEvent('mousemove', mouseInit); });
}

function arrowRight(el) {
  try {
    var ev = new KeyboardEvent('keydown', {
      key: 'ArrowRight', code: 'ArrowRight', keyCode: 39, which: 39,
      bubbles: true, cancelable: true, composed: true
    });
    ev.__agTouchSynthetic = true;
    el.dispatchEvent(ev);
  } catch (_) {}
}
