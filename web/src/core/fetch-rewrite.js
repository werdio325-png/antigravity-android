/**
 * core/fetch-rewrite.js - file:// WebView -> core origin fetch rewrite.
 *
 * The WebView loads from file:///android_asset/web/, so main.js builds its
 * transport from window.location.origin ("file://") and the Connect/grpc-web
 * transport then fetches e.g.
 *   file://exa.language_server_pb.languageserverservice/Method
 * which the WebView rejects: URL scheme "file" is not supported.
 *
 * Rewrite file:// or relative ConnectRPC URLs onto the core origin, preserving
 * method/headers/body and attaching the CSRF token. Idempotent and a no-op for
 * non-RPC / already-absolute http(s) URLs.
 *
 * The original periodic HEAD ping is not ported (it referenced an undefined
 * helper; see docs/DEADCODE.md).
 */
var RPC_MARKERS = ['exa.language_server_pb.', 'exa.host_bridge_pb.', '/exa.'];

// RPC path (no leading slash) when `url` is a file:// or relative ConnectRPC
// URL; null for anything else (http(s), blob, data, static assets).
function rpcPathFrom(url) {
  if (!url) return null;
  var s = String(url);
  if (/^(https?|blob|data|wss?|chrome-extension):/i.test(s)) return null;
  var best = -1;
  for (var i = 0; i < RPC_MARKERS.length; i++) {
    var at = s.indexOf(RPC_MARKERS[i]);
    if (at >= 0 && (best === -1 || at < best)) best = at;
  }
  if (best === -1) return null;
  return s.slice(best).replace(/^\/+/, '') || null;
}

