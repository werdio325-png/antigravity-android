/**
 * patches/touch_model_menu/state.js - mutable patch state.
 */
var state = {
  installed: false,
  hasTouch: false,
  rows: 0,
  groups: 0,
  lastOpenAt: 0,
  opens: 0,
  observer: null,
  scanTimer: 0
};
