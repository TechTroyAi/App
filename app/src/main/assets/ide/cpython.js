(function () {
  // Jadex · lazy CPython. The editor never waits for WASM.
  // State: "idle" -> "loading" -> "ready" | "failed"
  var state = "idle";
  var bootPromise = null;

  window.jadexStdinQueue = [];
  window.JadexCPython = {
    ready: false,
    engine: "subset",
    py: null,
    get state() { return state; }
  };

  function chip(text, cls) {
    var el = document.getElementById("status-lang");
    if (!el) return;
    el.textContent = text;
    el.className = cls || "jade";
  }

  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement("script");
      s.src = src;
      s.onload = function () { resolve(); };
      s.onerror = function () { reject(new Error("failed " + src)); };
      document.head.appendChild(s);
    });
  }

  function readStdin(prompt) {
    if (window.jadexStdinQueue && window.jadexStdinQueue.length) {
      return window.jadexStdinQueue.shift();
    }
    var v = window.prompt(prompt || "input: ", "");
    return v == null ? "" : v;
  }

  async function boot() {
    chip("Loading CPython…", "jade loading");
    await loadScript("pyodide/pyodide.js");
    try { await loadScript("pyodide/pyodide.asm.js"); } catch (e) {}
    var indexURL = new URL("pyodide/", window.location.href).href;
    var loader = window.loadPyodide;
    if (loader && loader.loadPyodide) loader = loader.loadPyodide;
    var py = await loader({ indexURL: indexURL, stdin: readStdin });
    window.JadexCPython.ready = true;
    window.JadexCPython.engine = "cpython";
    window.JadexCPython.py = py;
    window.JadexCPython.version = py.version;
    return py;
  }

  // Idempotent. Safe to call from Run, from idle prefetch, from anywhere.
  window.JadexCPython.ensure = function () {
    if (state === "ready") return Promise.resolve(window.JadexCPython.py);
    if (bootPromise) return bootPromise;
    state = "loading";
    bootPromise = boot().then(function (py) {
      state = "ready";
      chip("CPython " + String(py.version).split(" ")[0], "jade");
      return py;
    }).catch(function (err) {
      console.error("CPython boot failed", err);
      state = "failed";
      bootPromise = null; // allow a retry on the next Run
      window.JadexCPython.ready = false;
      window.JadexCPython.engine = "subset";
      chip("Subset · tap Run again for CPython", "jade warn");
      return null;
    });
    return bootPromise;
  };

  // Warm up quietly after first paint, without blocking anything.
  window.JadexCPython.prefetch = function (delay) {
    var start = function () { window.JadexCPython.ensure(); };
    var go = function () {
      if (window.requestIdleCallback) window.requestIdleCallback(start, { timeout: 4000 });
      else setTimeout(start, 0);
    };
    setTimeout(go, delay == null ? 1200 : delay);
  };

  window.JadexCPython.run = async function (src, files, onPrint) {
    var py = window.JadexCPython.py;
    if (!py) throw new Error("CPython not ready");
    var FS = py.FS;
    Object.keys(files || {}).forEach(function (name) {
      if (!name || name.charAt(0) === "_") return;
      try { FS.writeFile(name, files[name] || ""); } catch (e) {}
    });
    FS.writeFile("_jadex_run.py", src || "");
    FS.writeFile("jadex.py", [
      "def plot(x, y=None, kind='line'):",
      "    if y is None:",
      "        y = list(x)",
      "        x = list(range(len(y)))",
      "    import json as _json",
      "    jadex_plot_js(_json.dumps({'x': [float(v) for v in x], 'y': [float(v) for v in y], 'kind': kind}))",
      ""
    ].join("\n"));
    py.globals.set("jadex_print_js", function (s) { if (onPrint) onPrint(String(s)); });
    py.globals.set("jadex_plot_js", function (s) {
      try {
        var data = typeof s === "string" ? JSON.parse(s) : s;
        if (window.JadexStudio && window.JadexStudio.plot) window.JadexStudio.plot(data);
      } catch (e) {}
    });
    py.globals.set("jadex_readline_js", function (p) { return readStdin(p); });
    var runner = [
      "import sys, traceback, builtins, json",
      "class _Out:",
      "    def write(self, s):",
      "        if s: jadex_print_js(s)",
      "    def flush(self):",
      "        pass",
      "sys.stdout = _Out()",
      "sys.stderr = _Out()",
      "def _input(prompt=''):",
      "    if prompt: jadex_print_js(prompt)",
      "    return jadex_readline_js(prompt)",
      "builtins.input = _input",
      "g = {'__name__': '__main__'}",
      "try:",
      "    exec(open('_jadex_run.py', encoding='utf-8').read(), g, g)",
      "except Exception:",
      "    traceback.print_exc()",
    ].join("\n");
    return await py.runPythonAsync(runner);
  };
})();
