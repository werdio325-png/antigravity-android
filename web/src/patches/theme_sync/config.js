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
var hostTheme = 'system';
try {
  var params = new URLSearchParams(window.location.search);
  hostTheme = params.get('hostTheme') || window.__AG_HOST_THEME__ || 'system';
} catch (e) {
  hostTheme = window.__AG_HOST_THEME__ || 'system';
}
if (hostTheme !== 'light' && hostTheme !== 'dark') {
  hostTheme = 'system';
}

var state = {
  installed: false,
  last: null,
  notifications: 0,
  observer: null,
  storageBound: false,
  hasAndroid: false
};
