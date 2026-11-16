/**
 * core/public-api.js - expose window.__agBridgeCore.
 *
 * Dead entries listed in docs/DEADCODE.md are not ported.
 */
var api = {
  version: VERSION,
  config: CONFIG,
  endpoints: ENDPOINTS,
  endpoint: endpoint,
  setEndpoint: setEndpoint,
  baseOrigin: baseOrigin,
  syncCsrf: syncCsrf,
  parseEndpointSpec: parseEndpointSpec,
  triggerStreamRecovery: triggerStreamRecovery,
  getActiveStreamCount: function () { return activeStreamClosers.size; }
};

window.__agBridgeCore = api;

try { console.log('[AGY bridge.core] v' + VERSION + ' ready (loopback stream recovery + online shim active)'); } catch (e) {}
