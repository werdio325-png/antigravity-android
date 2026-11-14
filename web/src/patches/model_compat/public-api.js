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
