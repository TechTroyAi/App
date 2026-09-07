#!/usr/bin/env node
/*
 * Verifies the parts of the worker contract that do not need a browser:
 *   1. the SharedArrayBuffer stdin handshake actually unblocks a waiting thread
 *   2. the interrupt flag is visible across threads
 *   3. traceback parsing maps CPython errors back to editor line numbers
 *
 * Node's worker_threads uses the same Atomics semantics as the browser, so a
 * pass here means the blocking input() round-trip is sound.
 */
const assert = require("assert");
const { Worker, isMainThread, workerData, parentPort } = require("worker_threads");

// ---- the exact routines used by pyworker.js / cpython.js -------------------
function readStdinSync(stdinBuffer) {
  const ctl = new Int32Array(stdinBuffer, 0, 2);
  Atomics.store(ctl, 0, 0);
  Atomics.wait(ctl, 0, 0);
  const len = Atomics.load(ctl, 1);
  if (len <= 0) return "";
  const chars = new Uint16Array(stdinBuffer, 8, len);
  let out = "";
  for (let i = 0; i < len; i++) out += String.fromCharCode(chars[i]);
  return out;
}

function answerStdin(stdinBuffer, text) {
  const ctl = new Int32Array(stdinBuffer, 0, 2);
  let s = String(text == null ? "" : text);
  const max = (stdinBuffer.byteLength - 8) / 2;
  if (s.length > max) s = s.slice(0, max);
  const chars = new Uint16Array(stdinBuffer, 8, s.length);
  for (let i = 0; i < s.length; i++) chars[i] = s.charCodeAt(i);
  Atomics.store(ctl, 1, s.length);
  Atomics.store(ctl, 0, 1);
  Atomics.notify(ctl, 0);
}

function formatError(err) {
  const text = err && err.message ? err.message : String(err);
  let line = 0;
  const re = /File "(?:\/)?_jadex_run\.py", line (\d+)/g;
  let m;
  while ((m = re.exec(text)) !== null) line = parseInt(m[1], 10);
  if (/KeyboardInterrupt/.test(text)) {
    return { text: "KeyboardInterrupt — stopped\n", line, interrupted: true };
  }
  return { text: text.replace(/^PythonError:\s*/, "") + "\n", line, interrupted: false };
}

if (!isMainThread) {
  const { stdinBuffer, interruptBuffer } = workerData;
  const got = readStdinSync(stdinBuffer);          // blocks until main answers
  const flag = new Uint8Array(interruptBuffer)[0]; // set by main before notify
  parentPort.postMessage({ got, flag });
  return;
}

(async function main() {
  // 1 + 2: blocking stdin across a real thread boundary, plus interrupt visibility
  const stdinBuffer = new SharedArrayBuffer(8 + 4096 * 2);
  const interruptBuffer = new SharedArrayBuffer(1);
  const w = new Worker(__filename, { workerData: { stdinBuffer, interruptBuffer } });

  const reply = await new Promise((resolve, reject) => {
    w.once("message", resolve);
    w.once("error", reject);
    setTimeout(() => {
      new Uint8Array(interruptBuffer)[0] = 2;
      answerStdin(stdinBuffer, "Troy");
    }, 60);
    setTimeout(() => reject(new Error("worker never woke: Atomics handshake broken")), 4000);
  });
  await w.terminate();

  assert.strictEqual(reply.got, "Troy", "stdin round-trip");
  assert.strictEqual(reply.flag, 2, "interrupt flag visible cross-thread");
  console.log("ok  stdin handshake unblocks the worker");
  console.log("ok  interrupt flag is visible across threads");

  // truncation must not corrupt the buffer
  const big = "x".repeat(9000);
  const w2 = new Worker(__filename, { workerData: { stdinBuffer, interruptBuffer } });
  const r2 = await new Promise((resolve, reject) => {
    w2.once("message", resolve);
    w2.once("error", reject);
    setTimeout(() => answerStdin(stdinBuffer, big), 60);
    setTimeout(() => reject(new Error("timeout on oversized stdin")), 4000);
  });
  await w2.terminate();
  assert.strictEqual(r2.got.length, 4096, "oversized stdin clamps to buffer capacity");
  console.log("ok  oversized stdin is clamped, not corrupting");

  // 3: traceback -> editor line
  const tb = formatError({
    message:
      'PythonError: Traceback (most recent call last):\n' +
      '  File "/_jadex_run.py", line 3, in <module>\n' +
      '  File "/_jadex_run.py", line 7, in boom\n' +
      "ZeroDivisionError: division by zero",
  });
  assert.strictEqual(tb.line, 7, "uses the deepest user frame");
  assert.strictEqual(tb.interrupted, false);
  assert.ok(!/^PythonError:/.test(tb.text), "PythonError prefix stripped");
  console.log("ok  traceback maps to the deepest user line (7)");

  const ki = formatError({ message: "PythonError: KeyboardInterrupt" });
  assert.strictEqual(ki.interrupted, true, "KeyboardInterrupt flagged as a stop");
  console.log("ok  KeyboardInterrupt reported as a stop, not a crash");

  console.log("\nall worker-protocol checks passed");
})().catch((e) => {
  console.error("FAIL:", e.message);
  process.exit(1);
});
