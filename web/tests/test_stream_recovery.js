'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const bundlePath = path.resolve(__dirname, '..', 'generated', 'bridge.core.js');
const bundleCode = fs.readFileSync(bundlePath, 'utf8');

const unhandledRejections = [];
process.on('unhandledRejection', (err) => {
  unhandledRejections.push(err);
});

function createSandbox(fetchImpl) {
  const winTarget = new EventTarget();
  const docTarget = new EventTarget();

  const documentObj = {
    visibilityState: 'visible',
    cookie: '',
    querySelector: () => null,
    addEventListener: docTarget.addEventListener.bind(docTarget),
    removeEventListener: docTarget.removeEventListener.bind(docTarget),
    dispatchEvent: docTarget.dispatchEvent.bind(docTarget)
  };

  const sandbox = {
    __APP_CONFIG__: { enginePort: 45157, csrfToken: 'test-csrf' },
    __AG_ENDPOINTS__: { enginePort: '45157' },
    location: { search: '' },
    document: documentObj,
    fetch: fetchImpl,
    Headers,
    Response,
    ReadableStream,
    AbortController,
    Event,
    URLSearchParams,
    setTimeout,
    clearTimeout,
    console: {
      log: () => {},
      warn: (...args) => console.warn(...args),
      error: (...args) => console.error(...args)
    },
    Set,
    Array,
    Promise,
    Date,
    Object,
    RegExp,
    Error,
    TypeError,
    String,
    encodeURIComponent,
    decodeURIComponent
  };

  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.addEventListener = winTarget.addEventListener.bind(winTarget);
  sandbox.removeEventListener = winTarget.removeEventListener.bind(winTarget);
  sandbox.dispatchEvent = winTarget.dispatchEvent.bind(winTarget);

  vm.createContext(sandbox);
  vm.runInContext(bundleCode, sandbox, { filename: 'bridge.core.js' });
  return sandbox;
}

