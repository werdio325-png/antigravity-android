/**
 * patches/touch_model_menu/config.js - config, guard and bundle scope.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 */
'use strict';

if (window.__agTouchModelMenu && window.__agTouchModelMenu.__installed) {
  return;
}

var CONFIG = {
  // selectors
  trigger: '[data-testid="model-selector-trigger"]',
  panel: '[data-testid="model-selector-panel"]',
  effortGroup: '[data-testid="model-selector-effort-group"]',
  effortOption: '[data-testid="model-selector-effort-option"]',
  modelItem: '[data-testid="model-selector-item"]',
  menuItemRole: '[role="menuitem"]',
  // observer root
  root: '#root',
  // behaviour
  fallbackDelayMs: 70,
  retryDelayMs: 260,
  reopenGuardMs: 400
};
