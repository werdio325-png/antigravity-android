/**
 * patches/updater_ui/config.js - in-app update panel.
 *
 * Native side (AndroidUpdate) does all network, checksum and installer work.
 * This UI only renders state and forwards explicit user actions. Nothing is ever
 * installed without the user pressing Install and confirming in Android's own
 * package installer.
 */
'use strict';

if (window.__agUpdaterUi && window.__agUpdaterUi.__installed) {
  return;
}

var UPDATER = {
  panelId: 'ag-update-panel',
  buttonId: 'ag-update-button',
  styleId: 'ag-update-style',
  idleLabel: 'Updates',
  eventName: '__agUpdateEvent'
};
