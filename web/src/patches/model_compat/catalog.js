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
