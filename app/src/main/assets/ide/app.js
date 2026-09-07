(function () {
  var SNIPS = {
    hello: 'print("Hello from Jadex")\nprint(2 + 2)\n',
    loop: "for i in range(1, 6):\n    print(i, i * i)\n",
    func: "def greet(name):\n    return \"Hi \" + name\n\nprint(greet(\"Jadex\"))\n",
    list: "nums = [3, 1, 4, 1, 5]\nprint(sorted(nums))\nprint(sum(nums))\nuser = {\"lang\": \"Python\", \"level\": 1}\nprint(user[\"lang\"])\n",
    class: "class Point:\n    def __init__(self, x, y):\n        self.x = x\n        self.y = y\n\np = Point(2, 5)\nprint(p.x, p.y)\n",
    input: "import math\nr = float(input(\"radius: \"))\nprint(\"area\", math.pi * r * r)\n",
    plot: "import jadex\njadex.plot([0, 1, 2, 3, 4], [0, 1, 4, 9, 16])\nprint(\"plotted\")\n",
    troy: "# Jadex · by Troy\n",
    test: "import unittest\n\nclass T(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(1 + 1, 2)\n\nif __name__ == '__main__':\n    unittest.main()\n"
  };

  var storeKey = "jadex-files-v1";
  var files = loadFiles();
  var current = files._active || "main.py";
  var fontSize = parseInt(localStorage.getItem("jadex-font") || "15", 10);
  var wrap = localStorage.getItem("jadex-wrap") === "1";
  var running = null;
  var undoStack = [];
  var redoStack = [];
  var horiz = localStorage.getItem("jadex-horiz") === "1" || (window.innerWidth > window.innerHeight);

  var code = document.getElementById("code");
  var highlight = document.getElementById("highlight");
  var gutter = document.getElementById("gutter");
  var consoleEl = document.getElementById("console");
  var fileList = document.getElementById("file-list");
  var tabs = document.getElementById("tabs");
  var chip = document.getElementById("file-chip");
  var zoomLabel = document.getElementById("zoom-label");
  var statusPos = document.getElementById("status-pos");
  var statusWrap = document.getElementById("status-wrap");
  var statusKb = document.getElementById("status-kb");
  var codeScroll = document.getElementById("code-scroll");
  var editorWrap = document.getElementById("editor-wrap");
  var pane = document.getElementById("console-pane");
  var app = document.getElementById("app");

  function loadFiles() {
    try {
      var raw = localStorage.getItem(storeKey);
      if (raw) return JSON.parse(raw);
    } catch (e) {}
    return {
      _active: "main.py",
      "main.py": 'print("Jadex")\nprint("by Troy")\nprint("black jade · keyboard-safe · wide code")\n\nfor n in range(3):\n    print("ready", n)\n'
    };
  }
  var _saveTimer = 0;
  function persist() {
    _saveTimer = 0;
    try { localStorage.setItem(storeKey, JSON.stringify(files)); } catch (e) {}
  }
  // Keep the in-memory model instant; batch the expensive serialize.
  function saveFiles(immediate) {
    files[current] = code.value;
    files._active = current;
    if (immediate) {
      if (_saveTimer) { clearTimeout(_saveTimer); }
      persist();
      return;
    }
    if (_saveTimer) return;
    _saveTimer = setTimeout(persist, 400);
  }
  window.addEventListener("pagehide", function () { saveFiles(true); });
  document.addEventListener("visibilitychange", function () {
    if (document.visibilityState === "hidden") saveFiles(true);
  });
  function pushUndo() {
    undoStack.push(code.value);
    if (undoStack.length > 80) undoStack.shift();
    redoStack = [];
  }

  function applyFont() {
    document.documentElement.style.setProperty("--font", fontSize + "px");
    zoomLabel.textContent = fontSize + "px";
    localStorage.setItem("jadex-font", String(fontSize));
    syncEditor();
  }
  function applyWrap() {
    code.style.whiteSpace = wrap ? "pre-wrap" : "pre";
    highlight.style.whiteSpace = wrap ? "pre-wrap" : "pre";
    code.wrap = wrap ? "soft" : "off";
    statusWrap.textContent = wrap ? "Wrap" : "No wrap";
    localStorage.setItem("jadex-wrap", wrap ? "1" : "0");
    document.getElementById("btn-wrap").textContent = wrap ? "no wrap" : "wrap";
  }
  function applyHoriz() {
    document.body.classList.toggle("horiz", horiz);
    localStorage.setItem("jadex-horiz", horiz ? "1" : "0");
    document.getElementById("btn-horiz").textContent = horiz ? "stack" : "wide";
  }

  var _hlText = null;      // last text we painted
  var _gutterLines = -1;   // last line count we built
  var _hlFrame = 0;

  // Heavy: full-file syntax paint. Coalesced to one per animation frame and
  // skipped entirely when the text has not changed.
  function escHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
  var _hlRange = "";
  function paintHighlight(force) {
    var src = code.value;
    var lines = src.split("\n");
    // Virtualized: only the visible window is tokenized. Off-screen lines are
    // emitted as escaped text so line boxes (and therefore caret alignment,
    // scroll height and the gutter) stay pixel-identical.
    var lh = fontSize * 1.55;
    var top = code.scrollTop || 0;
    var viewH = (editorWrap.clientHeight || 400);
    var pad = 80;
    var first = Math.max(0, Math.floor(top / lh) - pad);
    var last = Math.min(lines.length, Math.ceil((top + viewH) / lh) + pad);
    var rangeKey = first + ":" + last;
    if (!force && src === _hlText && rangeKey === _hlRange) return;
    _hlText = src;
    _hlRange = rangeKey;
    var out = [];
    for (var i = 0; i < lines.length; i++) {
      if (i >= first && i < last) out.push(TroyPython.highlightLine(lines[i]));
      else out.push(escHtml(lines[i]));
    }
    highlight.innerHTML = out.join("\n") + "\n";
  }
  function schedulePaint() {
    if (_hlFrame) return;
    _hlFrame = requestAnimationFrame(function () { _hlFrame = 0; paintHighlight(); });
  }

  function syncEditor() {
    schedulePaint();
    var lines = code.value.split("\n");
    if (lines.length !== _gutterLines) {
      _gutterLines = lines.length;
      var g = "";
      for (var i = 0; i < lines.length; i++) g += (i + 1) + "\n";
      gutter.textContent = g || "1\n";
    }
    var h = Math.max(editorWrap.clientHeight - 8, lines.length * fontSize * 1.55 + 48);
    code.style.height = h + "px";
    highlight.style.height = code.style.height;
    highlight.style.width = Math.max(code.scrollWidth, codeScroll.clientWidth) + "px";
    code.style.width = highlight.style.width;
    var pos = code.selectionStart || 0;
    var before = code.value.slice(0, pos);
    var ln = before.split("\n").length;
    var col = before.length - before.lastIndexOf("\n");
    statusPos.textContent = "Ln " + ln + ", Col " + col;
    chip.textContent = current;
  }

  function renderFiles() {
    fileList.innerHTML = "";
    tabs.innerHTML = "";
    Object.keys(files).forEach(function (name) {
      if (name[0] === "_") return;
      var li = document.createElement("li");
      li.appendChild(document.createTextNode(name));
      if (name === current) li.className = "active";
      li.onclick = function () { openFile(name); };
      var del = document.createElement("span");
      del.className = "del";
      del.textContent = "×";
      del.onclick = function (e) {
        e.stopPropagation();
        if (name === "main.py") return;
        delete files[name];
        if (current === name) openFile("main.py");
        else { saveFiles(); renderFiles(); }
      };
      li.appendChild(del);
      fileList.appendChild(li);
      var tab = document.createElement("div");
      tab.className = "tab" + (name === current ? " active" : "");
      tab.textContent = name;
      tab.onclick = function () { openFile(name); };
      tabs.appendChild(tab);
    });
  }

  function openFile(name) {
    saveFiles();
    current = name;
    code.value = files[name] || "";
    renderFiles();
    syncEditor();
    saveFiles();
  }

  function insertText(s, wrapPair) {
    pushUndo();
    var a = code.selectionStart, b = code.selectionEnd;
    var sel = code.value.slice(a, b);
    var out = wrapPair && sel ? s[0] + sel + s[s.length - 1] : s;
    if (s === "()" || s === "[]" || s === "{}") {
      out = sel ? s[0] + sel + s[1] : s;
    }
    code.value = code.value.slice(0, a) + out + code.value.slice(b);
    var caret = a + (sel ? out.length : (s.length === 2 && "()[]{}".indexOf(s) >= 0 ? 1 : out.length));
    code.selectionStart = code.selectionEnd = caret;
    code.focus();
    syncEditor();
    saveFiles();
  }

  function unindent() {
    pushUndo();
    var a = code.selectionStart;
    var lineStart = code.value.lastIndexOf("\n", a - 1) + 1;
    var line = code.value.slice(lineStart, a);
    var cut = line.indexOf("    ") === 0 ? 4 : (line.indexOf(" ") === 0 ? 1 : 0);
    if (!cut) return;
    code.value = code.value.slice(0, lineStart) + code.value.slice(lineStart + cut);
    code.selectionStart = code.selectionEnd = a - cut;
    syncEditor();
  }

  document.querySelectorAll(".act").forEach(function (btn) {
    btn.onclick = function () {
      document.querySelectorAll(".act").forEach(function (b) { b.classList.remove("active"); });
      btn.classList.add("active");
      ["files", "search", "snippets", "help"].forEach(function (id) {
        document.getElementById("panel-" + id).classList.toggle("hidden", btn.getAttribute("data-panel") !== id);
      });
      document.querySelector(".side-head").textContent = btn.getAttribute("data-panel").toUpperCase();
      document.body.classList.remove("side-collapsed");
    };
  });

  document.getElementById("btn-new-file").onclick = function () {
    var name = prompt("File name", "script.py");
    if (!name) return;
    if (!/\.py$/.test(name)) name += ".py";
    files[name] = "# " + name + "\n";
    openFile(name);
  };

  document.querySelectorAll(".snip").forEach(function (b) {
    b.onclick = function () {
      insertText(SNIPS[b.getAttribute("data-snip")] || "");
    };
  });

  document.getElementById("search-q").oninput = function () {
    var q = this.value.toLowerCase();
    var hits = document.getElementById("search-hits");
    hits.innerHTML = "";
    if (!q) return;
    Object.keys(files).forEach(function (name) {
      if (name[0] === "_") return;
      (files[name] || "").split("\n").forEach(function (line, i) {
        if (line.toLowerCase().indexOf(q) >= 0) {
          var li = document.createElement("li");
          li.textContent = name + ":" + (i + 1) + "  " + line.trim();
          li.onclick = function () { openFile(name); };
          hits.appendChild(li);
        }
      });
    });
  };

  code.addEventListener("input", function () { syncEditor(); saveFiles(); });
  var _scrollFrame = 0;
  code.addEventListener("scroll", function () {
    if (_scrollFrame) return;
    _scrollFrame = requestAnimationFrame(function () {
      _scrollFrame = 0;
      highlight.style.transform = "translate(" + (-code.scrollLeft) + "px," + (-code.scrollTop) + "px)";
      gutter.scrollTop = code.scrollTop;
      paintHighlight();   // colour whatever just scrolled into view
    });
  }, { passive: true });
  function syncCaret() {
    var pos = code.selectionStart || 0;
    var before = code.value.slice(0, pos);
    var ln = before.split("\n").length;
    statusPos.textContent = "Ln " + ln + ", Col " + (before.length - before.lastIndexOf("\n"));
  }
  var NAV = { ArrowUp: 1, ArrowDown: 1, ArrowLeft: 1, ArrowRight: 1, Home: 1, End: 1, PageUp: 1, PageDown: 1, Shift: 1, Control: 1, Alt: 1, Meta: 1 };
  code.addEventListener("keyup", function (e) {
    if (e && NAV[e.key]) { syncCaret(); return; }  // moving the caret is not an edit
    syncEditor();
  });
  code.addEventListener("click", syncCaret);
  code.addEventListener("keydown", function (e) {
    if (e.key === "Tab") {
      e.preventDefault();
      if (e.shiftKey) unindent();
      else insertText("    ");
    }
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
      e.preventDefault();
      runCode();
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "z") {
      e.preventDefault();
      if (undoStack.length) {
        redoStack.push(code.value);
        code.value = undoStack.pop();
        syncEditor();
      }
    }
    if (e.key === "Enter") {
      var start = code.selectionStart;
      var line = code.value.slice(0, start).split("\n").pop();
      var m = line.match(/^(\s*)/);
      var extra = /:\s*$/.test(line) ? "    " : "";
      if (m) {
        setTimeout(function () {
          var pos = code.selectionStart;
          var indent = m[1] + extra;
          code.value = code.value.slice(0, pos) + indent + code.value.slice(pos);
          code.selectionStart = code.selectionEnd = pos + indent.length;
          syncEditor();
        }, 0);
      }
    }
  });

  document.getElementById("btn-zoom-in").onclick = function () { fontSize = Math.min(28, fontSize + 1); applyFont(); };
  document.getElementById("btn-zoom-out").onclick = function () { fontSize = Math.max(11, fontSize - 1); applyFont(); };
  document.getElementById("btn-wrap").onclick = function () { wrap = !wrap; applyWrap(); };
  document.getElementById("btn-kb").onclick = function () { code.blur(); };
  document.getElementById("btn-horiz").onclick = function () { horiz = !horiz; applyHoriz(); };
  document.getElementById("btn-side").onclick = function () {
    document.body.classList.toggle("side-collapsed");
  };
  document.getElementById("btn-clear").onclick = function () { consoleEl.textContent = ""; };
  document.getElementById("btn-stop").onclick = function () {
    if (running && running.stopped !== undefined) running.stopped = true;
    // Worker-backed CPython: this genuinely breaks a runaway loop.
    if (window.JadexCPython && JadexCPython.busy && JadexCPython.busy()) {
      JadexCPython.interrupt();
    }
  };

  document.getElementById("ime-bar").addEventListener("mousedown", function (e) { e.preventDefault(); });
  document.getElementById("ime-bar").addEventListener("click", function (e) {
    var btn = e.target.closest("button");
    if (!btn) return;
    var cmd = btn.getAttribute("data-cmd");
    var ins = btn.getAttribute("data-ins");
    if (cmd === "hide") code.blur();
    else if (cmd === "run") runCode();
    else if (cmd === "undo") {
      if (undoStack.length) { redoStack.push(code.value); code.value = undoStack.pop(); syncEditor(); }
    } else if (cmd === "redo") {
      if (redoStack.length) { undoStack.push(code.value); code.value = redoStack.pop(); syncEditor(); }
    } else if (cmd === "unindent") unindent();
    else if (ins) insertText(ins);
  });

  var pinch = 0;
  editorWrap.addEventListener("touchstart", function (e) {
    if (e.touches.length === 2) {
      var dx = e.touches[0].clientX - e.touches[1].clientX;
      var dy = e.touches[0].clientY - e.touches[1].clientY;
      pinch = Math.hypot(dx, dy);
    }
  }, { passive: true });
  editorWrap.addEventListener("touchmove", function (e) {
    if (e.touches.length === 2 && pinch) {
      var dx = e.touches[0].clientX - e.touches[1].clientX;
      var dy = e.touches[0].clientY - e.touches[1].clientY;
      var d = Math.hypot(dx, dy);
      if (Math.abs(d - pinch) > 24) {
        fontSize = Math.max(11, Math.min(28, fontSize + (d > pinch ? 1 : -1)));
        pinch = d;
        applyFont();
      }
    }
  }, { passive: true });

  var handle = document.getElementById("split-handle");
  handle.addEventListener("touchstart", dragStart, { passive: false });
  handle.addEventListener("mousedown", dragStart);
  function dragStart(e) {
    e.preventDefault();
    function move(ev) {
      var pt = ev.touches ? ev.touches[0] : ev;
      if (document.body.classList.contains("horiz") || window.innerWidth > window.innerHeight) {
        var w = window.innerWidth - pt.clientX;
        pane.style.width = Math.max(160, Math.min(window.innerWidth * 0.5, w)) + "px";
        pane.style.height = "auto";
      } else {
        var h = window.innerHeight - pt.clientY;
        pane.style.height = Math.max(64, Math.min(window.innerHeight * 0.42, h)) + "px";
      }
    }
    function up() {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("touchmove", move);
      window.removeEventListener("mouseup", up);
      window.removeEventListener("touchend", up);
    }
    window.addEventListener("mousemove", move);
    window.addEventListener("touchmove", move, { passive: false });
    window.addEventListener("mouseup", up);
    window.addEventListener("touchend", up);
  }

  // A tight print() loop used to create one DOM node + one scroll reflow per
  // call. Batch everything into a single frame, and coalesce runs of plain text.
  var _logQueue = [];
  var _logFrame = 0;
  function flushLog() {
    _logFrame = 0;
    if (!_logQueue.length) return;
    var frag = document.createDocumentFragment();
    var buf = "", bufCls = null, i;
    function emit() {
      if (!buf) return;
      var span = document.createElement("span");
      if (bufCls) span.className = bufCls;
      span.textContent = buf;
      frag.appendChild(span);
      buf = "";
    }
    for (i = 0; i < _logQueue.length; i++) {
      var it = _logQueue[i];
      if (it.cls !== bufCls) { emit(); bufCls = it.cls; }
      buf += it.text;
    }
    emit();
    _logQueue.length = 0;
    consoleEl.appendChild(frag);
    // Trim scrollback so a runaway loop can't grow the DOM without bound.
    while (consoleEl.childNodes.length > 600) consoleEl.removeChild(consoleEl.firstChild);
    consoleEl.scrollTop = consoleEl.scrollHeight;
  }
  function log(text, cls) {
    _logQueue.push({ text: text, cls: cls || null });
    if (!_logFrame) _logFrame = requestAnimationFrame(flushLog);
  }

  function runCode() {
    saveFiles();
    consoleEl.textContent = "";
    log(">>> " + current + "\n", "ok");
    try {
      running = TroyPython.run(code.value, {
        print: function (s) { log(s); },
        input: function (prompt) { return window.prompt(prompt, "") || ""; }
      });
      log("\n[done]\n", "ok");
    } catch (err) {
      var msg = (err && err.message) ? err.message : String(err);
      if (err && err.line) msg += "  (line " + err.line + ")";
      log(msg + "\n", "err");
    }
  }
  document.getElementById("btn-run").onclick = runCode;

  function layoutForKeyboard() {
    var vv = window.visualViewport;
    var layoutH = window.innerHeight;
    var vis = vv ? vv.height : layoutH;
    var kb = Math.max(0, layoutH - vis);
    var ratio = kb / Math.max(layoutH, 1);
    var open = kb > 90;
    document.body.classList.toggle("kb-open", open);
    // Cap usable chrome to 55%+ editor: never let IME-owned layout exceed 45%.
    var used = Math.min(vis, layoutH * 0.55 + (open ? 0 : layoutH * 0.45));
    if (open) {
      var keep = Math.max(layoutH * 0.55, vis);
      app.style.height = Math.min(keep, vis) + "px";
      document.documentElement.style.setProperty("--vvh", vis + "px");
      statusKb.textContent = "KB " + Math.round(ratio * 100) + "% capped";
    } else {
      app.style.height = "";
      document.documentElement.style.setProperty("--vvh", "100dvh");
      statusKb.textContent = "KB safe";
    }
    syncEditor();
  }
  if (window.visualViewport) {
    window.visualViewport.addEventListener("resize", layoutForKeyboard);
    window.visualViewport.addEventListener("scroll", layoutForKeyboard);
  }
  window.addEventListener("resize", function () {
    if (window.innerWidth > window.innerHeight) {
      horiz = true;
      applyHoriz();
    }
    layoutForKeyboard();
  });

  var breakpoints = {};
  var acEl = document.getElementById("ac");
  var minimap = document.getElementById("minimap");
  var debugLog = document.getElementById("debug-log");
  var problemsList = document.getElementById("problems-list");
  var outlineList = document.getElementById("outline-list");

  function gotoLine(n) {
    var lines = code.value.split("\n");
    var pos = 0;
    for (var i = 0; i < n - 1 && i < lines.length; i++) pos += lines[i].length + 1;
    code.focus();
    code.selectionStart = code.selectionEnd = pos;
    syncEditor();
  }

  function refreshIntel() {
    var probs = TroyPython.check(code.value);
    problemsList.innerHTML = "";
    if (!probs.length) {
      var ok = document.createElement("li");
      ok.textContent = "No problems";
      ok.style.color = "#2ee59b";
      problemsList.appendChild(ok);
    }
    probs.forEach(function (p) {
      var li = document.createElement("li");
      li.textContent = "L" + p.line + "  " + p.message;
      li.onclick = function () { gotoLine(p.line); };
      problemsList.appendChild(li);
    });
    outlineList.innerHTML = "";
    TroyPython.outline(code.value).forEach(function (o) {
      var li = document.createElement("li");
      li.textContent = (o.kind === "cls" ? "class " : "def ") + o.name;
      li.onclick = function () { gotoLine(o.line); };
      outlineList.appendChild(li);
    });
    drawMinimap();
  }

  function drawMinimap() {
    if (!minimap) return;
    if (minimap.offsetParent === null) return; // hidden: don't pay for pixels nobody sees
    var h = editorWrap.clientHeight || 200;
    minimap.height = h;
    minimap.width = 56;
    var ctx = minimap.getContext("2d");
    ctx.fillStyle = "#080c0a";
    ctx.fillRect(0, 0, 56, h);
    var lines = code.value.split("\n");
    var scale = Math.max(0.4, h / Math.max(lines.length, 1));
    lines.forEach(function (ln, i) {
      ctx.fillStyle = /^\s*#/.test(ln) ? "#4d6b5c" : (/def |class /.test(ln) ? "#2ee59b" : "#1a3d32");
      ctx.fillRect(4, i * scale, Math.min(48, ln.length * 0.7), Math.max(1, scale - 0.3));
    });
  }

  gutter.addEventListener("click", function (e) {
    var y = e.offsetY;
    var line = Math.max(1, Math.floor(y / (fontSize * 1.55)) + 1);
    if (breakpoints[line]) delete breakpoints[line];
    else breakpoints[line] = true;
    syncEditor();
  });

  var oldSync = syncEditor;
  var _bpSig = null;
  var _intelTimer = 0;
  syncEditor = function () {
    oldSync();
    var lines = code.value.split("\n");
    var sig = lines.length + "|" + Object.keys(breakpoints).join(",");
    if (sig !== _bpSig) {
      _bpSig = sig;
      var g = "";
      for (var i = 0; i < lines.length; i++) {
        g += (breakpoints[i + 1] ? "●" : "") + (i + 1) + "\n";
      }
      gutter.textContent = g || "1\n";
    }
    // Lint + outline + minimap are not per-keystroke concerns.
    if (_intelTimer) clearTimeout(_intelTimer);
    _intelTimer = setTimeout(function () { _intelTimer = 0; refreshIntel(); }, 220);
    maybeComplete();
  };

  function wordPrefix() {
    var pos = code.selectionStart;
    var left = code.value.slice(0, pos);
    var m = left.match(/[A-Za-z_][A-Za-z0-9_]*$/);
    return m ? m[0] : "";
  }
  function maybeComplete() {
    var pre = wordPrefix();
    if (pre.length < 2) { acEl.classList.add("hidden"); return; }
    var items = TroyPython.complete(code.value, pre);
    if (!items.length) { acEl.classList.add("hidden"); return; }
    acEl.innerHTML = "";
    items.forEach(function (it, i) {
      var li = document.createElement("li");
      li.textContent = it;
      if (i === 0) li.className = "active";
      li.onclick = function () { applyComplete(it); };
      acEl.appendChild(li);
    });
    acEl.classList.remove("hidden");
  }
  function applyComplete(it) {
    var pre = wordPrefix();
    var pos = code.selectionStart;
    code.value = code.value.slice(0, pos - pre.length) + it + code.value.slice(pos);
    code.selectionStart = code.selectionEnd = pos - pre.length + it.length;
    acEl.classList.add("hidden");
    code.focus();
    oldSync();
    saveFiles();
  }

  var origRun = runCode;
  var _busy = false;
  function setBusy(on) {
    _busy = on;
    var b = document.getElementById("btn-run");
    if (b) { b.classList.toggle("busy", on); b.textContent = on ? "… running" : "▶ Run"; }
  }
  runCode = async function (debug) {
    if (_busy) return;              // no double-fire from ▶ / IME bar / Ctrl+Enter
    setBusy(true);
    var _t0 = (performance && performance.now) ? performance.now() : Date.now();
    function done() {
      var ms = ((performance && performance.now) ? performance.now() : Date.now()) - _t0;
      log("[done in " + (ms < 1000 ? Math.round(ms) + "ms" : (ms / 1000).toFixed(2) + "s") + "]\n", "ok");
      setBusy(false);
    }
    try {
      return await realRun(debug, done);
    } catch (e) {
      setBusy(false);
      throw e;
    }
  };
  async function realRun(debug, done) {
    saveFiles(true);
    _logQueue.length = 0;
    consoleEl.textContent = "";
    if (debugLog) debugLog.textContent = "";
    var ws = {};
    Object.keys(files).forEach(function (k) { if (k[0] !== "_") ws[k] = files[k]; });
    ws[current] = code.value;
    var cp = window.JadexCPython;
    if (cp && !debug && !cp.ready) {
      // Editor was never blocked; only Run waits, and it says so in one line.
      log("… CPython is almost ready — one moment\n", "ok");
      try { await cp.ensure(); } catch (e) {}
    }
    if (cp && cp.ready && !debug) {
      log(">>> CPython · " + current + "\n", "ok");
      try {
        var res = await cp.run(code.value, ws, function (t, stream) {
          log(t, stream === "err" ? "err" : null);
        });
        if (res && res.error) {
          log(res.error, res.interrupted ? "ok" : "err");
          if (res.line) {
            // Real CPython traceback, mapped back onto the editor.
            gotoLine(res.line);
            if (window.JadexStudio && JadexStudio.markError) JadexStudio.markError(res.line);
          }
        }
        done();
      } catch (err) {
        log((err && err.message ? err.message : String(err)) + "\n", "err");
        setBusy(false);
      }
      return;
    }
    if (cp && cp.state === "failed" && !debug) {
      log("[subset engine · tap Run again to retry CPython]\n", "ok");
    }
    log(">>> " + (debug ? "debug " : "") + current + "\n", "ok");
    try {
      running = TroyPython.run(code.value, {
        print: function (s) { log(s); },
        input: function (prompt) { return window.prompt(prompt, "") || ""; },
        files: ws,
        onLine: debug ? function (line, locals) {
          if (!breakpoints[line]) return;
          var dump = [];
          for (var k in locals) {
            if (typeof locals[k] === "function") continue;
            try { dump.push(k + " = " + String(locals[k]).slice(0, 80)); } catch (e) {}
          }
          debugLog.textContent += "break L" + line + "\n  " + dump.join("\n  ") + "\n";
        } : null
      });
      done();
    } catch (err) {
      var msg = (err && err.message) ? err.message : String(err);
      var line = err && err.line;
      if (line) msg += "  (line " + line + ")";
      log(msg + "\n", "err");
      setBusy(false);
      if (line) {
        gotoLine(line);
        if (window.JadexStudio && JadexStudio.markError) JadexStudio.markError(line);
      }
    }
  }
  document.getElementById("btn-run").onclick = function () { runCode(false); };
  document.getElementById("btn-debug").onclick = function () { runCode(true); };
  window.runCode = runCode;
  window.gotoLine = gotoLine;

  document.getElementById("repl-in").addEventListener("keydown", async function (e) {
    if (e.key !== "Enter") return;
    e.preventDefault();
    var line = this.value;
    this.value = "";
    log(">>> " + line + "\n", "ok");
    try {
      if (window.JadexCPython && !JadexCPython.ready) {
        try { await JadexCPython.ensure(); } catch (e) {}
      }
      if (window.JadexCPython && JadexCPython.ready) {
        var r = await JadexCPython.repl(line, function (t, stream) {
          log(t, stream === "err" ? "err" : null);
        });
        if (r && r.error) log(r.error, r.interrupted ? "ok" : "err");
      } else {
        TroyPython.run(line, {
          print: function (s) { log(s); },
          input: function (p) { return window.prompt(p, "") || ""; },
          files: files
        });
      }
    } catch (err) {
      log((err.message || err) + "\n", "err");
    }
  });

  var findbar = document.getElementById("findbar");
  function openFind() {
    findbar.classList.remove("hidden");
    document.getElementById("find-q").focus();
  }
  document.getElementById("find-close").onclick = function () { findbar.classList.add("hidden"); };
  document.getElementById("find-next").onclick = function () {
    var q = document.getElementById("find-q").value;
    if (!q) return;
    var from = code.selectionEnd;
    var idx = code.value.indexOf(q, from);
    if (idx < 0) idx = code.value.indexOf(q);
    if (idx >= 0) {
      code.focus();
      code.selectionStart = idx;
      code.selectionEnd = idx + q.length;
    }
  };
  document.getElementById("find-repl").onclick = function () {
    var q = document.getElementById("find-q").value;
    var r = document.getElementById("repl-q").value;
    if (!q) return;
    pushUndo();
    code.value = code.value.replace(q, r);
    syncEditor();
    saveFiles();
  };

  var COMMANDS = [
    { name: "Run Python file", run: function () { runCode(false); } },
    { name: "Debug (breakpoints)", run: function () { runCode(true); } },
    { name: "Find", run: openFind },
    { name: "Toggle wide layout", run: function () { horiz = !horiz; applyHoriz(); } },
    { name: "Hide keyboard", run: function () { code.blur(); } },
    { name: "New file", run: function () { document.getElementById("btn-new-file").click(); } },
    { name: "Toggle wrap", run: function () { wrap = !wrap; applyWrap(); } },
    { name: "Zoom in", run: function () { fontSize = Math.min(28, fontSize + 1); applyFont(); } },
    { name: "Zoom out", run: function () { fontSize = Math.max(11, fontSize - 1); applyFont(); } }
  ];
  var pal = document.getElementById("palette");
  var palQ = document.getElementById("palette-q");
  var palList = document.getElementById("palette-list");
  function openPalette() {
    pal.classList.remove("hidden");
    palQ.value = "";
    renderPal("");
    palQ.focus();
  }
  function renderPal(q) {
    palList.innerHTML = "";
    COMMANDS.filter(function (c) { return c.name.toLowerCase().indexOf(q.toLowerCase()) >= 0; }).forEach(function (c, i) {
      var li = document.createElement("li");
      li.textContent = c.name;
      if (i === 0) li.className = "active";
      li.onclick = function () { pal.classList.add("hidden"); c.run(); };
      palList.appendChild(li);
    });
  }
  palQ.oninput = function () { renderPal(this.value); };
  palQ.onkeydown = function (e) {
    if (e.key === "Escape") pal.classList.add("hidden");
    if (e.key === "Enter") {
      var first = palList.querySelector("li");
      if (first) first.click();
    }
  };
  document.getElementById("btn-cmd").onclick = openPalette;

  window.addEventListener("keydown", function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "p") {
      e.preventDefault();
      openPalette();
    }
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "f") {
      e.preventDefault();
      openFind();
    }
    if (e.key === "F5") {
      e.preventDefault();
      runCode(false);
    }
  });

  code.value = files[current] || "";
  applyFont();
  applyWrap();
  applyHoriz();
  renderFiles();
  syncEditor();
})();

// Editor first, WASM second: warm CPython in the background after first paint.
(function () {
  function warm() {
    if (window.JadexCPython && window.JadexCPython.prefetch) window.JadexCPython.prefetch(1200);
  }
  if (document.readyState === "complete") requestAnimationFrame(warm);
  else window.addEventListener("load", function () { requestAnimationFrame(warm); });
})();
