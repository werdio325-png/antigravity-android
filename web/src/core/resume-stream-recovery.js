/**
 * core/resume-stream-recovery.js - Recover ConnectRPC streaming calls
 * (StreamAgentStateUpdates, JetboxSubscribeToSummaries, etc.) after Android
 * WebViewLifecycle.pause() calls webView.pauseTimers().
 */
var activeStreamClosers = new Set();
var lastResumeTs = 0;
var lastHostResumeTs = 0;
var pendingVisibilityTimer = null;

function triggerStreamRecovery(reason, force) {
  try {
    var now = Date.now();
    if (!force && (now - lastResumeTs) < 300) {
      return;
    }
    lastResumeTs = now;
    var toRotate = [];
    activeStreamClosers.forEach(function (entry) {
      if (entry && entry.openedAt <= now) {
        toRotate.push(entry);
      }
    });
    if (toRotate.length > 0) {
      try {
        console.log('[AGY bridge.core] ' + reason + ': rotating ' + toRotate.length + ' active stream(s)');
      } catch (e) {}
      for (var i = 0; i < toRotate.length; i++) {
        var entry = toRotate[i];
        activeStreamClosers.delete(entry);
        try { entry.close(); } catch (e) {}
      }
    }
  } catch (e) { warn('triggerStreamRecovery', e); }
}

try {
  if (typeof global.addEventListener === 'function') {
    global.addEventListener('agy:host-resume', function () {
      lastHostResumeTs = Date.now();
      if (pendingVisibilityTimer !== null) {
        try { clearTimeout(pendingVisibilityTimer); } catch (e) {}
        pendingVisibilityTimer = null;
      }
      triggerStreamRecovery('agy:host-resume', true);
    });
  }
  if (global.document && typeof global.document.addEventListener === 'function') {
    global.document.addEventListener('visibilitychange', function () {
      if (global.document.visibilityState === 'visible') {
        if (pendingVisibilityTimer !== null) {
          try { clearTimeout(pendingVisibilityTimer); } catch (e) {}
        }
        pendingVisibilityTimer = setTimeout(function () {
          pendingVisibilityTimer = null;
          if ((Date.now() - lastHostResumeTs) > 500) {
            triggerStreamRecovery('visibilitychange:visible', false);
          }
        }, 150);
      }
    });
  }
} catch (e) { warn('resume listeners', e); }

function isStreamingRpc(pathOrUrl) {
  if (!pathOrUrl) return false;
  var s = String(pathOrUrl);
  return (s.indexOf('/exa.') !== -1 || s.indexOf('exa.') === 0
      || s.indexOf('/jetski.') !== -1 || s.indexOf('jetski.') === 0
      || s.indexOf('Service/') !== -1)
      && (s.indexOf('Stream') !== -1 || s.indexOf('Subscribe') !== -1);
}

function isLoopbackRpcUrl(url) {
  if (!url) return false;
  var s = String(url);
  return /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//i.test(s) && isStreamingRpc(s);
}

function wrapStreamingFetch(origFetch, url, opts) {
  var callerSignal = (opts && opts.signal) || (url && typeof url === 'object' && url.signal) || null;
  var streamCtrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var resolvePendingRead = null;
  var closedByResume = false;
  var realReader = null;
  var cleanedUp = false;
  var entry = {
    openedAt: Date.now(),
    close: closeOnResume
  };

  function cleanup() {
    if (cleanedUp) return;
    cleanedUp = true;
    activeStreamClosers.delete(entry);
    if (callerSignal && typeof callerSignal.removeEventListener === 'function') {
      try { callerSignal.removeEventListener('abort', onCallerAbort); } catch (e) {}
    }
  }

  function resolveInterrupter() {
    if (resolvePendingRead) {
      var resolve = resolvePendingRead;
      resolvePendingRead = null;
      try { resolve({ done: true, value: undefined }); } catch (e) {}
    }
  }

  function onCallerAbort() {
    cleanup();
    if (streamCtrl) {
      try { streamCtrl.abort(callerSignal && callerSignal.reason); } catch (e) {}
    }
    resolveInterrupter();
  }

  function closeOnResume() {
    closedByResume = true;
    cleanup();
    resolveInterrupter();
    if (realReader) {
      try {
        Promise.resolve(realReader.cancel('agy:host-resume')).catch(function () {});
      } catch (e) {}
    }
    if (streamCtrl) {
      try { streamCtrl.abort(); } catch (e) {}
    }
  }

  if (callerSignal && callerSignal.aborted) {
    onCallerAbort();
  } else {
    if (callerSignal && typeof callerSignal.addEventListener === 'function') {
      callerSignal.addEventListener('abort', onCallerAbort, { once: true });
    }
    activeStreamClosers.add(entry);
  }

  var fetchOpts = {};
  if (opts) {
    for (var k in opts) {
      if (Object.prototype.hasOwnProperty.call(opts, k)) fetchOpts[k] = opts[k];
    }
  }
  if (streamCtrl) {
    fetchOpts.signal = streamCtrl.signal;
  }

  return origFetch(url, fetchOpts).then(function (response) {
    if (!response || !response.body || typeof response.body.getReader !== 'function' || typeof ReadableStream === 'undefined') {
      cleanup();
      return response;
    }
    var realBody = response.body;
    var wrappedStream = new ReadableStream({
      start: function () {
        realReader = realBody.getReader();
      },
      pull: function (controller) {
        if (closedByResume) {
          if (realReader) {
            try {
              Promise.resolve(realReader.cancel('agy:host-resume')).catch(function () {});
            } catch (e) {}
          }
          cleanup();
          controller.close();
          return Promise.resolve();
        }
        var readPromise = realReader.read();
        // Suppress unhandled rejection if resumeInterrupter wins the race first.
        readPromise.catch(function () {});
        var resumeInterrupter = new Promise(function (resolve) {
          resolvePendingRead = resolve;
        });
        return Promise.race([readPromise, resumeInterrupter]).then(function (res) {
          resolvePendingRead = null;
          if (!res || res.done || closedByResume) {
            cleanup();
            controller.close();
          } else {
            controller.enqueue(res.value);
          }
        }).catch(function (err) {
          resolvePendingRead = null;
          cleanup();
          if (closedByResume) {
            controller.close();
          } else {
            controller.error(err);
          }
        });
      },
      cancel: function (reason) {
        resolvePendingRead = null;
        cleanup();
        if (realReader) {
          try { return realReader.cancel(reason); } catch (e) {}
        }
      }
    });

    try {
      return new Response(wrappedStream, {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers
      });
    } catch (e) {
      try {
        Object.defineProperty(response, 'body', {
          get: function () { return wrappedStream; },
          configurable: true
        });
      } catch (e2) {}
      return response;
    }
  }).catch(function (err) {
    cleanup();
    throw err;
  });
}
