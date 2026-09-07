#!/usr/bin/env node
// Deterministic worker lifecycle tests: no WASM download or real-time sleeps.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.join(__dirname, '../app/src/main/assets/ide');
const script = fs.readFileSync(path.join(root, 'cpython.js'), 'utf8');
function harness(throws = false) {
  const workers = [], timers = new Map(), chip = {};
  let seq = 0;
  class Worker {
    constructor() { if (throws) throw Error('unsupported'); this.messages = []; workers.push(this); }
    postMessage(m) { this.messages.push(m); }
    terminate() { this.terminated = true; }
    emit(data) { if (this.onmessage) this.onmessage({ data }); }
  }
  const context = { Worker, console: { error() {} }, document: { getElementById: () => chip },
    setTimeout(fn, ms) { timers.set(++seq, { fn, ms }); return seq; },
    clearTimeout(id) { timers.delete(id); }, crossOriginIsolated: false };
  context.window = context; context.self = context;
  vm.runInNewContext(script, context);
  return { cp: context.JadexCPython, workers, timers, chip };
}
(async () => {
  const h = harness();
  assert.equal(h.cp.state, 'idle');
  assert.equal(h.workers.length, 0);
  assert.equal(h.timers.size, 0);
  console.log('ok  opening the editor starts no worker or warmup timer');
  const first = h.cp.ensure();
  assert.equal(h.cp.ensure(), first);
  assert.equal(h.workers.length, 1);
  assert.equal(h.chip.textContent, 'Loading CPython…');
  h.workers[0].emit({ type: 'ready', version: '3.12.1' });
  assert.equal(await first, true);
  assert.equal(h.timers.size, 0);
  assert.equal(await h.cp.ensure(), true);
  assert.equal(h.workers.length, 1);
  const run = h.cp.run('print(1)', {}, () => {});
  h.workers[0].emit({ type: 'done', runId: 999 });
  assert.equal(h.cp.busy(), true);
  h.workers[0].emit({ type: 'done', runId: 1 });
  await run;
  assert.equal(h.cp.busy(), false);
  console.log('ok  first Run shares one boot; warm Runs reuse it; stale replies ignored');
  const crashedRun = h.cp.run('print(2)', {}, () => {});
  h.workers[0].onerror({ message: 'WASM crashed' });
  assert.match((await crashedRun).error, /stopped unexpectedly/);
  assert.equal(h.cp.state, 'failed');
  assert.equal(h.cp.busy(), false);
  assert.equal(h.cp.threaded, false);
  assert.equal(h.workers[0].terminated, true);
  console.log('ok  runtime crash releases Run without replaying side effects');
  const retry = h.cp.ensure();
  h.workers[1].emit({ type: 'bootfail', error: 'bad WASM' });
  assert.equal(await retry, false);
  assert.equal(h.workers[1].terminated, true);
  assert.equal(h.chip.textContent, 'Subset · tap Run again for CPython');
  const slow = h.cp.ensure();
  const timer = [...h.timers.values()][0];
  assert.equal(timer.ms, 12000);
  timer.fn();
  assert.equal(await slow, false);
  assert.equal(h.workers[2].terminated, true);
  const recovered = h.cp.ensure();
  h.workers[3].emit({ type: 'ready', version: '3.12.1' });
  assert.equal(await recovered, true);
  console.log('ok  failed/slow boots terminate, fall back, and retry successfully');
  const unsupported = harness(true);
  assert.equal(await unsupported.cp.ensure(), false);
  assert.equal(unsupported.cp.state, 'failed');
  console.log('ok  unsupported Worker falls back without crashing UI');
  const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
  const app = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
  assert.ok(!html.includes('id="splash"'));
  assert.ok(html.includes('by Troy'));
  assert.ok(!app.includes('.prefetch('));
  assert.ok(app.includes('cp.run(source, ws,'));
  console.log('ok  no startup overlay/warmup; brand and Run source snapshot retained');
  // Exercise the actual app Run function while the user continues editing.
  let finishBoot;
  const output = [], executions = [];
  const cp = { ready: false, state: 'loading',
    ensure: () => new Promise(resolve => { finishBoot = resolve; }),
    run: async (src, files) => { executions.push({ src, files }); return {}; } };
  const ui = { window: { JadexCPython: cp }, saveFiles() {}, _logQueue: [],
    consoleEl: {}, debugLog: null, files: { 'main.py': 'print(1)' }, current: 'main.py',
    code: { value: 'print(1)' }, log: text => output.push(text), setBusy() {},
    TroyPython: { run: src => { executions.push({ src, subset: true }); } } };
  const start = app.indexOf('  async function realRun(');
  const end = app.indexOf('  document.getElementById("btn-run").onclick = function', start);
  vm.runInNewContext(app.slice(start, end), ui);
  let completed = 0;
  const pending = ui.realRun(false, () => completed++);
  assert.match(output.join(''), /almost ready/);
  ui.code.value = 'print(2)'; // typing remains independent of the pending run
  ui.current = 'other.py';
  cp.ready = true;
  finishBoot(true);
  await pending;
  assert.equal(executions[0].src, 'print(1)');
  assert.equal(executions[0].files['main.py'], 'print(1)');
  assert.equal(ui.code.value, 'print(2)');
  assert.equal(completed, 1);
  cp.ready = false;
  cp.state = 'failed';
  cp.ensure = async () => false;
  await ui.realRun(false, () => completed++);
  assert.equal(executions[1].subset, true);
  assert.equal(completed, 2);
  assert.match(output.join(''), /subset engine/);
  console.log('ok  Run snapshots code while editing; failed boot executes subset and completes');

})().catch(err => { console.error(err); process.exitCode = 1; });
