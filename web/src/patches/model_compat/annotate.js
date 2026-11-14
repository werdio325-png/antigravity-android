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
