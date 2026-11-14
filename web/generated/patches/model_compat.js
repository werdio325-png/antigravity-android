(function(){
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

/**
 * patches/model_compat/state.js - shared state for the model compat patch.
 */
var state = {
  installed: false,
  observer: null,
  timer: 0,
  lastReport: []
};

/**
 * patches/model_compat/catalog.js - access the engine-derived catalogue.
 *
 * The catalogue comes from the embedded language_server binary (extracted at
 * build time) via window.__APP_CONFIG__.engineInfo.catalog. Nothing is hardcoded
 * here, so a newer engine automatically yields a newer catalogue.
 */
function engineInfo() {
  var cfg = window.__APP_CONFIG__ || {};
  return cfg.engineInfo || null;
}

function engineCatalog() {
  var info = engineInfo();
  return (info && info.catalog && info.catalog.models) || [];
}

function normalizeModelName(value) {
  return String(value || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

function findCatalogEntry(label) {
  var target = normalizeModelName(label);
  if (!target) return null;
  var models = engineCatalog();
  var i;
  for (i = 0; i < models.length; i++) {
    if (normalizeModelName(models[i].id) === target ||
        normalizeModelName(models[i].display_name) === target) {
      return models[i];
    }
  }
  // "Gemini 4 Argon (High)" resolves to its base entry.
  for (i = 0; i < models.length; i++) {
    var base = normalizeModelName(models[i].base_id);
    if (base && target.indexOf(base) === 0) return models[i];
  }
  return null;
}

function engineVersion() {
  var info = engineInfo();
  return (info && info.engine && info.engine.version) || 'unknown';
}

/**
 * patches/model_compat/classify.js - explain a model row's real state.
 *
 * Three outcomes only:
 *   engine_missing  - this engine build has no such model id at all;
 *   server_side     - the engine knows the model but the UI reports it disabled,
 *                     which is decided by the account/rollout on Google's side;
 *   listed          - the engine knows the model and the row is enabled.
 *
 * The patch never flips `aria-disabled`/`disabled` and never adds a model.
 */
function rowLabel(row) {
  if (!row) return '';
  // The menu renders data-model-label on every model row; prefer it over text,
  // which may include tier suffixes or badges we appended ourselves.
  var raw = row.getAttribute('data-model-label');
  if (!raw) raw = row.getAttribute('aria-label');
  if (!raw) raw = row.textContent || '';
  return String(raw).replace(/\s+/g, ' ').trim();
}

function rowIsDisabled(row) {
  if (!row) return false;
  if (row.getAttribute('aria-disabled') === 'true') return true;
  if (row.hasAttribute('disabled')) return true;
  if (row.getAttribute('data-disabled') === 'true') return true;
  try {
    if (row.querySelector('[aria-disabled="true"],[disabled]')) return true;
  } catch (_) { /* ignore */ }
  return false;
}

function classifyRow(row) {
  var label = rowLabel(row);
  if (!label || !MODEL_COMPAT.modelish.test(label)) {
    return null;
  }
  var entry = findCatalogEntry(label);
  var disabled = rowIsDisabled(row);
  if (!entry) {
    return {
      state: 'engine_missing',
      label: label,
      detail: 'Not part of engine ' + engineVersion() +
        '. This build cannot offer it; a newer engine build is required.'
    };
  }
  if (disabled) {
    return {
      state: 'server_side',
      label: label,
      model: entry.id,
      detail: 'The engine supports ' + (entry.display_name || entry.id) +
        ', but this account is not currently offered it. Availability is decided by Google.'
    };
  }
  return {
    state: 'listed',
    label: label,
    model: entry.id,
    detail: 'Engine ' + engineVersion() + ' includes ' + (entry.display_name || entry.id) + '.'
  };
}

/**
 * patches/model_compat/annotate.js - DOM annotation for model rows.
 *
 * Only rows the engine itself renders are touched. Disabled rows get a short
 * reason badge ("engine" / "account") plus a full tooltip; enabled rows are left
 * alone. Re-renders are handled by re-applying on mutation.
 */
function ensureStyles() {
  if (document.getElementById('ag-model-compat-style')) return;
  var style = document.createElement('style');
  style.id = 'ag-model-compat-style';
  style.textContent =
    '[data-continuum-compat-text]{display:inline-block;margin-left:6px;padding:0 5px;' +
    'border-radius:9999px;font-size:10px;line-height:16px;vertical-align:middle;' +
    'border:1px solid currentColor;opacity:.75;white-space:nowrap}' +
    '[data-continuum-compat="engine_missing"]{opacity:.55}';
  (document.head || document.documentElement).appendChild(style);
}

function applyRow(row) {
  var report = classifyRow(row);
  if (!report) return null;

  row.setAttribute('data-continuum-compat', report.state);
  row.setAttribute('title', report.detail);

  var badge = row.querySelector('[' + MODEL_COMPAT.badgeTextAttr + ']');
  if (report.state === 'listed') {
    if (badge && badge.parentNode) badge.parentNode.removeChild(badge);
    return report;
  }

  var text = report.state === 'engine_missing' ? 'engine' : 'account';
  if (!badge) {
    badge = document.createElement('span');
    badge.setAttribute(MODEL_COMPAT.badgeTextAttr, text);
    badge.textContent = text;
    row.appendChild(badge);
  } else if (badge.textContent !== text) {
    badge.textContent = text;
  }
  return report;
}

function scanModelRows() {
  ensureStyles();
  var rows = [];
  try {
    rows = document.querySelectorAll(MODEL_COMPAT.modelItem + ',' + MODEL_COMPAT.menuItemRole);
  } catch (_) {
    rows = [];
  }
  var report = [];
  for (var i = 0; i < rows.length; i++) {
    var item = applyRow(rows[i]);
    if (item) report.push(item);
  }
  state.lastReport = report;
  return report;
}

/**
 * patches/model_compat/install.js - arm the observer and expose the report.
 */
function scheduleScan() {
  if (state.timer) return;
  state.timer = setTimeout(function () {
    state.timer = 0;
    scanModelRows();
  }, MODEL_COMPAT.scanDebounceMs);
}

function installModelCompat() {
  if (state.installed) return;
  state.installed = true;

  var root = document.querySelector(MODEL_COMPAT.root) || document.body || document.documentElement;
  if (root && window.MutationObserver) {
    state.observer = new MutationObserver(scheduleScan);
    state.observer.observe(root, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['aria-disabled', 'disabled', 'data-disabled', 'class'] });
  }
  // The menu only exists after the user opens it; scan once DOM is ready too.
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scheduleScan);
  } else {
    scheduleScan();
  }
  console.log('[agModelCompat] active; engine=' + engineVersion() +
    ' catalog=' + engineCatalog().length + ' models');
}

/**
 * patches/model_compat/public-api.js - window.AgyCompat diagnostics.
 */
window.AgyCompat = window.AgyCompat || {};

window.AgyCompat.engine = function () {
  var info = engineInfo() || {};
  return {
    version: (info.engine && info.engine.version) || 'unknown',
    buildTag: (info.engine && info.engine.build_tag) || 'unknown',
    modelCount: engineCatalog().length,
    availability: (info.catalog && info.catalog.availability) || 'server-decided'
  };
};

window.AgyCompat.models = function () {
  return engineCatalog().map(function (model) {
    return { id: model.id, displayName: model.display_name, tiers: model.tiers || [] };
  });
};

/** Live report of what the open model menu currently shows, and why. */
window.AgyCompat.report = function () {
  var rows = scanModelRows();
  var out = { engine: window.AgyCompat.engine(), rows: rows };
  if (window.console && console.info) {
    console.info('[agModelCompat] report', out);
  }
  return out;
};

installModelCompat();


})();
