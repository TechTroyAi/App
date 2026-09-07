(function () {
  window.JadexCPython = { ready: false, engine: "subset", py: null };
  window.jadexStdinQueue = [];

  function setStatus(text) {
    var el = document.getElementById("status-lang");
    if (el) el.textContent = text;
    var msg = document.getElementById("splash-msg");
    if (msg) msg.textContent = text;
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
    setStatus("Loading CPython…");
    await loadScript("pyodide/pyodide.js");
    try { await loadScript("pyodide/pyodide.asm.js"); } catch (e) {}
    var indexURL = new URL("pyodide/", window.location.href).href;
    var loader = window.loadPyodide;
    if (loader && loader.loadPyodide) loader = loader.loadPyodide;
    var py = await loader({
      indexURL: indexURL,
      stdin: readStdin
    });
    window.JadexCPython = { ready: true, engine: "cpython", py: py, version: py.version };
    setStatus("CPython " + String(py.version).split(" ")[0]);
    var splash = document.getElementById("splash");
    if (splash) splash.classList.add("gone");
  }

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

  boot().catch(function (err) {
    console.error("CPython boot failed", err);
    setStatus("Subset · CPython offline");
    window.JadexCPython.ready = false;
    window.JadexCPython.engine = "subset";
    var splash = document.getElementById("splash");
    if (splash) splash.classList.add("gone");
  });
})();
