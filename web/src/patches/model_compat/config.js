/**
 * patches/model_compat/config.js - annotate the model menu with engine facts.
 *
 * The app does not own the model catalogue: the engine reports it and Google's
 * servers decide per-account availability. This patch only *explains* what the
 * engine already shows - it never enables a model, never rewrites the catalogue
 * and never claims availability.
 */
'use strict';

if (window.__agModelCompat && window.__agModelCompat.__installed) {
  return;
}

var MODEL_COMPAT = {
  modelItem: '[data-testid="model-selector-item"]',
  menuItemRole: '[role="menuitem"]',
  trigger: '[data-testid="model-selector-trigger"]',
  root: '#root',
  scanDebounceMs: 200,
  // A model row that starts like a product name but is unknown to this engine.
  modelish: /^(gemini|claude|gpt|grok|opus)\b/i,
  badgeAttr: 'data-continuum-compat',
  badgeTextAttr: 'data-continuum-compat-text'
};
