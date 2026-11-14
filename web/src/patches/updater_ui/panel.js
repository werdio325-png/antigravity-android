/**
 * patches/updater_ui/panel.js - DOM for the update button and panel.
 */
function updaterStyles() {
  if (document.getElementById(UPDATER.styleId)) return;
  var style = document.createElement('style');
  style.id = UPDATER.styleId;
  style.textContent = [
    '#' + UPDATER.buttonId + '{position:fixed;right:14px;bottom:78px;z-index:2147483000;',
    'border-radius:9999px;padding:8px 14px;font:600 12px/1.2 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;',
    'border:1px solid rgba(128,128,128,.45);background:rgba(18,23,38,.92);color:#f1f5f9;',
    'box-shadow:0 4px 16px rgba(0,0,0,.35);cursor:pointer;touch-action:manipulation}',
    'html.light #' + UPDATER.buttonId + '{background:rgba(255,255,255,.95);color:#0f172a}',
    '#' + UPDATER.panelId + '{position:fixed;right:12px;left:12px;bottom:120px;z-index:2147483001;',
    'max-width:460px;margin-left:auto;border-radius:14px;padding:14px;',
    'background:rgba(18,23,38,.98);color:#f1f5f9;border:1px solid rgba(128,128,128,.35);',
    'box-shadow:0 12px 40px rgba(0,0,0,.45);font:13px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;',
    'max-height:70vh;overflow:auto}',
    'html.light #' + UPDATER.panelId + '{background:rgba(255,255,255,.99);color:#0f172a}',
    '#' + UPDATER.panelId + ' h3{margin:0 0 8px;font-size:14px;font-weight:700}',
    '#' + UPDATER.panelId + ' .ag-row{display:flex;justify-content:space-between;gap:10px;padding:2px 0}',
    '#' + UPDATER.panelId + ' .ag-notes{white-space:pre-wrap;font-size:12px;opacity:.85;',
    'max-height:26vh;overflow:auto;border:1px solid rgba(128,128,128,.3);border-radius:8px;padding:8px;margin:8px 0}',
    '#' + UPDATER.panelId + ' .ag-bar{height:6px;border-radius:9999px;background:rgba(128,128,128,.25);overflow:hidden;margin:8px 0}',
    '#' + UPDATER.panelId + ' .ag-bar>i{display:block;height:100%;width:0;background:#38bdf8;transition:width .18s}',
    '#' + UPDATER.panelId + ' .ag-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}',
    '#' + UPDATER.panelId + ' button{border-radius:8px;padding:8px 12px;font:600 12px/1 system-ui,sans-serif;',
    'border:1px solid rgba(128,128,128,.45);background:transparent;color:inherit;cursor:pointer;touch-action:manipulation}',
    '#' + UPDATER.panelId + ' button[data-primary]{background:#0284c7;border-color:#0284c7;color:#fff}',
    '#' + UPDATER.panelId + ' button[disabled]{opacity:.45;cursor:default}',
    '#' + UPDATER.panelId + ' .ag-error{color:#f87171;font-size:12px;margin-top:6px;white-space:pre-wrap}'
  ].join('');
  (document.head || document.documentElement).appendChild(style);
}

function updaterEl(tag, className, text) {
  var element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined && text !== null) element.textContent = String(text);
  return element;
}

function updaterEnsureDom() {
  updaterStyles();
  if (document.getElementById(UPDATER.buttonId)) return;

  var button = updaterEl('button', null, UPDATER.idleLabel);
  button.id = UPDATER.buttonId;
  button.type = 'button';
  button.addEventListener('click', function (event) {
    event.preventDefault();
    event.stopPropagation();
    updaterSetOpen(!updaterState.open);
    updaterRefresh();
  });

  var panel = updaterEl('div');
  panel.id = UPDATER.panelId;
  panel.style.display = 'none';
  panel.addEventListener('click', function (event) { event.stopPropagation(); });

  var body = document.body || document.documentElement;
  body.appendChild(button);
  body.appendChild(panel);
}

function updaterSetOpen(open) {
  updaterEnsureDom();
  updaterState.open = !!open;
  var panel = document.getElementById(UPDATER.panelId);
  if (panel) panel.style.display = open ? 'block' : 'none';
  if (open) updaterRender(updaterState.last);
}

function updaterRender(state) {
  updaterEnsureDom();
  if (!state) {
    updaterRefresh();
    return;
  }
  updaterState.last = state;

  var panel = document.getElementById(UPDATER.panelId);
  var button = document.getElementById(UPDATER.buttonId);
  if (!panel || !button) return;

  var installed = state.installedVersion || 'unknown';
  var latest = state.latestVersion || '';
  var status = state.state || 'idle';

  button.textContent = status === 'available' ? 'Update available: ' + latest
    : (status === 'ready' ? 'Update ready to install' : UPDATER.idleLabel);

  panel.textContent = '';

  var head = updaterEl('h3', null, 'Continuum updates');
  panel.appendChild(head);

  panel.appendChild(updaterEl('div', 'ag-row', 'Installed: ' + installed));
  if (latest) {
    panel.appendChild(updaterEl('div', 'ag-row', 'Latest: ' + latest + (state.latestTag ? ' (' + state.latestTag + ')' : '')));
  }
  if (state.sizeBytes) {
    panel.appendChild(updaterEl('div', 'ag-row', 'Download size: ' + formatBytes(state.sizeBytes)));
  }
  if (state.sha256) {
    panel.appendChild(updaterEl('div', 'ag-row', 'SHA-256: ' + shortChecksum(state.sha256)));
  }
  if (state.repo) {
    panel.appendChild(updaterEl('div', 'ag-row', 'Source: github.com/' + state.repo));
  }

  if (state.notes || status === 'available' || status === 'ready') {
    panel.appendChild(updaterEl('div', 'ag-notes', notesPreview(state.notes)));
  }

  if (status === 'downloading' || status === 'ready') {
    var bar = updaterEl('div', 'ag-bar');
    var fill = updaterEl('i');
    fill.style.width = Math.max(0, Math.min(100, state.progress || 0)) + '%';
    bar.appendChild(fill);
    panel.appendChild(bar);
    panel.appendChild(updaterEl('div', 'ag-row',
      formatBytes(state.downloadedBytes) + ' / ' + formatBytes(state.totalBytes || state.sizeBytes)));
  }

  if (state.error) {
    panel.appendChild(updaterEl('div', 'ag-error', state.error));
  }

  var actions = updaterEl('div', 'ag-actions');

  var check = updaterEl('button', null, status === 'checking' ? 'Checking…' : 'Check for updates');
  check.disabled = status === 'checking' || status === 'downloading';
  check.addEventListener('click', updaterCheck);
  actions.appendChild(check);

  if (status === 'available') {
    var download = updaterEl('button', null, 'Download');
    download.setAttribute('data-primary', '1');
    download.addEventListener('click', updaterDownload);
    actions.appendChild(download);
  }

  if (status === 'ready' || status === 'installing') {
    var install = updaterEl('button', null, 'Install');
    install.setAttribute('data-primary', '1');
    install.addEventListener('click', updaterInstall);
    actions.appendChild(install);
  }

  if (status === 'available' || status === 'ready' || status === 'downloading') {
    var later = updaterEl('button', null, 'Later');
    later.addEventListener('click', updaterPostpone);
    actions.appendChild(later);
  }

  panel.appendChild(actions);

  if (status === 'ready' && state.needsInstallPermission) {
    panel.appendChild(updaterEl('div', 'ag-error',
      'Android needs permission to install this update. Tap Install, allow "Install unknown apps" for Continuum, then tap Install again.'));
  }
}
