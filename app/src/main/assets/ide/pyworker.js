/* Jadex · CPython worker.
 *
 * Everything Python does happens here, off the UI thread. The main thread keeps
 * a SharedArrayBuffer whose byte 0 is the interrupt flag; CPython polls it and
 * raises KeyboardInterrupt, which is how Stop can kill a `while True:`.
 *
 * Protocol (main -> worker):
 *   {type:"boot"}                      -> {type:"ready", version} | {type:"bootfail", error}
 *   {type:"run", src, files, runId}    -> stream of {type:"out"|"plot"} then {type:"done"}
 *   {type:"repl", src, runId}          -> same, but the namespace persists
 *   {type:"interrupt-buffer", buffer}  -> installs the SharedArrayBuffer
 * Stdin is synchronous: the worker blocks on Atomics.wait while the main thread
 * fills the buffer, so input() behaves like real input().
 */

var py = null;
var interruptBuffer = null;
var stdinBuffer = null;   // [0]=state, [1]=length, [2..]=utf16 chars
var replGlobals = null;

function post(msg) { self.postMessage(msg); }

function readStdinSync(prompt) {
  if (!stdinBuffer) return "";
  var ctl = new Int32Array(stdinBuffer, 0, 2);
  post({ type: "stdin", prompt: String(prompt == null ? "" : prompt) });
  // Block this worker (never the UI thread) until the main thread answers.
  Atomics.store(ctl, 0, 0);
  Atomics.wait(ctl, 0, 0);
  var len = Atomics.load(ctl, 1);
  if (len <= 0) return "";
  var chars = new Uint16Array(stdinBuffer, 8, len);
  var out = "";
  for (var i = 0; i < len; i++) out += String.fromCharCode(chars[i]);
  return out;
}

var RUNNER = [
  "import sys, traceback, builtins",
  "class _Out:",
  "    def __init__(self, stream): self._s = stream",
  "    def write(self, s):",
  "        if s: jadex_print_js(s, self._s)",
  "        return len(s) if s else 0",
  "    def flush(self): pass",
  "    def isatty(self): return False",
  "sys.stdout = _Out('out')",
  "sys.stderr = _Out('err')",
  "def _input(prompt=''):",
  "    if prompt: jadex_print_js(str(prompt), 'out')",
  "    return jadex_readline_js(str(prompt))",
  "builtins.input = _input",
  ""
].join("\n");

var JADEX_MODULE = [
  "def plot(x, y=None, kind='line'):",
  "    if y is None:",
  "        y = list(x)",
  "        x = list(range(len(y)))",
  "    import json as _json",
  "    jadex_plot_js(_json.dumps({'x': [float(v) for v in x], 'y': [float(v) for v in y], 'kind': kind}))",
  ""
].join("\n");

async function boot() {
  importScripts("pyodide/pyodide.js");
  var loader = self.loadPyodide;
  if (loader && loader.loadPyodide) loader = loader.loadPyodide;
  py = await loader({ indexURL: new URL("pyodide/", self.location.href).href });

  if (interruptBuffer) py.setInterruptBuffer(interruptBuffer);
  py.globals.set("jadex_print_js", function (s, stream) {
    post({ type: "out", text: String(s), stream: stream === "err" ? "err" : "out" });
  });
  py.globals.set("jadex_plot_js", function (s) { post({ type: "plot", data: String(s) }); });
  py.globals.set("jadex_readline_js", readStdinSync);
  py.FS.writeFile("jadex.py", JADEX_MODULE);
  await py.runPythonAsync(RUNNER);
  return py.version;
}

function syncFiles(files) {
  Object.keys(files || {}).forEach(function (name) {
    if (!name || name.charAt(0) === "_") return;
    try { py.FS.writeFile(name, files[name] || ""); } catch (e) {}
  });
}

// A traceback the editor can act on: pull out the line numbers in _jadex_run.py.
function formatError(err) {
  var text = (err && err.message) ? err.message : String(err);
  var line = 0;
  var re = /File "(?:\/)?_jadex_run\.py", line (\d+)/g;
  var m;
  while ((m = re.exec(text)) !== null) line = parseInt(m[1], 10);
  if (/KeyboardInterrupt/.test(text)) {
    return { text: "KeyboardInterrupt — stopped\n", line: line, interrupted: true };
  }
  return { text: text.replace(/^PythonError:\s*/, "") + "\n", line: line, interrupted: false };
}

self.onmessage = async function (e) {
  var msg = e.data || {};

  if (msg.type === "interrupt-buffer") {
    interruptBuffer = msg.buffer;
    stdinBuffer = msg.stdin;
    if (py) py.setInterruptBuffer(interruptBuffer);
    return;
  }

  if (msg.type === "boot") {
    try {
      var version = await boot();
      post({ type: "ready", version: version });
    } catch (err) {
      post({ type: "bootfail", error: String(err && err.message ? err.message : err) });
    }
    return;
  }

  if (msg.type === "run" || msg.type === "repl") {
    if (!py) { post({ type: "done", runId: msg.runId, error: "CPython not ready" }); return; }
    var t0 = Date.now();
    try {
      if (msg.type === "run") {
        syncFiles(msg.files);
        py.FS.writeFile("_jadex_run.py", msg.src || "");
        replGlobals = py.globals.get("dict")();
        replGlobals.set("__name__", "__main__");
        await py.runPythonAsync(
          "exec(compile(open('_jadex_run.py', encoding='utf-8').read(), '_jadex_run.py', 'exec'), _jx_ns, _jx_ns)",
          { globals: (function () {
              var ns = py.globals.get("dict")();
              ns.set("_jx_ns", replGlobals);
              return ns;
            })() }
        );
      } else {
        // REPL keeps its namespace across lines, so `x = 5` then `print(x)` works.
        if (!replGlobals) {
          replGlobals = py.globals.get("dict")();
          replGlobals.set("__name__", "__main__");
        }
        await py.runPythonAsync(msg.src || "", { globals: replGlobals });
      }
      post({ type: "done", runId: msg.runId, ms: Date.now() - t0 });
    } catch (err) {
      var info = formatError(err);
      post({
        type: "done",
        runId: msg.runId,
        ms: Date.now() - t0,
        error: info.text,
        line: info.line,
        interrupted: info.interrupted
      });
    }
  }
};