function interceptProjectRpc(path, bodyData) {
  if (!path) return null;
  if (path.indexOf('GetDefaultProjectDir') !== -1) {
    var defDir = (global.Android && typeof global.Android.getDefaultProjectsDirectory === 'function')
      ? global.Android.getDefaultProjectsDirectory()
      : '/storage/emulated/0/Documents/Antigravity';
    var uri = defDir.startsWith('file://') ? defDir : ('file://' + defDir);
    return new Response(JSON.stringify({ defaultProjectDirUri: uri }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }
  if (path.indexOf('CreateScratchProjectFolder') !== -1) {
    var projName = 'project-' + Date.now().toString(36);
    try {
      if (bodyData) {
        var text = (typeof bodyData === 'string')
          ? bodyData
          : (bodyData instanceof ArrayBuffer ? new TextDecoder().decode(bodyData) : '');
        if (text) {
          var parsed = JSON.parse(text);
          if (parsed && parsed.name) {
            projName = String(parsed.name).trim().replace(/\s+/g, '-');
          }
        }
      }
    } catch (_) {}
    if (global.Android && typeof global.Android.createProjectDirectory === 'function') {
      try { global.Android.createProjectDirectory(projName); } catch (_) {}
    }
    var baseDir = (global.Android && typeof global.Android.getDefaultProjectsDirectory === 'function')
      ? global.Android.getDefaultProjectsDirectory()
      : '/storage/emulated/0/Documents/Antigravity';
    var fullPath = baseDir.replace(/\/+$/, '') + '/' + projName;
    return new Response(JSON.stringify({
      folderUri: 'file://' + fullPath,
      isGitRepo: false
    }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }
  return null;
}

// --- Custom OpenAI / Claude ConnectRPC Bridge ---
var _customChatStreams = Object.create(null);
var _customChatPendingFrames = Object.create(null);
var _customChatState = Object.create(null);

function encodeConnectFrame(obj) {
  var json = JSON.stringify(obj);
  var payload = new TextEncoder().encode(json);
  var frame = new Uint8Array(5 + payload.length);
  frame[0] = 0;
  var len = payload.length;
  frame[1] = (len >>> 24) & 0xff;
  frame[2] = (len >>> 16) & 0xff;
  frame[3] = (len >>> 8) & 0xff;
  frame[4] = len & 0xff;
  frame.set(payload, 5);
  return frame;
}

function pushCustomChatUpdate(cascadeId, steps, isRunning) {
  var indices = [];
  for (var i = 0; i < steps.length; i++) indices.push(i);
  var payload = {
    update: {
      conversationId: cascadeId,
      trajectoryId: cascadeId,
      status: isRunning ? 'RUNNING' : 'IDLE',
      executableStatus: isRunning ? 'RUNNING' : 'IDLE',
      fullyIdle: !isRunning,
      mainTrajectoryUpdate: {
        stepsUpdate: {
          totalLength: steps.length,
          indices: indices,
          steps: steps
        }
      }
    }
  };
  var frame = encodeConnectFrame(payload);
  var entry = _customChatStreams[cascadeId];
  if (!entry || !entry.controller) {
    if (!_customChatPendingFrames[cascadeId]) _customChatPendingFrames[cascadeId] = [];
    _customChatPendingFrames[cascadeId].push(frame);
    return;
  }
  try {
    entry.controller.enqueue(frame);
  } catch (e) {
    warn('pushCustomChatUpdate', e);
  }
}

function resolveCustomProviderConfig(modelLabel, baseUrl) {
  var cfgs = [];
  try {
    var raw = (global.Android && typeof global.Android.getOpenAiConfigs === 'function')
      ? global.Android.getOpenAiConfigs() : '';
    if (!raw) raw = (global.localStorage && global.localStorage.getItem('ag_openai_configs')) || '';
    if (raw) {
      var arr = JSON.parse(raw);
      if (Array.isArray(arr)) cfgs = arr;
    }
  } catch (_) {}
  var matched = null;
  for (var i = 0; i < cfgs.length; i++) {
    var c = cfgs[i];
    if (!c) continue;
    var mods = Array.isArray(c.models) ? c.models : [];
    if (c.model === modelLabel || mods.indexOf(modelLabel) !== -1) {
      matched = c;
      break;
    }
  }
  if (!matched && cfgs.length > 0) matched = cfgs[0];
  var url = baseUrl || (matched && matched.url) || 'https://api.openai.com/v1';
  if (matched && matched.providerType === 'anthropic-setup-token' &&
      global.Android && typeof global.Android.getAnthropicBridgeUrl === 'function') {
    try {
      var bu = global.Android.getAnthropicBridgeUrl();
      if (bu) url = bu;
    } catch (_) {}
  }
  var apiKey = (matched && matched.apiKey) ? String(matched.apiKey).trim() : '';
  return { url: url.replace(/\/+$/, ''), apiKey: apiKey };
}

function extractBodyText(bodyData) {
  if (!bodyData) return '';
  if (typeof bodyData === 'string') return bodyData;
  if (bodyData instanceof ArrayBuffer || ArrayBuffer.isView(bodyData)) {
    return new TextDecoder().decode(bodyData);
  }
  return '';
}

function extractCascadeUserText(req) {
  if (!req || !Array.isArray(req.items)) return '';
  var parts = [];
  for (var i = 0; i < req.items.length; i++) {
    var it = req.items[i];
    if (!it) continue;
    if (typeof it.text === 'string') parts.push(it.text);
    else if (it.chunk && it.chunk.case === 'text' && typeof it.chunk.value === 'string') parts.push(it.chunk.value);
    else if (it.chunk && typeof it.chunk.text === 'string') parts.push(it.chunk.text);
    else if (it.item && it.item.case === 'text' && typeof it.item.value === 'string') parts.push(it.item.value);
  }
  return parts.join('\n').trim();
}

function wrapAgentStateStreamResponse(resp, bodyText) {
  if (!resp || !resp.ok || !resp.body || typeof TransformStream === 'undefined') return resp;
  var convId = '';
  try {
    var parsed = JSON.parse(bodyText || '{}');
    convId = parsed.conversationId || '';
  } catch (_) {}
  if (!convId) return resp;

  var streamController = null;
  var ts = new TransformStream({
    start: function (controller) {
      streamController = controller;
      _customChatStreams[convId] = { controller: controller };
      var pending = _customChatPendingFrames[convId];
      if (pending && pending.length > 0) {
        delete _customChatPendingFrames[convId];
        for (var i = 0; i < pending.length; i++) {
          try { controller.enqueue(pending[i]); } catch (_) {}
        }
      }
    },
    transform: function (chunk, controller) {
      controller.enqueue(chunk);
    },
    flush: function () {
      if (_customChatStreams[convId] && _customChatStreams[convId].controller === streamController) {
        delete _customChatStreams[convId];
      }
    }
  });
  resp.body.pipeTo(ts.writable).catch(function () {});
  return new Response(ts.readable, {
    status: resp.status,
    statusText: resp.statusText,
    headers: resp.headers
  });
}

function runCustomModelTurn(origFetch, cascadeId, userText, modelLabel, baseUrl) {
  var state = _customChatState[cascadeId];
  if (!state) {
    state = { steps: [], messages: [] };
    _customChatState[cascadeId] = state;
  }
  state.messages.push({ role: 'user', content: userText });
  var userStep = {
    type: 'USER_INPUT',
    status: 'DONE',
    userInput: { items: [{ text: userText }] }
  };
  var assistantStep = {
    type: 'PLANNER_RESPONSE',
    status: 'RUNNING',
    plannerResponse: { response: '' }
  };
  state.steps.push(userStep);
  state.steps.push(assistantStep);
  pushCustomChatUpdate(cascadeId, state.steps, true);

  var cfg = resolveCustomProviderConfig(modelLabel, baseUrl);
  var endpoint = cfg.url + '/chat/completions';
  var headers = { 'Content-Type': 'application/json' };
  if (cfg.apiKey) headers['Authorization'] = 'Bearer ' + cfg.apiKey;

  origFetch(endpoint, {
    method: 'POST',
    headers: headers,
    body: JSON.stringify({
      model: modelLabel,
      messages: state.messages,
      stream: true
    })
  }).then(function (res) {
    if (!res.ok) {
      return res.text().then(function (errTxt) {
        throw new Error('HTTP ' + res.status + (errTxt ? ': ' + errTxt : ''));
      });
    }
    if (!res.body) throw new Error('Empty response body');
    var reader = res.body.getReader();
    var decoder = new TextDecoder();
    var buf = '';
    var acc = '';
    function pump() {
      return reader.read().then(function (r) {
        if (r.done) {
          assistantStep.status = 'DONE';
          assistantStep.plannerResponse.response = acc || '(No response)';
          state.messages.push({ role: 'assistant', content: assistantStep.plannerResponse.response });
          pushCustomChatUpdate(cascadeId, state.steps, false);
          return;
        }
        buf += decoder.decode(r.value, { stream: true });
        var lines = buf.split(/\r?\n/);
        buf = lines.pop() || '';
        for (var i = 0; i < lines.length; i++) {
          var line = lines[i].trim();
          if (!line.startsWith('data:')) continue;
          var data = line.slice(5).trim();
          if (!data || data === '[DONE]') continue;
          try {
            var ev = JSON.parse(data);
            var delta = ev.choices && ev.choices[0] && ev.choices[0].delta;
            if (delta && typeof delta.content === 'string' && delta.content.length > 0) {
              acc += delta.content;
              assistantStep.plannerResponse.response = acc;
              pushCustomChatUpdate(cascadeId, state.steps, true);
            }
          } catch (_) {}
        }
        return pump();
      });
    }
    return pump();
  }).catch(function (err) {
    assistantStep.status = 'DONE';
    assistantStep.plannerResponse.response = 'Error (' + modelLabel + '): ' + (err && err.message ? err.message : String(err));
    pushCustomChatUpdate(cascadeId, state.steps, false);
  });
}

function handleCustomCascadeReq(origFetch, req) {
  if (!req) return false;
  var pc = req.cascadeConfig && req.cascadeConfig.plannerConfig;
  var ov = pc && pc.customModelInfoOverride;
  var reqModel = pc && pc.requestedModel && pc.requestedModel.choice && pc.requestedModel.choice.value;
  var isByom = pc && (pc.planModel === 326 || pc.planModel === 'GOOGLE_GEMINI_INTERNAL_BYOM' || reqModel === 326 || reqModel === 'GOOGLE_GEMINI_INTERNAL_BYOM');
  if (!ov && !isByom) return false;
  var cascadeId = req.cascadeId;
  var modelLabel = (ov && ov.chatModelName) || 'gpt-4o';
  var baseUrl = (ov && ov.modelName) || '';
  var userText = extractCascadeUserText(req);
  if (!cascadeId || !userText) return false;
  runCustomModelTurn(origFetch, cascadeId, userText, modelLabel, baseUrl);
  return true;
}

function maybeInterceptCustomChatRpc(origFetch, path, bodyData) {
  if (!path || path.indexOf('SendUserCascadeMessage') === -1) return null;
  var text = extractBodyText(bodyData);
  if (!text) return null;
  try {
    var req = JSON.parse(text);
    if (!handleCustomCascadeReq(origFetch, req)) return null;
    return new Response('{}', {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    });
  } catch (e) {
    warn('maybeInterceptCustomChatRpc', e);
    return null;
  }
}

function installFetchRewrite() {
  if (global.__AG_FETCH_REWRITE__ || typeof global.fetch !== 'function') return;
  var origFetch = global.fetch.bind(global);
  global.__agHandleCustomCascadeMessage = function (req) {
    try {
      return handleCustomCascadeReq(origFetch, req);
    } catch (e) {
      warn('__agHandleCustomCascadeMessage', e);
      return false;
    }
  };

  function fetchWithStartupRetry(u, opts, retries) {
    return origFetch(u, opts).catch(function (err) {
      if (retries > 0) {
        return new Promise(function (resolve) {
          setTimeout(function () {
            resolve(fetchWithStartupRetry(u, opts, retries - 1));
          }, 120);
        });
      }
      throw err;
    });
  }

  global.fetch = function (resource, init) {
    try {
      // Request object: rebuild against the core origin, keep method/body.
      if (resource && typeof resource === 'object' && typeof resource.url === 'string') {
        var rpath = rpcPathFrom(resource.url);
        if (rpath) {
          try {
            var targetUrl = appendCsrfQuery(coreBase() + '/' + rpath);
            var reqMethod = resource.method || (init && init.method) || 'POST';
            var reqHeaders = new Headers(resource.headers || (init && init.headers) || {});
            ensureCsrf(reqHeaders);

            var bodyPromise;
            if (init && init.body !== undefined) {
              bodyPromise = Promise.resolve(init.body);
            } else {
              try {
                bodyPromise = resource.clone().arrayBuffer().then(function (buf) {
                  return buf.byteLength > 0 ? buf : undefined;
                }).catch(function () { return undefined; });
              } catch (e) {
                bodyPromise = Promise.resolve(undefined);
              }
            }

            return bodyPromise.then(function (bodyData) {
              var projectResp = interceptProjectRpc(rpath, bodyData);
              if (projectResp) return projectResp;

              var customChatResp = maybeInterceptCustomChatRpc(origFetch, rpath, bodyData);
              if (customChatResp) return customChatResp;

              var nextOpts = {
                method: reqMethod,
                headers: reqHeaders,
                body: bodyData,
                credentials: 'omit'
              };
              var p = fetchWithStartupRetry(targetUrl, nextOpts, 30);
              if (rpath.indexOf('StreamAgentStateUpdates') !== -1) {
                var bTxt = extractBodyText(bodyData);
                return p.then(function (resp) { return wrapAgentStateStreamResponse(resp, bTxt); });
              }
              return p;
            });
          } catch (e) { warn('fetch rewrite (Request)', e); }
        }
        return origFetch(resource, init);
      }

      // String URL: the Connect/grpc-web transport's common path.
      if (typeof resource === 'string') {
        var path = rpcPathFrom(resource);
        if (path) {
          var projectResp = interceptProjectRpc(path, init && init.body);
          if (projectResp) return Promise.resolve(projectResp);

          var customChatResp = maybeInterceptCustomChatRpc(origFetch, path, init && init.body);
          if (customChatResp) return Promise.resolve(customChatResp);

          var url = appendCsrfQuery(coreBase() + '/' + path);
          var headers = new Headers((init && init.headers) || {});
          ensureCsrf(headers);
          var next = {};
          if (init) {
            for (var k in init) {
              if (Object.prototype.hasOwnProperty.call(init, k)) next[k] = init[k];
            }
          }
          next.headers = headers;

          var p2 = fetchWithStartupRetry(url, next, 30);
          if (path.indexOf('StreamAgentStateUpdates') !== -1) {
            var bTxt2 = extractBodyText(init && init.body);
            return p2.then(function (resp) { return wrapAgentStateStreamResponse(resp, bTxt2); });
          }
          return p2;
        }
      }
    } catch (e) { warn('fetch rewrite', e); }
    return origFetch(resource, init);
  };
  global.__AG_FETCH_REWRITE__ = true;
}

installFetchRewrite();
