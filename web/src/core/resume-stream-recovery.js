/**
 * core/resume-stream-recovery.js - Recover ConnectRPC streaming calls
 * (StreamAgentStateUpdates, JetboxSubscribeToState, etc.) after Android
 * WebViewLifecycle.pause() calls webView.pauseTimers().
 */
var activeStreamClosers = new Set();
var lastResumeTs = 0;
var lastHostResumeTs = 0;
var pendingVisibilityTimer = null;

// Fast-forward the 1000ms reconnect backoff in Hca / $i right after a host resume
try {
  var origSetTimeout = global.setTimeout.bind(global);
  var origClearTimeout = global.clearTimeout.bind(global);
  global.setTimeout = function (fn, delay) {
    if (delay === 1000 && (Date.now() - lastResumeTs) < 3000) {
      delay = 40;
    }
    var args = Array.prototype.slice.call(arguments, 2);
    return origSetTimeout(function () {
      fn.apply(global, args);
    }, delay);
  };
} catch (e) { warn('setTimeout fast-forward', e); }

function triggerStreamRecovery(reason, force) {
  try {
    var now = Date.now();
    if (!force && (now - lastResumeTs) < 300) {
      return;
    }
    lastResumeTs = now;
    if (activeStreamClosers.size > 0) {
      console.log('[AGY bridge.core] ' + reason + ': rotating ' + activeStreamClosers.size + ' active stream(s)');
      var entries = Array.from(activeStreamClosers);
      entries.forEach(function (entry) {
        if (entry && entry.openedAt <= now) {
          activeStreamClosers.delete(entry);
          try { entry.close(); } catch (e) {}
        }
      });
    }
    try { global.dispatchEvent(new Event('online')); } catch (e) {}
    try { global.dispatchEvent(new Event('focus')); } catch (e) {}
  } catch (e) { warn('triggerStreamRecovery', e); }
}

try {
  if (typeof origAddEventListener === 'function') {
    origAddEventListener('agy:host-resume', function () {
      lastHostResumeTs = Date.now();
      if (pendingVisibilityTimer) {
        try { origClearTimeout(pendingVisibilityTimer); } catch (e) {}
        pendingVisibilityTimer = null;
      }
      // Always rotate streams that were open before WebViewLifecycle.resume() finished
      triggerStreamRecovery('agy:host-resume', true);
    });
  }
  if (global.document && global.document.addEventListener) {
    global.document.addEventListener('visibilitychange', function () {
      if (global.document.visibilityState === 'visible') {
        if (pendingVisibilityTimer) {
          try { origClearTimeout(pendingVisibilityTimer); } catch (e) {}
        }
        pendingVisibilityTimer = origSetTimeout(function () {
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
  return (s.indexOf('/exa.') !== -1 || s.indexOf('/jetski.') !== -1 || s.indexOf('Service/') !== -1)
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
  var rejectPendingRead = null;
  var closedByResume = false;
  var realReader = null;
  var entry = {
    openedAt: Date.now(),
    close: closeOnResume
  };

  function onCallerAbort() {
    activeStreamClosers.delete(entry);
    if (streamCtrl) {
      try { streamCtrl.abort(callerSignal && callerSignal.reason); } catch (e) {}
    }
    if (rejectPendingRead) {
      var r = rejectPendingRead;
      rejectPendingRead = null;
      try { r({ done: true, value: undefined }); } catch (e) {}
    }
  }

  function closeOnResume() {
    closedByResume = true;
    activeStreamClosers.delete(entry);
    if (rejectPendingRead) {
      var r = rejectPendingRead;
      rejectPendingRead = null;
      try { r({ done: true, value: undefined }); } catch (e) {}
    }
    if (realReader) {
      try { realReader.cancel('agy:host-resume'); } catch (e) {}
    }
    if (streamCtrl) {
      try { streamCtrl.abort(); } catch (e) {}
    }
  }

  if (callerSignal) {
    if (callerSignal.aborted) {
      onCallerAbort();
    } else if (callerSignal.addEventListener) {
      callerSignal.addEventListener('abort', onCallerAbort, { once: true });
    }
  }

  activeStreamClosers.add(entry);

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
      return response;
    }
    var realBody = response.body;
    var wrappedStream = new ReadableStream({
      start: function () {
        realReader = realBody.getReader();
      },
      pull: function (controller) {
        if (closedByResume) {
          controller.close();
          return Promise.resolve();
        }
        var resumeInterrupter = new Promise(function (resolve) {
          rejectPendingRead = resolve;
        });
        return Promise.race([realReader.read(), resumeInterrupter]).then(function (res) {
          rejectPendingRead = null;
          if (!res || res.done || closedByResume) {
            activeStreamClosers.delete(entry);
            controller.close();
          } else {
            controller.enqueue(res.value);
          }
        }).catch(function (err) {
          rejectPendingRead = null;
          activeStreamClosers.delete(entry);
          if (closedByResume) {
            controller.close();
          } else {
            controller.error(err);
          }
        });
      },
      cancel: function (reason) {
        rejectPendingRead = null;
        activeStreamClosers.delete(entry);
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
    activeStreamClosers.delete(entry);
    throw err;
  });
}
