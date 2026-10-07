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
var hostTheme = 'dark';
try {
  var params = new URLSearchParams(window.location.search);
  hostTheme = params.get('hostTheme') || window.__AG_HOST_THEME__ || 'dark';
} catch (e) {
  hostTheme = window.__AG_HOST_THEME__ || 'dark';
}
hostTheme = hostTheme === 'light' ? 'light' : 'dark';

var state = {
  installed: false,
  last: null,
  notifications: 0,
  observer: null,
  storageBound: false,
  hasAndroid: false
};
