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
