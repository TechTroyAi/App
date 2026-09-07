(function () {
  // Jadex · lazy CPython, running in a worker.
  //
  // The editor never waits for WASM, and WASM never blocks the editor: Python
  // lives on a worker thread and the UI thread only ever posts messages. A
  // SharedArrayBuffer carries the interrupt flag, which is what makes Stop able
  // to break a `while True:`.
  //
  // State: "idle" -> "loading" -> "ready" | "failed"
  var state = "idle";
  var bootPromise = null;
  var worker = null;
  var interruptBuffer = null;
  var stdinBuffer = null;
  var runSeq = 0;
  var active = null;      // { runId, resolve, onPrint }

  var isolated = (typeof SharedArrayBuffer !== "undefined") && self.crossOriginIsolated !== false;

  window.jadexStdinQueue = [];
  window.JadexCPython = {
    ready: false,
    engine: "subset",
    threaded: false,
    get state() { return state; },
    get isolated() { return isolated; }
  };

  function chip(text, cls) {
    var el = document.getElementById("status-lang");
    if (!el) return;
    el.textContent = text;
    el.className = cls || "jade";
  }

  function answerStdin(text) {
    if (!stdinBuffer) return;
    var ctl = new Int32Array(stdinBuffer, 0, 2);
    var s = String(text == null ? "" : text);
    var max = (stdinBuffer.byteLength - 8) / 2;
    if (s.length > max) s = s.slice(0, max);
    var chars = new Uint16Array(stdinBuffer, 8, s.length);
    for (var i = 0; i < s.length; i++) chars[i] = s.charCodeAt(i);
    Atomics.store(ctl, 1, s.length);
    Atomics.store(ctl, 0, 1);
    Atomics.notify(ctl, 0);   // unblock the worker's Atomics.wait
  }

  function handle(msg) {
    if (msg.type === "out") {
      if (active && active.onPrint) active.onPrint(msg.text, msg.stream);
      return;
    }
    if (msg.type === "plot") {
      try {
        var data = JSON.parse(msg.data);
        if (window.JadexStudio && window.JadexStudio.plot) window.JadexStudio.plot(data);
      } catch (e) {}
      return;
    }
    if (msg.type === "stdin") {
      // Prefer queued stdin; otherwise ask, but never block the UI thread.
      var v;
      if (window.jadexStdinQueue && window.jadexStdinQueue.length) v = window.jadexStdinQueue.shift();
      else v = window.prompt(msg.prompt || "input: ", "");
      answerStdin(v == null ? "" : v);
      return;
    }
    if (msg.type === "done") {
      var a = active;
      active = null;
      if (a && a.resolve) a.resolve(msg);
      return;
    }
  }

  function spawn() {
    return new Promise(function (resolve, reject) {
      var w;
      try { w = new Worker("pyworker.js"); }
      catch (e) { reject(e); return; }
      var settled = false;
      w.onmessage = function (e) {
        var msg = e.data || {};
        if (!settled && msg.type === "ready") {
          settled = true;
          worker = w;
          resolve(msg.version);
          return;
        }
        if (!settled && msg.type === "bootfail") {
          settled = true;
          reject(new Error(msg.error || "boot failed"));
          return;
        }
        handle(msg);
      };
      w.onerror = function (err) {
        if (!settled) { settled = true; reject(new Error(err.message || "worker error")); }
      };
      if (isolated) {
        interruptBuffer = new Uint8Array(new SharedArrayBuffer(1));
        stdinBuffer = new SharedArrayBuffer(8 + 4096 * 2);
        w.postMessage({ type: "interrupt-buffer", buffer: interruptBuffer, stdin: stdinBuffer });
      }
      w.postMessage({ type: "boot" });
    });
  }

  window.JadexCPython.ensure = function () {
    if (state === "ready") return Promise.resolve(true);
    if (bootPromise) return bootPromise;
    state = "loading";
    chip("Loading CPython…", "jade loading");
    bootPromise = spawn().then(function (version) {
      state = "ready";
      window.JadexCPython.ready = true;
      window.JadexCPython.engine = "cpython";
      window.JadexCPython.threaded = true;
      window.JadexCPython.version = version;
      chip("CPython " + String(version).split(" ")[0] + (isolated ? "" : " · no stop"), "jade");
      return true;
    }).catch(function (err) {
      console.error("CPython boot failed", err);
      state = "failed";
      bootPromise = null;              // next Run is a genuine retry
      window.JadexCPython.ready = false;
      window.JadexCPython.engine = "subset";
      chip("Subset · tap Run again for CPython", "jade warn");
      return false;
    });
    return bootPromise;
  };

  window.JadexCPython.prefetch = function (delay) {
    var start = function () { window.JadexCPython.ensure(); };
    var go = function () {
      if (window.requestIdleCallback) window.requestIdleCallback(start, { timeout: 4000 });
      else setTimeout(start, 0);
    };
    setTimeout(go, delay == null ? 1200 : delay);
  };

  function dispatch(type, payload, onPrint) {
    if (!worker) return Promise.reject(new Error("CPython not ready"));
    if (active) return Promise.reject(new Error("already running"));
    var runId = ++runSeq;
    return new Promise(function (resolve) {
      active = { runId: runId, resolve: resolve, onPrint: onPrint };
      payload.type = type;
      payload.runId = runId;
      worker.postMessage(payload);
    });
  }

  window.JadexCPython.run = function (src, files, onPrint) {
    return dispatch("run", { src: src, files: files }, onPrint);
  };
  window.JadexCPython.repl = function (src, onPrint) {
    return dispatch("repl", { src: src }, onPrint);
  };
  window.JadexCPython.busy = function () { return !!active; };

  // The whole point of the worker: this actually stops a runaway loop.
  window.JadexCPython.interrupt = function () {
    if (!active) return false;
    if (interruptBuffer) {
      interruptBuffer[0] = 2;          // SIGINT -> KeyboardInterrupt in CPython
      return true;
    }
    // No cross-origin isolation: the only honest option is a hard restart.
    if (worker) {
      try { worker.terminate(); } catch (e) {}
      worker = null;
      state = "idle";
      bootPromise = null;
      window.JadexCPython.ready = false;
      if (active && active.resolve) {
        active.resolve({ type: "done", error: "Stopped — restarting CPython\n", interrupted: true });
      }
      active = null;
      chip("CPython restarting…", "jade loading");
      window.JadexCPython.ensure();
      return true;
    }
    return false;
  };
})();
