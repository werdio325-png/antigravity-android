(function(){
/**
 * patches/backend_ui/panel.js - Settings: AI Backend.
 *
 * Shows which backend is active, what the runtime reports (CLI version, process
 * and ACP state), and the model catalogue discovered from the agent's own session
 * options with its honest status. It never marks a model available on its own.
 */
'use strict';

var BACKEND_UI = {
  buttonId: 'ag-backend-button',
  panelId: 'ag-backend-panel',
  styleId: 'ag-backend-style',
  eventName: '__agAcpEvent'
};

var backendState = { open: false, status: null, installed: false };

function backendBridge() {
  return window.AndroidAgent || null;
}

function backendToken() {
  return window.__AG_NATIVE_BRIDGE_TOKEN__ || '';
}

function backendStyles() {
  if (document.getElementById(BACKEND_UI.styleId)) return;
  var style = document.createElement('style');
  style.id = BACKEND_UI.styleId;
  style.textContent = [
    '#' + BACKEND_UI.buttonId + '{position:fixed;left:14px;bottom:78px;z-index:2147483000;',
    'border-radius:9999px;padding:8px 14px;font:600 12px/1.2 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;',
    'border:1px solid rgba(128,128,128,.45);background:rgba(18,23,38,.92);color:#f1f5f9;',
    'box-shadow:0 4px 16px rgba(0,0,0,.35);cursor:pointer;touch-action:manipulation}',
    'html.light #' + BACKEND_UI.buttonId + '{background:rgba(255,255,255,.95);color:#0f172a}',
    '#' + BACKEND_UI.panelId + '{position:fixed;left:12px;right:12px;bottom:120px;z-index:2147483001;',
    'max-width:460px;margin-right:auto;border-radius:14px;padding:14px;',
    'background:rgba(18,23,38,.98);color:#f1f5f9;border:1px solid rgba(128,128,128,.35);',
    'box-shadow:0 12px 40px rgba(0,0,0,.45);font:13px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;',
    'max-height:70vh;overflow:auto}',
    'html.light #' + BACKEND_UI.panelId + '{background:rgba(255,255,255,.99);color:#0f172a}',
    '#' + BACKEND_UI.panelId + ' h3{margin:0 0 8px;font-size:14px;font-weight:700}',
    '#' + BACKEND_UI.panelId + ' .ag-row{display:flex;justify-content:space-between;gap:10px;padding:2px 0}',
    '#' + BACKEND_UI.panelId + ' .ag-models{margin:8px 0;border-top:1px solid rgba(128,128,128,.25);padding-top:8px}',
    '#' + BACKEND_UI.panelId + ' .ag-model{display:flex;justify-content:space-between;gap:8px;padding:3px 0;font-size:12px}',
    '#' + BACKEND_UI.panelId + ' .ag-model b{font-weight:600}',
    '#' + BACKEND_UI.panelId + ' .ag-model span{opacity:.75}',
    '#' + BACKEND_UI.panelId + ' .ag-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}',
    '#' + BACKEND_UI.panelId + ' button{border-radius:8px;padding:8px 12px;font:600 12px/1 system-ui,sans-serif;',
    'border:1px solid rgba(128,128,128,.45);background:transparent;color:inherit;cursor:pointer}',
    '#' + BACKEND_UI.panelId + ' button[data-primary]{background:#0284c7;border-color:#0284c7;color:#fff}',
    '#' + BACKEND_UI.panelId + ' .ag-error{color:#f87171;font-size:12px;margin-top:6px;white-space:pre-wrap}'
  ].join('');
  (document.head || document.documentElement).appendChild(style);
}

function backendEl(tag, className, text) {
  var element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined && text !== null) element.textContent = String(text);
  return element;
}

function backendEnsureDom() {
  backendStyles();
  if (document.getElementById(BACKEND_UI.buttonId)) return;
  var button = backendEl('button', null, 'Agent backend');
  button.id = BACKEND_UI.buttonId;
  button.type = 'button';
  button.addEventListener('click', function (event) {
    event.preventDefault();
    event.stopPropagation();
    backendState.open = !backendState.open;
    var panel = document.getElementById(BACKEND_UI.panelId);
    if (panel) panel.style.display = backendState.open ? 'block' : 'none';
    if (backendState.open) backendRefresh();
  });
  var panel = backendEl('div');
  panel.id = BACKEND_UI.panelId;
  panel.style.display = 'none';
  panel.addEventListener('click', function (event) { event.stopPropagation(); });
  (document.body || document.documentElement).appendChild(button);
  (document.body || document.documentElement).appendChild(panel);
}

function backendRefresh() {
  var bridge = backendBridge();
  if (!bridge || typeof bridge.status !== 'function') {
    backendRender({ error: 'The backend bridge is unavailable in this build.', backend: 'embedded' });
    return;
  }
  try {
    backendRender(JSON.parse(bridge.status()));
  } catch (error) {
    backendRender({ error: 'Cannot read backend status: ' + error.message, backend: 'embedded' });
  }
}

function backendRender(status) {
  backendEnsureDom();
  backendState.status = status;
  var panel = document.getElementById(BACKEND_UI.panelId);
  var button = document.getElementById(BACKEND_UI.buttonId);
  if (!panel || !button) return;

  var mode = status.backend || status.mode || 'embedded';
  button.textContent = mode === 'acp' ? 'Agent: Antigravity CLI' : 'Agent: embedded engine';

  panel.textContent = '';
  panel.appendChild(backendEl('h3', null, 'AI backend'));

  panel.appendChild(backendEl('div', 'ag-row', 'Active: ' + (mode === 'acp' ? 'Official Antigravity CLI (ACP)' : 'Continuum embedded engine')));
  if (mode === 'acp') {
    panel.appendChild(backendEl('div', 'ag-row', 'CLI: ' + (status.cliInstalled ? ('installed ' + (status.cliVersion || 'version unknown')) : 'not installed')));
    panel.appendChild(backendEl('div', 'ag-row', 'Process: ' + (status.processAlive ? 'running' : 'stopped')));
    panel.appendChild(backendEl('div', 'ag-row', 'ACP: ' + (status.connected ? 'connected' : 'disconnected')));
    panel.appendChild(backendEl('div', 'ag-row', 'Auth: ' + (status.authRequired ? 'sign-in required' : 'ok')));
    if (status.sessionId) panel.appendChild(backendEl('div', 'ag-row', 'Session: ' + status.sessionId));
    if (status.workspace) panel.appendChild(backendEl('div', 'ag-row', 'Workspace: ' + status.workspace));
    if (status.lastSyncAt) {
      var when = new Date(Number(status.lastSyncAt));
      panel.appendChild(backendEl('div', 'ag-row', 'Last sync: ' + (isNaN(when.getTime()) ? status.lastSyncAt : when.toLocaleTimeString())));
    }

    var catalogue = status.catalogue || {};
    var models = catalogue.models || [];
    if (models.length) {
      var box = backendEl('div', 'ag-models');
      models.forEach(function (model) {
        var row = backendEl('div', 'ag-model');
        row.appendChild(backendEl('b', null, model.label || model.id));
        // Status is whatever the runtime and a real turn established; never
        // upgraded here.
        row.appendChild(backendEl('span', null, model.id + ' · ' + model.status));
        row.title = model.detail || '';
        box.appendChild(row);
      });
      panel.appendChild(box);
      if ((catalogue.thinkingLevels || []).length) {
        panel.appendChild(backendEl('div', 'ag-row', 'Thinking: ' + catalogue.thinkingLevels.join(', ')));
      }
    }
  }

  if (status.error) {
    panel.appendChild(backendEl('div', 'ag-error', status.error));
  }

  var actions = backendEl('div', 'ag-actions');
  if (mode !== 'acp') {
    var useAcp = backendEl('button', null, 'Use ACP backend');
    useAcp.setAttribute('data-primary', '1');
    useAcp.addEventListener('click', function () {
      if (backendBridge().setBackend) backendBridge().setBackend(backendToken(), 'acp');
      backendRefresh();
    });
    actions.appendChild(useAcp);
  } else {
    if (!status.cliInstalled) {
      var install = backendEl('button', null, 'Install CLI');
      install.setAttribute('data-primary', '1');
      install.addEventListener('click', function () {
        if (backendBridge().installCli) backendBridge().installCli(backendToken());
        panel.appendChild(backendEl('div', 'ag-error', 'Downloading Google\'s CLI and verifying its SHA-512…'));
      });
      actions.appendChild(install);
    } else {
      var start = backendEl('button', null, 'Start');
      start.addEventListener('click', function () {
        if (backendBridge().start) backendBridge().start(backendToken(), status.workspace || '');
      });
      actions.appendChild(start);
      var restart = backendEl('button', null, 'Restart runtime');
      restart.addEventListener('click', function () {
        if (backendBridge().restart) backendBridge().restart(backendToken());
      });
      actions.appendChild(restart);
    }
    var useEmbedded = backendEl('button', null, 'Use embedded engine');
    useEmbedded.addEventListener('click', function () {
      if (backendBridge().setBackend) backendBridge().setBackend(backendToken(), 'embedded');
      backendRefresh();
    });
    actions.appendChild(useEmbedded);
  }
  panel.appendChild(actions);

  var diagnostics = backendEl('div', 'ag-actions');
  var refresh = backendEl('button', null, 'Refresh status');
  refresh.addEventListener('click', backendRefresh);
  diagnostics.appendChild(refresh);
  panel.appendChild(diagnostics);
}

function installBackendUi() {
  if (backendState.installed) return;
  backendState.installed = true;
  window[BACKEND_UI.eventName] = function (event) {
    if (!event) return;
    if (event.type === 'status' && event.status) {
      backendRender(event.status);
    } else if (event.type === 'update' || event.type === 'turn' || event.type === 'error') {
      // Agent progress is rendered by the app's own conversation view; here we
      // only keep the diagnostic panel current.
      backendRefresh();
    }
  };
  function mount() {
    backendEnsureDom();
    backendRefresh();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
  console.info('[agBackend] ready');
}

window.AgyBackend = {
  open: function () { backendState.open = true; backendEnsureDom(); backendRefresh(); },
  close: function () {
    backendState.open = false;
    var panel = document.getElementById(BACKEND_UI.panelId);
    if (panel) panel.style.display = 'none';
  },
  status: function () { return backendState.status; },
  refresh: backendRefresh
};

installBackendUi();


})();
