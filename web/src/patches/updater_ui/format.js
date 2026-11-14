/**
 * patches/updater_ui/format.js - small formatting helpers.
 */
function formatBytes(bytes) {
  var value = Number(bytes) || 0;
  if (value <= 0) return 'unknown size';
  var units = ['B', 'KB', 'MB', 'GB'];
  var index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value = value / 1024;
    index++;
  }
  return (index === 0 ? value : value.toFixed(value >= 100 ? 0 : 1)) + ' ' + units[index];
}

function shortChecksum(sha) {
  return typeof sha === 'string' && sha.length >= 12 ? sha.slice(0, 12) + '…' : 'not published';
}

function notesPreview(notes) {
  var text = String(notes || '').trim();
  if (!text) return 'No changelog was published for this release.';
  return text.length > 4000 ? text.slice(0, 4000) + '\n…' : text;
}
