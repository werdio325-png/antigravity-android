#!/usr/bin/env node
/**
 * sync_test.js - regression tests for patches/session_sync.
 *
 * Runs the real generated bundle in a sandbox with a fake WebSocket, fake clock
 * and fake reload, so the stale-state logic (resume, dead stream, reload budget,
 * duplicate prevention) is exercised without a device.
 *
 * These cover the logic of the fix only. Whether the engine actually resumes the
 * conversation after the reload is device behaviour (see docs/STATE_SYNC.md).
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const BUNDLE = path.join(__dirname, '..', 'generated', 'patches', 'session_sync.js');
const source = fs.readFileSync(BUNDLE, 'utf8');

function createHarness() {
  let now = 1000000;
  let timers = [];
  let timerSeq = 0;
  const reloads = [];

  function setTimeoutFake(fn, ms) {
    const id = ++timerSeq;
    timers.push({ id, at: now + (ms || 0), fn });
    return id;
  }
  function clearTimeoutFake(id) {
    timers = timers.filter((t) => t.id !== id);
  }
  function advance(ms) {
    const target = now + ms;
    let guard = 0;
    for (;;) {
      const due = timers.filter((t) => t.at <= target).sort((a, b) => a.at - b.at)[0];
      if (!due || guard++ > 200) break;
      timers = timers.filter((t) => t.id !== due.id);
      now = Math.max(now, due.at);
      due.fn();
    }
    now = target;
  }

  class FakeWebSocket {
    constructor(url) {
      this.url = url;
      this.listeners = {};
      FakeWebSocket.instances.push(this);
    }
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    }
    fire(type) {
      (this.listeners[type] || []).forEach((fn) => fn({ type }));
    }
  }
  FakeWebSocket.instances = [];
  FakeWebSocket.CONNECTING = 0;
  FakeWebSocket.OPEN = 1;
  FakeWebSocket.CLOSING = 2;
  FakeWebSocket.CLOSED = 3;

  const documentStub = {
    visibilityState: 'visible',
    listeners: {},
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
    dispatchEvent() {}
  };

  const sandbox = {
    console: { info() {}, warn() {}, log() {}, error() {} },
    setTimeout: setTimeoutFake,
    clearTimeout: clearTimeoutFake,
    setInterval: () => 0,
    clearInterval() {},
    Date: { now: () => now },
    CustomEvent: class CustomEvent { constructor(type, opts) { this.type = type; this.detail = (opts || {}).detail; } },
    Event: class Event { constructor(type) { this.type = type; } },
    document: documentStub,
    location: { reload: () => reloads.push(now) },
    WebSocket: FakeWebSocket,
    navigator: { onLine: true },
    listeners: {},
    addEventListener(type, fn) {
      (this.listeners[type] = this.listeners[type] || []).push(fn);
    },
    dispatchEvent(type) {
      const event = typeof type === 'string' ? { type } : type;
      (this.listeners[event.type] || []).forEach((fn) => fn(event));
    }
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;

  vm.createContext(sandbox);
  vm.runInContext(source, sandbox, { filename: 'session_sync.js' });

  return {
    sandbox,
    FakeWebSocket,
    reloads,
    advance,
    get sync() {
      return sandbox.AgySync;
    }
  };
}

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

test('installs diagnostics API', () => {
  const h = createHarness();
  assert.ok(h.sync, 'AgySync should exist');
  const state = h.sync.state();
  assert.strictEqual(state.everOpened, false);
  assert.strictEqual(state.streamAlive, false);
});

test('resume without any stream is ignored (auth/setup pages)', () => {
  const h = createHarness();
  const result = h.sync.resume(60000);
  assert.strictEqual(result.action, 'not-streaming');
  h.advance(30000);
  assert.strictEqual(h.reloads.length, 0);
});

test('short background with a live stream is ignored', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  socket.fire('open');
  const result = h.sync.resume(500);
  assert.strictEqual(result.action, 'ignored');
  h.advance(30000);
  assert.strictEqual(h.reloads.length, 0);
});

test('resume with a live stream nudges but never reloads', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  socket.fire('open');
  const result = h.sync.resume(12000);
  assert.strictEqual(result.action, 'nudged');
  h.advance(60000);
  assert.strictEqual(h.reloads.length, 0, 'must not reload while the stream is alive');
});

test('resume with a dead stream reconciles once (stale conversation fix)', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  socket.fire('open');
  socket.fire('close');
  const result = h.sync.resume(45000);
  assert.strictEqual(result.action, 'reconnecting');
  assert.strictEqual(h.reloads.length, 0, 'reload must wait for the reconnect grace period');
  h.advance(1500);
  assert.strictEqual(h.reloads.length, 1, 'stale view must be reconciled from the engine');
});

test('a stream that reconnects on its own prevents a reload', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  socket.fire('open');
  socket.fire('close');
  h.sync.resume(45000);
  const second = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  second.fire('open');
  h.advance(5000);
  assert.strictEqual(h.reloads.length, 0);
});

test('repeated resumes inside the cooldown do not reload twice', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  socket.fire('open');
  socket.fire('close');
  h.sync.resume(20000);
  h.advance(1500);
  assert.strictEqual(h.reloads.length, 1);
  h.sync.resume(20000);
  h.advance(1500);
  assert.strictEqual(h.reloads.length, 1, 'duplicate reconciliation must be suppressed');
});

test('a stream that stays closed while visible triggers the watchdog', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  socket.fire('open');
  socket.fire('close');
  h.advance(12000);
  assert.strictEqual(h.reloads.length, 1, 'watchdog should reconcile without a resume');
});

test('reload budget stops a loop when the engine is down', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws');
  socket.fire('open');
  socket.fire('close');
  for (let i = 0; i < 6; i++) {
    h.sync.resume(20000);
    h.advance(40000);
  }
  assert.strictEqual(h.reloads.length, 3, 'reloads must be capped');
});

test('socket observer preserves the native constructor semantics', () => {
  const h = createHarness();
  const socket = new h.sandbox.WebSocket('wss://127.0.0.1/ws', ['proto']);
  assert.ok(socket instanceof h.FakeWebSocket, 'wrapper must return the real socket');
  assert.strictEqual(h.sandbox.WebSocket.OPEN, 1, 'constants must be preserved');
  assert.ok(h.sandbox.WebSocket.prototype === h.FakeWebSocket.prototype,
    'prototype must be preserved');
});

let failed = 0;
for (const t of tests) {
  try {
    t.fn();
    console.log('  ok   ' + t.name);
  } catch (error) {
    failed++;
    console.error('  FAIL ' + t.name + ': ' + error.message);
  }
}
console.log(`session_sync: ${tests.length - failed}/${tests.length} passed`);
process.exit(failed === 0 ? 0 : 1);
