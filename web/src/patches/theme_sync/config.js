/**
 * patches/theme_sync/config.js - host theme, state and guard.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 */
'use strict';

if (window.__agThemeSync && window.__agThemeSync.__installed) {
  return;
}

var doc = window.document;
var initialHostTheme = 'system';
try {
  var params = new URLSearchParams(window.location.search);
  initialHostTheme = params.get('hostTheme') || window.__AG_HOST_THEME__ || 'system';
} catch (e) {
  initialHostTheme = window.__AG_HOST_THEME__ || 'system';
}
if (initialHostTheme !== 'light' && initialHostTheme !== 'dark') {
  initialHostTheme = 'system';
}

var state = {
  installed: false,
  last: null,
  lastFromBody: null,
  notifications: 0,
  observer: null,
  storageBound: false,
  hasAndroid: false
};