function makeStreamingResponse(onCancel) {
  let sentFirst = false;
  let canceled = false;
  const body = new ReadableStream({
    pull(controller) {
      if (!sentFirst) {
        sentFirst = true;
        controller.enqueue(new Uint8Array([1, 2, 3]));
        return;
      }
      // Keep stream open until aborted or canceled.
      return new Promise(() => {});
    },
    cancel(reason) {
      canceled = true;
      if (onCancel) onCancel(reason);
    }
  });
  return {
    response: new Response(body, { status: 200 }),
    isCanceled: () => canceled
  };
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function run() {
  // 1. Rewritten + direct loopback streaming RPCs deliver chunks and rotate cleanly on agy:host-resume.
  {
    const seenUrls = [];
    const cancelFlags = [];

    const sandbox = createSandbox((url, opts) => {
      seenUrls.push(String(url));
      const { response, isCanceled } = makeStreamingResponse();
      cancelFlags.push({ isCanceled, signal: opts && opts.signal });
      return Promise.resolve(response);
    });

    const r1 = await sandbox.fetch('file://exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates', {
      method: 'POST'
    });
    const r2 = await sandbox.fetch('https://127.0.0.1:45157/jetski.JetboxService/JetboxSubscribeToSummaries', {
      method: 'POST'
    });

    assert.ok(seenUrls[0].startsWith('https://127.0.0.1:45157/exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates'));
    assert.strictEqual(seenUrls[1], 'https://127.0.0.1:45157/jetski.JetboxService/JetboxSubscribeToSummaries');

    const reader1 = r1.body.getReader();
    const reader2 = r2.body.getReader();

    const c1 = await reader1.read();
    const c2 = await reader2.read();
    assert.deepStrictEqual(Array.from(c1.value), [1, 2, 3]);
    assert.deepStrictEqual(Array.from(c2.value), [1, 2, 3]);

    // Start pending reads, then fire agy:host-resume.
    const next1 = reader1.read();
    const next2 = reader2.read();
    sandbox.dispatchEvent(new Event('agy:host-resume'));

    const end1 = await next1;
    const end2 = await next2;
    assert.strictEqual(end1.done, true);
    assert.strictEqual(end2.done, true);
    assert.strictEqual(cancelFlags[0].signal.aborted, true);
    assert.strictEqual(cancelFlags[1].signal.aborted, true);
    await sleep(20);
    assert.strictEqual(cancelFlags[0].isCanceled(), true);
    assert.strictEqual(cancelFlags[1].isCanceled(), true);
  }

  // 2. visibilitychange fallback: no double-rotate after agy:host-resume; rotates standalone after >500ms.
  {
    const sandbox = createSandbox(() => Promise.resolve(makeStreamingResponse().response));

    // Order A: visibilitychange fires first, then agy:host-resume cancels pending timer.
    sandbox.document.visibilityState = 'visible';
    sandbox.document.dispatchEvent(new Event('visibilitychange'));
    await sleep(20);
    sandbox.dispatchEvent(new Event('agy:host-resume'));

    // Open a fresh stream immediately after resume (simulating reconnect).
    const freshAfterResume = await sandbox.fetch('https://127.0.0.1:45157/jetski.JetboxService/JetboxSubscribeToSummaries');
    const freshReader = freshAfterResume.body.getReader();
    const firstChunk = await freshReader.read();
    assert.deepStrictEqual(Array.from(firstChunk.value), [1, 2, 3]);

    // Order B: visibilitychange fires right after agy:host-resume -> must NOT close fresh stream.
    sandbox.document.dispatchEvent(new Event('visibilitychange'));
    let closedUnexpectedly = false;
    const pendingRead = freshReader.read().then((res) => {
      if (res.done) closedUnexpectedly = true;
    });
    await sleep(220);
    assert.strictEqual(closedUnexpectedly, false, 'visibilitychange must not double-rotate right after agy:host-resume');

    // Wait past the 500ms suppression window, then verify standalone visibilitychange rotates after ~150ms.
    await sleep(350);
    sandbox.document.dispatchEvent(new Event('visibilitychange'));
    await pendingRead;
    assert.strictEqual(closedUnexpectedly, true, 'standalone visibilitychange:visible must rotate stream');
  }

  // 3. Aborted RPC fails immediately (no 30x retry) and Request.signal is preserved.
  {
    let fetchCalls = 0;
    let capturedSignal = null;

    const sandbox = createSandbox((url, opts) => {
      fetchCalls++;
      capturedSignal = opts && opts.signal;
      if (url.indexOf('AbortImmediately') !== -1 || url.indexOf('GetUserStatus') !== -1) {
        const err = new Error('The operation was aborted');
        err.name = 'AbortError';
        return Promise.reject(err);
      }
      return Promise.resolve(makeStreamingResponse().response);
    });

    // 3a: Immediate AbortError must not trigger 30x retry.
    const abortReq = {
      url: 'file://exa.language_server_pb.LanguageServerService/StreamAbortImmediately',
      method: 'POST',
      headers: new Headers(),
      signal: new AbortController().signal,
      clone() {
        return {
          arrayBuffer: () => Promise.resolve(new ArrayBuffer(0))
        };
      }
    };
    await assert.rejects(
      () => sandbox.fetch(abortReq),
      (err) => err && err.name === 'AbortError'
    );
    assert.strictEqual(fetchCalls, 1, 'AbortError must not trigger startup retries');

    // 3b: Request.signal abort on an active rewritten stream propagates to inner signal.
    fetchCalls = 0;
    capturedSignal = null;
    const callerCtrl = new AbortController();
    const activeReq = {
      url: 'file://exa.language_server_pb.LanguageServerService/StreamAgentStateUpdates',
      method: 'POST',
      headers: new Headers(),
      signal: callerCtrl.signal,
      clone() {
        return {
          arrayBuffer: () => Promise.resolve(new ArrayBuffer(0))
        };
      }
    };
    const activeRes = await sandbox.fetch(activeReq);
    assert.strictEqual(fetchCalls, 1);
    assert.ok(capturedSignal, 'Request.signal must be wired through fetch rewrite');
    const activeReader = activeRes.body.getReader();
    await activeReader.read();
    const pendingActiveRead = activeReader.read();
    callerCtrl.abort();
    assert.strictEqual(capturedSignal.aborted, true, 'Caller abort must propagate through wrapped stream signal');
    const abortedEnd = await pendingActiveRead;
    assert.strictEqual(abortedEnd.done, true);

    // Also test pre-aborted unary Request keeps exact Request.signal and fails after 1 call.
    fetchCalls = 0;
    capturedSignal = null;
    const preAbortedCtrl = new AbortController();
    preAbortedCtrl.abort();
    const preAbortedReq = {
      url: 'file://exa.language_server_pb.LanguageServerService/GetUserStatus',
      method: 'POST',
      headers: new Headers(),
      signal: preAbortedCtrl.signal,
      clone() {
        return {
          arrayBuffer: () => Promise.resolve(new ArrayBuffer(0))
        };
      }
    };
    await assert.rejects(() => sandbox.fetch(preAbortedReq));
    assert.strictEqual(fetchCalls, 1, 'Pre-aborted Request must fail after 1 call');
    assert.strictEqual(capturedSignal, preAbortedCtrl.signal, 'Unary Request.signal must be preserved');
  }

  // 4. Non-streaming unary RPCs are untouched by agy:host-resume.
  {
    let unaryCanceled = false;
    let unarySignal = null;
    const sandbox = createSandbox((url, opts) => {
      unarySignal = opts && opts.signal;
      const body = new ReadableStream({
        pull() {
          return new Promise(() => {});
        },
        cancel() {
          unaryCanceled = true;
        }
      });
      return Promise.resolve(new Response(body, { status: 200 }));
    });

    const res = await sandbox.fetch('https://127.0.0.1:45157/exa.language_server_pb.LanguageServerService/GetUserStatus', {
      method: 'POST'
    });
    sandbox.dispatchEvent(new Event('agy:host-resume'));
    await sleep(50);
    assert.strictEqual(unaryCanceled, false, 'Unary RPC body must not be canceled on resume');
    assert.strictEqual(unarySignal, undefined, 'Unary RPC must not be wrapped with stream AbortController');
    await res.body.cancel();
  }

  await sleep(20);
  assert.deepStrictEqual(unhandledRejections, [], 'Expected zero unhandled rejections');
  console.log('test_stream_recovery: OK');
}

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
